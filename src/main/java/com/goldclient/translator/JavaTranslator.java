package com.goldclient.translator;

import com.goldclient.model.IrMethodModel;
import com.goldclient.model.IrOperationModel;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class JavaTranslator {
  public TranslationResult translate(IrMethodModel method) {
    List<IrOperationModel> operations = method.operations();
    TranslationContext context = new TranslationContext();
    StringBuilder source = new StringBuilder();
    boolean complete = true;

    source.append(returnType(method.descriptor())).append(" ").append(method.name()).append("(")
        .append(parameterList(method.descriptor())).append(") {\n");

    Map<String, Integer> labels = findLabels(operations, 0, operations.size());
    RenderResult rendered = renderRange(
        operations, 0, operations.size(), 1, context, labels);
    source.append(rendered.source());
    complete &= rendered.complete();

    // Validate the raw graph as well as the rendered structure. A renderer can
    // recover readable text from malformed IR, but that text must not be marked
    // complete when its branches or exception-table metadata are inconsistent.
    ControlFlowAudit.Report audit = ControlFlowAudit.analyze(operations);
    for (ControlFlowAudit.Issue issue : audit.errors()) {
      source.append("  // CONTROL-FLOW ERROR at operation ")
          .append(issue.operationIndex()).append(": ")
          .append(issue.message()).append("\n");
    }
    complete &= audit.valid();

    source.append("}");
    return new TranslationResult(source.toString(), complete);
  }

  private static RenderResult renderRange(
      List<IrOperationModel> operations,
      int start,
      int end,
      int indent,
      TranslationContext context,
      Map<String, Integer> labels) {
    StringBuilder source = new StringBuilder();
    boolean complete = true;

    for (int i = start; i < end; i++) {
      IrOperationModel operation = operations.get(i);

      if ("TRY_BEGIN".equals(operation.kind())) {
        ConditionalRender guarded = renderTryCatch(
            operations, i, end, indent, context, labels);
        if (guarded != null) {
          source.append(guarded.source());
          complete &= guarded.complete();
          i = guarded.lastIndex();
          continue;
        }
        source.append(indent(indent))
            .append("GoldClientRuntime.unsupported(\"try-catch-region\", \"")
            .append(escapeJava(operation.operands().toString()))
            .append("\");\n");
        complete = false;
        continue;
      }

      if ("TRY_END".equals(operation.kind()) || "CATCH_BEGIN".equals(operation.kind()))
        continue;

      if ("LABEL".equals(operation.kind())) {
        if (!operation.operands().isEmpty()) {
          int loopBranch = findBackwardConditional(
              operations, i, end, operation.operands().get(0));
          if (loopBranch > i) {
            ConditionalRender loop = renderBackwardLoop(
                operations, loopBranch, i, end, indent, context, labels,
                operations.get(loopBranch).operands().get(0));
            source.append(loop.source());
            complete &= loop.complete();
            i = loop.lastIndex();
            continue;
          }
        }
        continue;
      }

      if ("TABLE_SWITCH".equals(operation.kind())
          || "LOOKUP_SWITCH".equals(operation.kind())) {
        ConditionalRender switched = renderSwitch(
            operations, i, end, indent, context, labels);
        if (switched != null) {
          source.append(switched.source());
          complete &= switched.complete();
          i = switched.lastIndex();
          continue;
        }
      }

      if ("CONDITIONAL_BRANCH".equals(operation.kind())
          && operation.operands().size() >= 2) {
        ConditionalRender branch = renderConditional(
            operations, i, end, indent, context, labels);

        if (branch != null) {
          source.append(branch.source());
          complete &= branch.complete();
          i = branch.lastIndex();
          continue;
        }
      }

      if ("MONITOR".equals(operation.kind())) {
        String opcode = operation.operands().isEmpty() ? "monitor" : operation.operands().get(0);
        String value = operation.operands().size() > 1 ? operation.operands().get(1) : "undefined";
        source.append(indent(indent))
            .append("GoldClientRuntime.unsupported(\"monitor\", \"")
            .append(escapeJava(opcode + " on " + value))
            .append("\");\n");
        complete = false;
        continue;
      }

      if ("UNSUPPORTED_DYNAMIC".equals(operation.kind())) {
        source.append(indent(indent)).append("// unsupported invokedynamic: ")
            .append(operation.operands()).append("\n");
        complete = false;
        continue;
      }

      if (("METHOD_CALL".equals(operation.kind()) || "CALL_VALUE".equals(operation.kind()))
          && i + 1 < end
          && "THROW".equals(operations.get(i + 1).kind())
          && !operation.operands().isEmpty()
          && operation.operands().get(0).trim().startsWith("new ")) {
        source.append(indent(indent))
            .append("throw ")
            .append(normalizeExpression(operation.operands().get(0)))
            .append(";\n");
        i++;
        continue;
      }

      if ("JUMP".equals(operation.kind()) && !operation.operands().isEmpty()) {
        String target = operation.operands().get(0);
        Integer targetIndex = labels.get(target);

        if (targetIndex != null && targetIndex == end)
          continue;

        source.append(indent(indent))
            .append("GoldClientRuntime.unsupported(\"JUMP\", \"")
            .append(escapeJava(operation.operands().toString()))
            .append("\");\n");
        complete = false;
        continue;
      }

      String line = translateOperation(operation, context);
      if (line == null) {
        if (isExpressionOnly(operation))
          continue;

        // Keep emitting syntactically valid JavaScript even when this opcode
        // is not implemented yet. The runtime failure is explicit and retains
        // the operation details for debugging; completeness stays false.
        source.append(indent(indent))
            .append("GoldClientRuntime.unsupported(\"")
            .append(escapeJava(operation.kind()))
            .append("\", \"")
            .append(escapeJava(String.join(" | ", operation.operands())))
            .append("\");\n");
        complete = false;
      } else if (!line.isBlank()) {
        source.append(indent(indent)).append(line).append("\n");
      }
    }

    return new RenderResult(source.toString(), complete);
  }

  /**
   * Structures a conventional JVM exception table entry as a source try/catch.
   * Complex/malformed regions are left explicitly incomplete rather than
   * silently claiming that exception behavior was preserved.
   */
  private static ConditionalRender renderTryCatch(
      List<IrOperationModel> operations, int begin, int end, int indent,
      TranslationContext context, Map<String, Integer> labels) {
    List<String> metadata = operations.get(begin).operands();
    if (metadata.size() < 4) return null;
    String startLabel = metadata.get(0);
    String endLabel = metadata.get(1);
    String handlerLabel = metadata.get(2);
    String exceptionType = metadata.get(3);
    int tryEnd = -1;
    int catchBegin = -1;
    for (int i = begin + 1; i < end; i++) {
      IrOperationModel operation = operations.get(i);
      if (operation.operands().size() >= 3
          && startLabel.equals(operation.operands().get(0))
          && endLabel.equals(operation.operands().get(1))
          && handlerLabel.equals(operation.operands().get(2))) {
        if ("TRY_END".equals(operation.kind())) tryEnd = i;
        if ("CATCH_BEGIN".equals(operation.kind())) catchBegin = i;
      }
    }
    if (tryEnd <= begin || catchBegin <= tryEnd) return null;

    int handlerEnd = end;
    String joinLabel = null;
    for (int i = catchBegin + 1; i < end; i++) {
      IrOperationModel operation = operations.get(i);

      // Do not mistake a branch leaving a nested try/catch for the end of
      // this handler. This is common in compiler-generated try-with-resources
      // code, where close() has its own exception table inside the outer catch.
      if ("TRY_BEGIN".equals(operation.kind())) {
        ConditionalRender nested = renderTryCatch(
            operations, i, end, indent, context, labels);
        if (nested != null) {
          i = nested.lastIndex();
          continue;
        }
      }

      if ("JUMP".equals(operation.kind()) && !operation.operands().isEmpty()) {
        String target = operation.operands().get(0);
        Integer targetIndex = labels.get(target);
        Integer protectedEnd = labels.get(endLabel);
        if (targetIndex != null && protectedEnd != null && targetIndex > protectedEnd) {
          handlerEnd = i;
          joinLabel = target;
          break;
        }
      }
      if ("RETURN".equals(operation.kind()) || "THROW".equals(operation.kind())
          || "TERMINATE".equals(operation.kind())) {
        handlerEnd = i + 1;
        break;
      }
    }
    if (handlerEnd <= catchBegin + 1) return null;

    int bodyStart = begin + 1;
    while (bodyStart < tryEnd && "LABEL".equals(operations.get(bodyStart).kind())) bodyStart++;
    RenderResult tryBody = renderRange(
        operations, bodyStart, tryEnd, indent + 1, context, labels);

    int catchBodyStart = catchBegin + 1;
    String exceptionVariable = "caughtException";
    // A handler label is normally the first instruction after CATCH_BEGIN.
    // Skip labels before looking for the JVM's exception-stack-to-local store.
    while (catchBodyStart < handlerEnd
        && "LABEL".equals(operations.get(catchBodyStart).kind())) {
      catchBodyStart++;
    }
    if (catchBodyStart < handlerEnd) {
      IrOperationModel first = operations.get(catchBodyStart);
      if ("LOCAL_WRITE".equals(first.kind()) && first.operands().size() >= 2
          && "<exception>".equals(first.operands().get(1))) {
        exceptionVariable = first.operands().get(0);
        catchBodyStart++;
      }
    }
    RenderResult catchBody = renderRange(
        operations, catchBodyStart, handlerEnd, indent + 1, context, labels);

    StringBuilder source = new StringBuilder();
    source.append(indent(indent)).append("try {\n").append(tryBody.source());
    source.append(indent(indent)).append("} catch (")
        .append(exceptionType).append(" ").append(exceptionVariable).append(") {\n")
        .append(catchBody.source());
    source.append(indent(indent)).append("}\n");
    boolean complete = tryBody.complete() && catchBody.complete();
    // A handler may legitimately terminate with return/throw instead of
    // jumping to a shared continuation. That is structured control flow, not
    // evidence that the exception region was only partially rendered.
    if (joinLabel == null && handlerEnd < end
        && !isTerminal(operations.get(handlerEnd - 1))) complete = false;
    int last = joinLabel == null ? handlerEnd - 1 : handlerEnd;
    return new ConditionalRender(source.toString(), complete, last);
  }

  private static ConditionalRender renderSwitch(
      List<IrOperationModel> operations,
      int switchIndex,
      int end,
      int indent,
      TranslationContext context,
      Map<String, Integer> labels) {
    IrOperationModel operation = operations.get(switchIndex);
    List<String> operands = operation.operands();
    String value;
    String defaultLabel;
    List<SwitchArm> arms = new java.util.ArrayList<>();

    try {
      if ("TABLE_SWITCH".equals(operation.kind())) {
        if (operands.size() < 4) return null;
        value = operands.get(0);
        int min = Integer.parseInt(operands.get(1));
        int max = Integer.parseInt(operands.get(2));
        defaultLabel = operands.get(3);
        if (max < min || (long) max - min + 1 != operands.size() - 4) return null;
        for (long key = min; key <= (long) max; key++) {
          arms.add(new SwitchArm((int) key, operands.get(4 + (int) (key - min))));
        }
      } else {
        if (operands.size() < 2 || (operands.size() - 2) % 2 != 0) return null;
        value = operands.get(0);
        defaultLabel = operands.get(1);
        for (int index = 2; index < operands.size(); index += 2) {
          arms.add(new SwitchArm(
              Integer.parseInt(operands.get(index)), operands.get(index + 1)));
        }
      }
    } catch (NumberFormatException exception) {
      return null;
    }

    Integer defaultIndex = labels.get(defaultLabel);
    if (defaultIndex == null || defaultIndex <= switchIndex || defaultIndex >= end) return null;
    Map<String, Integer> targetIndices = new HashMap<>();
    for (SwitchArm arm : arms) {
      Integer target = labels.get(arm.label());
      if (target == null || target <= switchIndex || target >= end) return null;
      targetIndices.put(arm.label(), target);
    }

    int lastTarget = defaultIndex;
    for (int target : targetIndices.values()) lastTarget = Math.max(lastTarget, target);

    int joinIndex = end;
    String joinLabel = null;
    for (int index = switchIndex + 1; index < end; index++) {
      IrOperationModel candidate = operations.get(index);
      if (!"JUMP".equals(candidate.kind()) || candidate.operands().isEmpty()) continue;
      String targetLabel = candidate.operands().get(0);
      Integer targetIndex = labels.get(targetLabel);
      if (targetIndex != null && targetIndex > lastTarget && targetIndex < joinIndex) {
        joinIndex = targetIndex;
        joinLabel = targetLabel;
      }
    }

    Map<Integer, List<String>> labelsAtTarget = new java.util.TreeMap<>();
    for (SwitchArm arm : arms) {
      int target = targetIndices.get(arm.label());
      labelsAtTarget.computeIfAbsent(target, ignored -> new java.util.ArrayList<>())
          .add("case " + arm.key() + ":");
    }
    labelsAtTarget.computeIfAbsent(defaultIndex, ignored -> new java.util.ArrayList<>())
        .add("default:");

    List<Integer> orderedTargets = new java.util.ArrayList<>(labelsAtTarget.keySet());
    orderedTargets.sort(Integer::compareTo);
    StringBuilder source = new StringBuilder();
    source.append(indent(indent)).append("switch (")
        .append(normalizeExpression(value)).append(") {\n");
    boolean complete = true;

    for (int block = 0; block < orderedTargets.size(); block++) {
      int targetIndex = orderedTargets.get(block);
      int segmentEnd = block + 1 < orderedTargets.size()
          ? orderedTargets.get(block + 1) : joinIndex;
      if (segmentEnd < targetIndex + 1) {
        complete = false;
        continue;
      }

      for (String label : labelsAtTarget.get(targetIndex)) {
        source.append(indent(indent + 1)).append(label).append("\n");
      }

      int bodyEnd = segmentEnd;
      boolean jumpsToJoin = false;
      if (bodyEnd > targetIndex + 1) {
        IrOperationModel last = operations.get(bodyEnd - 1);
        if ("JUMP".equals(last.kind()) && !last.operands().isEmpty()
            && joinLabel != null && joinLabel.equals(last.operands().get(0))) {
          bodyEnd--;
          jumpsToJoin = true;
        }
      }

      RenderResult body = renderRange(
          operations, targetIndex + 1, bodyEnd, indent + 2, context, labels);
      source.append(body.source());
      complete &= body.complete();

      boolean endsAbruptly = bodyEnd > targetIndex + 1
          && (isTerminal(operations.get(bodyEnd - 1)) || jumpsToJoin);
      boolean fallsThroughToNextCase = block + 1 < orderedTargets.size()
          && segmentEnd == orderedTargets.get(block + 1)
          && !endsAbruptly;
      if (jumpsToJoin || (!fallsThroughToNextCase && !endsAbruptly)) {
        source.append(indent(indent + 2)).append("break;\n");
      }
    }

    source.append(indent(indent)).append("}\n");
    int lastIndex = joinIndex < end ? joinIndex - 1 : end - 1;
    return new ConditionalRender(source.toString(), complete, lastIndex);
  }

  private static boolean isTerminal(IrOperationModel operation) {
    return "RETURN".equals(operation.kind()) || "THROW".equals(operation.kind())
        || "TERMINATE".equals(operation.kind());
  }

  private static ConditionalRender renderConditional(
      List<IrOperationModel> operations,
      int branchIndex,
      int end,
      int indent,
      TranslationContext context,
      Map<String, Integer> labels) {
    IrOperationModel branch = operations.get(branchIndex);
    String condition = branch.operands().get(0);
    String target = branch.operands().get(1);
    Integer targetIndex = labels.get(target);

    if (targetIndex == null || targetIndex >= end)
      return null;

    if (targetIndex <= branchIndex) {
      return renderBackwardLoop(
          operations, branchIndex, targetIndex, end, indent, context, labels,
          condition);
    }

    int jumpIndex = findForwardJoinJump(operations, branchIndex + 1, targetIndex);
    if (jumpIndex >= 0) {
      IrOperationModel jump = operations.get(jumpIndex);
      if (!jump.operands().isEmpty()) {
        Integer endIndex = labels.get(jump.operands().get(0));
        if (endIndex != null && endIndex > targetIndex && endIndex <= end) {
          RenderResult fallthroughBody = renderRange(
              operations, branchIndex + 1, jumpIndex, indent + 1, context, labels);
          RenderResult targetBody = renderRange(
              operations, targetIndex + 1, endIndex, indent + 1, context, labels);

          StringBuilder source = new StringBuilder();
          source.append(indent(indent))
              .append("if (")
              .append(normalizeCondition(condition))
              .append(") {\n");
          source.append(targetBody.source());
          source.append(indent(indent)).append("} else {\n");
          source.append(fallthroughBody.source());
          source.append(indent(indent)).append("}\n");

          return new ConditionalRender(
              source.toString(),
              fallthroughBody.complete() && targetBody.complete(),
              endIndex);
        }
      }
    }

    RenderResult body = renderRange(
        operations, branchIndex + 1, targetIndex, indent + 1, context, labels);

    StringBuilder source = new StringBuilder();
    source.append(indent(indent))
        .append("if (")
        .append(negateCondition(condition))
        .append(") {\n");
    source.append(body.source());
    source.append(indent(indent)).append("}\n");

    return new ConditionalRender(source.toString(), body.complete(), targetIndex);
  }

  private static ConditionalRender renderBackwardLoop(
      List<IrOperationModel> operations,
      int branchIndex,
      int targetIndex,
      int end,
      int indent,
      TranslationContext context,
      Map<String, Integer> labels,
      String condition) {
    RenderResult body = renderRange(
        operations, targetIndex + 1, branchIndex, indent + 1, context, labels);

    StringBuilder source = new StringBuilder();
    source.append(indent(indent))
        .append("do {\n");
    source.append(body.source());
    source.append(indent(indent))
        .append("} while (")
        .append(normalizeCondition(condition))
        .append(");\n");

    return new ConditionalRender(source.toString(), body.complete(), branchIndex);
  }

  private static int findBackwardConditional(
      List<IrOperationModel> operations,
      int labelIndex,
      int end,
      String label) {
    for (int i = labelIndex + 1; i < end; i++) {
      IrOperationModel operation = operations.get(i);
      if ("CONDITIONAL_BRANCH".equals(operation.kind())
          && operation.operands().size() >= 2
          && label.equals(operation.operands().get(1)))
        return i;
    }
    return -1;
  }

  private static int findForwardJoinJump(
      List<IrOperationModel> operations,
      int start,
      int targetIndex) {
    for (int i = start; i < targetIndex; i++) {
      if ("JUMP".equals(operations.get(i).kind()))
        return i;
    }
    return -1;
  }

  private static Map<String, Integer> findLabels(
      List<IrOperationModel> operations,
      int start,
      int end) {
    Map<String, Integer> labels = new HashMap<>();
    for (int i = start; i < end; i++) {
      IrOperationModel operation = operations.get(i);
      if ("LABEL".equals(operation.kind()) && !operation.operands().isEmpty())
        labels.put(operation.operands().get(0), i);
    }
    return labels;
  }

  private static String translateOperation(
      IrOperationModel operation,
      TranslationContext context) {
    List<String> operands = operation.operands();

    return switch (operation.kind()) {
      case "LABEL" -> "";
      case "DROP" -> translateDrop(operands);
      case "STACK_OPERATION" -> "";
      case "CALL_VALUE" -> null;
      case "LOCAL_READ", "FIELD_READ", "OBJECT_CREATE", "ARRAY_CREATE",
          "ARRAY_READ", "ARRAY_LENGTH", "DUP", "SWAP", "ARITHMETIC",
          "TYPE_CONVERSION", "TYPE_CHECK" -> null;
      case "ARRAY_WRITE" -> operands.size() >= 3
          ? normalizeExpression(operands.get(0)) + "["
              + normalizeExpression(operands.get(1)) + "] = "
              + normalizeExpression(operands.get(2)) + ";"
          : null;
      case "LOCAL_WRITE" -> translateLocalWrite(operands, context);
      case "FIELD_WRITE" -> translateFieldWrite(operands);
      case "METHOD_CALL" -> translateMethodCall(operands);
      case "CONDITIONAL_BRANCH", "JUMP", "TABLE_SWITCH", "LOOKUP_SWITCH" -> null;
      case "RETURN" -> operands.isEmpty()
          ? "return;"
          : "return " + normalizeExpression(operands.get(0)) + ";";
      case "THROW" -> operands.isEmpty()
          ? null
          : "throw " + normalizeExpression(operands.get(0)) + ";";
      default -> null;
    };
  }

  private static boolean isExpressionOnly(IrOperationModel operation) {
    return switch (operation.kind()) {
      case "CONSTANT", "LOCAL_READ", "FIELD_READ", "OBJECT_CREATE", "ARRAY_CREATE", "CALL_VALUE",
          "ARRAY_READ", "ARRAY_LENGTH", "DUP", "SWAP", "STACK_OPERATION", "ARITHMETIC",
          "TYPE_CONVERSION", "TYPE_CHECK", "DROP" -> true;
      default -> false;
    };
  }

  private static String translateDrop(List<String> operands) {
    if (operands.isEmpty()) return "";
    String expression = normalizeExpression(operands.get(0));
    // POP discards a value, but Java still evaluates side effects that produced it.
    // Pure constants, local reads, and arithmetic expressions can safely disappear.
    if (expression.contains("(") || expression.startsWith("new ")) return expression + ";";
    return "";
  }

  private static String translateLocalWrite(
      List<String> operands,
      TranslationContext context) {
    if (operands.size() < 2)
      return null;

    String local = operands.get(0);
    String value = normalizeExpression(operands.get(1));
    if (context.declareLocal(local)) {
      if ("null".equals(value))
        return "Object " + local + " = null;";
      return "var " + local + " = " + value + ";";
    }
    return local + " = " + value + ";";
  }

  private static String translateFieldWrite(List<String> operands) {
    if (operands.size() == 2)
      return operands.get(0) + " = " + normalizeExpression(operands.get(1)) + ";";
    if (operands.size() >= 3)
      return operands.get(0) + "." + simpleField(operands.get(1))
          + " = " + normalizeExpression(operands.get(2)) + ";";
    return null;
  }

  private static String translateMethodCall(List<String> operands) {
    if (operands.isEmpty())
      return null;
    return normalizeExpression(operands.get(0)) + ";";
  }

  private static String normalizeCondition(String condition) {
    String trimmed = condition == null ? "" : condition.trim();
    return trimmed.isEmpty() ? "false" : trimmed;
  }

  private static String negateCondition(String condition) {
    String trimmed = normalizeCondition(condition);

    String[] operators = {" == ", " != ", " <= ", " >= ", " < ", " > "};
    String[] inverses = {" != ", " == ", " > ", " < ", " >= ", " <= "};

    for (int i = 0; i < operators.length; i++) {
      int index = trimmed.indexOf(operators[i]);
      if (index >= 0) {
        return trimmed.substring(0, index)
            + inverses[i]
            + trimmed.substring(index + operators[i].length());
      }
    }

    if (trimmed.startsWith("!"))
      return trimmed.substring(1).trim();

    return "!(" + trimmed + ")";
  }

  private static String normalizeExpression(String expression) {
    if (expression == null)
      return null;

    String trimmed = expression.trim();

    if (isQuoted(trimmed) || isJavaLiteral(trimmed))
      return trimmed;

    if (trimmed.startsWith("new ") && !trimmed.endsWith(")"))
      return trimmed + "()";

    if (trimmed.startsWith("new ") && trimmed.endsWith(")")) {
      int open = trimmed.indexOf('(');
      if (open > 0 && open < trimmed.length() - 1) {
        String type = trimmed.substring(4, open);
        String argument = trimmed.substring(open + 1, trimmed.length() - 1);
        if (!argument.isBlank() && !isQuoted(argument) && !isJavaLiteral(argument)
            && !looksLikeExpression(argument)) {
          return "new " + type + "(\"" + escapeJava(argument) + "\")";
        }
      }
    }

    return trimmed;
  }

  private static String returnType(String descriptor) {
    if (descriptor == null)
      return "void";
    int close = descriptor.indexOf(')');
    if (close < 0 || close + 1 >= descriptor.length())
      return "void";
    return descriptorType(descriptor.substring(close + 1));
  }

  private static String parameterList(String descriptor) {
    if (descriptor == null)
      return "";
    int open = descriptor.indexOf('(');
    int close = descriptor.indexOf(')');
    if (open < 0 || close < open)
      return "";

    StringBuilder result = new StringBuilder();
    int index = open + 1;
    int localSlot = 0;
    while (index < close) {
      int start = index;
      while (index < close && descriptor.charAt(index) == '[')
        index++;
      if (index >= close)
        break;

      char type = descriptor.charAt(index);
      if (type == 'L') {
        int end = descriptor.indexOf(';', index);
        if (end < 0 || end > close)
          break;
        index = end + 1;
      } else {
        index++;
      }

      if (result.length() > 0)
        result.append(", ");
      result.append(descriptorType(descriptor.substring(start, index)))
          .append(" local")
          .append(localSlot);
      char parameterType = descriptor.charAt(start);
      localSlot += (parameterType == 'J' || parameterType == 'D') ? 2 : 1;
    }
    return result.toString();
  }

  private static String descriptorType(String descriptor) {
    if (descriptor == null || descriptor.isEmpty())
      return "Object";

    int arrayDepth = 0;
    while (arrayDepth < descriptor.length() && descriptor.charAt(arrayDepth) == '[')
      arrayDepth++;
    if (arrayDepth > 0)
      return descriptorType(descriptor.substring(arrayDepth)) + "[]".repeat(arrayDepth);

    return switch (descriptor.charAt(0)) {
      case 'V' -> "void";
      case 'Z' -> "boolean";
      case 'B' -> "byte";
      case 'C' -> "char";
      case 'S' -> "short";
      case 'I' -> "int";
      case 'J' -> "long";
      case 'F' -> "float";
      case 'D' -> "double";
      case 'L' -> {
        int end = descriptor.indexOf(';');
        yield end > 1 ? descriptor.substring(1, end).replace('/', '.') : "Object";
      }
      default -> "Object";
    };
  }

  private static boolean looksLikeExpression(String value) {
    String trimmed = value == null ? "" : value.trim();
    if (trimmed.isEmpty())
      return false;

    return trimmed.matches("local\\d+")
        || trimmed.matches("[A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)+")
        || trimmed.matches("[A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*\\s*\\([^)]*\\)")
        || trimmed.startsWith("new ")
        || (trimmed.startsWith("(") && trimmed.endsWith(")"))
        || trimmed.matches(".*\\s(?:\\+|-|\\*|/|%|<|>|==|!=|<=|>=|&&|\\|\\||\\?|:)\\s.*");
  }

  private static boolean isJavaLiteral(String value) {
    if ("null".equals(value) || "true".equals(value) || "false".equals(value))
      return true;

    return value.matches("-?(?:0|[1-9]\\d*)(?:[lLfFdD])?")
        || value.matches("-?(?:0|[1-9]\\d*)\\.\\d+(?:[fFdD])?");
  }

  private static boolean isQuoted(String value) {
    return value.length() >= 2
        && value.startsWith("\"")
        && value.endsWith("\"");
  }

  private static String escapeJava(String value) {
    return value.replace("\\", "\\\\").replace("\"", "\\\"");
  }

  private static String simpleField(String field) {
    int separator = field.lastIndexOf('.');
    return separator >= 0 ? field.substring(separator + 1) : field;
  }

  private static String indent(int level) {
    return "  ".repeat(Math.max(0, level));
  }

  private record SwitchArm(int key, String label) {}

  private record ConditionalRender(String source, boolean complete, int lastIndex) {}

  private record RenderResult(String source, boolean complete) {}
}
