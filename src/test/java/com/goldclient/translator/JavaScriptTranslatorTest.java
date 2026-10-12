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
  void emitsSyntaxAcceptedByNodeWhenAvailable() throws Exception {
    var method = new IrMethodModel("printIt", "()V", List.of(
        op("METHOD_CALL", "System.out.println(\"hello\")"),
        op("RETURN")));
    TranslationResult result = new JavaScriptTranslator().translate(method);
    Path module = tempDir.resolve("generated.mjs");
    Files.writeString(module, result.source(), StandardCharsets.UTF_8);

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
    assertTrue(process.exitValue() == 0, output + "\\n" + result.source());
  }

  private static IrOperationModel op(String kind, String... operands) {
    return new IrOperationModel(kind, List.of(operands), 0);
  }
}