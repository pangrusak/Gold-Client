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
    assertTrue(lowered.contains("throw new Error(\"real failure\")"), lowered);
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

    assertTrue(lowered.contains(
        "GoldClientRuntime.createJava(\"java.util.HashMap\", GoldClientRuntime.createJava(\"java.util.IdentityHashMap\"), () -> GoldClientRuntime.createJava(\"java.util.HashSet\"))"),
        lowered);
    assertFalse(lowered.contains("createJava(\"java.util.HashMap\", )("), lowered);
    assertFalse(lowered.contains("createJava(\"java.util.IdentityHashMap\", )"), lowered);
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

}