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

class JavaScriptModTranslationExporterTest {
  @TempDir
  Path tempDir;

  @Test
  void exportsJavaScriptModulesRuntimeAndIndependentReport() throws Exception {
    ClassModel clazz = new ClassModel("example/Feature", "java/lang/Object", List.of(),
        Opcodes.ACC_PUBLIC, null, List.of(), List.of(), List.of(
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
    assertEquals(1, report.classCount());
    assertEquals(3, report.methodCount());
    assertEquals(1, report.completeCount());
    assertEquals(1, report.incompleteCount());
    assertEquals(1, report.skippedCount());
    assertTrue(Files.readString(output.resolve("javascript-report.txt"))
        .contains("not the same as a working mod"));
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