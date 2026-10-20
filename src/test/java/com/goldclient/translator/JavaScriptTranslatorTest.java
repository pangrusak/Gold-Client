package com.goldclient.translator;

import com.goldclient.model.IrMethodModel;
import com.goldclient.model.IrOperationModel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaScriptTranslatorTest {
  @TempDir
  Path tempDir;

  @Test
  void lowersSimpleMethodIntoAnExportedJavaScriptFunction() {
    var method = new IrMethodModel("isReady", "()Z", List.of(
        op("CONSTANT", "true"), op("RETURN", "true")));
    TranslationResult result = new JavaScriptTranslator().translate(method);

    assertTrue(result.complete(), result.source());
    assertTrue(result.source().matches("(?s).*export function isReady__\\w+\\(\\) \\{.*"));
    assertTrue(result.source().contains("return true;"));
    assertFalse(result.source().contains("boolean isReady"));
  }

  @Test
  void keepsCallsOnUppercaseForgeConstantsAsFieldReceivers() {
    assertEquals(
        "GoldClientRuntime.getStaticField(\"net.minecraftforge.fml.common.registry.ForgeRegistries\", \"ITEMS\").getValuesCollection()",
        JavaScriptTranslator.rewriteCode(new StringBuilder(
            "net.minecraftforge.fml.common.registry.ForgeRegistries.ITEMS.getValuesCollection()")));
    assertEquals(
        "GoldClientRuntime.getStaticField(\"net.minecraftforge.fml.common.network.NetworkRegistry\", \"INSTANCE\").newSimpleChannel(\"appleskin\")",
        JavaScriptTranslator.rewriteCode(new StringBuilder(
            "net.minecraftforge.fml.common.network.NetworkRegistry.INSTANCE.newSimpleChannel(\"appleskin\")")));
    assertEquals(
        "GoldClientRuntime.getStaticField(\"net.minecraftforge.common.MinecraftForge\", \"EVENT_BUS\").register(this)",
        JavaScriptTranslator.rewriteCode(new StringBuilder(
            "net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(this)")));
    assertFalse(JavaScriptTranslator.rewriteCode(new StringBuilder(
        "ForgeRegistries.ITEMS.getValuesCollection()")).contains("GoldClientRuntime\", \"GoldClientRuntime"));
    assertEquals(
        "\"appleskin\".toLowerCase(GoldClientRuntime.getStaticField(\"java.util.Locale\", \"ROOT\"))",
        JavaScriptTranslator.rewriteCode(new StringBuilder(
            "\"appleskin\".toLowerCase(java.util.Locale.ROOT)")));
  }

  @Test
  void mapsInstanceLocalZeroToThisAndKeepsParameterSlotsAligned() {
    var instanceMethod = new IrMethodModel("getSelf", "()Ljava/lang/Object;", List.of(
        op("RETURN", "local0")));
    TranslationResult instance = new JavaScriptTranslator().translate(instanceMethod, false);
    assertTrue(instance.source().contains("return this;"), instance.source());

    var withParameter = new IrMethodModel("identity", "(I)I", List.of(
        op("RETURN", "local1")));
    TranslationResult translated = new JavaScriptTranslator().translate(withParameter, false);
    assertTrue(translated.source().contains("identity__"), translated.source());
    assertTrue(translated.source().contains("(local1)"), translated.source());
    assertTrue(translated.source().contains("return local1;"), translated.source());
  }

  @Test
  void keepsMethodsEmittableWhenOnlyStackValuesNeedAnExplicitFallback() {
    var method = new IrMethodModel("approximate", "()Ljava/lang/Object;", List.of(
        op("RETURN", "<unknown>")));
    TranslationResult result = new JavaScriptTranslator().translate(method);

    assertTrue(result.complete(), result.source());
    assertTrue(result.source().contains(
        "Approximation: unresolved JVM stack/exception values were emitted as undefined."),
        result.source());
    assertTrue(result.source().contains("return undefined;"), result.source());
    assertFalse(result.source().contains("<unknown>"), result.source());
  }

  @Test
  void emitsExplicitFallbackForAnUnresolvedExceptionStackValue() {
    var method = new IrMethodModel("exceptionFallback", "()Ljava/lang/Object;", List.of(
        op("RETURN", "<exception>")));
    TranslationResult result = new JavaScriptTranslator().translate(method);

    assertTrue(result.complete(), result.source());
    assertTrue(result.source().contains("return undefined;"), result.source());
    assertTrue(result.source().contains(
        "Approximation: unresolved JVM stack/exception values were emitted as undefined."),
        result.source());
    assertFalse(result.source().contains("<exception>"), result.source());
  }

  @Test
  void unsupportedOperationsBecomeExplicitRuntimeFailures() {
    var method = new IrMethodModel("unknown", "()V", List.of(
        op("UNKNOWN", "mystery-op"), op("RETURN")));
    TranslationResult result = new JavaScriptTranslator().translate(method);
    assertFalse(result.complete());
    assertTrue(result.source().contains("GoldClientRuntime.unsupported("), result.source());
    assertFalse(result.source().contains("// TODO:"), result.source());
  }

  @Test
  void lowersCatchSyntaxAndJavaExceptionConstructors() {
    var method = new IrMethodModel("guard", "()V", List.of(
        op("TRY_BEGIN", "Ltry", "Lend", "Lhandler", "java.lang.IllegalArgumentException"),
        op("LABEL", "Ltry"),
        op("METHOD_CALL", "mightFail()"),
        op("TRY_END", "Ltry", "Lend", "Lhandler", "java.lang.IllegalArgumentException"),
        op("LABEL", "Lend"),
        op("JUMP", "Ljoin"),
        op("CATCH_BEGIN", "Ltry", "Lend", "Lhandler", "java.lang.IllegalArgumentException"),
        op("LABEL", "Lhandler"),
        op("LOCAL_WRITE", "local0", "<exception>"),
        op("METHOD_CALL", "recover(local0)"),
        op("JUMP", "Ljoin"),
        op("LABEL", "Ljoin"),
        op("RETURN")));

    TranslationResult result = new JavaScriptTranslator().translate(method);
    assertTrue(result.source().contains("catch (local0)"), result.source());
    assertFalse(result.source().contains("catch (java.lang.IllegalArgumentException"), result.source());
    assertFalse(result.source().contains("let local0 = <exception>"), result.source());
  }

  @Test
  void keepsAProtectedRegionOutsideALoopThatExitsOntoItsStart() {
    var method = new IrMethodModel("loopThenTry", "()V", List.of(
        op("LABEL", "Lhead"),
        op("LOCAL_READ", "local0"),
        op("CONDITIONAL_BRANCH", "local0 == 0", "Ltry"),
        op("METHOD_CALL", "body()"),
        op("JUMP", "Lhead"),
        op("TRY_BEGIN", "Ltry", "Lend", "Lhandler", "java.lang.Exception"),
        op("LABEL", "Ltry"),
        op("METHOD_CALL", "work()"),
        op("TRY_END", "Ltry", "Lend", "Lhandler", "java.lang.Exception"),
        op("LABEL", "Lend"),
        op("JUMP", "Ljoin"),
        op("CATCH_BEGIN", "Ltry", "Lend", "Lhandler", "java.lang.Exception"),
        op("LABEL", "Lhandler"),
        op("LOCAL_WRITE", "local1", "<exception>"),
        op("METHOD_CALL", "recover(local1)"),
        op("LABEL", "Ljoin"),
        op("RETURN")));

    TranslationResult result = new JavaScriptTranslator().translate(method);

    assertTrue(result.complete(), result.source());
    assertTrue(result.source().contains("try {"), result.source());
    assertTrue(result.source().contains("work();"), result.source());
    assertFalse(result.source().contains("unsupported(\"try-catch-region\""), result.source());
  }

  @Test
  void keepsALoopHeaderAtTheStartOfAProtectedRegion() {
    var method = new IrMethodModel("tryContainingLoop", "()V", List.of(
        op("TRY_BEGIN", "Lhead", "Lend", "Lhandler", "java.lang.Exception"),
        op("LABEL", "Lhead"),
        op("LOCAL_READ", "local0"),
        op("CONDITIONAL_BRANCH", "local0 == 0", "Lend"),
        op("METHOD_CALL", "body()"),
        op("JUMP", "Lhead"),
        op("TRY_END", "Lhead", "Lend", "Lhandler", "java.lang.Exception"),
        op("LABEL", "Lend"),
        op("JUMP", "Ljoin"),
        op("CATCH_BEGIN", "Lhead", "Lend", "Lhandler", "java.lang.Exception"),
        op("LABEL", "Lhandler"),
        op("LOCAL_WRITE", "local1", "<exception>"),
        op("METHOD_CALL", "recover(local1)"),
        op("LABEL", "Ljoin"),
        op("RETURN")));

    TranslationResult result = new JavaScriptTranslator().translate(method);

    assertTrue(result.complete(), result.source());
    assertTrue(result.source().contains("while (true)"), result.source());
    assertTrue(result.source().contains("break;"), result.source());
    assertFalse(result.source().contains("unsupported(\"JUMP\""), result.source());
  }

  @Test
  void wrapsALoopWhoseHeaderStartsInsideAProtectedRegion() {
    var method = new IrMethodModel("loopOverTries", "()V", List.of(
        op("TRY_BEGIN", "L0", "L1", "L2", "java.lang.Exception", "false"),
        op("LABEL", "L0"),
        op("CONDITIONAL_BRANCH", "local0 == 0", "L1"),
        op("METHOD_CALL", "innerWork()"),
        op("TRY_END", "L0", "L1", "L2", "java.lang.Exception", "false"),
        op("LABEL", "L1"),
        op("JUMP", "L3"),
        op("CATCH_BEGIN", "L0", "L1", "L2", "java.lang.Exception", "false"),
        op("LABEL", "L2"),
        op("LOCAL_WRITE", "local1", "<exception>"),
        op("TRY_BEGIN", "L3", "L4", "L5", "java.lang.Exception", "false"),
        op("LABEL", "L3"),
        op("METHOD_CALL", "pause()"),
        op("TRY_END", "L3", "L4", "L5", "java.lang.Exception", "false"),
        op("LABEL", "L4"),
        op("JUMP", "L0"),
        op("CATCH_BEGIN", "L3", "L4", "L5", "java.lang.Exception", "false"),
        op("LABEL", "L5"),
        op("LOCAL_WRITE", "local2", "<exception>"),
        op("METHOD_CALL", "recover(local2)"),
        op("JUMP", "L0")));

    TranslationResult result = new JavaScriptTranslator().translate(method);

    assertTrue(result.complete(), result.source());
    assertTrue(result.source().contains("while (true)"), result.source());
    assertTrue(result.source().contains("innerWork();"), result.source());
    assertTrue(result.source().contains("pause();"), result.source());
    assertTrue(result.source().contains("recover(local2);"), result.source());
    assertEquals(2, countOccurrences(result.source(), "try {"), result.source());
    // The first catch must stay empty; the second try is not part of it.
    assertFalse(result.source().contains("catch (local1) { if (!GoldClientRuntime.instanceOf(local1, \"java.lang.Exception\")) throw local1;\n    try"),
        result.source());
    assertFalse(result.source().contains("unsupported(\"JUMP\""), result.source());
  }

  @Test
  void wrapsASwitchLoopWhoseBackEdgeIsNotTheLastInstruction() {
    var method = new IrMethodModel("switchLoop", "()V", List.of(
        op("LABEL", "L0"),
        op("LOCAL_READ", "local0"),
        op("TABLE_SWITCH", "local0", "0", "1", "Lcase0", "Lcase1", "Lcase2"),
        op("LABEL", "Lcase1"),
        op("METHOD_CALL", "step()"),
        op("JUMP", "L0"),
        op("LABEL", "Lskip"),
        op("LABEL", "Lcase0"),
        op("RETURN"),
        op("LABEL", "Lcase2"),
        op("RETURN")));

    TranslationResult result = new JavaScriptTranslator().translate(method);

    assertTrue(result.complete(), result.source());
    assertTrue(result.source().contains("while (true)"), result.source());
    assertTrue(result.source().contains("step();"), result.source());
    assertTrue(result.source().contains("continue"), result.source());
    assertFalse(result.source().contains("unsupported(\"JUMP\""), result.source());
  }

  @Test
  void nestsTriesThatShareOneProtectedStartLabel() {
    // A nested try compiles both exception entries with the same start label,
    // and the analyzer emits both TRY_BEGIN markers before that label. The
    // inner marker can come first, so the renderer must still recognize the
    // wider outer region instead of dropping its catch clause.
    var method = new IrMethodModel("sharedStart", "()V", List.of(
        op("TRY_BEGIN", "L0", "L1", "L2", "java.lang.Exception", "false"),
        op("TRY_BEGIN", "L0", "L3", "L4", "java.lang.Exception", "false"),
        op("LABEL", "L0"),
        op("METHOD_CALL", "innerWork()"),
        op("TRY_END", "L0", "L1", "L2", "java.lang.Exception", "false"),
        op("LABEL", "L1"),
        op("JUMP", "L5"),
        op("CATCH_BEGIN", "L0", "L1", "L2", "java.lang.Exception", "false"),
        op("LABEL", "L2"),
        op("LOCAL_WRITE", "local1", "<exception>"),
        op("METHOD_CALL", "innerRecover(local1)"),
        op("LABEL", "L5"),
        op("METHOD_CALL", "outerWork()"),
        op("TRY_END", "L0", "L3", "L4", "java.lang.Exception", "false"),
        op("LABEL", "L3"),
        op("JUMP", "L6"),
        op("CATCH_BEGIN", "L0", "L3", "L4", "java.lang.Exception", "false"),
        op("LABEL", "L4"),
        op("LOCAL_WRITE", "local2", "<exception>"),
        op("METHOD_CALL", "outerRecover(local2)"),
        op("LABEL", "L6"),
        op("RETURN")));

    TranslationResult result = new JavaScriptTranslator().translate(method);

    assertTrue(result.complete(), result.source());
    assertTrue(result.source().contains("innerWork();"), result.source());
    assertTrue(result.source().contains("outerWork();"), result.source());
    assertTrue(result.source().contains("innerRecover(local1);"), result.source());
    assertTrue(result.source().contains("outerRecover(local2);"), result.source());
    assertEquals(2, countOccurrences(result.source(), "try {"), result.source());
    assertFalse(result.source().contains("unsupported(\"JUMP\""), result.source());
  }

  @Test
  void keepsACatchHandlerInsideTheFallthroughArmOfAnIfElse() {
    var method = new IrMethodModel("ifElseTry", "()V", List.of(
        op("LOCAL_READ", "local0"),
        op("CONDITIONAL_BRANCH", "local0 == 0", "Linvalid"),
        op("TRY_BEGIN", "Ltry", "Lend", "Lhandler", "java.lang.Exception"),
        op("LABEL", "Ltry"),
        op("METHOD_CALL", "risky()"),
        op("TRY_END", "Ltry", "Lend", "Lhandler", "java.lang.Exception"),
        op("LABEL", "Lend"),
        op("JUMP", "Ljoin"),
        op("CATCH_BEGIN", "Ltry", "Lend", "Lhandler", "java.lang.Exception"),
        op("LABEL", "Lhandler"),
        op("LOCAL_WRITE", "local1", "<exception>"),
        op("THROW", "local1"),
        op("LABEL", "Linvalid"),
        op("METHOD_CALL", "invalid()"),
        op("THROW", "invalid"),
        op("LABEL", "Ljoin"),
        op("RETURN")));

    TranslationResult result = new JavaScriptTranslator().translate(method);

    assertTrue(result.complete(), result.source());
    assertTrue(result.source().contains("try {"), result.source());
    assertTrue(result.source().contains("catch (local1)"), result.source());
    assertFalse(result.source().contains("unsupported(\"try-catch-region\""), result.source());
  }

  @Test
  void keepsSwitchArmsInsideACatchClauseUntilTheSharedJoin() {
    var method = new IrMethodModel("catchSwitch", "()V", List.of(
        op("TRY_BEGIN", "Ltry", "Lend", "Lhandler", "java.lang.Exception"),
        op("LABEL", "Ltry"),
        op("METHOD_CALL", "risky()"),
        op("TRY_END", "Ltry", "Lend", "Lhandler", "java.lang.Exception"),
        op("LABEL", "Lend"),
        op("JUMP", "Ljoin"),
        op("CATCH_BEGIN", "Ltry", "Lend", "Lhandler", "java.lang.Exception"),
        op("LABEL", "Lhandler"),
        op("LOCAL_WRITE", "local1", "<exception>"),
        op("TABLE_SWITCH", "local1", "0", "1", "Ldefault", "Lcase0", "Lcase1"),
        op("LABEL", "Lcase0"),
        op("CONSTANT", "\"a\""),
        op("LOCAL_WRITE", "local1", "\"a\""),
        op("JUMP", "Ljoin"),
        op("LABEL", "Lcase1"),
        op("CONSTANT", "\"b\""),
        op("LOCAL_WRITE", "local1", "\"b\""),
        op("JUMP", "Ljoin"),
        op("LABEL", "Ldefault"),
        op("CONSTANT", "\"c\""),
        op("LOCAL_WRITE", "local1", "\"c\""),
        op("LABEL", "Ljoin"),
        op("RETURN")));

    TranslationResult result = new JavaScriptTranslator().translate(method);

    assertTrue(result.complete(), result.source());
    assertTrue(result.source().contains("catch (local1)"), result.source());
    assertTrue(result.source().contains("switch (local1)"), result.source());
    assertTrue(result.source().contains("case 0:"), result.source());
    assertFalse(result.source().contains("unsupported(\"TABLE_SWITCH\""), result.source());
  }

  @Test
  void rendersAUserCatchFinallyWithOneCleanupCopy() {
    var method = new IrMethodModel("userCatchFinally", "()V", List.of(
        op("TRY_BEGIN", "Ltry", "Lnormal", "Lcatch", "java.lang.Exception", "false"),
        op("TRY_BEGIN", "Ltry", "Lnormal", "Lfinally", "java.lang.Throwable", "true"),
        op("LABEL", "Ltry"),
        op("METHOD_CALL", "work()"),
        op("TRY_END", "Ltry", "Lnormal", "Lcatch", "java.lang.Exception", "false"),
        op("TRY_END", "Ltry", "Lnormal", "Lfinally", "java.lang.Throwable", "true"),
        op("LABEL", "Lnormal"),
        op("METHOD_CALL", "cleanup()"),
        op("JUMP", "Ljoin"),
        op("CATCH_BEGIN", "Ltry", "Lnormal", "Lcatch", "java.lang.Exception", "false"),
        op("TRY_BEGIN", "Lcatch", "LcatchEnd", "Lfinally", "java.lang.Throwable", "true"),
        op("LABEL", "Lcatch"),
        op("LOCAL_WRITE", "local1", "<exception>"),
        op("METHOD_CALL", "recover(local1)"),
        op("TRY_END", "Lcatch", "LcatchEnd", "Lfinally", "java.lang.Throwable", "true"),
        op("LABEL", "LcatchEnd"),
        op("METHOD_CALL", "cleanup()"),
        op("JUMP", "Ljoin"),
        op("CATCH_BEGIN", "Ltry", "Lnormal", "Lfinally", "java.lang.Throwable", "true"),
        op("CATCH_BEGIN", "Lcatch", "LcatchEnd", "Lfinally", "java.lang.Throwable", "true"),
        op("LABEL", "Lfinally"),
        op("LOCAL_WRITE", "local2", "<exception>"),
        op("METHOD_CALL", "cleanup()"),
        op("LOCAL_READ", "local2"),
        op("THROW", "local2"),
        op("LABEL", "Ljoin"),
        op("RETURN")));

    TranslationResult result = new JavaScriptTranslator().translate(method);

    assertTrue(result.complete(), result.source());
    assertTrue(result.source().contains("catch (local1)"), result.source());
    assertTrue(result.source().contains("instanceOf(local1, \"java.lang.Exception\")"), result.source());
    assertTrue(result.source().contains("} finally {"), result.source());
    assertTrue(result.source().contains("recover(local1);"), result.source());
    assertEquals(1, result.source().split("cleanup\\(\\);", -1).length - 1, result.source());
  }

  @Test
  void mergesASplitStringSwitchIntoOneTryCatch() {
    var method = new IrMethodModel("splitSwitch", "(I)Ljava/lang/String;", List.of(
        op("TRY_BEGIN", "Ltry", "Lnormal", "Lcatch", "java.lang.Exception", "false"),
        op("LABEL", "Ltry"),
        op("TABLE_SWITCH", "local0", "0", "1", "Ldefault", "Lcase0", "Lcase1"),
        op("TRY_END", "Ltry", "Lnormal", "Lcatch", "java.lang.Exception", "false"),
        op("LABEL", "Lnormal"),
        op("CONSTANT", "\"first\""),
        op("RETURN", "\"first\""),
        op("TRY_BEGIN", "Lcase0", "Lcase0End", "Lcatch", "java.lang.Exception", "false"),
        op("LABEL", "Lcase0"),
        op("CONSTANT", "\"a\""),
        op("TRY_END", "Lcase0", "Lcase0End", "Lcatch", "java.lang.Exception", "false"),
        op("LABEL", "Lcase0End"),
        op("RETURN", "\"a\""),
        op("TRY_BEGIN", "Lcase1", "Lcase1End", "Lcatch", "java.lang.Exception", "false"),
        op("LABEL", "Lcase1"),
        op("CONSTANT", "\"b\""),
        op("TRY_END", "Lcase1", "Lcase1End", "Lcatch", "java.lang.Exception", "false"),
        op("LABEL", "Lcase1End"),
        op("RETURN", "\"b\""),
        op("TRY_BEGIN", "Ldefault", "Lcatch", "Lcatch", "java.lang.Exception", "false"),
        op("LABEL", "Ldefault"),
        op("THROW", "new java.lang.IllegalArgumentException()"),
        op("TRY_END", "Ldefault", "Lcatch", "Lcatch", "java.lang.Exception", "false"),
        op("CATCH_BEGIN", "Ltry", "Lnormal", "Lcatch", "java.lang.Exception", "false"),
        op("CATCH_BEGIN", "Lcase0", "Lcase0End", "Lcatch", "java.lang.Exception", "false"),
        op("CATCH_BEGIN", "Lcase1", "Lcase1End", "Lcatch", "java.lang.Exception", "false"),
        op("CATCH_BEGIN", "Ldefault", "Lcatch", "Lcatch", "java.lang.Exception", "false"),
        op("LABEL", "Lcatch"),
        op("LOCAL_WRITE", "local1", "<exception>"),
        op("CONSTANT", "\"fallback\""),
        op("RETURN", "\"fallback\"")));

    TranslationResult result = new JavaScriptTranslator().translate(method);

    assertTrue(result.complete(), result.source());
    assertTrue(result.source().contains("try {"), result.source());
    assertTrue(result.source().contains("switch (local0)"), result.source());
    assertTrue(result.source().contains("case 0:"), result.source());
    assertTrue(result.source().contains("case 1:"), result.source());
    assertTrue(result.source().contains("fallback"), result.source());
    assertFalse(result.source().contains("unsupported(\"TABLE_SWITCH\""), result.source());
  }

  @Test
  void keepsAThrowInsideACatchLoopInsideTheHandler() {
    var method = new IrMethodModel("catchLoopThrow", "()V", List.of(
        op("TRY_BEGIN", "Ltry", "Lnormal", "Lcatch", "java.lang.Exception", "false"),
        op("LABEL", "Ltry"),
        op("METHOD_CALL", "work()"),
        op("TRY_END", "Ltry", "Lnormal", "Lcatch", "java.lang.Exception", "false"),
        op("LABEL", "Lnormal"),
        op("JUMP", "Ljoin"),
        op("CATCH_BEGIN", "Ltry", "Lnormal", "Lcatch", "java.lang.Exception", "false"),
        op("LABEL", "Lcatch"),
        op("LOCAL_WRITE", "local0", "<exception>"),
        op("LABEL", "Lloop"),
        op("CONDITIONAL_BRANCH", "hasNext == 0", "Lend"),
        op("METHOD_CALL", "step()"),
        op("CONDITIONAL_BRANCH", "required == 0", "Lskip"),
        op("THROW", "failure"),
        op("LABEL", "Lskip"),
        op("JUMP", "Lloop"),
        op("LABEL", "Lend"),
        op("LABEL", "Ljoin"),
        op("RETURN")));

    TranslationResult result = new JavaScriptTranslator().translate(method);

    assertTrue(result.complete(), result.source());
    assertTrue(result.source().contains("while"), result.source());
    assertTrue(result.source().contains("throw failure;"), result.source());
    assertFalse(result.source().contains("unsupported(\"JUMP\""), result.source());
  }

  @Test
  void materializesATernaryIntoACallThatFeedsACondition() {
    var method = new IrMethodModel("ternaryCondition", "(Z)Z", List.of(
        op("CONDITIONAL_BRANCH", "flag != 0", "Lelse"),
        op("CONSTANT", "1"),
        op("JUMP", "Ljoin"),
        op("LABEL", "Lelse"),
        op("CONSTANT", "0"),
        op("LABEL", "Ljoin"),
        op("CALL_VALUE", "check(<unknown>, name)"),
        op("CONDITIONAL_BRANCH", "check(<unknown>, name) == 0", "Lfail"),
        op("CONSTANT", "1"),
        op("RETURN", "1"),
        op("LABEL", "Lfail"),
        op("CONSTANT", "0"),
        op("RETURN", "0")));

    TranslationResult result = new JavaScriptTranslator().translate(method);

    assertTrue(result.complete(), result.source());
    assertTrue(result.source().contains("check((flag != 0 ? 0 : 1), name)"),
        result.source());
    assertFalse(result.source().contains("<unknown>"), result.source());
  }

  @Test
  void preservesStringsWhenLoweringJavaSyntax() {
    String source = """
        void sample() {
          System.out.println("java.lang.IllegalStateException should stay text");
          throw new java.lang.IllegalStateException("real failure");
        }
        """;
    String lowered = new JavaScriptTranslator().lowerJavaSyntax(source);
    assertTrue(lowered.contains("console.log(\"java.lang.IllegalStateException should stay text\")"),
        lowered);
    assertTrue(lowered.contains(
        "throw GoldClientRuntime.javaException(\"java.lang.IllegalStateException\", \"real failure\")"),
        lowered);
  }

  @Test
  void lowersJavaNumericSuffixesPrimitiveCastsArraysAndCommonApis() {
    String source = """
        void sample() {
          long count = 12L;
          float scale = 1.5F;
          int index = (int) local0;
          int[] values = new int[local1];
          java.lang.String[] names = new java.lang.String[local2];
          boolean same = local2.equals(local3);
          System.arraycopy(values, 0, values, 1, 2);
        }
        """;
    String lowered = new JavaScriptTranslator().lowerJavaSyntax(source);
    assertTrue(lowered.contains("12;"), lowered);
    assertTrue(lowered.contains("1.5;"), lowered);
    assertTrue(lowered.contains("GoldClientRuntime.toInt32(local0)"), lowered);
    assertTrue(lowered.contains("GoldClientRuntime.newArray(") && lowered.contains("local1)"), lowered);
    assertTrue(lowered.contains("GoldClientRuntime.newArray(\"java.lang.String\", local2)"), lowered);
    assertTrue(lowered.contains("GoldClientRuntime.javaEquals(local2, local3)"), lowered);
    assertTrue(lowered.contains("GoldClientRuntime.arraycopy(values, 0, values, 1, 2)"), lowered);
    assertFalse(lowered.contains("12L"), lowered);
    assertFalse(lowered.contains("1.5F"), lowered);
  }

  @Test
  void keepsMultidimensionalArrayAllocationAsExecutableRuntimeCode() {
    var method = new IrMethodModel("matrix", "()V", List.of(
        op("LOCAL_WRITE", "local1", "GoldClientRuntime.newMultiArray(\"int\", [2, 3], 2)"),
        op("RETURN")));
    TranslationResult result = new JavaScriptTranslator().translate(method);

    assertTrue(result.complete(), result.source());
    assertTrue(result.source().contains(
        "GoldClientRuntime.newMultiArray(\"int\", [2, 3], 2)"), result.source());
  }

  @Test
  void routesModClassConstructionAndStaticCallsThroughRuntime() {
    String lowered = new JavaScriptTranslator().lowerJavaSyntax("""
        void sample() {
          Object value = new mezz.jei.ingredients.IngredientFilter();
          Object runtime = mezz.jei.Internal.getRuntime();
          Object math = java.lang.Math.max(2, 3);
        }
        """);
    assertTrue(lowered.contains("GoldClientRuntime.create(") && lowered.contains("mezz.jei.ingredients.IngredientFilter"), lowered);
    assertTrue(lowered.contains("GoldClientRuntime.invokeStaticClass(") && lowered.contains("mezz.jei.Internal") && lowered.contains("getRuntime"), lowered);
    assertTrue(lowered.contains("GoldClientRuntime.invokeJavaStatic(") && lowered.contains("java.lang.Math") && lowered.contains("max"), lowered);
  }

  @Test
  void removesReferenceCastsAndLowersUnknownAnalyzerValuesToValidJavaScript() {
    String lowered = new JavaScriptTranslator().lowerJavaSyntax("""
        function sample(local2) {
          let item = (net.minecraft.item.ItemStack)local2;
          let mode = (mezz.jei.api.recipe.IFocus$Mode) this.contents.get(this.current);
          let missing = <unknown>;
          return <unknown-stack>;
        }
        """);

    assertFalse(lowered.contains("(net.minecraft.item.ItemStack)"), lowered);
    assertFalse(lowered.contains("(mezz.jei.api.recipe.IFocus$Mode)"), lowered);
    assertFalse(lowered.contains("<unknown>"), lowered);
    assertFalse(lowered.contains("<unknown-stack>"), lowered);
    assertTrue(lowered.contains("let item = local2;"), lowered);
    assertTrue(lowered.contains("let missing = undefined;"), lowered);
    assertTrue(lowered.contains("return undefined;"), lowered);
  }

  @Test
  void zeroArgumentStaticCallsDoNotPassAnAccidentalUndefinedArgument() {
    String lowered = new JavaScriptTranslator().lowerJavaSyntax("""
        void sample() {
          Object a = java.util.Collections.emptyList();
          Object b = mezz.jei.Internal.getRuntime();
        }
        """);
    assertTrue(lowered.contains("GoldClientRuntime.invokeJavaStatic("), lowered);
    assertTrue(lowered.contains("GoldClientRuntime.invokeStaticClass("), lowered);
    assertFalse(lowered.contains(", )"), lowered);
  }

  @Test
  void treatsUppercaseStaticFieldReceiversAsFieldsNotClasses() {
    String lowered = new JavaScriptTranslator().lowerJavaSyntax("""
        void sample() {
          net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(local0);
        }
        """);
    assertTrue(lowered.contains(
        "GoldClientRuntime.getStaticField(\"net.minecraftforge.common.MinecraftForge\", \"EVENT_BUS\").register(local0)"),
        lowered);
    assertFalse(lowered.contains("invokeStaticClass(\"EVENT_BUS\""), lowered);
  }

  @Test
  void routesTranslatedStaticFieldReadsAndWritesThroughRuntime() {
    String lowered = new JavaScriptTranslator().lowerJavaSyntax("""
        void sample() {
          Object value = mezz.jei.Internal.runtime;
          mezz.jei.Internal.runtime = value;
        }
        """);
    assertTrue(lowered.contains("GoldClientRuntime.getStaticField("), lowered);
    assertTrue(lowered.contains("GoldClientRuntime.setStaticField("), lowered);
  }

  @Test
  void routesCommonJavaUtilityCallsThroughCompatibilityHelpers() {
    String lowered = new JavaScriptTranslator().lowerJavaSyntax("""
        void sample() {
          Object values = Arrays.asList(local0, local1);
          Object empty = Collections.emptyList();
          String label = String.format("%s", local0);
          boolean enabled = Boolean.parseBoolean(local1);
        }
        """);
    assertTrue(lowered.contains("GoldClientRuntime.invokeJavaStatic("), lowered);
    assertTrue(lowered.contains("java.util.Arrays"), lowered);
    assertTrue(lowered.contains("java.util.Collections"), lowered);
    assertTrue(lowered.contains("java.lang.String"), lowered);
    assertTrue(lowered.contains("java.lang.Boolean"), lowered);
  }

  @Test
  void lowersRawJvmPrimitiveArrayAllocationDescriptors() {
    String lowered = new JavaScriptTranslator().lowerJavaSyntax(
        "void sample() { var values = new [I[local9](); }");
    assertTrue(lowered.contains("GoldClientRuntime.newArray(\"int\", local9)"), lowered);
    assertFalse(lowered.contains("new [I["), lowered);
  }

  @Test
  void invalidNumericMethodReceiversBecomeExplicitIncompleteOperations() {
    var method = new IrMethodModel("badReceiver", "()V", List.of(
        op("METHOD_CALL", "0.set(value)"), op("RETURN")));
    TranslationResult result = new JavaScriptTranslator().translate(method);
    assertFalse(result.complete(), result.source());
    assertTrue(result.source().contains(
        "GoldClientRuntime.unsupported(\"invalid-call-receiver\", \"0.set(value)\")"),
        result.source());
  }

  @Test
  void staticFieldAssignmentsWithSpuriousNewPrefixUseRuntimeSetter() {
    String lowered = new JavaScriptTranslator().lowerJavaSyntax(
        "void sample() { new mezz.jei.collect.Table.wrapperMaps = value; }");
    assertTrue(lowered.contains(
        "GoldClientRuntime.setStaticField(\"mezz.jei.collect.Table\", \"wrapperMaps\", value);"),
        lowered);
    assertFalse(lowered.contains("getStaticField(\"mezz.jei.collect.Table\", \"wrapperMaps\") ="),
        lowered);
  }

  @Test
  void lowersRawJvmArrayAllocationsWithoutConstructorParentheses() {
    String lowered = new JavaScriptTranslator().lowerJavaSyntax("""
        void sample() {
          Object values = new [I[local0];
        }
        """);
    assertTrue(lowered.contains("GoldClientRuntime.newArray(\"int\", local0)"), lowered);
    assertFalse(lowered.contains("new [I"), lowered);
  }

  @Test
  void doesNotTurnStaticFieldComparisonsIntoAssignments() {
    String lowered = new JavaScriptTranslator().lowerJavaSyntax("""
        void sample() {
          if (squeek.appleskin.AppleSkin.hasAppleCore == 0) {
            return;
          }
          if (net.minecraft.client.Minecraft.field_142025_a != 0) {
            return;
          }
        }
        """);
    assertFalse(lowered.contains("setStaticField("), lowered);
    assertTrue(lowered.contains("getStaticField(\"squeek.appleskin.AppleSkin\", \"hasAppleCore\") == 0"),
        lowered);
    assertTrue(lowered.contains("getStaticField(\"net.minecraft.client.Minecraft\", \"field_142025_a\") != 0"),
        lowered);
  }

  @Test
  void lowersDirectGetterCallUsedAsStaticFieldAssignmentTarget() {
    String lowered = new JavaScriptTranslator().lowerJavaSyntax(
        "void sample() { squeek.appleskin.ModConfig.config = value; }");
    assertTrue(lowered.contains(
        "GoldClientRuntime.setStaticField(\"squeek.appleskin.ModConfig\", \"config\", value);"),
        lowered);
    assertFalse(lowered.contains("getStaticField(\"squeek.appleskin.ModConfig\", \"config\") ="),
        lowered);
  }

  @Test
  void lowersStaticFieldAssignmentWhenRightHandSideContainsLineBreaks() {
    String lowered = new JavaScriptTranslator().lowerJavaSyntax("""
        void sample() {
          new GoldClientRuntime.getStaticField("mezz.jei.collect.Table", "wrapperMaps") =
              GoldClientRuntime.createJava("java.util.HashMap", "constructor text");
        }
        """);
    assertTrue(lowered.contains(
        "GoldClientRuntime.setStaticField(\"mezz.jei.collect.Table\", \"wrapperMaps\","),
        lowered);
    assertFalse(lowered.contains("getStaticField(\"mezz.jei.collect.Table\", \"wrapperMaps\") ="),
        lowered);
  }
  @Test
  void keepsSemicolonsInsideArrayDescriptorStringsWhenRewritingFieldWrites() {
    String lowered = new JavaScriptTranslator().lowerJavaSyntax("""
        void sample() {
          new GoldClientRuntime.getStaticField("example.Mod", "values") =
              GoldClientRuntime.checkCast(value, "[Lexample.Mod$Entry;");
        }
        """);
    assertTrue(lowered.contains(
        "GoldClientRuntime.setStaticField(\"example.Mod\", \"values\", GoldClientRuntime.checkCast(value, \"[Lexample.Mod$Entry;\"));"),
        lowered);
  }

  @Test
  void lowersGetterCallUsedAsStaticFieldAssignmentTarget() {
    String lowered = new JavaScriptTranslator().lowerJavaSyntax(
        "void sample() { new GoldClientRuntime.getStaticField(\"mezz.jei.collect.Table\", \"wrapperMaps\") = value; }");
    assertTrue(lowered.contains(
        "GoldClientRuntime.setStaticField(\"mezz.jei.collect.Table\", \"wrapperMaps\", value);"),
        lowered);
    assertFalse(lowered.contains("getStaticField(\"mezz.jei.collect.Table\", \"wrapperMaps\") ="),
        lowered);
  }

  @Test
  void lowersJavaConstructorsBeforeStaticCallRewriting() {
    String lowered = new JavaScriptTranslator().lowerJavaSyntax("""
        void sample() {
          Object maps = new java.util.HashMap();
          Object nested = new java.util.HashMap(new java.util.IdentityHashMap());
        }
        """);
    assertTrue(lowered.contains(
        "GoldClientRuntime.createJava(\"java.util.HashMap\""), lowered);
    assertTrue(lowered.contains(
        "GoldClientRuntime.createJava(\"java.util.IdentityHashMap\""), lowered);
    assertFalse(lowered.contains("GoldClientRuntime.invokeJavaStatic(\"java.util.HashMap\""), lowered);
    assertFalse(lowered.contains("new java.util.HashMap"), lowered);
  }

  @Test
  void rewritesWholeNestedConstructorCallsWithoutCallingCreatedObjects() {
    String lowered = new JavaScriptTranslator().lowerJavaSyntax("""
        void sample() {
          Object maps = new java.util.HashMap(
              new java.util.IdentityHashMap(),
              () -> new java.util.HashSet());
        }
        """);

    String compact = lowered.replaceAll("\\s+", " ");
    assertTrue(compact.contains(
        "GoldClientRuntime.createJava(\"java.util.HashMap\", GoldClientRuntime.createJava(\"java.util.IdentityHashMap\"), () -> GoldClientRuntime.createJava(\"java.util.HashSet\"))"),
        lowered);
    assertFalse(lowered.contains("createJava(\"java.util.HashMap\", )("), lowered);
    assertFalse(lowered.contains("createJava(\"java.util.IdentityHashMap\", )"), lowered);
  }

  @Test
  void repairsDuplicatedConstructorReceiverFromBytecodeStackReconstruction() {
    String lowered = new JavaScriptTranslator().lowerJavaSyntax("""
        void sample() {
          Object maps = new java.util.HashMap()(
              new java.util.IdentityHashMap(),
              () -> new java.util.HashSet());
        }
        """);

    String compact = lowered.replaceAll("\\s+", " ");
    assertTrue(compact.contains(
        "GoldClientRuntime.createJava(\"java.util.HashMap\", GoldClientRuntime.createJava(\"java.util.IdentityHashMap\"), () -> GoldClientRuntime.createJava(\"java.util.HashSet\"))"),
        lowered);
    assertFalse(lowered.contains("createJava(\"java.util.HashMap\")(\n"), lowered);
  }

  @Test
  void lowersRawStaticFieldAssignmentAfterFieldReadRewriting() {
    String lowered = new JavaScriptTranslator().lowerJavaSyntax("""
        void sample() {
          new mezz.jei.collect.Table.wrapperMaps =
              new java.util.HashMap(local0, () -> new java.util.IdentityHashMap());
        }
        """);
    assertTrue(lowered.contains(
        "GoldClientRuntime.setStaticField(\"mezz.jei.collect.Table\", \"wrapperMaps\","), lowered);
    assertFalse(lowered.contains("getStaticField(\"mezz.jei.collect.Table\", \"wrapperMaps\") ="),
        lowered);
  }

  @Test
  void emitsSyntaxAcceptedByNodeWhenAvailable() throws Exception {
    var method = new IrMethodModel("isReady", "()Z", List.of(
        op("CONSTANT", "true"), op("RETURN", "true")));
    TranslationResult result = new JavaScriptTranslator().translate(method);
    String exportName = java.util.regex.Pattern
        .compile("export function (isReady__\\w+)\\(")
        .matcher(result.source()).results().findFirst().orElseThrow().group(1);
    Path module = tempDir.resolve("generated.mjs");
    Files.writeString(module, result.source() + "\nconsole.log(" + exportName + "());\n",
        StandardCharsets.UTF_8);

    Process process;
    try {
      process = new ProcessBuilder("node", "--check", module.toString())
          .redirectErrorStream(true).start();
    } catch (java.io.IOException missingNode) {
      org.junit.jupiter.api.Assumptions.assumeTrue(false, "Node.js is not installed");
      return;
    }
    assertTrue(process.waitFor(10, TimeUnit.SECONDS), "node --check timed out");
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertTrue(process.exitValue() == 0, output + "\n" + result.source());

    Process execution = new ProcessBuilder("node", module.toString())
        .redirectErrorStream(true).start();
    assertTrue(execution.waitFor(10, TimeUnit.SECONDS), "generated JS execution timed out");
    String executionOutput = new String(
        execution.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
    assertTrue(execution.exitValue() == 0, executionOutput);
    assertTrue(executionOutput.equals("true"), executionOutput);
  }

  @Test
  void lowersConstructorsAcrossQuotedArgumentsAndRepairsDelegationSyntax() {
    String lowered = new JavaScriptTranslator().lowerJavaSyntax("""
        void sample() {
          Object table = new mezz.jei.collect.Table(
              GoldClientRuntime.createJava("java.util.HashMap"),
              () -> GoldClientRuntime.createJava("java.util.IdentityHashMap"));
          this();
          Object empty = mezz.jei.Internal.getRuntime();
        }
        """);

    String compact = lowered.replaceAll("\\s+", " ");
    assertTrue(compact.contains(
        "GoldClientRuntime.create(\"mezz.jei.collect.Table\", GoldClientRuntime.createJava(\"java.util.HashMap\"), () -> GoldClientRuntime.createJava(\"java.util.IdentityHashMap\"))"),
        lowered);
    assertFalse(lowered.contains("new mezz.jei.collect.Table"), lowered);
    assertFalse(lowered.contains("this();"), lowered);
    assertTrue(lowered.contains(
        "GoldClientRuntime.unsupported(\"constructor-delegation\", \"this()\")"),
        lowered);
    assertFalse(lowered.contains(", )"), lowered);
  }

  private static IrOperationModel op(String kind, String... operands) {
    return new IrOperationModel(kind, List.of(operands), 0);
  }

  @Test
  void routesMinecraftAndForgeStaticCallsThroughPlatformBindings() {
    String lowered = new JavaScriptTranslator().lowerJavaSyntax("""
        void sample() {
          Object client = net.minecraft.client.Minecraft.getMinecraft();
          Object registry = net.minecraftforge.fml.common.registry.GameRegistry.findRegistry();
        }
        """);
    assertTrue(lowered.contains("GoldClientRuntime.invokePlatformStatic("), lowered);
    assertTrue(lowered.contains("net.minecraft.client.Minecraft") && lowered.contains("getMinecraft"), lowered);
    assertTrue(lowered.contains("net.minecraftforge.fml.common.registry.GameRegistry"), lowered);
    assertFalse(lowered.contains(", )"), lowered);
    assertFalse(lowered.contains("invokeStaticClass(\"net.minecraft.client.Minecraft\""), lowered);
  }

  @Test
  void preservesTypedCatchSemanticsWithRuntimeTypeGuard() {
    String lowered = new JavaScriptTranslator().lowerJavaSyntax("""
        try {
          work();
        } catch (java.io.IOException missingNode) {
          recover(missingNode);
        }
        """);

    assertTrue(lowered.contains("catch (missingNode)"), lowered);
    assertTrue(lowered.contains(
        "GoldClientRuntime.instanceOf(missingNode, \"java.io.IOException\")"), lowered);
    assertTrue(lowered.contains("throw missingNode;"), lowered);
    assertTrue(lowered.contains("recover(missingNode);"), lowered);
    assertFalse(lowered.contains("catch (java.io.IOException"), lowered);
  }

  @Test
  void explicitAdjacentCatchClausesMergeWithoutBeingLabelledApproximate() {
    String lowered = new JavaScriptTranslator().lowerJavaSyntax("""
        try {
          work();
        } catch (java.io.IOException io) {
          recover(io);
        } catch (java.lang.RuntimeException runtime) {
          recover(runtime);
        } catch (java.lang.Throwable fatal) {
          rethrow(fatal);
        }
        """);

    assertTrue(lowered.contains("GoldClientRuntime.instanceOf(__goldCaught"), lowered);
    assertTrue(lowered.contains("else if (GoldClientRuntime.instanceOf(__goldCaught"), lowered);
    assertTrue(lowered.contains("else { throw __goldCaught"), lowered);
    assertTrue(lowered.contains("JVM catch clauses merged into ordered runtime type dispatch"),
        lowered);
    assertFalse(lowered.contains("Gold Client approximation: merged multiple catch clauses"),
        lowered);
  }

  @Test
  void untypedSyntheticCatchMergeStaysLabelledApproximate() {
    String lowered = new JavaScriptTranslator().lowerJavaSyntax("""
        try {
          work();
        } catch (first) {
          recover(first);
        } catch (java.lang.RuntimeException runtime) {
          recover(runtime);
        }
        """);

    assertTrue(lowered.contains("Gold Client approximation: merged multiple catch clauses"),
        lowered);
  }

  @Test
  void trailingSyntheticCatchHandlerMergesWithoutApproximation() {
    String lowered = new JavaScriptTranslator().lowerJavaSyntax("""
        try {
          work();
        } catch (mezz.jei.recipes.BrokenCraftingRecipeException broken) {
          recover(broken);
        } catch (caughtException) {
          if (!(guard(caughtException))) throw caughtException;
          recover(caughtException);
        }
        """);

    assertTrue(lowered.contains("JVM catch clauses merged into ordered runtime type dispatch"),
        lowered);
    assertTrue(lowered.contains("throw __goldCaught"), lowered);
    assertFalse(lowered.contains("Gold Client approximation: merged multiple catch clauses"),
        lowered);
  }

  private static int countOccurrences(String text, String needle) {
    int count = 0;
    int index = 0;
    while ((index = text.indexOf(needle, index)) >= 0) {
      count++;
      index += needle.length();
    }
    return count;
  }

}