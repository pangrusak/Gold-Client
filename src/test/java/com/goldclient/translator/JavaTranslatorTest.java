package com.goldclient.translator;

import com.goldclient.model.IrMethodModel;
import com.goldclient.model.IrOperationModel;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaTranslatorTest {
  @Test
  void emitsReturnedCallsOnlyOnceAndPreservesDiscardedCallSideEffects() {
    IrMethodModel returnedCall = new IrMethodModel("value", "()I", List.of(
        new IrOperationModel("CALL_VALUE", List.of("this.compute()"), 0),
        new IrOperationModel("RETURN", List.of("this.compute()"), 1)));
    String returned = new JavaTranslator().translate(returnedCall).source();
    assertEquals(returned.indexOf("this.compute()"), returned.lastIndexOf("this.compute()"), returned);

    IrMethodModel discardedCall = new IrMethodModel("discard", "()V", List.of(
        new IrOperationModel("CALL_VALUE", List.of("this.compute()"), 0),
        new IrOperationModel("DROP", List.of("this.compute()"), 1),
        new IrOperationModel("RETURN", List.of(), 2)));
    String discarded = new JavaTranslator().translate(discardedCall).source();
    assertEquals(discarded.indexOf("this.compute();"), discarded.lastIndexOf("this.compute();"), discarded);
  }

  @Test
  void translatesLithiumStyleNullCheck() {
    IrMethodModel method = new IrMethodModel(
        "onInitialize",
        "()V",
        List.of(
            new IrOperationModel(
                "FIELD_READ",
                List.of("me.jellysquid.mods.lithium.common.LithiumMod.CONFIG"),
                0),
            new IrOperationModel(
                "CONDITIONAL_BRANCH",
                List.of("me.jellysquid.mods.lithium.common.LithiumMod.CONFIG != null", "L0"),
                1),
            new IrOperationModel(
                "OBJECT_CREATE",
                List.of("new java.lang.IllegalStateException"),
                2),
            new IrOperationModel("DUP", List.of("new java.lang.IllegalStateException"), 3),
            new IrOperationModel(
                "CONSTANT",
                List.of("The mixin plugin did not initialize the config! Did it not load?"),
                4),
            new IrOperationModel(
                "METHOD_CALL",
                List.of("new java.lang.IllegalStateException(The mixin plugin did not initialize the config! Did it not load?)"),
                5),
            new IrOperationModel(
                "THROW",
                List.of("new java.lang.IllegalStateException"),
                6),
            new IrOperationModel("LABEL", List.of("L0"), 7),
            new IrOperationModel("RETURN", List.of(), 8)
        ));

    TranslationResult result = new JavaTranslator().translate(method);

    assertTrue(result.complete());
    assertTrue(result.source().contains(
        "if (me.jellysquid.mods.lithium.common.LithiumMod.CONFIG == null) {"));
    assertTrue(result.source().contains(
        "throw new java.lang.IllegalStateException(\"The mixin plugin did not initialize the config! Did it not load?\");"));
    assertTrue(result.source().contains("return;"));
  }

  @Test
  void preservesPunctuationInConstructorStringArguments() {
    IrMethodModel method = new IrMethodModel(
        "test",
        "()V",
        List.of(new IrOperationModel(
            "THROW",
            List.of("new java.lang.IllegalStateException(The config did not initialize!)"),
            0)));

    TranslationResult result = new JavaTranslator().translate(method);

    assertTrue(result.complete());
    assertTrue(result.source().contains(
        "throw new java.lang.IllegalStateException(\"The config did not initialize!\");"));
  }

  @Test
  void translatesConstantsAndExpressions() {
    IrMethodModel method = new IrMethodModel(
        "test",
        "()V",
        List.of(
            new IrOperationModel("CONSTANT", List.of("2"), 0),
            new IrOperationModel("CONSTANT", List.of("3"), 1),
            new IrOperationModel("ARITHMETIC", List.of("(2 + 3)"), 2),
            new IrOperationModel("LOCAL_WRITE", List.of("local1", "(2 + 3)"), 3),
            new IrOperationModel("LOCAL_READ", List.of("local1"), 4),
            new IrOperationModel("METHOD_CALL", List.of("System.out.println(local1)"), 5),
            new IrOperationModel("RETURN", List.of(), 6)));

    TranslationResult result = new JavaTranslator().translate(method);

    assertTrue(result.complete());
    assertTrue(result.source().contains("var local1 = (2 + 3);"));
    assertTrue(result.source().contains("System.out.println(local1);"));
  }

  @Test
  void translatesMethodArgumentsAndObjectConstruction() {
    IrMethodModel method = new IrMethodModel(
        "test",
        "()V",
        List.of(
            new IrOperationModel("CONSTANT", List.of("hello"), 0),
            new IrOperationModel(
                "METHOD_CALL",
                List.of("new java.lang.IllegalStateException(hello)"),
                1),
            new IrOperationModel("THROW", List.of("new java.lang.IllegalStateException(hello)"), 2)));

    TranslationResult result = new JavaTranslator().translate(method);

    assertTrue(result.complete());
    assertTrue(result.source().contains(
        "throw new java.lang.IllegalStateException(\"hello\");"));
  }

  @Test
  void translatesIfElse() {
    IrMethodModel method = new IrMethodModel(
        "test",
        "()V",
        List.of(
            new IrOperationModel("CONDITIONAL_BRANCH", List.of("local1 == 0", "Lelse"), 0),
            new IrOperationModel("METHOD_CALL", List.of("foo()"), 1),
            new IrOperationModel("JUMP", List.of("Lend"), 2),
            new IrOperationModel("LABEL", List.of("Lelse"), 3),
            new IrOperationModel("METHOD_CALL", List.of("bar()"), 4),
            new IrOperationModel("LABEL", List.of("Lend"), 5),
            new IrOperationModel("RETURN", List.of(), 6)));

    TranslationResult result = new JavaTranslator().translate(method);

    assertTrue(result.complete());
    assertTrue(result.source().contains("if (local1 == 0) {"));
    assertTrue(result.source().contains("foo();"));
    assertTrue(result.source().contains("} else {"));
    assertTrue(result.source().contains("bar();"));
    assertTrue(result.source().indexOf("bar();")
        < result.source().indexOf("foo();"));
  }

  @Test
  void translatesNestedIfStatements() {
    IrMethodModel method = new IrMethodModel(
        "test",
        "()V",
        List.of(
            new IrOperationModel("CONDITIONAL_BRANCH", List.of("a == 0", "LouterEnd"), 0),
            new IrOperationModel("CONDITIONAL_BRANCH", List.of("b == 0", "LinnerEnd"), 1),
            new IrOperationModel("METHOD_CALL", List.of("nested()"), 2),
            new IrOperationModel("LABEL", List.of("LinnerEnd"), 3),
            new IrOperationModel("LABEL", List.of("LouterEnd"), 4),
            new IrOperationModel("RETURN", List.of(), 5)));

    TranslationResult result = new JavaTranslator().translate(method);

    assertTrue(result.complete());
    assertTrue(result.complete());
    assertTrue(result.source().contains("if (a != 0) {"));
    assertTrue(result.source().contains("if (b != 0) {"));
    assertTrue(result.source().contains("nested();"));
  }

  @Test
  void translatesDoWhileLoop() {
    IrMethodModel method = new IrMethodModel(
        "test",
        "()V",
        List.of(
            new IrOperationModel("LABEL", List.of("Lloop"), 0),
            new IrOperationModel("METHOD_CALL", List.of("tick()"), 1),
            new IrOperationModel("CONDITIONAL_BRANCH", List.of("local1 < 10", "Lloop"), 2),
            new IrOperationModel("RETURN", List.of(), 3)));

    TranslationResult result = new JavaTranslator().translate(method);

    assertTrue(result.complete());
    assertTrue(result.source().contains("do {"));
    assertTrue(result.source().contains("tick();"));
    assertTrue(result.source().contains("} while (local1 < 10);"));
  }

  @Test
  void translatesFieldWriteAndReturn() {
    IrMethodModel method = new IrMethodModel(
        "test",
        "()V",
        List.of(
            new IrOperationModel(
                "FIELD_WRITE",
                List.of("example.Test.VALUE", "42"),
                0),
            new IrOperationModel("RETURN", List.of("example.Test.VALUE"), 1)));

    TranslationResult result = new JavaTranslator().translate(method);

    assertTrue(result.complete());
    assertTrue(result.source().contains("example.Test.VALUE = 42;"));
    assertTrue(result.source().contains("return example.Test.VALUE;"));
  }

  @Test
  void translatesMethodCallAndReturnExpression() {
    IrMethodModel method = new IrMethodModel(
        "test",
        "()V",
        List.of(
            new IrOperationModel("METHOD_CALL", List.of("System.out.println(\"hello\")"), 0),
            new IrOperationModel("RETURN", List.of("value"), 1)));

    TranslationResult result = new JavaTranslator().translate(method);

    assertTrue(result.complete());
    assertTrue(result.source().contains("System.out.println(\"hello\");"));
    assertTrue(result.source().contains("return value;"));
  }

  @Test
  void translatesMethodDescriptorSignature() {
    IrMethodModel method = new IrMethodModel(
        "compute",
        "(Ljava/lang/String;I)Z",
        List.of(new IrOperationModel("RETURN", List.of("true"), 0)));

    TranslationResult result = new JavaTranslator().translate(method);

    assertTrue(result.complete());
    assertTrue(result.source().startsWith("boolean compute(java.lang.String local0, int local1)"));
    assertTrue(result.source().contains("return true;"));
  }

  @Test
  void translatesArrayAndLongReturnTypes() {
    TranslationResult array = new JavaTranslator().translate(new IrMethodModel(
        "values",
        "()[I",
        List.of(new IrOperationModel("RETURN", List.of("local0"), 0))));
    TranslationResult wide = new JavaTranslator().translate(new IrMethodModel(
        "count",
        "()J",
        List.of(new IrOperationModel("RETURN", List.of("42L"), 0))));

    assertTrue(array.source().startsWith("int[] values()"));
    assertTrue(wide.source().startsWith("long count()"));
  }

  @Test
  void preservesNumericConstructorArguments() {
    IrMethodModel method = new IrMethodModel(
        "test",
        "()V",
        List.of(
            new IrOperationModel(
                "METHOD_CALL",
                List.of("new java.lang.IllegalArgumentException(42)"),
                0),
            new IrOperationModel(
                "THROW",
                List.of("new java.lang.IllegalArgumentException(42)"),
                1)));

    TranslationResult result = new JavaTranslator().translate(method);

    assertTrue(result.complete());
    assertTrue(result.source().contains(
        "throw new java.lang.IllegalArgumentException(42);"));
  }

  @Test
  void marksUnsupportedOperations() {
    IrMethodModel method = new IrMethodModel(
        "test",
        "()V",
        List.of(new IrOperationModel("UNSUPPORTED", List.of("x"), 0)));

    TranslationResult result = new JavaTranslator().translate(method);

    assertTrue(!result.complete());
    assertTrue(result.source().contains("GoldClientRuntime.unsupported(\"UNSUPPORTED\", \"x\");"), result.source());
  }

  @Test
  void declaresThenAssignsLocals() {
    IrMethodModel method = new IrMethodModel(
        "test",
        "()V",
        List.of(
            new IrOperationModel("LOCAL_WRITE", List.of("local1", "1"), 0),
            new IrOperationModel("LOCAL_WRITE", List.of("local1", "2"), 1),
            new IrOperationModel("RETURN", List.of(), 2)));

    TranslationResult result = new JavaTranslator().translate(method);

    assertTrue(result.source().contains("var local1 = 1;"));
    assertTrue(result.source().contains("local1 = 2;"));
  }

  @Test
  void translatesTableSwitchIntoStructuredCases() {
    IrMethodModel method = new IrMethodModel(
        "test",
        "(I)I",
        List.of(
            new IrOperationModel("TABLE_SWITCH",
                List.of("local0", "1", "2", "Ldefault", "Lone", "Ltwo"), 0),
            new IrOperationModel("LABEL", List.of("Lone"), 1),
            new IrOperationModel("RETURN", List.of("11"), 2),
            new IrOperationModel("LABEL", List.of("Ltwo"), 3),
            new IrOperationModel("RETURN", List.of("22"), 4),
            new IrOperationModel("LABEL", List.of("Ldefault"), 5),
            new IrOperationModel("RETURN", List.of("-1"), 6)));

    TranslationResult result = new JavaTranslator().translate(method);

    assertTrue(result.complete(), result.source());
    assertTrue(result.source().contains("switch (local0) {"), result.source());
    assertTrue(result.source().contains("case 1:"), result.source());
    assertTrue(result.source().contains("case 2:"), result.source());
    assertTrue(result.source().contains("default:"), result.source());
    assertTrue(result.source().contains("return 11;"), result.source());
    assertTrue(result.source().contains("return 22;"), result.source());
    assertTrue(!result.source().contains("TODO"), result.source());
  }

  @Test
  void translatesLookupSwitchAndSharedCaseTargets() {
    IrMethodModel method = new IrMethodModel(
        "test",
        "(I)I",
        List.of(
            new IrOperationModel("LOOKUP_SWITCH",
                List.of("local0", "Ldefault", "-4", "Lnegative", "7", "Lpositive",
                    "9", "Lpositive"), 0),
            new IrOperationModel("LABEL", List.of("Lnegative"), 1),
            new IrOperationModel("RETURN", List.of("-4"), 2),
            new IrOperationModel("LABEL", List.of("Lpositive"), 3),
            new IrOperationModel("RETURN", List.of("7"), 4),
            new IrOperationModel("LABEL", List.of("Ldefault"), 5),
            new IrOperationModel("RETURN", List.of("0"), 6)));

    TranslationResult result = new JavaTranslator().translate(method);

    assertTrue(result.complete(), result.source());
    assertTrue(result.source().contains("case -4:"), result.source());
    assertTrue(result.source().contains("case 7:"), result.source());
    assertTrue(result.source().contains("case 9:"), result.source());
    assertTrue(result.source().contains("default:"), result.source());
    assertEquals(1, occurrences(result.source(), "case 7:"));
    assertTrue(!result.source().contains("TODO"), result.source());
  }

  @Test
  void rendersArrayStoreStatements() {
    IrMethodModel method = new IrMethodModel("write", "([II)V", List.of(
        new IrOperationModel("ARRAY_WRITE", List.of("array", "index", "value"), 0),
        new IrOperationModel("RETURN", List.of(), 1)));
    TranslationResult result = new JavaTranslator().translate(method);
    assertTrue(result.complete(), result.source());
    assertTrue(result.source().contains("array[index] = value;"), result.source());
  }

  @Test
  void rendersTryCatchRegionsFromSemanticMarkers() {
    IrMethodModel method = new IrMethodModel("guarded", "()V", List.of(
        new IrOperationModel("TRY_BEGIN",
            List.of("Ltry", "LafterTry", "Lhandler", "java.lang.IllegalArgumentException"), 0),
        new IrOperationModel("LABEL", List.of("Ltry"), 0),
        new IrOperationModel("METHOD_CALL", List.of("mightFail()"), 1),
        new IrOperationModel("TRY_END",
            List.of("Ltry", "LafterTry", "Lhandler", "java.lang.IllegalArgumentException"), 2),
        new IrOperationModel("LABEL", List.of("LafterTry"), 2),
        new IrOperationModel("JUMP", List.of("Ljoin"), 3),
        new IrOperationModel("CATCH_BEGIN",
            List.of("Ltry", "LafterTry", "Lhandler", "java.lang.IllegalArgumentException"), 4),
        new IrOperationModel("LABEL", List.of("Lhandler"), 4),
        new IrOperationModel("LOCAL_WRITE", List.of("local0", "<exception>"), 5),
        new IrOperationModel("METHOD_CALL", List.of("recover(local0)"), 6),
        new IrOperationModel("JUMP", List.of("Ljoin"), 7),
        new IrOperationModel("LABEL", List.of("Ljoin"), 8),
        new IrOperationModel("RETURN", List.of(), 9)));
    TranslationResult result = new JavaTranslator().translate(method);
    assertTrue(result.source().contains("try {"), result.source());
    assertTrue(result.source().contains(
        "catch (java.lang.IllegalArgumentException local0)"), result.source());
    assertTrue(result.source().contains("mightFail();"), result.source());
    assertTrue(result.source().contains("recover(local0);"), result.source());
    assertTrue(!result.source().contains("TODO"), result.source());
  }

  @Test
  void rendersNestedTryCatchWithoutTruncatingOuterHandler() {
    List<String> outer = List.of(
        "Ltry", "LafterTry", "Lhandler", "java.lang.IllegalArgumentException");
    List<String> inner = List.of(
        "Linner", "LinnerEnd", "LinnerHandler", "java.lang.IllegalStateException");
    IrMethodModel method = new IrMethodModel("nestedGuard", "()V", List.of(
        new IrOperationModel("TRY_BEGIN", outer, 0),
        new IrOperationModel("LABEL", List.of("Ltry"), 0),
        new IrOperationModel("METHOD_CALL", List.of("mightFail()"), 1),
        new IrOperationModel("TRY_END", outer, 2),
        new IrOperationModel("LABEL", List.of("LafterTry"), 2),
        new IrOperationModel("JUMP", List.of("Ljoin"), 3),
        new IrOperationModel("CATCH_BEGIN", outer, 4),
        new IrOperationModel("LABEL", List.of("Lhandler"), 4),
        new IrOperationModel("LOCAL_WRITE", List.of("local0", "<exception>"), 5),
        new IrOperationModel("TRY_BEGIN", inner, 6),
        new IrOperationModel("LABEL", List.of("Linner"), 6),
        new IrOperationModel("METHOD_CALL", List.of("closeResource()"), 7),
        new IrOperationModel("TRY_END", inner, 8),
        new IrOperationModel("LABEL", List.of("LinnerEnd"), 8),
        new IrOperationModel("JUMP", List.of("LinnerJoin"), 9),
        new IrOperationModel("CATCH_BEGIN", inner, 10),
        new IrOperationModel("LABEL", List.of("LinnerHandler"), 10),
        new IrOperationModel("LOCAL_WRITE", List.of("local1", "<exception>"), 11),
        new IrOperationModel("METHOD_CALL", List.of("handleClose(local1)"), 12),
        new IrOperationModel("JUMP", List.of("LinnerJoin"), 13),
        new IrOperationModel("LABEL", List.of("LinnerJoin"), 14),
        new IrOperationModel("METHOD_CALL", List.of("recover(local0)"), 15),
        new IrOperationModel("RETURN", List.of(), 16),
        new IrOperationModel("LABEL", List.of("Ljoin"), 17),
        new IrOperationModel("RETURN", List.of(), 18)));

    TranslationResult result = new JavaTranslator().translate(method);

    assertTrue(result.complete(), result.source());
    assertTrue(result.source().contains("catch (java.lang.IllegalArgumentException local0)"),
        result.source());
    assertTrue(result.source().contains("catch (java.lang.IllegalStateException local1)"),
        result.source());
    assertTrue(result.source().contains("closeResource();"), result.source());
    assertTrue(result.source().contains("handleClose(local1);"), result.source());
    assertTrue(result.source().contains("recover(local0);"), result.source());
    assertTrue(!result.source().contains("TODO"), result.source());
  }

  private static int occurrences(String text, String needle) {
    int count = 0;
    int offset = 0;
    while ((offset = text.indexOf(needle, offset)) >= 0) {
      count++;
      offset += needle.length();
    }
    return count;
  }

  @Test
  void modelsJVMStackPermutationsWithoutEmittingPseudoInstructions() {
    IrMethodModel method = new IrMethodModel(
        "stackOps",
        "()V",
        List.of(
            new IrOperationModel("STACK_OPERATION", List.of("DUP_X1", "local0", "local1"), 0),
            new IrOperationModel("METHOD_CALL", List.of("consume(local0, local1)"), 1),
            new IrOperationModel("RETURN", List.of(), 2)));

    TranslationResult result = new JavaTranslator().translate(method);

    assertTrue(result.source().contains("consume(local0, local1);"), result.source());
    assertTrue(!result.source().contains("TODO: STACK_OPERATION"), result.source());
  }

  @Test
  void keepsMonitorOperationsExplicitAndMarksMethodIncomplete() {
    IrMethodModel method = new IrMethodModel(
        "synchronizedBlock",
        "()V",
        List.of(
            new IrOperationModel("MONITOR", List.of("MONITORENTER", "local0"), 0),
            new IrOperationModel("RETURN", List.of(), 1)));

    TranslationResult result = new JavaTranslator().translate(method);

    assertTrue(!result.complete(), result.source());
    assertTrue(result.source().contains(
        "GoldClientRuntime.unsupported(\"monitor\", \"MONITORENTER on local0\");"),
        result.source());
    assertTrue(!result.source().contains("TODO: MONITOR"), result.source());
  }

  @Test
  void unsupportedDynamicCallIsVisibleButDoesNotBreakSourceStructure() {
    IrMethodModel method = new IrMethodModel(
        "dynamic",
        "()V",
        List.of(
            new IrOperationModel("UNSUPPORTED_DYNAMIC", List.of("run", "()V"), 0),
            new IrOperationModel("RETURN", List.of(), 1)));

    TranslationResult result = new JavaTranslator().translate(method);

    assertTrue(!result.complete(), result.source());
    assertTrue(result.source().contains("unsupported invokedynamic"), result.source());
    assertTrue(result.source().contains("return;"), result.source());
  }

}
