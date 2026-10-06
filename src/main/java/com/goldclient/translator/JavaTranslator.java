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

      if ("METHOD_CALL".equals(operation.kind())
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
            .append("// TODO: JUMP ")
            .append(operation.operands())
            .append("\n");
        complete = false;
        continue;
      }

      String line = translateOperation(operation, context);
      if (line == null) {
        if (isExpressionOnly(operation))
          continue;

        source.append(indent(indent))
            .append("// TODO: ")
            .append(operation.kind())
            .append(" ")
            .append(operation.operands())
            .append("\n");
        complete = false;
      } else if (!line.isBlank()) {
        source.append(indent(indent)).append(line).append("\n");
      }
    }

    return new RenderResult(source.toString(), complete);
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
      case "LABEL", "DROP" -> "";
      case "LOCAL_READ", "FIELD_READ", "OBJECT_CREATE", "ARRAY_CREATE",
          "DUP", "SWAP", "ARITHMETIC", "TYPE_CONVERSION", "TYPE_CHECK" -> null;
      case "LOCAL_WRITE" -> translateLocalWrite(operands, context);
      case "FIELD_WRITE" -> translateFieldWrite(operands);
      case "METHOD_CALL" -> translateMethodCall(operands);
      case "CONDITIONAL_BRANCH", "JUMP", "SWITCH" -> null;
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
      case "CONSTANT", "LOCAL_READ", "FIELD_READ", "OBJECT_CREATE", "ARRAY_CREATE",
          "DUP", "SWAP", "ARITHMETIC", "TYPE_CONVERSION", "TYPE_CHECK", "DROP" -> true;
      default -> false;
    };
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

    return trimmed.matches("local\\\\d+")
        || trimmed.matches("[A-Za-z_$][\\\\w$]*(?:\\\\.[A-Za-z_$][\\\\w$]*)+")
        || trimmed.matches("[A-Za-z_$][\\\\w$]*(?:\\\\.[A-Za-z_$][\\\\w$]*)*\\\\s*\\\\([^)]*\\\\)")
        || trimmed.startsWith("new ")
        || (trimmed.startsWith("(") && trimmed.endsWith(")"))
        || trimmed.matches(".*[+\\\\-*/%<>=!&|].*");
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

  private record ConditionalRender(String source, boolean complete, int lastIndex) {}

  private record RenderResult(String source, boolean complete) {}
}
