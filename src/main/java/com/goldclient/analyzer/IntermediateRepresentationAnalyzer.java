package com.goldclient.analyzer;

import com.goldclient.model.InstructionModel;
import com.goldclient.model.IrMethodModel;
import com.goldclient.model.IrOperationModel;
import com.goldclient.model.MethodModel;
import java.util.ArrayList;
import java.util.List;

public final class IntermediateRepresentationAnalyzer {
  public IrMethodModel analyze(MethodModel method) {
    List<IrOperationModel> operations = new ArrayList<>();

    for (int i = 0; i < method.instructions().size(); i++) {
      InstructionModel instruction = method.instructions().get(i);
      String kind = kindOf(instruction);

      if (kind == null)
        continue;

      operations.add(new IrOperationModel(
          kind,
          instruction.operands(),
          i
      ));
    }

    return new IrMethodModel(method.name(), method.descriptor(), operations);
  }

  private static String kindOf(InstructionModel instruction) {
    String opcode = instruction.opcodeName();

    if ("LABEL".equals(opcode))
      return "LABEL";

    if (opcode.startsWith("ICONST_")
        || opcode.startsWith("LCONST_")
        || opcode.startsWith("FCONST_")
        || opcode.startsWith("DCONST_")
        || "BIPUSH".equals(opcode)
        || "SIPUSH".equals(opcode)
        || "LDC".equals(opcode))
      return "CONSTANT";

    if (opcode.startsWith("ILOAD")
        || opcode.startsWith("LLOAD")
        || opcode.startsWith("FLOAD")
        || opcode.startsWith("DLOAD")
        || opcode.startsWith("ALOAD"))
      return "LOCAL_READ";

    if (opcode.startsWith("ISTORE")
        || opcode.startsWith("LSTORE")
        || opcode.startsWith("FSTORE")
        || opcode.startsWith("DSTORE")
        || opcode.startsWith("ASTORE"))
      return "LOCAL_WRITE";

    if ("GETSTATIC".equals(opcode) || "GETFIELD".equals(opcode))
      return "FIELD_READ";

    if ("PUTSTATIC".equals(opcode) || "PUTFIELD".equals(opcode))
      return "FIELD_WRITE";

    if ("NEW".equals(opcode))
      return "OBJECT_CREATE";

    if ("ANEWARRAY".equals(opcode)
        || "NEWARRAY".equals(opcode)
        || "MULTIANEWARRAY".equals(opcode))
      return "ARRAY_CREATE";

    if ("INVOKEVIRTUAL".equals(opcode)
        || "INVOKEINTERFACE".equals(opcode)
        || "INVOKESPECIAL".equals(opcode)
        || "INVOKESTATIC".equals(opcode)
        || "INVOKEDYNAMIC".equals(opcode))
      return "METHOD_CALL";

    if (opcode.startsWith("IF"))
      return "CONDITIONAL_BRANCH";

    if ("GOTO".equals(opcode) || "GOTO_W".equals(opcode))
      return "JUMP";

    if ("TABLESWITCH".equals(opcode) || "LOOKUPSWITCH".equals(opcode))
      return "SWITCH";

    if ("RETURN".equals(opcode)
        || "IRETURN".equals(opcode)
        || "LRETURN".equals(opcode)
        || "FRETURN".equals(opcode)
        || "DRETURN".equals(opcode)
        || "ARETURN".equals(opcode)
        || "ATHROW".equals(opcode))
      return "TERMINATE";

    if ("POP".equals(opcode)
        || "POP2".equals(opcode)
        || "DUP".equals(opcode)
        || "DUP_X1".equals(opcode)
        || "DUP_X2".equals(opcode)
        || "DUP2".equals(opcode)
        || "DUP2_X1".equals(opcode)
        || "DUP2_X2".equals(opcode)
        || "SWAP".equals(opcode))
      return "STACK_OPERATION";

    if (opcode.endsWith("ADD")
        || opcode.endsWith("SUB")
        || opcode.endsWith("MUL")
        || opcode.endsWith("DIV")
        || opcode.endsWith("REM")
        || opcode.endsWith("NEG"))
      return "ARITHMETIC";

    if (opcode.startsWith("I2")
        || opcode.startsWith("L2")
        || opcode.startsWith("F2")
        || opcode.startsWith("D2")
        || "I2B".equals(opcode)
        || "I2C".equals(opcode)
        || "I2S".equals(opcode))
      return "TYPE_CONVERSION";

    if ("CHECKCAST".equals(opcode)
        || "INSTANCEOF".equals(opcode))
      return "TYPE_CHECK";

    return "OTHER";
  }
}
