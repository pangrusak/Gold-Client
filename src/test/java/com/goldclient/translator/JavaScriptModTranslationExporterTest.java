package com.goldclient.translator;

import com.goldclient.model.ClassModel;
import com.goldclient.model.ControlFlowModel;
import com.goldclient.model.IrMethodModel;
import com.goldclient.model.IrOperationModel;
import com.goldclient.model.FieldModel;
import com.goldclient.model.MethodModel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.Opcodes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaScriptModTranslationExporterTest {
  @TempDir
  Path tempDir;

  @Test
  void exportsJavaScriptModulesRuntimeAndIndependentReport() throws Exception {
    ClassModel clazz = new ClassModel("example/Feature", "java/lang/Object", List.of(),
        Opcodes.ACC_PUBLIC, null, List.of(),
        List.of(new FieldModel("VERSION", "I", null,
            Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, List.of(), 7)),
        List.of(
            method("isReady", "()Z", Opcodes.ACC_PUBLIC, List.of(
                op("CONSTANT", "true"), op("RETURN", "true"))),
            method("unknown", "()V", Opcodes.ACC_PUBLIC, List.of(op("UNKNOWN", "opcode"))),
            method("nativeCall", "()V", Opcodes.ACC_PUBLIC | Opcodes.ACC_NATIVE, List.of())),
        List.of());

    Path output = tempDir.resolve("js");
    var report = new JavaScriptModTranslationExporter().export(List.of(clazz), output);
    Path module = output.resolve("example/Feature.translated.js");
    assertTrue(Files.isRegularFile(module));
    assertTrue(Files.readString(module).contains("export function isReady__"));
    assertTrue(Files.isRegularFile(output.resolve("gold-client-runtime.js")));
    assertTrue(Files.isRegularFile(output.resolve("javascript-report.json")));
    assertTrue(Files.isRegularFile(output.resolve("javascript-report.txt")));
    assertTrue(Files.isRegularFile(output.resolve("mod-index.js")));
    assertTrue(Files.isRegularFile(output.resolve("mod-manifest.json")));
    assertTrue(Files.readString(output.resolve("mod-index.js")).contains("export function getClass"));
    assertTrue(Files.readString(output.resolve("mod-manifest.json")).contains("example.Feature"));
    String moduleSource = Files.readString(module);
    assertTrue(moduleSource.contains("export class Translated_example_Feature"), moduleSource);
    assertTrue(moduleSource.contains("GoldClientRuntime.invoke(this, __goldMethods"), moduleSource);
    assertTrue(moduleSource.contains("Translated_example_Feature.VERSION = 7;"), moduleSource);
    assertTrue(Files.readString(output.resolve("mod-manifest.json")).contains("constantValue"));
    assertTrue(Files.readString(output.resolve("gold-client-runtime.js")).contains("registerClass(name, constructor, staticInitializer"));
    assertEquals(1, report.classCount());
    assertEquals(3, report.methodCount());
    assertEquals(1, report.completeCount());
    assertEquals(1, report.incompleteCount());
    assertEquals(0, report.skippedCount());
    assertEquals(1, report.stubbedCount());
    assertTrue(Files.readString(module).contains("untranslated-method"));
    assertTrue(Files.readString(output.resolve("javascript-report.txt"))
        .contains("not the same as a working mod"));
  }

  @Test
  void preservesModResourcesAndSkipsClassBytesAndSignatureFiles() throws Exception {
    Path jar = tempDir.resolve("input.jar");
    try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
      output.putNextEntry(new JarEntry("assets/example/lang/en_us.json"));
      output.write("{\"hello\":\"world\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
      output.closeEntry();
      output.putNextEntry(new JarEntry("example/Feature.class"));
      output.write(new byte[] {1, 2, 3});
      output.closeEntry();
      output.putNextEntry(new JarEntry("META-INF/EXAMPLE.SF"));
      output.write(new byte[] {4, 5});
      output.closeEntry();
    }

    Path outputDir = tempDir.resolve("with-resources");
    var report = new JavaScriptModTranslationExporter().export(jar, List.of(), outputDir);
    assertEquals(1, report.resourceCount());
    assertTrue(Files.isRegularFile(outputDir.resolve("resources/assets/example/lang/en_us.json")));
    assertTrue(Files.readString(outputDir.resolve("resources/assets/example/lang/en_us.json"))
        .contains("world"));
    assertTrue(Files.readString(outputDir.resolve("resource-index.json")).contains("en_us.json"));
    assertFalse(Files.exists(outputDir.resolve("resources/example/Feature.class")));
    assertFalse(Files.exists(outputDir.resolve("resources/META-INF/EXAMPLE.SF")));
  }

  private static MethodModel method(
      String name, String descriptor, int access, List<IrOperationModel> operations) {
    return new MethodModel(name, descriptor, null, access, List.of(), List.of(), List.of(),
        List.of(), List.of(), 2, 2, new ControlFlowModel(0, List.of()),
        new IrMethodModel(name, descriptor, operations));
  }

  private static IrOperationModel op(String kind, String... operands) {
    return new IrOperationModel(kind, List.of(operands), 0);
  }
}