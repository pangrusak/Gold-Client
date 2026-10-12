package com.goldclient.translator;

import com.goldclient.model.ClassModel;
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
          result = translator.translateStub(method.name(), method.descriptor(), why);
          status = "stubbed";
          reason = why;
        } else {
          result = translator.translate(method.intermediateRepresentation(),
              (method.access() & Opcodes.ACC_STATIC) != 0);
          status = result.complete() ? "complete" : "incomplete";
          reason = result.complete() ? null : incompleteReason(result.source());
        }
        statuses.add(new MethodStatus(clazz.name(), method.name(), method.descriptor(), status, reason));
        module.append(result.source()).append("\n");
      }
      Files.writeString(destination, module, StandardCharsets.UTF_8);
      filesWritten++;
    }

    int resourceCount = copyResources(inputJar, root);
    Files.writeString(root.resolve("gold-client-runtime.js"), runtimeSource(), StandardCharsets.UTF_8);
    writeModuleIndex(classes, root);
    writeClassManifest(classes, root);
    Files.writeString(root.resolve("package.json"),
        "{\n  \"type\": \"module\"\n}\n", StandardCharsets.UTF_8);
    ExportReport report = new ExportReport(
        "gold-client-javascript-export-v1", classes.size(), statuses.size(),
        count(statuses, "complete"), count(statuses, "incomplete"),
        count(statuses, "skipped"), count(statuses, "stubbed"), filesWritten, resourceCount, statuses);
    Files.writeString(root.resolve("javascript-report.json"),
        new GsonBuilder().setPrettyPrinting().create().toJson(report) + System.lineSeparator(),
        StandardCharsets.UTF_8);
    Files.writeString(root.resolve("javascript-report.txt"), summary(report), StandardCharsets.UTF_8);
    return report;
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

  private static String incompleteReason(String source) {
    java.util.regex.Matcher todo = java.util.regex.Pattern
        .compile("(?m)^\\s*// TODO: ([^\\n]+)")
        .matcher(source);
    if (todo.find()) return "Untranslated IR/control-flow operation: " + todo.group(1).trim();
    java.util.regex.Matcher audit = java.util.regex.Pattern
        .compile("(?m)^\\s*// CONTROL-FLOW ERROR at operation ([^\\n]+)")
        .matcher(source);
    if (audit.find()) return "Control-flow audit: " + audit.group(1).trim();
    if (source.contains("Unsupported JVM IR")) return "Unsupported JVM IR operation remains.";
    if (source.contains("<exception>")) return "Exception stack value was not lowered.";
    return "Translation emitted, but semantics or host API compatibility remain unverified.";
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
            clazz.fields().stream().map(field -> new FieldManifest(
                field.name(), field.descriptor(), (field.access() & Opcodes.ACC_STATIC) != 0,
                (field.access() & Opcodes.ACC_FINAL) != 0)).toList(),
            clazz.methods().stream().map(method -> new MethodManifest(
                method.name(), method.descriptor(),
                (method.access() & Opcodes.ACC_STATIC) != 0,
                (method.access() & Opcodes.ACC_ABSTRACT) != 0,
                (method.access() & Opcodes.ACC_NATIVE) != 0)).toList()))
        .toList();
    Files.writeString(root.resolve("mod-manifest.json"),
        new GsonBuilder().setPrettyPrinting().create().toJson(manifest) + System.lineSeparator(),
        StandardCharsets.UTF_8);
  }

  public record ClassManifest(String name, String superName, List<String> interfaces,
      List<FieldManifest> fields, List<MethodManifest> methods) {}
  public record FieldManifest(String name, String descriptor, boolean isStatic, boolean isFinal) {}
  public record MethodManifest(String name, String descriptor, boolean isStatic,
      boolean isAbstract, boolean isNative) {}

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
        JavaScript class modules: %d
        Preserved JAR resources: %d

        IMPORTANT: syntactically valid JavaScript is not the same as a working mod.
        Minecraft/Forge APIs, event systems, mixins, resources, and loader behavior
        require target-side adapters and are not automatically implemented here.
        """.formatted(report.formatVersion(), report.classCount(), report.methodCount(),
            report.completeCount(), report.incompleteCount(), report.skippedCount(),
            report.stubbedCount(), report.fileCount(), report.resourceCount());
  }

  private static String runtimeSource() {
    return """
        // Small compatibility helpers used by generated JavaScript.
        // This is not a replacement for Minecraft/Forge or a full JVM runtime.
        export const GoldClientRuntime = Object.freeze({
          intDiv(a, b) {
            if (b === 0) throw new Error("ArithmeticException: / by zero");
            return Math.trunc(a / b);
          },
          intMod(a, b) {
            if (b === 0) throw new Error("ArithmeticException: / by zero");
            return a - Math.trunc(a / b) * b;
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
            if (value === null || value === undefined) throw new TypeError("null array");
            return value.length;
          },
          newArray(kind, length) {
            const size = Number(length);
            if (!Number.isInteger(size) || size < 0) throw new RangeError("invalid array length");
            const defaults = { boolean: false, byte: 0, char: "\0", short: 0,
              int: 0, long: 0, float: 0, double: 0, Object: null };
            return Array(size).fill(Object.prototype.hasOwnProperty.call(defaults, kind)
              ? defaults[kind] : null);
          },
          javaString(value) {
            if (value === null || value === undefined) return "null";
            if (typeof value === "boolean") return value ? "true" : "false";
            return String(value);
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
          unsupported(operation, detail = "") {
            throw new Error("Unsupported translated JVM operation: " + operation + " " + detail);
          }
        });
        """;
  }

  public record ExportReport(String formatVersion, int classCount, int methodCount,
      int completeCount, int incompleteCount, int skippedCount, int stubbedCount, int fileCount, int resourceCount,
      List<MethodStatus> methods) {
    public ExportReport { methods = List.copyOf(methods); }
  }

  public record MethodStatus(String className, String methodName, String descriptor,
      String status, String reason) {}
}