package com.goldclient.analyzer;

import com.goldclient.model.InstructionModel;
import com.goldclient.model.IrMethodModel;
import com.goldclient.model.MethodModel;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class IntermediateRepresentationAnalyzerTest {
  @Test
  void reconstructsSimpleExceptionPath() {
    List<InstructionModel> instructions = List.of(
        instruction(178, "GETSTATIC", "example/Test", "CONFIG", "Lexample/Config;"),
        instruction(199, "IFNONNULL", "L0"),
        instruction(187, "NEW", "java/lang/IllegalStateException"),
        instruction(89, "DUP"),
        instruction(18, "LDC", "missing"),
        instruction(183, "INVOKESPECIAL", "java/lang/IllegalStateException", "<init>", "(Ljava/lang/String;)V", "false"),
        instruction(191, "ATHROW"),
        instruction(-1, "LABEL", "L0"),
        instruction(177, "RETURN")
    );

    MethodModel method = method("test", "()V", instructions);
    IrMethodModel ir = new IntermediateRepresentationAnalyzer().analyze(method);

    assertTrue(ir.operations().stream().anyMatch(o ->
        o.kind().equals("FIELD_READ") && o.operands().get(0).equals("example.Test.CONFIG")));

    assertTrue(ir.operations().stream().anyMatch(o ->
        o.kind().equals("CONDITIONAL_BRANCH")
            && o.operands().get(0).equals("example.Test.CONFIG != null")));

    assertTrue(ir.operations().stream().anyMatch(o ->
        o.kind().equals("METHOD_CALL")
            && o.operands().get(0).contains("new java.lang.IllegalStateException")));

    assertTrue(ir.operations().stream().anyMatch(o -> o.kind().equals("THROW")));
  }

  @Test
  void carriesOperandValuesAcrossControlFlowJoin() {
    List<InstructionModel> instructions = List.of(
        instruction(25, "ALOAD", "0"),
        instruction(198, "IFNULL", "L0"),
        instruction(25, "ALOAD", "0"),
        instruction(167, "GOTO", "L1"),
        instruction(-1, "LABEL", "L0"),
        instruction(25, "ALOAD", "0"),
        instruction(-1, "LABEL", "L1"),
        instruction(58, "ASTORE", "1"),
        instruction(177, "RETURN")
    );

    MethodModel method = method("join", "()V", instructions);
    IrMethodModel ir = new IntermediateRepresentationAnalyzer().analyze(method);

    assertTrue(ir.operations().stream().anyMatch(o ->
        o.kind().equals("LOCAL_WRITE")
            && o.operands().get(0).equals("local1")
            && o.operands().get(1).equals("local0")));
  }

  @Test
  void reconstructsIntegerComparison() {
    List<InstructionModel> instructions = List.of(
        instruction(21, "ILOAD", "1"),
        instruction(21, "ILOAD", "2"),
        instruction(159, "IF_ICMPEQ", "L0"),
        instruction(177, "RETURN"),
        instruction(-1, "LABEL", "L0"),
        instruction(177, "RETURN")
    );

    MethodModel method = method("compare", "()V", instructions);
    IrMethodModel ir = new IntermediateRepresentationAnalyzer().analyze(method);

    assertTrue(ir.operations().stream().anyMatch(o ->
        o.kind().equals("CONDITIONAL_BRANCH")
            && o.operands().get(0).equals("local1 == local2")));
  }

  private static MethodModel method(
      String name,
      String descriptor,
      List<InstructionModel> instructions) {
    MethodModel raw = new MethodModel(
        name, descriptor, null, 0,
        List.of(), List.of(), List.of(),
        instructions, List.of(), 3, 3
    );

    return new MethodModel(
        name, descriptor, null, 0,
        List.of(), List.of(), List.of(),
        instructions, List.of(), 3, 3,
        new ControlFlowAnalyzer().analyze(raw)
    );
  }

  private static InstructionModel instruction(int opcode, String name, String... operands) {
    return new InstructionModel(opcode, name, List.of(operands));
  }
}
