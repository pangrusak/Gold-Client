package com.goldclient.planner;

import com.goldclient.analyzer.ModAnalysis;
import com.goldclient.analyzer.ModAnalyzer;
import com.goldclient.model.ClassModel;
import com.goldclient.translator.ForgeLifecycleExporter;
import com.goldclient.translator.ForgeTransformationInspector;
import com.goldclient.translator.JavaScriptBundleExporter;
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
    List<Path> libraries = new ArrayList<>();
    Path output = null;
    Path reportFile = null;
    boolean singleFile = false;
    boolean compact = false;
    try {
      for (int i = 0; i < args.length; i++) {
        String flag = args[i];
        if (flag.equals("--help")) {
          usage(out);
          return 0;
        }
        if (flag.equals("--single-file")) {
          singleFile = true;
          continue;
        }
        if (flag.equals("--compact")) {
          compact = true;
          continue;
        }
        if (++i >= args.length) throw new IllegalArgumentException("Missing value for " + flag);
        String value = args[i];
        switch (flag) {
          case "--input" -> inputs.add(Path.of(value).toAbsolutePath().normalize());
          case "--library" -> libraries.add(Path.of(value).toAbsolutePath().normalize());
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

      Set<Path> realInputs = new LinkedHashSet<>();
      for (Path input : inputs) {
        if (!Files.isRegularFile(input)) throw new IOException("Input JAR does not exist: " + input);
        if (!realInputs.add(input.toRealPath())) {
          throw new IllegalArgumentException("Input JAR supplied more than once: " + input);
        }
      }
      Set<Path> realLibraries = new LinkedHashSet<>();
      for (Path library : libraries) {
        if (!Files.isRegularFile(library)) {
          throw new IOException("Library JAR does not exist: " + library);
        }
        if (realInputs.contains(library.toRealPath())) {
          throw new IllegalArgumentException("JAR supplied as both --input and --library: " + library);
        }
        if (!realLibraries.add(library.toRealPath())) {
          throw new IllegalArgumentException("Library JAR supplied more than once: " + library);
        }
      }
      Set<Path> allPaths = new LinkedHashSet<>(realInputs);
      allPaths.addAll(realLibraries);
      List<Path> allInputs = new ArrayList<>(allPaths);
      Set<Path> libraryPaths = Set.copyOf(realLibraries);

      List<ClassModel> classes = new ArrayList<>();
      Map<String, Path> classOwners = new LinkedHashMap<>();
      Map<String, String> metadataModIds = new LinkedHashMap<>();
      List<InputRecord> records = new ArrayList<>();
      Set<String> libraryClasses = new LinkedHashSet<>();
      List<String> transformerLibraries = new ArrayList<>();
      List<String> transformationBlockers = new ArrayList<>();
      Map<String, Integer> classifications = new LinkedHashMap<>();
      classifications.put("MOD", 0);
      classifications.put("API_PROVIDER", 0);
      classifications.put("TRANSFORMER", 0);
      for (Path input : allInputs) {
        boolean library = libraryPaths.contains(input);
        ModAnalysis analysis = new ModAnalyzer().analyze(input);
        int methods = analysis.totalMethods();
        int fields = analysis.totalFields();
        String metadataModId = analysis.metadata().getModId();
        ForgeTransformationInspector.Report transformations =
            ForgeTransformationInspector.inspect(List.of(input));
        List<String> surfaces = transformationSurfaces(transformations);
        boolean transformer = !surfaces.isEmpty();
        boolean modEntrypoint = metadataModId != null && !metadataModId.isBlank()
            && !"Unknown".equals(metadataModId);
        String classification = transformer ? "TRANSFORMER"
            : modEntrypoint ? "MOD" : "API_PROVIDER";
        classifications.merge(classification, 1, Integer::sum);
        if (transformer) {
          transformationBlockers.addAll(transformations.blockers());
          if (library) transformerLibraries.add(input.toString());
        }
        for (ClassModel clazz : analysis.classes()) {
          Path previous = classOwners.putIfAbsent(clazz.name(), input);
          if (previous != null) {
            throw new IOException("Duplicate class definition in loader inputs: "
                + clazz.name().replace('/', '.') + " in " + previous + " and " + input);
          }
          classes.add(clazz);
          if (library) libraryClasses.add(clazz.name());
          if (metadataModId != null && !metadataModId.isBlank()
              && !"Unknown".equals(metadataModId)) {
            metadataModIds.putIfAbsent(clazz.name(), metadataModId);
          }
        }
        records.add(new InputRecord(input.toString(), TranslationPipeline.sha256(input),
            analysis.totalClasses(), methods, fields, library ? "library" : "mod",
            classification, surfaces));
      }

      Path destination = output;
      JavaScriptModTranslationExporter.ExportReport export =
          new JavaScriptModTranslationExporter().export(allInputs, classes, metadataModIds,
              libraryClasses, destination);
      Path singleFileOutput = null;
      JavaScriptBundleExporter.BundleSummary singleFileSummary = null;
      if (singleFile) {
        singleFileOutput = destination.resolve(JavaScriptBundleExporter.DEFAULT_FILE_NAME);
        singleFileSummary = JavaScriptBundleExporter.write(destination, singleFileOutput, compact);
      }
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
      Set<String> blockerSet = new LinkedHashSet<>();
      JsonArray blockers = lifecycle.getAsJsonArray("blockers");
      if (blockers != null) {
        for (JsonElement blocker : blockers) blockerSet.add(blocker.getAsString());
      }
      blockerSet.addAll(transformationBlockers);
      for (String library : transformerLibraries) {
        blockerSet.add("Library " + library + " is a transformer library; Mixin, coremod, "
            + "access-transformer, and ASM transformation are not implemented by the "
            + "JavaScript pipeline.");
      }
      List<String> blockerList = new ArrayList<>(blockerSet);

      Map<String, Object> report = new LinkedHashMap<>();
      List<String> incompleteMethods = export.methods().stream()
          .filter(method -> "incomplete".equals(method.status()))
          .map(method -> method.className().replace('/', '.') + "#" + method.methodName()
              + method.descriptor())
          .toList();
      List<String> adapterRequiredMethods = export.methods().stream()
          .filter(method -> "adapter-required".equals(method.status()))
          .map(method -> method.className().replace('/', '.') + "#" + method.methodName()
              + method.descriptor())
          .toList();
      report.put("formatVersion", "gold-forge-loader-build-v1");
      report.put("inputs", records);
      report.put("inputCount", inputs.size());
      report.put("libraries", libraries.stream().map(Path::toString).toList());
      report.put("libraryClassCount", libraryClasses.size());
      report.put("transformerLibraries", transformerLibraries);
      report.put("classificationCounts", classifications);
      report.put("output", destination.toString());
      report.put("classCount", classes.size());
      report.put("methodCount", export.methodCount());
      report.put("adapterRequiredCount", export.adapterRequiredCount());
      report.put("adapterRequiredMethods", adapterRequiredMethods);
      report.put("incompleteCount", export.incompleteCount());
      report.put("incompleteMethods", incompleteMethods);
      report.put("mods", modIds);
      report.put("loadOrder", lifecycle.get("loadOrder").getAsString());
      report.put("lifecycleStatus", lifecycle.get("status").getAsString());
      report.put("blockers", blockerList);
      Path transformationsFile = destination.resolve("forge-transformations.json");
      if (Files.isRegularFile(transformationsFile)) {
        report.put("transformations", JsonParser.parseString(
            Files.readString(transformationsFile, StandardCharsets.UTF_8)));
      }
      report.put("translationComplete", export.incompleteCount() == 0);
      report.put("ready", blockerList.isEmpty() && export.incompleteCount() == 0);
      if (singleFileSummary != null) {
        report.put("singleFile", singleFileOutput.toString());
        report.put("singleFileModuleCount", singleFileSummary.moduleCount());
        report.put("singleFileBytes", singleFileSummary.bytes());
      }
      Files.createDirectories(reportFile.getParent());
      Files.writeString(reportFile,
          new GsonBuilder().setPrettyPrinting().create().toJson(report) + System.lineSeparator(),
          StandardCharsets.UTF_8);

      out.println("Gold Client Forge loader build");
      out.println("Inputs: " + inputs.size());
      out.println("Libraries: " + libraries.size());
      out.println("Classifications: MOD=" + classifications.get("MOD")
          + ", API_PROVIDER=" + classifications.get("API_PROVIDER")
          + ", TRANSFORMER=" + classifications.get("TRANSFORMER"));
      out.println("Classes: " + classes.size());
      out.println("Methods inspected: " + export.methodCount());
      out.println("Adapter-required declarations: " + export.adapterRequiredCount());
      out.println("Incomplete methods: " + export.incompleteCount());
      if (!incompleteMethods.isEmpty()) {
        out.println("Incomplete method list:");
        for (String method : incompleteMethods) out.println("  - " + method);
      }
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
      if (singleFileSummary != null) {
        out.println("Single-file bundle: " + singleFileOutput);
        out.println("Single-file modules: " + singleFileSummary.moduleCount());
      }
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
        [--library <api-provider.jar> ...] --output <directory> [--report <file>] \
        [--single-file [--compact]]
          Builds one shared JavaScript Forge loader bundle from several mod JARs.
          --input may be repeated. Duplicate class definitions are rejected.
          --library repeats an API/dependency JAR whose classes are bundled but
          whose own @Mod entry points are not added to the lifecycle.
          --single-file writes gold-client-forge.js as one classic-script file.
          --compact strips full-line comments and blank lines from that file.
          Exits 2 when lifecycle blockers or incomplete methods remain.
        """);
  }

  private static List<String> transformationSurfaces(
      ForgeTransformationInspector.Report report) {
    List<String> surfaces = new ArrayList<>();
    if (!report.mixinConfigs().isEmpty()) {
      surfaces.add(report.mixinConfigs().size() + " mixin config(s)");
    }
    if (!report.accessTransformers().isEmpty()) {
      int rules = report.accessTransformers().stream()
          .mapToInt(ForgeTransformationInspector.AccessTransformer::ruleCount).sum();
      surfaces.add(report.accessTransformers().size() + " access-transformer file(s), "
          + rules + " rule(s)");
    }
    if (!report.coremods().isEmpty()) {
      surfaces.add(report.coremods().size() + " coremod/loading-plugin declaration(s)");
    }
    return List.copyOf(surfaces);
  }

  private record InputRecord(String path, String sha256, int classCount, int methodCount,
      int fieldCount, String role, String classification, List<String> transformationSurfaces) {
    InputRecord {
      transformationSurfaces = List.copyOf(transformationSurfaces);
    }
  }
}
