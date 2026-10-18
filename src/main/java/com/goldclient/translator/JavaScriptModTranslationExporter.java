package com.goldclient.translator;

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
    return export(null, classes, outputDirectory);
  }

  public ExportReport export(Path inputJar, List<ClassModel> classes, Path outputDirectory) throws IOException {
    if (classes == null) throw new IllegalArgumentException("Classes must not be null.");
    if (outputDirectory == null) throw new IllegalArgumentException("Output directory must not be null.");
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
              (method.access() & Opcodes.ACC_STATIC) != 0);
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

    ClassCoverageReport classCoverage = writeClassCoverage(inputJar, classes, root);
    int resourceCount = copyResources(inputJar, root);
    Files.writeString(root.resolve("gold-client-runtime.js"), runtimeSource(), StandardCharsets.UTF_8);
    writeModuleIndex(classes, root);
    writeClassManifest(classes, root);
    new ForgeLifecycleExporter().export(classes, root);
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
            .append(", isAdapterRequired: ")
            .append((method.access() & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0
                || method.intermediateRepresentation().operations().isEmpty())
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
    for (var field : clazz.fields()) {
      if ((field.access() & Opcodes.ACC_STATIC) == 0) {
        module.append("    this.").append(field.name()).append(" = ")
            .append(defaultValue(field.descriptor())).append(";\n");
      }
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
      if (hasStatic) {
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
      module.append("  static ").append(name)
          .append("(...args) { return GoldClientRuntime.invokeStatic(this, __goldMethods, ")
          .append(quote(name)).append(", args); }\n");
    }
    module.append("}\n");
    for (var field : clazz.fields()) {
      if ((field.access() & Opcodes.ACC_STATIC) != 0) {
        module.append(className).append('.').append(field.name()).append(" = ")
            .append(fieldInitializer(field, true)).append(";\n");
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
        .append("], methods: __goldMethods });\n");
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

  private static int copyResources(Path inputJar, Path root) throws IOException {
    if (inputJar == null) return 0;
    Path resourceRoot = root.resolve("resources").normalize();
    Files.createDirectories(resourceRoot);
    List<? extends ZipEntry> entries;
    try (ZipFile zip = new ZipFile(inputJar.toFile())) {
      entries = Collections.list(zip.entries()).stream()
          .filter(entry -> !entry.isDirectory())
          .sorted(Comparator.comparing(ZipEntry::getName))
          .toList();
      List<ResourceRecord> copied = new ArrayList<>();
      for (ZipEntry entry : entries) {
        String name = entry.getName().replace('\\', '/');
        if (name.endsWith(".class") || isSignatureFile(name)) continue;
        Path destination = resourceRoot.resolve(name).normalize();
        if (!destination.startsWith(resourceRoot)) {
          throw new IOException("Rejected unsafe JAR resource path: " + name);
        }
        Files.createDirectories(destination.getParent());
        try (var stream = zip.getInputStream(entry)) {
          Files.copy(stream, destination, StandardCopyOption.REPLACE_EXISTING);
        }
        copied.add(new ResourceRecord(name, Files.size(destination)));
      }
      Files.writeString(root.resolve("resource-index.json"),
          new GsonBuilder().setPrettyPrinting().create().toJson(copied) + System.lineSeparator(),
          StandardCharsets.UTF_8);
      return copied.size();
    }
  }

  /**
   * Audits class coverage independently from method completeness. Every class
   * in the input JAR must have a ClassModel and a generated module; unsupported
   * methods are still represented explicitly inside that module.
   */
  private static ClassCoverageReport writeClassCoverage(
      Path inputJar, List<ClassModel> classes, Path root) throws IOException {
    java.util.Set<String> modeled = new java.util.TreeSet<>();
    for (ClassModel clazz : classes) {
      modeled.add(clazz.name().replace('.', '/'));
    }

    java.util.Set<String> jarClasses = new java.util.TreeSet<>();
    java.util.Set<String> unreadableClassEntries = new java.util.TreeSet<>();
    int jarClassEntries = 0;
    if (inputJar != null) {
      try (ZipFile zip = new ZipFile(inputJar.toFile())) {
        for (ZipEntry entry : Collections.list(zip.entries())) {
          if (entry.isDirectory() || !entry.getName().endsWith(".class")) continue;
          jarClassEntries++;
          try (var stream = zip.getInputStream(entry)) {
            jarClasses.add(new org.objectweb.asm.ClassReader(stream).getClassName());
          } catch (RuntimeException invalidClass) {
            // Keep export resilient for malformed .class payloads, but never report
            // complete coverage when an input class entry could not be inspected.
            unreadableClassEntries.add(entry.getName());
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

    ClassCoverageReport report = new ClassCoverageReport(
        "gold-client-class-coverage-v1",
        inputJar == null ? null : inputJar.toAbsolutePath().normalize().toString(),
        jarClassEntries, jarClasses.size(), modeled.size(), translatedModules,
        unmodeledJarClasses, modeledClassesAbsentFromJar, missingModules, unreadableClassEntries,
        unmodeledJarClasses.isEmpty() && modeledClassesAbsentFromJar.isEmpty()
            && missingModules.isEmpty() && unreadableClassEntries.isEmpty());
    Files.writeString(root.resolve("class-coverage.json"),
        new GsonBuilder().setPrettyPrinting().create().toJson(report)
            + System.lineSeparator(), StandardCharsets.UTF_8);
    return report;
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
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
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
