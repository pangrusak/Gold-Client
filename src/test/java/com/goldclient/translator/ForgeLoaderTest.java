package com.goldclient.translator;

import com.goldclient.analyzer.ModAnalyzer;
import com.goldclient.model.AnnotationData;
import com.goldclient.model.ClassModel;
import com.goldclient.model.ControlFlowModel;
import com.goldclient.model.FieldModel;
import com.goldclient.model.IrMethodModel;
import com.goldclient.model.IrOperationModel;
import com.goldclient.model.MethodModel;
import com.goldclient.planner.LoaderCommand;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.Opcodes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ForgeLoaderTest {
  @TempDir
  Path tempDir;

  private static final String MOD = "net.minecraftforge.fml.common.Mod";
  private static final String EVENT_HANDLER = "net.minecraftforge.fml.common.Mod$EventHandler";
  private static final String SUBSCRIBE_EVENT =
      "net.minecraftforge.fml.common.eventhandler.SubscribeEvent";
  private static final String EVENT_BUS_SUBSCRIBER =
      "net.minecraftforge.fml.common.Mod$EventBusSubscriber";
  private static final String SIDED_PROXY = "net.minecraftforge.fml.common.SidedProxy";

  @Test
  void ordersMultipleModsAndRegistersStaticAndInstanceSubscribers() throws Exception {
    ClassModel proxy = new ClassModel("sample/ClientProxy", "java/lang/Object", List.of(),
        Opcodes.ACC_PUBLIC, null, List.of(), List.of(), List.of(), List.of(), List.of());
    ClassModel modA = new ClassModel("sample/AMod", "java/lang/Object", List.of(),
        Opcodes.ACC_PUBLIC, null, List.of(MOD), List.of(), List.of(
            constructor(),
            annotatedMethod("onTick",
                "(Lnet/minecraftforge/event/TickEvent$ClientTickEvent;)V",
                SUBSCRIBE_EVENT, Map.of(
                    "priority", new AnnotationData.EnumValue(
                        "net.minecraftforge.fml.common.eventhandler.EventPriority", "HIGH"),
                    "receiveCanceled", true))),
        List.of(), List.of(mod("a", "")));
    ClassModel modB = new ClassModel("sample/BMod", "java/lang/Object", List.of(),
        Opcodes.ACC_PUBLIC, null, List.of(MOD),
        List.of(sidedProxy("proxy", "Lsample/ClientProxy;", "sample.ClientProxy")),
        List.of(constructor(), handler("init",
            "(Lnet/minecraftforge/fml/common/event/FMLInitializationEvent;)V")),
        List.of(), List.of(mod("b", "required-after:a")));
    ClassModel subscriber = new ClassModel("sample/Subscribers", "java/lang/Object", List.of(),
        Opcodes.ACC_PUBLIC, null, List.of(EVENT_BUS_SUBSCRIBER), List.of(), List.of(
            staticAnnotatedMethod("onTick",
                "(Lnet/minecraftforge/event/TickEvent$ClientTickEvent;)V", SUBSCRIBE_EVENT,
                Map.of())),
        List.of(), List.of(new AnnotationData(EVENT_BUS_SUBSCRIBER, true, Map.of(
            "modid", "b",
            "value", List.of(new AnnotationData.EnumValue(
                "net.minecraftforge.fml.relauncher.Side", "CLIENT"))))));

    Path output = tempDir.resolve("loader-plan");
    ForgeLifecycleExporter.LifecycleReport report = new ForgeLifecycleExporter()
        .export(List.of(subscriber, modB, proxy, modA), output);

    assertEquals("READY_FOR_REAL_FORGE_HOST", report.status(), report.blockers().toString());
    assertEquals(List.of("a", "b"), report.mods().stream()
        .map(ForgeLifecycleExporter.ModEntry::modId).toList());
    assertEquals("a,b", report.loadOrder());
    assertEquals(1, report.injectedFields());
    assertEquals(2, report.discoveredEventSubscribers());
    assertEquals(1, report.staticSubscribers().size());
    assertEquals(0, report.mods().get(1).instanceSubscribers().size());
    assertEquals(1, report.mods().get(0).instanceSubscribers().size());

    String javascript = Files.readString(output.resolve("forge-lifecycle.js"));
    assertTrue(javascript.contains("startForgeServerLifecycle"));
    assertTrue(javascript.contains("stopForgeMods"));
    assertTrue(javascript.contains("host.registerSubscriber"));
    assertTrue(javascript.contains("MinecraftForge.EVENT_BUS.register"));
    assertTrue(javascript.contains("forgeServerLifecyclePhases"));
    assertTrue(javascript.contains("\"priority\":\"HIGH\""), javascript);
    assertTrue(javascript.contains("\"receiveCanceled\":true"), javascript);
  }

  @Test
  void resolvesModIdFromMetadataOnlyForUseMetadataEntries() throws Exception {
    ClassModel mod = new ClassModel("sample/MetaMod", "java/lang/Object", List.of(),
        Opcodes.ACC_PUBLIC, null, List.of(MOD), List.of(), List.of(constructor()),
        List.of(), List.of(new AnnotationData(MOD, true, Map.of(
            "modid", "", "useMetadata", true, "version", ""))));

    Path output = tempDir.resolve("metadata-plan");
    ForgeLifecycleExporter.LifecycleReport report = new ForgeLifecycleExporter()
        .export(List.of(mod), Map.of("sample/MetaMod", "metaid"), output);

    assertEquals("READY_FOR_REAL_FORGE_HOST", report.status(), report.blockers().toString());
    assertEquals(List.of("metaid"), report.mods().stream()
        .map(ForgeLifecycleExporter.ModEntry::modId).toList());
  }

  @Test
  void reportsMissingModIdWhenMetadataIdCannotBeUsed() throws Exception {
    ClassModel mod = new ClassModel("sample/MetaMod", "java/lang/Object", List.of(),
        Opcodes.ACC_PUBLIC, null, List.of(MOD), List.of(), List.of(constructor()),
        List.of(), List.of(new AnnotationData(MOD, true, Map.of(
            "modid", "", "useMetadata", false, "version", ""))));

    Path output = tempDir.resolve("metadata-blocked-plan");
    ForgeLifecycleExporter.LifecycleReport report = new ForgeLifecycleExporter()
        .export(List.of(mod), Map.of("sample/MetaMod", "metaid"), output);

    assertEquals("PARTIAL_WITH_BLOCKERS", report.status());
    assertTrue(report.blockers().stream().anyMatch(blocker ->
        blocker.contains("Missing literal @Mod(modid=...)")
            && blocker.contains("mcmod.info declares metaid")), report.blockers().toString());
  }

  @Test
  void generatedAppleSkinBundleIsSyntacticallyLoadableByNode() throws Exception {
    Path jar = Path.of("test-mods/appleskin1.12.2.jar");
    org.junit.jupiter.api.Assumptions.assumeTrue(Files.isRegularFile(jar),
        "AppleSkin fixture is missing");
    Path output = tempDir.resolve("appleskin-bundle");
    var analysis = new ModAnalyzer().analyze(jar);
    new JavaScriptModTranslationExporter().export(List.of(jar), analysis.classes(), output);
    Process node;
    try {
      node = new ProcessBuilder("node", "--input-type=module", "-e",
          "const m = await import('./forge-lifecycle.js');"
              + "if (m.forgeLifecycleBlockers.length) throw new Error(m.forgeLifecycleBlockers);"
              + "console.log(m.forgeMods.map(mod => mod.modId).join(','));")
          .directory(output.toFile()).redirectErrorStream(true).start();
    } catch (java.io.IOException missingNode) {
      org.junit.jupiter.api.Assumptions.assumeTrue(false, "Node.js is not installed");
      return;
    }
    assertTrue(node.waitFor(20, TimeUnit.SECONDS), "AppleSkin bundle import timed out");
    String nodeOutput = new String(node.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
    assertEquals(0, node.exitValue(), nodeOutput);
    assertEquals("appleskin", nodeOutput);
  }

  @Test
  void loaderCommandWritesBundleAndHonestPlanForAppleSkin() throws Exception {
    Path jar = Path.of("test-mods/appleskin1.12.2.jar");
    org.junit.jupiter.api.Assumptions.assumeTrue(Files.isRegularFile(jar),
        "AppleSkin fixture is missing");
    Path output = tempDir.resolve("cli-loader");
    ByteArrayOutputStream stdout = new ByteArrayOutputStream();
    ByteArrayOutputStream stderr = new ByteArrayOutputStream();
    int code;
    try (PrintStream out = new PrintStream(stdout, true, StandardCharsets.UTF_8);
         PrintStream err = new PrintStream(stderr, true, StandardCharsets.UTF_8)) {
      code = LoaderCommand.run(new String[] {
          "--input", jar.toString(), "--output", output.toString(),
          "--single-file", "--compact"
      }, out, err);
    }
    String log = stdout.toString(StandardCharsets.UTF_8)
        + stderr.toString(StandardCharsets.UTF_8);
    assertEquals(0, code, log);
    assertTrue(log.contains("Translation complete: yes"), log);
    assertTrue(log.contains("Lifecycle status: READY_FOR_REAL_FORGE_HOST"), log);
    assertTrue(log.contains("Single-file bundle:"), log);
    assertTrue(Files.isRegularFile(output.resolve("loader-plan.json")));
    assertTrue(Files.isRegularFile(output.resolve("forge-lifecycle.js")));
    Path singleFile = output.resolve(JavaScriptBundleExporter.DEFAULT_FILE_NAME);
    assertTrue(Files.isRegularFile(singleFile));
    String singleSource = Files.readString(singleFile);
    assertFalse(singleSource.contains("import "), "single file must not use ESM imports");
    assertTrue(singleSource.contains("global.GoldClientForge"), singleSource);
    String plan = Files.readString(output.resolve("loader-plan.json"));
    assertTrue(plan.contains("\"translationComplete\": true"));
    assertTrue(plan.contains("\"singleFile\""));
    assertTrue(plan.contains("\"singleFileModuleCount\""));
  }

  @Test
  void reportsMissingRequiredDependenciesInsteadOfInventingLoadOrder() throws Exception {
    ClassModel mod = new ClassModel("sample/Broken", "java/lang/Object", List.of(),
        Opcodes.ACC_PUBLIC, null, List.of(MOD), List.of(), List.of(constructor()),
        List.of(), List.of(mod("broken", "required-after:missing")));
    Path output = tempDir.resolve("blocked-loader");
    ForgeLifecycleExporter.LifecycleReport report = new ForgeLifecycleExporter()
        .export(List.of(mod), output);
    assertEquals("PARTIAL_WITH_BLOCKERS", report.status());
    assertTrue(report.blockers().stream().anyMatch(blocker ->
        blocker.contains("Required dependency missing: broken -> missing")), report.blockers().toString());
  }

  @Test
  void reportsMixinClassesAsUnappliedTransformations() throws Exception {
    ClassModel mod = new ClassModel("sample/MixinMod", "java/lang/Object", List.of(),
        Opcodes.ACC_PUBLIC, null, List.of(MOD), List.of(), List.of(constructor()),
        List.of(), List.of(mod("mixinmod", "")));
    ClassModel mixin = new ClassModel("sample/MixinThing", "java/lang/Object", List.of(),
        Opcodes.ACC_PUBLIC, null, List.of("org.spongepowered.asm.mixin.Mixin"),
        List.of(), List.of(constructor()), List.of(), List.of());
    Path output = tempDir.resolve("mixin-plan");
    ForgeLifecycleExporter.LifecycleReport report = new ForgeLifecycleExporter()
        .export(List.of(mod, mixin), output);
    assertEquals("PARTIAL_WITH_BLOCKERS", report.status());
    assertTrue(report.blockers().stream().anyMatch(blocker ->
        blocker.contains("Mixin transformation is not implemented")
            && blocker.contains("sample.MixinThing")), report.blockers().toString());
  }

  @Test
  void reportsCoremodTransformersAsUnappliedTransformations() throws Exception {
    ClassModel mod = new ClassModel("sample/CoreMod", "java/lang/Object", List.of(),
        Opcodes.ACC_PUBLIC, null, List.of(MOD), List.of(), List.of(constructor()),
        List.of(), List.of(mod("coremod", "")));
    ClassModel transformer = new ClassModel("sample/CoreTransformer", "java/lang/Object",
        List.of("net/minecraft/launchwrapper/IClassTransformer"), Opcodes.ACC_PUBLIC, null,
        List.of(), List.of(), List.of(constructor()), List.of(), List.of());
    Path output = tempDir.resolve("coremod-plan");
    ForgeLifecycleExporter.LifecycleReport report = new ForgeLifecycleExporter()
        .export(List.of(mod, transformer), output);
    assertEquals("PARTIAL_WITH_BLOCKERS", report.status());
    assertTrue(report.blockers().stream().anyMatch(blocker ->
        blocker.contains("Coremod/ASM transformation is not implemented")
            && blocker.contains("sample.CoreTransformer")), report.blockers().toString());
  }

  @Test
  void generatedLoaderConstructsAllModsThenRunsPhaseMajorLifecycleAndSubscribers()
      throws Exception {
    ClassModel modA = new ClassModel("sample/AMod", "java/lang/Object", List.of(),
        Opcodes.ACC_PUBLIC, null, List.of(MOD), List.of(), List.of(
            constructor(),
            handler("construction",
                "(Lnet/minecraftforge/fml/common/event/FMLConstructionEvent;)V"),
            handler("preInit",
                "(Lnet/minecraftforge/fml/common/event/FMLPreInitializationEvent;)V"),
            annotatedMethod("onTick",
                "(Lnet/minecraftforge/event/TickEvent$ClientTickEvent;)V", SUBSCRIBE_EVENT,
                Map.of())),
        List.of(), List.of(mod("a", "")));
    ClassModel modB = new ClassModel("sample/BMod", "java/lang/Object", List.of(),
        Opcodes.ACC_PUBLIC, null, List.of(MOD), List.of(), List.of(
            constructor(),
            handler("init",
                "(Lnet/minecraftforge/fml/common/event/FMLInitializationEvent;)V")),
        List.of(), List.of(mod("b", "required-after:a")));

    Path output = tempDir.resolve("loader-runtime");
    new ForgeLifecycleExporter().export(List.of(modB, modA), output);
    new ForgeHostRuntimeExporter().export(output);
    Files.writeString(output.resolve("package.json"), "{\n  \"type\": \"module\"\n}\n");
    String runtimeSource = new String(getClass().getResourceAsStream(
        "/com/goldclient/translator/gold-client-runtime.js").readAllBytes(),
        StandardCharsets.UTF_8);
    Files.writeString(output.resolve("gold-client-runtime.js"), runtimeSource,
        StandardCharsets.UTF_8);
    Files.writeString(output.resolve("mod-index.js"), """
        import { GoldClientRuntime } from "./gold-client-runtime.js";
        const seen = globalThis.__forgeLoaderEvents = [];
        export class BaseEvent {
          constructor() { this.cancelled = false; }
          isCanceled() { return this.cancelled; }
        }
        export class ChildEvent extends BaseEvent {
          constructor(id) { super(); this.id = id; }
        }
        export class StaticHandlers {
          static onChild(event) { seen.push("static:" + event.id); }
        }
        export class AMod {
          constructor() { seen.push("construct:a"); }
          construction() { seen.push("construction:a"); }
          preInit() {
            seen.push("preInit:a");
            GoldClientRuntime.getStaticField(
              "net.minecraftforge.common.MinecraftForge", "EVENT_BUS").register(this);
            GoldClientRuntime.getStaticField(
              "net.minecraftforge.common.MinecraftForge", "EVENT_BUS")
              .register(GoldClientRuntime.classLiteral("sample.StaticHandlers"));
          }
          onTick(event) { seen.push("tick:a:" + event.id); }
          onBase(event) { seen.push("base:a:" + event.id); }
        }
        export class BMod {
          constructor() { seen.push("construct:b"); }
          init() { seen.push("init:b"); }
        }
        GoldClientRuntime.registerClass("sample.BaseEvent", BaseEvent);
        GoldClientRuntime.registerClass("sample.ChildEvent", ChildEvent, null,
          { superName: "sample.BaseEvent" });
        GoldClientRuntime.registerClass("sample.StaticHandlers", StaticHandlers, null, {
          methods: new Map([["onChild", [{
            arity: 1,
            descriptor: "(Lsample/BaseEvent;)V",
            isStatic: true,
            annotations: ["net.minecraftforge.fml.common.eventhandler.SubscribeEvent"],
            subscribePriority: "NORMAL",
            receiveCanceled: false,
            fn: StaticHandlers.onChild
          }]]])
        });
        GoldClientRuntime.registerClass("sample.AMod", AMod, null, {
          methods: new Map([
            ["onTick", [{
              arity: 1,
              descriptor: "(Lnet/minecraftforge/event/TickEvent$ClientTickEvent;)V",
              isStatic: false,
              annotations: ["net.minecraftforge.fml.common.eventhandler.SubscribeEvent"],
              subscribePriority: "NORMAL",
              receiveCanceled: false,
              fn: AMod.prototype.onTick
            }]],
            ["onBase", [{
              arity: 1,
              descriptor: "(Lsample/BaseEvent;)V",
              isStatic: false,
              annotations: ["net.minecraftforge.fml.common.eventhandler.SubscribeEvent"],
              subscribePriority: "NORMAL",
              receiveCanceled: false,
              fn: AMod.prototype.onBase
            }]]
          ])
        });
        GoldClientRuntime.registerClass("sample.BMod", BMod);
        export const classes = Object.freeze({
          "sample.AMod": {}, "sample.BMod": {}, "sample.BaseEvent": {}, "sample.ChildEvent": {},
          "sample.StaticHandlers": {}
        });
        """, StandardCharsets.UTF_8);

    Path runner = output.resolve("loader-runtime-test.mjs");
    Files.writeString(runner, """
        import { startForgeMods, stopForgeMods, forgeLoaderStatus } from "./forge-lifecycle.js";
        import { createForgeHost } from "./forge-host-runtime.js";
        import { ChildEvent } from "./mod-index.js";
        import { GoldClientRuntime as runtime } from "./gold-client-runtime.js";
        const seen = globalThis.__forgeLoaderEvents;
        const bindings = {
          events: { createLifecycleEvent: async (mod, phase) => {
            seen.push("event:" + phase.name + ":" + mod.modId);
            return { phase: phase.name, modId: mod.modId };
          } },
          minecraft: {}, forge: { stopMod: async () => {} }, render: {}, input: {},
          resources: {}, network: {}, config: {},
          registries: { register: (_kind, _id, value) => value, lookup: () => null },
          logger: { debug: () => {}, info: () => {}, warn: () => {}, error: () => {} },
          side: { current: () => "CLIENT" }
        };
        const host = createForgeHost(bindings);
        const started = await startForgeMods(host);
        if (started.length !== 2) throw new Error("expected two started mods");
        host.postEvent("net.minecraftforge.event.TickEvent$ClientTickEvent", { id: 7 });
        host.postEvent("sample.ChildEvent", new ChildEvent(9));
        const expectedPrefix = [
          "construct:a", "construct:b",
          "event:construction:a", "construction:a", "event:construction:b",
          "event:preInit:a", "preInit:a", "event:preInit:b",
          "event:init:a", "event:init:b", "init:b",
          "event:postInit:a", "event:postInit:b",
          "event:loadComplete:a", "event:loadComplete:b",
          "tick:a:7", "base:a:9", "static:9"
        ];
        if (seen.join(",") !== expectedPrefix.join(",")) {
          throw new Error("phase-major order mismatch: " + seen.join(","));
        }
        const loader = "net.minecraftforge.fml.common.Loader";
        if (runtime.invokePlatformStatic(loader, "isModLoaded", "a") !== 1) {
          throw new Error("Loader.isModLoaded did not see a started mod");
        }
        if (runtime.invokePlatformStatic(loader, "isModLoaded", "missing") !== 0) {
          throw new Error("Loader.isModLoaded accepted an unknown mod");
        }
        if (runtime.invokePlatformStatic(loader, "isModLoaded", "forge") !== 1) {
          throw new Error("Loader.isModLoaded did not report Forge");
        }
        const status = forgeLoaderStatus();
        if (!status.started || status.modCount !== 2 || status.staticSubscriberCount !== 0
            || status.discoveredInstanceSubscriberCount !== 1
            || status.manualEventBusRegistration !== true) {
          throw new Error("loader status mismatch: " + JSON.stringify(status));
        }
        const stopped = await stopForgeMods(host);
        if (stopped.join(",") !== "b,a") throw new Error("stop order mismatch: " + stopped);
        const entrypointModule = await import("./eaglercraft-forge-entry.js");
        const target = {};
        entrypointModule.installGoldClientForgeEntrypoint(target);
        target.goldClientForgeBindings = bindings;
        const entryStarted = await target.goldClientStartForgeMods();
        if (entryStarted.started.length !== 2) {
          throw new Error("entrypoint did not start both mods");
        }
        if (target.goldClientForgeEntrypoint.getStatus().started !== true) {
          throw new Error("entrypoint status was not started");
        }
        const entryStopped = await target.goldClientForgeEntrypoint.stop();
        if (entryStopped.join(",") !== "b,a") {
          throw new Error("entrypoint stop did not target the started host: " + entryStopped);
        }
        if (target.goldClientForgeEntrypoint.getStatus().started) {
          throw new Error("entrypoint status remained started after stop");
        }
        console.log("forge-loader-ok");
        """, StandardCharsets.UTF_8);

    Process node;
    try {
      node = new ProcessBuilder("node", runner.toString()).redirectErrorStream(true).start();
    } catch (java.io.IOException missingNode) {
      org.junit.jupiter.api.Assumptions.assumeTrue(false, "Node.js is not installed");
      return;
    }
    assertTrue(node.waitFor(10, TimeUnit.SECONDS), "generated Forge loader timed out");
    String nodeOutput = new String(node.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
    assertEquals(0, node.exitValue(), nodeOutput);
    assertEquals("forge-loader-ok", nodeOutput);

    Path singleFile = output.resolve(JavaScriptBundleExporter.DEFAULT_FILE_NAME);
    JavaScriptBundleExporter.BundleSummary summary =
        JavaScriptBundleExporter.write(output, singleFile, true);
    assertTrue(summary.moduleCount() >= 5, "single-file bundle should include every module");
    assertTrue(Files.size(singleFile) > 0, "single-file bundle should not be empty");
    assertFalse(Files.readString(singleFile).contains("import "), "single file must not use ESM imports");

    Path singleRunner = output.resolve("loader-single-file-test.cjs");
    Files.writeString(singleRunner, """
        const fs = require("node:fs");
        const path = require("node:path");
        const vm = require("node:vm");
        const source = fs.readFileSync(path.join(__dirname, "gold-client-forge.js"), "utf8");
        // Browsers expose window === globalThis. Keep that shape so the host
        // installation path cannot define a non-writable property and then
        // assign it again.
        const sandbox = {
          console, Date, Math, Map, Set, Promise, Object, Array, String, Number,
          Error, TypeError, RangeError, RegExp, JSON, setTimeout, clearTimeout, queueMicrotask
        };
        sandbox.globalThis = sandbox;
        sandbox.window = sandbox;
        vm.createContext(sandbox);
        vm.runInContext(source, sandbox, { filename: "gold-client-forge.js" });
        const bundle = sandbox.GoldClientForge;
        if (!bundle) throw new Error("GoldClientForge missing");
        if (!sandbox.window.goldClientForgeEntrypoint) throw new Error("entrypoint was not installed");
        if (!Array.isArray(bundle.mods) || bundle.mods.length !== 2) {
          throw new Error("mod metadata missing from the single-file bundle");
        }
        if (typeof bundle.runtime.resolveClass !== "function") {
          throw new Error("runtime class registry is not exposed");
        }
        const seen = sandbox.__forgeLoaderEvents;
        const bindings = {
          events: { createLifecycleEvent: async (mod, phase) => {
            seen.push("event:" + phase.name + ":" + mod.modId);
            return { phase: phase.name, modId: mod.modId };
          } },
          minecraft: {}, forge: { stopMod: async () => {} }, render: {}, input: {},
          resources: {}, network: {}, config: {},
          registries: { register: (_kind, _id, value) => value, lookup: () => null },
          logger: { debug: () => {}, info: () => {}, warn: () => {}, error: () => {} },
          side: { current: () => "CLIENT" }
        };
        (async () => {
          const started = await bundle.start(bindings);
          if (started.modCount !== 2) throw new Error("single-file start did not run two mods");
          const host = sandbox.window.goldClientForgeHost;
          host.postEvent("net.minecraftforge.event.TickEvent$ClientTickEvent", { id: 7 });
          const ChildEvent = bundle.runtime.resolveClass("sample.ChildEvent");
          host.postEvent("sample.ChildEvent", new ChildEvent(9));
          const expectedPrefix = [
            "construct:a", "construct:b",
            "event:construction:a", "construction:a", "event:construction:b",
            "event:preInit:a", "preInit:a", "event:preInit:b",
            "event:init:a", "event:init:b", "init:b",
            "event:postInit:a", "event:postInit:b",
            "event:loadComplete:a", "event:loadComplete:b",
            "tick:a:7", "base:a:9", "static:9"
          ];
          if (seen.join(",") !== expectedPrefix.join(",")) {
            throw new Error("single-file phase-major order mismatch: " + seen.join(","));
          }
          const status = bundle.status();
          if (!status.started || status.modCount !== 2) {
            throw new Error("single-file loader status mismatch: " + JSON.stringify(status));
          }
          const stopped = await bundle.stop("single-file test");
          if (stopped.join(",") !== "b,a") {
            throw new Error("single-file stop order mismatch: " + stopped.join(","));
          }
          console.log("single-file-ok");
        })().catch(error => {
          console.error(error && error.stack ? error.stack : error);
          process.exit(1);
        });
        """, StandardCharsets.UTF_8);

    Process singleNode;
    try {
      singleNode = new ProcessBuilder("node", singleRunner.toString())
          .redirectErrorStream(true).start();
    } catch (java.io.IOException missingNode) {
      org.junit.jupiter.api.Assumptions.assumeTrue(false, "Node.js is not installed");
      return;
    }
    assertTrue(singleNode.waitFor(20, TimeUnit.SECONDS), "single-file loader timed out");
    String singleOutput = new String(singleNode.getInputStream().readAllBytes(),
        StandardCharsets.UTF_8).trim();
    assertEquals(0, singleNode.exitValue(), singleOutput);
    assertEquals("single-file-ok", singleOutput);
  }

  @Test
  void keepsServerOnlyHandlersWithoutDispatchingThemOnTheClient() throws Exception {
    ClassModel mod = new ClassModel("sample/ServerAware", "java/lang/Object", List.of(),
        Opcodes.ACC_PUBLIC, null, List.of(MOD), List.of(), List.of(
            constructor(),
            handler("serverStarting",
                "(Lnet/minecraftforge/fml/common/event/FMLServerStartingEvent;)V")),
        List.of(), List.of(mod("serveraware", "")));
    Path output = tempDir.resolve("server-aware");
    ForgeLifecycleExporter.LifecycleReport report = new ForgeLifecycleExporter()
        .export(List.of(mod), output);
    assertEquals("READY_FOR_REAL_FORGE_HOST", report.status(), report.blockers().toString());
    String javascript = Files.readString(output.resolve("forge-lifecycle.js"));
    assertTrue(report.mods().get(0).handlers().get("serverStarting").size() == 1);
    assertTrue(javascript.contains("forgeServerLifecyclePhases"));
    assertFalse(javascript.contains("serverStarting,\\n") && javascript.contains("forgeLifecyclePhases = Object.freeze([{\"name\":\"serverStarting\""));
  }

  @Test
  void acceptsSidedProxyImplementationsThatImplementTheFieldInterface() throws Exception {
    ClassModel proxyInterface = new ClassModel("sample/IClientProxy", "java/lang/Object", List.of(),
        Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT, null, List.of(),
        List.of(), List.of(), List.of(), List.of());
    ClassModel proxy = new ClassModel("sample/ClientProxy", "java/lang/Object",
        List.of("sample/IClientProxy"), Opcodes.ACC_PUBLIC, null, List.of(),
        List.of(), List.of(constructor()), List.of(), List.of());
    ClassModel mod = new ClassModel("sample/ProxyMod", "java/lang/Object", List.of(),
        Opcodes.ACC_PUBLIC, null, List.of(MOD),
        List.of(sidedProxy("proxy", "Lsample/IClientProxy;", "sample.ClientProxy")),
        List.of(constructor()), List.of(), List.of(mod("proxymod", "")));
    Path output = tempDir.resolve("interface-proxy");
    ForgeLifecycleExporter.LifecycleReport report = new ForgeLifecycleExporter()
        .export(List.of(proxyInterface, proxy, mod), output);
    assertEquals("READY_FOR_REAL_FORGE_HOST", report.status(), report.blockers().toString());
    assertEquals(1, report.injectedFields());
  }

  private static MethodModel constructor() {
    return new MethodModel("<init>", "()V", null, Opcodes.ACC_PUBLIC, List.of(), List.of(),
        List.of(), List.of(), List.of(), 1, 1, new ControlFlowModel(0, List.of()),
        new IrMethodModel("<init>", "()V", List.of(
            new IrOperationModel("RETURN", List.of(), 0))), List.of());
  }

  private static MethodModel handler(String name, String descriptor) {
    return new MethodModel(name, descriptor, null, Opcodes.ACC_PUBLIC, List.of(),
        List.of(EVENT_HANDLER), List.of(), List.of(), List.of(), 1, 2,
        new ControlFlowModel(0, List.of()),
        new IrMethodModel(name, descriptor, List.of(
            new IrOperationModel("RETURN", List.of(), 0))),
        List.of(new AnnotationData(EVENT_HANDLER, true, Map.of())));
  }

  private static MethodModel annotatedMethod(String name, String descriptor, String annotation,
      Map<String, Object> values) {
    return annotatedMethod(name, descriptor, annotation, values, Opcodes.ACC_PUBLIC);
  }

  private static MethodModel staticAnnotatedMethod(String name, String descriptor,
      String annotation, Map<String, Object> values) {
    return annotatedMethod(name, descriptor, annotation, values,
        Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC);
  }

  private static MethodModel annotatedMethod(String name, String descriptor, String annotation,
      Map<String, Object> values, int access) {
    return new MethodModel(name, descriptor, null, access, List.of(),
        List.of(annotation), List.of(), List.of(), List.of(), 1, 2,
        new ControlFlowModel(0, List.of()),
        new IrMethodModel(name, descriptor, List.of(
            new IrOperationModel("RETURN", List.of(), 0))),
        List.of(new AnnotationData(annotation, true, values)));
  }

  private static FieldModel sidedProxy(String name, String descriptor, String implementation) {
    return new FieldModel(name, descriptor, null,
        Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, List.of(SIDED_PROXY), null,
        List.of(new AnnotationData(SIDED_PROXY, true,
            Map.of("clientSide", implementation, "serverSide", implementation))));
  }

  private static AnnotationData mod(String id, String dependencies) {
    return new AnnotationData(MOD, true, Map.of(
        "modid", id, "dependencies", dependencies, "version", "1.0"));
  }
}
