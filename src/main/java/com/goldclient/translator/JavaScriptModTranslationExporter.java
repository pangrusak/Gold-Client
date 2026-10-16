package com.goldclient.translator;

import com.goldclient.model.ClassModel;
import com.goldclient.model.FieldModel;
import com.goldclient.model.MethodModel;
import com.google.gson.GsonBuilder;
import java.io.IOException;
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
          status = "adapter-required";
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
    return report;
  }

  /**
   * Adds a real JavaScript class facade around the translated function exports.
   * Method bodies stay independently exportable, while callers get Java-like
   * instance/static dispatch and constructors that execute the recovered body.
   */
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
          .append(quote(name)).append(", args); }\\n");
    }
    for (String name : inheritedStatic) {
      module.append("  static ").append(name)
          .append("(...args) { return GoldClientRuntime.invokeStatic(this, __goldMethods, ")
          .append(quote(name)).append(", args); }\\n");
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
        .append(className).append(", (__goldMethods.get(\\\"<clinit>\\\") ?? [])")
        .append(".find(method => method.isStatic)?.fn ?? null, { superName: ")
        .append(clazz.superName() == null ? "null" : quote(clazz.superName().replace('/', '.')))
        .append(", interfaces: [")
        .append(String.join(", ", clazz.interfaces().stream()
            .map(name -> quote(name.replace('/', '.'))).toList()))
        .append("], methods: __goldMethods });\\n");
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
        JavaScript class modules: %d
        Preserved JAR resources: %d

        IMPORTANT: syntactically valid JavaScript is not the same as a working mod.
        Minecraft/Forge APIs, event systems, mixins, resources, and loader behavior
        require target-side adapters and are not automatically implemented here.
        """.formatted(report.formatVersion(), report.classCount(), report.methodCount(),
            report.completeCount(), report.incompleteCount(), report.skippedCount(),
            report.stubbedCount(), report.adapterRequiredCount(), report.fileCount(), report.resourceCount());
  }

  private static String runtimeSource() {
    return runtimeSourcePartOne() + runtimeSourcePartTwo();
  }

  private static String runtimeSourcePartOne() {
    return """
        // Small compatibility helpers used by generated JavaScript.
        // This is not a replacement for Minecraft/Forge or a full JVM runtime.
        class GoldClientJavaList {
          constructor(initial = []) {
            this.items = Array.isArray(initial) ? initial.slice() : [];
          }
          add(...args) { if (args.length > 1) { this.addAt(args[0], args[1]); return; } this.items.push(args[0]); return true; }
          offer(value) { this.items.push(value); return true; }
          push(value) { this.items.push(value); return; }
          pop() { if (!this.items.length) throw new Error("NoSuchElementException"); return this.items.pop(); }
          poll() { return this.items.length ? this.items.shift() : null; }
          peek() { return this.items.length ? this.items[0] : null; }
          element() { if (!this.items.length) throw new Error("NoSuchElementException"); return this.items[0]; }
          addFirst(value) { this.items.unshift(value); }
          addLast(value) { this.items.push(value); }
          offerFirst(value) { this.items.unshift(value); return true; }
          offerLast(value) { this.items.push(value); return true; }
          pollFirst() { return this.poll(); }
          pollLast() { return this.items.length ? this.items.pop() : null; }
          peekFirst() { return this.peek(); }
          peekLast() { return this.items.length ? this.items[this.items.length - 1] : null; }
          getFirst() { return this.element(); }
          getLast() { if (!this.items.length) throw new Error("NoSuchElementException"); return this.items[this.items.length - 1]; }
          removeFirst() { return this.removeAt(0); }
          removeLast() { return this.removeAt(this.items.length - 1); }
          addAt(index, value) { this.#checkPosition(index); this.items.splice(index, 0, value); return; }
          addAll(values) {
            const list = values?.items ?? values;
            const copy = Array.from(list ?? []);
            this.items.push(...copy);
            return copy.length > 0;
          }
          addAllAt(index, values) {
            this.#checkPosition(index);
            const copy = Array.from(values?.items ?? values ?? []);
            this.items.splice(index, 0, ...copy);
            return copy.length > 0;
          }
          get(index) { this.#checkIndex(index); return this.items[index]; }
          set(index, value) { this.#checkIndex(index); const old = this.items[index]; this.items[index] = value; return old; }
          size() { return this.items.length; }
          isEmpty() { return this.items.length === 0; }
          contains(value) { return this.items.some(item => Object.is(item, value)); }
          containsAll(values) { return Array.from(values?.items ?? values ?? []).every(value => this.contains(value)); }
          indexOf(value) { return this.items.findIndex(item => Object.is(item, value)); }
          lastIndexOf(value) { return this.items.findLastIndex ? this.items.findLastIndex(item => Object.is(item, value)) : this.items.map(item => Object.is(item, value)).lastIndexOf(true); }
          clear() { this.items.length = 0; }
          remove(value) {
            const index = typeof value === "number" ? value : this.indexOf(value);
            if (index < 0 || index >= this.items.length) return false;
            this.items.splice(index, 1); return true;
          }
          removeAt(index) { this.#checkIndex(index); return this.items.splice(index, 1)[0]; }
          removeAll(values) {
            const candidates = Array.from(values?.items ?? values ?? []);
            const before = this.items.length;
            this.items = this.items.filter(item => !candidates.some(value => Object.is(value, item)));
            return before !== this.items.length;
          }
          retainAll(values) {
            const candidates = Array.from(values?.items ?? values ?? []);
            const before = this.items.length;
            this.items = this.items.filter(item => candidates.some(value => Object.is(value, item)));
            return before !== this.items.length;
          }
          replaceAll(callback) { this.items = this.items.map((value, index) => callback(value, index)); }
          removeIf(predicate) {
            const before = this.items.length;
            this.items = this.items.filter((value, index) => !predicate(value, index));
            return before !== this.items.length;
          }
          subList(from, to) {
            if (from < 0 || to > this.items.length || from > to) throw new RangeError("IndexOutOfBoundsException");
            return new GoldClientJavaList(this.items.slice(from, to));
          }
          toArray() { return this.items.slice(); }
          sort(comparator) { this.items.sort(comparator ?? ((a, b) => String(a).localeCompare(String(b)))); }
          replace(index, value) { return this.set(index, value); }
          iterator() { return this.items[Symbol.iterator](); }
          listIterator(index = 0) {
            let cursor = index;
            const owner = this;
            return {
              hasNext: () => cursor < owner.items.length,
              next: () => { if (cursor >= owner.items.length) throw new Error("NoSuchElementException"); return owner.items[cursor++]; },
              hasPrevious: () => cursor > 0,
              previous: () => { if (cursor <= 0) throw new Error("NoSuchElementException"); return owner.items[--cursor]; },
              nextIndex: () => cursor,
              previousIndex: () => cursor - 1,
              remove: () => { if (cursor <= 0) throw new Error("IllegalStateException"); owner.items.splice(--cursor, 1); },
              set: value => { if (cursor <= 0) throw new Error("IllegalStateException"); owner.items[cursor - 1] = value; },
              add: value => owner.items.splice(cursor++, 0, value)
            };
          }
          forEach(callback) { this.items.forEach(callback); }
          stream() { return new GoldClientJavaStream(this.items); }
          toString() { return "[" + this.items.map(value => value == null ? "null" : String(value)).join(", ") + "]"; }
          #checkIndex(index) {
            if (!Number.isInteger(index) || index < 0 || index >= this.items.length) throw new RangeError("IndexOutOfBoundsException: " + index);
          }
          #checkPosition(index) {
            if (!Number.isInteger(index) || index < 0 || index > this.items.length) throw new RangeError("IndexOutOfBoundsException: " + index);
          }
          [Symbol.iterator]() { return this.items[Symbol.iterator](); }
        }
        class GoldClientJavaSet {
          constructor(initial = []) { this.items = new Set(initial?.items ?? initial ?? []); }
          add(value) { const before = this.items.size; this.items.add(value); return this.items.size !== before; }
          addAll(values) { let changed = false; for (const value of values?.items ?? values ?? []) changed = this.add(value) || changed; return changed; }
          contains(value) { return this.items.has(value); }
          containsAll(values) { return Array.from(values?.items ?? values ?? []).every(value => this.contains(value)); }
          size() { return this.items.size; }
          isEmpty() { return this.items.size === 0; }
          remove(value) { return this.items.delete(value); }
          removeAll(values) { let changed = false; for (const value of values?.items ?? values ?? []) changed = this.remove(value) || changed; return changed; }
          retainAll(values) { const keep = new Set(values?.items ?? values ?? []); let changed = false; for (const value of this.items) if (!keep.has(value)) { this.items.delete(value); changed = true; } return changed; }
          clear() { this.items.clear(); }
          toArray() { return Array.from(this.items); }
          forEach(callback) { this.items.forEach(callback); }
          iterator() { return this.items[Symbol.iterator](); }
          stream() { return new GoldClientJavaStream(this.items); }
          [Symbol.iterator]() { return this.items[Symbol.iterator](); }
        }
        class GoldClientJavaMap {
          constructor(initial = []) {
            const entries = initial?.items instanceof Map ? initial.items : (initial instanceof Map ? initial : (initial?.items ?? initial ?? []));
            this.items = new Map(entries);
          }
          put(key, value) { const old = this.items.has(key) ? this.items.get(key) : null; this.items.set(key, value); return old; }
          get(key) { return this.items.has(key) ? this.items.get(key) : null; }
          getOrDefault(key, fallback) { return this.items.has(key) ? this.items.get(key) : fallback; }
          containsKey(key) { return this.items.has(key); }
          containsValue(value) { return Array.from(this.items.values()).some(item => Object.is(item, value)); }
          size() { return this.items.size; }
          isEmpty() { return this.items.size === 0; }
          remove(key) { if (!this.items.has(key)) return null; const old = this.items.get(key); this.items.delete(key); return old; }
          removePair(key, value) { if (!this.items.has(key) || !Object.is(this.items.get(key), value)) return false; this.items.delete(key); return true; }
          replace(key, value) { if (!this.items.has(key)) return null; const old = this.items.get(key); this.items.set(key, value); return old; }
          replacePair(key, oldValue, newValue) { if (!this.items.has(key) || !Object.is(this.items.get(key), oldValue)) return false; this.items.set(key, newValue); return true; }
          putIfAbsent(key, value) { if (!this.items.has(key) || this.items.get(key) == null) this.items.set(key, value); return this.items.get(key); }
          computeIfAbsent(key, fn) { let value = this.items.get(key); if (value == null) { value = fn(key); if (value != null) this.items.set(key, value); } return value ?? null; }
          computeIfPresent(key, fn) { if (!this.items.has(key) || this.items.get(key) == null) return null; const value = fn(key, this.items.get(key)); if (value == null) this.items.delete(key); else this.items.set(key, value); return value ?? null; }
          compute(key, fn) { const value = fn(key, this.items.has(key) ? this.items.get(key) : null); if (value == null) this.items.delete(key); else this.items.set(key, value); return value ?? null; }
          merge(key, value, fn) { const old = this.items.get(key); const next = old == null ? value : fn(old, value); if (next == null) this.items.delete(key); else this.items.set(key, next); return next ?? null; }
          clear() { this.items.clear(); }
          putAll(other) { for (const [key, value] of (other?.items ?? other)) this.items.set(key, value); }
          keySet() { return new GoldClientJavaSet(this.items.keys()); }
          values() { return new GoldClientJavaList(Array.from(this.items.values())); }
          entrySet() { return new GoldClientJavaSet(Array.from(this.items.entries()).map(([key, value]) => ({ key, value, getKey() { return this.key; }, getValue() { return this.value; }, setValue(next) { const old = this.value; this.value = next; return old; } }))); }
          forEach(callback) { this.items.forEach((value, key) => callback(key, value)); }
          toObject() { return Object.fromEntries(this.items); }
          [Symbol.iterator]() { return this.items[Symbol.iterator](); }
        }
        class GoldClientJavaUuid {
          constructor(value) {
            this.value = String(value).toLowerCase();
            if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(this.value)
                && !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(this.value)) {
              throw new TypeError("IllegalArgumentException: Invalid UUID string");
            }
          }
          toString() { return this.value; }
          equals(other) { return other instanceof GoldClientJavaUuid && this.value === other.value; }
          compareTo(other) { return this.value.localeCompare(String(other)); }
          getMostSignificantBits() { return BigInt("0x" + this.value.replace(/-/g, "").slice(0, 16)); }
          getLeastSignificantBits() { return BigInt("0x" + this.value.replace(/-/g, "").slice(16)); }
          static randomUUID() {
            const bytes = Array.from({ length: 16 }, () => Math.floor(Math.random() * 256));
            bytes[6] = (bytes[6] & 0x0f) | 0x40;
            bytes[8] = (bytes[8] & 0x3f) | 0x80;
            const hex = bytes.map(value => value.toString(16).padStart(2, "0")).join("");
            return new GoldClientJavaUuid(hex.slice(0, 8) + "-" + hex.slice(8, 12) + "-" + hex.slice(12, 16) + "-" + hex.slice(16, 20) + "-" + hex.slice(20));
          }
          static fromString(value) { return new GoldClientJavaUuid(value); }
        }

        class GoldClientJavaBitSet {
          constructor(initial = []) {
            this.bits = new Set();
            for (const bit of initial?.bits ?? initial ?? []) if (Number.isInteger(bit) && bit >= 0) this.bits.add(bit);
          }
          set(from, to, value = true) {
            if (typeof to === "boolean") { value = to; to = from + 1; }
            if (to === undefined) to = from + 1;
            if (from < 0 || to < from) throw new RangeError("IndexOutOfBoundsException");
            for (let bit = from; bit < to; bit++) value ? this.bits.add(bit) : this.bits.delete(bit);
          }
          get(from, to) {
            if (to === undefined) return this.bits.has(from);
            const result = new GoldClientJavaBitSet();
            for (const bit of this.bits) if (bit >= from && bit < to) result.bits.add(bit - from);
            return result;
          }
          clear(from, to) {
            if (from === undefined) { this.bits.clear(); return; }
            if (to === undefined) { this.bits.delete(from); return; }
            for (let bit = from; bit < to; bit++) this.bits.delete(bit);
          }
          flip(from, to) {
            if (to === undefined) to = from + 1;
            if (from < 0 || to < from) throw new RangeError("IndexOutOfBoundsException");
            for (let bit = from; bit < to; bit++) this.bits.has(bit) ? this.bits.delete(bit) : this.bits.add(bit);
          }
          nextSetBit(from) { return Array.from(this.bits).filter(bit => bit >= from).sort((a, b) => a - b)[0] ?? -1; }
          nextClearBit(from) { let bit = from; while (this.bits.has(bit)) bit++; return bit; }
          previousSetBit(from) { return Array.from(this.bits).filter(bit => bit <= from).sort((a, b) => b - a)[0] ?? -1; }
          cardinality() { return this.bits.size; }
          length() { return this.bits.size ? Math.max(...this.bits) + 1 : 0; }
          isEmpty() { return this.bits.size === 0; }
          intersects(other) { return Array.from(this.bits).some(bit => other.bits.has(bit)); }
          and(other) { for (const bit of this.bits) if (!other.bits.has(bit)) this.bits.delete(bit); }
          or(other) { for (const bit of other.bits) this.bits.add(bit); }
          xor(other) { for (const bit of other.bits) this.bits.has(bit) ? this.bits.delete(bit) : this.bits.add(bit); }
          andNot(other) { for (const bit of other.bits) this.bits.delete(bit); }
          clone() { return new GoldClientJavaBitSet(this.bits); }
          toArray() { return Array.from(this.bits).sort((a, b) => a - b); }
          toString() { return "{" + this.toArray().join(", ") + "}"; }
          static valueOf(values) {
            const result = new GoldClientJavaBitSet();
            Array.from(values ?? []).forEach((value, index) => { if (value) result.set(index); });
            return result;
          }
        }

        class GoldClientJavaLocale {
          constructor(language = "", country = "", variant = "") {
            this.language = String(language).toLowerCase();
            this.country = String(country).toUpperCase();
            this.variant = String(variant);
          }
          getLanguage() { return this.language; }
          getCountry() { return this.country; }
          getVariant() { return this.variant; }
          getDisplayLanguage() { return this.language; }
          getDisplayCountry() { return this.country; }
          toLanguageTag() { return [this.language, this.country].filter(Boolean).join("-") || "und"; }
          toString() { return [this.language, this.country, this.variant].filter(Boolean).join(this.variant ? "_" : "_"); }
          equals(other) { return other instanceof GoldClientJavaLocale && this.toLanguageTag() === other.toLanguageTag() && this.variant === other.variant; }
          static forLanguageTag(tag) {
            const parts = String(tag).replace(/_/g, "-").split("-");
            return new GoldClientJavaLocale(parts[0] === "und" ? "" : parts[0], parts[1] ?? "", parts.slice(2).join("_"));
          }
          static getDefault() { return GoldClientJavaLocale.defaultLocale ?? new GoldClientJavaLocale("en", "US"); }
          static setDefault(locale) { GoldClientJavaLocale.defaultLocale = locale; }
        }

        class GoldClientJavaProperties extends GoldClientJavaMap {
          setProperty(key, value) { const old = this.put(String(key), String(value)); return old; }
          getProperty(key, fallback = null) {
            const value = this.get(String(key));
            return value == null ? fallback : String(value);
          }
          propertyNames() { return this.keySet().iterator(); }
          stringPropertyNames() { return new GoldClientJavaSet(Array.from(this.items.keys()).filter(key => typeof key === "string")); }
          list(output = console) {
            for (const [key, value] of this.items) output?.println?.(key + "=" + value);
          }
          store(output, comment = "") {
            const lines = [];
            if (comment) lines.push("# " + comment);
            lines.push("# " + new Date().toISOString());
            for (const [key, value] of this.items) lines.push(String(key) + "=" + String(value));
            const text = lines.join("\\n") + "\\n";
            if (typeof output === "function") output(text);
            else if (output && typeof output.write === "function") output.write(text);
            return text;
          }
        }

        class GoldClientJavaTimeUnit {
          static factors = Object.freeze({
            NANOSECONDS: 1e-9, MICROSECONDS: 1e-6, MILLISECONDS: 1e-3,
            SECONDS: 1, MINUTES: 60, HOURS: 3600, DAYS: 86400
          });
          constructor(name) {
            if (!Object.prototype.hasOwnProperty.call(GoldClientJavaTimeUnit.factors, name)) throw new TypeError("IllegalArgumentException: unknown TimeUnit " + name);
            this.name = name;
            this.factor = GoldClientJavaTimeUnit.factors[name];
          }
          convert(duration, sourceUnit) {
            const seconds = Number(duration) * sourceUnit.factor;
            const result = seconds / this.factor;
            if (this.name === "NANOSECONDS" || this.name === "MICROSECONDS" || this.name === "MILLISECONDS") return Math.trunc(result);
            return Math.trunc(result);
          }
          toNanos(duration) { return Math.trunc(Number(duration) * this.factor / 1e-9); }
          toMicros(duration) { return Math.trunc(Number(duration) * this.factor / 1e-6); }
          toMillis(duration) { return Math.trunc(Number(duration) * this.factor / 1e-3); }
          toSeconds(duration) { return Math.trunc(Number(duration) * this.factor); }
          toMinutes(duration) { return Math.trunc(Number(duration) * this.factor / 60); }
          toHours(duration) { return Math.trunc(Number(duration) * this.factor / 3600); }
          toDays(duration) { return Math.trunc(Number(duration) * this.factor / 86400); }
          toString() { return this.name; }
          static named(name) { return new GoldClientJavaTimeUnit(String(name)); }
        }

        class GoldClientJavaRandom {
          constructor(seed = Date.now()) { this.setSeed(seed); }
          setSeed(seed) { this.seed = (Number(seed) ^ 0x6d2b79f5) >>> 0; this.haveGaussian = false; this.nextGaussianValue = 0; }
          next(bits = 32) { this.seed = (Math.imul(this.seed, 1664525) + 1013904223) >>> 0; return this.seed >>> (32 - Math.max(1, Math.min(32, bits))); }
          nextInt(bound) {
            if (bound === undefined) return this.next(32) | 0;
            if (!Number.isInteger(bound) || bound <= 0) throw new RangeError("IllegalArgumentException: bound must be positive");
            return Math.floor((this.next(32) >>> 0) / 4294967296 * bound);
          }
          nextLong() { return (BigInt(this.next(32) >>> 0) << 32n) | BigInt(this.next(32) >>> 0); }
          nextBoolean() { return this.next(1) !== 0; }
          nextFloat() { return this.next(24) / 16777216; }
          nextDouble() { return ((this.next(26) * 67108864) + this.next(27)) / 9007199254740992; }
          nextBytes(bytes) { for (let i = 0; i < bytes.length; i++) bytes[i] = this.next(8) & 0xff; return bytes; }
          nextGaussian() {
            if (this.haveGaussian) { this.haveGaussian = false; return this.nextGaussianValue; }
            let u, v, s;
            do { u = 2 * this.nextDouble() - 1; v = 2 * this.nextDouble() - 1; s = u * u + v * v; } while (s >= 1 || s === 0);
            const multiplier = Math.sqrt(-2 * Math.log(s) / s);
            this.nextGaussianValue = v * multiplier;
            this.haveGaussian = true;
            return u * multiplier;
          }
        }

        class GoldClientJavaAtomic {
          constructor(value = 0) { this.value = value; }
          get() { return this.value; }
          set(value) { this.value = value; }
          lazySet(value) { this.value = value; }
          getAndSet(value) { const old = this.value; this.value = value; return old; }
          compareAndSet(expected, update) { if (!Object.is(this.value, expected)) return false; this.value = update; return true; }
          weakCompareAndSet(expected, update) { return this.compareAndSet(expected, update); }
          getAndIncrement() { const old = this.value; this.value++; return old; }
          incrementAndGet() { return ++this.value; }
          getAndDecrement() { const old = this.value; this.value--; return old; }
          decrementAndGet() { return --this.value; }
          getAndAdd(delta) { const old = this.value; this.value += delta; return old; }
          addAndGet(delta) { this.value += delta; return this.value; }
          getAndUpdate(update) { const old = this.value; this.value = update(old); return old; }
          updateAndGet(update) { this.value = update(this.value); return this.value; }
          getAndAccumulate(value, fn) { const old = this.value; this.value = fn(old, value); return old; }
          accumulateAndGet(value, fn) { this.value = fn(this.value, value); return this.value; }
          intValue() { return Number(this.value) | 0; }
          longValue() { return this.value; }
          booleanValue() { return Boolean(this.value); }
          toString() { return String(this.value); }
        }

        class GoldClientJavaPriorityQueue extends GoldClientJavaList {
          constructor(initial = [], comparator = null) {
            super(initial?.items ?? initial ?? []);
            this.comparator = comparator ?? ((a, b) => a < b ? -1 : a > b ? 1 : 0);
            this.#sort();
          }
          #sort() { this.items.sort(this.comparator); }
          add(value) { this.items.push(value); this.#sort(); return true; }
          offer(value) { return this.add(value); }
          addAll(values) { const added = super.addAll(values); this.#sort(); return added; }
          poll() { return this.items.length ? this.items.shift() : null; }
          peek() { return this.items.length ? this.items[0] : null; }
          remove(value) { const removed = super.remove(value); this.#sort(); return removed; }
          clear() { super.clear(); }
          iterator() { return this.items.slice()[Symbol.iterator](); }
        }

        class GoldClientJavaDate {
          constructor(value = Date.now()) { this.date = value instanceof Date ? new Date(value.getTime()) : new Date(value); }
          getTime() { return this.date.getTime(); }
          setTime(value) { this.date.setTime(Number(value)); return this.getTime(); }
          before(other) { return this.getTime() < other.getTime(); }
          after(other) { return this.getTime() > other.getTime(); }
          compareTo(other) { return Math.sign(this.getTime() - other.getTime()); }
          equals(other) { return other instanceof GoldClientJavaDate && this.getTime() === other.getTime(); }
          toInstant() { return new Date(this.getTime()); }
          toISOString() { return this.date.toISOString(); }
          toString() { return this.date.toString(); }
          static now() { return new GoldClientJavaDate(Date.now()); }
        }

        """;
  }

  private static String runtimeSourcePartTwo() {
    return """
        class GoldClientJavaStream {
          constructor(values = []) {
            this.values = Array.from(values?.items ?? values ?? []);
            this.closed = false;
          }
          #assertOpen() { if (this.closed) throw new Error("IllegalStateException: stream has already been operated upon or closed"); }
          #next(values) { this.#assertOpen(); return new GoldClientJavaStream(values); }
          map(mapper) { this.#assertOpen(); return this.#next(this.values.map(mapper)); }
          filter(predicate) { this.#assertOpen(); return this.#next(this.values.filter(predicate)); }
          flatMap(mapper) {
            this.#assertOpen();
            const flattened = [];
            for (const value of this.values) {
              const mapped = mapper(value);
              flattened.push(...Array.from(mapped?.values ?? mapped?.items ?? mapped ?? []));
            }
            return this.#next(flattened);
          }
          peek(action) { this.#assertOpen(); this.values.forEach(action); return this; }
          distinct() { this.#assertOpen(); return this.#next(Array.from(new Set(this.values))); }
          sorted(comparator) {
            this.#assertOpen();
            const values = this.values.slice();
            values.sort(typeof comparator === "function" ? comparator : ((a, b) => a < b ? -1 : a > b ? 1 : 0));
            return this.#next(values);
          }
          limit(count) { this.#assertOpen(); if (count < 0) throw new RangeError("IllegalArgumentException: negative limit"); return this.#next(this.values.slice(0, count)); }
          skip(count) { this.#assertOpen(); if (count < 0) throw new RangeError("IllegalArgumentException: negative skip"); return this.#next(this.values.slice(count)); }
          sequential() { this.#assertOpen(); return this; }
          parallel() { this.#assertOpen(); return this; }
          unordered() { this.#assertOpen(); return this; }
          onClose(callback) { this.closeCallback = callback; return this; }
          close() { if (!this.closed) { this.closed = true; this.closeCallback?.(); } }
          forEach(action) { this.#assertOpen(); this.values.forEach(action); this.close(); }
          forEachOrdered(action) { return this.forEach(action); }
          toArray(generator) { this.#assertOpen(); return generator ? generator(this.values.length).map((_, i) => this.values[i]) : this.values.slice(); }
          count() { this.#assertOpen(); return this.values.length; }
          anyMatch(predicate) { this.#assertOpen(); return this.values.some(predicate); }
          allMatch(predicate) { this.#assertOpen(); return this.values.every(predicate); }
          noneMatch(predicate) { this.#assertOpen(); return !this.values.some(predicate); }
          findFirst() { this.#assertOpen(); return this.values.length ? new GoldClientJavaOptional(this.values[0]) : GoldClientJavaOptional.empty(); }
          findAny() { return this.findFirst(); }
          reduce(...args) {
            this.#assertOpen();
            if (args.length > 1) return this.values.reduce(args[1], args[0]);
            return this.values.length ? new GoldClientJavaOptional(this.values.reduce(args[0])) : GoldClientJavaOptional.empty();
          }
          min(comparator) {
            this.#assertOpen();
            if (!this.values.length) return GoldClientJavaOptional.empty();
            const compare = typeof comparator === "function" ? comparator : ((a, b) => a < b ? -1 : a > b ? 1 : 0);
            return new GoldClientJavaOptional(this.values.reduce((a, b) => compare(a, b) <= 0 ? a : b));
          }
          max(comparator) {
            this.#assertOpen();
            if (!this.values.length) return GoldClientJavaOptional.empty();
            const compare = typeof comparator === "function" ? comparator : ((a, b) => a < b ? -1 : a > b ? 1 : 0);
            return new GoldClientJavaOptional(this.values.reduce((a, b) => compare(a, b) >= 0 ? a : b));
          }
          collect(collector, valueMapper, mergeFunction) {
            this.#assertOpen();
            if (collector && typeof collector === "function") return this.values.reduce(collector, valueMapper);
            if (collector?.kind === "list") return new GoldClientJavaList(this.values);
            if (collector?.kind === "set") return new GoldClientJavaSet(this.values);
            if (collector?.kind === "joining") return this.values.map(String).join(collector.delimiter ?? "");
            if (collector?.kind === "counting") return this.values.length;
            if (collector?.kind === "toMap") {
              const map = new GoldClientJavaMap();
              for (const value of this.values) {
                const key = collector.keyMapper(value);
                const mapped = collector.valueMapper(value);
                if (map.containsKey(key)) {
                  if (!collector.mergeFunction) throw new Error("IllegalStateException: duplicate key " + key);
                  map.put(key, collector.mergeFunction(map.get(key), mapped));
                } else map.put(key, mapped);
              }
              return map;
            }
            return this.values.slice();
          }
          toList() { this.#assertOpen(); return new GoldClientJavaList(this.values); }
          iterator() { this.#assertOpen(); return this.values[Symbol.iterator](); }
          [Symbol.iterator]() { this.#assertOpen(); return this.values[Symbol.iterator](); }
        }

        class GoldClientJavaMatcher {
          constructor(pattern, input) {
            this.pattern = pattern;
            this.input = String(input);
            this.cursor = 0;
            this.last = null;
          }
          find() {
            const flags = this.pattern.flags.includes("g") ? this.pattern.flags : this.pattern.flags + "g";
            const regex = new RegExp(this.pattern.source, flags);
            regex.lastIndex = this.cursor;
            const match = regex.exec(this.input);
            if (!match) { this.last = null; return false; }
            this.last = match;
            this.cursor = match.index + Math.max(match[0].length, 1);
            return true;
          }
          matches() {
            const regex = new RegExp("^(?:" + this.pattern.source + ")$", this.pattern.flags.replace(/[gy]/g, ""));
            this.last = regex.exec(this.input);
            return this.last !== null;
          }
          lookingAt() {
            const regex = new RegExp("^(?:" + this.pattern.source + ")", this.pattern.flags.replace(/[gy]/g, ""));
            this.last = regex.exec(this.input);
            return this.last !== null;
          }
          group(index = 0) {
            if (!this.last) throw new Error("IllegalStateException: No match available");
            if (index < 0 || index >= this.last.length) throw new RangeError("IndexOutOfBoundsException");
            return this.last[index] ?? null;
          }
          groupCount() { return Math.max(0, (this.last?.length ?? 1) - 1); }
          start(index = 0) {
            if (!this.last) throw new Error("IllegalStateException: No match available");
            if (index === 0) return this.last.index;
            const prefix = this.last[0].indexOf(this.last[index] ?? "");
            return prefix < 0 ? -1 : this.last.index + prefix;
          }
          end(index = 0) {
            const start = this.start(index);
            const value = this.group(index);
            return start < 0 || value == null ? -1 : start + value.length;
          }
          reset(input = this.input) { this.input = String(input); this.cursor = 0; this.last = null; return this; }
          replaceAll(replacement) { return this.input.replace(new RegExp(this.pattern.source, this.pattern.flags.replace(/[gy]/g, "") + "g"), String(replacement)); }
          replaceFirst(replacement) { return this.input.replace(new RegExp(this.pattern.source, this.pattern.flags.replace(/[gy]/g, "")), String(replacement)); }
          usePattern(pattern) { this.pattern = pattern; this.cursor = 0; this.last = null; return this; }
        }
        class GoldClientJavaPattern {
          constructor(source, flags = "") { this.source = String(source); this.flags = flags; this.regex = new RegExp(this.source, this.flags); }
          matcher(input) { return new GoldClientJavaMatcher(this, input); }
          pattern() { return this.source; }
          flagsValue() { return this.flags; }
          split(input, limit = 0) { return String(input).split(this.regex, limit === 0 ? undefined : limit); }
          toString() { return this.source; }
          static compile(source, flags = "") {
            let jsFlags = "";
            if (typeof flags === "string") jsFlags = flags;
            else {
              if (flags & 2) jsFlags += "i";
              if (flags & 4) jsFlags += "m";
              if (flags & 32) jsFlags += "s";
            }
            return new GoldClientJavaPattern(source, [...new Set(jsFlags)].join(""));
          }
          static matches(regex, input) { return new RegExp("^(?:" + regex + ")$").test(String(input)); }
          static quote(value) { return Array.from(String(value), character => ".^$*+?()[]{}|".includes(character) || character.charCodeAt(0) === 92 ? "\\\\" + character : character).join(""); }
        }

        class GoldClientJavaOptional {
          constructor(value, present = value !== null && value !== undefined) {
            this.present = Boolean(present);
            this.value = this.present ? value : null;
          }
          isPresent() { return this.present; }
          isEmpty() { return !this.present; }
          get() {
            if (!this.present) throw new Error("NoSuchElementException: No value present");
            return this.value;
          }
          orElse(fallback) { return this.present ? this.value : fallback; }
          orElseGet(supplier) { return this.present ? this.value : supplier(); }
          orElseThrow(supplier) {
            if (this.present) return this.value;
            throw typeof supplier === "function" ? supplier() : new Error("NoSuchElementException: No value present");
          }
          ifPresent(consumer) { if (this.present) consumer(this.value); }
          ifPresentOrElse(consumer, emptyAction) { if (this.present) consumer(this.value); else emptyAction(); }
          map(mapper) { return this.present ? new GoldClientJavaOptional(mapper(this.value)) : new GoldClientJavaOptional(null, false); }
          flatMap(mapper) {
            if (!this.present) return new GoldClientJavaOptional(null, false);
            const result = mapper(this.value);
            if (!(result instanceof GoldClientJavaOptional)) throw new TypeError("Optional.flatMap mapper must return Optional");
            return result;
          }
          filter(predicate) { return !this.present || predicate(this.value) ? this : new GoldClientJavaOptional(null, false); }
          stream() { return new GoldClientJavaStream(this.present ? [this.value] : []); }
          toString() { return this.present ? "Optional[" + String(this.value) + "]" : "Optional.empty"; }
          static empty() { return new GoldClientJavaOptional(null, false); }
          static of(value) {
            if (value == null) throw new TypeError("NullPointerException");
            return new GoldClientJavaOptional(value);
          }
          static ofNullable(value) { return new GoldClientJavaOptional(value); }
        }

        class GoldClientJavaStringBuilder {
          constructor(initial = "") { this.value = initial == null ? "" : String(initial); }
          append(value) { this.value += value == null ? "null" : String(value); return this; }
          toString() { return this.value; }
          length() { return this.value.length; }
          charAt(index) { return this.value.charAt(index); }
          setLength(length) { this.value = this.value.slice(0, length).padEnd(length, "\\0"); return this; }
          delete(start, end) { this.value = this.value.slice(0, start) + this.value.slice(end); return this; }
          insert(index, value) { this.value = this.value.slice(0, index) + String(value) + this.value.slice(index); return this; }
        }

        export const GoldClientRuntime = Object.freeze({
          intDiv(a, b) {
            if (b === 0) throw new Error("ArithmeticException: / by zero");
            // JVM IDIV truncates toward zero. Integer.MIN_VALUE / -1 wraps in 32-bit arithmetic.
            if (a === -2147483648 && b === -1) return -2147483648;
            return Math.trunc(a / b);
          },
          intMod(a, b) {
            if (b === 0) throw new Error("ArithmeticException: / by zero");
            // JVM IREM has the dividend's sign; it is not Math.floorMod.
            if (a === -2147483648 && b === -1) return 0;
            return a - Math.trunc(a / b) * b;
          },
          floorDiv(a, b) {
            if (b === 0) throw new Error("ArithmeticException: / by zero");
            if (a === -2147483648 && b === -1) return -2147483648;
            return Math.floor(a / b);
          },
          floorMod(a, b) {
            if (b === 0) throw new Error("ArithmeticException: / by zero");
            if (a === -2147483648 && b === -1) return 0;
            return a - Math.floor(a / b) * b;
          },
          javaEquals(a, b) {
            if (a === null || a === undefined) return a == b;
            if (typeof a.equals === "function") return Boolean(a.equals(b));
            return a === b;
          },
          requireNonNull(value, message = "null") {
            if (value === null || value === undefined) throw new TypeError(message);
            return value;
          },
          arrayLength(value) {
            if (value === null || value === undefined) throw new TypeError("NullPointerException: arraylength");
            if (!Array.isArray(value) && typeof value.length !== "number") {
              return this.unsupported("arraylength", "value is not an array");
            }
            return value.length;
          },
          arrayIndex(value, index) {
            if (value === null || value === undefined) throw new TypeError("NullPointerException: array access");
            const i = Number(index);
            if (!Number.isInteger(i) || i < 0 || i >= value.length) {
              throw new RangeError("ArrayIndexOutOfBoundsException: " + index);
            }
            return i;
          },
          arrayGet(value, index) {
            return value[this.arrayIndex(value, index)];
          },
          arraySet(value, index, element) {
            value[this.arrayIndex(value, index)] = element;
            return element;
          },
          newArray(kind, length) {
            const size = Number(length);
            if (!Number.isInteger(size) || size < 0) throw new RangeError("invalid array length");
            const defaults = { boolean: false, byte: 0, char: "\0", short: 0,
              int: 0, long: 0, float: 0, double: 0, Object: null };
            return Array(size).fill(Object.prototype.hasOwnProperty.call(defaults, kind)
              ? defaults[kind] : null);
          },
          newMultiArray(kind, dimensions, totalDimensions = dimensions.length) {
            const sizes = dimensions.map(Number);
            for (const size of sizes) {
              if (!Number.isInteger(size) || size < 0) throw new RangeError("invalid array length");
            }
            const defaults = { boolean: false, byte: 0, char: "\0", short: 0,
              int: 0, long: 0, float: 0, double: 0, Object: null };
            const leafDefault = Object.prototype.hasOwnProperty.call(defaults, kind)
              ? defaults[kind] : null;
            const allocate = depth => {
              const size = sizes[depth];
              if (depth === sizes.length - 1) {
                return Array(size).fill(totalDimensions > sizes.length ? null : leafDefault);
              }
              return Array.from({ length: size }, () => allocate(depth + 1));
            };
            return sizes.length === 0 ? [] : allocate(0);
          },
          unmodifiable(collection) {
            const mutators = new Set([
              "add", "addAt", "addAll", "addAllAt", "remove", "removeAt", "removeAll",
              "retainAll", "removeIf", "replaceAll", "set", "clear", "put", "putAll",
              "putIfAbsent", "compute", "computeIfAbsent", "computeIfPresent", "merge",
              "replace", "replacePair", "removePair", "setProperty", "setTime", "flip",
              "and", "or", "xor", "andNot", "poll", "pollFirst", "pollLast", "push", "pop",
              "addFirst", "addLast", "offer", "offerFirst", "offerLast", "removeFirst", "removeLast"
            ]);
            return new Proxy(collection, {
              get(target, property) {
                const value = Reflect.get(target, property, target);
                if (typeof property === "string" && mutators.has(property) && typeof value === "function") {
                  return () => { throw new Error("UnsupportedOperationException: unmodifiable collection"); };
                }
                return typeof value === "function" ? value.bind(target) : value;
              },
              set() { throw new Error("UnsupportedOperationException: unmodifiable collection"); },
              deleteProperty() { throw new Error("UnsupportedOperationException: unmodifiable collection"); }
            });
          },
          javaString(value) {
            if (value === null || value === undefined) return "null";
            if (typeof value === "boolean") return value ? "true" : "false";
            if (typeof value === "object") {
              const method = value.toString;
              if (typeof method === "function" && method !== Object.prototype.toString) {
                return String(method.call(value));
              }
            }
            return String(value);
          },
          concat(values) {
            return values.map(value => this.javaString(value)).join("");
          },
          javaHashCode(value) {
            if (value === null || value === undefined) return 0;
            if (typeof value === "number") return Number(value) | 0;
            if (typeof value === "boolean") return value ? 1231 : 1237;
            const text = String(value);
            let hash = 0;
            for (let i = 0; i < text.length; i++) hash = (Math.imul(31, hash) + text.charCodeAt(i)) | 0;
            return hash;
          },
          arraycopy(source, sourceIndex, target, targetIndex, length) {
            if (source == null || target == null) throw new TypeError("null array");
            if (length < 0) throw new RangeError("negative length");
            const values = Array.prototype.slice.call(source, sourceIndex, sourceIndex + length);
            for (let i = 0; i < values.length; i++) target[targetIndex + i] = values[i];
          },
          toInt32(value) {
            return Number(value) | 0;
          },
          toByte(value) {
            const number = Number(value) | 0;
            return (number << 24) >> 24;
          },
          toShort(value) {
            const number = Number(value) | 0;
            return (number << 16) >> 16;
          },
          toChar(value) {
            return (Number(value) | 0) & 0xffff;
          },
          floatToInt(value) {
            const number = Number(value);
            if (Number.isNaN(number)) return 0;
            if (number >= 2147483647) return 2147483647;
            if (number <= -2147483648) return -2147483648;
            return Math.trunc(number);
          },
          floatToLong(value) {
            const number = Number(value);
            if (Number.isNaN(number)) return 0;
            if (number >= 9223372036854775807) return 9223372036854775807;
            if (number <= -9223372036854775808) return -9223372036854775808;
            return Math.trunc(number);
          },
          checkCast(value, className) {
            if (value === null || value === undefined) return value;
            const constructor = this.classes.get(className);
            if (constructor && !(value instanceof constructor)) {
              throw new TypeError("ClassCastException: " + className);
            }
            return value;
          },
          instanceOf(value, className) {
            if (value === null || value === undefined) return false;
            if (className === "java.lang.Object") return true;
            if (className.endsWith("[]")) return Array.isArray(value);
            if (className === "java.lang.String") {
              return typeof value === "string" || value instanceof String;
            }
            if (className === "java.lang.Number") return typeof value === "number";
            if (className === "java.lang.Boolean") return typeof value === "boolean";
            if (className === "java.lang.CharSequence") {
              return typeof value === "string" || value instanceof String;
            }
            const constructor = this.classes.get(className);
            return constructor ? value instanceof constructor : false;
          },
          classLiteral(className) {
            const runtime = this;
            return Object.freeze({
              __goldClassName: className,
              getName() { return className; },
              getSimpleName() { return className.split(/[.$]/).pop(); },
              isInstance(value) { return runtime.instanceOf(value, className); },
              isAssignableFrom(other) {
                const otherName = other?.__goldClassName ?? other?.name;
                if (otherName === className || className === "java.lang.Object") return true;
                const target = runtime.classes.get(className);
                const source = runtime.classes.get(otherName);
                return Boolean(target && source && source.prototype instanceof target);
              },
              toString() { return (className.endsWith("[]") ? "class " : "class ") + className; }
            });
          },
          methodType(descriptor) {
            return Object.freeze({
              descriptor,
              toString() { return descriptor; },
              parameterCount() {
                const args = descriptor.slice(1, descriptor.indexOf(")"));
                return (args.match(/L[^;]+;|\\[+[^\\[;]+;?|[ZBCSIJFD]/g) ?? []).length;
              }
            });
          },
          classes: new Map(),
          registerClass(name, constructor, staticInitializer = null, metadata = {}) {
            constructor.__goldClassName = name;
            constructor.__goldStaticInitializer = staticInitializer;
            constructor.__goldInitialized = false;
            constructor.__goldSuperName = metadata.superName ?? null;
            constructor.__goldInterfaces = Array.from(metadata.interfaces ?? []);
            constructor.__goldMethods = metadata.methods ?? new Map();
            this.classes.set(name, constructor);
            return constructor;
          },
          ensureInitialized(constructor) {
            if (!constructor || constructor.__goldInitialized) return;
            constructor.__goldInitialized = true;
            if (typeof constructor.__goldStaticInitializer === "function") {
              constructor.__goldStaticInitializer.call(constructor);
            }
          },
          getStaticField(className, fieldName) {
            if (className === "java.util.concurrent.TimeUnit") return GoldClientJavaTimeUnit.named(fieldName);
            if (className === "java.util.Locale") {
              const locales = { ROOT: new GoldClientJavaLocale(), US: new GoldClientJavaLocale("en", "US"),
                UK: new GoldClientJavaLocale("en", "GB"), CANADA: new GoldClientJavaLocale("en", "CA"),
                FRANCE: new GoldClientJavaLocale("fr", "FR"), GERMANY: new GoldClientJavaLocale("de", "DE"),
                JAPAN: new GoldClientJavaLocale("ja", "JP"), CHINA: new GoldClientJavaLocale("zh", "CN") };
              if (Object.prototype.hasOwnProperty.call(locales, fieldName)) return locales[fieldName];
            }
            const constructor = this.classes.get(className);
            if (!constructor) {
              const service = this.platformService(className);
              if (service) {
                const bindings = globalThis.window?.goldClientForgeBindings ?? globalThis.goldClientForgeBindings;
                const target = bindings?.[service];
                if (!target || typeof target.get !== "function") {
                  return this.unsupported("missing-platform-field-binding", className + "." + fieldName);
                }
                return target.get(className, fieldName);
              }
              return this.unsupported("missing-static-field-class", className);
            }
            this.ensureInitialized(constructor);
            return constructor[fieldName];
          },
          setStaticField(className, fieldName, value) {
            const constructor = this.classes.get(className);
            if (!constructor) {
              const service = this.platformService(className);
              if (service) {
                const bindings = globalThis.window?.goldClientForgeBindings ?? globalThis.goldClientForgeBindings;
                const target = bindings?.[service];
                if (!target || typeof target.set !== "function") {
                  return this.unsupported("missing-platform-field-binding", className + "." + fieldName);
                }
                target.set(className, fieldName, value);
                return value;
              }
              return this.unsupported("missing-static-field-class", className);
            }
            this.ensureInitialized(constructor);
            constructor[fieldName] = value;
            return value;
          },
          platformService(owner) {
            if (owner.startsWith("net.minecraft.")) return "minecraft";
            if (owner.startsWith("net.minecraftforge.")) return "forge";
            return null;
          },
          invokePlatformStatic(owner, method, ...args) {
            const service = this.platformService(owner);
            if (!service) return this.unsupported("unknown-platform-class", owner + "." + method);
            const bindings = globalThis.window?.goldClientForgeBindings ?? globalThis.goldClientForgeBindings;
            const target = bindings?.[service];
            if (!target || typeof target.call !== "function") {
              return this.unsupported("missing-platform-method-binding", owner + "." + method);
            }
            return target.call(owner, method, args);
          },
          resolveClass(name) {
            return this.classes.get(name) ?? null;
          },
          create(name, ...args) {
            const constructor = this.classes.get(name);
            if (!constructor) {
              const service = this.platformService(name);
              if (service) {
                const bindings = globalThis.window?.goldClientForgeBindings ?? globalThis.goldClientForgeBindings;
                const target = bindings?.[service];
                if (typeof target?.construct === "function") return target.construct(name, args);
                return this.unsupported("missing-platform-constructor-binding", name);
              }
              return this.unsupported("missing-class-adapter", name);
            }
            return Reflect.construct(constructor, args);
          },
          createJava(name, ...args) {
            switch (name) {
              case "java.util.ArrayList":
              case "java.util.LinkedList":
              case "java.util.Vector":
              case "java.util.Stack":
              case "java.util.ArrayDeque":
              case "java.util.concurrent.ConcurrentLinkedQueue":
              case "java.util.concurrent.CopyOnWriteArrayList":
                return new GoldClientJavaList(Array.from(args[0]?.items ?? args[0] ?? []));
              case "java.util.HashSet":
              case "java.util.LinkedHashSet":
              case "java.util.TreeSet":
                return new GoldClientJavaSet(args[0] ?? []);
              case "java.util.HashMap":
              case "java.util.LinkedHashMap":
              case "java.util.TreeMap":
              case "java.util.IdentityHashMap":
              case "java.util.concurrent.ConcurrentHashMap":
                return new GoldClientJavaMap(args[0] ?? []);
              case "java.lang.StringBuilder":
              case "java.lang.StringBuffer":
                return new GoldClientJavaStringBuilder(args[0] ?? "");
              case "java.util.Optional":
                return new GoldClientJavaOptional(args[0]);
              case "java.util.Date":
                return new GoldClientJavaDate(args.length ? args[0] : Date.now());
              case "java.util.UUID":
                return args.length ? GoldClientJavaUuid.fromString(args[0]) : GoldClientJavaUuid.randomUUID();
              case "java.util.PriorityQueue":
                return new GoldClientJavaPriorityQueue(args[0] ?? [], args[1] ?? null);
              case "java.util.Locale":
                return new GoldClientJavaLocale(args[0] ?? "", args[1] ?? "", args[2] ?? "");
              case "java.util.BitSet":
                return new GoldClientJavaBitSet();
              case "java.util.Random":
              case "java.util.SplittableRandom":
                return new GoldClientJavaRandom(args.length ? args[0] : Date.now());
              case "java.util.concurrent.TimeUnit":
                return GoldClientJavaTimeUnit.named(args[0] ?? "SECONDS");
              case "java.util.Properties":
                return new GoldClientJavaProperties(args[0] ?? []);
              case "java.util.concurrent.atomic.AtomicInteger":
              case "java.util.concurrent.atomic.AtomicLong":
              case "java.util.concurrent.atomic.AtomicBoolean":
              case "java.util.concurrent.atomic.AtomicReference":
                return new GoldClientJavaAtomic(args.length ? args[0] : 0);
              case "java.util.regex.Pattern":
                return new GoldClientJavaPattern(args[0] ?? "");
              case "java.lang.String":
                return String(args[0] ?? "");
              default:
                return this.unsupported("missing-java-class-adapter", name);
            }
          },
          invokeJavaStatic(owner, method, ...args) {
            if (owner === "java.lang.reflect.Array") {
              if (method === "getLength") return this.arrayLength(args[0]);
              if (method === "get") return this.arrayGet(args[0], args[1]);
              if (method === "set") return this.arraySet(args[0], args[1], args[2]);
              if (method === "getBoolean" || method === "getByte" || method === "getChar"
                  || method === "getShort" || method === "getInt" || method === "getLong"
                  || method === "getFloat" || method === "getDouble") {
                return this.arrayGet(args[0], args[1]);
              }
              if (method === "setBoolean" || method === "setByte" || method === "setChar"
                  || method === "setShort" || method === "setInt" || method === "setLong"
                  || method === "setFloat" || method === "setDouble") {
                return this.arraySet(args[0], args[1], args[2]);
              }
              if (method === "newInstance") {
                const type = args[0]?.__goldClassName ?? "java.lang.Object";
                const kind = ({ boolean: "boolean", byte: "byte", char: "char", short: "short",
                  int: "int", long: "long", float: "float", double: "double" })[type] ?? "Object";
                const dimensions = Array.isArray(args[1]) ? args[1] : [args[1]];
                if (dimensions.length === 1) return this.newArray(kind, dimensions[0]);
                return this.newMultiArray(kind, dimensions, dimensions.length);
              }
            }
            if (owner === "java.lang.System") {
              if (method === "identityHashCode") return this.javaHashCode(args[0]);
              if (method === "currentTimeMillis") return Date.now();
              if (method === "nanoTime") return Date.now() * 1000000;
              if (method === "lineSeparator") return "\\n";
              if (method === "getProperty") {
                const properties = { "line.separator": "\\n", "file.separator": "/", "path.separator": ":" };
                return properties[args[0]] ?? args[1] ?? null;
              }
              if (method === "gc") return;
            }
            if (owner === "java.util.Objects") {
              if (method === "equals") return this.javaEquals(args[0], args[1]);
              if (method === "deepEquals") {
                const a = args[0], b = args[1];
                return Array.isArray(a) && Array.isArray(b)
                  ? a.length === b.length && a.every((value, index) => this.javaEquals(value, b[index]))
                  : this.javaEquals(a, b);
              }
              if (method === "hashCode") return this.javaHashCode(args[0]);
              if (method === "hash") return args.reduce((hash, value) =>
                (Math.imul(hash, 31) + this.javaHashCode(value)) | 0, 1);
              if (method === "toString") return args[0] == null ? args[1] : this.javaString(args[0]);
              if (method === "isNull") return args[0] == null;
              if (method === "nonNull") return args[0] != null;
              if (method === "requireNonNull") {
                return this.requireNonNull(args[0], args[1] ?? "null");
              }
              if (method === "compare") {
                const a = args[0], b = args[1];
                return a < b ? -1 : a > b ? 1 : 0;
              }
            }
            if (owner === "java.lang.Class" && method === "forName") {
              return this.classLiteral(String(args[0]).replace(/\\//g, "."));
            }
            if (owner === "java.lang.String") {
              if (method === "valueOf") return args[0] == null ? "null" : this.javaString(args[0]);
              if (method === "join") {
                const delimiter = String(args[0]);
                const values = args.length > 2 ? args.slice(1) : Array.from(args[1] ?? []);
                return values.map(value => value == null ? "" : this.javaString(value)).join(delimiter);
              }
              if (method === "format") {
                let index = 1;
                return String(args[0]).replace(/%([sdif%])/g, (match, specifier) => {
                  if (specifier === "%") return "%";
                  const value = args[index++];
                  if (specifier === "d" || specifier === "i") return String(Number(value) | 0);
                  if (specifier === "f") return String(Number(value));
                  return this.javaString(value);
                });
              }
            }
            if (owner === "java.lang.Integer" || owner === "java.lang.Short"
                || owner === "java.lang.Byte") {
              if (method === "parseInt" || method === "parseShort" || method === "parseByte") {
                const radix = args[1] == null ? 10 : Number(args[1]);
                const value = Number.parseInt(String(args[0]), radix);
                if (!Number.isInteger(value)) throw new TypeError("NumberFormatException: " + args[0]);
                if (method === "parseShort" && (value < -32768 || value > 32767)) throw new RangeError("NumberFormatException");
                if (method === "parseByte" && (value < -128 || value > 127)) throw new RangeError("NumberFormatException");
                return value | 0;
              }
              if (method === "valueOf") return Number(args[0]) | 0;
              if (method === "toString") return String(Number(args[0]) | 0);
              if (method === "compare") return Math.sign((Number(args[0]) | 0) - (Number(args[1]) | 0));
              if (method === "sum") return (Number(args[0]) + Number(args[1])) | 0;
              if (method === "max") return Math.max(Number(args[0]) | 0, Number(args[1]) | 0);
              if (method === "min") return Math.min(Number(args[0]) | 0, Number(args[1]) | 0);
              if (method === "bitCount") {
                let value = Number(args[0]) | 0, count = 0;
                while (value) { count += value & 1; value >>>= 1; }
                return count;
              }
              if (method === "numberOfLeadingZeros") {
                const value = Number(args[0]) | 0;
                return value === 0 ? 32 : Math.clz32(value);
              }
              if (method === "numberOfTrailingZeros") {
                const value = Number(args[0]) | 0;
                return value === 0 ? 32 : 31 - Math.clz32(value & -value);
              }
            }
            if (owner === "java.lang.Boolean") {
              if (method === "parseBoolean") return String(args[0]).toLowerCase() === "true";
              if (method === "valueOf") return typeof args[0] === "boolean"
                ? args[0] : String(args[0]).toLowerCase() === "true";
              if (method === "toString") return args[0] ? "true" : "false";
              if (method === "compare") return args[0] === args[1] ? 0 : args[0] ? 1 : -1;
            }
            if (owner === "java.lang.Math") {
              if (method === "floorDiv") return this.floorDiv(args[0], args[1]);
              if (method === "floorMod") return this.floorMod(args[0], args[1]);
              if (method === "addExact") {
                const value = Number(args[0]) + Number(args[1]);
                if (!Number.isSafeInteger(value) || value < -2147483648 || value > 2147483647) throw new RangeError("ArithmeticException: integer overflow");
                return value;
              }
              if (method === "subtractExact") {
                const value = Number(args[0]) - Number(args[1]);
                if (!Number.isSafeInteger(value) || value < -2147483648 || value > 2147483647) throw new RangeError("ArithmeticException: integer overflow");
                return value;
              }
              if (method === "multiplyExact") {
                const value = Number(args[0]) * Number(args[1]);
                if (!Number.isSafeInteger(value) || value < -2147483648 || value > 2147483647) throw new RangeError("ArithmeticException: integer overflow");
                return value;
              }
              if (method === "incrementExact" || method === "decrementExact" || method === "negateExact") {
                const value = method === "incrementExact" ? Number(args[0]) + 1
                  : method === "decrementExact" ? Number(args[0]) - 1 : -Number(args[0]);
                if (!Number.isSafeInteger(value) || value < -2147483648 || value > 2147483647) throw new RangeError("ArithmeticException: integer overflow");
                return value;
              }
              if (method === "toIntExact") {
                const value = Number(args[0]);
                if (!Number.isInteger(value) || value < -2147483648 || value > 2147483647) throw new RangeError("ArithmeticException: integer overflow");
                return value;
              }
              if (method === "signum") return Math.sign(args[0]);
              if (method === "round") return Math.floor(Number(args[0]) + 0.5);
              if (method === "min") return Math.min(...args);
              if (method === "max") return Math.max(...args);
              if (method === "copySign") return Math.abs(args[0]) * (args[1] < 0 || Object.is(args[1], -0) ? -1 : 1);
              if (method === "nextUp") return args[0] === Infinity ? Infinity : Number(args[0]) + Number.EPSILON;
              if (typeof Math[method] === "function") return Math[method](...args);
            }
            if (owner === "java.util.Arrays") {
              if (method === "asList") return new GoldClientJavaList(args[0] ?? args);
              if (method === "copyOf") {
                const source = Array.from(args[0] ?? []);
                const length = Math.max(0, Number(args[1]) | 0);
                return Array.from({ length }, (_, index) => index < source.length ? source[index] : null);
              }
              if (method === "copyOfRange") {
                const source = Array.from(args[0] ?? []);
                const from = Number(args[1]) | 0;
                const to = Number(args[2]) | 0;
                if (from < 0 || to < from) throw new RangeError("ArrayIndexOutOfBoundsException");
                return Array.from({ length: to - from }, (_, index) => source[from + index] ?? null);
              }
              if (method === "equals" || method === "deepEquals") {
                const a = args[0], b = args[1];
                return Array.isArray(a) && Array.isArray(b) && a.length === b.length
                  && a.every((value, index) => Object.is(value, b[index]));
              }
              if (method === "hashCode") {
                return Array.from(args[0] ?? []).reduce((hash, value) =>
                  (Math.imul(hash, 31) + this.javaHashCode(value)) | 0, 1);
              }
              if (method === "fill") {
                const array = args[0];
                if (args.length === 2) array.fill(args[1]);
                else array.fill(args[3], args[1], args[2]);
                return;
              }
              if (method === "sort") {
                const array = args[0];
                const comparator = typeof args[1] === "function" ? args[1] : ((a, b) => String(a).localeCompare(String(b)));
                array.sort(comparator);
                return;
              }
              if (method === "binarySearch") {
                const array = args[0], target = args[1];
                const comparator = typeof args[2] === "function" ? args[2] : ((a, b) => a < b ? -1 : a > b ? 1 : 0);
                let low = 0, high = array.length - 1;
                while (low <= high) {
                  const mid = (low + high) >>> 1;
                  const compare = comparator(array[mid], target);
                  if (compare < 0) low = mid + 1;
                  else if (compare > 0) high = mid - 1;
                  else return mid;
                }
                return -(low + 1);
              }
              if (method === "toString") return "[" + Array.from(args[0] ?? []).map(value => value == null ? "null" : String(value)).join(", ") + "]";
            }
            if (owner === "java.util.Comparator") {
              if (method === "naturalOrder") return (a, b) => a < b ? -1 : a > b ? 1 : 0;
              if (method === "reverseOrder") return (a, b) => a > b ? -1 : a < b ? 1 : 0;
              if (method === "comparing") {
                const key = args[0];
                return (a, b) => {
                  const av = typeof key === "function" ? key(a) : a?.[key];
                  const bv = typeof key === "function" ? key(b) : b?.[key];
                  return av < bv ? -1 : av > bv ? 1 : 0;
                };
              }
            }
            if (owner === "java.util.Collections") {
              if (method === "emptyList") return new GoldClientJavaList();
              if (method === "emptySet") return new GoldClientJavaSet();
              if (method === "emptyMap") return new GoldClientJavaMap();
              if (method === "singletonList") return new GoldClientJavaList([args[0]]);
              if (method === "singleton") return new GoldClientJavaSet([args[0]]);
              if (method === "singletonMap") return new GoldClientJavaMap([[args[0], args[1]]]);
              if (["unmodifiableList", "unmodifiableSet", "unmodifiableMap"].includes(method)) return this.unmodifiable(args[0]);
              if (["synchronizedList", "synchronizedSet", "synchronizedMap"].includes(method)) return args[0];
              if (method === "addAll") {
                const collection = args[0];
                const values = args.slice(1);
                if (collection?.add) { let changed = false; for (const value of values) changed = collection.add(value) || changed; return changed; }
                if (Array.isArray(collection)) { collection.push(...values); return values.length > 0; }
              }
              if (method === "reverse") { if (args[0]?.items) args[0].items.reverse(); else args[0]?.reverse?.(); return; }
              if (method === "sort") { if (args[0]?.items) args[0].items.sort(args[1]); else args[0]?.sort?.(args[1]); return; }
              if (method === "frequency") { const values = args[0]?.items ?? args[0] ?? []; return Array.from(values).filter(value => Object.is(value, args[1])).length; }
              if (method === "disjoint") {
                const left = Array.from(args[0]?.items ?? args[0] ?? []);
                const right = new Set(args[1]?.items ?? args[1] ?? []);
                return !left.some(value => right.has(value));
              }
              if (method === "min" || method === "max") {
                const values = Array.from(args[0]?.items ?? args[0] ?? []);
                if (!values.length) throw new Error("NoSuchElementException");
                const comparator = typeof args[1] === "function" ? args[1] : ((a, b) => a < b ? -1 : a > b ? 1 : 0);
                return values.reduce((best, value) => {
                  const compare = comparator(value, best);
                  return method === "min" ? (compare < 0 ? value : best) : (compare > 0 ? value : best);
                });
              }
              if (method === "replaceAll") {
                const collection = args[0], oldValue = args[1], newValue = args[2];
                if (collection?.items) collection.items = collection.items.map(value => Object.is(value, oldValue) ? newValue : value);
                else if (Array.isArray(collection)) for (let i = 0; i < collection.length; i++) if (Object.is(collection[i], oldValue)) collection[i] = newValue;
                return;
              }
              if (method === "rotate") {
                const collection = args[0]?.items ?? args[0];
                if (!collection?.length) return;
                const distance = ((Number(args[1]) % collection.length) + collection.length) % collection.length;
                if (distance) collection.unshift(...collection.splice(collection.length - distance, distance));
                return;
              }
              if (method === "shuffle") {
                const collection = args[0]?.items ?? args[0];
                for (let i = collection.length - 1; i > 0; i--) {
                  const j = Math.floor(Math.random() * (i + 1));
                  [collection[i], collection[j]] = [collection[j], collection[i]];
                }
                return;
              }
            }
            if (owner === "java.util.BitSet" && method === "valueOf") return GoldClientJavaBitSet.valueOf(args[0]);
            if (owner === "java.util.Locale") {
              if (method === "forLanguageTag") return GoldClientJavaLocale.forLanguageTag(args[0]);
              if (method === "getDefault") return GoldClientJavaLocale.getDefault();
              if (method === "setDefault") return GoldClientJavaLocale.setDefault(args[0]);
              if (method === "of") return new GoldClientJavaLocale(args[0] ?? "", args[1] ?? "", args[2] ?? "");
            }
            if (owner === "java.util.UUID") {
              if (method === "randomUUID") return GoldClientJavaUuid.randomUUID();
              if (method === "fromString") return GoldClientJavaUuid.fromString(args[0]);
            }
            if (owner === "java.util.Date" && method === "from") return new GoldClientJavaDate(args[0]);
            if (owner === "java.lang.Character") {
              const value = String(args[0] ?? "").charAt(0);
              if (method === "isDigit") return /^\\p{N}$/u.test(value);
              if (method === "isLetter") return /^\\p{L}$/u.test(value);
              if (method === "isLetterOrDigit") return /^[\\p{L}\\p{N}]$/u.test(value);
              if (method === "isWhitespace" || method === "isSpaceChar") return /^\\s$/u.test(value);
              if (method === "isUpperCase") return value.toUpperCase() === value && value.toLowerCase() !== value;
              if (method === "isLowerCase") return value.toLowerCase() === value && value.toUpperCase() !== value;
              if (method === "toUpperCase") return value.toUpperCase().charAt(0);
              if (method === "toLowerCase") return value.toLowerCase().charAt(0);
              if (method === "getNumericValue") return Number.isNaN(Number(value)) ? -1 : Number(value);
              if (method === "charCount") return String.fromCodePoint(Number(args[0])).length;
              if (method === "toString") return value;
              if (method === "compare") return String(args[0]).localeCompare(String(args[1]));
            }
            if (["java.lang.Byte", "java.lang.Short", "java.lang.Float", "java.lang.Double", "java.lang.Number"].includes(owner)) {
              if (method === "parseByte" || method === "parseShort" || method === "parseInt" || method === "parseLong") return Number.parseInt(args[0], args[1] ?? 10);
              if (method === "parseFloat" || method === "parseDouble" || method === "valueOf") return Number(args[0]);
              if (method === "toString") return String(args[0]);
              if (method === "isNaN") return Number.isNaN(Number(args[0]));
              if (method === "isInfinite") return !Number.isFinite(Number(args[0]));
              if (method === "isFinite") return Number.isFinite(Number(args[0]));
              if (method === "compare") return Math.sign(Number(args[0]) - Number(args[1]));
            }
            if (owner === "java.util.stream.Stream") {
              if (method === "empty") return new GoldClientJavaStream();
              if (method === "of") return new GoldClientJavaStream(args.length === 1 && Array.isArray(args[0]) ? args[0] : args);
              if (method === "ofNullable") return new GoldClientJavaStream(args[0] == null ? [] : [args[0]]);
              if (method === "concat") return new GoldClientJavaStream([...(args[0]?.values ?? args[0] ?? []), ...(args[1]?.values ?? args[1] ?? [])]);
              if (method === "iterate") {
                const values = [];
                let value = args[0];
                const predicate = args[2];
                for (let i = 0; i < 10000 && (!predicate || predicate(value)); i++) {
                  values.push(value);
                  value = args[1](value);
                }
                return new GoldClientJavaStream(values);
              }
              if (method === "generate") return new GoldClientJavaStream(Array.from({ length: 1000 }, () => args[0]()));
            }
            if (owner === "java.util.stream.Collectors") {
              if (method === "toList") return { kind: "list" };
              if (method === "toSet") return { kind: "set" };
              if (method === "joining") return { kind: "joining", delimiter: args[0] ?? "" };
              if (method === "counting") return { kind: "counting" };
              if (method === "toMap") return { kind: "toMap", keyMapper: args[0], valueMapper: args[1], mergeFunction: args[2] };
            }
            if (owner === "java.util.regex.Pattern") {
              if (method === "compile") return GoldClientJavaPattern.compile(args[0], args[1] ?? "");
              if (method === "matches") return GoldClientJavaPattern.matches(args[0], args[1]);
              if (method === "quote") return GoldClientJavaPattern.quote(args[0]);
            }
            if (owner === "java.util.Optional") {
              if (method === "empty") return GoldClientJavaOptional.empty();
              if (method === "of") return GoldClientJavaOptional.of(args[0]);
              if (method === "ofNullable") return GoldClientJavaOptional.ofNullable(args[0]);
            }
            if (owner === "java.util.Objects") {
              if (method === "equals") return Object.is(args[0], args[1]);
              if (method === "deepEquals") return JSON.stringify(args[0]) === JSON.stringify(args[1]);
              if (method === "isNull") return args[0] == null;
              if (method === "nonNull") return args[0] != null;
              if (method === "hash") return args.reduce((hash, value) => (Math.imul(hash, 31) + this.javaHashCode(value)) | 0, 1);
              if (method === "toString") return args[0] == null ? (args[1] ?? "null") : String(args[0]);
              if (method === "requireNonNull") return this.requireNonNull(args[0], args[1]);
              if (method === "requireNonNullElse") return args[0] == null ? this.requireNonNull(args[1]) : args[0];
              if (method === "requireNonNullElseGet") return args[0] == null ? this.requireNonNull(args[1]()) : args[0];
              if (method === "compare") return args[0] === args[1] ? 0 : args[0] < args[1] ? -1 : 1;
            }
            if (owner === "java.lang.Integer" || owner === "java.lang.Long") {
              if (method === "parseInt" || method === "parseLong" || method === "valueOf") return Number.parseInt(args[0], args[1] ?? 10);
              if (method === "decode") {
                const value = String(args[0]);
                const sign = value.startsWith("-") ? -1 : 1;
                const unsigned = value.replace(/^[+-]/, "");
                if (/^0x/i.test(unsigned)) return sign * Number.parseInt(unsigned.slice(2), 16);
                if (unsigned.startsWith("#")) return sign * Number.parseInt(unsigned.slice(1), 16);
                if (/^0[0-7]+$/.test(unsigned)) return sign * Number.parseInt(unsigned.slice(1), 8);
                return sign * Number.parseInt(unsigned, 10);
              }
              if (method === "toString") return String(args[0]);
              if (method === "toHexString") return (Number(args[0]) >>> 0).toString(16);
              if (method === "toBinaryString") return (Number(args[0]) >>> 0).toString(2);
              if (method === "toOctalString") return (Number(args[0]) >>> 0).toString(8);
              if (method === "compare") return Math.sign(args[0] - args[1]);
              if (method === "sum") return (Number(args[0]) + Number(args[1])) | 0;
              if (method === "max") return Math.max(args[0], args[1]);
              if (method === "min") return Math.min(args[0], args[1]);
              if (method === "bitCount") {
                let value = Number(args[0]) >>> 0, count = 0;
                while (value) { value &= value - 1; count++; }
                return count;
              }
              if (method === "highestOneBit") {
                const value = Number(args[0]) | 0;
                if (!value) return 0;
                return (1 << (31 - Math.clz32(value >>> 0))) | 0;
              }
              if (method === "lowestOneBit") return (Number(args[0]) | 0) & -(Number(args[0]) | 0);
              if (method === "numberOfLeadingZeros") return Math.clz32(Number(args[0]) >>> 0);
              if (method === "numberOfTrailingZeros") {
                const value = Number(args[0]) >>> 0;
                return value === 0 ? 32 : 31 - Math.clz32(value & -value);
              }
            }
            if (owner === "java.lang.Boolean") {
              if (method === "parseBoolean" || method === "valueOf") return String(args[0]).toLowerCase() === "true";
              if (method === "toString") return String(Boolean(args[0]));
            }
            if (owner === "java.lang.String") {
              if (method === "valueOf") return this.javaString(args[0]);
              if (method === "format") {
                const formatIndex = typeof args[0] === "string" ? 0 : 1;
                const format = String(args[formatIndex] ?? "");
                const values = args.slice(formatIndex + 1);
                let valueIndex = 0;
                return format.replace(/%%|%0?(\\d+)?(?:\\.(\\d+))?([sdif])/g,
                  (token, width, precision, type) => {
                    if (token === "%%") return "%";
                    const value = values[valueIndex++];
                    let rendered;
                    if (type === "d" || type === "i") rendered = String(Math.trunc(Number(value)));
                    else if (type === "f") rendered = Number(value).toFixed(precision === undefined ? 6 : Number(precision));
                    else rendered = this.javaString(value);
                    if (width) rendered = rendered.padStart(Number(width), token.startsWith("%0") ? "0" : " ");
                    return rendered;
                  });
              }
            }
            if (owner === "java.lang.System") {
              if (method === "currentTimeMillis") return Date.now();
              if (method === "nanoTime") return Math.floor(performance.now() * 1e6);
              if (method === "identityHashCode") return this.javaHashCode(args[0]);
              if (method === "arraycopy") return this.arraycopy(...args);
              if (method === "getProperties") return new GoldClientJavaProperties([
                ["line.separator", "\\n"], ["file.separator", "/"], ["path.separator", ":"],
                ["os.name", "Eaglercraft"], ["java.version", "translated-runtime"]
              ]);
              if (method === "getProperty") {
                const properties = { "line.separator": "\\n", "file.separator": "/", "path.separator": ":", "os.name": "Eaglercraft", "java.version": "translated-runtime" };
                return Object.prototype.hasOwnProperty.call(properties, args[0]) ? properties[args[0]] : (args[1] ?? null);
              }
              if (method === "getenv") return args.length ? (args[1] ?? null) : Object.freeze({});
              if (method === "lineSeparator") return "\\n";
            }
            const translated = this.classes.get(owner);
            if (translated && typeof translated[method] === "function") return translated[method](...args);
            return this.unsupported("missing-java-static-adapter", owner + "." + method);
          },
          invokeStaticClass(className, methodName, ...args) {
            const constructor = this.classes.get(className);
            if (!constructor) return this.unsupported("missing-static-class", className);
            this.ensureInitialized(constructor);
            const method = constructor[methodName];
            if (typeof method !== "function") {
              return this.unsupported("missing-static-method", className + "." + methodName);
            }
            return method.apply(constructor, args);
          },
          invokeInstance(receiver, methodName, ...args) {
            if (receiver === null || receiver === undefined) {
              throw new TypeError("NullPointerException: " + methodName);
            }
            if (methodName === "equals") return this.javaEquals(receiver, args[0]);
            if (methodName === "hashCode") return this.javaHashCode(receiver);
            if (methodName === "toString") return this.javaString(receiver);
            if (methodName === "getClass") {
              if (Array.isArray(receiver)) return this.classLiteral("java.lang.Object[]");
              return this.classLiteral(receiver?.constructor?.__goldClassName ?? "java.lang.Object");
            }
            if (Array.isArray(receiver) && methodName === "clone") return receiver.slice();
            if (methodName === "compareTo") {
              const a = String(receiver), b = String(args[0]);
              return a < b ? -1 : a > b ? 1 : 0;
            }
            if (typeof receiver === "string" || receiver instanceof String) {
              const value = String(receiver);
              switch (methodName) {
                case "length": return value.length;
                case "isEmpty": return value.length === 0;
                case "charAt":
                  if (args[0] < 0 || args[0] >= value.length) throw new RangeError("StringIndexOutOfBoundsException");
                  return value.charAt(args[0]);
                case "substring": {
                  const start = Number(args[0]);
                  const end = args.length === 1 ? value.length : Number(args[1]);
                  if (!Number.isInteger(start) || !Number.isInteger(end)
                      || start < 0 || end < start || end > value.length) {
                    throw new RangeError("StringIndexOutOfBoundsException");
                  }
                  return value.substring(start, end);
                }
                case "subSequence": {
                  const start = Number(args[0]), end = Number(args[1]);
                  if (start < 0 || end < start || end > value.length) {
                    throw new RangeError("StringIndexOutOfBoundsException");
                  }
                  return value.substring(start, end);
                }
                case "contains": return value.includes(String(args[0]));
                case "startsWith": return value.startsWith(String(args[0]), args[1] ?? 0);
                case "endsWith": return value.endsWith(String(args[0]));
                case "indexOf": return value.indexOf(String(args[0]), args[1] ?? 0);
                case "lastIndexOf": return value.lastIndexOf(String(args[0]), args[1] ?? value.length);
                case "toLowerCase": return value.toLowerCase();
                case "toUpperCase": return value.toUpperCase();
                case "trim": return value.trim();
                case "strip": return value.trim();
                case "stripLeading": return value.trimStart();
                case "stripTrailing": return value.trimEnd();
                case "equalsIgnoreCase": return value.toLowerCase() === String(args[0]).toLowerCase();
                case "compareToIgnoreCase": {
                  const a = value.toLowerCase(), b = String(args[0]).toLowerCase();
                  return a < b ? -1 : a > b ? 1 : 0;
                }
                case "matches": return new RegExp("^(?:" + args[0] + ")$").test(value);
                case "replace": return value.split(String(args[0])).join(String(args[1]));
                case "replaceAll": return value.replace(new RegExp(args[0], "g"), String(args[1]));
                case "replaceFirst": return value.replace(new RegExp(args[0]), String(args[1]));
                case "split": return value.split(new RegExp(args[0]), args[1] === 0 ? undefined : args[1]);
                case "toCharArray": return Array.from(value);
                case "getBytes": return Array.from(new TextEncoder().encode(value));
                case "repeat": {
                  const count = Number(args[0]);
                  if (!Number.isInteger(count) || count < 0) throw new RangeError("IllegalArgumentException");
                  return value.repeat(count);
                }
                case "isBlank": return value.trim().length === 0;
                case "codePointAt": return value.codePointAt(args[0]);
              }
            }
            if (typeof receiver === "number" || typeof receiver === "boolean") {
              if (methodName === "intValue" || methodName === "longValue" || methodName === "shortValue" || methodName === "byteValue") return Number(receiver) | 0;
              if (methodName === "doubleValue" || methodName === "floatValue") return Number(receiver);
              if (methodName === "booleanValue") return Boolean(receiver);
            }
            const method = receiver[methodName];
            if (typeof method !== "function") {
              return this.unsupported("missing-instance-method", methodName + " on " + String(receiver));
            }
            return method.apply(receiver, args);
          },
          resolveInterfaceMethod(className, name, arity, isStatic, visited = new Set()) {
            if (!className || visited.has(className)) return null;
            visited.add(className);
            const current = this.classes.get(className);
            if (!current) return null;
            const candidates = current.__goldMethods?.get(name) ?? [];
            const target = candidates.find(method => !method.isAbstract
              && method.isStatic === isStatic && method.arity === arity);
            if (target) return target;
            for (const parentInterface of current.__goldInterfaces ?? []) {
              const inherited = this.resolveInterfaceMethod(
                parentInterface, name, arity, isStatic, visited);
              if (inherited) return inherited;
            }
            return null;
          },
          resolveClassMethod(className, name, arity, isStatic) {
            let currentName = className;
            const visitedClasses = new Set();
            const interfaces = [];
            while (currentName && !visitedClasses.has(currentName)) {
              visitedClasses.add(currentName);
              const current = this.classes.get(currentName);
              if (!current) break;
              interfaces.push(...(current.__goldInterfaces ?? []));
              const candidates = current.__goldMethods?.get(name) ?? [];
              const target = candidates.find(method => !method.isAbstract
                && method.isStatic === isStatic && method.arity === arity);
              if (target) return target;
              currentName = current.__goldSuperName;
            }
            for (const interfaceName of interfaces) {
              const target = this.resolveInterfaceMethod(interfaceName, name, arity, isStatic);
              if (target) return target;
            }
            return null;
          },
          invoke(receiver, methods, name, args, optional = false) {
            const className = receiver?.constructor?.__goldClassName;
            const target = this.resolveClassMethod(className, name, args.length, false);
            if (target) return target.fn.apply(receiver, args);
            const declaration = (methods.get(name) ?? []).find(method =>
              method.isAbstract && !method.isStatic && method.arity === args.length);
            if (declaration) return declaration.fn.apply(receiver, args);
            if (optional) return undefined;
            return this.unsupported("missing-instance-method", name + "/" + args.length);
          },
          invokeStatic(receiver, methods, name, args) {
            this.ensureInitialized(receiver);
            const className = typeof receiver === "function"
              ? receiver.__goldClassName : receiver?.constructor?.__goldClassName;
            const target = this.resolveClassMethod(className, name, args.length, true);
            if (target) return target.fn.apply(receiver, args);
            const declaration = (methods.get(name) ?? []).find(method =>
              method.isAbstract && method.isStatic && method.arity === args.length);
            if (declaration) return declaration.fn.apply(receiver, args);
            return this.unsupported("missing-static-method", name + "/" + args.length);
          },
          invokeDeclaration(detail, receiver, argsLike) {
            const adapter = globalThis.goldClientDeclarationAdapters?.[detail];
            if (typeof adapter !== "function") {
              return this.unsupported("missing-declaration-adapter", detail);
            }
            return adapter(receiver, ...Array.from(argsLike ?? []));
          },
          unsupported(operation, detail = "") {
            throw new Error("Unsupported translated JVM operation: " + operation + " " + detail);
          }
        });
        """;
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
