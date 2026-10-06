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

    for (IrOperationModel operation : method.operations()) {
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

  private static String translateOperation(
      IrOperationModel operation,
      TranslationContext context) {
    List<String> operands = operation.operands();

    return switch (operation.kind()) {
      case "LABEL" -> "";
      case "CONSTANT", "LOCAL_READ", "FIELD_READ", "OBJECT_CREATE", "ARRAY_CREATE",
          "DUP", "SWAP", "ARITHMETIC", "TYPE_CONVERSION", "TYPE_CHECK" -> "";
      case "LOCAL_WRITE" -> translateLocalWrite(operands, context);
      case "FIELD_WRITE" -> translateFieldWrite(operands);
      case "METHOD_CALL" -> translateMethodCall(operands);
      case "CONDITIONAL_BRANCH" -> translateConditional(operands);
      case "JUMP" -> translateJump(operands);
      case "RETURN" -> operands.isEmpty() ? "return;" : "return " + operands.get(0) + ";";
      case "THROW" -> operands.isEmpty() ? null : "throw " + operands.get(0) + ";";
      case "DROP" -> "";
      default -> null;
    };
  }

  private static String translateLocalWrite(
      List<String> operands,
      TranslationContext context) {
    if (operands.size() < 2)
      return null;

    String local = operands.get(0);
    String value = operands.get(1);
    if (context.declareLocal(local))
      return "Object " + local + " = " + value + ";";
    return local + " = " + value + ";";
  }

  private static String translateFieldWrite(List<String> operands) {
    if (operands.size() == 2)
      return operands.get(0) + " = " + operands.get(1) + ";";
    if (operands.size() >= 3)
      return operands.get(0) + "." + simpleField(operands.get(1))
          + " = " + operands.get(2) + ";";
    return null;
  }

  private static String translateMethodCall(List<String> operands) {
    if (operands.isEmpty())
      return null;
    return operands.get(0).endsWith(";") ? operands.get(0) : operands.get(0) + ";";
  }

  private static String translateConditional(List<String> operands) {
    if (operands.size() < 2)
      return null;
    return "if (" + operands.get(0) + ") { // target " + operands.get(1) + " }";
  }

  private static String translateJump(List<String> operands) {
    if (operands.isEmpty())
      return null;
    return "// goto " + operands.get(0);
  }

  private static String simpleField(String field) {
    int separator = field.lastIndexOf('.');
    return separator >= 0 ? field.substring(separator + 1) : field;
  }
}
