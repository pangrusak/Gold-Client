package com.goldclient.analyzer;

import com.goldclient.model.BasicBlockModel;
import com.goldclient.model.InstructionModel;
import com.goldclient.model.IrMethodModel;
import com.goldclient.model.IrOperationModel;
import com.goldclient.model.MethodModel;
import java.util.*;
import org.objectweb.asm.Opcodes;

public final class IntermediateRepresentationAnalyzer {
  public IrMethodModel analyze(MethodModel method) {
    List<InstructionModel> instructions = method.instructions();
    List<BasicBlockModel> blocks = method.controlFlow().blocks();

    // First pass: solve the operand-stack dataflow across basic blocks.
    // A block may have multiple predecessors, so its incoming stack is the
    // merge of the stacks produced by those predecessors.
    //
    // IMPORTANT: exception edges do not carry the normal operand stack. JVM
    // exception handlers begin with the thrown exception on the stack.
    // Also, mergeValue() uses a bounded lattice so loops always converge.
    Map<Integer, List<String>> incomingStacks = new HashMap<>();
    Map<Integer, List<com.goldclient.model.ControlFlowEdgeModel>> incomingEdges =
        new HashMap<>();

    for (com.goldclient.model.ControlFlowEdgeModel edge : method.controlFlow().edges())
      incomingEdges.computeIfAbsent(edge.toBlock(), ignored -> new ArrayList<>()).add(edge);

    if (!blocks.isEmpty()) {
      incomingStacks.put(method.controlFlow().entryBlock(), List.of());

      Deque<Integer> worklist = new ArrayDeque<>();
      worklist.add(method.controlFlow().entryBlock());

      while (!worklist.isEmpty()) {
        int blockId = worklist.removeFirst();
        BasicBlockModel block = blocks.get(blockId);
        List<String> incoming = incomingStacks.getOrDefault(blockId, List.of());
        List<String> outgoing = simulateBlock(block, incoming, instructions);

        for (int successor : block.successors()) {
          List<String> propagated = isExceptionEdge(
              blockId, successor, method.controlFlow().edges())
                  ? List.of("<exception>")
                  : outgoing;

          List<String> existing = incomingStacks.get(successor);
          List<String> merged = existing == null
              ? List.copyOf(propagated)
              : mergeStacks(existing, propagated);

          if (existing == null || !existing.equals(merged)) {
            incomingStacks.put(successor, merged);
            worklist.addLast(successor);
          }
        }
      }
    }

    // Second pass: emit semantic operations using the solved stack state.
    List<IrOperationModel> operations = new ArrayList<>();
    for (BasicBlockModel block : blocks) {
      Deque<String> stack = new ArrayDeque<>(
          incomingStacks.getOrDefault(block.id(), List.of()));

      for (int i = block.startInstruction(); i < block.endInstruction(); i++) {
        InstructionModel instruction = instructions.get(i);
        translate(instruction, i, stack, operations);
      }
    }

    operations = annotateExceptionRegions(operations, method.tryCatchBlocks());
    return new IrMethodModel(method.name(), method.descriptor(), operations);
  }

  private static List<String> simulateBlock(
      BasicBlockModel block,
      List<String> incoming,
      List<InstructionModel> instructions) {
    Deque<String> stack = new ArrayDeque<>(incoming);

    // Reuse the exact opcode semantics used by the emitter, but discard the
    // temporary operations. This keeps dataflow and emitted IR in sync.
    for (int i = block.startInstruction(); i < block.endInstruction(); i++)
      translate(instructions.get(i), i, stack, new ArrayList<>());

    return List.copyOf(stack);
  }

  /**
   * Adds exception-region markers to the semantic stream without changing
   * instruction offsets. Markers are metadata consumed by the structured
   * source renderer; ordinary methods with no handlers remain unchanged.
   */
  private static List<IrOperationModel> annotateExceptionRegions(
      List<IrOperationModel> input,
      List<com.goldclient.model.TryCatchModel> handlers) {
    if (handlers.isEmpty() || input.isEmpty()) return input;
    List<IrOperationModel> result = new ArrayList<>(input);
    for (com.goldclient.model.TryCatchModel handler : handlers) {
      int start = findLabelOperation(result, handler.startLabel());
      int end = findLabelOperation(result, handler.endLabel());
      int catchIndex = findLabelOperation(result, handler.handlerLabel());
      if (start < 0 || end < 0 || catchIndex < 0 || start >= end || catchIndex <= start)
        continue;
      int source = result.get(start).sourceInstruction();
      result.add(start, new IrOperationModel("TRY_BEGIN", List.of(
          handler.startLabel(), handler.endLabel(), handler.handlerLabel(),
          normalizeExceptionType(handler.exceptionType())), source));
      end++;
      catchIndex++;
      result.add(end, new IrOperationModel("TRY_END", List.of(
          handler.startLabel(), handler.endLabel(), handler.handlerLabel(),
          normalizeExceptionType(handler.exceptionType())),
          result.get(Math.min(end, result.size() - 1)).sourceInstruction()));
      // Inserting TRY_END shifts the handler marker when the handler follows
      // the protected range. Keep CATCH_BEGIN aligned with the handler label;
      // otherwise the renderer starts the catch body before the real handler.
      if (end <= catchIndex) catchIndex++;
      result.add(catchIndex, new IrOperationModel("CATCH_BEGIN", List.of(
          handler.startLabel(), handler.endLabel(), handler.handlerLabel(),
          normalizeExceptionType(handler.exceptionType())),
          result.get(Math.min(catchIndex, result.size() - 1)).sourceInstruction()));
    }
    return List.copyOf(result);
  }

  private static int findLabelOperation(List<IrOperationModel> operations, String label) {
    for (int i = 0; i < operations.size(); i++) {
      IrOperationModel operation = operations.get(i);
      if ("LABEL".equals(operation.kind()) && !operation.operands().isEmpty()
          && label.equals(operation.operands().get(0))) return i;
    }
    return -1;
  }

  private static String multiArrayElementKind(String descriptor) {
    if (descriptor == null || descriptor.isBlank()) return "Object";
    int element = 0;
    while (element < descriptor.length() && descriptor.charAt(element) == '[') element++;
    if (element >= descriptor.length()) return "Object";
    return switch (descriptor.charAt(element)) {
      case 'Z' -> "boolean";
      case 'B' -> "byte";
      case 'C' -> "char";
      case 'S' -> "short";
      case 'I' -> "int";
      case 'J' -> "long";
      case 'F' -> "float";
      case 'D' -> "double";
      default -> "Object";
    };
  }

  private static String normalizeExceptionType(String type) {
    if (type == null || type.isBlank()) return "java.lang.Throwable";
    return type.replace('/', '.');
  }

  private static List<String> mergeStacks(List<String> left, List<String> right) {
    if (left.size() != right.size())
      return List.of("<unknown-stack>");

    List<String> merged = new ArrayList<>(left.size());
    for (int i = 0; i < left.size(); i++)
      merged.add(mergeValue(left.get(i), right.get(i)));

    return List.copyOf(merged);
  }

  private static String mergeValue(String left, String right) {
    if (Objects.equals(left, right))
      return left;

    // Keep the abstract value lattice bounded. Arbitrary symbolic expressions
    // grow forever around loops (x -> x+1 -> x+1+1 ...), preventing the
    // worklist solver from reaching a fixed point.
    return "<unknown>";
  }

  private static boolean isExceptionEdge(
      int fromBlock,
      int toBlock,
      List<com.goldclient.model.ControlFlowEdgeModel> edges) {
    return edges.stream().anyMatch(edge ->
        edge.fromBlock() == fromBlock
            && edge.toBlock() == toBlock
            && "EXCEPTION".equals(edge.kind()));
  }

  private static void translate(
      InstructionModel instruction,
      int sourceInstruction,
      Deque<String> stack,
      List<IrOperationModel> operations) {
    String opcode = instruction.opcodeName();

    if ("LABEL".equals(opcode)) {
      operations.add(op("LABEL", instruction.operands(), sourceInstruction));
      return;
    }

    if (isConstant(opcode)) {
      String value = constantValue(instruction);
      stack.push(value);
      operations.add(op("CONSTANT", List.of(value), sourceInstruction));
      return;
    }

    if (isLoad(opcode)) {
      String local = localName(instruction);
      stack.push(local);
      operations.add(op("LOCAL_READ", List.of(local), sourceInstruction));
      return;
    }

    if (isStore(opcode)) {
      String value = pop(stack);
      String local = localName(instruction);
      operations.add(op("LOCAL_WRITE", List.of(local, value), sourceInstruction));
      return;
    }

    if ("GETSTATIC".equals(opcode)) {
      String field = fieldName(instruction);
      stack.push(field);
      operations.add(op("FIELD_READ", List.of(field), sourceInstruction));
      return;
    }

    if ("GETFIELD".equals(opcode)) {
      String receiver = pop(stack);
      String field = fieldName(instruction);
      String expression = receiver + "." + field.substring(field.lastIndexOf('.') + 1);
      stack.push(expression);
      operations.add(op("FIELD_READ", List.of(receiver, field, expression), sourceInstruction));
      return;
    }

    if ("PUTSTATIC".equals(opcode)) {
      String value = pop(stack);
      operations.add(op("FIELD_WRITE", List.of(fieldName(instruction), value), sourceInstruction));
      return;
    }

    if ("PUTFIELD".equals(opcode)) {
      String value = pop(stack);
      String receiver = pop(stack);
      operations.add(op("FIELD_WRITE", List.of(receiver, fieldName(instruction), value), sourceInstruction));
      return;
    }

    if ("NEW".equals(opcode)) {
      String type = instruction.operands().isEmpty() ? "unknown" : instruction.operands().get(0);
      String object = "new " + type.replace('/', '.');
      stack.push(object);
      operations.add(op("OBJECT_CREATE", List.of(object), sourceInstruction));
      return;
    }

    if ("NEWARRAY".equals(opcode) || "ANEWARRAY".equals(opcode)) {
      String count = pop(stack);
      String type = instruction.operands().isEmpty() ? "unknown" : instruction.operands().get(0);
      String array = "new " + type.replace('/', '.') + "[" + count + "]";
      stack.push(array);
      operations.add(op("ARRAY_CREATE", List.of(array), sourceInstruction));
      return;
    }

    if ("MULTIANEWARRAY".equals(opcode)) {
      String descriptor = instruction.operands().isEmpty() ? "[Ljava/lang/Object;"
          : instruction.operands().get(0);
      int dimensions = instruction.operands().size() > 1
          ? Integer.parseInt(instruction.operands().get(1)) : 1;
      List<String> dimensionsValues = new ArrayList<>();
      for (int i = 0; i < dimensions; i++)
        dimensionsValues.add(0, pop(stack));
      String kind = multiArrayElementKind(descriptor);
      String array = "GoldClientRuntime.newMultiArray(\\\"" + kind + "\\\", ["
          + String.join(", ", dimensionsValues) + "])";
      stack.push(array);
      operations.add(op("ARRAY_CREATE", List.of(array), sourceInstruction));
      return;
    }

    if (isArrayLoad(opcode)) {
      String index = pop(stack);
      String array = pop(stack);
      String expression = array + "[" + index + "]";
      stack.push(expression);
      operations.add(op("ARRAY_READ", List.of(expression), sourceInstruction));
      return;
    }

    if (isArrayStore(opcode)) {
      String value = pop(stack);
      String index = pop(stack);
      String array = pop(stack);
      operations.add(op("ARRAY_WRITE", List.of(array, index, value), sourceInstruction));
      return;
    }

    if ("ARRAYLENGTH".equals(opcode)) {
      String array = pop(stack);
      String expression = array + ".length";
      stack.push(expression);
      operations.add(op("ARRAY_LENGTH", List.of(expression), sourceInstruction));
      return;
    }

    if (isStackOperation(opcode)) {
      translateStackOperation(opcode, stack, operations, sourceInstruction);
      return;
    }

    if (isMethodCall(opcode)) {
      translateMethodCall(instruction, stack, operations, sourceInstruction);
      return;
    }

    if (isArithmetic(opcode)) {
      translateArithmetic(opcode, stack, operations, sourceInstruction);
      return;
    }

    // JVM comparison instructions push -1, 0, or 1. Keep them as expressions
    // so subsequent branches consume a real comparison result.
    if ("LCMP".equals(opcode) || "FCMPL".equals(opcode) || "FCMPG".equals(opcode)
        || "DCMPL".equals(opcode) || "DCMPG".equals(opcode)) {
      String right = pop(stack);
      String left = pop(stack);
      String comparison;
      if (opcode.endsWith("CMPL")) {
        comparison = "((" + left + " < " + right + ") ? -1 : ("
            + left + " > " + right + ") ? 1 : ("
            + left + " == " + right + ") ? 0 : -1)";
      } else {
        comparison = "((" + left + " > " + right + ") ? 1 : ("
            + left + " < " + right + ") ? -1 : ("
            + left + " == " + right + ") ? 0 : 1)";
      }
      stack.push(comparison);
      operations.add(op("ARITHMETIC", List.of(comparison), sourceInstruction));
      return;
    }

    // JavaScript has no JVM monitor intrinsics. Preserve evaluation order and
    // report the semantic gap explicitly instead of leaving the stack dirty.
    if ("MONITORENTER".equals(opcode) || "MONITOREXIT".equals(opcode)) {
      operations.add(op("MONITOR", List.of(opcode, pop(stack)), sourceInstruction));
      return;
    }

    if ("IINC".equals(opcode)) {
      String local = instruction.operands().isEmpty() ? "local?" : "local" + instruction.operands().get(0);
      String amount = instruction.operands().size() > 1 ? instruction.operands().get(1) : "1";
      String expression = local + " + " + amount;
      operations.add(op("LOCAL_WRITE", List.of(local, expression), sourceInstruction));
      return;
    }

    if (isTypeConversion(opcode)) {
      String value = pop(stack);
      String converted = "(" + conversionType(opcode) + ")" + value;
      stack.push(converted);
      operations.add(op("TYPE_CONVERSION", List.of(value, converted), sourceInstruction));
      return;
    }

    if ("CHECKCAST".equals(opcode)) {
      String value = pop(stack);
      String type = instruction.operands().isEmpty() ? "unknown" : instruction.operands().get(0).replace('/', '.');
      String converted = "(" + type + ")" + value;
      stack.push(converted);
      operations.add(op("TYPE_CHECK", List.of(value, converted), sourceInstruction));
      return;
    }

    if ("INSTANCEOF".equals(opcode)) {
      String value = pop(stack);
      String type = instruction.operands().isEmpty() ? "unknown" : instruction.operands().get(0).replace('/', '.');
      String expression = value + " instanceof " + type;
      stack.push(expression);
      operations.add(op("TYPE_CHECK", List.of(expression), sourceInstruction));
      return;
    }

    if (isConditional(opcode)) {
      translateConditional(instruction, opcode, stack, operations, sourceInstruction);
      return;
    }

    if ("GOTO".equals(opcode) || "GOTO_W".equals(opcode)) {
      operations.add(op("JUMP", instruction.operands(), sourceInstruction));
      return;
    }

    if ("TABLESWITCH".equals(opcode) || "LOOKUPSWITCH".equals(opcode)) {
      String value = pop(stack);
      List<String> operands = new ArrayList<>();
      operands.add(value);
      operands.addAll(instruction.operands());
      operations.add(op("TABLESWITCH".equals(opcode) ? "TABLE_SWITCH" : "LOOKUP_SWITCH",
          operands, sourceInstruction));
      return;
    }

    if (isReturn(opcode)) {
      if ("RETURN".equals(opcode))
        operations.add(op("RETURN", List.of(), sourceInstruction));
      else
        operations.add(op("RETURN", List.of(pop(stack)), sourceInstruction));
      return;
    }

    if ("ATHROW".equals(opcode)) {
      operations.add(op("THROW", List.of(pop(stack)), sourceInstruction));
      return;
    }

    // Never discard the actual opcode when it falls outside the current
    // semantic lowering table. The exporter can now emit a useful runtime
    // diagnostic naming the bytecode instruction rather than a generic OTHER.
    List<String> unsupportedOperands = new ArrayList<>();
    unsupportedOperands.add(opcode);
    unsupportedOperands.addAll(instruction.operands());
    operations.add(op("UNSUPPORTED_OPCODE", unsupportedOperands, sourceInstruction));
  }

  private static void translateMethodCall(
      InstructionModel instruction,
      Deque<String> stack,
      List<IrOperationModel> operations,
      int sourceInstruction) {
    String opcode = instruction.opcodeName();
    if ("INVOKEDYNAMIC".equals(opcode)) {
      translateInvokeDynamic(instruction, stack, operations, sourceInstruction);
      return;
    }
    List<String> operands = instruction.operands();
    if (operands.size() < 3) {
      operations.add(op("METHOD_CALL", operands, sourceInstruction));
      return;
    }

    String owner = operands.get(0);
    String name = operands.get(1);
    String descriptor = operands.get(2);

    List<String> args = new ArrayList<>();
    int argumentCount = argumentCount(descriptor);
    for (int i = 0; i < argumentCount; i++)
      args.add(0, pop(stack));

    String receiver = null;
    if (!"INVOKESTATIC".equals(opcode) && !"INVOKEDYNAMIC".equals(opcode))
      receiver = pop(stack);

    String call;
    if ("<init>".equals(name) && receiver != null) {
      call = receiver + "(" + String.join(", ", args) + ")";
      if (!stack.isEmpty() && stack.peek().equals(receiver)) {
        stack.pop();
        stack.push(call);
      }
    } else if (receiver != null) {
      call = receiver + "." + name + "(" + String.join(", ", args) + ")";
    } else {
      call = owner.replace('/', '.') + "." + name + "(" + String.join(", ", args) + ")";
    }

    boolean constructorValue = "<init>".equals(name) && receiver != null
        && receiver.trim().startsWith("new ");
    if (!returnsVoid(descriptor) || constructorValue) {
      stack.push(call);
      operations.add(op("CALL_VALUE", List.of(call), sourceInstruction));
    } else {
      operations.add(op("METHOD_CALL", List.of(call), sourceInstruction));
    }
  }

  private static void translateInvokeDynamic(
      InstructionModel instruction,
      Deque<String> stack,
      List<IrOperationModel> operations,
      int sourceInstruction) {
    List<String> operands = instruction.operands();
    String name = operands.isEmpty() ? "dynamicCall" : operands.get(0);
    String callSiteDescriptor = operands.size() > 1 ? operands.get(1) : "()Ljava/lang/Object;";
    String bootstrap = operands.size() > 2 ? operands.get(2) : "";
    int capturedCount = argumentCount(callSiteDescriptor);
    List<String> captured = new ArrayList<>();
    for (int i = 0; i < capturedCount; i++) captured.add(0, pop(stack));

    boolean lambdaMetafactory = bootstrap.contains("java/lang/invoke/LambdaMetafactory")
        && (bootstrap.contains(",metafactory,") || bootstrap.contains(",altMetafactory,"));
    java.util.regex.Matcher handle = java.util.regex.Pattern
        .compile("^Handle\\((\\d+),([^,]+),([^,]+),(.+),(true|false)\\)$")
        .matcher(operands.size() > 4 ? operands.get(4) : "");
    if (!lambdaMetafactory || operands.size() < 6 || !handle.matches()) {
      String detail = name + " " + callSiteDescriptor + " " + bootstrap;
      String unsupported = "GoldClientRuntime.unsupported(\"invokedynamic\", \"";
      unsupported += detail.replace("\\", "/").replace("\"", "'") + "\")";
      if (!returnsVoid(callSiteDescriptor)) {
        stack.push(unsupported);
        operations.add(op("CALL_VALUE", List.of(unsupported), sourceInstruction));
      } else {
        operations.add(op("METHOD_CALL", List.of(unsupported), sourceInstruction));
      }
      operations.add(op("UNSUPPORTED_DYNAMIC", List.of(name, callSiteDescriptor), sourceInstruction));
      return;
    }

    int tag = Integer.parseInt(handle.group(1));
    String owner = handle.group(2).replace('/', '.');
    String implementationName = handle.group(3);
    String implementationDescriptor = handle.group(4);
    String samDescriptor = descriptorFromType(operands.get(3));
    int lambdaArity = samDescriptor == null ? 0 : argumentCount(samDescriptor);
    List<String> lambdaArguments = new ArrayList<>();
    for (int i = 0; i < lambdaArity; i++) lambdaArguments.add("lambdaArg" + i);
    List<String> capturedNames = new ArrayList<>();
    for (int i = 0; i < captured.size(); i++) capturedNames.add("capturedArg" + i);

    List<String> callArguments = new ArrayList<>();
    String call;
    if (tag == Opcodes.H_INVOKESTATIC) {
      callArguments.addAll(capturedNames);
      callArguments.addAll(lambdaArguments);
      String helperName = owner.startsWith("java.") ? "invokeJavaStatic" : "invokeStaticClass";
      call = "GoldClientRuntime." + helperName + "(\"" + owner + "\", \""
          + implementationName + "\", " + String.join(", ", callArguments) + ")";
    } else if (tag == Opcodes.H_NEWINVOKESPECIAL) {
      callArguments.addAll(capturedNames);
      callArguments.addAll(lambdaArguments);
      String helperName = owner.startsWith("java.") ? "createJava" : "create";
      call = "GoldClientRuntime." + helperName + "(\"" + owner + "\", "
          + String.join(", ", callArguments) + ")";
    } else if (tag == Opcodes.H_INVOKEVIRTUAL || tag == Opcodes.H_INVOKEINTERFACE
        || tag == Opcodes.H_INVOKESPECIAL) {
      String receiver;
      if (!capturedNames.isEmpty()) {
        receiver = capturedNames.get(0);
        callArguments.addAll(capturedNames.subList(1, capturedNames.size()));
        callArguments.addAll(lambdaArguments);
      } else if (!lambdaArguments.isEmpty()) {
        receiver = lambdaArguments.get(0);
        callArguments.addAll(lambdaArguments.subList(1, lambdaArguments.size()));
      } else {
        receiver = "null";
      }
      call = "GoldClientRuntime.invokeInstance(" + receiver + ", \"" + implementationName
          + "\", " + String.join(", ", callArguments) + ")";
    } else {
      String detail = "unsupported lambda handle tag " + tag + " for " + owner + "." + implementationName;
      call = "GoldClientRuntime.unsupported(\"lambda-handle\", \"" + detail + "\")";
      operations.add(op("UNSUPPORTED_DYNAMIC", List.of(name, detail), sourceInstruction));
    }

    String lambda = "(" + String.join(", ", lambdaArguments) + ") => " + call;
    if (!captured.isEmpty()) {
      lambda = "((" + String.join(", ", capturedNames) + ") => " + lambda + ")("
          + String.join(", ", captured) + ")";
    }
    stack.push(lambda);
    operations.add(op("CALL_VALUE", List.of(lambda), sourceInstruction));
  }

  private static String descriptorFromType(String value) {
    if (value == null || !value.startsWith("Type(") || !value.endsWith(")")) return null;
    return value.substring(5, value.length() - 1);
  }

  private static void translateArithmetic(
      String opcode,
      Deque<String> stack,
      List<IrOperationModel> operations,
      int sourceInstruction) {
    String value;
    if (opcode.endsWith("NEG")) {
      value = "-" + pop(stack);
    } else {
      String right = pop(stack);
      String left = pop(stack);
      String operator = arithmeticOperator(opcode);
      value = "(" + left + " " + operator + " " + right + ")";
    }
    stack.push(value);
    operations.add(op("ARITHMETIC", List.of(value), sourceInstruction));
  }

  private static void translateConditional(
      InstructionModel instruction,
      String opcode,
      Deque<String> stack,
      List<IrOperationModel> operations,
      int sourceInstruction) {
    String target = instruction.operands().isEmpty() ? "" : instruction.operands().get(0);
    String condition;

    if (isSingleValueConditional(opcode)) {
      String value = pop(stack);
      condition = switch (opcode) {
        case "IFEQ" -> value + " == 0";
        case "IFNE" -> value + " != 0";
        case "IFLT" -> value + " < 0";
        case "IFLE" -> value + " <= 0";
        case "IFGT" -> value + " > 0";
        case "IFGE" -> value + " >= 0";
        case "IFNULL" -> value + " == null";
        case "IFNONNULL" -> value + " != null";
        default -> value + " " + opcode;
      };
    } else {
      String right = pop(stack);
      String left = pop(stack);
      condition = switch (opcode) {
        case "IF_ICMPEQ", "IF_ACMPEQ" -> left + " == " + right;
        case "IF_ICMPNE", "IF_ACMPNE" -> left + " != " + right;
        case "IF_ICMPLT" -> left + " < " + right;
        case "IF_ICMPLE" -> left + " <= " + right;
        case "IF_ICMPGT" -> left + " > " + right;
        case "IF_ICMPGE" -> left + " >= " + right;
        default -> left + " " + opcode + " " + right;
      };
    }

    operations.add(op("CONDITIONAL_BRANCH", List.of(condition, target), sourceInstruction));
  }

  private static void translateStackOperation(
      String opcode,
      Deque<String> stack,
      List<IrOperationModel> operations,
      int sourceInstruction) {
    switch (opcode) {
      case "POP" -> {
        String value = pop(stack);
        operations.add(op("DROP", List.of(value), sourceInstruction));
      }
      case "POP2" -> {
        String first = pop(stack);
        if (isCategory2(first)) {
          operations.add(op("DROP", List.of(first), sourceInstruction));
        } else {
          String second = pop(stack);
          // The lower value was produced first, so preserve that evaluation
          // order when POP2 discards two category-one values.
          operations.add(op("DROP", List.of(second), sourceInstruction));
          operations.add(op("DROP", List.of(first), sourceInstruction));
        }
      }
      case "DUP" -> {
        String value = pop(stack);
        pushInOrder(stack, value, value);
        operations.add(op("DUP", List.of(value), sourceInstruction));
      }
      case "DUP_X1" -> {
        String v1 = pop(stack), v2 = pop(stack);
        pushInOrder(stack, v1, v2, v1);
        operations.add(op("STACK_OPERATION", List.of(opcode, v1, v2), sourceInstruction));
      }
      case "DUP_X2" -> {
        String v1 = pop(stack), v2 = pop(stack);
        if (isCategory2(v2)) {
          pushInOrder(stack, v1, v2, v1);
          operations.add(op("STACK_OPERATION", List.of(opcode, v1, v2), sourceInstruction));
        } else {
          String v3 = pop(stack);
          pushInOrder(stack, v1, v3, v2, v1);
          operations.add(op("STACK_OPERATION", List.of(opcode, v1, v2, v3), sourceInstruction));
        }
      }
      case "DUP2" -> {
        String v1 = pop(stack);
        if (isCategory2(v1)) {
          pushInOrder(stack, v1, v1);
          operations.add(op("STACK_OPERATION", List.of(opcode, v1), sourceInstruction));
        } else {
          String v2 = pop(stack);
          pushInOrder(stack, v2, v1, v2, v1);
          operations.add(op("STACK_OPERATION", List.of(opcode, v1, v2), sourceInstruction));
        }
      }
      case "DUP2_X1" -> {
        String v1 = pop(stack);
        if (isCategory2(v1)) {
          String v2 = pop(stack);
          pushInOrder(stack, v1, v2, v1);
          operations.add(op("STACK_OPERATION", List.of(opcode, v1, v2), sourceInstruction));
        } else {
          String v2 = pop(stack), v3 = pop(stack);
          pushInOrder(stack, v2, v1, v3, v2, v1);
          operations.add(op("STACK_OPERATION", List.of(opcode, v1, v2, v3), sourceInstruction));
        }
      }
      case "DUP2_X2" -> {
        String v1 = pop(stack);
        if (isCategory2(v1)) {
          String v2 = pop(stack);
          if (isCategory2(v2)) {
            pushInOrder(stack, v1, v2, v1);
            operations.add(op("STACK_OPERATION", List.of(opcode, v1, v2), sourceInstruction));
          } else {
            String v3 = pop(stack);
            pushInOrder(stack, v1, v3, v2, v1);
            operations.add(op("STACK_OPERATION", List.of(opcode, v1, v2, v3), sourceInstruction));
          }
        } else {
          String v2 = pop(stack), v3 = pop(stack);
          if (isCategory2(v3)) {
            pushInOrder(stack, v2, v1, v3, v2, v1);
            operations.add(op("STACK_OPERATION", List.of(opcode, v1, v2, v3), sourceInstruction));
          } else {
            String v4 = pop(stack);
            pushInOrder(stack, v2, v1, v4, v3, v2, v1);
            operations.add(op("STACK_OPERATION", List.of(opcode, v1, v2, v3, v4), sourceInstruction));
          }
        }
      }
      case "SWAP" -> {
        String first = pop(stack), second = pop(stack);
        pushInOrder(stack, first, second);
        operations.add(op("SWAP", List.of(first, second), sourceInstruction));
      }
      default -> operations.add(op("STACK_OPERATION", List.of(opcode), sourceInstruction));
    }
  }

  // Values are supplied bottom-to-top; Deque.push() in that order leaves the
  // intended top value at the head of the symbolic operand stack.
  private static void pushInOrder(Deque<String> stack, String... bottomToTop) {
    for (String value : bottomToTop) stack.push(value);
  }

  private static boolean isCategory2(String value) {
    if (value == null) return false;
    String expression = value.trim();
    return expression.matches(".*(?:[0-9]+[lL]|[0-9]+(?:\\.[0-9]+)?[dD])$")
        || expression.startsWith("long ") || expression.startsWith("double ")
        || expression.contains("Long.valueOf(") || expression.contains("Double.valueOf(");
  }

  private static boolean isConstant(String opcode) {
    return opcode.startsWith("ICONST_")
        || opcode.startsWith("LCONST_")
        || opcode.startsWith("FCONST_")
        || opcode.startsWith("DCONST_")
        || "BIPUSH".equals(opcode)
        || "SIPUSH".equals(opcode)
        || "LDC".equals(opcode);
  }

  private static String constantValue(InstructionModel instruction) {
    String opcode = instruction.opcodeName();
    if (opcode.startsWith("ICONST_")) {
      String value = opcode.substring("ICONST_".length());
      return "M1".equals(value) ? "-1" : value;
    }
    if (opcode.startsWith("LCONST_"))
      return opcode.substring("LCONST_".length()) + "L";
    if (opcode.startsWith("FCONST_"))
      return opcode.substring("FCONST_".length()) + "F";
    if (opcode.startsWith("DCONST_"))
      return opcode.substring("DCONST_".length());
    if (!instruction.operands().isEmpty()) {
      String value = instruction.operands().get(0);
      if ("LDC".equals(opcode) && !isNumericLiteral(value)
          && !"true".equals(value) && !"false".equals(value)
          && !"null".equals(value) && !isQuoted(value)) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
      }
      return value;
    }
    return "unknown";
  }

  private static boolean isNumericLiteral(String value) {
    return value.matches("-?(?:0|[1-9]\\d*)(?:[lLfFdD])?")
        || value.matches("-?(?:0|[1-9]\\d*)\\.\\d+(?:[fFdD])?");
  }

  private static boolean isQuoted(String value) {
    return value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"");
  }

  private static String localName(InstructionModel instruction) {
    if (instruction.operands().isEmpty())
      return "local?";
    return "local" + instruction.operands().get(0);
  }

  private static String fieldName(InstructionModel instruction) {
    List<String> operands = instruction.operands();
    if (operands.size() < 3)
      return String.join(".", operands);
    return operands.get(0).replace('/', '.') + "." + operands.get(1);
  }

  private static int argumentCount(String descriptor) {
    int count = 0;
    boolean inArgs = false;
    for (int i = 0; i < descriptor.length(); i++) {
      char c = descriptor.charAt(i);
      if (c == '(') {
        inArgs = true;
        continue;
      }
      if (c == ')')
        break;
      if (!inArgs) continue;
      if (c == 'L') {
        while (i < descriptor.length() && descriptor.charAt(i) != ';') i++;
        count++;
      } else if (c == '[') {
        while (i + 1 < descriptor.length() && descriptor.charAt(i + 1) == '[') i++;
        if (i + 1 < descriptor.length() && descriptor.charAt(i + 1) == 'L') {
          i += 2;
          while (i < descriptor.length() && descriptor.charAt(i) != ';') i++;
        }
        count++;
      } else {
        count++;
      }
    }
    return count;
  }

  private static boolean returnsVoid(String descriptor) {
    return descriptor.endsWith(")V");
  }

  private static String conversionType(String opcode) {
    if (opcode.endsWith("I")) return "int";
    if (opcode.endsWith("L")) return "long";
    if (opcode.endsWith("F")) return "float";
    if (opcode.endsWith("D")) return "double";
    if (opcode.equals("I2B")) return "byte";
    if (opcode.equals("I2C")) return "char";
    if (opcode.equals("I2S")) return "short";
    return "value";
  }

  private static String arithmeticOperator(String opcode) {
    if (opcode.endsWith("ADD")) return "+";
    if (opcode.endsWith("SUB")) return "-";
    if (opcode.endsWith("MUL")) return "*";
    if (opcode.endsWith("DIV")) return "/";
    if (opcode.endsWith("REM")) return "%";
    if (opcode.endsWith("AND")) return "&";
    if (opcode.endsWith("OR")) return "|";
    if (opcode.endsWith("XOR")) return "^";
    if (opcode.endsWith("SHL")) return "<<";
    if (opcode.endsWith("USHR")) return ">>>";
    if (opcode.endsWith("SHR")) return ">>";
    return "?";
  }

  private static String pop(Deque<String> stack) {
    return stack.isEmpty() ? "<unknown>" : stack.pop();
  }

  private static IrOperationModel op(String kind, List<String> operands, int sourceInstruction) {
    return new IrOperationModel(kind, operands, sourceInstruction);
  }

  private static boolean isLoad(String opcode) {
    return opcode.startsWith("ILOAD") || opcode.startsWith("LLOAD")
        || opcode.startsWith("FLOAD") || opcode.startsWith("DLOAD")
        || opcode.startsWith("ALOAD");
  }

  private static boolean isStore(String opcode) {
    return opcode.startsWith("ISTORE") || opcode.startsWith("LSTORE")
        || opcode.startsWith("FSTORE") || opcode.startsWith("DSTORE")
        || opcode.startsWith("ASTORE");
  }

  private static boolean isMethodCall(String opcode) {
    return opcode.equals("INVOKEVIRTUAL") || opcode.equals("INVOKEINTERFACE")
        || opcode.equals("INVOKESPECIAL") || opcode.equals("INVOKESTATIC")
        || opcode.equals("INVOKEDYNAMIC");
  }

  private static boolean isConditional(String opcode) {
    return opcode.startsWith("IF");
  }

  private static boolean isSingleValueConditional(String opcode) {
    return opcode.equals("IFEQ") || opcode.equals("IFNE")
        || opcode.equals("IFLT") || opcode.equals("IFLE")
        || opcode.equals("IFGT") || opcode.equals("IFGE")
        || opcode.equals("IFNULL") || opcode.equals("IFNONNULL");
  }

  private static boolean isReturn(String opcode) {
    return opcode.equals("RETURN") || opcode.endsWith("RETURN");
  }

  private static boolean isStackOperation(String opcode) {
    return Set.of("POP", "POP2", "DUP", "DUP_X1", "DUP_X2",
        "DUP2", "DUP2_X1", "DUP2_X2", "SWAP").contains(opcode);
  }

  private static boolean isArithmetic(String opcode) {
    return opcode.endsWith("ADD") || opcode.endsWith("SUB")
        || opcode.endsWith("MUL") || opcode.endsWith("DIV")
        || opcode.endsWith("REM") || opcode.endsWith("NEG")
        || opcode.endsWith("AND") || opcode.endsWith("OR")
        || opcode.endsWith("XOR") || opcode.endsWith("SHL")
        || opcode.endsWith("SHR") || opcode.endsWith("USHR");
  }

  private static boolean isArrayLoad(String opcode) {
    return Set.of("IALOAD", "LALOAD", "FALOAD", "DALOAD", "AALOAD",
        "BALOAD", "CALOAD", "SALOAD").contains(opcode);
  }

  private static boolean isArrayStore(String opcode) {
    return Set.of("IASTORE", "LASTORE", "FASTORE", "DASTORE", "AASTORE",
        "BASTORE", "CASTORE", "SASTORE").contains(opcode);
  }

  private static boolean isTypeConversion(String opcode) {
    return opcode.startsWith("I2") || opcode.startsWith("L2")
        || opcode.startsWith("F2") || opcode.startsWith("D2")
        || opcode.equals("I2B") || opcode.equals("I2C") || opcode.equals("I2S");
  }

  private static String kindOf(InstructionModel instruction) {
    String opcode = instruction.opcodeName();
    if ("LABEL".equals(opcode)) return "LABEL";
    if (isConstant(opcode)) return "CONSTANT";
    if (isLoad(opcode)) return "LOCAL_READ";
    if (isStore(opcode)) return "LOCAL_WRITE";
    if ("GETSTATIC".equals(opcode) || "GETFIELD".equals(opcode)) return "FIELD_READ";
    if ("PUTSTATIC".equals(opcode) || "PUTFIELD".equals(opcode)) return "FIELD_WRITE";
    if ("NEW".equals(opcode)) return "OBJECT_CREATE";
    if ("ANEWARRAY".equals(opcode) || "NEWARRAY".equals(opcode)
        || "MULTIANEWARRAY".equals(opcode)) return "ARRAY_CREATE";
    if (isMethodCall(opcode)) return "METHOD_CALL";
    if (isConditional(opcode)) return "CONDITIONAL_BRANCH";
    if (opcode.equals("GOTO") || opcode.equals("GOTO_W")) return "JUMP";
    if (opcode.equals("TABLESWITCH") || opcode.equals("LOOKUPSWITCH")) return "SWITCH";
    if (isReturn(opcode) || opcode.equals("ATHROW")) return "TERMINATE";
    if (isStackOperation(opcode)) return "STACK_OPERATION";
    if (isArithmetic(opcode)) return "ARITHMETIC";
    if (isTypeConversion(opcode)) return "TYPE_CONVERSION";
    if (opcode.equals("CHECKCAST") || opcode.equals("INSTANCEOF")) return "TYPE_CHECK";
    return "OTHER";
  }
}
