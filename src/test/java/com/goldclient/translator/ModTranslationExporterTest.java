package com.goldclient.translator;

import com.goldclient.model.ClassModel;
import com.goldclient.model.ControlFlowModel;
import com.goldclient.model.IrMethodModel;
import com.goldclient.model.IrOperationModel;
import com.goldclient.model.MethodModel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.Opcodes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModTranslationExporterTest {
  @TempDir
  Path tempDir;

  @Test
  void exportsEveryClassAndWritesMachineReadableAndHumanReadableReports() throws Exception {
    MethodModel supported = method(
        "isReady", "()Z", Opcodes.ACC_PUBLIC,
        List.of(
            new IrOperationModel("CONSTANT", List.of("true"), 0),
            new IrOperationModel("RETURN", List.of("true"), 1)));
    MethodModel unsupported = method(
        "notReady", "()V", Opcodes.ACC_PUBLIC,
        List.of(new IrOperationModel("UNSUPPORTED", List.of("unknown-op"), 0)));
    MethodModel abstractMethod = method(
        "interfaceMethod", "()V", Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, List.of());
    ClassModel first = clazz("example/First", List.of(supported, unsupported, abstractMethod));
    ClassModel second = clazz("example/Second", List.of(
        method("noIr", "()V", Opcodes.ACC_PUBLIC, List.of())));

    Path output = tempDir.resolve("export");
    ModTranslationExporter.TranslationReport report =
        new ModTranslationExporter().export(List.of(second, first), output);

    assertEquals(2, report.classCount());
    assertEquals(4, report.methodCount());
    assertEquals(1, report.completeCount());
    assertEquals(1, report.incompleteCount());
    assertEquals(2, report.skippedCount());
    assertEquals(2, report.fileCount());

    Path firstOutput = output.resolve("example/First.translated.txt");
    Path secondOutput = output.resolve("example/Second.translated.txt");
    assertTrue(Files.isRegularFile(firstOutput));
    assertTrue(Files.isRegularFile(secondOutput));
    assertTrue(Files.readString(firstOutput).contains("return true;"));
    assertTrue(Files.readString(firstOutput).contains("TODO: UNSUPPORTED"));
    assertTrue(Files.readString(firstOutput).contains("SKIPPED (abstract/native)"));
    assertTrue(Files.isRegularFile(output.resolve("translation-report.json")));
    assertTrue(Files.isRegularFile(output.resolve("translation-report.txt")));

    String json = Files.readString(output.resolve("translation-report.json"));
    assertTrue(json.contains("\"completeCount\": 1"));
    assertTrue(json.contains("\"incompleteCount\": 1"));
    assertTrue(json.contains("\"skippedCount\": 2"));
    assertTrue(Files.readString(output.resolve("translation-report.txt"))
        .contains("does not prove executable JavaScript"));
  }

  @Test
  void rejectsNullInputsClearly() {
    ModTranslationExporter exporter = new ModTranslationExporter();
    try {
      exporter.export(null, tempDir.resolve("null-classes"));
    } catch (Exception exception) {
      assertTrue(exception instanceof IllegalArgumentException);
      assertTrue(exception.getMessage().contains("Classes"));
      return;
    }
    throw new AssertionError("Expected null classes to be rejected");
  }

  private static ClassModel clazz(String name, List<MethodModel> methods) {
    return new ClassModel(
        name, "java/lang/Object", List.of(), Opcodes.ACC_PUBLIC,
        null, List.of(), List.of(), methods, List.of());
  }

  private static MethodModel method(
      String name, String descriptor, int access, List<IrOperationModel> operations) {
    return new MethodModel(
        name, descriptor, null, access,
        List.of(), List.of(), List.of(), List.of(), List.of(),
        2, 2, new ControlFlowModel(0, List.of()),
        new IrMethodModel(name, descriptor, operations));
  }
}
