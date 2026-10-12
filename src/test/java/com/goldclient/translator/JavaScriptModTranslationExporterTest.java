package com.goldclient.translator;

import com.goldclient.model.ClassModel;
import com.goldclient.model.ControlFlowModel;
import com.goldclient.model.IrMethodModel;
import com.goldclient.model.IrOperationModel;
import com.goldclient.model.FieldModel;
import com.goldclient.model.MethodModel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
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

    Path runner = output.resolve("run-generated-class.mjs");
    Files.writeString(runner, """
        import { Translated_example_Feature } from "./example/Feature.translated.js";
        const instance = new Translated_example_Feature();
        console.log(instance.isReady());
        """, StandardCharsets.UTF_8);
    Process node;
    try {
      node = new ProcessBuilder("node", runner.toString()).redirectErrorStream(true).start();
    } catch (java.io.IOException missingNode) {
      org.junit.jupiter.api.Assumptions.assumeTrue(false, "Node.js is not installed");
      return;
    }
    assertTrue(node.waitFor(10, TimeUnit.SECONDS), "generated class execution timed out");
    String nodeOutput = new String(node.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
    assertEquals(0, node.exitValue(), nodeOutput);
    assertEquals("true", nodeOutput);
  }

  @Test
  void emitsGenericForgeLifecycleDispatcherFromAnnotations() throws Exception {
    var modAnnotation = new com.goldclient.model.AnnotationData(
        "net.minecraftforge.fml.common.Mod", true, java.util.Map.of("modid", "samplemod"));
    ClassModel mod = new ClassModel("sample/ExampleMod", "java/lang/Object", List.of(),
        Opcodes.ACC_PUBLIC, null, List.of("net.minecraftforge.fml.common.Mod"),
        List.of(new FieldModel("instance", "Lsample/ExampleMod;", null, Opcodes.ACC_PUBLIC,
            List.of("net.minecraftforge.fml.common.Mod$Instance"))),
        List.of(
            annotatedMethod("preInit",
                "(Lnet/minecraftforge/fml/common/event/FMLPreInitializationEvent;)V",
                "net.minecraftforge.fml.common.Mod$EventHandler"),
            method("ordinary", "()V", Opcodes.ACC_PUBLIC, List.of(op("RETURN")))),
        List.of(), List.of(modAnnotation));

    Path output = tempDir.resolve("forge-lifecycle");
    var report = new ForgeLifecycleExporter().export(List.of(mod), output);
    String javascript = Files.readString(output.resolve("forge-lifecycle.js"));
    String plan = Files.readString(output.resolve("forge-lifecycle-plan.json"));
    assertEquals("READY_FOR_REAL_FORGE_HOST", report.status());
    assertEquals(1, report.discoveredModClasses());
    assertEquals(1, report.lifecycleHandlers());
    assertEquals(1, report.injectedFields());
    assertTrue(javascript.contains("export async function startForgeMods"));
    assertTrue(javascript.contains("host.createEvent(mod, phase)"));
    assertTrue(javascript.contains("GoldClientRuntime.resolveClass(mod.className)"));
    assertTrue(plan.contains("samplemod"));
    assertTrue(plan.contains("FMLPreInitializationEvent"));
  }

  private static MethodModel annotatedMethod(String name, String descriptor, String annotation) {
    return new MethodModel(name, descriptor, null, Opcodes.ACC_PUBLIC, List.of(),
        List.of(annotation), List.of(), List.of(), List.of(), 1, 2,
        new com.goldclient.model.ControlFlowModel(0, List.of()),
        new IrMethodModel(name, descriptor, List.of(op("RETURN"))));
  }

  @Test
  void exportsStrictForgeHostRuntimeAndContract() throws Exception {
    Path output = tempDir.resolve("strict-host");
    new ForgeHostRuntimeExporter().export(output);
    String runtime = Files.readString(output.resolve("forge-host-runtime.js"));
    String contract = Files.readString(output.resolve("forge-host-contract.json"));
    assertTrue(runtime.contains("export function createForgeHost(bindings)"));
    assertTrue(runtime.contains("export class ForgeEventBus"));
    assertTrue(runtime.contains("export class ForgeModState"));
    assertTrue(runtime.contains("ForgeHostBindingError"));
    assertTrue(runtime.contains("never fabricates a working game object"));
    assertTrue(contract.contains("strictMissingBindingErrors"));
    assertTrue(contract.contains("registries"));
    assertTrue(contract.contains("mixins, coremods"));
  }

  @Test
  void generatedForgeHostRuntimeExecutesStrictBindingAndEventBusContracts() throws Exception {
    Path output = tempDir.resolve("strict-host-node");
    new ForgeHostRuntimeExporter().export(output);
    Files.writeString(output.resolve("package.json"), "{\n  \"type\": \"module\"\n}\n");
    Path runner = output.resolve("host-contract-test.mjs");
    Files.writeString(runner, """
        import { ForgeEventBus, createForgeHost, assertForgeHostBindings } from "./forge-host-runtime.js";
        const bus = new ForgeEventBus();
        const seen = [];
        bus.subscribe("tick", () => seen.push("low"), { priority: 1, owner: "a" });
        bus.subscribe("tick", () => seen.push("high"), { priority: 10, owner: "b" });
        const result = bus.post("tick", {});
        if (seen.join(",") !== "high,low" || result.listenerCount !== 2) throw new Error("event order");
        bus.clearOwner("a");
        if (bus.size !== 1) throw new Error("owner cleanup");
        let rejected = false;
        try { createForgeHost({}); } catch (error) { rejected = error.name === "ForgeHostBindingError"; }
        if (!rejected) throw new Error("missing bindings were silently accepted");
        const bindings = {
          events: { createLifecycleEvent: async () => ({}) },
          minecraft: {}, forge: { stopMod: async () => {} }, render: {}, input: {},
          resources: {}, network: {}, config: {},
          registries: { register: (_kind, _id, value) => value, lookup: () => null },
          logger: { debug: () => {}, info: () => {}, warn: () => {}, error: () => {} },
          side: { current: () => "CLIENT" }
        };
        const report = assertForgeHostBindings(bindings);
        if (!report.valid || report.serviceCount !== 11) throw new Error("binding validation");
        const host = createForgeHost(bindings);
        if (host.diagnostics().eventListenerCount !== 0) throw new Error("diagnostics");
        console.log("forge-host-ok");
        """);
    Process node;
    try {
      node = new ProcessBuilder("node", runner.toString()).redirectErrorStream(true).start();
    } catch (java.io.IOException missingNode) {
      org.junit.jupiter.api.Assumptions.assumeTrue(false, "Node.js is not installed");
      return;
    }
    assertTrue(node.waitFor(10, TimeUnit.SECONDS), "generated Forge host runtime timed out");
    String outputText = new String(node.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
    assertEquals(0, node.exitValue(), outputText);
    assertEquals("forge-host-ok", outputText);
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