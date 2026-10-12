package com.goldclient.translator;

import com.goldclient.model.AnnotationData;
import com.goldclient.model.ClassModel;
import com.goldclient.model.FieldModel;
import com.goldclient.model.MethodModel;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.objectweb.asm.Opcodes;

/**
 * Emits the generic Forge 1.12.2 lifecycle wiring discovered from original class
 * annotations. The generated module deliberately requires a real host adapter for
 * Forge events and injected fields; it never pretends plain JS objects are Forge.
 */
public final class ForgeLifecycleExporter {
  private static final String MOD = "net.minecraftforge.fml.common.Mod";
  private static final String EVENT_HANDLER = "net.minecraftforge.fml.common.Mod$EventHandler";
  private static final String INSTANCE = "net.minecraftforge.fml.common.Mod$Instance";
  private static final String METADATA = "net.minecraftforge.fml.common.Mod$Metadata";

  private static final List<Phase> PHASES = List.of(
      new Phase("preInit", "net.minecraftforge.fml.common.event.FMLPreInitializationEvent"),
      new Phase("init", "net.minecraftforge.fml.common.event.FMLInitializationEvent"),
      new Phase("postInit", "net.minecraftforge.fml.common.event.FMLPostInitializationEvent"),
      new Phase("loadComplete", "net.minecraftforge.fml.common.event.FMLLoadCompleteEvent"));

  public record Phase(String name, String eventType) {}
  public record Handler(String method, String descriptor, String eventType) {}
  public record FieldInjection(String name, String descriptor, String kind, boolean isStatic) {}
  public record ModEntry(String modId, String className, List<FieldInjection> injections,
                         Map<String, List<Handler>> handlers) {}
  public record LifecycleReport(String formatVersion, String status, int discoveredModClasses,
      int lifecycleHandlers, int injectedFields, List<String> blockers, List<ModEntry> mods,
      List<String> hostRequirements) {}

  public LifecycleReport export(List<ClassModel> classes, Path outputDirectory) throws IOException {
    if (classes == null || outputDirectory == null) {
      throw new IllegalArgumentException("Classes and output directory must not be null.");
    }
    List<String> blockers = new ArrayList<>();
    List<ModEntry> mods = new ArrayList<>();
    Set<String> ids = new LinkedHashSet<>();
    for (ClassModel clazz : classes.stream().sorted(Comparator.comparing(ClassModel::name)).toList()) {
      if (!clazz.annotations().contains(MOD)) continue;
      String id = annotationString(clazz.annotationData(), MOD, "modid");
      if (id == null || id.isBlank()) {
        blockers.add("Missing literal @Mod(modid=...) on " + clazz.name());
        id = clazz.name().replace('/', '.');
      }
      if (!ids.add(id)) blockers.add("Duplicate @Mod ID: " + id);
      List<FieldInjection> injections = new ArrayList<>();
      for (FieldModel field : clazz.fields()) {
        if (field.annotations().contains(INSTANCE)) {
          injections.add(new FieldInjection(field.name(), field.descriptor(), "instance",
              (field.access() & Opcodes.ACC_STATIC) != 0));
        }
        if (field.annotations().contains(METADATA)) {
          injections.add(new FieldInjection(field.name(), field.descriptor(), "metadata",
              (field.access() & Opcodes.ACC_STATIC) != 0));
        }
      }
      Map<String, List<Handler>> handlers = new LinkedHashMap<>();
      for (Phase phase : PHASES) handlers.put(phase.name(), new ArrayList<>());
      for (MethodModel method : clazz.methods()) {
        if (!method.annotations().contains(EVENT_HANDLER)) continue;
        Phase phase = phaseForDescriptor(method.descriptor());
        if (phase == null || (method.access() & Opcodes.ACC_STATIC) != 0
            || (method.access() & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
          blockers.add("Unsupported @Mod.EventHandler signature: " + clazz.name() + "."
              + method.name() + method.descriptor());
          continue;
        }
        handlers.get(phase.name()).add(new Handler(method.name(), method.descriptor(), phase.eventType()));
      }
      Map<String, List<Handler>> immutableHandlers = new LinkedHashMap<>();
      handlers.forEach((phase, values) -> immutableHandlers.put(phase, List.copyOf(values)));
      mods.add(new ModEntry(id, clazz.name().replace('/', '.'), List.copyOf(injections),
          Map.copyOf(immutableHandlers)));
    }
    if (mods.isEmpty()) blockers.add("No Forge 1.12.2 @Mod entry class was found.");
    for (ModEntry mod : mods) {
      if (mod.className().contains("$")) {
        blockers.add("Nested @Mod entry class needs an outer-instance-aware constructor: " + mod.className());
      }
    }

    int handlerCount = mods.stream().flatMap(mod -> mod.handlers().values().stream())
        .mapToInt(List::size).sum();
    int injectionCount = mods.stream().mapToInt(mod -> mod.injections().size()).sum();
    String status = blockers.isEmpty() ? "READY_FOR_REAL_FORGE_HOST" : "PARTIAL_WITH_BLOCKERS";
    LifecycleReport report = new LifecycleReport("gold-forge-lifecycle-v1", status,
        mods.size(), handlerCount, injectionCount, List.copyOf(blockers), List.copyOf(mods),
        List.of("client-side Forge 1.12.2 lifecycle event creation/dispatch",
            "active-mod-container and mod-context binding",
            "@Mod.Instance and @Mod.Metadata injection",
            "configuration/resource/network/input/Minecraft API bindings",
            "Forge static event-bus subscriber registration where used"));
    Path root = outputDirectory.toAbsolutePath().normalize();
    Files.createDirectories(root);
    Files.writeString(root.resolve("forge-lifecycle-plan.json"),
        new GsonBuilder().setPrettyPrinting().create().toJson(report) + System.lineSeparator(),
        StandardCharsets.UTF_8);
    Files.writeString(root.resolve("forge-lifecycle.js"), javascript(report),
        StandardCharsets.UTF_8);
    return report;
  }

  private static Phase phaseForDescriptor(String descriptor) {
    for (Phase phase : PHASES) {
      if (descriptor.equals("(L" + phase.eventType().replace('.', '/') + ";)V")) return phase;
    }
    return null;
  }

  private static String annotationString(List<AnnotationData> annotations,
      String annotationName, String key) {
    for (AnnotationData annotation : annotations) {
      if (!annotation.name().equals(annotationName)) continue;
      Object value = annotation.values().get(key);
      if (value instanceof String string) return string;
    }
    return null;
  }

  private static String javascript(LifecycleReport report) {
    String data = new GsonBuilder().disableHtmlEscaping().create().toJson(report.mods());
    String blockers = new GsonBuilder().disableHtmlEscaping().create().toJson(report.blockers());
    return """
        // Generated by Gold Client from original Forge 1.12.2 annotations.
        // This module is a lifecycle dispatcher, not an implementation of Forge.
        import { GoldClientRuntime } from "./gold-client-runtime.js";
        import { classes as translatedClasses } from "./mod-index.js";
        void translatedClasses; // Importing the index evaluates and registers every translated class.

        export const forgeMods = Object.freeze(%s);
        export const forgeLifecycleBlockers = Object.freeze(%s);
        export const forgeLifecyclePhases = Object.freeze([
          { name: "preInit", eventType: "net.minecraftforge.fml.common.event.FMLPreInitializationEvent" },
          { name: "init", eventType: "net.minecraftforge.fml.common.event.FMLInitializationEvent" },
          { name: "postInit", eventType: "net.minecraftforge.fml.common.event.FMLPostInitializationEvent" },
          { name: "loadComplete", eventType: "net.minecraftforge.fml.common.event.FMLLoadCompleteEvent" }
        ]);

        function requireHost(host) {
          if (!host || typeof host !== "object") {
            throw new TypeError("Gold Client requires a real Forge host adapter.");
          }
          for (const method of ["beforeConstruction", "injectFields", "afterConstruction",
                                "createEvent", "afterPhase"]) {
            if (typeof host[method] !== "function") {
              throw new Error("Forge host adapter is missing required method: " + method);
            }
          }
          if (forgeLifecycleBlockers.length) {
            throw new Error("Forge lifecycle plan has blockers: " + forgeLifecycleBlockers.join("; "));
          }
        }

        /** Runs original translated @Mod constructors and annotated lifecycle handlers in phase order. */
        export async function startForgeMods(host) {
          requireHost(host);
          const started = [];
          for (const mod of forgeMods) {
            const ModClass = GoldClientRuntime.resolveClass(mod.className);
            if (!ModClass) {
              throw new Error("Translated @Mod class was not registered: " + mod.className);
            }
            await host.beforeConstruction(mod);
            const instance = new ModClass();
            await host.injectFields(mod, instance, GoldClientRuntime);
            await host.afterConstruction(mod, instance);
            for (const phase of forgeLifecyclePhases) {
              const event = await host.createEvent(mod, phase);
              const handlers = mod.handlers[phase.name] ?? [];
              for (const handler of handlers) {
                const callback = instance[handler.method];
                if (typeof callback !== "function") {
                  throw new Error("Translated lifecycle handler is missing: "
                    + mod.className + "." + handler.method + handler.descriptor);
                }
                await callback.call(instance, event);
              }
              await host.afterPhase(mod, phase, instance);
            }
            started.push({ modId: mod.modId, className: mod.className, instance });
          }
          return started;
        }
        """.formatted(data, blockers);
  }
}
