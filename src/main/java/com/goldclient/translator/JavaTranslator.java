package com.goldclient.translator;

import com.goldclient.model.IrMethodModel;
import com.goldclient.model.IrOperationModel;
import java.util.List;

public final class JavaTranslator {
  public TranslationResult translate(IrMethodModel method) {
    StringBuilder source = new StringBuilder();
    TranslationContext context = new TranslationContext();
    boolean complete = true;

    source.append("void ").append(method.name()).append("() {\n");

    List<IrOperationModel> operations = method.operations();
    for (int i = 0; i < operations.size(); i++) {
      IrOperationModel operation = operations.get(i);

      if ("FIELD_READ".equals(operation.kind())
          && i + 1 < operations.size()
          && "CONDITIONAL_BRANCH".equals(operations.get(i + 1).kind())) {
        continue;
      }

      if ("CONDITIONAL_BRANCH".equals(operation.kind())
          && operation.operands().size() >= 2) {
        BranchTranslation branch = translateConditionalBlock(operations, i, operation);
        if (branch != null) {
          source.append(branch.source());
          i = branch.lastIndex();
          continue;
        }
      }

      String line = translateOperation(operation, context);
      if (line == null) {
        source.append("  // TODO: ").append(operation.kind())
            .append(" ").append(operation.operands()).append("\n");
        complete = false;
      } else if (!line.isBlank()) {
        source.append("  ").append(line).append("\n");
      }
    }

    source.append("}");
    return new TranslationResult(source.toString(), complete);
  }

  private static BranchTranslation translateConditionalBlock(
      List<IrOperationModel> operations,
      int branchIndex,
      IrOperationModel branch) {
    String condition = branch.operands().get(0);
    String target = branch.operands().get(1);

    int targetIndex = -1;
    for (int i = branchIndex + 1; i < operations.size(); i++) {
      IrOperationModel operation = operations.get(i);
      if ("LABEL".equals(operation.kind())
          && !operation.operands().isEmpty()
          && target.equals(operation.operands().get(0))) {
        targetIndex = i;
        break;
      }
    }

    if (targetIndex < 0)
      return null;

    StringBuilder body = new StringBuilder();
    String pendingConstruction = null;

    for (int i = branchIndex + 1; i < targetIndex; i++) {
      IrOperationModel operation = operations.get(i);

      switch (operation.kind()) {
        case "OBJECT_CREATE" -> {
          if (!operation.operands().isEmpty())
            pendingConstruction = operation.operands().get(0);
        }
        case "CONSTANT" -> {
          // The constructor call carries the constant argument in the current IR.
        }
        case "DUP" -> {
          // Stack duplication is already represented by the eventual constructor call.
        }
        case "METHOD_CALL" -> {
          if (!operation.operands().isEmpty())
            pendingConstruction = normalizeExpression(operation.operands().get(0));
        }
        case "THROW" -> {
          String expression = pendingConstruction != null
              ? pendingConstruction
              : (operation.operands().isEmpty()
                  ? null
                  : normalizeExpression(operation.operands().get(0)));

          if (expression == null)
            return null;

          body.append("    throw ").append(expression).append(";\n");
          pendingConstruction = null;
        }
        default -> {
          return null;
        }
      }
    }

    if (body.isEmpty())
      return null;

    StringBuilder source = new StringBuilder();
    source.append("  if (").append(normalizeNegatedCondition(condition)).append(") {\n");
    source.append(body);
    source.append("  }\n");
    return new BranchTranslation(source.toString(), targetIndex);
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
      case "CONDITIONAL_BRANCH" -> null;
      case "JUMP" -> null;
      case "RETURN" -> operands.isEmpty() ? "return;" : "return " + normalizeExpression(operands.get(0)) + ";";
      case "THROW" -> operands.isEmpty() ? null : "throw " + normalizeExpression(operands.get(0)) + ";";
      default -> null;
    };
  }

  private static String translateLocalWrite(
      List<String> operands,
      TranslationContext context) {
    if (operands.size() < 2)
      return null;

    String local = operands.get(0);
    String value = normalizeExpression(operands.get(1));
    if (context.declareLocal(local))
      return "Object " + local + " = " + value + ";";
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

  private static String normalizeNegatedCondition(String condition) {
    String trimmed = condition == null ? "" : condition.trim();
    if (trimmed.endsWith(" != null")) {
      return trimmed.substring(0, trimmed.length() - " != null".length()) + " == null";
    }
    if (trimmed.endsWith(" == null")) {
      return trimmed.substring(0, trimmed.length() - " == null".length()) + " != null";
    }
    if (trimmed.startsWith("!")) {
      return trimmed.substring(1).trim();
    }
    return "!(" + trimmed + ")";
  }

  private static String normalizeExpression(String expression) {
    if (expression == null)
      return null;

    String trimmed = expression.trim();
    if (trimmed.startsWith("new ") && !trimmed.endsWith(")")) {
      return trimmed + "()";
    }

    if (trimmed.startsWith("new ") && trimmed.endsWith(")")) {
      int open = trimmed.indexOf('(');
      if (open > 0 && open < trimmed.length() - 1) {
        String type = trimmed.substring(4, open);
        String argument = trimmed.substring(open + 1, trimmed.length() - 1);
        if (!argument.isBlank() && !isQuoted(argument))
          return "new " + type + "(\"" + escapeJava(argument) + "\")";
      }
    }

    return trimmed;
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

  private record BranchTranslation(String source, int lastIndex) {}
}
