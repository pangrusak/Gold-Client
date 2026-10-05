package com.goldclient.analyzer;

import com.goldclient.model.BasicBlockModel;
import com.goldclient.model.InstructionModel;
import com.goldclient.model.IrMethodModel;
import com.goldclient.model.IrOperationModel;
import com.goldclient.model.MethodModel;
import java.util.*;

public final class IntermediateRepresentationAnalyzer {
  public IrMethodModel analyze(MethodModel method) {
    List<IrOperationModel> operations = new ArrayList<>();
    List<InstructionModel> instructions = method.instructions();

    for (BasicBlockModel block : method.controlFlow().blocks()) {
      Deque<String> stack = new ArrayDeque<>();

      for (int i = block.startInstruction(); i < block.endInstruction(); i++) {
        InstructionModel instruction = instructions.get(i);
        translate(instruction, i, stack, operations);
      }
    }

    return new IrMethodModel(method.name(), method.descriptor(), operations);
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
      String descriptor = instruction.operands().isEmpty() ? "[?" : instruction.operands().get(0);
      int dimensions = instruction.operands().size() > 1
          ? Integer.parseInt(instruction.operands().get(1)) : 1;
      List<String> dimensionsValues = new ArrayList<>();
      for (int i = 0; i < dimensions; i++)
        dimensionsValues.add(0, pop(stack));
      String array = descriptor + " " + dimensionsValues;
      stack.push(array);
      operations.add(op("ARRAY_CREATE", List.of(array), sourceInstruction));
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
      operations.add(op("SWITCH", operands, sourceInstruction));
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

    operations.add(op(kindOf(instruction), instruction.operands(), sourceInstruction));
  }

  private static void translateMethodCall(
      InstructionModel instruction,
      Deque<String> stack,
      List<IrOperationModel> operations,
      int sourceInstruction) {
    String opcode = instruction.opcodeName();
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
    } else if (receiver != null) {
      call = receiver + "." + name + "(" + String.join(", ", args) + ")";
    } else {
      call = owner.replace('/', '.') + "." + name + "(" + String.join(", ", args) + ")";
    }

    if (!returnsVoid(descriptor)) {
      stack.push(call);
      operations.add(op("METHOD_CALL", List.of(call), sourceInstruction));
    } else {
      operations.add(op("METHOD_CALL", List.of(call), sourceInstruction));
    }
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
        String value = pop(stack);
        operations.add(op("DROP", List.of(value), sourceInstruction));
      }
      case "DUP" -> {
        String value = pop(stack);
        stack.push(value);
        stack.push(value);
        operations.add(op("DUP", List.of(value), sourceInstruction));
      }
      case "SWAP" -> {
        String first = pop(stack);
        String second = pop(stack);
        stack.push(first);
        stack.push(second);
        operations.add(op("SWAP", List.of(first, second), sourceInstruction));
      }
      default -> operations.add(op("STACK_OPERATION", List.of(opcode), sourceInstruction));
    }
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
    if (!instruction.operands().isEmpty())
      return instruction.operands().get(0);
    return "unknown";
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
        || opcode.endsWith("REM") || opcode.endsWith("NEG");
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
