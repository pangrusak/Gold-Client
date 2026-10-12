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
          result = translator.translateStub(method.name(), method.descriptor(), why,
              (method.access() & Opcodes.ACC_STATIC) != 0);
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
      appendExecutableClassWrapper(module, clazz);
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

  /**
   * Adds a real JavaScript class facade around the translated function exports.
   * Method bodies stay independently exportable, while callers get Java-like
   * instance/static dispatch and constructors that execute the recovered body.
   */
  private static void appendExecutableClassWrapper(StringBuilder module, ClassModel clazz) {
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
            .append(", isStatic: ").append((method.access() & Opcodes.ACC_STATIC) != 0)
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
        .append(".find(method => method.isStatic)?.fn ?? null);\n");
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
        class GoldClientJavaList {
          constructor(initial = []) {
            this.items = Array.isArray(initial) ? initial.slice() : [];
          }
          add(value) { this.items.push(value); return true; }
          addAll(values) { const list = values?.items ?? values; this.items.push(...Array.from(list ?? [])); return true; }
          get(index) { return this.items[index]; }
          set(index, value) { const old = this.items[index]; this.items[index] = value; return old; }
          size() { return this.items.length; }
          isEmpty() { return this.items.length === 0; }
          contains(value) { return this.items.some(item => Object.is(item, value) || item === value); }
          indexOf(value) { return this.items.indexOf(value); }
          clear() { this.items.length = 0; }
          remove(value) {
            const index = typeof value === "number" ? value : this.items.indexOf(value);
            if (index < 0 || index >= this.items.length) return false;
            this.items.splice(index, 1); return true;
          }
          toArray() { return this.items.slice(); }
          sort(comparator) { this.items.sort(comparator); }
          iterator() { return this.items[Symbol.iterator](); }
          forEach(callback) { this.items.forEach(callback); }
          [Symbol.iterator]() { return this.items[Symbol.iterator](); }
        }
        class GoldClientJavaSet {
          constructor(initial = []) { this.items = new Set(initial?.items ?? initial ?? []); }
          add(value) { const before = this.items.size; this.items.add(value); return this.items.size !== before; }
          contains(value) { return this.items.has(value); }
          size() { return this.items.size; }
          isEmpty() { return this.items.size === 0; }
          remove(value) { return this.items.delete(value); }
          clear() { this.items.clear(); }
          toArray() { return Array.from(this.items); }
          iterator() { return this.items[Symbol.iterator](); }
          [Symbol.iterator]() { return this.items[Symbol.iterator](); }
        }
        class GoldClientJavaMap {
          constructor(initial = []) {
            this.items = new Map(initial?.items instanceof Map ? initial.items : (initial instanceof Map ? initial : []));
          }
          put(key, value) { const old = this.items.get(key); this.items.set(key, value); return old ?? null; }
          get(key) { return this.items.has(key) ? this.items.get(key) : null; }
          getOrDefault(key, fallback) { return this.items.has(key) ? this.items.get(key) : fallback; }
          containsKey(key) { return this.items.has(key); }
          containsValue(value) { return Array.from(this.items.values()).some(item => Object.is(item, value)); }
          size() { return this.items.size; }
          isEmpty() { return this.items.size === 0; }
          remove(key) { const old = this.get(key); this.items.delete(key); return old; }
          clear() { this.items.clear(); }
          putAll(other) { for (const [key, value] of (other?.items ?? other)) this.items.set(key, value); }
          keySet() { return new GoldClientJavaSet(this.items.keys()); }
          values() { return new GoldClientJavaList(Array.from(this.items.values())); }
          entrySet() { return new GoldClientJavaSet(Array.from(this.items.entries()).map(([key, value]) => ({ getKey: () => key, getValue: () => value }))); }
          forEach(callback) { this.items.forEach((value, key) => callback(key, value)); }
          [Symbol.iterator]() { return this.items[Symbol.iterator](); }
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
          classes: new Map(),
          registerClass(name, constructor, staticInitializer = null) {
            constructor.__goldStaticInitializer = staticInitializer;
            constructor.__goldInitialized = false;
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
            const constructor = this.classes.get(className);
            if (!constructor) return this.unsupported("missing-static-field-class", className);
            this.ensureInitialized(constructor);
            return constructor[fieldName];
          },
          setStaticField(className, fieldName, value) {
            const constructor = this.classes.get(className);
            if (!constructor) return this.unsupported("missing-static-field-class", className);
            this.ensureInitialized(constructor);
            constructor[fieldName] = value;
            return value;
          },
          resolveClass(name) {
            return this.classes.get(name) ?? null;
          },
          create(name, ...args) {
            const constructor = this.classes.get(name);
            if (!constructor) return this.unsupported("missing-class-adapter", name);
            return Reflect.construct(constructor, args);
          },
          createJava(name, ...args) {
            switch (name) {
              case "java.util.ArrayList":
              case "java.util.LinkedList":
              case "java.util.Vector":
              case "java.util.Stack":
                return new GoldClientJavaList(Array.isArray(args[0]) ? args[0] : []);
              case "java.util.HashSet":
              case "java.util.LinkedHashSet":
              case "java.util.TreeSet":
                return new GoldClientJavaSet(args[0] ?? []);
              case "java.util.HashMap":
              case "java.util.LinkedHashMap":
              case "java.util.TreeMap":
              case "java.util.IdentityHashMap":
                return new GoldClientJavaMap(args[0] ?? []);
              case "java.lang.StringBuilder":
              case "java.lang.StringBuffer":
                return new GoldClientJavaStringBuilder(args[0] ?? "");
              case "java.lang.String":
                return String(args[0] ?? "");
              default:
                return this.unsupported("missing-java-class-adapter", name);
            }
          },
          invokeJavaStatic(owner, method, ...args) {
            if (owner === "java.lang.Math") {
              if (method === "floorDiv") return this.intDiv(args[0], args[1]);
              if (method === "floorMod") return this.intMod(args[0], args[1]);
              if (typeof Math[method] === "function") return Math[method](...args);
            }
            if (owner === "java.util.Arrays") {
              if (method === "asList") return new GoldClientJavaList(args[0] ?? args);
              if (method === "copyOf") return Array.from(args[0] ?? []).slice(0, args[1]);
              if (method === "equals") return JSON.stringify(args[0]) === JSON.stringify(args[1]);
              if (method === "toString") return "[" + Array.from(args[0] ?? []).join(", ") + "]";
            }
            if (owner === "java.util.Collections") {
              if (method === "emptyList") return new GoldClientJavaList();
              if (method === "emptySet") return new GoldClientJavaSet();
              if (method === "emptyMap") return new GoldClientJavaMap();
              if (method === "singletonList") return new GoldClientJavaList([args[0]]);
              if (method === "singleton") return new GoldClientJavaSet([args[0]]);
              if (method === "singletonMap") return new GoldClientJavaMap([[args[0], args[1]]]);
              if (["unmodifiableList", "unmodifiableSet", "unmodifiableMap",
                   "synchronizedList", "synchronizedSet", "synchronizedMap"].includes(method)) return args[0];
              if (method === "addAll") {
                const collection = args[0];
                const values = args.slice(1);
                if (collection?.add) { let changed = false; for (const value of values) changed = collection.add(value) || changed; return changed; }
                if (Array.isArray(collection)) { collection.push(...values); return values.length > 0; }
              }
              if (method === "reverse") { if (args[0]?.items) args[0].items.reverse(); else args[0]?.reverse?.(); return; }
              if (method === "sort") { if (args[0]?.items) args[0].items.sort(args[1]); else args[0]?.sort?.(args[1]); return; }
              if (method === "frequency") { const values = args[0]?.items ?? args[0] ?? []; return Array.from(values).filter(value => Object.is(value, args[1])).length; }
            }
            if (owner === "java.util.Objects") {
              if (method === "equals") return Object.is(args[0], args[1]);
              if (method === "hash") return args.reduce((hash, value) => (Math.imul(hash, 31) + this.javaHashCode(value)) | 0, 1);
              if (method === "toString") return args[0] == null ? (args[1] ?? "null") : String(args[0]);
              if (method === "requireNonNull") return this.requireNonNull(args[0], args[1]);
            }
            if (owner === "java.lang.Integer" || owner === "java.lang.Long") {
              if (method === "parseInt" || method === "parseLong" || method === "valueOf") return Number.parseInt(args[0], args[1] ?? 10);
              if (method === "toString") return String(args[0]);
              if (method === "compare") return Math.sign(args[0] - args[1]);
            }
            if (owner === "java.lang.Boolean") {
              if (method === "parseBoolean" || method === "valueOf") return String(args[0]).toLowerCase() === "true";
              if (method === "toString") return String(Boolean(args[0]));
            }
            if (owner === "java.lang.String") {
              if (method === "valueOf") return this.javaString(args[0]);
              if (method === "format") return String(args[0]).replace(/%s/g, () => String(args.shift()));
            }
            if (owner === "java.lang.System" && method === "currentTimeMillis") return Date.now();
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
            if (methodName === "compareTo") return String(receiver).localeCompare(String(args[0]));
            const method = receiver[methodName];
            if (typeof method !== "function") {
              return this.unsupported("missing-instance-method", methodName + " on " + String(receiver));
            }
            return method.apply(receiver, args);
          },
          invoke(receiver, methods, name, args, optional = false) {
            const candidates = methods.get(name) ?? [];
            const target = candidates.find(method => !method.isStatic && method.arity === args.length);
            if (!target) {
              if (optional) return undefined;
              return this.unsupported("missing-instance-method", name + "/" + args.length);
            }
            return target.fn.apply(receiver, args);
          },
          invokeStatic(receiver, methods, name, args) {
            this.ensureInitialized(receiver);
            const candidates = methods.get(name) ?? [];
            const target = candidates.find(method => method.isStatic && method.arity === args.length);
            if (!target) return this.unsupported("missing-static-method", name + "/" + args.length);
            return target.fn.apply(receiver, args);
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