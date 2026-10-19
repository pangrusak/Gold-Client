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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.objectweb.asm.Opcodes;

/**
 * Emits the generic Forge 1.12.2 loader metadata discovered from original class
 * annotations. The generated module deliberately requires a real host adapter for
 * Forge events and injected fields; it never pretends plain JS objects are Forge.
 *
 * <p>The emitted dispatcher is phase-major, like FML: every discovered mod is
 * constructed before preInit, every preInit handler runs before any init handler,
 * etc. Static event-bus subscribers are registered before the first lifecycle
 * handler. Instance subscribers are not registered automatically; translated
 * mods become live when their code calls {@code MinecraftForge.EVENT_BUS.register}
 * and the runtime routes that call to the host bus.</p>
 */
public final class ForgeLifecycleExporter {
  private static final String MOD = "net.minecraftforge.fml.common.Mod";
  private static final String EVENT_HANDLER = "net.minecraftforge.fml.common.Mod$EventHandler";
  private static final String INSTANCE = "net.minecraftforge.fml.common.Mod$Instance";
  private static final String METADATA = "net.minecraftforge.fml.common.Mod$Metadata";
  private static final String INSTANCE_FACTORY = "net.minecraftforge.fml.common.Mod$InstanceFactory";
  private static final String SIDED_PROXY = "net.minecraftforge.fml.common.SidedProxy";
  private static final String EVENT_BUS_SUBSCRIBER =
      "net.minecraftforge.fml.common.Mod$EventBusSubscriber";
  private static final String SUBSCRIBE_EVENT =
      "net.minecraftforge.fml.common.eventhandler.SubscribeEvent";

  private static final List<Phase> PHASES = List.of(
      new Phase("preInit", "net.minecraftforge.fml.common.event.FMLPreInitializationEvent", true),
      new Phase("init", "net.minecraftforge.fml.common.event.FMLInitializationEvent", true),
      new Phase("postInit", "net.minecraftforge.fml.common.event.FMLPostInitializationEvent", true),
      new Phase("loadComplete", "net.minecraftforge.fml.common.event.FMLLoadCompleteEvent", true),
      new Phase("serverAboutToStart",
          "net.minecraftforge.fml.common.event.FMLServerAboutToStartEvent", false),
      new Phase("serverStarting",
          "net.minecraftforge.fml.common.event.FMLServerStartingEvent", false),
      new Phase("serverStarted",
          "net.minecraftforge.fml.common.event.FMLServerStartedEvent", false),
      new Phase("serverStopping",
          "net.minecraftforge.fml.common.event.FMLServerStoppingEvent", false),
      new Phase("serverStopped",
          "net.minecraftforge.fml.common.event.FMLServerStoppedEvent", false));

  public record Phase(String name, String eventType, boolean clientPhase) {}
  public record Handler(String method, String descriptor, String eventType) {}
  public record FieldInjection(String name, String descriptor, String kind, boolean isStatic,
      String targetModId, String implementation) {}
  public record Subscriber(String owner, String className, String method, String descriptor,
      String eventType, String priority, boolean receiveCanceled, boolean isStatic,
      List<String> sides) {
    public Subscriber {
      sides = List.copyOf(sides);
    }
  }
  public record ModEntry(String modId, String className, String name, String version,
      String dependencies, String acceptedMinecraftVersions, boolean clientSideOnly,
      boolean serverSideOnly, String instanceFactory, List<FieldInjection> injections,
      Map<String, List<Handler>> handlers, List<Subscriber> instanceSubscribers) {
    public ModEntry {
      injections = List.copyOf(injections);
      LinkedHashMap<String, List<Handler>> copy = new LinkedHashMap<>();
      handlers.forEach((phase, values) -> copy.put(phase, List.copyOf(values)));
      handlers = Map.copyOf(copy);
      instanceSubscribers = List.copyOf(instanceSubscribers);
    }
  }
  public record LifecycleReport(String formatVersion, String status, int discoveredModClasses,
      int lifecycleHandlers, int injectedFields, int discoveredEventSubscribers, String loadOrder,
      List<String> blockers, List<ModEntry> mods, List<Subscriber> staticSubscribers,
      List<String> hostRequirements) {
    public LifecycleReport {
      blockers = List.copyOf(blockers);
      mods = List.copyOf(mods);
      staticSubscribers = List.copyOf(staticSubscribers);
      hostRequirements = List.copyOf(hostRequirements);
    }
  }

  public LifecycleReport export(List<ClassModel> classes, Path outputDirectory) throws IOException {
    return export(classes, Map.of(), outputDirectory);
  }

  /**
   * Exports the lifecycle plan. {@code metadataModIds} maps an internal class name to
   * the mod ID declared by that class's JAR metadata ({@code mcmod.info}); it is used
   * only for {@code @Mod(useMetadata = true)} entries whose annotation has no literal
   * {@code modid}.
   */
  public LifecycleReport export(List<ClassModel> classes, Map<String, String> metadataModIds,
      Path outputDirectory) throws IOException {
    if (classes == null || outputDirectory == null) {
      throw new IllegalArgumentException("Classes and output directory must not be null.");
    }
    Map<String, String> metadataIds = metadataModIds == null ? Map.of() : metadataModIds;
    List<String> blockers = new ArrayList<>();
    Map<String, ClassModel> classesByName = indexClasses(classes, blockers);
    List<ModEntry> entries = new ArrayList<>();
    List<Subscriber> staticSubscribers = new ArrayList<>();
    Set<String> ids = new LinkedHashSet<>();

    for (ClassModel clazz : classes.stream().sorted(Comparator.comparing(ClassModel::name)).toList()) {
      if (!clazz.annotations().contains(MOD)) continue;
      AnnotationData modAnnotation = annotation(clazz.annotationData(), MOD);
      String id = stringValue(modAnnotation, "modid");
      boolean useMetadata = booleanValue(modAnnotation, "useMetadata");
      String metadataId = metadataIds.get(clazz.name());
      if (id == null || id.isBlank()) {
        if (useMetadata && metadataId != null && !metadataId.isBlank()) {
          id = metadataId;
        } else {
          String detail = metadataId == null || metadataId.isBlank()
              ? "" : " (mcmod.info declares " + metadataId + " but useMetadata is not true)";
          blockers.add("Missing literal @Mod(modid=...) on " + dotted(clazz.name()) + detail);
          id = dotted(clazz.name());
        }
      } else if (useMetadata && metadataId != null && !metadataId.isBlank()
          && !metadataId.equals(id)) {
        blockers.add("mcmod.info mod ID " + metadataId + " does not match @Mod(modid=" + id
            + ") on " + dotted(clazz.name()));
      }
      if (!id.matches("[a-z][a-z0-9_]{0,63}")) {
        blockers.add("Invalid Forge mod ID on " + dotted(clazz.name()) + ": " + id);
      }
      if (!ids.add(id)) blockers.add("Duplicate @Mod ID: " + id);
      entries.add(inspectMod(clazz, id, classesByName, blockers));
    }

    if (entries.isEmpty()) blockers.add("No Forge 1.12.2 @Mod entry class was found.");

    List<ModEntry> ordered = orderMods(entries, blockers);
    Set<String> knownIds = new LinkedHashSet<>();
    for (ModEntry mod : entries) knownIds.add(mod.modId());
    validateInstanceInjections(entries, knownIds, classesByName, blockers);

    for (ClassModel clazz : classes.stream().sorted(Comparator.comparing(ClassModel::name)).toList()) {
      AnnotationData registration = annotation(clazz.annotationData(), EVENT_BUS_SUBSCRIBER);
      if (registration == null) continue;
      String owner = stringValue(registration, "modid");
      if (owner == null || owner.isBlank()) {
        if (entries.size() == 1) owner = entries.get(0).modId();
        else {
          blockers.add("Multi-mod @Mod.EventBusSubscriber requires an explicit modid: "
              + dotted(clazz.name()));
          continue;
        }
      }
      if (!knownIds.contains(owner)) {
        blockers.add("@Mod.EventBusSubscriber references mod not present in this bundle: "
            + owner + " on " + dotted(clazz.name()));
        continue;
      }
      List<String> sides = enumConstants(registration, "value");
      if (!sides.isEmpty() && !sides.contains("CLIENT")) continue;
      for (MethodModel method : methodsWithInheritance(clazz, classesByName)) {
        if (!method.annotations().contains(SUBSCRIBE_EVENT)) continue;
        Subscriber subscriber = subscriber(owner, clazz, method, true, blockers);
        if (subscriber != null) staticSubscribers.add(subscriber);
      }
    }

    for (ModEntry mod : ordered) {
      List<Subscriber> instance = new ArrayList<>();
      ClassModel clazz = classesByName.get(mod.className().replace('.', '/'));
      if (clazz != null) {
        for (MethodModel method : methodsWithInheritance(clazz, classesByName)) {
          if (!method.annotations().contains(SUBSCRIBE_EVENT)) continue;
          if ((method.access() & Opcodes.ACC_STATIC) != 0) continue;
          Subscriber subscriber = subscriber(mod.modId(), clazz, method, false, blockers);
          if (subscriber != null) instance.add(subscriber);
        }
      }
      if (!instance.isEmpty()) {
        entries = replaceMod(entries, mod.modId(), new ModEntry(mod.modId(), mod.className(),
            mod.name(), mod.version(), mod.dependencies(), mod.acceptedMinecraftVersions(),
            mod.clientSideOnly(), mod.serverSideOnly(), mod.instanceFactory(), mod.injections(),
            mod.handlers(), instance));
      }
    }
    ordered = orderWithoutDuplicatingBlockers(entries, blockers);

    int handlerCount = ordered.stream().flatMap(mod -> mod.handlers().values().stream())
        .mapToInt(List::size).sum();
    int injectionCount = ordered.stream().mapToInt(mod -> mod.injections().size()).sum();
    int subscriberCount = staticSubscribers.size()
        + ordered.stream().mapToInt(mod -> mod.instanceSubscribers().size()).sum();
    String loadOrder = String.join(",", ordered.stream().map(ModEntry::modId).toList());
    String status = blockers.isEmpty() ? "READY_FOR_REAL_FORGE_HOST" : "PARTIAL_WITH_BLOCKERS";
    LifecycleReport report = new LifecycleReport("gold-forge-lifecycle-v2", status,
        ordered.size(), handlerCount, injectionCount, subscriberCount, loadOrder,
        List.copyOf(blockers), List.copyOf(ordered), List.copyOf(staticSubscribers),
        List.of("client-side Forge 1.12.2 lifecycle event creation/dispatch",
            "active-mod-container and mod-context binding",
            "@Mod.Instance and @Mod.Metadata injection",
            "configuration/resource/network/input/Minecraft API bindings",
            "phase-major FML lifecycle ordering and mod dependency ordering",
            "static @Mod.EventBusSubscriber registration",
            "runtime MinecraftForge.EVENT_BUS.register interception for instance @SubscribeEvent methods",
            "Minecraft/Forge class transformation, mixins and coremods"));
    Path root = outputDirectory.toAbsolutePath().normalize();
    Files.createDirectories(root);
    Files.writeString(root.resolve("forge-lifecycle-plan.json"),
        new GsonBuilder().setPrettyPrinting().create().toJson(report) + System.lineSeparator(),
        StandardCharsets.UTF_8);
    Files.writeString(root.resolve("forge-lifecycle.js"), javascript(report),
        StandardCharsets.UTF_8);
    return report;
  }

  private static ModEntry inspectMod(ClassModel clazz, String id,
      Map<String, ClassModel> classesByName, List<String> blockers) {
    if ((clazz.access() & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE)) != 0) {
      blockers.add("@Mod entry must be a concrete class: " + dotted(clazz.name()));
    }
    if (clazz.name().contains("$") && (clazz.access() & Opcodes.ACC_STATIC) == 0) {
      blockers.add("Non-static nested @Mod entry requires an outer instance: " + dotted(clazz.name()));
    }
    if (clazz.superName() != null && !internalName(clazz.superName()).equals("java/lang/Object")
        && !classesByName.containsKey(internalName(clazz.superName()))) {
      blockers.add("Mod superclass is absent from the translation bundle; inherited lifecycle "
          + "discovery is incomplete: " + dotted(clazz.name()) + " extends " + dotted(clazz.superName()));
    }

    AnnotationData modAnnotation = annotation(clazz.annotationData(), MOD);
    String version = valueOrEmpty(stringValue(modAnnotation, "version"));
    String dependencies = valueOrEmpty(stringValue(modAnnotation, "dependencies"));
    String accepted = valueOrEmpty(stringValue(modAnnotation, "acceptedMinecraftVersions"));
    boolean clientSideOnly = booleanValue(modAnnotation, "clientSideOnly");
    boolean serverSideOnly = booleanValue(modAnnotation, "serverSideOnly");
    if (serverSideOnly) {
      blockers.add("Server-only mod cannot be loaded in a client-side Forge host: " + id);
    }

    List<FieldInjection> injections = new ArrayList<>();
    for (FieldModel field : clazz.fields()) {
      if (field.annotations().contains(INSTANCE)) {
        String requested = valueOrEmpty(stringValue(annotation(field.annotationData(), INSTANCE),
            "value"));
        String target = requested.isBlank() ? id : requested;
        if (!field.descriptor().startsWith("L") || !field.descriptor().endsWith(";")) {
          blockers.add("@Mod.Instance field must be a reference type: " + dotted(clazz.name())
              + "." + field.name());
        } else {
          injections.add(new FieldInjection(field.name(), field.descriptor(), "instance",
              (field.access() & Opcodes.ACC_STATIC) != 0, target, null));
        }
      }
      if (field.annotations().contains(METADATA)) {
        if (!"Lnet/minecraftforge/fml/common/ModMetadata;".equals(field.descriptor())) {
          blockers.add("@Mod.Metadata field must use Forge ModMetadata: " + dotted(clazz.name())
              + "." + field.name() + " " + field.descriptor());
        } else {
          injections.add(new FieldInjection(field.name(), field.descriptor(), "metadata",
              (field.access() & Opcodes.ACC_STATIC) != 0, id, null));
        }
      }
      if (field.annotations().contains(SIDED_PROXY)) {
        FieldInjection proxy = inspectProxy(clazz, field, id, classesByName, blockers);
        if (proxy != null) injections.add(proxy);
      }
    }

    Map<String, List<Handler>> handlers = new LinkedHashMap<>();
    for (Phase phase : PHASES) handlers.put(phase.name(), new ArrayList<>());
    for (MethodModel method : methodsWithInheritance(clazz, classesByName)) {
      if (!method.annotations().contains(EVENT_HANDLER)) continue;
      Phase phase = phaseForDescriptor(method.descriptor());
      if (phase == null || (method.access() & Opcodes.ACC_STATIC) != 0
          || (method.access() & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0
          || !isJavaScriptIdentifier(method.name())) {
        blockers.add("Unsupported @Mod.EventHandler signature: " + dotted(clazz.name()) + "."
            + method.name() + method.descriptor());
        continue;
      }
      handlers.get(phase.name()).add(new Handler(method.name(), method.descriptor(),
          phase.eventType()));
    }

    String instanceFactory = instanceFactory(clazz, classesByName, blockers);
    List<MethodModel> allMethods = methodsWithInheritance(clazz, classesByName);
    boolean hasAnyConstructor = allMethods.stream()
        .anyMatch(method -> method.name().equals("<init>"));
    boolean hasZeroArgConstructor = allMethods.stream()
        .anyMatch(method -> method.name().equals("<init>")
            && method.descriptor().equals("()V")
            && (method.access() & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) == 0);
    if (instanceFactory == null && hasAnyConstructor && !hasZeroArgConstructor) {
      blockers.add("Mod has neither a no-argument constructor nor an @Mod.InstanceFactory: "
          + dotted(clazz.name()));
    }

    return new ModEntry(id, dotted(clazz.name()), id, version, dependencies, accepted,
        clientSideOnly, serverSideOnly, instanceFactory, injections, handlers, List.of());
  }

  private static FieldInjection inspectProxy(ClassModel clazz, FieldModel field, String modId,
      Map<String, ClassModel> classesByName, List<String> blockers) {
    AnnotationData annotation = annotation(field.annotationData(), SIDED_PROXY);
    String clientSide = valueOrEmpty(stringValue(annotation, "clientSide"));
    String requestedMod = valueOrEmpty(stringValue(annotation, "modId"));
    if (!requestedMod.isBlank() && !requestedMod.equals(modId)) {
      blockers.add("@SidedProxy references another mod: " + dotted(clazz.name()) + "."
          + field.name() + " -> " + requestedMod);
      return null;
    }
    if (clientSide.isBlank()) {
      blockers.add("@SidedProxy has no clientSide implementation for a client-side load: "
          + dotted(clazz.name()) + "." + field.name());
      return null;
    }
    String implementation = clientSide.replace('.', '/');
    ClassModel proxy = classesByName.get(implementation);
    if (proxy == null) {
      blockers.add("@SidedProxy implementation is absent from the translation bundle: "
          + clientSide + " for " + dotted(clazz.name()) + "." + field.name());
      return null;
    }
    if ((proxy.access() & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE)) != 0) {
      blockers.add("@SidedProxy implementation must be concrete: " + clientSide);
      return null;
    }
    if (!field.descriptor().startsWith("L") || !field.descriptor().endsWith(";")) {
      blockers.add("@SidedProxy field must be a reference type: " + dotted(clazz.name()) + "."
          + field.name());
      return null;
    }
    String declared = field.descriptor().substring(1, field.descriptor().length() - 1);
    if (!isAssignable(implementation, declared, classesByName)) {
      blockers.add("@SidedProxy implementation is not assignable to field type: " + clientSide
          + " -> " + dotted(declared));
      return null;
    }
    return new FieldInjection(field.name(), field.descriptor(), "proxy",
        (field.access() & Opcodes.ACC_STATIC) != 0, modId, dotted(implementation));
  }

  private static String instanceFactory(ClassModel clazz, Map<String, ClassModel> classesByName,
      List<String> blockers) {
    String found = null;
    for (MethodModel method : methodsWithInheritance(clazz, classesByName)) {
      if (!method.annotations().contains(INSTANCE_FACTORY)) continue;
      if (found != null) {
        blockers.add("Multiple @Mod.InstanceFactory methods: " + dotted(clazz.name()) + "."
            + found + " and " + method.name());
        continue;
      }
      if ((method.access() & Opcodes.ACC_STATIC) == 0
          || (method.access() & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0
          || !method.descriptor().startsWith("()L")
          || !method.descriptor().endsWith(";")
          || !isJavaScriptIdentifier(method.name())) {
        blockers.add("Unsupported @Mod.InstanceFactory signature: " + dotted(clazz.name()) + "."
            + method.name() + method.descriptor());
        continue;
      }
      String returned = method.descriptor().substring(2, method.descriptor().length() - 1);
      if (!isAssignable(returned, clazz.name(), classesByName)) {
        blockers.add("@Mod.InstanceFactory must return the mod class: " + dotted(clazz.name())
            + "." + method.name() + " -> " + dotted(returned));
        continue;
      }
      found = method.name();
    }
    return found;
  }

  private static Subscriber subscriber(String owner, ClassModel ownerClass, MethodModel method,
      boolean staticRequired, List<String> blockers) {
    if (staticRequired && (method.access() & Opcodes.ACC_STATIC) == 0) {
      blockers.add("@Mod.EventBusSubscriber method must be static: " + dotted(ownerClass.name())
          + "." + method.name());
      return null;
    }
    if (staticRequired && (method.access() & Opcodes.ACC_PRIVATE) != 0) {
      blockers.add("@Mod.EventBusSubscriber method must be visible to the loader: "
          + dotted(ownerClass.name()) + "." + method.name());
      return null;
    }
    if ((method.access() & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
      blockers.add("Subscriber method has no executable body: " + dotted(ownerClass.name())
          + "." + method.name());
      return null;
    }
    if (!method.descriptor().startsWith("(L") || !method.descriptor().endsWith(";)V")
        || !isJavaScriptIdentifier(method.name())) {
      blockers.add("Unsupported @SubscribeEvent signature: " + dotted(ownerClass.name()) + "."
          + method.name() + method.descriptor());
      return null;
    }
    String eventType = method.descriptor().substring(2, method.descriptor().length() - 3)
        .replace('/', '.');
    AnnotationData annotation = annotation(method.annotationData(), SUBSCRIBE_EVENT);
    String priority = "NORMAL";
    List<String> configuredPriority = enumConstants(annotation, "priority");
    if (!configuredPriority.isEmpty()) {
      String value = configuredPriority.get(0);
      if (!Set.of("HIGHEST", "HIGH", "NORMAL", "LOW", "LOWEST").contains(value)) {
        blockers.add("Unsupported @SubscribeEvent priority: " + value + " on "
            + dotted(ownerClass.name()) + "." + method.name());
        return null;
      }
      priority = value;
    }
    boolean receiveCanceled = booleanValue(annotation, "receiveCanceled");
    return new Subscriber(owner, dotted(ownerClass.name()), method.name(), method.descriptor(),
        eventType, priority, receiveCanceled, staticRequired, List.of());
  }

  private static void validateInstanceInjections(List<ModEntry> mods, Set<String> knownIds,
      Map<String, ClassModel> classesByName, List<String> blockers) {
    Map<String, ModEntry> byId = new HashMap<>();
    for (ModEntry mod : mods) byId.put(mod.modId(), mod);
    for (ModEntry mod : mods) {
      for (FieldInjection field : mod.injections()) {
        if (!"instance".equals(field.kind())) continue;
        ModEntry target = byId.get(field.targetModId());
        if (target == null) {
          blockers.add("@Mod.Instance target is not present in this bundle: " + mod.modId()
              + "." + field.name() + " -> " + field.targetModId());
          continue;
        }
        String declared = field.descriptor().substring(1, field.descriptor().length() - 1);
        if (!isAssignable(internalName(target.className()), declared, classesByName)) {
          blockers.add("@Mod.Instance field type is not assignable from the target mod class: "
              + mod.modId() + "." + field.name() + " <- " + target.className());
        }
      }
    }
  }

  private static List<ModEntry> orderMods(List<ModEntry> entries, List<String> blockers) {
    ForgeDependencyGraph.Plan plan;
    try {
      List<ForgeDependencyGraph.Mod> mods = entries.stream()
          .map(mod -> new ForgeDependencyGraph.Mod(mod.modId(), mod.version(), mod.dependencies()))
          .toList();
      plan = ForgeDependencyGraph.plan(mods);
    } catch (IllegalArgumentException error) {
      blockers.add("Forge dependency metadata is invalid: " + error.getMessage());
      return entries;
    }
    blockers.addAll(plan.blockers());
    if (!plan.ready()) return entries;
    Map<String, ModEntry> byId = new HashMap<>();
    for (ModEntry entry : entries) byId.put(entry.modId(), entry);
    return plan.ordered().stream().map(mod -> byId.get(mod.id())).toList();
  }

  private static List<ModEntry> orderWithoutDuplicatingBlockers(List<ModEntry> entries,
      List<String> blockers) {
    List<String> ignored = new ArrayList<>();
    return orderMods(entries, ignored);
  }

  private static List<ModEntry> replaceMod(List<ModEntry> entries, String modId, ModEntry value) {
    List<ModEntry> result = new ArrayList<>(entries);
    for (int i = 0; i < result.size(); i++) {
      if (result.get(i).modId().equals(modId)) {
        result.set(i, value);
        return result;
      }
    }
    result.add(value);
    return result;
  }

  private static Map<String, ClassModel> indexClasses(List<ClassModel> classes,
      List<String> blockers) {
    Map<String, ClassModel> result = new LinkedHashMap<>();
    for (ClassModel clazz : classes) {
      if (result.putIfAbsent(clazz.name(), clazz) != null) {
        blockers.add("Duplicate class definition in translation bundle: " + dotted(clazz.name()));
      }
    }
    return result;
  }

  private static List<MethodModel> methodsWithInheritance(ClassModel clazz,
      Map<String, ClassModel> classesByName) {
    Map<String, MethodModel> methods = new LinkedHashMap<>();
    Set<String> visited = new LinkedHashSet<>();
    ClassModel cursor = clazz;
    while (cursor != null && visited.add(cursor.name())) {
      for (MethodModel method : cursor.methods()) {
        methods.putIfAbsent(method.name() + method.descriptor(), method);
      }
      cursor = cursor.superName() == null ? null : classesByName.get(internalName(cursor.superName()));
    }
    return List.copyOf(methods.values());
  }

  private static boolean isAssignable(String from, String to, Map<String, ClassModel> classes) {
    String target = internalName(to);
    List<String> pending = new ArrayList<>();
    pending.add(internalName(from));
    Set<String> visited = new HashSet<>();
    while (!pending.isEmpty()) {
      String cursor = pending.remove(pending.size() - 1);
      if (cursor == null || !visited.add(cursor)) continue;
      if (cursor.equals(target)) return true;
      ClassModel model = classes.get(cursor);
      if (model == null) continue;
      if (model.superName() != null) pending.add(internalName(model.superName()));
      for (String implemented : model.interfaces()) pending.add(internalName(implemented));
    }
    return false;
  }

  private static Phase phaseForDescriptor(String descriptor) {
    for (Phase phase : PHASES) {
      if (descriptor.equals("(L" + phase.eventType().replace('.', '/') + ";)V")) return phase;
    }
    return null;
  }

  private static AnnotationData annotation(List<AnnotationData> annotations, String name) {
    for (AnnotationData annotation : annotations) {
      if (annotation.name().equals(name)) return annotation;
    }
    return null;
  }

  private static String stringValue(AnnotationData annotation, String key) {
    if (annotation == null) return null;
    Object value = annotation.values().get(key);
    return value instanceof String string ? string : null;
  }

  private static boolean booleanValue(AnnotationData annotation, String key) {
    if (annotation == null) return false;
    Object value = annotation.values().get(key);
    if (value instanceof Boolean bool) return bool;
    if (value instanceof Number number) return number.intValue() != 0;
    return false;
  }

  private static List<String> enumConstants(AnnotationData annotation, String key) {
    if (annotation == null) return List.of();
    Object value = annotation.values().get(key);
    if (value instanceof AnnotationData.EnumValue enumValue) {
      return List.of(enumValue.constant());
    }
    if (value instanceof List<?> values) {
      List<String> result = new ArrayList<>();
      for (Object item : values) {
        if (item instanceof AnnotationData.EnumValue enumValue) result.add(enumValue.constant());
      }
      return List.copyOf(result);
    }
    return List.of();
  }

  private static String valueOrEmpty(String value) {
    return value == null ? "" : value;
  }

  private static String dotted(String name) {
    return name == null ? null : name.replace('/', '.');
  }

  private static String internalName(String name) {
    return name == null ? null : name.replace('.', '/');
  }

  private static boolean isJavaScriptIdentifier(String value) {
    return value != null && value.matches("[A-Za-z_$][A-Za-z0-9_$]*")
        && !Set.of("await", "break", "case", "catch", "class", "const", "continue", "debugger",
            "default", "delete", "do", "else", "export", "extends", "finally", "for", "function",
            "if", "import", "in", "instanceof", "new", "return", "super", "switch", "this",
            "throw", "try", "typeof", "var", "void", "while", "with", "yield", "enum",
            "implements", "interface", "let", "package", "private", "protected", "public",
            "static").contains(value);
  }

  private static String javascript(LifecycleReport report) {
    GsonBuilder gson = new GsonBuilder().disableHtmlEscaping();
    String data = gson.create().toJson(report.mods());
    String staticSubscribers = gson.create().toJson(report.staticSubscribers());
    String blockers = gson.create().toJson(report.blockers());
    String clientPhases = gson.create().toJson(PHASES.stream()
        .filter(Phase::clientPhase)
        .map(phase -> Map.of("name", phase.name(), "eventType", phase.eventType()))
        .toList());
    String serverPhases = gson.create().toJson(PHASES.stream()
        .filter(phase -> !phase.clientPhase())
        .map(phase -> Map.of("name", phase.name(), "eventType", phase.eventType()))
        .toList());
    return """
        // Generated by Gold Client from original Forge 1.12.2 annotations.
        // This module is a loader/dispatcher, not an implementation of Forge.
        import { GoldClientRuntime } from "./gold-client-runtime.js";
        import { createForgeHost } from "./forge-host-runtime.js";
        import { classes as translatedClasses } from "./mod-index.js";
        void translatedClasses; // Importing the index evaluates and registers every translated class.

        export const forgeMods = Object.freeze(%s);
        export const forgeStaticSubscribers = Object.freeze(%s);
        export const forgeLifecycleBlockers = Object.freeze(%s);
        export const forgeLifecyclePhases = Object.freeze(%s);
        export const forgeServerLifecyclePhases = Object.freeze(%s);

        let goldClientForgeStarted = false;
        let goldClientForgeServerStarted = false;
        let goldClientForgeInstances = new Map();

        function requireHost(host) {
          if (!host || typeof host !== "object") {
            throw new TypeError("Gold Client requires a real Forge host adapter.");
          }
          for (const method of ["beforeConstruction", "injectFields", "afterConstruction",
                                "createEvent", "afterPhase", "registerSubscriber", "stopMod"]) {
            if (typeof host[method] !== "function") {
              throw new Error("Forge host adapter is missing required method: " + method);
            }
          }
          if (forgeLifecycleBlockers.length) {
            throw new Error("Forge lifecycle plan has blockers: " + forgeLifecycleBlockers.join("; "));
          }
        }

        function requireClass(name, label) {
          const resolved = GoldClientRuntime.resolveClass(name);
          if (!resolved) throw new Error(label + " was not translated/registered: " + name);
          return resolved;
        }

        function instantiateMod(mod, ModClass) {
          if (mod.instanceFactory) {
            const factory = ModClass[mod.instanceFactory];
            if (typeof factory !== "function") {
              throw new Error("Translated @Mod.InstanceFactory is missing: "
                + mod.className + "." + mod.instanceFactory);
            }
            return factory.call(ModClass);
          }
          return new ModClass();
        }

        function staticSubscriberCallback(subscriber) {
          const Owner = requireClass(subscriber.className, "Static subscriber class");
          const callback = Owner[subscriber.method];
          if (typeof callback !== "function") {
            throw new Error("Translated static subscriber is missing: "
              + subscriber.className + "." + subscriber.method);
          }
          return event => callback.call(Owner, event);
        }

        function enterForgeMod(mod) {
          globalThis.goldClientCurrentForgeModId = mod.modId;
        }

        function leaveForgeMod() {
          delete globalThis.goldClientCurrentForgeModId;
        }

        async function dispatchPhase(host, mod, phase, instance, instances) {
          const event = await host.createEvent(mod, phase);
          for (const handler of mod.handlers[phase.name] ?? []) {
            const callback = instance[handler.method];
            if (typeof callback !== "function") {
              throw new Error("Translated lifecycle handler is missing: "
                + mod.className + "." + handler.method + handler.descriptor);
            }
            await callback.call(instance, event);
          }
          await host.afterPhase(mod, phase, instance, instances);
        }

        /** Runs original translated @Mod constructors and lifecycle handlers in FML phase order. */
        export async function startForgeMods(host) {
          requireHost(host);
          if (goldClientForgeStarted) throw new Error("Forge mods are already started.");
          goldClientForgeStarted = true;
          const instances = new Map();
          try {
            // FML constructs every mod first, in dependency order.
            for (const mod of forgeMods) {
              const ModClass = GoldClientRuntime.resolveClass(mod.className);
              if (!ModClass) throw new Error("@Mod class was not translated/registered: " + mod.className);
              enterForgeMod(mod);
              await host.beforeConstruction(mod);
              const instance = instantiateMod(mod, ModClass);
              if (!instance || (typeof instance !== "object" && typeof instance !== "function")) {
                throw new Error("@Mod construction did not return an object: " + mod.modId);
              }
              instances.set(mod.modId, instance);
            }
            // All instances exist before cross-mod @Mod.Instance and proxy injection.
            for (const mod of forgeMods) {
              const instance = instances.get(mod.modId);
              enterForgeMod(mod);
              await host.injectFields(mod, instance, GoldClientRuntime, instances);
              await host.afterConstruction(mod, instance);
            }
            // Static subscribers are live before preInit, matching FML registration order.
            for (const subscriber of forgeStaticSubscribers) {
              globalThis.goldClientCurrentForgeModId = subscriber.owner;
              await host.registerSubscriber({ modId: subscriber.owner },
                subscriber, staticSubscriberCallback(subscriber));
            }
            // FML does not auto-register arbitrary instance @SubscribeEvent methods.
            // Translated mods become live when their own code calls
            // MinecraftForge.EVENT_BUS.register(...); GoldClientRuntime routes that
            // call to the same host bus with the current mod as the cleanup owner.
            // Phase-major: every preInit before every init, etc.
            for (const phase of forgeLifecyclePhases) {
              for (const mod of forgeMods) {
                enterForgeMod(mod);
                await dispatchPhase(host, mod, phase, instances.get(mod.modId), instances);
              }
            }
            goldClientForgeInstances = instances;
            return Object.freeze(forgeMods.map(mod => Object.freeze({
              modId: mod.modId, className: mod.className, instance: instances.get(mod.modId)
            })));
          } catch (error) {
            goldClientForgeStarted = false;
            goldClientForgeInstances = new Map();
            throw error;
          } finally {
            leaveForgeMod();
          }
        }

        /** Dispatches the FML server lifecycle after the integrated server starts. */
        export async function startForgeServerLifecycle(host) {
          requireHost(host);
          if (!goldClientForgeStarted) {
            throw new Error("Forge mods must be started before the server lifecycle.");
          }
          if (goldClientForgeServerStarted) {
            throw new Error("Forge server lifecycle is already started.");
          }
          goldClientForgeServerStarted = true;
          try {
            for (const phase of forgeServerLifecyclePhases) {
              for (const mod of forgeMods) {
                enterForgeMod(mod);
                await dispatchPhase(host, mod, phase,
                  goldClientForgeInstances.get(mod.modId), goldClientForgeInstances);
              }
            }
            return Object.freeze(forgeMods.map(mod => mod.modId));
          } catch (error) {
            goldClientForgeServerStarted = false;
            throw error;
          } finally {
            leaveForgeMod();
          }
        }

        /** Stops mods in reverse dependency order and clears owner-scoped bus/registry state. */
        export async function stopForgeMods(host, reason = "stopForgeMods") {
          requireHost(host);
          if (!goldClientForgeStarted) return Object.freeze([]);
          const stopped = [];
          for (let i = forgeMods.length - 1; i >= 0; i--) {
            const mod = forgeMods[i];
            if (await host.stopMod(mod.modId, reason)) stopped.push(mod.modId);
          }
          goldClientForgeStarted = false;
          goldClientForgeServerStarted = false;
          goldClientForgeInstances = new Map();
          return Object.freeze(stopped);
        }

        export function forgeLoaderStatus() {
          return Object.freeze({
            formatVersion: "gold-forge-loader-v1",
            started: goldClientForgeStarted,
            serverStarted: goldClientForgeServerStarted,
            modCount: forgeMods.length,
            loadOrder: forgeMods.map(mod => mod.modId),
            blockers: forgeLifecycleBlockers.slice(),
            staticSubscriberCount: forgeStaticSubscribers.length,
            discoveredInstanceSubscriberCount: forgeMods.reduce(
              (count, mod) => count + (mod.instanceSubscribers?.length ?? 0), 0),
            manualEventBusRegistration: true
          });
        }

        /** Starts with the strict host runtime; bindings must connect real target-side APIs. */
        export async function startForgeModsWithBindings(bindings) {
          return startForgeMods(createForgeHost(bindings));
        }
        """.formatted(data, staticSubscribers, blockers, clientPhases, serverPhases);
  }
}
