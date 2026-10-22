package com.goldclient.translator;

import com.goldclient.model.IrMethodModel;
import com.goldclient.model.IrOperationModel;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        "if (me.jellysquid.mods.lithium.common.LithiumMod.CONFIG != null) {"));
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
  void keepsJavaScriptArrowExpressionsAsConstructorArguments() {
    IrMethodModel method = new IrMethodModel(
        "test",
        "()V",
        List.of(new IrOperationModel(
            "LOCAL_WRITE",
            List.of("local1", "new java.util.HashMap(() => GoldClientRuntime.createJava(\"java.util.IdentityHashMap\"))"),
            0),
            new IrOperationModel("RETURN", List.of(), 1)));

    TranslationResult result = new JavaTranslator().translate(method);

    assertTrue(result.source().contains(
        "new java.util.HashMap(() => GoldClientRuntime.createJava(\"java.util.IdentityHashMap\"))"),
        result.source());
    assertTrue(!result.source().contains(
        "new java.util.HashMap(\"() => GoldClientRuntime"), result.source());
  }

  @Test
  void keepsConstructorChainsIntactInsteadOfQuotingTheChainTail() {
    IrMethodModel method = new IrMethodModel("test", "()V", List.of(
        new IrOperationModel(
            "LOCAL_WRITE",
            List.of("local1", "new java.lang.StringBuilder().append(local0).append(46).toString()"),
            0),
        new IrOperationModel("RETURN", List.of(), 1)));

    TranslationResult result = new JavaTranslator().translate(method);

    assertTrue(result.complete(), result.source());
    assertTrue(result.source().contains(
        "new java.lang.StringBuilder().append(local0).append(46).toString()"),
        result.source());
    assertFalse(result.source().contains("new java.lang.StringBuilder(\")"), result.source());
  }

  @Test
  void emitsSynchronizedMonitorOperationsAsExplicitNoOpApproximations() {
    IrMethodModel method = new IrMethodModel("guarded", "()V", List.of(
        new IrOperationModel("MONITOR", List.of("MONITORENTER", "local0"), 0),
        new IrOperationModel("METHOD_CALL", List.of("work()"), 1),
        new IrOperationModel("MONITOR", List.of("MONITOREXIT", "local0"), 2),
        new IrOperationModel("RETURN", List.of(), 3)));

    TranslationResult result = new JavaTranslator().translate(method);

    assertTrue(result.complete(), result.source());
    assertTrue(result.source().contains("Approximation: ignored JVM MONITORENTER"), result.source());
    assertTrue(result.source().contains("Approximation: ignored JVM MONITOREXIT"), result.source());
    assertTrue(result.source().contains("work();"), result.source());
    assertTrue(!result.source().contains("unsupported(\"monitor\""), result.source());
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
  void resolvesBracketedBytecodeBranchLabels() {
    IrMethodModel method = new IrMethodModel(
        "test",
        "()V",
        List.of(
            new IrOperationModel("CONDITIONAL_BRANCH", List.of("local1 == 0", "[Lelse]"), 0),
            new IrOperationModel("METHOD_CALL", List.of("foo()"), 1),
            new IrOperationModel("JUMP", List.of("[Lend]"), 2),
            new IrOperationModel("LABEL", List.of("Lelse"), 3),
            new IrOperationModel("METHOD_CALL", List.of("bar()"), 4),
            new IrOperationModel("LABEL", List.of("Lend"), 5),
            new IrOperationModel("RETURN", List.of(), 6)));

    TranslationResult result = new JavaTranslator().translate(method);

    assertTrue(result.complete(), result.source());
    assertTrue(result.source().contains("if (local1 == 0) {"), result.source());
    assertTrue(result.source().contains("foo();"), result.source());
    assertTrue(result.source().contains("bar();"), result.source());
    assertTrue(!result.source().contains("unsupported(\"JUMP\""), result.source());
    assertTrue(!result.source().contains("unsupported(\"CONDITIONAL_BRANCH\""), result.source());
  }

  @Test
  void structuresConditionalBranchesThatExitNestedRanges() {
    IrMethodModel method = new IrMethodModel(
        "nestedExit",
        "()V",
        List.of(
            new IrOperationModel("CONDITIONAL_BRANCH", List.of("outer == 0", "Louter"), 0),
            new IrOperationModel("CONDITIONAL_BRANCH", List.of("inner == 0", "Lafter"), 1),
            new IrOperationModel("METHOD_CALL", List.of("nested()"), 2),
            new IrOperationModel("LABEL", List.of("Louter"), 3),
            new IrOperationModel("LABEL", List.of("Lafter"), 4),
            new IrOperationModel("RETURN", List.of(), 5)));

    TranslationResult result = new JavaTranslator().translate(method);

    assertTrue(result.complete(), result.source());
    assertTrue(result.source().contains("if (inner == 0) {"), result.source());
    assertTrue(result.source().contains("nested();"), result.source());
    assertTrue(result.source().contains("return;"), result.source());
    assertTrue(!result.source().contains("unsupported(\"CONDITIONAL_BRANCH\""), result.source());
  }

  @Test
  void structuresNestedBranchThatExitsToNonTerminalJoin() {
    IrMethodModel method = new IrMethodModel(
        "nestedExitToJoin",
        "()V",
        List.of(
            new IrOperationModel("CONDITIONAL_BRANCH", List.of("outer == 0", "Louter"), 0),
            new IrOperationModel("CONDITIONAL_BRANCH", List.of("inner == 0", "Lafter"), 1),
            new IrOperationModel("METHOD_CALL", List.of("nested()"), 2),
            new IrOperationModel("JUMP", List.of("Louter"), 3),
            new IrOperationModel("LABEL", List.of("Louter"), 4),
            new IrOperationModel("LABEL", List.of("Lafter"), 5),
            new IrOperationModel("METHOD_CALL", List.of("joined()"), 6),
            new IrOperationModel("RETURN", List.of(), 7)));

    TranslationResult result = new JavaTranslator().translate(method);

    assertTrue(result.complete(), result.source());
    assertTrue(result.source().contains("if (inner != 0) {"), result.source());
    assertTrue(result.source().contains("nested();"), result.source());
    assertTrue(result.source().contains("joined();"), result.source());
    assertTrue(!result.source().contains("unsupported("), result.source());
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
    assertTrue(result.source().contains("if (a == 0) {"));
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
  void breaksOutOfDoWhileWhenTheBodyJumpsPastItsBackEdge() {
    IrMethodModel method = new IrMethodModel("find", "(I)I", List.of(
        new IrOperationModel("CONDITIONAL_BRANCH", List.of("local4 == 0", "Lexit"), 0),
        new IrOperationModel("LABEL", List.of("Lheader"), 1),
        new IrOperationModel("LOCAL_WRITE", List.of("local3", "(local3 + 1) & mask"), 2),
        new IrOperationModel("LOCAL_WRITE", List.of("local4", "key[local3]"), 3),
        new IrOperationModel("CONDITIONAL_BRANCH", List.of("local4 == 0", "Lexit"), 4),
        new IrOperationModel("CONDITIONAL_BRANCH", List.of("local4 != local1", "Lheader"), 5),
        new IrOperationModel("RETURN", List.of("local3"), 6),
        new IrOperationModel("LABEL", List.of("Lexit"), 7),
        new IrOperationModel("RETURN", List.of("local3"), 8)));

    TranslationResult result = new JavaTranslator().translate(method);

    assertTrue(result.complete(), result.source());
    assertTrue(result.source().contains("do {"), result.source());
    assertTrue(result.source().contains("if (local4 == 0) break;"), result.source());
    assertTrue(result.source().contains("} while (local4 != local1);"), result.source());
  }

  @Test
  void translatesForwardExitWithBackwardGotoAsWhileLoop() {
    IrMethodModel method = new IrMethodModel("sum", "()I", List.of(
        new IrOperationModel("LABEL", List.of("L0"), 0),
        // The branch condition is true when the JVM jumps to the loop exit.
        new IrOperationModel("CONDITIONAL_BRANCH", List.of("iterator.hasNext() == 0", "Lexit"), 1),
        new IrOperationModel("LOCAL_WRITE", List.of("local1", "local1 + iterator.next()"), 2),
        new IrOperationModel("JUMP", List.of("L0"), 3),
        new IrOperationModel("LABEL", List.of("Lexit"), 4),
        new IrOperationModel("RETURN", List.of("local1"), 5)));

    TranslationResult result = new JavaTranslator().translate(method);

    assertTrue(result.complete(), result.source());
    assertTrue(result.source().contains("while (true)"), result.source());
    assertTrue(result.source().contains("if (iterator.hasNext() == 0) break;"), result.source());
    assertTrue(!result.source().contains("unsupported(\"JUMP\""), result.source());
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
    IrMethodModel method = new IrMethodModel("write", "([III)V", List.of(
        new IrOperationModel("ARRAY_WRITE", List.of("array", "index", "value"), 0),
        new IrOperationModel("RETURN", List.of(), 1)));
    TranslationResult result = new JavaTranslator().translate(method);
    assertTrue(result.complete(), result.source());
    assertTrue(result.source().contains("GoldClientRuntime.arraySet(array, index, value);"), result.source());
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
  void rendersCatchAllFinallyForTryWithResourcesWithoutUnreachableCatch() {
    List<String> typed = List.of(
        "Ltry", "Lend", "Ltyped", "java.lang.Throwable", "false");
    List<String> catchAll = List.of(
        "Ltry", "Lend", "Lfinally", "java.lang.Throwable", "true");
    List<String> protectedHandler = List.of(
        "Ltyped", "LcleanupEnd", "Lfinally", "java.lang.Throwable", "true");
    IrMethodModel method = new IrMethodModel("guardedResource", "()V", List.of(
        new IrOperationModel("TRY_BEGIN", typed, 0),
        new IrOperationModel("TRY_BEGIN", catchAll, 0),
        new IrOperationModel("LABEL", List.of("Ltry"), 0),
        new IrOperationModel("METHOD_CALL", List.of("writeResource()"), 1),
        new IrOperationModel("TRY_END", typed, 2),
        new IrOperationModel("TRY_END", catchAll, 2),
        new IrOperationModel("LABEL", List.of("Lend"), 2),
        new IrOperationModel("JUMP", List.of("Ljoin"), 3),
        new IrOperationModel("CATCH_BEGIN", typed, 4),
        new IrOperationModel("TRY_BEGIN", protectedHandler, 4),
        new IrOperationModel("LABEL", List.of("Ltyped"), 4),
        new IrOperationModel("LOCAL_WRITE", List.of("local1", "<exception>"), 5),
        new IrOperationModel("LOCAL_READ", List.of("local1"), 5),
        new IrOperationModel("LOCAL_WRITE", List.of("local2", "local1"), 6),
        new IrOperationModel("LOCAL_READ", List.of("local1"), 6),
        new IrOperationModel("THROW", List.of("local1"), 7),
        new IrOperationModel("CATCH_BEGIN", catchAll, 8),
        new IrOperationModel("CATCH_BEGIN", protectedHandler, 8),
        new IrOperationModel("LABEL", List.of("Lfinally"), 8),
        new IrOperationModel("LOCAL_WRITE", List.of("local3", "<exception>"), 9),
        new IrOperationModel("TRY_END", protectedHandler, 9),
        new IrOperationModel("LABEL", List.of("LcleanupEnd"), 9),
        new IrOperationModel("METHOD_CALL", List.of("closeResource()"), 10),
        new IrOperationModel("LOCAL_READ", List.of("local3"), 10),
        new IrOperationModel("THROW", List.of("local3"), 11),
        new IrOperationModel("LABEL", List.of("Ljoin"), 12),
        new IrOperationModel("RETURN", List.of(), 13)));

    TranslationResult result = new JavaTranslator().translate(method);

    assertTrue(result.complete(), result.source());
    assertTrue(result.source().contains("try {"), result.source());
    assertTrue(result.source().contains(
        "catch (java.lang.Throwable local1)"), result.source());
    assertTrue(result.source().contains("} finally {"), result.source());
    assertFalse(result.source().contains(
        "catch (java.lang.Throwable local3)"), result.source());
    assertEquals(1, occurrences(result.source(), "catch ("), result.source());
    assertTrue(result.source().contains("closeResource();"), result.source());
  }

  @Test
  void keepsLoopExitJumpAfterTryThatHasARetryingCatch() {
    IrMethodModel method = new IrMethodModel(
        "retryUntilMoved",
        "()V",
        List.of(
            new IrOperationModel("CONDITIONAL_BRANCH", List.of("flag == 0", "L6"), 0),
            new IrOperationModel(
                "TRY_BEGIN", List.of("L0", "L1", "L2", "java.io.IOException", "false"), 1),
            new IrOperationModel("LABEL", List.of("L0"), 1),
            new IrOperationModel("LOCAL_WRITE", List.of("local4", "move()"), 2),
            new IrOperationModel(
                "TRY_END", List.of("L0", "L1", "L2", "java.io.IOException", "false"), 3),
            new IrOperationModel("LABEL", List.of("L1"), 3),
            new IrOperationModel("JUMP", List.of("L7"), 4),
            new IrOperationModel(
                "CATCH_BEGIN", List.of("L0", "L1", "L2", "java.io.IOException", "false"), 5),
            new IrOperationModel("LABEL", List.of("L2"), 5),
            new IrOperationModel("LOCAL_WRITE", List.of("local5", "<exception>"), 6),
            new IrOperationModel("METHOD_CALL", List.of("sleep()"), 7),
            new IrOperationModel("LABEL", List.of("L8"), 8),
            new IrOperationModel("JUMP", List.of("L0"), 9),
            new IrOperationModel("LABEL", List.of("L6"), 10),
            new IrOperationModel("LABEL", List.of("L7"), 10),
            new IrOperationModel("RETURN", List.of(), 11)));

    TranslationResult result = new JavaTranslator().translate(method);

    assertTrue(result.source().contains("while (true)"), result.source());
    assertTrue(result.source().contains("break;"), result.source());
    assertTrue(result.source().contains("sleep();"), result.source());
  }

  @Test
  void leavesTryAtRangeBoundaryWhenCatchOnlyCodePrecedesNormalJoin() {
    IrMethodModel method = new IrMethodModel(
        "conditionalTry",
        "()V",
        List.of(
            new IrOperationModel("CONDITIONAL_BRANCH", List.of("cond == 0", "Ltry"), 0),
            new IrOperationModel("JUMP", List.of("Lnormal"), 1),
            new IrOperationModel(
                "TRY_BEGIN", List.of("Ltry", "Lend", "Lhandler", "java.lang.RuntimeException", "false"), 2),
            new IrOperationModel("LABEL", List.of("Ltry"), 2),
            new IrOperationModel("METHOD_CALL", List.of("work()"), 3),
            new IrOperationModel(
                "TRY_END", List.of("Ltry", "Lend", "Lhandler", "java.lang.RuntimeException", "false"), 4),
            new IrOperationModel("LABEL", List.of("Lend"), 4),
            new IrOperationModel("JUMP", List.of("Lnormal"), 5),
            new IrOperationModel(
                "CATCH_BEGIN", List.of("Ltry", "Lend", "Lhandler", "java.lang.RuntimeException", "false"), 6),
            new IrOperationModel("LABEL", List.of("Lhandler"), 6),
            new IrOperationModel("LOCAL_WRITE", List.of("local1", "<exception>"), 7),
            new IrOperationModel("METHOD_CALL", List.of("logFailure()"), 8),
            new IrOperationModel("THROW", List.of("local1"), 9),
            new IrOperationModel("LABEL", List.of("LcatchOnly"), 10),
            new IrOperationModel("METHOD_CALL", List.of("catchOnly()"), 10),
            new IrOperationModel("RETURN", List.of(), 11),
            new IrOperationModel("LABEL", List.of("Lnormal"), 12),
            new IrOperationModel("METHOD_CALL", List.of("after()"), 12),
            new IrOperationModel("RETURN", List.of(), 13)));

    TranslationResult result = new JavaTranslator().translate(method);

    assertTrue(result.complete(), result.source());
    assertTrue(result.source().contains("break _try"), result.source());
    assertTrue(result.source().contains("catchOnly();"), result.source());
    assertTrue(result.source().contains("after();"), result.source());
    assertFalse(result.source().contains("unsupported(\"JUMP\""), result.source());
  }

  @Test
  void matchesExceptionMarkersWithBracketedLabels() {
    IrMethodModel method = new IrMethodModel("bracketedGuard", "()V", List.of(
        new IrOperationModel("TRY_BEGIN",
            List.of("[Ltry]", "[LafterTry]", "[Lhandler]", "java.lang.IllegalArgumentException"), 0),
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

    assertTrue(result.complete(), result.source());
    assertTrue(result.source().contains("try {"), result.source());
    assertTrue(result.source().contains(
        "catch (java.lang.IllegalArgumentException local0)"), result.source());
    assertTrue(!result.source().contains("try-catch-region"), result.source());
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

  @Test
  void ignoresJumpsThatOnlyCrossLabels() {
    IrMethodModel method = new IrMethodModel(
        "fallthroughJump",
        "()V",
        List.of(
            new IrOperationModel("JUMP", List.of("[Lnext]"), 0),
            new IrOperationModel("LABEL", List.of("Lnext"), 1),
            new IrOperationModel("RETURN", List.of(), 2)));

    TranslationResult result = new JavaTranslator().translate(method);

    assertTrue(result.complete(), result.source());
    assertTrue(result.source().contains("return;"), result.source());
    assertTrue(!result.source().contains("unsupported(\"JUMP\""), result.source());
  }

  @Test
  void skipsIsolatedDeadBlocksAfterForwardJump() {
    IrMethodModel method = new IrMethodModel(
        "deadBlock",
        "()V",
        List.of(
            new IrOperationModel("JUMP", List.of("Lend"), 0),
            new IrOperationModel("LABEL", List.of("Ldead"), 1),
            new IrOperationModel("METHOD_CALL", List.of("unreachable()"), 2),
            new IrOperationModel("LABEL", List.of("Lend"), 3),
            new IrOperationModel("RETURN", List.of(), 4)));

    TranslationResult result = new JavaTranslator().translate(method);

    assertTrue(result.complete(), result.source());
    assertTrue(!result.source().contains("unreachable()"), result.source());
    assertTrue(!result.source().contains("unsupported(\"JUMP\""), result.source());
  }

  @Test
  void preservesForwardJumpWhenSkippedBlockHasAnExternalEntry() {
    IrMethodModel method = new IrMethodModel(
        "liveBlock",
        "()V",
        List.of(
            new IrOperationModel("JUMP", List.of("Lend"), 0),
            new IrOperationModel("LABEL", List.of("Ldead"), 1),
            new IrOperationModel("METHOD_CALL", List.of("reachableFromBackEdge()"), 2),
            new IrOperationModel("LABEL", List.of("Lend"), 3),
            new IrOperationModel("JUMP", List.of("Ldead"), 4)));

    TranslationResult result = new JavaTranslator().translate(method);

    assertTrue(!result.complete(), result.source());
    assertTrue(result.source().contains("reachableFromBackEdge();"), result.source());
    assertTrue(result.source().contains("unsupported(\"JUMP\""), result.source());
  }

  @Test
  void structuresConditionalExitWithUnconditionalBackwardLoopEdge() {
    IrMethodModel method = new IrMethodModel(
        "backwardLoop",
        "()V",
        List.of(
            new IrOperationModel("LABEL", List.of("L0"), 0),
            new IrOperationModel("METHOD_CALL", List.of("step()"), 1),
            new IrOperationModel("CONDITIONAL_BRANCH", List.of("keepGoing", "Lend"), 2),
            new IrOperationModel("JUMP", List.of("L0"), 3),
            new IrOperationModel("LABEL", List.of("Lend"), 4),
            new IrOperationModel("RETURN", List.of(), 5)));

    TranslationResult result = new JavaTranslator().translate(method);

    assertTrue(result.complete(), result.source());
    assertTrue(result.source().contains("while (true) {"), result.source());
    assertTrue(result.source().contains("if (keepGoing) break;"), result.source());
    assertTrue(!result.source().contains("unsupported(\"JUMP\""), result.source());
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
  void keepsMonitorOperationsVisibleAsNoOpApproximations() {
    IrMethodModel method = new IrMethodModel(
        "synchronizedBlock",
        "()V",
        List.of(
            new IrOperationModel("MONITOR", List.of("MONITORENTER", "local0"), 0),
            new IrOperationModel("RETURN", List.of(), 1)));

    TranslationResult result = new JavaTranslator().translate(method);

    assertTrue(result.complete(), result.source());
    assertTrue(result.source().contains(
        "Approximation: ignored JVM MONITORENTER on local0."),
        result.source());
    assertTrue(!result.source().contains("GoldClientRuntime.unsupported(\"monitor\""),
        result.source());
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


  @Test
  void preservesLambdaArgumentsAsExecutableConstructorExpressions() {
    IrMethodModel method = new IrMethodModel(
        "register",
        "()V",
        List.of(new IrOperationModel(
            "METHOD_CALL",
            List.of("new java.util.HashMap(new java.util.HashMap(), () -> new java.util.IdentityHashMap())"),
            0)));

    TranslationResult result = new JavaTranslator().translate(method);

    assertTrue(result.complete(), result.source());
    assertTrue(result.source().contains(
        "new java.util.HashMap(new java.util.HashMap(), () -> new java.util.IdentityHashMap())"),
        result.source());
    assertTrue(!result.source().contains("\\"), result.source());
  }

  @Test
  void prefersOuterIfJoinOverEarlierNestedGoto() {
    IrMethodModel method = new IrMethodModel("test", "()V", List.of(
        new IrOperationModel("CONDITIONAL_BRANCH", List.of("outerCondition", "Lelse"), 0),
        new IrOperationModel("CONDITIONAL_BRANCH", List.of("innerCondition", "LinnerElse"), 1),
        new IrOperationModel("METHOD_CALL", List.of("early()"), 2),
        new IrOperationModel("JUMP", List.of("Ljoin"), 3),
        new IrOperationModel("LABEL", List.of("LinnerElse"), 4),
        new IrOperationModel("METHOD_CALL", List.of("later()"), 5),
        new IrOperationModel("JUMP", List.of("Ljoin"), 6),
        new IrOperationModel("LABEL", List.of("Lelse"), 7),
        new IrOperationModel("METHOD_CALL", List.of("outerElse()"), 8),
        new IrOperationModel("LABEL", List.of("Ljoin"), 9),
        new IrOperationModel("RETURN", List.of(), 10)));

    TranslationResult result = new JavaTranslator().translate(method);

    assertTrue(result.complete(), result.source());
    assertTrue(result.source().contains("early();"), result.source());
    assertTrue(result.source().contains("later();"), result.source());
    assertTrue(result.source().contains("outerElse();"), result.source());
    assertTrue(result.source().contains("} else {"), result.source());
  }

  @Test
  void lowersMultiCatchWithOneRuntimeDispatchAndSharedHandler() {
    List<String> io = List.of("Ltry", "LafterTry", "Lhandler", "java.io.IOException");
    List<String> sql = List.of("Ltry", "LafterTry", "Lhandler", "java.sql.SQLException");
    IrMethodModel method = new IrMethodModel("test", "()V", List.of(
        new IrOperationModel("TRY_BEGIN", io, 0),
        new IrOperationModel("TRY_BEGIN", sql, 1),
        new IrOperationModel("LABEL", List.of("Ltry"), 2),
        new IrOperationModel("METHOD_CALL", List.of("work()"), 3),
        new IrOperationModel("TRY_END", io, 4),
        new IrOperationModel("TRY_END", sql, 5),
        new IrOperationModel("LABEL", List.of("LafterTry"), 6),
        new IrOperationModel("CATCH_BEGIN", io, 7),
        new IrOperationModel("CATCH_BEGIN", sql, 8),
        new IrOperationModel("LABEL", List.of("Lhandler"), 9),
        new IrOperationModel("LOCAL_WRITE", List.of("local1", "<exception>"), 10),
        new IrOperationModel("METHOD_CALL", List.of("recover(local1)"), 11),
        new IrOperationModel("JUMP", List.of("Ljoin"), 12),
        new IrOperationModel("LABEL", List.of("Ljoin"), 13),
        new IrOperationModel("RETURN", List.of(), 14)));

    TranslationResult result = new JavaTranslator().translate(method);

    assertTrue(result.complete(), result.source());
    assertTrue(result.source().contains("catch (caughtException)"), result.source());
    assertTrue(result.source().contains(
        "GoldClientRuntime.instanceOf(caughtException, \"java.io.IOException\")"), result.source());
    assertTrue(result.source().contains(
        "GoldClientRuntime.instanceOf(caughtException, \"java.sql.SQLException\")"), result.source());
    assertTrue(result.source().contains("var local1 = caughtException;"), result.source());
    assertTrue(result.source().contains("recover(local1);"), result.source());
    assertTrue(!result.source().contains("<exception>"), result.source());
  }

  @Test
  void structuresForwardExitLoopWithNestedBackwardLoop() {
    IrMethodModel method = new IrMethodModel("nestedLoops", "()V", List.of(
        new IrOperationModel("LABEL", List.of("Louter"), 0),
        new IrOperationModel("CALL_VALUE", List.of("outerHasNext()"), 1),
        new IrOperationModel("CONDITIONAL_BRANCH", List.of("outerHasNext() == 0", "LouterEnd"), 2),
        new IrOperationModel("METHOD_CALL", List.of("outerWork()"), 3),
        new IrOperationModel("LABEL", List.of("Linner"), 4),
        new IrOperationModel("CALL_VALUE", List.of("innerHasNext()"), 5),
        new IrOperationModel("CONDITIONAL_BRANCH", List.of("innerHasNext() == 0", "LinnerEnd"), 6),
        new IrOperationModel("METHOD_CALL", List.of("innerWork()"), 7),
        new IrOperationModel("JUMP", List.of("Linner"), 8),
        new IrOperationModel("LABEL", List.of("LinnerEnd"), 9),
        new IrOperationModel("JUMP", List.of("Louter"), 10),
        new IrOperationModel("LABEL", List.of("LouterEnd"), 11),
        new IrOperationModel("RETURN", List.of(), 12)));

    TranslationResult result = new JavaTranslator().translate(method);

    assertTrue(result.complete(), result.source());
    assertTrue(result.source().contains("while (true)"), result.source());
    assertTrue(result.source().contains("if (outerHasNext() == 0) break;"), result.source());
    assertTrue(result.source().contains("if (innerHasNext() == 0) break;"), result.source());
    assertTrue(result.source().contains("outerWork();"), result.source());
    assertTrue(result.source().contains("innerWork();"), result.source());
    assertTrue(!result.source().contains("unsupported(\"JUMP\""), result.source());
  }

  @Test
  void lowersShortCircuitBooleanReturnMergeWithoutUnknownStackValue() {
    IrMethodModel method = new IrMethodModel("isEnabled", "()Z", List.of(
        new IrOperationModel("CONDITIONAL_BRANCH", List.of("primaryEnabled != 0", "Ltrue"), 0),
        new IrOperationModel("CALL_VALUE", List.of("toggleKey.isPressed()"), 1),
        new IrOperationModel("CONDITIONAL_BRANCH", List.of("toggleKey.isPressed() != 0", "Lfalse"), 2),
        new IrOperationModel("LABEL", List.of("Ltrue"), 3),
        new IrOperationModel("CONSTANT", List.of("1"), 4),
        new IrOperationModel("JUMP", List.of("Ljoin"), 5),
        new IrOperationModel("LABEL", List.of("Lfalse"), 6),
        new IrOperationModel("CONSTANT", List.of("0"), 7),
        new IrOperationModel("LABEL", List.of("Ljoin"), 8),
        new IrOperationModel("RETURN", List.of("<unknown>"), 9)));

    TranslationResult result = new JavaTranslator().translate(method);

    assertTrue(result.complete(), result.source());
    assertTrue(result.source().contains("if (primaryEnabled != 0)"), result.source());
    assertTrue(result.source().contains("return 1;"), result.source());
    assertTrue(result.source().contains("if (toggleKey.isPressed() != 0)"), result.source());
    assertTrue(result.source().contains("return 0;"), result.source());
    assertTrue(!result.source().contains("<unknown>"), result.source());
    assertTrue(!result.source().contains("unsupported(\"JUMP\""), result.source());
  }

  @Test
  void structuresTryRegionThatStartsAtConditionalTarget() {
    List<String> region = List.of("Ltry", "LafterTry", "Lhandler", "java.lang.RuntimeException");
    IrMethodModel method = new IrMethodModel("render", "()V", List.of(
        new IrOperationModel("CONDITIONAL_BRANCH", List.of("skipPreTry != 0", "Ltry"), 0),
        new IrOperationModel("METHOD_CALL", List.of("beforeTry()"), 1),
        new IrOperationModel("TRY_BEGIN", region, 2),
        new IrOperationModel("LABEL", List.of("Ltry"), 3),
        new IrOperationModel("METHOD_CALL", List.of("protectedWork()"), 4),
        new IrOperationModel("TRY_END", region, 5),
        new IrOperationModel("LABEL", List.of("LafterTry"), 6),
        new IrOperationModel("JUMP", List.of("Ljoin"), 7),
        new IrOperationModel("CATCH_BEGIN", region, 8),
        new IrOperationModel("LABEL", List.of("Lhandler"), 9),
        new IrOperationModel("LOCAL_WRITE", List.of("local1", "<exception>"), 10),
        new IrOperationModel("METHOD_CALL", List.of("recover(local1)"), 11),
        new IrOperationModel("JUMP", List.of("Ljoin"), 12),
        new IrOperationModel("LABEL", List.of("Ljoin"), 13),
        new IrOperationModel("RETURN", List.of(), 14)));

    TranslationResult result = new JavaTranslator().translate(method);

    assertTrue(result.complete(), result.source());
    assertTrue(result.source().contains("beforeTry();"), result.source());
    assertTrue(result.source().contains("try {"), result.source());
    assertTrue(result.source().contains("catch (java.lang.RuntimeException local1)"), result.source());
    assertTrue(result.source().contains("protectedWork();"), result.source());
    assertTrue(result.source().contains("recover(local1);"), result.source());
    assertTrue(!result.source().contains("<exception>"), result.source());
    assertTrue(!result.source().contains("unsupported(\"JUMP\""), result.source());
  }

  @Test
  void lowersConditionalStackMergeIntoLocalTernary() {
    IrMethodModel method = new IrMethodModel("chooseMode", "()V", List.of(
        new IrOperationModel("CONDITIONAL_BRANCH", List.of("isOutput == 0", "Loutput"), 0),
        new IrOperationModel("FIELD_READ", List.of("Focus.Mode.INPUT", "Focus.Mode.INPUT", "Focus.Mode.INPUT"), 1),
        new IrOperationModel("JUMP", List.of("Ljoin"), 2),
        new IrOperationModel("LABEL", List.of("Loutput"), 3),
        new IrOperationModel("FIELD_READ", List.of("Focus.Mode.OUTPUT", "Focus.Mode.OUTPUT", "Focus.Mode.OUTPUT"), 4),
        new IrOperationModel("LABEL", List.of("Ljoin"), 5),
        new IrOperationModel("LOCAL_WRITE", List.of("local4", "<unknown>"), 6),
        new IrOperationModel("RETURN", List.of("local4"), 7)));

    TranslationResult result = new JavaTranslator().translate(method);

    assertTrue(result.complete(), result.source());
    assertTrue(result.source().contains(
        "var local4 = (isOutput == 0 ? Focus.Mode.OUTPUT : Focus.Mode.INPUT);"), result.source());
    assertTrue(!result.source().contains("<unknown>"), result.source());
    assertTrue(!result.source().contains("unsupported(\"JUMP\""), result.source());
  }

  @Test
  void lowersConditionalStackMergeInsideConstructorArgument() {
    IrMethodModel method = new IrMethodModel("createTimer", "()V", List.of(
        new IrOperationModel("CONDITIONAL_BRANCH", List.of("reverse != 0", "Lzero"), 0),
        new IrOperationModel("CONSTANT", List.of("1"), 1),
        new IrOperationModel("JUMP", List.of("Ljoin"), 2),
        new IrOperationModel("LABEL", List.of("Lzero"), 3),
        new IrOperationModel("CONSTANT", List.of("0"), 4),
        new IrOperationModel("LABEL", List.of("Ljoin"), 5),
        new IrOperationModel("CALL_VALUE", List.of("new Timer(local2, local6, <unknown>)"), 6),
        new IrOperationModel("FIELD_WRITE", List.of("local0", "Example.timer", "new Timer(local2, local6, <unknown>)"), 7),
        new IrOperationModel("RETURN", List.of(), 8)));

    TranslationResult result = new JavaTranslator().translate(method);

    assertTrue(result.complete(), result.source());
    assertTrue(result.source().contains(
        "local0.timer = new Timer(local2, local6, (reverse != 0 ? 0 : 1));"), result.source());
    assertTrue(!result.source().contains("<unknown>"), result.source());
    assertTrue(!result.source().contains("unsupported(\"JUMP\""), result.source());
  }

  @Test
  void lowersShortCircuitLocalValueMergeWithoutUnknown() {
    IrMethodModel method = new IrMethodModel("chooseFlag", "()V", List.of(
        new IrOperationModel("CONDITIONAL_BRANCH", List.of("primary != 0", "Ltrue"), 0),
        new IrOperationModel("CALL_VALUE", List.of("secondary()"), 1),
        new IrOperationModel("CONDITIONAL_BRANCH", List.of("secondary() != 0", "Lfalse"), 2),
        new IrOperationModel("LABEL", List.of("Ltrue"), 3),
        new IrOperationModel("CONSTANT", List.of("1"), 4),
        new IrOperationModel("JUMP", List.of("Ljoin"), 5),
        new IrOperationModel("LABEL", List.of("Lfalse"), 6),
        new IrOperationModel("CONSTANT", List.of("0"), 7),
        new IrOperationModel("LABEL", List.of("Ljoin"), 8),
        new IrOperationModel("LOCAL_WRITE", List.of("local3", "<unknown>"), 9),
        new IrOperationModel("RETURN", List.of("local3"), 10)));

    TranslationResult result = new JavaTranslator().translate(method);

    assertTrue(result.complete(), result.source());
    assertTrue(result.source().contains("if (primary != 0)"), result.source());
    assertTrue(result.source().contains("local3 = 1;"), result.source());
    assertTrue(result.source().contains("local3 = 0;"), result.source());
    assertTrue(!result.source().contains("<unknown>"), result.source());
    assertTrue(!result.source().contains("unsupported(\"JUMP\""), result.source());
  }

  @Test
  void structuresShortCircuitChainWithTwoExpressionBlocksIntoReturnGuards() {
    IrMethodModel method = new IrMethodModel("isOwner", "(Lexample/Player;)Z", List.of(
        new IrOperationModel("FIELD_READ", List.of("local0", "example.Tile.owner", "local0.owner"), 0),
        new IrOperationModel("CONDITIONAL_BRANCH", List.of("local0.owner == null", "Ltrue"), 1),
        new IrOperationModel("CALL_VALUE", List.of("local1.getId().equals(local0.owner)"), 2),
        new IrOperationModel("CONDITIONAL_BRANCH",
            List.of("local1.getId().equals(local0.owner) != 0", "Ltrue"), 3),
        new IrOperationModel("FIELD_READ",
            List.of("local1.creative", "example.Player.creative", "local1.creative"), 4),
        new IrOperationModel("CONDITIONAL_BRANCH", List.of("local1.creative == 0", "Lfalse"), 5),
        new IrOperationModel("LABEL", List.of("Ltrue"), 6),
        new IrOperationModel("CONSTANT", List.of("1"), 7),
        new IrOperationModel("JUMP", List.of("Ljoin"), 8),
        new IrOperationModel("LABEL", List.of("Lfalse"), 9),
        new IrOperationModel("CONSTANT", List.of("0"), 10),
        new IrOperationModel("LABEL", List.of("Ljoin"), 11),
        new IrOperationModel("RETURN", List.of("<unknown>"), 12)));

    TranslationResult result = new JavaTranslator().translate(method);

    assertTrue(result.complete(), result.source());
    assertTrue(result.source().contains("if (local0.owner == null) return 1;"), result.source());
    assertTrue(result.source().contains(
        "if (local1.getId().equals(local0.owner) != 0) return 1;"), result.source());
    assertTrue(result.source().contains("if (local1.creative == 0) return 0;"), result.source());
    assertTrue(result.source().contains("return 1;"), result.source());
    assertTrue(!result.source().contains("<unknown>"), result.source());
    assertTrue(!result.source().contains("unsupported(\"JUMP\""), result.source());
  }

  @Test
  void breaksOutOfATryWhenTheBodyJumpsToItsProtectedEnd() {
    IrMethodModel method = new IrMethodModel("pick", "(Z)I", List.of(
        new IrOperationModel("TRY_BEGIN",
            List.of("Ltry", "Lend", "Lcatch", "java.lang.Exception", "false"), 0),
        new IrOperationModel("LABEL", List.of("Ltry"), 1),
        new IrOperationModel("CONDITIONAL_BRANCH", List.of("flag == 0", "Lskip"), 2),
        new IrOperationModel("CONSTANT", List.of("1"), 3),
        new IrOperationModel("LOCAL_WRITE", List.of("local1", "1"), 4),
        new IrOperationModel("JUMP", List.of("Lend"), 5),
        new IrOperationModel("LABEL", List.of("Lskip"), 6),
        new IrOperationModel("CONSTANT", List.of("2"), 7),
        new IrOperationModel("LOCAL_WRITE", List.of("local1", "2"), 8),
        new IrOperationModel("TRY_END",
            List.of("Ltry", "Lend", "Lcatch", "java.lang.Exception", "false"), 9),
        new IrOperationModel("LABEL", List.of("Lend"), 10),
        new IrOperationModel("LOCAL_READ", List.of("local1"), 11),
        new IrOperationModel("RETURN", List.of("local1"), 12),
        new IrOperationModel("CATCH_BEGIN",
            List.of("Ltry", "Lend", "Lcatch", "java.lang.Exception", "false"), 13),
        new IrOperationModel("LABEL", List.of("Lcatch"), 14),
        new IrOperationModel("LOCAL_WRITE", List.of("local2", "<exception>"), 15),
        new IrOperationModel("CONSTANT", List.of("-1"), 16),
        new IrOperationModel("RETURN", List.of("-1"), 17)));

    TranslationResult result = new JavaTranslator().translate(method);

    assertTrue(result.complete(), result.source());
    assertTrue(result.source().contains("_try0: {"), result.source());
    assertFalse(result.source().contains("unsupported(\"JUMP\""), result.source());
  }

  @Test
  void keepsDoWhileHeaderWhenExplicitContinuesAlsoTargetIt() {
    IrMethodModel method = new IrMethodModel("scan", "()I", List.of(
        new IrOperationModel("LABEL", List.of("L0"), 0),
        new IrOperationModel("CONDITIONAL_BRANCH", List.of("terminator == 0", "Lbreak"), 1),
        new IrOperationModel("JUMP", List.of("L0"), 2),
        new IrOperationModel("LABEL", List.of("Lmid"), 3),
        new IrOperationModel("CONSTANT", List.of("1"), 4),
        new IrOperationModel("LOCAL_WRITE", List.of("local2", "1"), 5),
        new IrOperationModel("JUMP", List.of("L0"), 6),
        new IrOperationModel("CONDITIONAL_BRANCH", List.of("local2 != 0", "L0"), 7),
        new IrOperationModel("LABEL", List.of("Lbreak"), 8),
        new IrOperationModel("LOCAL_READ", List.of("local2"), 9),
        new IrOperationModel("RETURN", List.of("local2"), 10)));

    TranslationResult result = new JavaTranslator().translate(method);

    assertTrue(result.complete(), result.source());
    assertTrue(result.source().contains("do {"), result.source());
    assertTrue(result.source().contains("if (terminator == 0) break;"), result.source());
    assertTrue(result.source().contains("} while (local2 != 0);"), result.source());
    assertFalse(result.source().contains("unsupported(\"JUMP\""), result.source());
  }

  @Test
  void foldsConstructorDuplicateIntoConditionalValueMerge() {
    IrMethodModel method = new IrMethodModel("read", "()Ljava/lang/String;", List.of(
        new IrOperationModel("CONDITIONAL_BRANCH", List.of("local1 != null", "Lthen"), 0),
        new IrOperationModel("OBJECT_CREATE", List.of("new java.lang.String"), 1),
        new IrOperationModel("DUP", List.of("new java.lang.String"), 2),
        new IrOperationModel("LOCAL_READ", List.of("local2"), 3),
        new IrOperationModel("CALL_VALUE", List.of("new java.lang.String(local2)"), 4),
        new IrOperationModel("JUMP", List.of("Ljoin"), 5),
        new IrOperationModel("LABEL", List.of("Lthen"), 6),
        new IrOperationModel("LOCAL_READ", List.of("local1"), 7),
        new IrOperationModel(
            "CALL_VALUE", List.of("local1.append(local2).toString()"), 8),
        new IrOperationModel("LABEL", List.of("Ljoin"), 9),
        new IrOperationModel("LOCAL_WRITE", List.of("local3", "<unknown>"), 10),
        new IrOperationModel("LOCAL_READ", List.of("local3"), 11),
        new IrOperationModel("RETURN", List.of("local3"), 12)));

    TranslationResult result = new JavaTranslator().translate(method);

    assertTrue(result.complete(), result.source());
    assertTrue(result.source().contains(
        "var local3 = (local1 != null ? local1.append(local2).toString() "
            + ": new java.lang.String(local2));"),
        result.source());
    assertFalse(result.source().contains("<unknown>"), result.source());
  }

  @Test
  void structuresShortCircuitChainWithTwoExpressionBlocksIntoLocalTernary() {
    IrMethodModel method = new IrMethodModel("isVisible", "()V", List.of(
        new IrOperationModel("CONDITIONAL_BRANCH", List.of("a == 0", "Ltrue"), 0),
        new IrOperationModel("CONDITIONAL_BRANCH", List.of("b == 0", "Ltrue"), 1),
        new IrOperationModel("CALL_VALUE", List.of("c.hasNext()"), 2),
        new IrOperationModel("CONDITIONAL_BRANCH", List.of("c.hasNext() == 0", "Lfalse"), 3),
        new IrOperationModel("LABEL", List.of("Ltrue"), 4),
        new IrOperationModel("CONSTANT", List.of("1"), 5),
        new IrOperationModel("JUMP", List.of("Ljoin"), 6),
        new IrOperationModel("LABEL", List.of("Lfalse"), 7),
        new IrOperationModel("CONSTANT", List.of("0"), 8),
        new IrOperationModel("LABEL", List.of("Ljoin"), 9),
        new IrOperationModel("LOCAL_WRITE", List.of("local3", "<unknown>"), 10),
        new IrOperationModel("RETURN", List.of("local3"), 11)));

    TranslationResult result = new JavaTranslator().translate(method);

    assertTrue(result.complete(), result.source());
    assertTrue(result.source().contains(
        "var local3 = (a == 0 ? 1 : (b == 0 ? 1 : (c.hasNext() == 0 ? 0 : 1)));"),
        result.source());
    assertTrue(!result.source().contains("<unknown>"), result.source());
    assertTrue(!result.source().contains("unsupported(\"JUMP\""), result.source());
  }

  @Test
  void structuresShortCircuitChainIntoMethodArgumentSink() {
    IrMethodModel method = new IrMethodModel("updateVisibility", "()V", List.of(
        new IrOperationModel("CONDITIONAL_BRANCH", List.of("error == null", "Lactive"), 0),
        new IrOperationModel("CONDITIONAL_BRANCH",
            List.of("error.type.allowsTransfer == 0", "Linactive"), 1),
        new IrOperationModel("LABEL", List.of("Lactive"), 2),
        new IrOperationModel("CONSTANT", List.of("1"), 3),
        new IrOperationModel("JUMP", List.of("Ljoin"), 4),
        new IrOperationModel("LABEL", List.of("Linactive"), 5),
        new IrOperationModel("CONSTANT", List.of("0"), 6),
        new IrOperationModel("LABEL", List.of("Ljoin"), 7),
        new IrOperationModel("METHOD_CALL", List.of("button.setVisible(<unknown>)"), 8),
        new IrOperationModel("RETURN", List.of(), 9)));

    TranslationResult result = new JavaTranslator().translate(method);

    assertTrue(result.complete(), result.source());
    assertTrue(result.source().contains(
        "button.setVisible((error == null ? 1 : (error.type.allowsTransfer == 0 ? 0 : 1)));"),
        result.source());
    assertTrue(!result.source().contains("<unknown>"), result.source());
    assertTrue(!result.source().contains("unsupported(\"JUMP\""), result.source());
  }

  @Test
  void foldsNestedMergeValuesIntoTheirConsumingCondition() {
    IrMethodModel method = new IrMethodModel("updateFlags", "()V", List.of(
        new IrOperationModel("CONDITIONAL_BRANCH", List.of("settings != 0", "Ltrue"), 0),
        new IrOperationModel("LOCAL_READ", List.of("local0"), 1),
        new IrOperationModel("FIELD_READ", List.of("local0", "A", "local0.a"), 2),
        new IrOperationModel("CONSTANT", List.of("-1"), 3),
        new IrOperationModel("CONDITIONAL_BRANCH", List.of("local0.a != -1", "LskipA"), 4),
        new IrOperationModel("CONSTANT", List.of("1"), 5),
        new IrOperationModel("JUMP", List.of("LjoinA"), 6),
        new IrOperationModel("LABEL", List.of("LskipA"), 7),
        new IrOperationModel("CONSTANT", List.of("0"), 8),
        new IrOperationModel("LABEL", List.of("LjoinA"), 9),
        new IrOperationModel("LOCAL_READ", List.of("local0"), 10),
        new IrOperationModel("FIELD_READ", List.of("local0", "B", "local0.b"), 11),
        new IrOperationModel("CONSTANT", List.of("-1"), 12),
        new IrOperationModel("CONDITIONAL_BRANCH", List.of("local0.b != -1", "LskipB"), 13),
        new IrOperationModel("CONSTANT", List.of("1"), 14),
        new IrOperationModel("JUMP", List.of("LjoinB"), 15),
        new IrOperationModel("LABEL", List.of("LskipB"), 16),
        new IrOperationModel("CONSTANT", List.of("0"), 17),
        new IrOperationModel("LABEL", List.of("LjoinB"), 18),
        new IrOperationModel("CONDITIONAL_BRANCH", List.of("<unknown> == <unknown>", "Lfalse"), 19),
        new IrOperationModel("LABEL", List.of("Ltrue"), 20),
        new IrOperationModel("CONSTANT", List.of("1"), 21),
        new IrOperationModel("JUMP", List.of("Ljoin"), 22),
        new IrOperationModel("LABEL", List.of("Lfalse"), 23),
        new IrOperationModel("CONSTANT", List.of("0"), 24),
        new IrOperationModel("LABEL", List.of("Ljoin"), 25),
        new IrOperationModel("LOCAL_WRITE", List.of("local0", "<unknown>"), 26),
        new IrOperationModel("RETURN", List.of(), 27)));

    TranslationResult result = new JavaTranslator().translate(method);

    assertTrue(result.complete(), result.source());
    assertTrue(!result.source().contains("<unknown>"), result.source());
    assertTrue(result.source().contains("(settings != 0 ? 1 :"), result.source());
    assertTrue(result.source().contains(
        "(local0.a != -1 ? 0 : 1) == (local0.b != -1 ? 0 : 1)"), result.source());
  }

  @Test
  void startsElseArmAtTryMarkerWhenBranchTargetsProtectedLabel() {
    IrMethodModel method = new IrMethodModel("updateItem", "()V", List.of(
        new IrOperationModel("CONDITIONAL_BRANCH", List.of("local1.isEmpty() != 0", "Lthen"), 0),
        new IrOperationModel("LOCAL_READ", List.of("local1"), 1),
        new IrOperationModel("CONSTANT", List.of("\"-\""), 2),
        new IrOperationModel("CALL_VALUE", List.of("local1.equals(\"-\")"), 3),
        new IrOperationModel("CONDITIONAL_BRANCH", List.of("local1.equals(\"-\") == 0", "Ltry"), 4),
        new IrOperationModel("LABEL", List.of("Lthen"), 5),
        new IrOperationModel("FIELD_WRITE",
            List.of("local0", "Example.item", "null"), 6),
        new IrOperationModel("RETURN", List.of(), 7),
        new IrOperationModel("TRY_BEGIN",
            List.of("Ltry", "Lend", "Lhandler", "java.lang.Exception", "false"), 8),
        new IrOperationModel("LABEL", List.of("Ltry"), 9),
        new IrOperationModel("OBJECT_CREATE", List.of("new java.lang.Object"), 10),
        new IrOperationModel("DUP", List.of("new java.lang.Object"), 11),
        new IrOperationModel("LOCAL_READ", List.of("local1"), 12),
        new IrOperationModel("CALL_VALUE", List.of("new java.lang.Object(local1)"), 13),
        new IrOperationModel("LOCAL_WRITE", List.of("local2", "new java.lang.Object(local1)"), 14),
        new IrOperationModel("TRY_END",
            List.of("Ltry", "Lend", "Lhandler", "java.lang.Exception", "false"), 15),
        new IrOperationModel("LABEL", List.of("Lend"), 16),
        new IrOperationModel("JUMP", List.of("Ljoin"), 17),
        new IrOperationModel("CATCH_BEGIN",
            List.of("Ltry", "Lend", "Lhandler", "java.lang.Exception", "false"), 18),
        new IrOperationModel("LABEL", List.of("Lhandler"), 19),
        new IrOperationModel("LOCAL_WRITE", List.of("local3", "<exception>"), 20),
        new IrOperationModel("CALL_VALUE", List.of("log(local3.getMessage())"), 21),
        new IrOperationModel("RETURN", List.of(), 22),
        new IrOperationModel("LABEL", List.of("Ljoin"), 23),
        new IrOperationModel("CALL_VALUE", List.of("use(local2)"), 24),
        new IrOperationModel("RETURN", List.of(), 25)));

    TranslationResult result = new JavaTranslator().translate(method);

    assertTrue(result.complete(), result.source());
    assertTrue(result.source().contains("try {"), result.source());
    assertTrue(result.source().contains("} catch (java.lang.Exception local3) {"),
        result.source());
    assertFalse(result.source().contains("unsupported(\"try-catch-region\""), result.source());
    assertFalse(result.source().contains("unsupported(\"JUMP\""), result.source());
  }

  @Test
  void structuresInfiniteLoopWithEarlyReturnsAndContinue() {
    IrMethodModel method = new IrMethodModel("findEntry", "()Z", List.of(
        new IrOperationModel("LABEL", List.of("Lloop"), 0),
        new IrOperationModel("CALL_VALUE", List.of("nextEntry()"), 1),
        new IrOperationModel("LOCAL_WRITE", List.of("local2", "nextEntry()"), 2),
        new IrOperationModel("CONDITIONAL_BRANCH", List.of("local2 == null", "LnotFound"), 3),
        new IrOperationModel("CALL_VALUE", List.of("local2.getName().equals(name)"), 4),
        new IrOperationModel("CONDITIONAL_BRANCH", List.of("local2.getName().equals(name) == 0", "Lcontinue"), 5),
        new IrOperationModel("CONSTANT", List.of("1"), 6),
        new IrOperationModel("RETURN", List.of("1"), 7),
        new IrOperationModel("LABEL", List.of("LnotFound"), 8),
        new IrOperationModel("CONSTANT", List.of("0"), 9),
        new IrOperationModel("RETURN", List.of("0"), 10),
        new IrOperationModel("LABEL", List.of("Lcontinue"), 11),
        new IrOperationModel("JUMP", List.of("Lloop"), 12)));

    TranslationResult result = new JavaTranslator().translate(method);

    assertTrue(result.complete(), result.source());
    assertTrue(result.source().contains("while (true)"), result.source());
    assertTrue(result.source().contains("if (local2 == null)"), result.source());
    assertTrue(result.source().contains("return 0;"), result.source());
    assertTrue(result.source().contains("continue;"), result.source());
    assertTrue(result.source().contains("return 1;"), result.source());
    assertTrue(!result.source().contains("unsupported(\"JUMP\""), result.source());
  }

  @Test
  void treatsSwitchDefaultThatIsTheJoinAsContinuation() {
    IrMethodModel method = new IrMethodModel("trim", "()V", List.of(
        new IrOperationModel("LOCAL_READ", List.of("size"), 0),
        new IrOperationModel("TABLE_SWITCH",
            List.of("size", "0", "2", "Ljoin", "Lempty", "Lsingle", "Lmany"), 1),
        new IrOperationModel("LABEL", List.of("Lempty"), 2),
        new IrOperationModel("METHOD_CALL", List.of("emptyMap()"), 3),
        new IrOperationModel("JUMP", List.of("Ljoin"), 4),
        new IrOperationModel("LABEL", List.of("Lsingle"), 5),
        new IrOperationModel("METHOD_CALL", List.of("singletonMap()"), 6),
        new IrOperationModel("JUMP", List.of("Ljoin"), 7),
        new IrOperationModel("LABEL", List.of("Lmany"), 8),
        new IrOperationModel("METHOD_CALL", List.of("copyMap()"), 9),
        new IrOperationModel("LABEL", List.of("Ljoin"), 10),
        new IrOperationModel("RETURN", List.of(), 11)));

    TranslationResult result = new JavaTranslator().translate(method);

    assertTrue(result.complete(), result.source());
    assertTrue(result.source().contains("switch (size)"), result.source());
    assertTrue(result.source().contains("case 0:"), result.source());
    assertTrue(result.source().contains("default:") == false, result.source());
    assertTrue(result.source().contains("emptyMap();"), result.source());
    assertTrue(result.source().contains("singletonMap();"), result.source());
    assertTrue(result.source().contains("copyMap();"), result.source());
    assertTrue(!result.source().contains("unsupported(\"JUMP\""), result.source());
  }

  @Test
  void structuresSwitchThatExitsNestedRangeIntoSharedReturn() {
    IrMethodModel method = new IrMethodModel("classify", "(I)I", List.of(
        new IrOperationModel("LOCAL_READ", List.of("ready"), 0),
        new IrOperationModel("CONDITIONAL_BRANCH", List.of("ready == 0", "Lret"), 0),
        new IrOperationModel("LOCAL_READ", List.of("type"), 1),
        new IrOperationModel("TABLE_SWITCH",
            List.of("type", "1", "2", "Lret", "Ltrue", "Ltrue"), 1),
        new IrOperationModel("LABEL", List.of("Ltrue"), 2),
        new IrOperationModel("CONSTANT", List.of("1"), 3),
        new IrOperationModel("RETURN", List.of("1"), 4),
        new IrOperationModel("LABEL", List.of("Lret"), 5),
        new IrOperationModel("CONSTANT", List.of("0"), 6),
        new IrOperationModel("RETURN", List.of("0"), 7)));

    TranslationResult result = new JavaTranslator().translate(method);

    assertTrue(result.complete(), result.source());
    assertTrue(result.source().contains("switch (type)"), result.source());
    assertTrue(result.source().contains("case 1:"), result.source());
    assertTrue(result.source().contains("case 2:"), result.source());
    assertTrue(result.source().contains("return 1;"), result.source());
    assertTrue(result.source().contains("return 0;"), result.source());
    assertTrue(!result.source().contains("default:"), result.source());
    assertTrue(!result.source().contains("unsupported("), result.source());
  }

  @Test
  void lowersASharedLoopTailReachedFromANestedArm() {
    IrMethodModel method = new IrMethodModel("scan", "()V", List.of(
        new IrOperationModel("LABEL", List.of("Lloop"), 0),
        new IrOperationModel("CALL_VALUE", List.of("hasNext()"), 1),
        new IrOperationModel("CONDITIONAL_BRANCH", List.of("hasNext() == 0", "Lend"), 2),
        new IrOperationModel("CONDITIONAL_BRANCH", List.of("isFrame", "Lskip"), 3),
        new IrOperationModel("JUMP", List.of("Ltail"), 4),
        new IrOperationModel("LABEL", List.of("Lskip"), 5),
        new IrOperationModel("METHOD_CALL", List.of("process()"), 6),
        new IrOperationModel("LABEL", List.of("Ltail"), 7),
        new IrOperationModel("LOCAL_READ", List.of("keep"), 8),
        new IrOperationModel("CONDITIONAL_BRANCH", List.of("keep == 0", "Lcontinue"), 9),
        new IrOperationModel("JUMP", List.of("Lend"), 10),
        new IrOperationModel("LABEL", List.of("Lcontinue"), 11),
        new IrOperationModel("JUMP", List.of("Lloop"), 12),
        new IrOperationModel("LABEL", List.of("Lend"), 13),
        new IrOperationModel("RETURN", List.of(), 14)));

    TranslationResult result = new JavaTranslator().translate(method);

    assertTrue(result.complete(), result.source());
    assertTrue(result.source().contains("while (true)"), result.source());
    assertTrue(result.source().contains("if (!((keep == 0))) break;"), result.source());
    assertTrue(!result.source().contains("unsupported(\"JUMP\""), result.source());
  }

  @Test
  void prioritizesOuterForwardExitOverInnerContinueBranch() {
    IrMethodModel method = new IrMethodModel("loopWithContinue", "()V", List.of(
        new IrOperationModel("LABEL", List.of("Lloop"), 0),
        new IrOperationModel("CALL_VALUE", List.of("hasNext()"), 1),
        new IrOperationModel("CONDITIONAL_BRANCH", List.of("hasNext() == 0", "Lend"), 2),
        new IrOperationModel("CONDITIONAL_BRANCH", List.of("skipItem", "Lloop"), 3),
        new IrOperationModel("METHOD_CALL", List.of("processItem()"), 4),
        new IrOperationModel("JUMP", List.of("Lloop"), 5),
        new IrOperationModel("LABEL", List.of("Lend"), 6),
        new IrOperationModel("RETURN", List.of(), 7)));

    TranslationResult result = new JavaTranslator().translate(method);

    assertTrue(result.complete(), result.source());
    assertTrue(result.source().contains("while (true)"), result.source());
    assertTrue(result.source().contains("if (skipItem) continue;"), result.source());
    assertTrue(result.source().contains("processItem();"), result.source());
    assertTrue(!result.source().contains("unsupported(\"JUMP\""), result.source());
  }

  @Test
  void lowersMultiConditionBooleanReturnChain() {
    IrMethodModel method = new IrMethodModel("isMouseOver", "()Z", List.of(
        new IrOperationModel("CONDITIONAL_BRANCH", List.of("rectangle.contains(x, y) != 0", "Ltrue"), 0),
        new IrOperationModel("FIELD_READ", List.of("recipeTransferButton"), 1),
        new IrOperationModel("CONDITIONAL_BRANCH", List.of("recipeTransferButton == null", "Lfalse"), 2),
        new IrOperationModel("FIELD_READ", List.of("recipeTransferButton"), 3),
        new IrOperationModel("CALL_VALUE", List.of("recipeTransferButton.isHovered()"), 4),
        new IrOperationModel("CONDITIONAL_BRANCH", List.of("recipeTransferButton.isHovered() == 0", "Lfalse"), 5),
        new IrOperationModel("LABEL", List.of("Ltrue"), 6),
        new IrOperationModel("CONSTANT", List.of("1"), 7),
        new IrOperationModel("JUMP", List.of("Ljoin"), 8),
        new IrOperationModel("LABEL", List.of("Lfalse"), 9),
        new IrOperationModel("CONSTANT", List.of("0"), 10),
        new IrOperationModel("LABEL", List.of("Ljoin"), 11),
        new IrOperationModel("RETURN", List.of("<unknown>"), 12)));

    TranslationResult result = new JavaTranslator().translate(method);

    assertTrue(result.complete(), result.source());
    assertTrue(result.source().contains("if (rectangle.contains(x, y) != 0)"), result.source());
    assertTrue(result.source().contains("if (recipeTransferButton == null)"), result.source());
    assertTrue(result.source().contains("if (recipeTransferButton.isHovered() == 0)"), result.source());
    assertTrue(result.source().contains("return 1;"), result.source());
    assertTrue(result.source().contains("return 0;"), result.source());
    assertTrue(!result.source().contains("<unknown>"), result.source());
    assertTrue(!result.source().contains("unsupported(\"JUMP\""), result.source());
  }

  @Test
  void structuresContinueDiamondWithoutLeavingGoto() {
    IrMethodModel method = new IrMethodModel("loopWithConditionalContinue", "()V", List.of(
        new IrOperationModel("LABEL", List.of("Lloop"), 0),
        new IrOperationModel("CONDITIONAL_BRANCH", List.of("done", "Lend"), 1),
        new IrOperationModel("CONDITIONAL_BRANCH", List.of("skipItem", "Lcontinue"), 2),
        new IrOperationModel("METHOD_CALL", List.of("processItem()"), 3),
        new IrOperationModel("JUMP", List.of("Ljoin"), 4),
        new IrOperationModel("LABEL", List.of("Lcontinue"), 5),
        new IrOperationModel("JUMP", List.of("Lloop"), 6),
        new IrOperationModel("LABEL", List.of("Ljoin"), 7),
        new IrOperationModel("JUMP", List.of("Lloop"), 8),
        new IrOperationModel("LABEL", List.of("Lend"), 9),
        new IrOperationModel("RETURN", List.of(), 10)));

    TranslationResult result = new JavaTranslator().translate(method);

    assertTrue(result.complete(), result.source());
    assertTrue(result.source().contains("if (skipItem) continue;"), result.source());
    assertTrue(result.source().contains("processItem();"), result.source());
    assertTrue(!result.source().contains("unsupported(\"JUMP\""), result.source());
  }

  @Test
  void structuresConditionalAssignmentChainWithSharedJoin() {
    IrMethodModel method = new IrMethodModel("chooseDimension", "()V", List.of(
        new IrOperationModel("CONDITIONAL_BRANCH", List.of("direction == TOP", "Lheight"), 0),
        new IrOperationModel("FIELD_READ", List.of("direction"), 1),
        new IrOperationModel("CONDITIONAL_BRANCH", List.of("direction != BOTTOM", "Lwidth"), 2),
        new IrOperationModel("LABEL", List.of("Lheight"), 3),
        new IrOperationModel("CALL_VALUE", List.of("drawable.getHeight()"), 4),
        new IrOperationModel("LOCAL_WRITE", List.of("local6", "drawable.getHeight()"), 5),
        new IrOperationModel("JUMP", List.of("Ljoin"), 6),
        new IrOperationModel("LABEL", List.of("Lwidth"), 7),
        new IrOperationModel("CALL_VALUE", List.of("drawable.getWidth()"), 8),
        new IrOperationModel("LOCAL_WRITE", List.of("local6", "drawable.getWidth()"), 9),
        new IrOperationModel("LABEL", List.of("Ljoin"), 10),
        new IrOperationModel("RETURN", List.of("local6"), 11)));

    TranslationResult result = new JavaTranslator().translate(method);

    assertTrue(result.complete(), result.source());
    assertTrue(result.source().contains("if (direction == TOP)"), result.source());
    assertTrue(result.source().contains("if (direction != BOTTOM)"), result.source());
    assertTrue(result.source().contains("drawable.getHeight()"), result.source());
    assertTrue(result.source().contains("drawable.getWidth()"), result.source());
    assertTrue(!result.source().contains("unsupported(\"JUMP\""), result.source());
  }

  @Test
  void structuresCatchHandlerLoopUntilItsSharedJoin() {
    List<String> region = List.of("Ltry", "Lend", "Lhandler", "java.lang.RuntimeException");
    IrMethodModel method = new IrMethodModel("readWithRecovery", "()V", List.of(
        new IrOperationModel("TRY_BEGIN", region, 0),
        new IrOperationModel("LABEL", List.of("Ltry"), 1),
        new IrOperationModel("METHOD_CALL", List.of("protectedWork()"), 2),
        new IrOperationModel("TRY_END", region, 3),
        new IrOperationModel("LABEL", List.of("Lend"), 4),
        new IrOperationModel("JUMP", List.of("Ljoin"), 5),
        new IrOperationModel("CATCH_BEGIN", region, 6),
        new IrOperationModel("LABEL", List.of("Lhandler"), 7),
        new IrOperationModel("LOCAL_WRITE", List.of("local1", "<exception>"), 8),
        new IrOperationModel("LABEL", List.of("Lloop"), 9),
        new IrOperationModel("CONDITIONAL_BRANCH", List.of("local1.isDone() != 0", "LafterLoop"), 10),
        new IrOperationModel("METHOD_CALL", List.of("recoverStep()"), 11),
        new IrOperationModel("JUMP", List.of("Lloop"), 12),
        new IrOperationModel("LABEL", List.of("LafterLoop"), 13),
        new IrOperationModel("METHOD_CALL", List.of("afterCatch()"), 14),
        new IrOperationModel("LABEL", List.of("Ljoin"), 15),
        new IrOperationModel("RETURN", List.of(), 16)));

    TranslationResult result = new JavaTranslator().translate(method);

    assertTrue(result.complete(), result.source());
    assertTrue(result.source().contains("try {"), result.source());
    assertTrue(result.source().contains("catch (java.lang.RuntimeException local1)"), result.source());
    assertTrue(result.source().contains("while (true)"), result.source());
    assertTrue(result.source().contains("recoverStep();"), result.source());
    assertTrue(result.source().contains("afterCatch();"), result.source());
    assertTrue(!result.source().contains("<exception>"), result.source());
    assertTrue(!result.source().contains("unsupported(\"JUMP\""), result.source());
  }

  @Test
  void earlyReturnBranchConsumesSkipGotoToSharedJoin() {
    IrMethodModel method = new IrMethodModel("findOrNull", "()Ljava/lang/Object;", List.of(
        new IrOperationModel("CONDITIONAL_BRANCH", List.of("alreadyFound != 0", "LreturnNull"), 0),
        new IrOperationModel("METHOD_CALL", List.of("scanNext()"), 1),
        new IrOperationModel("LOCAL_WRITE", List.of("local2", "scanNext()"), 2),
        new IrOperationModel("JUMP", List.of("Ljoin"), 3),
        new IrOperationModel("LABEL", List.of("LreturnNull"), 4),
        new IrOperationModel("CONSTANT", List.of("null"), 5),
        new IrOperationModel("RETURN", List.of("null"), 6),
        new IrOperationModel("LABEL", List.of("Ljoin"), 7),
        new IrOperationModel("RETURN", List.of("local2"), 8)));

    TranslationResult result = new JavaTranslator().translate(method);

    assertTrue(result.complete(), result.source());
    assertTrue(result.source().contains("if (alreadyFound != 0)"), result.source());
    assertTrue(result.source().contains("return null;"), result.source());
    assertTrue(result.source().contains("local2 = scanNext();"), result.source());
    assertTrue(!result.source().contains("unsupported(\"JUMP\""), result.source());
  }

  @Test
  void keepsTryStartAndNormalReturnWhenBranchTargetsProtectedRegion() {
    List<String> region = List.of("Ltry", "Lend", "Lhandler", "java.lang.RuntimeException");
    IrMethodModel method = new IrMethodModel("safeToString", "()Ljava/lang/String;", List.of(
        new IrOperationModel("CONDITIONAL_BRANCH", List.of("isNotRegistryEntry != 0", "Ltry"), 0),
        new IrOperationModel("CONSTANT", List.of("\"fallback\""), 1),
        new IrOperationModel("RETURN", List.of("\"fallback\""), 2),
        new IrOperationModel("TRY_BEGIN", region, 3),
        new IrOperationModel("LABEL", List.of("Ltry"), 4),
        new IrOperationModel("CALL_VALUE", List.of("local0.toString()"), 5),
        new IrOperationModel("TRY_END", region, 6),
        new IrOperationModel("LABEL", List.of("Lend"), 7),
        new IrOperationModel("RETURN", List.of("local0.toString()"), 8),
        new IrOperationModel("CATCH_BEGIN", region, 9),
        new IrOperationModel("LABEL", List.of("Lhandler"), 10),
        new IrOperationModel("LOCAL_WRITE", List.of("local1", "<exception>"), 11),
        new IrOperationModel("METHOD_CALL", List.of("logFailure(local1)"), 12),
        new IrOperationModel("RETURN", List.of("local0.getClass().toString()"), 13)));

    TranslationResult result = new JavaTranslator().translate(method);

    assertTrue(result.complete(), result.source());
    assertTrue(result.source().contains("if (isNotRegistryEntry == 0)"), result.source());
    assertTrue(result.source().contains("try {"), result.source());
    assertTrue(result.source().contains("return local0.toString();"), result.source());
    assertTrue(result.source().contains("catch (java.lang.RuntimeException local1)"), result.source());
    assertTrue(result.source().contains("return local0.getClass().toString();"), result.source());
    assertTrue(!result.source().contains("<exception>"), result.source());
  }

  @Test
  void processesLoopHeaderAfterConditionalJoin() {
    IrMethodModel method = new IrMethodModel("chooseThenLoop", "()V", List.of(
        new IrOperationModel("LABEL", List.of("Lentry"), 0),
        new IrOperationModel("CONDITIONAL_BRANCH", List.of("useElse", "Lelse"), 1),
        new IrOperationModel("METHOD_CALL", List.of("thenBranch()"), 2),
        new IrOperationModel("JUMP", List.of("Lloop"), 3),
        new IrOperationModel("LABEL", List.of("Lelse"), 4),
        new IrOperationModel("METHOD_CALL", List.of("elseBranch()"), 5),
        new IrOperationModel("LABEL", List.of("Lloop"), 6),
        new IrOperationModel("CONDITIONAL_BRANCH", List.of("done", "Lend"), 7),
        new IrOperationModel("METHOD_CALL", List.of("loopStep()"), 8),
        new IrOperationModel("JUMP", List.of("Lloop"), 9),
        new IrOperationModel("LABEL", List.of("Lend"), 10),
        new IrOperationModel("RETURN", List.of(), 11)));

    TranslationResult result = new JavaTranslator().translate(method);

    assertTrue(result.complete(), result.source());
    assertTrue(result.source().contains("thenBranch();"), result.source());
    assertTrue(result.source().contains("elseBranch();"), result.source());
    assertTrue(result.source().contains("while (true)"), result.source());
    assertTrue(result.source().contains("loopStep();"), result.source());
    assertTrue(!result.source().contains("unsupported(\"JUMP\""), result.source());
  }

  @Test
  void preservesTryRegionAtSharedConditionalJoin() {
    List<String> protectedRegion =
        List.of("Lprotected", "LprotectedEnd", "LprotectedCatch", "java.lang.RuntimeException");
    List<String> joinRegion =
        List.of("Ljoin", "LjoinEnd", "LjoinCatch", "java.lang.RuntimeException");
    IrMethodModel method = new IrMethodModel("branchIntoProtectedWork", "()V", List.of(
        new IrOperationModel("CONDITIONAL_BRANCH", List.of("useProtected != 0", "Lprotected"), 0),
        new IrOperationModel("JUMP", List.of("Ljoin"), 1),
        new IrOperationModel("TRY_BEGIN", protectedRegion, 2),
        new IrOperationModel("LABEL", List.of("Lprotected"), 3),
        new IrOperationModel("METHOD_CALL", List.of("protectedWork()"), 4),
        new IrOperationModel("TRY_END", protectedRegion, 5),
        new IrOperationModel("LABEL", List.of("LprotectedEnd"), 6),
        new IrOperationModel("JUMP", List.of("Ljoin"), 7),
        new IrOperationModel("CATCH_BEGIN", protectedRegion, 8),
        new IrOperationModel("LABEL", List.of("LprotectedCatch"), 9),
        new IrOperationModel("LOCAL_WRITE", List.of("local1", "<exception>"), 10),
        new IrOperationModel("METHOD_CALL", List.of("recoverProtected(local1)"), 11),
        new IrOperationModel("RETURN", List.of(), 12),
        new IrOperationModel("TRY_BEGIN", joinRegion, 13),
        new IrOperationModel("LABEL", List.of("Ljoin"), 14),
        new IrOperationModel("METHOD_CALL", List.of("afterJoin()"), 15),
        new IrOperationModel("TRY_END", joinRegion, 16),
        new IrOperationModel("LABEL", List.of("LjoinEnd"), 17),
        new IrOperationModel("RETURN", List.of(), 18),
        new IrOperationModel("CATCH_BEGIN", joinRegion, 19),
        new IrOperationModel("LABEL", List.of("LjoinCatch"), 20),
        new IrOperationModel("LOCAL_WRITE", List.of("local2", "<exception>"), 21),
        new IrOperationModel("THROW", List.of("local2"), 22)));

    TranslationResult result = new JavaTranslator().translate(method);

    assertTrue(result.complete(), result.source());
    assertTrue(result.source().contains("if (useProtected != 0)"), result.source());
    assertTrue(result.source().contains("protectedWork();"), result.source());
    assertTrue(result.source().contains("afterJoin();"), result.source());
    assertTrue(result.source().contains("catch (java.lang.RuntimeException local1)"), result.source());
    assertTrue(!result.source().contains("<exception>"), result.source());
    assertTrue(!result.source().contains("unsupported(\"JUMP\""), result.source());
  }

}
