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

/**
 * Exports the current semantic-IR-to-source translation for every class in a mod.
 *
 * <p>The emitted method bodies are Java-like translator output, not executable
 * JavaScript and not a claim of behavioral equivalence. This exporter deliberately
 * keeps that intermediate artifact separate from the TeaVM JavaScript pipeline.</p>
 */
public final class ModTranslationExporter {
  private static final String REPORT_FILE = "translation-report.json";
  private final JavaTranslator translator;

  public ModTranslationExporter() {
    this(new JavaTranslator());
  }

  ModTranslationExporter(JavaTranslator translator) {
    this.translator = translator;
  }

  public TranslationReport export(List<ClassModel> classes, Path outputDirectory)
      throws IOException {
    if (classes == null) throw new IllegalArgumentException("Classes must not be null.");
    if (outputDirectory == null) {
      throw new IllegalArgumentException("Output directory must not be null.");
    }

    Path root = outputDirectory.toAbsolutePath().normalize();
    Files.createDirectories(root);
    List<MethodStatus> statuses = new ArrayList<>();
    int filesWritten = 0;

    List<ClassModel> orderedClasses = classes.stream()
        .sorted(Comparator.comparing(ClassModel::name))
        .toList();

    for (ClassModel clazz : orderedClasses) {
      Path classFile = classOutputPath(root, clazz.name());
      Files.createDirectories(classFile.getParent());
      StringBuilder output = new StringBuilder();
      output.append("// Gold Client translated method dump\n")
          .append("// Class: ").append(clazz.name()).append("\n")
          .append("// NOTE: Java-like intermediate output; not executable JavaScript.\n\n");

      List<MethodModel> orderedMethods = clazz.methods().stream()
          .sorted(Comparator.comparing(MethodModel::name)
              .thenComparing(MethodModel::descriptor))
          .toList();

      for (MethodModel method : orderedMethods) {
        if ((method.access() & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
          statuses.add(new MethodStatus(
              clazz.name(), method.name(), method.descriptor(), "skipped",
              "Abstract or native method has no translatable bytecode.", null));
          output.append("// SKIPPED (abstract/native): ")
              .append(method.name()).append(method.descriptor()).append("\n\n");
          continue;
        }

        if (method.intermediateRepresentation().operations().isEmpty()) {
          statuses.add(new MethodStatus(
              clazz.name(), method.name(), method.descriptor(), "skipped",
              "No semantic IR operations were recovered.", null));
          output.append("// SKIPPED (no semantic IR): ")
              .append(method.name()).append(method.descriptor()).append("\n\n");
          continue;
        }

        TranslationResult result = translator.translate(method.intermediateRepresentation());
        String status = result.complete() ? "complete" : "incomplete";
        statuses.add(new MethodStatus(
            clazz.name(), method.name(), method.descriptor(), status,
            result.complete() ? null : "Translator emitted one or more unsupported operations.",
            root.relativize(classFile).toString().replace('\\\\', '/')));
        output.append("// Method: ").append(method.name()).append(method.descriptor())
            .append("\n// Translation status: ").append(status).append("\n")
            .append(result.source()).append("\n\n");
      }

      Files.writeString(classFile, output, StandardCharsets.UTF_8);
      filesWritten++;
    }

    TranslationReport report = new TranslationReport(
        "gold-client-method-export-v1",
        orderedClasses.size(),
        statuses.size(),
        count(statuses, "complete"),
        count(statuses, "incomplete"),
        count(statuses, "skipped"),
        filesWritten,
        statuses);
    Files.writeString(
        root.resolve(REPORT_FILE),
        new GsonBuilder().setPrettyPrinting().create().toJson(report) + System.lineSeparator(),
        StandardCharsets.UTF_8);
    writeTextSummary(root.resolve("translation-report.txt"), report);
    return report;
  }

  private static Path classOutputPath(Path root, String className) throws IOException {
    String relative = className.replace('\\', '/').replace('.', '/');
    while (relative.startsWith("/")) relative = relative.substring(1);
    Path destination = root.resolve(relative + ".translated.txt").normalize();
    if (!destination.startsWith(root)) {
      throw new IOException("Rejected class output path outside output directory: " + className);
    }
    return destination;
  }

  private static int count(List<MethodStatus> statuses, String wanted) {
    return (int) statuses.stream().filter(status -> status.status().equals(wanted)).count();
  }

  private static void writeTextSummary(Path path, TranslationReport report) throws IOException {
    String summary = """
        Gold Client whole-mod translation report
        Format: %s
        Classes visited: %d
        Methods inspected: %d
        Complete: %d
        Incomplete: %d
        Skipped: %d
        Class output files: %d

        Important: "complete" means the current translator handled the method's
        represented IR operations. It does not prove executable JavaScript,
        Minecraft/Forge API compatibility, or behavioral equivalence.
        """.formatted(report.formatVersion(), report.classCount(), report.methodCount(),
            report.completeCount(), report.incompleteCount(), report.skippedCount(),
            report.fileCount());
    Files.writeString(path, summary, StandardCharsets.UTF_8);
  }

  public record TranslationReport(
      String formatVersion,
      int classCount,
      int methodCount,
      int completeCount,
      int incompleteCount,
      int skippedCount,
      int fileCount,
      List<MethodStatus> methods) {
    public TranslationReport {
      methods = List.copyOf(methods);
    }
  }

  public record MethodStatus(
      String className,
      String methodName,
      String descriptor,
      String status,
      String reason,
      String outputFile) {}
}
