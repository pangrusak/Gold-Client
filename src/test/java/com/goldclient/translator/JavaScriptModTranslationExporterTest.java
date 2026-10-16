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
    assertTrue(Files.isRegularFile(output.resolve("incomplete-diagnostics.txt")));
    assertTrue(Files.readString(output.resolve("incomplete-diagnostics.txt"))
        .contains("example.Feature#unknown()V"));
    assertTrue(Files.readString(output.resolve("incomplete-diagnostics.txt"))
        .contains("diagnostics\nIncomplete methods:"));
    assertTrue(Files.isRegularFile(output.resolve("incomplete-method-details.json")));
    String incompleteDetails = Files.readString(output.resolve("incomplete-method-details.json"));
    assertTrue(incompleteDetails.contains("example.Feature"));
    assertTrue(incompleteDetails.contains("UNKNOWN"));
    assertTrue(incompleteDetails.contains("opcode"));
    assertTrue(incompleteDetails.contains("irOperationCount"));
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
    var unknownStatus = report.methods().stream()
        .filter(item -> item.methodName().equals("unknown"))
        .findFirst().orElseThrow();
    assertTrue(unknownStatus.reason().contains("[UNSUPPORTED_IR] Operation cannot be lowered: UNKNOWN"),
        unknownStatus.reason());
    assertFalse(unknownStatus.reason().contains("semantics or host API compatibility remain unverified"));
    assertEquals(0, report.skippedCount());
    assertEquals(0, report.stubbedCount());
    assertEquals(1, report.adapterRequiredCount());
    assertTrue(Files.readString(module).contains("adapter-required"));
    assertTrue(Files.readString(output.resolve("javascript-report.txt"))
        .contains("not the same as a working mod"));

    Path runner = output.resolve("run-generated-class.mjs");
    Files.writeString(runner, """
        import { Translated_example_Feature } from "./example/Feature.translated.js";
        globalThis.goldClientDeclarationAdapters = {
          "example.Feature#nativeCall()V: native method": () => "native-adapter-ok"
        };
        const instance = new Translated_example_Feature();
        console.log(instance.isReady());
        console.log(instance.nativeCall());
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
    assertEquals("true\nnative-adapter-ok", nodeOutput);
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
  void javaFloorDivisionAndModuloMatchJavaForNegativeOperands() throws Exception {
    Path output = tempDir.resolve("java-math-runtime");
    new JavaScriptModTranslationExporter().export(List.of(), output);
    Path runner = output.resolve("math-runtime-test.mjs");
    Files.writeString(runner, """
        import { GoldClientRuntime as runtime } from "./gold-client-runtime.js";
        const cases = [
          [runtime.intDiv(-7, 3), -2],
          [runtime.intMod(-7, 3), -1],
          [runtime.intDiv(7, -3), -2],
          [runtime.intMod(7, -3), 1],
          [runtime.floorDiv(-7, 3), -3],
          [runtime.floorMod(-7, 3), 2],
          [runtime.floorDiv(7, -3), -3],
          [runtime.floorMod(7, -3), -2],
          [runtime.intDiv(-2147483648, -1), -2147483648],
          [runtime.intMod(-2147483648, -1), 0]
        ];
        for (const [actual, expected] of cases) {
          if (actual !== expected) throw new Error(`expected ${expected}, got ${actual}`);
        }
        let dividedByZero = false;
        try { runtime.intDiv(1, 0); } catch (error) {
          dividedByZero = error.message.includes("ArithmeticException");
        }
        if (!dividedByZero) throw new Error("division by zero was not rejected");
        console.log("java-math-ok");
        """, StandardCharsets.UTF_8);
    Process node;
    try {
      node = new ProcessBuilder("node", runner.toString()).redirectErrorStream(true).start();
    } catch (java.io.IOException missingNode) {
      org.junit.jupiter.api.Assumptions.assumeTrue(false, "Node.js is not installed");
      return;
    }
    assertTrue(node.waitFor(10, TimeUnit.SECONDS), "Java math runtime test timed out");
    String outputText = new String(node.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
    assertEquals(0, node.exitValue(), outputText);
    assertEquals("java-math-ok", outputText);
  }

  @Test
  void javaRandomMatchesTheJdkSeededSequence() throws Exception {
    Path output = tempDir.resolve("java-random-runtime");
    new JavaScriptModTranslationExporter().export(List.of(), output);
    Path runner = output.resolve("java-random-test.mjs");
    Files.writeString(runner, """
        import { GoldClientRuntime as r } from "./gold-client-runtime.js";
        const first = r.createJava("java.util.Random", 0);
        if (first.nextInt() !== -1155484576) throw new Error("Java Random first nextInt mismatch");
        if (first.nextInt() !== -723955400) throw new Error("Java Random second nextInt mismatch");
        const longRandom = r.createJava("java.util.Random", 0);
        if (longRandom.nextLong() !== -4962768465676381896n) throw new Error("Java Random nextLong mismatch");
        const byteRandom = r.createJava("java.util.Random", 0);
        const bytes = Array(5).fill(0);
        byteRandom.nextBytes(bytes);
        if (bytes.join(",") !== "96,-76,32,-69,56") throw new Error("Java Random nextBytes mismatch");
        const bounded = r.createJava("java.util.Random", 0);
        if (bounded.nextInt(100) !== 60) throw new Error("Java Random bounded nextInt mismatch");
        const powerOfTwo = r.createJava("java.util.Random", 0);
        if (powerOfTwo.nextInt(16) !== 11) throw new Error("Java Random power-of-two bound mismatch");
        const doubleRandom = r.createJava("java.util.Random", 0);
        if (Math.abs(doubleRandom.nextDouble() - 0.730967787376657) > 1e-15) {
          throw new Error("Java Random nextDouble mismatch");
        }
        const gaussian = r.createJava("java.util.Random", 0);
        if (Math.abs(gaussian.nextGaussian() - 0.8025330637390305) > 1e-14) {
          throw new Error("Java Random nextGaussian mismatch");
        }
        if (gaussian.nextGaussian() !== gaussian.nextGaussianValue) {
          throw new Error("Java Random Gaussian cache should return the paired sample");
        }
        const reset = r.createJava("java.util.Random", 99);
        const beforeReset = reset.nextInt();
        reset.setSeed(0);
        if (reset.nextInt() !== -1155484576 || beforeReset === reset.nextInt()) {
          throw new Error("Java Random setSeed did not reset the sequence");
        }
        let invalidBoundRejected = false;
        try { r.createJava("java.util.Random", 0).nextInt(0); }
        catch (error) { invalidBoundRejected = error.message.includes("bound must be positive"); }
        if (!invalidBoundRejected) throw new Error("Java Random accepted a zero bound");
        console.log("java-random-ok");
        """, StandardCharsets.UTF_8);
    Process node;
    try {
      node = new ProcessBuilder("node", runner.toString()).redirectErrorStream(true).start();
    } catch (java.io.IOException missingNode) {
      org.junit.jupiter.api.Assumptions.assumeTrue(false, "Node.js is not installed");
      return;
    }
    assertTrue(node.waitFor(10, TimeUnit.SECONDS), "Java Random runtime test timed out");
    String outputText = new String(node.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
    assertEquals(0, node.exitValue(), outputText);
    assertEquals("java-random-ok", outputText);
  }

  @Test
  void collectionsUsesJavaEqualityAndSeededShuffle() throws Exception {
    Path output = tempDir.resolve("java-collections-runtime");
    new JavaScriptModTranslationExporter().export(List.of(), output);
    Path runner = output.resolve("collections-runtime-test.mjs");
    Files.writeString(runner, """
        import { GoldClientRuntime as r } from "./gold-client-runtime.js";
        const makeValue = value => ({ value, equals(other) { return other != null && other.value === this.value; } });
        const oneA = makeValue(1), oneB = makeValue(1), two = makeValue(2);
        if (r.invokeJavaStatic("java.util.Collections", "frequency", [oneA, two, oneB], makeValue(1)) !== 2) {
          throw new Error("Collections.frequency should use Java equals");
        }
        if (r.invokeJavaStatic("java.util.Collections", "disjoint", [oneA], [oneB])) {
          throw new Error("Collections.disjoint should use Java equals");
        }
        const replaced = [oneA, two, oneB];
        r.invokeJavaStatic("java.util.Collections", "replaceAll", replaced, makeValue(1), "replaced");
        if (replaced.join(",") !== "replaced,[object Object],replaced") {
          throw new Error("Collections.replaceAll should use Java equals");
        }
        const list = r.createJava("java.util.ArrayList", [0, 1, 2, 3, 4]);
        r.invokeJavaStatic("java.util.Collections", "shuffle", list, r.createJava("java.util.Random", 0));
        if (list.items.join(",") !== "3,2,1,4,0") {
          throw new Error("Collections.shuffle should use the supplied seeded Random: " + list.items);
        }
        const array = [0, 1, 2, 3, 4];
        r.invokeJavaStatic("java.util.Collections", "shuffle", array, r.createJava("java.util.Random", 0));
        if (array.join(",") !== "3,2,1,4,0") throw new Error("Collections.shuffle array mismatch");
        console.log("java-collections-ok");
        """, StandardCharsets.UTF_8);
    Process node;
    try {
      node = new ProcessBuilder("node", runner.toString()).redirectErrorStream(true).start();
    } catch (java.io.IOException missingNode) {
      org.junit.jupiter.api.Assumptions.assumeTrue(false, "Node.js is not installed");
      return;
    }
    assertTrue(node.waitFor(10, TimeUnit.SECONDS), "Java Collections runtime test timed out");
    String outputText = new String(node.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
    assertEquals(0, node.exitValue(), outputText);
    assertEquals("java-collections-ok", outputText);
  }

  @Test
  void objectsDeepEqualsRecursesThroughNestedAndPrimitiveArrays() throws Exception {
    Path output = tempDir.resolve("java-deep-equals-runtime");
    new JavaScriptModTranslationExporter().export(List.of(), output);
    Path runner = output.resolve("deep-equals-test.mjs");
    Files.writeString(runner, """
        import { GoldClientRuntime as r } from "./gold-client-runtime.js";
        const deepEquals = (a, b) => r.invokeJavaStatic("java.util.Objects", "deepEquals", a, b);
        if (!deepEquals([[1, 2], [3]], [[1, 2], [3]])) throw new Error("nested arrays should compare deeply");
        if (deepEquals([[1, 2], [3]], [[1, 2], [4]])) throw new Error("nested array difference was missed");
        if (!deepEquals(new Int32Array([1, 2, 3]), new Int32Array([1, 2, 3]))) {
          throw new Error("primitive typed arrays should compare deeply");
        }
        if (deepEquals(new Int32Array([1, 2]), new Int32Array([1, 3]))) {
          throw new Error("primitive typed-array difference was missed");
        }
        const equalA = { value: 7, equals(other) { return other != null && other.value === this.value; } };
        const equalB = { value: 7, equals(other) { return other != null && other.value === this.value; } };
        if (!deepEquals([equalA], [equalB])) throw new Error("object arrays should use Java equals for elements");
        if (!deepEquals(null, null) || deepEquals(null, [])) throw new Error("null deep equality mismatch");
        console.log("java-deep-equals-ok");
        """, StandardCharsets.UTF_8);
    Process node;
    try {
      node = new ProcessBuilder("node", runner.toString()).redirectErrorStream(true).start();
    } catch (java.io.IOException missingNode) {
      org.junit.jupiter.api.Assumptions.assumeTrue(false, "Node.js is not installed");
      return;
    }
    assertTrue(node.waitFor(10, TimeUnit.SECONDS), "Objects.deepEquals runtime test timed out");
    String outputText = new String(node.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
    assertEquals(0, node.exitValue(), outputText);
    assertEquals("java-deep-equals-ok", outputText);
  }

  @Test
  void commonJavaCompatibilityAdaptersExecuteAgainstNode() throws Exception {
    Path output = tempDir.resolve("java-compatibility-runtime");
    new JavaScriptModTranslationExporter().export(List.of(), output);
    Path runner = output.resolve("java-compatibility-test.mjs");
    Files.writeString(runner, """
        import { GoldClientRuntime as r } from "./gold-client-runtime.js";
        globalThis.goldClientForgeBindings = {
          minecraft: {
            call: (owner, method, args) => ({ owner, method, args }),
            construct: (owner, args) => ({ constructed: owner, args }),
            get: (owner, field) => owner + "." + field,
            set: (owner, field, value) => { globalThis.__lastPlatformSet = [owner, field, value]; }
          },
          forge: {
            call: (owner, method, args) => ({ owner, method, args }),
            construct: (owner, args) => ({ constructed: owner, args }),
            get: (owner, field) => owner + "." + field,
            set: (owner, field, value) => { globalThis.__lastPlatformSet = [owner, field, value]; }
          }
        };
        const clientCall = r.invokePlatformStatic("net.minecraft.client.Minecraft", "getMinecraft");
        if (clientCall.owner !== "net.minecraft.client.Minecraft" || clientCall.method !== "getMinecraft") throw new Error("Minecraft static binding");
        const stack = r.create("net.minecraft.item.ItemStack", "item", 1);
        if (stack.constructed !== "net.minecraft.item.ItemStack" || stack.args[1] !== 1) throw new Error("Minecraft constructor binding");
        if (r.getStaticField("net.minecraft.init.Items", "IRON_INGOT") !== "net.minecraft.init.Items.IRON_INGOT") throw new Error("Minecraft static field binding");
        r.setStaticField("net.minecraftforge.fml.common.registry.GameRegistry", "testField", 7);
        if (globalThis.__lastPlatformSet[2] !== 7) throw new Error("Forge static field write binding");
        const list = r.createJava("java.util.ArrayList");
        list.add("alpha");
        list.addAll(["beta", "gamma"]);
        if (list.size() !== 3 || list.get(1) !== "beta" || !list.containsAll(["alpha", "gamma"])) throw new Error("list basics");
        list.add(1, "inserted");
        if (list.toString() !== "[alpha, inserted, beta, gamma]") throw new Error("indexed list add");
        if (list.remove("beta") !== true || list.toString() !== "[alpha, inserted, gamma]") throw new Error("list removal");
        const copiedList = r.createJava("java.util.ArrayList", list);
        if (copiedList.size() !== 3 || copiedList.get(1) !== "inserted") throw new Error("list copy constructor");
        const readOnly = r.invokeJavaStatic("java.util.Collections", "unmodifiableList", list);
        let rejectedMutation = false;
        try { readOnly.add("blocked"); } catch (error) { rejectedMutation = error.message.includes("UnsupportedOperationException"); }
        if (!rejectedMutation || list.size() !== 3) throw new Error("unmodifiableList contract");
        if (list.subList(0, 1).get(0) !== "alpha") throw new Error("list subList");
        const map = r.createJava("java.util.HashMap");
        map.put("n", 2);
        if (map.compute("n", (_key, value) => value + 3) !== 5) throw new Error("map compute");
        if (map.merge("n", 4, (left, right) => left + right) !== 9) throw new Error("map merge");
        if (map.getOrDefault("missing", 17) !== 17) throw new Error("map default");
        if (r.invokeJavaStatic("java.util.Arrays", "binarySearch", [1, 3, 5, 7], 5) !== 2) throw new Error("binarySearch");
        if (r.invokeJavaStatic("java.util.Arrays", "binarySearch", [1, 3, 5, 7], 4) !== -3) throw new Error("binarySearch insertion point");
        if (r.invokeJavaStatic("java.util.Arrays", "equals", [1, 2], [1, 2]) !== true) throw new Error("array equals");
        if (r.invokeJavaStatic("java.lang.Math", "floorDiv", -7, 3) !== -3) throw new Error("floorDiv");
        if (r.invokeJavaStatic("java.lang.Math", "floorMod", -7, 3) !== 2) throw new Error("floorMod");
        if (r.invokeJavaStatic("java.util.Objects", "nonNull", 0) !== true) throw new Error("nonNull");
        if (r.invokeJavaStatic("java.util.Objects", "isNull", null) !== true) throw new Error("isNull");
        if (r.invokeJavaStatic("java.util.Collections", "frequency", ["a", "b", "a"], "a") !== 2) throw new Error("frequency");
        if (r.invokeJavaStatic("java.util.Collections", "disjoint", ["a"], ["b"]) !== true) throw new Error("disjoint");
        const array = [0, 0, 0];
        r.invokeJavaStatic("java.util.Arrays", "fill", array, 6);
        if (array.join(",") !== "6,6,6") throw new Error("array fill");
        const target = [0, 0, 0];
        r.arraycopy([4, 5], 0, target, 1, 2);
        if (target.join(",") !== "0,4,5") throw new Error("arraycopy");
        if (r.invokeInstance("Gold Client", "length") !== 11) throw new Error("String.length");
        if (r.invokeInstance("Gold Client", "substring", 5) !== "Client") throw new Error("String.substring");
        if (r.invokeInstance("Forge", "startsWith", "Fo") !== true) throw new Error("String.startsWith");
        if (r.invokeInstance("Forge", "equalsIgnoreCase", "forge") !== true) throw new Error("String.equalsIgnoreCase");
        if (r.invokeInstance("abc123", "matches", "[a-z]+\\\\d+") !== true) throw new Error("String.matches");
        if (r.invokeInstance("  hi  ", "trim") !== "hi") throw new Error("String.trim");
        const present = r.invokeJavaStatic("java.util.Optional", "of", "value");
        if (!present.isPresent() || present.orElse("fallback") !== "value") throw new Error("Optional.of");
        const empty = r.invokeJavaStatic("java.util.Optional", "empty");
        if (empty.isPresent() || empty.orElse("fallback") !== "fallback") throw new Error("Optional.empty");
        if (present.map(value => value.toUpperCase()).get() !== "VALUE") throw new Error("Optional.map");
        const pattern = r.invokeJavaStatic("java.util.regex.Pattern", "compile", "([a-z]+)-([0-9]+)");
        const matcher = pattern.matcher("mod-1122 and forge-12");
        if (!matcher.find() || matcher.group(1) !== "mod" || matcher.group(2) !== "1122") throw new Error("Pattern.Matcher find/group");
        if (!matcher.find() || matcher.group(1) !== "forge" || matcher.group(2) !== "12") throw new Error("Pattern.Matcher repeated find");
        if (r.invokeJavaStatic("java.util.regex.Pattern", "matches", "[A-Z]+", "JAVA") !== true) throw new Error("Pattern.matches");
        const stream = list.stream().filter(value => value.length > 4);
        if (stream.count() !== 3) throw new Error("Stream count");
        const mappedStream = list.stream().filter(value => value.length > 4).map(value => value.toUpperCase());
        if (mappedStream.toList().toString() !== "[ALPHA, INSERTED, GAMMA]") throw new Error("Stream toList");
        const collected = r.invokeJavaStatic("java.util.stream.Stream", "of", 2, 3, 4)
          .map(value => value * 2)
          .collect(r.invokeJavaStatic("java.util.stream.Collectors", "toList"));
        if (collected.toString() !== "[4, 6, 8]") throw new Error("Stream Collectors.toList");
        if (r.invokeJavaStatic("java.util.stream.Stream", "of", 1, 2, 3).reduce((a, b) => a + b).get() !== 6) throw new Error("Stream reduce");
        if (r.invokeJavaStatic("java.lang.Character", "isDigit", "7") !== true) throw new Error("Character.isDigit");
        if (r.invokeJavaStatic("java.lang.Character", "isLetter", "G") !== true) throw new Error("Character.isLetter");
        const date = r.createJava("java.util.Date", 123456789);
        if (date.getTime() !== 123456789 || date.before(r.createJava("java.util.Date", 123456790)) !== true) throw new Error("Date adapters");
        const uuid = r.invokeJavaStatic("java.util.UUID", "fromString", "123e4567-e89b-12d3-a456-426614174000");
        if (uuid.toString() !== "123e4567-e89b-12d3-a456-426614174000") throw new Error("UUID.fromString");
        const randomUuid = r.invokeJavaStatic("java.util.UUID", "randomUUID");
        if (!/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(randomUuid.toString())) throw new Error("UUID.randomUUID");
        const atomic = r.createJava("java.util.concurrent.atomic.AtomicInteger", 4);
        if (atomic.incrementAndGet() !== 5 || atomic.getAndAdd(3) !== 5 || atomic.get() !== 8) throw new Error("AtomicInteger");
        if (!atomic.compareAndSet(8, 10) || atomic.get() !== 10) throw new Error("AtomicInteger compareAndSet");
        const queue = r.createJava("java.util.PriorityQueue", [8, 2, 5]);
        if (queue.poll() !== 2 || queue.peek() !== 5) throw new Error("PriorityQueue ordering");
        const deque = r.createJava("java.util.ArrayDeque");
        deque.addFirst("first"); deque.addLast("last");
        if (deque.pollFirst() !== "first" || deque.pollLast() !== "last") throw new Error("ArrayDeque endpoints");
        const locale = r.invokeJavaStatic("java.util.Locale", "forLanguageTag", "en-US");
        if (locale.getLanguage() !== "en" || locale.getCountry() !== "US" || locale.toLanguageTag() !== "en-US") throw new Error("Locale adapters");
        const properties = r.createJava("java.util.Properties");
        properties.setProperty("mod.enabled", "true");
        if (properties.getProperty("mod.enabled") !== "true" || properties.getProperty("missing", "fallback") !== "fallback") throw new Error("Properties adapters");
        if (!properties.stringPropertyNames().contains("mod.enabled")) throw new Error("Properties names");
        const systemProperties = r.invokeJavaStatic("java.lang.System", "getProperties");
        if (systemProperties.getProperty("file.separator") !== "/") throw new Error("System properties");
        const seconds = r.getStaticField("java.util.concurrent.TimeUnit", "SECONDS");
        const millis = r.getStaticField("java.util.concurrent.TimeUnit", "MILLISECONDS");
        if (seconds.toMillis(3) !== 3000 || millis.convert(3000, seconds) !== 3000000) throw new Error("TimeUnit conversions");
        if (r.getStaticField("java.util.Locale", "US").toLanguageTag() !== "en-US") throw new Error("Locale constants");
        const bits = r.createJava("java.util.BitSet");
        bits.set(2); bits.set(5, 8);
        if (bits.cardinality() !== 4 || bits.nextSetBit(3) !== 5 || bits.nextClearBit(5) !== 8) throw new Error("BitSet basics");
        const otherBits = r.createJava("java.util.BitSet"); otherBits.set(5); otherBits.set(9);
        if (!bits.intersects(otherBits)) throw new Error("BitSet intersects");
        bits.and(otherBits);
        if (bits.toString() !== "{5}") throw new Error("BitSet and");
        const randomA = r.createJava("java.util.Random", 1234);
        const randomB = r.createJava("java.util.Random", 1234);
        if (randomA.nextInt() !== randomB.nextInt() || randomA.nextInt(10) < 0 || randomA.nextInt(10) >= 10) throw new Error("Random bounds/seed");
        let invalidBoundRejected = false;
        try { randomA.nextInt(0); } catch (error) { invalidBoundRejected = error.message.includes("bound must be positive"); }
        if (!invalidBoundRejected) throw new Error("Random invalid bound");
        if (r.invokeJavaStatic("java.lang.Integer", "bitCount", 0b101101) !== 4) throw new Error("Integer.bitCount");
        if (r.invokeJavaStatic("java.lang.Integer", "toHexString", 255) !== "ff") throw new Error("Integer.toHexString");
        if (r.invokeJavaStatic("java.lang.Integer", "decode", "-0x10") !== -16) throw new Error("Integer.decode");
        if (r.invokeJavaStatic("java.lang.Integer", "numberOfTrailingZeros", 40) !== 3) throw new Error("Integer.numberOfTrailingZeros");
        console.log("java-compatibility-ok");
        """, StandardCharsets.UTF_8);
    Process node;
    try {
      node = new ProcessBuilder("node", runner.toString()).redirectErrorStream(true).start();
    } catch (java.io.IOException missingNode) {
      org.junit.jupiter.api.Assumptions.assumeTrue(false, "Node.js is not installed");
      return;
    }
    assertTrue(node.waitFor(10, TimeUnit.SECONDS), "Java compatibility runtime test timed out");
    String outputText = new String(node.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
    assertEquals(0, node.exitValue(), outputText);
    assertEquals("java-compatibility-ok", outputText);
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
  @Test
  void resolvesInheritedConcreteMethodsForDeclarationImplementations() throws Exception {
    ClassModel contract = new ClassModel("example/IValue", "java/lang/Object", List.of(),
        Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT, null, List.of(),
        List.of(), List.of(method("getValue", "()Ljava/lang/String;",
            Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, List.of())), List.of());
    ClassModel parent = new ClassModel("example/Parent", "java/lang/Object", List.of(),
        Opcodes.ACC_PUBLIC, null, List.of(), List.of(),
        List.of(method("getValue", "()Ljava/lang/String;", Opcodes.ACC_PUBLIC,
            List.of(op("CONSTANT", "\"from-parent\""), op("RETURN", "\"from-parent\"")))),
        List.of());
    ClassModel child = new ClassModel("example/Child", "example/Parent", List.of("example/IValue"),
        Opcodes.ACC_PUBLIC, null, List.of(), List.of(), List.of(), List.of());

    Path output = tempDir.resolve("inherited-declarations");
    new JavaScriptModTranslationExporter().export(List.of(contract, parent, child), output);
    Path runner = output.resolve("run-inherited.mjs");
    Files.writeString(runner, """
        import { Translated_example_Parent } from "./example/Parent.translated.js";
        import { Translated_example_Child } from "./example/Child.translated.js";
        const child = new Translated_example_Child();
        if (child.getValue() !== "from-parent") throw new Error("inherited method dispatch failed");
        console.log("inherited-dispatch-ok");
        """, StandardCharsets.UTF_8);
    Process node;
    try {
      node = new ProcessBuilder("node", runner.toString()).redirectErrorStream(true).start();
    } catch (java.io.IOException missingNode) {
      org.junit.jupiter.api.Assumptions.assumeTrue(false, "Node.js is not installed");
      return;
    }
    assertTrue(node.waitFor(10, TimeUnit.SECONDS), "inherited method runtime test timed out");
    String outputText = new String(node.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
    assertEquals(0, node.exitValue(), outputText);
    assertEquals("inherited-dispatch-ok", outputText);
  }

  @Test
  void declarationAdapterKeysIncludeTheirDeclaringOwner() {
    JavaScriptTranslator translator = new JavaScriptTranslator();
    String first = translator.translateAdapter("example/First", "getValue",
        "()Ljava/lang/String;", "abstract method", false).source();
    String second = translator.translateAdapter("example/Second", "getValue",
        "()Ljava/lang/String;", "abstract method", false).source();

    assertTrue(first.contains("example.First#getValue()Ljava/lang/String;: abstract method"));
    assertTrue(second.contains("example.Second#getValue()Ljava/lang/String;: abstract method"));
    assertFalse(first.contains("example.Second#"));
  }

  @Test
  void exportsStableManifestForEveryDeclarationAdapter() throws Exception {
    ClassModel contract = new ClassModel("example/IValue", "java/lang/Object", List.of(),
        Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT, null, List.of(),
        List.of(), List.of(method("getValue", "()Ljava/lang/String;",
            Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, List.of())), List.of());
    Path output = tempDir.resolve("declaration-manifest");
    var report = new JavaScriptModTranslationExporter().export(List.of(contract), output);

    assertEquals(0, report.adapterRequiredCount());
    assertTrue(report.methods().stream().anyMatch(item -> item.status().equals("declaration-only")));
    assertTrue(Files.readString(output.resolve("example/IValue.translated.js"))
        .contains("Status: declaration-only; concrete implementations supply this method."));
    String manifest = Files.readString(output.resolve("declaration-adapters.json"));
    assertTrue(manifest.contains("\"formatVersion\": \"gold-client-declaration-adapters-v1\""));
    assertTrue(manifest.contains("\"adapterRequiredCount\": 0"));
    assertTrue(manifest.contains("\"adapters\": []"));
    assertFalse(manifest.contains("example.IValue#getValue()Ljava/lang/String;: abstract method"));
  }

  @Test
  void dispatchesSameArityOverloadsByJvmDescriptor() throws Exception {
    ClassModel clazz = new ClassModel("example/Overloads", "java/lang/Object", List.of(),
        Opcodes.ACC_PUBLIC, null, List.of(), List.of(),
        List.of(
            method("choose", "(I)Ljava/lang/String;", Opcodes.ACC_PUBLIC, List.of(
                op("CONSTANT", "\"int\""), op("RETURN", "\"int\""))),
            method("choose", "(Ljava/lang/String;)Ljava/lang/String;", Opcodes.ACC_PUBLIC, List.of(
                op("CONSTANT", "\"string\""), op("RETURN", "\"string\"")))),
        List.of());
    Path output = tempDir.resolve("overloaded-methods");
    new JavaScriptModTranslationExporter().export(List.of(clazz), output);
    Path runner = output.resolve("run-overloads.mjs");
    Files.writeString(runner, """
        import { Translated_example_Overloads } from "./example/Overloads.translated.js";
        const instance = new Translated_example_Overloads();
        if (instance.choose(7) !== "int") throw new Error("integer overload selected incorrectly");
        if (instance.choose("x") !== "string") throw new Error("string overload selected incorrectly");
        console.log("overload-dispatch-ok");
        """, StandardCharsets.UTF_8);
    Process node;
    try {
      node = new ProcessBuilder("node", runner.toString()).redirectErrorStream(true).start();
    } catch (java.io.IOException missingNode) {
      org.junit.jupiter.api.Assumptions.assumeTrue(false, "Node.js is not installed");
      return;
    }
    assertTrue(node.waitFor(10, TimeUnit.SECONDS), "overload runtime test timed out");
    String outputText = new String(node.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
    assertEquals(0, node.exitValue(), outputText);
    assertEquals("overload-dispatch-ok", outputText);
  }

  @Test
  void emitsRegisterClassJavaScriptWithoutLiteralEscapes() throws Exception {
    ClassModel clazz = new ClassModel("example/Escaping", "java/lang/Object", List.of(),
        Opcodes.ACC_PUBLIC, null, List.of(), List.of(), List.of(), List.of());
    Path output = tempDir.resolve("register-class-escaping");
    new JavaScriptModTranslationExporter().export(List.of(clazz), output);
    String module = Files.readString(output.resolve("example/Escaping.translated.js"));
    assertTrue(module.contains("__goldMethods.get(\"<clinit>\") ?? [])"));
    assertTrue(module.contains("], methods: __goldMethods });" + System.lineSeparator()));
    assertFalse(module.contains("__goldMethods.get(\\\"<clinit>\\\")"));
    assertFalse(module.contains("methods: __goldMethods });\\n"));
  }

}
