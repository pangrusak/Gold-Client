package com.goldclient.analyzer;

import com.goldclient.model.InstructionModel;
import com.goldclient.model.IrMethodModel;
import com.goldclient.model.MethodModel;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class IntermediateRepresentationAnalyzerTest {
  @Test
  void routesArrayLoadsStoresAndLengthsThroughRuntimeChecks() {
    MethodModel method = method("arrays", "([II)V", List.of(
        instruction(25, "ALOAD", "0"),
        instruction(21, "ILOAD", "1"),
        instruction(46, "IALOAD"),
        instruction(87, "POP"),
        instruction(25, "ALOAD", "0"),
        instruction(21, "ILOAD", "1"),
        instruction(5, "ICONST_2"),
        instruction(79, "IASTORE"),
        instruction(25, "ALOAD", "0"),
        instruction(190, "ARRAYLENGTH"),
        instruction(87, "POP"),
        instruction(177, "RETURN")));

    IrMethodModel ir = new IntermediateRepresentationAnalyzer().analyze(method);

    assertTrue(ir.operations().stream().anyMatch(operation ->
        "ARRAY_READ".equals(operation.kind())
            && operation.operands().get(0).contains("GoldClientRuntime.arrayGet(local0, local1)")),
        ir.operations().toString());
    assertTrue(ir.operations().stream().anyMatch(operation ->
        "ARRAY_WRITE".equals(operation.kind())
            && operation.operands().get(0).equals("local0")
            && operation.operands().get(1).equals("local1")),
        ir.operations().toString());
    assertTrue(ir.operations().stream().anyMatch(operation ->
        "ARRAY_LENGTH".equals(operation.kind())
            && operation.operands().get(0).contains("GoldClientRuntime.arrayLength(local0)")),
        ir.operations().toString());
  }

  @Test
  void wrapsIincAndEscapesStringConstants() {
    MethodModel method = method("increment", "()V", List.of(
        instruction(132, "IINC", "1", "1"),
        instruction(18, "LDC", "line one\nline two\\path"),
        instruction(87, "POP"),
        instruction(177, "RETURN")));

    IrMethodModel ir = new IntermediateRepresentationAnalyzer().analyze(method);

    assertTrue(ir.operations().stream().anyMatch(operation ->
        "LOCAL_WRITE".equals(operation.kind())
            && operation.operands().get(1).contains("GoldClientRuntime.toInt32(local1 + (1))")),
        ir.operations().toString());
    assertTrue(ir.operations().stream().anyMatch(operation ->
        "CONSTANT".equals(operation.kind())
            && operation.operands().get(0).contains("\\n")
            && operation.operands().get(0).contains("\\\\path")),
        ir.operations().toString());
  }

  @Test
  void lowersStringConcatFactoryMakeConcatIntoRuntimeConcat() {
    MethodModel method = method("concat", "(Ljava/lang/String;I)Ljava/lang/String;", List.of(
        instruction(25, "ALOAD", "0"),
        instruction(21, "ILOAD", "1"),
        instruction(186, "INVOKEDYNAMIC", "makeConcat",
            "(Ljava/lang/String;I)Ljava/lang/String;",
            "Handle(6,java/lang/invoke/StringConcatFactory,makeConcat,()V,false)"),
        instruction(176, "ARETURN")));

    IrMethodModel ir = new IntermediateRepresentationAnalyzer().analyze(method);

    assertTrue(ir.operations().stream().anyMatch(operation ->
        "CALL_VALUE".equals(operation.kind())
            && operation.operands().get(0).contains("GoldClientRuntime.concat([local0, local1])")),
        ir.operations().toString());
    assertFalse(ir.operations().stream().anyMatch(operation ->
        "UNSUPPORTED_DYNAMIC".equals(operation.kind())), ir.operations().toString());
  }

  @Test
  void lowersPrimitiveNarrowingAndReferenceChecksToRuntimeCalls() {
    MethodModel method = method("casts", "(Ljava/lang/Object;I)V", List.of(
        instruction(25, "ALOAD", "0"),
        instruction(192, "CHECKCAST", "java/lang/String"),
        instruction(87, "POP"),
        instruction(21, "ILOAD", "1"),
        instruction(145, "I2B"),
        instruction(87, "POP"),
        instruction(25, "ALOAD", "0"),
        instruction(193, "INSTANCEOF", "java/lang/String"),
        instruction(87, "POP"),
        instruction(177, "RETURN")));

    IrMethodModel ir = new IntermediateRepresentationAnalyzer().analyze(method);

    assertTrue(ir.operations().stream().anyMatch(operation ->
        "TYPE_CHECK".equals(operation.kind())
            && operation.operands().stream().anyMatch(value ->
                value.contains("GoldClientRuntime.checkCast(local0, \"java.lang.String\")"))),
        ir.operations().toString());
    assertTrue(ir.operations().stream().anyMatch(operation ->
        "TYPE_CONVERSION".equals(operation.kind())
            && operation.operands().get(1).contains("GoldClientRuntime.toByte(local1)")),
        ir.operations().toString());
    assertTrue(ir.operations().stream().anyMatch(operation ->
        "TYPE_CHECK".equals(operation.kind())
            && operation.operands().stream().anyMatch(value ->
                value.contains("GoldClientRuntime.instanceOf(local0, \"java.lang.String\")"))),
        ir.operations().toString());
  }

  @Test
  void lowersLambdaMetafactoryInvokedynamicIntoAClosure() {
    MethodModel method = method("lambda", "()V", List.of(
        instruction(186, "INVOKEDYNAMIC", "run", "()Ljava/lang/Runnable;",
            "Handle(6,java/lang/invoke/LambdaMetafactory,metafactory,(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;,false)",
            "Type(()V)",
            "Handle(6,example/Test,lambda$run$0,()V,false)",
            "Type(()V)"),
        instruction(87, "POP"),
        instruction(177, "RETURN")));
    IrMethodModel ir = new IntermediateRepresentationAnalyzer().analyze(method);
    assertTrue(ir.operations().stream().anyMatch(operation ->
        operation.kind().equals("CALL_VALUE") && operation.operands().get(0).contains("=>")));
    assertFalse(ir.operations().stream().anyMatch(operation ->
        operation.kind().equals("UNSUPPORTED_DYNAMIC")));
  }

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
        o.kind().equals("CALL_VALUE")
            && o.operands().get(0).contains("new java.lang.IllegalStateException")));

    assertTrue(ir.operations().stream().anyMatch(o -> o.kind().equals("THROW")));
  }

  @Test
  void preservesStringConstructorArguments() {
    List<InstructionModel> instructions = List.of(
        instruction(187, "NEW", "java/lang/IllegalStateException"),
        instruction(89, "DUP"),
        instruction(18, "LDC", "missing"),
        instruction(183, "INVOKESPECIAL", "java/lang/IllegalStateException", "<init>", "(Ljava/lang/String;)V", "false"),
        instruction(191, "ATHROW")
    );

    MethodModel method = method("test", "()V", instructions);
    IrMethodModel ir = new IntermediateRepresentationAnalyzer().analyze(method);

    assertTrue(ir.operations().stream().anyMatch(o ->
        o.kind().equals("CALL_VALUE")
            && o.operands().get(0).equals(
                "new java.lang.IllegalStateException(\"missing\")")));

    assertTrue(ir.operations().stream().anyMatch(o ->
        o.kind().equals("THROW")
            && o.operands().get(0).equals(
                "new java.lang.IllegalStateException(\"missing\")")));
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

  @Test
  void translatesArrayLoadsStoresAndBitwiseArithmetic() {
    List<InstructionModel> instructions = List.of(
        instruction(25, "ALOAD", "0"),
        instruction(21, "ILOAD", "1"),
        instruction(21, "ILOAD", "1"),
        instruction(21, "ILOAD", "2"),
        instruction(126, "IAND"),
        instruction(79, "IASTORE"),
        instruction(25, "ALOAD", "0"),
        instruction(21, "ILOAD", "1"),
        instruction(46, "IALOAD"),
        instruction(87, "POP"),
        instruction(25, "ALOAD", "0"),
        instruction(190, "ARRAYLENGTH"),
        instruction(172, "IRETURN"));
    IrMethodModel ir = new IntermediateRepresentationAnalyzer().analyze(
        method("arrays", "([III)I", instructions));
    assertTrue(ir.operations().stream().anyMatch(o ->
        o.kind().equals("ARITHMETIC") && o.operands().get(0).equals("(local1 & local2)")));
    assertTrue(ir.operations().stream().anyMatch(o ->
        o.kind().equals("ARRAY_WRITE") && o.operands().equals(
            List.of("local0", "local1", "(local1 & local2)"))));
    assertTrue(ir.operations().stream().anyMatch(o ->
        o.kind().equals("ARRAY_READ") && o.operands().get(0).equals("local0[local1]")));
    assertTrue(ir.operations().stream().anyMatch(o ->
        o.kind().equals("ARRAY_LENGTH") && o.operands().get(0).equals("local0.length")));
  }

  @Test
  void translatesShiftAndUnsignedShiftOpcodes() {
    List<InstructionModel> instructions = List.of(
        instruction(21, "ILOAD", "1"),
        instruction(21, "ILOAD", "2"),
        instruction(120, "ISHL"),
        instruction(54, "ISTORE", "3"),
        instruction(21, "ILOAD", "3"),
        instruction(21, "ILOAD", "2"),
        instruction(124, "IUSHR"),
        instruction(54, "ISTORE", "4"),
        instruction(177, "RETURN"));
    IrMethodModel ir = new IntermediateRepresentationAnalyzer().analyze(
        method("shifts", "(II)V", instructions));
    assertTrue(ir.operations().stream().anyMatch(o ->
        o.kind().equals("LOCAL_WRITE") && o.operands().get(1).equals("(local1 << local2)")));
    assertTrue(ir.operations().stream().anyMatch(o ->
        o.kind().equals("LOCAL_WRITE") && o.operands().get(1).equals("(local3 >>> local2)")));
  }

  @Test
  void annotatesExceptionTableRegionsInSemanticIr() {
    List<InstructionModel> instructions = List.of(
        instruction(-1, "LABEL", "Ltry"),
        instruction(184, "INVOKESTATIC", "example/Calls", "mightFail", "()V", "false"),
        instruction(-1, "LABEL", "Lend"),
        instruction(167, "GOTO", "Ljoin"),
        instruction(-1, "LABEL", "Lhandler"),
        instruction(58, "ASTORE", "0"),
        instruction(177, "RETURN"),
        instruction(-1, "LABEL", "Ljoin"),
        instruction(177, "RETURN"));
    MethodModel raw = new MethodModel(
        "guarded", "()V", null, 0, List.of(), List.of(), List.of(),
        instructions, List.of(new com.goldclient.model.TryCatchModel(
            "Ltry", "Lend", "Lhandler", "java/lang/IllegalArgumentException")), 2, 1);
    MethodModel analyzed = new MethodModel(
        raw.name(), raw.descriptor(), raw.signature(), raw.access(), raw.exceptions(),
        raw.annotations(), raw.referencedClasses(), raw.instructions(), raw.tryCatchBlocks(),
        raw.maxStack(), raw.maxLocals(), new ControlFlowAnalyzer().analyze(raw));
    IrMethodModel ir = new IntermediateRepresentationAnalyzer().analyze(analyzed);
    assertTrue(ir.operations().stream().anyMatch(o -> o.kind().equals("TRY_BEGIN")));
    assertTrue(ir.operations().stream().anyMatch(o -> o.kind().equals("TRY_END")));
    assertTrue(ir.operations().stream().anyMatch(o -> o.kind().equals("CATCH_BEGIN")));

    int tryEnd = indexOfKind(ir, "TRY_END");
    int catchBegin = indexOfKind(ir, "CATCH_BEGIN");
    int handlerLabel = indexOfLabel(ir, "Lhandler");
    assertTrue(tryEnd < catchBegin, ir.operations().toString());
    assertTrue(catchBegin < handlerLabel, ir.operations().toString());
  }

  @Test
  void integerArithmeticUsesJavaOverflowAndDivisionHelpers() {
    MethodModel method = method("integerMath", "()V", List.of(
        instruction(4, "ICONST_1"),
        instruction(5, "ICONST_2"),
        instruction(96, "IADD"),
        instruction(87, "POP"),
        instruction(4, "ICONST_1"),
        instruction(5, "ICONST_2"),
        instruction(108, "IDIV"),
        instruction(87, "POP"),
        instruction(177, "RETURN")));

    IrMethodModel ir = new IntermediateRepresentationAnalyzer().analyze(method);

    assertTrue(ir.operations().stream().anyMatch(operation ->
        "ARITHMETIC".equals(operation.kind())
            && operation.operands().contains("GoldClientRuntime.toInt32((1 + 2))")),
        ir.operations().toString());
    assertTrue(ir.operations().stream().anyMatch(operation ->
        "ARITHMETIC".equals(operation.kind())
            && operation.operands().contains("GoldClientRuntime.intDiv(1, 2)")),
        ir.operations().toString());
  }

  @Test
  void unsupportedOpcodesKeepTheirOriginalNamesInSemanticIr() {
    MethodModel method = method("unsupported", "()V", List.of(
        instruction(202, "BREAKPOINT"),
        instruction(177, "RETURN")));

    IrMethodModel ir = new IntermediateRepresentationAnalyzer().analyze(method);

    assertTrue(ir.operations().stream().anyMatch(operation ->
        "UNSUPPORTED_OPCODE".equals(operation.kind())
            && operation.operands().contains("BREAKPOINT")),
        ir.operations().toString());
  }

  @Test
  void dupX1PreservesOperandOrderForTheNextStaticCall() {
    MethodModel method = method("stack", "()V", List.of(
        instruction(4, "ICONST_1"),
        instruction(5, "ICONST_2"),
        instruction(90, "DUP_X1"),
        instruction(184, "INVOKESTATIC", "example/Owner", "consume", "(II)V", "false"),
        instruction(87, "POP"),
        instruction(177, "RETURN")));

    IrMethodModel ir = new IntermediateRepresentationAnalyzer().analyze(method);

    assertTrue(ir.operations().stream().anyMatch(operation ->
        "METHOD_CALL".equals(operation.kind())
            && operation.operands().contains("example.Owner.consume(1, 2)")),
        ir.operations().toString());
  }

  @Test
  void dup2AndPop2TreatLongAsOneCategoryTwoValue() {
    MethodModel method = method("wideStack", "()V", List.of(
        instruction(9, "LCONST_0"),
        instruction(92, "DUP2"),
        instruction(88, "POP2"),
        instruction(88, "POP2"),
        instruction(177, "RETURN")));

    IrMethodModel ir = new IntermediateRepresentationAnalyzer().analyze(method);

    assertTrue(ir.operations().stream().filter(operation ->
        "DROP".equals(operation.kind())).count() == 2, ir.operations().toString());
    assertFalse(ir.operations().stream().anyMatch(operation ->
        operation.operands().stream().anyMatch(value -> value.contains("<unknown>"))),
        ir.operations().toString());
  }

  @Test
  void lowersMultiANewArrayIntoAUsableRuntimeExpression() {
    MethodModel method = method("matrix", "()V", List.of(
        instruction(5, "ICONST_2"),
        instruction(6, "ICONST_3"),
        instruction(197, "MULTIANEWARRAY", "[[I", "2"),
        instruction(87, "POP"),
        instruction(177, "RETURN")));

    IrMethodModel ir = new IntermediateRepresentationAnalyzer().analyze(method);

    assertTrue(ir.operations().stream().anyMatch(operation ->
        "ARRAY_CREATE".equals(operation.kind())
            && operation.operands().contains("GoldClientRuntime.newMultiArray(\"int\", [2, 3], 2)")),
        ir.operations().toString());
  }

  @Test
  void longCompareProducesAnExpressionForConditionalBranch() {
    MethodModel method = method("compare", "()V", List.of(
        instruction(9, "LCONST_0"),
        instruction(10, "LCONST_1"),
        instruction(148, "LCMP"),
        instruction(153, "IFEQ", "L0"),
        instruction(177, "RETURN"),
        instruction(-1, "LABEL", "L0"),
        instruction(177, "RETURN")));

    IrMethodModel ir = new IntermediateRepresentationAnalyzer().analyze(method);

    assertTrue(ir.operations().stream().anyMatch(operation ->
        "ARITHMETIC".equals(operation.kind())
            && operation.operands().get(0).contains("0L")
            && operation.operands().get(0).contains("1L")),
        ir.operations().toString());
    assertTrue(ir.operations().stream().anyMatch(operation ->
        "CONDITIONAL_BRANCH".equals(operation.kind())
            && operation.operands().get(0).contains("== 0")),
        ir.operations().toString());
  }

  private static int indexOfKind(IrMethodModel method, String kind) {
    for (int i = 0; i < method.operations().size(); i++) {
      if (kind.equals(method.operations().get(i).kind())) return i;
    }
    return -1;
  }

  private static int indexOfLabel(IrMethodModel method, String label) {
    for (int i = 0; i < method.operations().size(); i++) {
      var operation = method.operations().get(i);
      if ("LABEL".equals(operation.kind()) && !operation.operands().isEmpty()
          && label.equals(operation.operands().get(0))) return i;
    }
    return -1;
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
