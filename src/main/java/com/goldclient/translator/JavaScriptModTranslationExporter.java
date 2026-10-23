package com.goldclient.translator;

import com.goldclient.model.AnnotationData;
import com.goldclient.model.ClassModel;
import com.goldclient.model.FieldModel;
import com.goldclient.model.MethodModel;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.objectweb.asm.Opcodes;

/** Writes per-class JavaScript modules and a separate honest JS completeness report. */
public final class JavaScriptModTranslationExporter {
  private final JavaScriptTranslator translator;

  public JavaScriptModTranslationExporter() {
    this(new JavaScriptTranslator());
  }

  JavaScriptModTranslationExporter(JavaScriptTranslator translator) {
    this.translator = translator;
  }

  public ExportReport export(List<ClassModel> classes, Path outputDirectory) throws IOException {
    return export((List<Path>) null, classes, outputDirectory);
  }

  public ExportReport export(Path inputJar, List<ClassModel> classes, Path outputDirectory) throws IOException {
    return export(inputJar == null ? List.of() : List.of(inputJar), classes, outputDirectory);
  }

  /**
   * Exports one JavaScript bundle from several input JARs. All classes share one
   * runtime and one class registry, which is required for a multi-mod Forge load.
   * Duplicate class definitions are rejected by the loader command before this
   * method is called.
   */
  public ExportReport export(List<Path> inputJars, List<ClassModel> classes,
      Path outputDirectory) throws IOException {
    return export(inputJars, classes, Map.of(), outputDirectory);
  }

  /**
   * Exports one bundle and carries per-class metadata mod IDs (from {@code mcmod.info})
   * so a {@code @Mod(useMetadata = true)} entry can resolve its ID from the JAR metadata.
   */
  public ExportReport export(List<Path> inputJars, List<ClassModel> classes,
      Map<String, String> metadataModIds, Path outputDirectory) throws IOException {
    return export(inputJars, classes, metadataModIds, Set.of(), outputDirectory);
  }

  /**
   * Exports a bundle while keeping the listed classes out of Forge lifecycle
   * discovery. This is used for {@code --library} inputs: their classes must be
   * translated and registered, but a library JAR's {@code @Mod} entry point is
   * not part of the game's load order.
   */
  public ExportReport export(List<Path> inputJars, List<ClassModel> classes,
      Map<String, String> metadataModIds, Set<String> excludedLifecycleClasses,
      Path outputDirectory) throws IOException {
    return export(inputJars, classes, metadataModIds, excludedLifecycleClasses,
        Set.of(), outputDirectory);
  }

  /**
   * Exports a bundle while treating {@code suppliedModIds} as present for
   * dependency resolution even though their library JARs have no lifecycle
   * entry. The supplied classes still translate and register normally.
   */
  public ExportReport export(List<Path> inputJars, List<ClassModel> classes,
      Map<String, String> metadataModIds, Set<String> excludedLifecycleClasses,
      Set<String> suppliedModIds, Path outputDirectory) throws IOException {
    if (classes == null) throw new IllegalArgumentException("Classes must not be null.");
    if (outputDirectory == null) throw new IllegalArgumentException("Output directory must not be null.");
    List<Path> jars = inputJars == null ? List.of() : inputJars.stream()
        .map(path -> path.toAbsolutePath().normalize()).toList();
    Path root = outputDirectory.toAbsolutePath().normalize();
    Files.createDirectories(root);
    List<MethodStatus> statuses = new ArrayList<>();
    int filesWritten = 0;

    for (ClassModel clazz : classes.stream().sorted(Comparator.comparing(ClassModel::name)).toList()) {
      Path destination = outputPath(root, clazz.name());
      Files.createDirectories(destination.getParent());
      StringBuilder module = new StringBuilder();
      module.append("// Gold Client generated JavaScript module\n")
          .append("// Class: ").append(clazz.name()).append("\n")
          .append("import { GoldClientRuntime } from ")
          .append(quote(relativeImport(destination.getParent(), root.resolve("gold-client-runtime.js"))))
          .append(";\n")
          .append("void GoldClientRuntime; // Runtime is available to lowered helpers.\n\n");

      java.util.Map<String, String> fieldRenames = collidingFieldRenames(clazz, classes);
      for (MethodModel method : clazz.methods().stream()
          .sorted(Comparator.comparing(MethodModel::name).thenComparing(MethodModel::descriptor))
          .toList()) {
        boolean unavailable = (method.access() & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0
            || method.intermediateRepresentation().operations().isEmpty();
        TranslationResult result;
        String status;
        String reason;
        if (unavailable) {
          String why = (method.access() & Opcodes.ACC_NATIVE) != 0 ? "native method"
              : (method.access() & Opcodes.ACC_ABSTRACT) != 0 ? "abstract method"
              : "semantic IR was not recovered";
          result = translator.translateAdapter(clazz.name(), method.name(), method.descriptor(), why,
              (method.access() & Opcodes.ACC_STATIC) != 0);
          boolean abstractDeclaration = (method.access() & Opcodes.ACC_ABSTRACT) != 0;
          if (abstractDeclaration) {
            result = new TranslationResult(result.source().replace(
                "// Status: adapter-required; no JVM method body exists.",
                "// Status: declaration-only; concrete implementations supply this method."),
                result.complete());
          }
          status = abstractDeclaration ? "declaration-only" : "adapter-required";
          reason = why;
        } else {
          result = translator.translate(method.intermediateRepresentation(),
              (method.access() & Opcodes.ACC_STATIC) != 0, fieldRenames);
          status = result.complete() ? "complete" : "incomplete";
          reason = result.complete() ? null : incompleteReason(result.source(), method.intermediateRepresentation());
        }
        statuses.add(new MethodStatus(clazz.name(), method.name(), method.descriptor(), status, reason));
        module.append(result.source()).append("\n");
      }
      appendExecutableClassWrapper(module, clazz, classes);
      Files.writeString(destination, module, StandardCharsets.UTF_8);
      filesWritten++;
    }

    ClassCoverageReport classCoverage = writeClassCoverage(jars, classes, root);
    int resourceCount = copyResources(jars, root);
    Files.writeString(root.resolve("gold-client-runtime.js"), runtimeSource(), StandardCharsets.UTF_8);
    writeModuleIndex(classes, root);
    writeClassManifest(classes, root);
    ForgeTransformationInspector.Report transformations =
        ForgeTransformationInspector.inspect(jars);
    Files.writeString(root.resolve("forge-transformations.json"),
        new GsonBuilder().setPrettyPrinting().create().toJson(transformations)
            + System.lineSeparator(), StandardCharsets.UTF_8);
    new ForgeLifecycleExporter().export(classes, metadataModIds, root,
        transformations.blockers(),
        excludedLifecycleClasses == null ? Set.of() : excludedLifecycleClasses,
        suppliedModIds == null ? Set.of() : suppliedModIds);
    new ForgeHostRuntimeExporter().export(root);
    new ForgeCompatibilityPlanner().export(classes, root);
    Files.writeString(root.resolve("package.json"),
        "{\n  \"type\": \"module\"\n}\n", StandardCharsets.UTF_8);
    ExportReport report = new ExportReport(
        "gold-client-javascript-export-v1", classes.size(), statuses.size(),
        count(statuses, "complete"), count(statuses, "incomplete"),
        count(statuses, "skipped"), count(statuses, "stubbed"), count(statuses, "adapter-required"),
        filesWritten, resourceCount, statuses);
    Files.writeString(root.resolve("javascript-report.json"),
        new GsonBuilder().setPrettyPrinting().create().toJson(report) + System.lineSeparator(),
        StandardCharsets.UTF_8);
    Files.writeString(root.resolve("javascript-report.txt"), summary(report), StandardCharsets.UTF_8);
    writeIncompleteDiagnostics(root, statuses, classes);
    writeDeclarationAdapterManifest(root, statuses, classes);
    return report;
  }

  /**
   * Adds a real JavaScript class facade around the translated function exports.
   * Method bodies stay independently exportable, while callers get Java-like
   * instance/static dispatch and constructors that execute the recovered body.
   */
  /** Exports exact stable keys needed to bind abstract/native declarations to target adapters. */
  private static void writeDeclarationAdapterManifest(
      Path root, List<MethodStatus> statuses, List<ClassModel> classes) throws IOException {
    java.util.Map<String, ClassModel> classByName = new java.util.HashMap<>();
    for (ClassModel clazz : classes) classByName.put(clazz.name(), clazz);
    List<DeclarationAdapter> adapters = new ArrayList<>();
    for (MethodStatus status : statuses) {
      if (!"adapter-required".equals(status.status())) continue;
      ClassModel clazz = classByName.get(status.className());
      if (clazz == null) continue;
      MethodModel method = clazz.methods().stream()
          .filter(candidate -> candidate.name().equals(status.methodName())
              && candidate.descriptor().equals(status.descriptor()))
          .findFirst().orElse(null);
      if (method == null) continue;
      String owner = clazz.name().replace('/', '.');
      String reason = status.reason() == null ? "declaration" : status.reason();
      adapters.add(new DeclarationAdapter(owner, method.name(), method.descriptor(),
          (method.access() & Opcodes.ACC_STATIC) != 0,
          (method.access() & Opcodes.ACC_ABSTRACT) != 0,
          descriptorArity(method.descriptor()),
          owner + "#" + method.name() + method.descriptor() + ": " + reason, reason));
    }
    DeclarationAdapterManifest manifest = new DeclarationAdapterManifest(
        "gold-client-declaration-adapters-v1", adapters.size(), List.copyOf(adapters));
    Files.writeString(root.resolve("declaration-adapters.json"),
        new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create().toJson(manifest)
            + System.lineSeparator(), StandardCharsets.UTF_8);
  }

  public record DeclarationAdapterManifest(
      String formatVersion, int adapterRequiredCount, List<DeclarationAdapter> adapters) {
    public DeclarationAdapterManifest { adapters = List.copyOf(adapters); }
  }

  public record DeclarationAdapter(
      String owner, String methodName, String descriptor, boolean isStatic,
      boolean isAbstract, int arity, String adapterKey, String reason) {}


  private static String subscribePriority(MethodModel method) {
    for (AnnotationData annotation : method.annotationData()) {
      if (!annotation.name().equals("net.minecraftforge.fml.common.eventhandler.SubscribeEvent")) continue;
      Object value = annotation.values().get("priority");
      if (value instanceof AnnotationData.EnumValue enumValue) return enumValue.constant();
      if (value instanceof List<?> values && !values.isEmpty()
          && values.get(0) instanceof AnnotationData.EnumValue enumValue) {
        return enumValue.constant();
      }
    }
    return "NORMAL";
  }

  private static boolean subscribeReceiveCanceled(MethodModel method) {
    for (AnnotationData annotation : method.annotationData()) {
      if (!annotation.name().equals("net.minecraftforge.fml.common.eventhandler.SubscribeEvent")) continue;
      Object value = annotation.values().get("receiveCanceled");
      if (value instanceof Boolean bool) return bool;
    }
    return false;
  }

  private static void appendExecutableClassWrapper(
      StringBuilder module, ClassModel clazz, List<ClassModel> allClasses) {
    String className = "Translated_" + safeIdentifier(clazz.name().replace('/', '_').replace('.', '_'));
    List<MethodModel> methods = clazz.methods().stream()
        .sorted(Comparator.comparing(MethodModel::name).thenComparing(MethodModel::descriptor))
        .toList();

    module.append("\nconst __goldMethods = new Map([\n");
    java.util.Map<String, List<MethodModel>> byName = new java.util.TreeMap<>();
    for (MethodModel method : methods) {
      byName.computeIfAbsent(method.name(), ignored -> new ArrayList<>()).add(method);
    }
    int methodIndex = 0;
    for (var entry : byName.entrySet()) {
      module.append("  [").append(quote(entry.getKey())).append(", [");
      for (int i = 0; i < entry.getValue().size(); i++) {
        MethodModel method = entry.getValue().get(i);
        if (i > 0) module.append(", ");
        module.append("{ arity: ").append(descriptorArity(method.descriptor()))
            .append(", descriptor: ").append(quote(method.descriptor()))
            .append(", isStatic: ").append((method.access() & Opcodes.ACC_STATIC) != 0)
            .append(", isAbstract: ").append((method.access() & Opcodes.ACC_ABSTRACT) != 0)
            .append(", isBridge: ").append((method.access() & Opcodes.ACC_BRIDGE) != 0)
            .append(", isAdapterRequired: ")
            .append((method.access() & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0
                || method.intermediateRepresentation().operations().isEmpty())
            .append(", annotations: [")
            .append(String.join(", ", method.annotations().stream().map(JavaScriptModTranslationExporter::quote).toList()))
            .append("]")
            .append(", subscribePriority: ").append(quote(subscribePriority(method)))
            .append(", receiveCanceled: ").append(subscribeReceiveCanceled(method))
            .append(", fn: ").append(exportedName(method.name(), method.descriptor())).append(" }");
      }
      module.append("]]");
      if (++methodIndex < byName.size()) module.append(',');
      module.append("\n");
    }
    module.append("]);\n");
    module.append("export class ").append(className).append(" {\n")
        .append("  constructor(...args) {\n")
        .append("    GoldClientRuntime.ensureInitialized(").append(className).append(");\n");
    java.util.Map<String, String> fieldRenames = collidingFieldRenames(clazz, allClasses);
    java.util.Set<String> initializedFields = new java.util.HashSet<>();
    for (var field : clazz.fields()) {
      if ((field.access() & Opcodes.ACC_STATIC) == 0) {
        initializedFields.add(field.name());
        module.append("    this.").append(javascriptFieldName(field.name(), fieldRenames)).append(" = ")
            .append(defaultValue(field.descriptor())).append(";\n");
      }
    }
    // Superclass constructors are invoked through invokeNamedConstructor on the
    // same receiver, so their JavaScript `new` initializers never run. Apply
    // inherited instance-field defaults here or subclass instances silently
    // miss base fields (e.g. ConfigOptionManager.frozen).
    java.util.Map<String, ClassModel> ancestorByName = new java.util.HashMap<>();
    for (ClassModel candidate : allClasses) {
      ancestorByName.put(candidate.name(), candidate);
      ancestorByName.put(candidate.name().replace('/', '.'), candidate);
      ancestorByName.put(candidate.name().replace('.', '/'), candidate);
    }
    java.util.Set<String> visitedAncestors = new java.util.HashSet<>();
    String ancestorName = clazz.superName();
    while (ancestorName != null && visitedAncestors.add(ancestorName)) {
      ClassModel parent = ancestorByName.get(ancestorName);
      if (parent == null) parent = ancestorByName.get(ancestorName.replace('/', '.'));
      if (parent == null) parent = ancestorByName.get(ancestorName.replace('.', '/'));
      if (parent == null) break;
      for (var field : parent.fields()) {
        if ((field.access() & Opcodes.ACC_STATIC) != 0) continue;
        if (!initializedFields.add(field.name())) continue;
        module.append("    this.").append(javascriptFieldName(field.name(), fieldRenames)).append(" = ")
            .append(defaultValue(field.descriptor())).append(";\n");
      }
      ancestorName = parent.superName();
    }
    module.append("    GoldClientRuntime.invoke(this, __goldMethods, \"<init>\", args, true);\n")
        .append("  }\n");

    for (var entry : byName.entrySet()) {
      String name = entry.getKey();
      if ("<init>".equals(name) || "<clinit>".equals(name) || "constructor".equals(name)
          || !isJavaScriptIdentifier(name)) continue;
      boolean hasInstance = entry.getValue().stream()
          .anyMatch(method -> (method.access() & Opcodes.ACC_STATIC) == 0);
      boolean hasStatic = entry.getValue().stream()
          .anyMatch(method -> (method.access() & Opcodes.ACC_STATIC) != 0);
      if (hasInstance) {
        module.append("  ").append(name).append("(...args) { return GoldClientRuntime.invoke(this, __goldMethods, ")
            .append(quote(name)).append(", args); }\n");
      }
      if (hasStatic && isJavaScriptStaticMember(name)) {
        module.append("  static ").append(name).append("(...args) { return GoldClientRuntime.invokeStatic(this, __goldMethods, ")
            .append(quote(name)).append(", args); }\n");
      }
    }
    // Add facade methods for inherited Java methods; runtime dispatch resolves the real body.
    java.util.Map<String, ClassModel> classByName = new java.util.HashMap<>();
    for (ClassModel candidate : allClasses) classByName.put(candidate.name(), candidate);
    java.util.Set<String> inheritedInstance = new java.util.TreeSet<>();
    java.util.Set<String> inheritedStatic = new java.util.TreeSet<>();
    java.util.Set<String> visitedParents = new java.util.HashSet<>();
    String parentName = clazz.superName();
    while (parentName != null && visitedParents.add(parentName)) {
      ClassModel parent = classByName.get(parentName);
      if (parent == null) break;
      for (MethodModel method : parent.methods()) {
        String name = method.name();
        if ("<init>".equals(name) || "<clinit>".equals(name) || "constructor".equals(name)
            || !isJavaScriptIdentifier(name) || byName.containsKey(name)) continue;
        if ((method.access() & Opcodes.ACC_STATIC) != 0) inheritedStatic.add(name);
        else inheritedInstance.add(name);
      }
      parentName = parent.superName();
    }
    for (String name : inheritedInstance) {
      module.append("  ").append(name)
          .append("(...args) { return GoldClientRuntime.invoke(this, __goldMethods, ")
          .append(quote(name)).append(", args); }\n");
    }
    for (String name : inheritedStatic) {
      if (!isJavaScriptStaticMember(name)) continue;
      module.append("  static ").append(name)
          .append("(...args) { return GoldClientRuntime.invokeStatic(this, __goldMethods, ")
          .append(quote(name)).append(", args); }\n");
    }
    module.append("}\n");
    for (var field : clazz.fields()) {
      if ((field.access() & Opcodes.ACC_STATIC) != 0) {
        module.append("GoldClientRuntime.defineStaticField(").append(className).append(", ")
            .append(quote(field.name())).append(", ")
            .append(fieldInitializer(field, true)).append(");\n");
      }
    }
    module.append("GoldClientRuntime.registerClass(")
        .append(quote(clazz.name().replace('/', '.'))).append(", ")
        .append(className).append(", (__goldMethods.get(\"<clinit>\") ?? [])")
        .append(".find(method => method.isStatic)?.fn ?? null, { superName: ")
        .append(clazz.superName() == null ? "null" : quote(clazz.superName().replace('/', '.')))
        .append(", interfaces: [")
        .append(String.join(", ", clazz.interfaces().stream()
            .map(name -> quote(name.replace('/', '.'))).toList()))
        .append("], methods: __goldMethods, annotations: ")
        .append(annotationEntryList(clazz)).append(" });\n");
  }

  /**
   * Serializes the annotation values harvested from the class file so the
   * runtime can rebuild a Forge ASMDataTable facade. The manifest only keeps
   * annotation names; this table keeps the values a mod may read through
   * {@code ASMData.getObject()}.
   */
  private static String annotationEntryList(ClassModel clazz) {
    List<String> entries = new ArrayList<>();
    for (AnnotationData annotation : clazz.annotationData()) {
      entries.add(annotationEntry(annotation, "class", null, null));
    }
    for (FieldModel field : clazz.fields()) {
      for (AnnotationData annotation : field.annotationData()) {
        entries.add(annotationEntry(annotation, "field", field.name(), field.descriptor()));
      }
    }
    for (MethodModel method : clazz.methods()) {
      for (AnnotationData annotation : method.annotationData()) {
        entries.add(annotationEntry(annotation, "method", method.name(), method.descriptor()));
      }
    }
    return "[" + String.join(", ", entries) + "]";
  }

  private static String annotationEntry(AnnotationData annotation, String target,
      String member, String descriptor) {
    return "{ target: " + quote(target)
        + ", member: " + (member == null ? "null" : quote(member))
        + ", descriptor: " + (descriptor == null ? "null" : quote(descriptor))
        + ", name: " + quote(annotation.name())
        + ", runtimeVisible: " + annotation.runtimeVisible()
        + ", values: " + annotationValuesLiteral(annotation.values()) + " }";
  }

  private static String annotationValuesLiteral(Map<String, Object> values) {
    List<String> entries = new ArrayList<>();
    for (Map.Entry<String, Object> entry : values.entrySet()) {
      entries.add(quote(entry.getKey()) + ": " + annotationValueLiteral(entry.getValue()));
    }
    return "{" + String.join(", ", entries) + "}";
  }

  private static String annotationValueLiteral(Object value) {
    if (value == null) return "null";
    if (value instanceof AnnotationData.EnumValue enumValue) {
      return "{ $goldEnum: { descriptor: " + quote(enumValue.descriptor())
          + ", constant: " + quote(enumValue.constant()) + " } }";
    }
    if (value instanceof AnnotationData.ClassValue classValue) {
      return "{ $goldClass: " + quote(classValue.descriptor()) + " }";
    }
    if (value instanceof AnnotationData annotation) {
      return "{ $goldAnnotation: { name: " + quote(annotation.name())
          + ", values: " + annotationValuesLiteral(annotation.values()) + " } }";
    }
    if (value instanceof List<?> list) {
      List<String> entries = new ArrayList<>();
      for (Object item : list) entries.add(annotationValueLiteral(item));
      return "[" + String.join(", ", entries) + "]";
    }
    if (value instanceof Boolean || value instanceof Number) return value.toString();
    if (value instanceof Character character) return "String.fromCharCode(" + (int) character + ")";
    return quote(String.valueOf(value));
  }

  private static String fieldInitializer(FieldModel field, boolean useConstant) {
    Object value = useConstant ? field.constantValue() : null;
    if (value == null) return defaultValue(field.descriptor());
    String descriptor = field.descriptor();
    if ("Z".equals(descriptor)) {
      if (value instanceof Number number) return number.intValue() == 0 ? "false" : "true";
      if (value instanceof Boolean bool) return bool ? "true" : "false";
    }
    if ("C".equals(descriptor) && value instanceof Number number) {
      return "String.fromCharCode(" + number.intValue() + ")";
    }
    if (("Ljava/lang/String;".equals(descriptor) || value instanceof String)
        && value instanceof String text) {
      return quote(text);
    }
    if (value instanceof Number number) {
      if (number instanceof Float || number instanceof Double) {
        double d = number.doubleValue();
        if (Double.isNaN(d)) return "Number.NaN";
        if (d == Double.POSITIVE_INFINITY) return "Number.POSITIVE_INFINITY";
        if (d == Double.NEGATIVE_INFINITY) return "Number.NEGATIVE_INFINITY";
      }
      return number.toString();
    }
    return defaultValue(descriptor);
  }

  /**
   * Java allows a field and a method with the same name; JavaScript objects do
   * not. Instance fields that collide with an instance method (in this class or
   * an ancestor) are emitted as {@code name$field} so the method stays callable.
   */
  private static java.util.Map<String, String> collidingFieldRenames(
      ClassModel clazz, List<ClassModel> allClasses) {
    java.util.Map<String, ClassModel> classByName = new java.util.HashMap<>();
    for (ClassModel candidate : allClasses) {
      classByName.put(candidate.name(), candidate);
      classByName.put(candidate.name().replace('/', '.'), candidate);
      classByName.put(candidate.name().replace('.', '/'), candidate);
    }
    java.util.Map<String, String> renames = new java.util.LinkedHashMap<>();
    java.util.Set<String> visited = new java.util.HashSet<>();
    String current = clazz.name();
    while (current != null && visited.add(current)) {
      ClassModel type = classByName.get(current);
      if (type == null) type = classByName.get(current.replace('/', '.'));
      if (type == null) type = classByName.get(current.replace('.', '/'));
      if (type == null) break;
      java.util.Set<String> methodNames = type.methods().stream()
          .filter(method -> (method.access() & Opcodes.ACC_STATIC) == 0)
          .map(MethodModel::name)
          .filter(name -> !"<init>".equals(name) && !"<clinit>".equals(name))
          .collect(java.util.stream.Collectors.toSet());
      for (FieldModel field : type.fields()) {
        if ((field.access() & Opcodes.ACC_STATIC) != 0) continue;
        if (methodNames.contains(field.name())) {
          renames.putIfAbsent(field.name(), field.name() + "$field");
        }
      }
      current = type.superName();
    }
    return renames;
  }

  private static String javascriptFieldName(String name, java.util.Map<String, String> renames) {
    return renames.getOrDefault(name, name);
  }

  private static String defaultValue(String descriptor) {
    if (descriptor == null || descriptor.isEmpty()) return "null";
    return switch (descriptor.charAt(0)) {
      case 'Z' -> "false";
      case 'B', 'C', 'S', 'I', 'J', 'F', 'D' -> "0";
      default -> "null";
    };
  }

  private static int descriptorArity(String descriptor) {
    if (descriptor == null) return 0;
    int close = descriptor.indexOf(')');
    if (descriptor.isEmpty() || descriptor.charAt(0) != '(' || close < 0) return 0;
    int count = 0;
    for (int i = 1; i < close; count++) {
      while (i < close && descriptor.charAt(i) == '[') i++;
      if (i >= close) break;
      if (descriptor.charAt(i) == 'L') {
        int end = descriptor.indexOf(';', i);
        if (end < 0 || end > close) break;
        i = end + 1;
      } else {
        i++;
      }
    }
    return count;
  }

  private static String exportedName(String name, String descriptor) {
    String suffix = Integer.toUnsignedString((descriptor == null ? "" : descriptor).hashCode(), 36);
    return safeIdentifier(name) + "__" + suffix;
  }

  private static String safeIdentifier(String value) {
    String safe = value == null ? "TranslatedClass" : value.replaceAll("[^A-Za-z0-9_$]", "_");
    if (safe.isBlank()) safe = "TranslatedClass";
    if (!Character.isJavaIdentifierStart(safe.charAt(0))) safe = "_" + safe;
    return safe;
  }

  private static boolean isJavaScriptIdentifier(String value) {
    return value != null && value.matches("[A-Za-z_$][A-Za-z0-9_$]*")
        && !java.util.Set.of("await", "break", "case", "catch", "class", "const", "continue",
            "debugger", "default", "delete", "do", "else", "export", "extends", "finally",
            "for", "function", "if", "import", "in", "instanceof", "new", "return", "super",
            "switch", "this", "throw", "try", "typeof", "var", "void", "while", "with",
            "yield", "enum", "implements", "interface", "let", "package", "private",
            "protected", "public", "static").contains(value);
  }

  /**
   * JavaScript class syntax rejects a static member named {@code prototype}.
   * Java static methods with that name still dispatch through the runtime's
   * method table, so the facade is skipped rather than emitting invalid JS.
   */
  private static boolean isJavaScriptStaticMember(String value) {
    return !"prototype".equals(value);
  }

  private static int copyResources(List<Path> inputJars, Path root) throws IOException {
    if (inputJars == null || inputJars.isEmpty()) return 0;
    Path resourceRoot = root.resolve("resources").normalize();
    Files.createDirectories(resourceRoot);
    java.util.Map<String, ResourceRecord> copied = new java.util.TreeMap<>();
    java.util.List<ResourceConflict> conflicts = new ArrayList<>();
    for (Path inputJar : inputJars) {
      try (ZipFile zip = new ZipFile(inputJar.toFile())) {
        List<? extends ZipEntry> entries = Collections.list(zip.entries()).stream()
            .filter(entry -> !entry.isDirectory())
            .sorted(Comparator.comparing(ZipEntry::getName))
            .toList();
        for (ZipEntry entry : entries) {
          String name = entry.getName().replace('\\', '/');
          if (name.endsWith(".class") || isSignatureFile(name)) continue;
          if (copied.containsKey(name)) {
            conflicts.add(new ResourceConflict(name, inputJar.toString(), "first input wins"));
            continue;
          }
          Path destination = resourceRoot.resolve(name).normalize();
          if (!destination.startsWith(resourceRoot)) {
            throw new IOException("Rejected unsafe JAR resource path: " + name);
          }
          Files.createDirectories(destination.getParent());
          try (var stream = zip.getInputStream(entry)) {
            Files.copy(stream, destination, StandardCopyOption.REPLACE_EXISTING);
          }
          copied.put(name, new ResourceRecord(name, Files.size(destination)));
        }
      }
    }
    Files.writeString(root.resolve("resource-index.json"),
        new GsonBuilder().setPrettyPrinting().create().toJson(copied.values()) + System.lineSeparator(),
        StandardCharsets.UTF_8);
    if (!conflicts.isEmpty()) {
      Files.writeString(root.resolve("resource-conflicts.json"),
          new GsonBuilder().setPrettyPrinting().create().toJson(conflicts) + System.lineSeparator(),
          StandardCharsets.UTF_8);
    }
    return copied.size();
  }

  private static int copyResources(Path inputJar, Path root) throws IOException {
    return copyResources(inputJar == null ? List.of() : List.of(inputJar), root);
  }

  /**
   * Audits class coverage independently from method completeness. Every class
   * in the input JAR must have a ClassModel and a generated module; unsupported
   * methods are still represented explicitly inside that module.
   */
  private static ClassCoverageReport writeClassCoverage(
      List<Path> inputJars, List<ClassModel> classes, Path root) throws IOException {
    java.util.Set<String> modeled = new java.util.TreeSet<>();
    for (ClassModel clazz : classes) {
      modeled.add(clazz.name().replace('.', '/'));
    }

    java.util.Set<String> jarClasses = new java.util.TreeSet<>();
    java.util.Set<String> unreadableClassEntries = new java.util.TreeSet<>();
    int jarClassEntries = 0;
    if (inputJars != null && !inputJars.isEmpty()) {
      for (Path inputJar : inputJars) {
        try (ZipFile zip = new ZipFile(inputJar.toFile())) {
          for (ZipEntry entry : Collections.list(zip.entries())) {
            if (entry.isDirectory() || !entry.getName().endsWith(".class")) continue;
            jarClassEntries++;
            try (var stream = zip.getInputStream(entry)) {
              jarClasses.add(new org.objectweb.asm.ClassReader(stream).getClassName());
            } catch (RuntimeException invalidClass) {
              // Keep export resilient for malformed .class payloads, but never report
              // complete coverage when an input class entry could not be inspected.
              unreadableClassEntries.add(inputJar + "!" + entry.getName());
            }
          }
        }
      }
    } else {
      jarClasses.addAll(modeled);
      jarClassEntries = modeled.size();
    }

    java.util.Set<String> unmodeledJarClasses = new java.util.TreeSet<>(jarClasses);
    unmodeledJarClasses.removeAll(modeled);
    java.util.Set<String> modeledClassesAbsentFromJar = new java.util.TreeSet<>(modeled);
    modeledClassesAbsentFromJar.removeAll(jarClasses);
    java.util.Set<String> missingModules = new java.util.TreeSet<>();
    int translatedModules = 0;
    for (String className : modeled) {
      if (Files.isRegularFile(outputPath(root, className))) {
        translatedModules++;
      } else {
        missingModules.add(className);
      }
    }

    String inputDescription = inputJars == null || inputJars.isEmpty() ? null
        : inputJars.size() == 1 ? inputJars.get(0).toString()
        : inputJars.stream().map(Path::toString).collect(java.util.stream.Collectors.joining(","));
    ClassCoverageReport report = new ClassCoverageReport(
        "gold-client-class-coverage-v1",
        inputDescription,
        jarClassEntries, jarClasses.size(), modeled.size(), translatedModules,
        unmodeledJarClasses, modeledClassesAbsentFromJar, missingModules, unreadableClassEntries,
        unmodeledJarClasses.isEmpty() && modeledClassesAbsentFromJar.isEmpty()
            && missingModules.isEmpty() && unreadableClassEntries.isEmpty());
    Files.writeString(root.resolve("class-coverage.json"),
        new GsonBuilder().setPrettyPrinting().create().toJson(report)
            + System.lineSeparator(), StandardCharsets.UTF_8);
    return report;
  }

  private static ClassCoverageReport writeClassCoverage(
      Path inputJar, List<ClassModel> classes, Path root) throws IOException {
    return writeClassCoverage(inputJar == null ? List.of() : List.of(inputJar), classes, root);
  }

  public record ClassCoverageReport(
      String formatVersion,
      String inputJar,
      int jarClassEntries,
      int uniqueJarClasses,
      int modeledClasses,
      int translatedModules,
      java.util.Set<String> unmodeledJarClasses,
      java.util.Set<String> modeledClassesAbsentFromJar,
      java.util.Set<String> missingModules,
      java.util.Set<String> unreadableClassEntries,
      boolean complete) {
    public ClassCoverageReport {
      unmodeledJarClasses = java.util.Set.copyOf(unmodeledJarClasses);
      modeledClassesAbsentFromJar = java.util.Set.copyOf(modeledClassesAbsentFromJar);
      missingModules = java.util.Set.copyOf(missingModules);
      unreadableClassEntries = java.util.Set.copyOf(unreadableClassEntries);
    }
  }

  private static boolean isSignatureFile(String name) {
    String upper = name.toUpperCase(java.util.Locale.ROOT);
    if (!upper.startsWith("META-INF/")) return false;
    return upper.endsWith(".SF") || upper.endsWith(".RSA") || upper.endsWith(".DSA")
        || upper.endsWith(".EC") || upper.equals("META-INF/INDEX.LIST");
  }

  public record ResourceRecord(String path, long sizeBytes) {}
  public record ResourceConflict(String path, String ignoredInput, String policy) {}

  private static Path outputPath(Path root, String className) throws IOException {
    String relative = className.replace('\\', '/').replace('.', '/');
    while (relative.startsWith("/")) relative = relative.substring(1);
    Path path = root.resolve(relative + ".translated.js").normalize();
    if (!path.startsWith(root)) throw new IOException("Rejected class output path: " + className);
    return path;
  }

  private static String relativeImport(Path fromDirectory, Path runtimeFile) {
    String relative = fromDirectory.relativize(runtimeFile).toString().replace('\\', '/');
    return relative.startsWith(".") ? relative : "./" + relative;
  }

  private static String quote(String value) {
    StringBuilder out = new StringBuilder(value.length() + 2).append('"');
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      switch (c) {
        case '\\' -> out.append("\\\\");
        case '"' -> out.append("\\\"");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        case '\b' -> out.append("\\b");
        case '\f' -> out.append("\\f");
        default -> {
          // A raw line separator in a string literal would terminate the
          // generated module line and make the whole bundle unparseable.
          if (c < 0x20 || c == '\u2028' || c == '\u2029') {
            out.append(String.format("\\u%04x", (int) c));
          } else {
            out.append(c);
          }
        }
      }
    }
    return out.append('"').toString();
  }

  /**
   * Explains incompleteness using evidence left by the translator. This is
   * deliberately diagnostic-only: it never changes a method's completeness
   * status and never treats a classified failure as successful translation.
   */
  private static String incompleteReason(String source, com.goldclient.model.IrMethodModel ir) {
    java.util.Set<String> reasons = new java.util.LinkedHashSet<>();

    java.util.regex.Matcher todo = java.util.regex.Pattern
        .compile("(?m)^\\s*// TODO: ([^\\n]+)")
        .matcher(source);
    while (todo.find()) {
      reasons.add("[UNLOWERED_OPERATION] " + todo.group(1).trim());
    }

    java.util.regex.Matcher audit = java.util.regex.Pattern
        .compile("(?m)^\\s*// CONTROL-FLOW ERROR at operation ([^\\n]+)")
        .matcher(source);
    while (audit.find()) {
      reasons.add("[CONTROL_FLOW_AUDIT] operation " + audit.group(1).trim());
    }

    java.util.regex.Matcher unsupported = java.util.regex.Pattern
        .compile("GoldClientRuntime\\.unsupported\\(\\\"([^\\\"]+)\\\",\\s*\\\"([^\\\"]*)\\\"\\)")
        .matcher(source);
    while (unsupported.find()) {
      String kind = unsupported.group(1);
      String detail = unsupported.group(2).trim();
      String evidence = detail.isEmpty() ? kind : kind + " (" + detail + ")";
      switch (kind) {
        case "JUMP" -> reasons.add("[CONTROL_FLOW] Unresolved jump: " + evidence);
        case "control-flow-audit" -> reasons.add("[CONTROL_FLOW_AUDIT] " + detail);
        case "try-catch-region" -> reasons.add("[EXCEPTION_REGION] Could not structure try/catch region: " + evidence);
        case "monitor" -> reasons.add("[MONITOR_SEMANTICS] JVM monitor operation is unsupported: " + evidence);
        case "UNSUPPORTED_DYNAMIC" -> reasons.add("[DYNAMIC_CALL] invokedynamic has no supported lowering: " + evidence);
        case "untranslated-method" -> reasons.add("[MISSING_BODY] Explicit stub emitted: " + evidence);
        case "missing-declaration-adapter" -> reasons.add("[MISSING_ADAPTER] No declaration adapter is registered: " + evidence);
        case "missing-instance-method" -> reasons.add("[RUNTIME_METHOD_LOOKUP] Instance method binding is missing: " + evidence);
        case "missing-static-method" -> reasons.add("[RUNTIME_METHOD_LOOKUP] Static method binding is missing: " + evidence);
        default -> reasons.add("[UNSUPPORTED_IR] Operation cannot be lowered: " + evidence);
      }
    }

    if (source.contains("unsupported invokedynamic")) {
      reasons.add("[DYNAMIC_CALL] invokedynamic call site has no supported lowering");
    }
    if (source.contains("<exception>")) {
      reasons.add("[EXCEPTION_STACK] Exception stack value was not lowered");
    }
    if (source.contains("<unknown-stack>")) {
      reasons.add("[UNKNOWN_STACK_VALUE] Operand stack value could not be reconstructed");
    }
    if (source.contains("<unknown>")) {
      reasons.add("[UNKNOWN_VALUE] Value reconstruction left an unknown value");
    }
    if (source.contains("Unsupported JVM IR")) {
      reasons.add("[UNSUPPORTED_JVM_IR] Unsupported JVM IR operation remains");
    }

    if (source.contains("Unable to lower method signature")) {
      reasons.add("[SIGNATURE_RECONSTRUCTION] Structured translator did not emit a method signature that the JS backend recognizes");
    }
    if (source.contains("Malformed intermediate method body")) {
      reasons.add("[BODY_EXTRACTION] Could not find valid method-body boundaries in structured output");
    }

    int conditionalBranches = 0;
    int jumps = 0;
    int exceptionMarkers = 0;
    java.util.Set<String> irUnknownEvidence = new java.util.LinkedHashSet<>();
    for (com.goldclient.model.IrOperationModel operation : ir.operations()) {
      if ("CONDITIONAL_BRANCH".equals(operation.kind())) conditionalBranches++;
      if ("JUMP".equals(operation.kind())) jumps++;
      if ("TRY_BEGIN".equals(operation.kind()) || "TRY_END".equals(operation.kind())
          || "CATCH_BEGIN".equals(operation.kind())) exceptionMarkers++;
      for (String operand : operation.operands()) {
        if (operand.contains("<unknown-stack>")) irUnknownEvidence.add("[UNKNOWN_STACK_VALUE] IR operand contains <unknown-stack>");
        else if (operand.contains("<unknown>")) irUnknownEvidence.add("[UNKNOWN_VALUE] IR operand contains <unknown>");
        if (operand.contains("<exception>")) irUnknownEvidence.add("[EXCEPTION_STACK] IR operand contains <exception>");
      }
    }
    reasons.addAll(irUnknownEvidence);
    boolean hasControlFlowReason = reasons.stream().anyMatch(reason ->
        reason.startsWith("[CONTROL_FLOW]") || reason.startsWith("[CONTROL_FLOW_AUDIT]")
            || reason.startsWith("[CONTROL_FLOW_STRUCTURING]"));
    if (!hasControlFlowReason && (conditionalBranches > 0 || jumps > 0)) {
      reasons.add("[CONTROL_FLOW_STRUCTURING] IR contains " + conditionalBranches
          + " conditional branch(es) and " + jumps
          + " jump(s), but emitted JavaScript has no explicit control-flow failure marker");
    }
    boolean hasExceptionReason = reasons.stream().anyMatch(reason ->
        reason.startsWith("[EXCEPTION_REGION]") || reason.startsWith("[EXCEPTION_STACK]"));
    if (!hasExceptionReason && exceptionMarkers > 0) {
      reasons.add("[EXCEPTION_STRUCTURING] IR contains " + exceptionMarkers
          + " exception marker(s), but emitted JavaScript has no explicit exception-region failure marker");
    }
    if (reasons.isEmpty()) {
      reasons.add("[UNCLASSIFIED] Translator marked the method incomplete but emitted no recognized diagnostic marker");
    }
    return String.join("; ", reasons);
  }

  /**
   * Writes a triage view grouped by the actual failure reason, with concrete
   * method signatures. This is an index over the existing honest statuses,
   * not a second completeness calculation.
   */
  private static void writeIncompleteDiagnostics(
      Path root, List<MethodStatus> statuses, List<ClassModel> classes) throws IOException {
    java.util.Map<String, List<MethodStatus>> grouped = new java.util.TreeMap<>();
    java.util.Map<String, MethodModel> modeledMethods = new java.util.HashMap<>();
    for (ClassModel clazz : classes) {
      for (MethodModel method : clazz.methods()) {
        modeledMethods.put(methodKey(clazz.name(), method.name(), method.descriptor()), method);
      }
    }

    List<IncompleteMethodDetail> details = new ArrayList<>();
    for (MethodStatus status : statuses) {
      if (!"incomplete".equals(status.status())) continue;
      String reason = status.reason() == null ? "[NO_REASON] No reason recorded" : status.reason();
      grouped.computeIfAbsent(reason, ignored -> new ArrayList<>()).add(status);
      MethodModel method = modeledMethods.get(methodKey(
          status.className(), status.methodName(), status.descriptor()));
      List<OperationEvidence> operations = new ArrayList<>();
      java.util.Map<String, Integer> operationCounts = new java.util.TreeMap<>();
      if (method != null) {
        for (var operation : method.intermediateRepresentation().operations()) {
          operationCounts.merge(operation.kind(), 1, Integer::sum);
          operations.add(new OperationEvidence(
              operation.sourceInstruction(), operation.kind(), operation.operands()));
        }
      }
      details.add(new IncompleteMethodDetail(
          status.className().replace('/', '.'), status.methodName(), status.descriptor(), reason,
          operations.size(), operationCounts, operations));
    }
    details.sort(Comparator.comparing(IncompleteMethodDetail::className)
        .thenComparing(IncompleteMethodDetail::methodName)
        .thenComparing(IncompleteMethodDetail::descriptor));
    Files.writeString(root.resolve("incomplete-method-details.json"),
        new GsonBuilder().setPrettyPrinting().create().toJson(details) + System.lineSeparator(),
        StandardCharsets.UTF_8);

    java.util.Map<String, Integer> categoryCounts = new java.util.TreeMap<>();
    java.util.regex.Pattern categoryPattern = java.util.regex.Pattern.compile("\\[([A-Z_]+)\\]");
    for (IncompleteMethodDetail detail : details) {
      java.util.regex.Matcher matcher = categoryPattern.matcher(detail.reason());
      java.util.Set<String> methodCategories = new java.util.TreeSet<>();
      while (matcher.find()) methodCategories.add(matcher.group(1));
      for (String category : methodCategories) categoryCounts.merge(category, 1, Integer::sum);
    }

    StringBuilder report = new StringBuilder("Gold Client incomplete-method diagnostics\n")
        .append("Incomplete methods: ").append(details.size())
        .append("\nDistinct reasons: ").append(grouped.size())
        .append("\nPer-method IR evidence: incomplete-method-details.json\n");
    if (!categoryCounts.isEmpty()) {
      report.append("\nFailure categories (methods may have multiple categories):\n");
      for (var category : categoryCounts.entrySet()) {
        report.append("  - ").append(category.getKey()).append(": ")
            .append(category.getValue()).append("\n");
      }
    }
    report.append("\n");
    for (var entry : grouped.entrySet()) {
      report.append("[").append(entry.getValue().size()).append(" methods] ")
          .append(entry.getKey()).append("\n");
      entry.getValue().stream()
          .sorted(Comparator.comparing(MethodStatus::className)
              .thenComparing(MethodStatus::methodName).thenComparing(MethodStatus::descriptor))
          .limit(8)
          .forEach(method -> report.append("  - ")
              .append(method.className().replace('/', '.')).append("#")
              .append(method.methodName()).append(method.descriptor()).append("\n"));
      if (entry.getValue().size() > 8) {
        report.append("  ... and ").append(entry.getValue().size() - 8)
            .append(" more; see javascript-report.json\n");
      }
      report.append("\n");
    }
    if (grouped.isEmpty()) report.append("No incomplete methods were reported.\n");
    Files.writeString(root.resolve("incomplete-diagnostics.txt"), report, StandardCharsets.UTF_8);
  }

  private static String methodKey(String className, String methodName, String descriptor) {
    return className + "#" + methodName + descriptor;
  }

  public record OperationEvidence(int sourceInstruction, String kind, List<String> operands) {
    public OperationEvidence {
      operands = List.copyOf(operands);
    }
  }

  public record IncompleteMethodDetail(
      String className,
      String methodName,
      String descriptor,
      String reason,
      int irOperationCount,
      java.util.Map<String, Integer> operationCounts,
      List<OperationEvidence> operations) {
    public IncompleteMethodDetail {
      operationCounts = java.util.Map.copyOf(operationCounts);
      operations = List.copyOf(operations);
    }
  }

  private static int count(List<MethodStatus> statuses, String status) {
    return (int) statuses.stream().filter(item -> item.status().equals(status)).count();
  }

  private static void writeModuleIndex(List<ClassModel> classes, Path root) throws IOException {
    StringBuilder index = new StringBuilder();
    index.append("// Gold Client whole-mod module registry.\n");
    List<ClassModel> ordered = classes.stream()
        .sorted(Comparator.comparing(ClassModel::name)).toList();
    for (int i = 0; i < ordered.size(); i++) {
      Path module = outputPath(root, ordered.get(i).name());
      String relative = root.relativize(module).toString().replace('\\', '/');
      index.append("import * as C").append(i).append(" from ")
          .append(quote("./" + relative)).append(";\n");
    }
    index.append("\nexport const classes = Object.freeze({\n");
    for (int i = 0; i < ordered.size(); i++) {
      ClassModel clazz = ordered.get(i);
      index.append("  ").append(quote(clazz.name().replace('/', '.')))
          .append(": C").append(i);
      if (i + 1 < ordered.size()) index.append(',');
      index.append("\n");
    }
    index.append("});\n")
        .append("export function getClass(name) { return classes[name] ?? null; }\n")
        .append("export default classes;\n");
    Files.writeString(root.resolve("mod-index.js"), index, StandardCharsets.UTF_8);
  }

  private static void writeClassManifest(List<ClassModel> classes, Path root) throws IOException {
    List<ClassManifest> manifest = classes.stream()
        .sorted(Comparator.comparing(ClassModel::name))
        .map(clazz -> new ClassManifest(
            clazz.name().replace('/', '.'),
            clazz.superName() == null ? null : clazz.superName().replace('/', '.'),
            clazz.interfaces().stream().map(name -> name.replace('/', '.')).toList(),
            clazz.annotations(),
            clazz.referencedClasses().stream().map(name -> name.replace('/', '.')).toList(),
            clazz.fields().stream().map(field -> new FieldManifest(
                field.name(), field.descriptor(), (field.access() & Opcodes.ACC_STATIC) != 0,
                (field.access() & Opcodes.ACC_FINAL) != 0, field.annotations(),
                field.constantValue())).toList(),
            clazz.methods().stream().map(method -> new MethodManifest(
                method.name(), method.descriptor(),
                (method.access() & Opcodes.ACC_STATIC) != 0,
                (method.access() & Opcodes.ACC_ABSTRACT) != 0,
                (method.access() & Opcodes.ACC_NATIVE) != 0,
                method.annotations(), method.referencedClasses().stream()
                    .map(name -> name.replace('/', '.')).toList())).toList()))
        .toList();
    Files.writeString(root.resolve("mod-manifest.json"),
        new GsonBuilder().setPrettyPrinting().create().toJson(manifest) + System.lineSeparator(),
        StandardCharsets.UTF_8);
  }

  public record ClassManifest(String name, String superName, List<String> interfaces,
      List<String> annotations, List<String> referencedClasses,
      List<FieldManifest> fields, List<MethodManifest> methods) {}
  public record FieldManifest(String name, String descriptor, boolean isStatic, boolean isFinal,
      List<String> annotations, Object constantValue) {}
  public record MethodManifest(String name, String descriptor, boolean isStatic,
      boolean isAbstract, boolean isNative, List<String> annotations,
      List<String> referencedClasses) {}

  private static String summary(ExportReport report) {
    return """
        Gold Client JavaScript export report
        Format: %s
        Classes visited: %d
        Methods inspected: %d
        JS-complete for represented IR: %d
        Incomplete: %d
        Skipped: %d
        Stubbed (symbol preserved): %d
        Adapter-required declarations: %d
        Abstract declarations (not executable bodies): %d
        JavaScript class modules: %d
        Preserved JAR resources: %d

        IMPORTANT: syntactically valid JavaScript is not the same as a working mod.
        Minecraft/Forge APIs, event systems, mixins, resources, and loader behavior
        require target-side adapters and are not automatically implemented here.
        """.formatted(report.formatVersion(), report.classCount(), report.methodCount(),
            report.completeCount(), report.incompleteCount(), report.skippedCount(),
            report.stubbedCount(), report.adapterRequiredCount(), count(report.methods(), "declaration-only"),
            report.fileCount(), report.resourceCount());
  }

  private static final String RUNTIME_SOURCE = loadRuntimeSource();

  private static String runtimeSource() {
    return RUNTIME_SOURCE;
  }

  // The runtime lives in a classpath resource because it outgrew the 65,535-byte limit on
  // string constants in the class file format, and a resource keeps it editable as JavaScript.
  private static String loadRuntimeSource() {
    try (InputStream stream = JavaScriptModTranslationExporter.class.getResourceAsStream("gold-client-runtime.js")) {
      if (stream == null) throw new IllegalStateException("Missing gold-client-runtime.js resource.");
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
    } catch (IOException exception) {
      throw new UncheckedIOException("Failed to read gold-client-runtime.js.", exception);
    }
  }

  public record ExportReport(String formatVersion, int classCount, int methodCount,
      int completeCount, int incompleteCount, int skippedCount, int stubbedCount, int adapterRequiredCount,
      int fileCount, int resourceCount,
      List<MethodStatus> methods) {
    public ExportReport { methods = List.copyOf(methods); }
  }

  public record MethodStatus(String className, String methodName, String descriptor,
      String status, String reason) {}
}
