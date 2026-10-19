package com.goldclient.translator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Packs a generated ES-module loader bundle into one classic-script file.
 *
 * <p>Generated modules use a small, fixed ESM subset: named, namespace, and
 * side-effect imports plus {@code export} declarations. Rewriting that subset
 * into a private module registry avoids a third-party bundler and keeps the
 * output dependency-free. The resulting file can be injected with a plain
 * {@code <script src="...">} tag; it does not require {@code type="module"}.</p>
 *
 * <p>Packing only proves the bundle is self-contained. It does not prove that a
 * Forge/Minecraft host has bound the APIs the translated mods call.</p>
 */
public final class JavaScriptBundleExporter {
  public record BundleSummary(int moduleCount, long bytes, String entryModule) {}

  private static final Pattern IMPORT_NAMESPACE = Pattern.compile(
      "(?m)^import\\s+\\*\\s+as\\s+([A-Za-z_$][\\w$]*)\\s+from\\s+\"([^\"]+)\"\\s*;");
  private static final Pattern IMPORT_NAMED = Pattern.compile(
      "(?m)^import\\s+\\{([^}]*)\\}\\s+from\\s+\"([^\"]+)\"\\s*;");
  private static final Pattern IMPORT_SIDE_EFFECT = Pattern.compile(
      "(?m)^import\\s+\"([^\"]+)\"\\s*;");
  private static final Pattern EXPORT_DEFAULT = Pattern.compile(
      "(?m)^export\\s+default\\s+");
  private static final Pattern EXPORT_DECLARATION = Pattern.compile(
      "(?m)^export\\s+(async\\s+function|function|class|const|let|var)\\s+([A-Za-z_$][\\w$]*)");
  private static final Pattern EXPORT_LIST = Pattern.compile(
      "(?m)^export\\s*\\{([^}]*)\\}\\s*;?");

  public static final String ENTRY_MODULE = "eaglercraft-forge-entry.js";
  public static final String DEFAULT_FILE_NAME = "gold-client-forge.js";

  private JavaScriptBundleExporter() {}

  public static BundleSummary write(Path bundleDirectory, Path outputFile, boolean compact)
      throws IOException {
    Path bundle = bundleDirectory.toAbsolutePath().normalize();
    Path output = outputFile.toAbsolutePath().normalize();
    String source = bundle(bundle, output, compact);
    Files.createDirectories(output.getParent());
    Files.writeString(output, source, StandardCharsets.UTF_8);
    return new BundleSummary(countModules(bundle, output), source.getBytes(StandardCharsets.UTF_8).length,
        ENTRY_MODULE);
  }

  public static String bundle(Path bundleDirectory, boolean compact) throws IOException {
    Path bundle = bundleDirectory.toAbsolutePath().normalize();
    return bundle(bundle, null, compact);
  }

  private static String bundle(Path bundle, Path excludedOutput, boolean compact)
      throws IOException {
    Map<String, String> modules = readModules(bundle, excludedOutput);
    if (!modules.containsKey(ENTRY_MODULE)) {
      throw new IOException("Bundle is missing " + ENTRY_MODULE);
    }
    Set<String> known = new LinkedHashSet<>(modules.keySet());
    StringBuilder output = new StringBuilder();
    output.append("// Gold Client single-file Forge bundle.\n");
    output.append("// Classic script: loads the generated entry and exposes globalThis.GoldClientForge.\n");
    output.append("// A self-contained bundle is not proof that a Forge host can run it.\n");
    output.append("(function (global) {\n");
    output.append("  \"use strict\";\n");
    output.append("  const __goldFactories = new Map();\n");
    output.append("  const __goldInstances = new Map();\n");
    output.append("  function __goldDefine(key, factory) { __goldFactories.set(key, factory); }\n");
    output.append("  function __goldRequire(key) {\n");
    output.append("    const cached = __goldInstances.get(key);\n");
    output.append("    if (cached) return cached;\n");
    output.append("    const factory = __goldFactories.get(key);\n");
    output.append("    if (!factory) throw new Error(\"Gold Client bundle module is missing: \" + key);\n");
    output.append("    const __exports = Object.create(null);\n");
    output.append("    __goldInstances.set(key, __exports);\n");
    output.append("    factory(__exports);\n");
    output.append("    return __exports;\n");
    output.append("  }\n");
    for (Map.Entry<String, String> module : modules.entrySet()) {
      output.append(transformModule(module.getKey(), module.getValue(), known, compact));
    }
    output.append("  const __goldEntry = __goldRequire(").append(jsString(ENTRY_MODULE)).append(");\n");
    output.append("  const __goldLifecycle = __goldRequire(\"forge-lifecycle.js\");\n");
    output.append("  const __goldRuntime = __goldRequire(\"gold-client-runtime.js\");\n");
    output.append("  const __goldClasses = __goldRequire(\"mod-index.js\");\n");
    output.append("  const __goldBundle = Object.freeze({\n");
    output.append("    formatVersion: \"gold-forge-single-file-v1\",\n");
    output.append("    moduleCount: ").append(modules.size()).append(",\n");
    output.append("    entry: __goldEntry,\n");
    output.append("    runtime: __goldRuntime.GoldClientRuntime,\n");
    output.append("    classes: __goldClasses.classes,\n");
    output.append("    getClass: function (name) {\n");
    output.append("      return __goldRuntime.GoldClientRuntime.resolveClass(name);\n");
    output.append("    },\n");
    output.append("    start: __goldEntry.startGoldClientForgeMods,\n");
    output.append("    install: __goldEntry.installGoldClientForgeEntrypoint,\n");
    output.append("    status: __goldLifecycle.forgeLoaderStatus,\n");
    output.append("    mods: __goldLifecycle.forgeMods,\n");
    output.append("    blockers: __goldLifecycle.forgeLifecycleBlockers,\n");
    output.append("    stop: function (reason) {\n");
    output.append("      const host = global.window?.goldClientForgeHost ?? global.goldClientForgeHost ?? null;\n");
    output.append("      return __goldLifecycle.stopForgeMods(host, reason);\n");
    output.append("    }\n");
    output.append("  });\n");
    output.append("  global.GoldClientForge = __goldBundle;\n");
    output.append("  global.GoldClientForgeBundle = __goldBundle;\n");
    output.append("})(typeof globalThis !== \"undefined\" ? globalThis : this);\n");
    return output.toString();
  }

  private static Map<String, String> readModules(Path bundle, Path excludedOutput)
      throws IOException {
    List<Path> files;
    try (Stream<Path> stream = Files.walk(bundle)) {
      files = stream.filter(Files::isRegularFile)
          .filter(path -> path.getFileName().toString().endsWith(".js"))
          .filter(path -> excludedOutput == null
              || !path.toAbsolutePath().normalize().equals(excludedOutput))
          .sorted(Comparator.comparing(path -> relativeKey(bundle, path)))
          .toList();
    }
    Map<String, String> modules = new LinkedHashMap<>();
    for (Path file : files) {
      modules.put(relativeKey(bundle, file),
          Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n"));
    }
    return modules;
  }

  private static int countModules(Path bundle, Path excludedOutput) throws IOException {
    return readModules(bundle, excludedOutput).size();
  }

  private static String relativeKey(Path root, Path file) {
    return root.relativize(file).toString().replace('\\', '/');
  }

  private static String transformModule(String key, String source, Set<String> known,
      boolean compact) throws IOException {
    String text = rewriteImports(source, key, known);
    Set<String> exports = new LinkedHashSet<>();
    text = EXPORT_DEFAULT.matcher(text).replaceAll("__exports.default = ");
    Matcher declarations = EXPORT_DECLARATION.matcher(text);
    StringBuffer rewritten = new StringBuffer();
    while (declarations.find()) {
      String kind = declarations.group(1);
      String name = declarations.group(2);
      exports.add(name);
      declarations.appendReplacement(rewritten,
          Matcher.quoteReplacement(kind + " " + name));
    }
    declarations.appendTail(rewritten);
    text = rewritten.toString();

    Matcher lists = EXPORT_LIST.matcher(text);
    StringBuilder listAssignments = new StringBuilder();
    rewritten = new StringBuffer();
    while (lists.find()) {
      for (String raw : lists.group(1).split(",")) {
        String specifier = raw.trim();
        if (specifier.isEmpty()) continue;
        String[] alias = specifier.split("\\s+as\\s+");
        String local = alias[0].trim();
        String exported = alias.length == 2 ? alias[1].trim() : local;
        listAssignments.append("__exports.").append(exported)
            .append(" = ").append(local).append(";\n");
      }
      lists.appendReplacement(rewritten, "");
    }
    lists.appendTail(rewritten);
    text = rewritten.toString();
    if (compact) text = compact(text);

    StringBuilder module = new StringBuilder(text.length() + 256);
    module.append("__goldDefine(").append(jsString(key)).append(", function (__exports) {\n");
    module.append(text);
    if (!text.isEmpty() && !text.endsWith("\n")) module.append('\n');
    for (String name : exports) {
      module.append("__exports.").append(name).append(" = ").append(name).append(";\n");
    }
    module.append(listAssignments);
    module.append("});\n");
    return module.toString();
  }

  private static String rewriteImports(String text, String key, Set<String> known)
      throws IOException {
    Matcher namespace = IMPORT_NAMESPACE.matcher(text);
    StringBuffer rewritten = new StringBuffer();
    while (namespace.find()) {
      String target = resolve(key, namespace.group(2), known);
      namespace.appendReplacement(rewritten, Matcher.quoteReplacement(
          "const " + namespace.group(1) + " = __goldRequire(" + jsString(target) + ");"));
    }
    namespace.appendTail(rewritten);
    text = rewritten.toString();

    Matcher named = IMPORT_NAMED.matcher(text);
    rewritten = new StringBuffer();
    while (named.find()) {
      String target = resolve(key, named.group(2), known);
      List<String> bindings = new ArrayList<>();
      for (String raw : named.group(1).split(",")) {
        String specifier = raw.trim();
        if (specifier.isEmpty()) continue;
        String[] alias = specifier.split("\\s+as\\s+");
        bindings.add(alias.length == 2
            ? alias[0].trim() + ": " + alias[1].trim() : specifier);
      }
      String replacement = bindings.isEmpty()
          ? "__goldRequire(" + jsString(target) + ");"
          : "const { " + String.join(", ", bindings) + " } = __goldRequire("
              + jsString(target) + ");";
      named.appendReplacement(rewritten, Matcher.quoteReplacement(replacement));
    }
    named.appendTail(rewritten);
    text = rewritten.toString();

    Matcher sideEffect = IMPORT_SIDE_EFFECT.matcher(text);
    rewritten = new StringBuffer();
    while (sideEffect.find()) {
      String target = resolve(key, sideEffect.group(1), known);
      sideEffect.appendReplacement(rewritten,
          Matcher.quoteReplacement("__goldRequire(" + jsString(target) + ");"));
    }
    sideEffect.appendTail(rewritten);
    return rewritten.toString();
  }

  private static String resolve(String fromKey, String specifier, Set<String> known)
      throws IOException {
    if (!specifier.startsWith("./") && !specifier.startsWith("../")) {
      throw new IOException("Unsupported bare module specifier in " + fromKey + ": " + specifier);
    }
    String base = fromKey.contains("/")
        ? fromKey.substring(0, fromKey.lastIndexOf('/')) : "";
    List<String> parts = new ArrayList<>();
    String combined = base.isEmpty() ? specifier : base + "/" + specifier;
    for (String part : combined.split("/")) {
      if (part.isEmpty() || part.equals(".")) continue;
      if (part.equals("..")) {
        if (parts.isEmpty()) {
          throw new IOException("Module path escapes the bundle: " + fromKey + " -> " + specifier);
        }
        parts.remove(parts.size() - 1);
      } else {
        parts.add(part);
      }
    }
    String resolved = String.join("/", parts);
    if (!known.contains(resolved)) {
      throw new IOException("Bundle module is missing: " + resolved + " (imported by " + fromKey + ")");
    }
    return resolved;
  }

  /**
   * Removes full-line comments and blank lines. Generated code uses no
   * multi-line literals, so this does not change string contents.
   */
  private static String compact(String source) {
    StringBuilder output = new StringBuilder(source.length());
    for (String line : source.split("\n", -1)) {
      if (line.stripLeading().startsWith("//")) continue;
      if (line.isBlank()) continue;
      output.append(line).append('\n');
    }
    return output.toString();
  }

  private static String jsString(String value) {
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
        .replace("\n", "\\n").replace("\r", "\\r") + "\"";
  }
}
