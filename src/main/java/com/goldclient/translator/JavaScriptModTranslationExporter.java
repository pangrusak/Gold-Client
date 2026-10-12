package com.goldclient.translator;

import com.goldclient.model.ClassModel;
import com.goldclient.model.MethodModel;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
        if ((method.access() & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
          statuses.add(new MethodStatus(clazz.name(), method.name(), method.descriptor(),
              "skipped", "Abstract/native method has no bytecode."));
          continue;
        }
        if (method.intermediateRepresentation().operations().isEmpty()) {
          statuses.add(new MethodStatus(clazz.name(), method.name(), method.descriptor(),
              "skipped", "No semantic IR operations were recovered."));
          continue;
        }
        TranslationResult result = translator.translate(method.intermediateRepresentation());
        statuses.add(new MethodStatus(clazz.name(), method.name(), method.descriptor(),
            result.complete() ? "complete" : "incomplete",
            result.complete() ? null : "Unsupported IR or unverified Java-to-JS behavior."));
        module.append(result.source()).append("\n");
      }
      Files.writeString(destination, module, StandardCharsets.UTF_8);
      filesWritten++;
    }

    Files.writeString(root.resolve("gold-client-runtime.js"), runtimeSource(), StandardCharsets.UTF_8);
    Files.writeString(root.resolve("package.json"),
        "{\n  \"type\": \"module\"\n}\n", StandardCharsets.UTF_8);
    ExportReport report = new ExportReport(
        "gold-client-javascript-export-v1", classes.size(), statuses.size(),
        count(statuses, "complete"), count(statuses, "incomplete"),
        count(statuses, "skipped"), filesWritten, statuses);
    Files.writeString(root.resolve("javascript-report.json"),
        new GsonBuilder().setPrettyPrinting().create().toJson(report) + System.lineSeparator(),
        StandardCharsets.UTF_8);
    Files.writeString(root.resolve("javascript-report.txt"), summary(report), StandardCharsets.UTF_8);
    return report;
  }

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

  private static int count(List<MethodStatus> statuses, String status) {
    return (int) statuses.stream().filter(item -> item.status().equals(status)).count();
  }

  private static String summary(ExportReport report) {
    return """
        Gold Client JavaScript export report
        Format: %s
        Classes visited: %d
        Methods inspected: %d
        JS-complete for represented IR: %d
        Incomplete: %d
        Skipped: %d
        JavaScript class modules: %d

        IMPORTANT: syntactically valid JavaScript is not the same as a working mod.
        Minecraft/Forge APIs, event systems, mixins, resources, and loader behavior
        require target-side adapters and are not automatically implemented here.
        """.formatted(report.formatVersion(), report.classCount(), report.methodCount(),
            report.completeCount(), report.incompleteCount(), report.skippedCount(),
            report.fileCount());
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
      int completeCount, int incompleteCount, int skippedCount, int fileCount,
      List<MethodStatus> methods) {
    public ExportReport { methods = List.copyOf(methods); }
  }

  public record MethodStatus(String className, String methodName, String descriptor,
      String status, String reason) {}
}