package com.goldclient.planner;

import com.goldclient.analyzer.ModAnalysis;
import com.goldclient.analyzer.ModAnalyzer;
import com.goldclient.model.ClassModel;
import com.goldclient.translator.ForgeLifecycleExporter;
import com.goldclient.translator.JavaScriptModTranslationExporter;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Translates several Forge 1.12.2 mod JARs into one shared JavaScript bundle.
 *
 * <p>This is a loader build, not a promise that every referenced Forge or
 * Minecraft API exists. All inputs are translated into one runtime/class
 * registry so the generated phase-major lifecycle can construct and dispatch
 * mods together. Missing APIs remain explicit adapter-required diagnostics.</p>
 */
public final class LoaderCommand {
  private LoaderCommand() {}

  public static int run(String[] args, PrintStream out, PrintStream err) {
    List<Path> inputs = new ArrayList<>();
    Path output = null;
    Path reportFile = null;
    try {
      for (int i = 0; i < args.length; i++) {
        String flag = args[i];
        if (flag.equals("--help")) {
          usage(out);
          return 0;
        }
        if (++i >= args.length) throw new IllegalArgumentException("Missing value for " + flag);
        String value = args[i];
        switch (flag) {
          case "--input" -> inputs.add(Path.of(value).toAbsolutePath().normalize());
          case "--output" -> {
            if (output != null) throw new IllegalArgumentException("Duplicate --output");
            output = Path.of(value).toAbsolutePath().normalize();
          }
          case "--report" -> {
            if (reportFile != null) throw new IllegalArgumentException("Duplicate --report");
            reportFile = Path.of(value).toAbsolutePath().normalize();
          }
          default -> throw new IllegalArgumentException("Unknown loader option: " + flag);
        }
      }
      if (inputs.isEmpty()) throw new IllegalArgumentException("At least one --input JAR is required");
      if (output == null) throw new IllegalArgumentException("--output directory is required");
      if (reportFile == null) reportFile = output.resolve("loader-plan.json");

      Set<Path> uniqueInputs = new LinkedHashSet<>();
      for (Path input : inputs) {
        if (!Files.isRegularFile(input)) throw new IOException("Input JAR does not exist: " + input);
        if (!uniqueInputs.add(input.toRealPath())) {
          throw new IllegalArgumentException("Input JAR supplied more than once: " + input);
        }
      }
      inputs = new ArrayList<>(uniqueInputs);

      List<ClassModel> classes = new ArrayList<>();
      Map<String, Path> classOwners = new LinkedHashMap<>();
      Map<String, String> metadataModIds = new LinkedHashMap<>();
      List<InputRecord> records = new ArrayList<>();
      for (Path input : inputs) {
        ModAnalysis analysis = new ModAnalyzer().analyze(input);
        int methods = analysis.totalMethods();
        int fields = analysis.totalFields();
        String metadataModId = analysis.metadata().getModId();
        for (ClassModel clazz : analysis.classes()) {
          Path previous = classOwners.putIfAbsent(clazz.name(), input);
          if (previous != null) {
            throw new IOException("Duplicate class definition in loader inputs: "
                + clazz.name().replace('/', '.') + " in " + previous + " and " + input);
          }
          classes.add(clazz);
          if (metadataModId != null && !metadataModId.isBlank()
              && !"Unknown".equals(metadataModId)) {
            metadataModIds.putIfAbsent(clazz.name(), metadataModId);
          }
        }
        records.add(new InputRecord(input.toString(), TranslationPipeline.sha256(input),
            analysis.totalClasses(), methods, fields));
      }

      Path destination = output;
      JavaScriptModTranslationExporter.ExportReport export =
          new JavaScriptModTranslationExporter().export(inputs, classes, metadataModIds, destination);
      JsonObject lifecycle = JsonParser.parseString(Files.readString(
          destination.resolve("forge-lifecycle-plan.json"), StandardCharsets.UTF_8))
          .getAsJsonObject();
      JsonArray mods = lifecycle.getAsJsonArray("mods");
      List<String> modIds = new ArrayList<>();
      if (mods != null) {
        for (JsonElement mod : mods) {
          modIds.add(mod.getAsJsonObject().get("modId").getAsString());
        }
      }
      JsonArray blockers = lifecycle.getAsJsonArray("blockers");
      List<String> blockerList = new ArrayList<>();
      if (blockers != null) {
        for (JsonElement blocker : blockers) blockerList.add(blocker.getAsString());
      }

      Map<String, Object> report = new LinkedHashMap<>();
      report.put("formatVersion", "gold-forge-loader-build-v1");
      report.put("inputs", records);
      report.put("output", destination.toString());
      report.put("classCount", classes.size());
      report.put("methodCount", export.methodCount());
      report.put("adapterRequiredCount", export.adapterRequiredCount());
      report.put("incompleteCount", export.incompleteCount());
      report.put("mods", modIds);
      report.put("loadOrder", lifecycle.get("loadOrder").getAsString());
      report.put("lifecycleStatus", lifecycle.get("status").getAsString());
      report.put("blockers", blockerList);
      report.put("translationComplete", export.incompleteCount() == 0);
      report.put("ready", blockerList.isEmpty() && export.incompleteCount() == 0);
      Files.createDirectories(reportFile.getParent());
      Files.writeString(reportFile,
          new GsonBuilder().setPrettyPrinting().create().toJson(report) + System.lineSeparator(),
          StandardCharsets.UTF_8);

      out.println("Gold Client Forge loader build");
      out.println("Inputs: " + inputs.size());
      out.println("Classes: " + classes.size());
      out.println("Methods inspected: " + export.methodCount());
      out.println("Adapter-required declarations: " + export.adapterRequiredCount());
      out.println("Incomplete methods: " + export.incompleteCount());
      out.println("Translation complete: " + (export.incompleteCount() == 0 ? "yes" : "no"));
      out.println("Mods: " + (modIds.isEmpty() ? "(none)" : String.join(", ", modIds)));
      out.println("Load order: " + lifecycle.get("loadOrder").getAsString());
      out.println("Lifecycle status: " + lifecycle.get("status").getAsString());
      if (!blockerList.isEmpty()) {
        out.println("Blockers:");
        for (String blocker : blockerList) out.println("  - " + blocker);
      }
      out.println("Bundle: " + destination);
      out.println("Entrypoint: " + destination.resolve("eaglercraft-forge-entry.js"));
      out.println("Report: " + reportFile);
      out.println("Note: a loader bundle is not proof that every Forge/Minecraft API is bound.");
      return blockerList.isEmpty() && export.incompleteCount() == 0 ? 0 : 2;
    } catch (Exception exception) {
      err.println("Loader build failed: " + exception.getMessage());
      return 2;
    }
  }

  private static void usage(PrintStream out) {
    out.println("""
        Usage: gold-client loader --input <mod.jar> [--input <dependency-mod.jar> ...] \
        --output <directory> [--report <file>]
          Builds one shared JavaScript Forge loader bundle from several mod JARs.
          --input may be repeated. Duplicate class definitions are rejected.
          Exits 2 when lifecycle blockers or incomplete methods remain.
        """);
  }

  private record InputRecord(String path, String sha256, int classCount, int methodCount,
      int fieldCount) {}
}
