package com.goldclient.translator;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * Inspects the transformation surface of the input JARs: Mixin configs,
 * access-transformer rule files, coremod plugins, and tweak classes.
 *
 * <p>This is deliberately an inspector, not an implementation of those
 * transformations. The JavaScript pipeline has no bytecode-rewriting phase for
 * Minecraft/Forge classes, so every non-empty surface still produces an
 * explicit blocker. Parsing the configs makes the blocker precise (which files,
 * how many mixin classes, how many AT rules) instead of a coarse string such as
 * {@code FMLAT=...}.</p>
 */
public final class ForgeTransformationInspector {
  public record MixinConfig(String jar, String resource, String packageName,
      List<String> mixinClasses, String plugin, String refmap, boolean required) {
    public MixinConfig {
      mixinClasses = List.copyOf(mixinClasses);
    }
  }

  public record AccessTransformer(String jar, String resource, int ruleCount,
      List<String> sampleRules) {
    public AccessTransformer {
      sampleRules = List.copyOf(sampleRules);
    }
  }

  public record Coremod(String jar, String key, String value) {}

  public record Report(List<MixinConfig> mixinConfigs,
      List<AccessTransformer> accessTransformers, List<Coremod> coremods,
      List<String> blockers) {
    public Report {
      mixinConfigs = List.copyOf(mixinConfigs);
      accessTransformers = List.copyOf(accessTransformers);
      coremods = List.copyOf(coremods);
      blockers = List.copyOf(blockers);
    }
  }

  private ForgeTransformationInspector() {}

  public static Report inspect(List<Path> jars) throws IOException {
    List<MixinConfig> mixins = new ArrayList<>();
    List<AccessTransformer> transformers = new ArrayList<>();
    List<Coremod> coremods = new ArrayList<>();
    if (jars == null || jars.isEmpty()) {
      return new Report(List.of(), List.of(), List.of(), List.of());
    }
    for (Path path : jars.stream().filter(p -> p != null)
        .map(p -> p.toAbsolutePath().normalize()).distinct()
        .sorted(Comparator.comparing(Path::toString)).toList()) {
      inspectJar(path, mixins, transformers, coremods);
    }
    return new Report(mixins, transformers, coremods, blockers(mixins, transformers, coremods));
  }

  private static void inspectJar(Path path, List<MixinConfig> mixins,
      List<AccessTransformer> transformers, List<Coremod> coremods) throws IOException {
    Set<String> mixinResources = new LinkedHashSet<>();
    Set<String> atResources = new LinkedHashSet<>();
    List<Coremod> jarCoremods = new ArrayList<>();
    try (JarFile jar = new JarFile(path.toFile(), false)) {
      Manifest manifest = jar.getManifest();
      if (manifest != null) {
        for (String key : List.of("FMLCorePlugin", "FMLAT", "TweakClass", "MixinConfigs")) {
          String value = manifest.getMainAttributes().getValue(key);
          if (value == null || value.isBlank()) continue;
          if (key.equals("FMLAT")) {
            for (String resource : splitResources(value)) atResources.add(resource);
          } else if (key.equals("MixinConfigs")) {
            for (String resource : splitResources(value)) mixinResources.add(resource);
          } else {
            jarCoremods.add(new Coremod(path.toString(), key, value.trim()));
          }
        }
      }
      List<JarEntry> entries = Collections.list(jar.entries());
      entries.sort(Comparator.comparing(JarEntry::getName));
      for (JarEntry entry : entries) {
        String name = entry.getName();
        if (!entry.isDirectory() && isMixinConfigResource(name)) mixinResources.add(name);
      }
      for (String resource : mixinResources.stream().sorted().toList()) {
        JarEntry entry = jar.getJarEntry(resource);
        if (entry == null) continue;
        try (InputStream stream = jar.getInputStream(entry)) {
          MixinConfig config = parseMixinConfig(path.toString(), resource, stream);
          if (config != null) mixins.add(config);
        }
      }
      for (String resource : atResources.stream().sorted().toList()) {
        JarEntry entry = jar.getJarEntry(resource);
        if (entry == null) continue;
        try (InputStream stream = jar.getInputStream(entry)) {
          transformers.add(parseAccessTransformer(path.toString(), resource, stream));
        }
      }
    }
    coremods.addAll(jarCoremods);
  }

  private static List<String> splitResources(String value) {
    List<String> resources = new ArrayList<>();
    for (String part : value.split(",")) {
      String resource = part.trim().replace('\\', '/');
      while (resource.startsWith("/")) resource = resource.substring(1);
      if (!resource.isBlank() && !resource.contains("..")) resources.add(resource);
    }
    return resources;
  }

  private static boolean isMixinConfigResource(String name) {
    return name.endsWith(".mixins.json") || name.equals("mixins.json")
        || (name.startsWith("META-INF/") && name.endsWith(".json")
            && name.toLowerCase().contains("mixin"));
  }

  static MixinConfig parseMixinConfig(String jar, String resource, InputStream stream)
      throws IOException {
    String text = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    JsonObject json;
    try {
      json = JsonParser.parseString(text).getAsJsonObject();
    } catch (RuntimeException malformed) {
      return new MixinConfig(jar, resource, "", List.of(), "", "", true);
    }
    if (!json.has("package") && !json.has("mixins") && !json.has("client")
        && !json.has("server")) {
      // A refmap or another META-INF JSON that merely has "mixin" in its name
      // is not a Mixin configuration.
      return null;
    }
    String packageName = string(json, "package");
    List<String> classes = new ArrayList<>();
    addMixinEntries(json.get("mixins"), packageName, classes);
    addMixinEntries(json.get("client"), packageName, classes);
    addMixinEntries(json.get("server"), packageName, classes);
    String plugin = string(json, "plugin");
    String refmap = string(json, "refmap");
    boolean required = !json.has("required")
        || (json.get("required").isJsonPrimitive() && json.get("required").getAsBoolean());
    return new MixinConfig(jar, resource, packageName, classes, plugin, refmap, required);
  }

  private static void addMixinEntries(JsonElement element, String packageName,
      List<String> classes) {
    if (element == null || !element.isJsonArray()) return;
    for (JsonElement item : element.getAsJsonArray()) {
      String name = null;
      if (item.isJsonPrimitive()) {
        name = item.getAsString();
      } else if (item.isJsonObject()) {
        JsonObject object = item.getAsJsonObject();
        name = string(object, "config");
        if (name == null) name = string(object, "target");
      }
      if (name == null || name.isBlank()) continue;
      boolean alreadyQualified = packageName == null || packageName.isBlank()
          || name.startsWith(packageName + ".")
          || (name.contains(".") && Character.isLowerCase(name.charAt(0)));
      String qualified = alreadyQualified ? name : packageName + "." + name;
      if (!classes.contains(qualified)) classes.add(qualified);
    }
  }

  static AccessTransformer parseAccessTransformer(String jar, String resource, InputStream stream)
      throws IOException {
    String text = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    List<String> rules = new ArrayList<>();
    for (String rawLine : text.split("\\R")) {
      String line = rawLine;
      int comment = line.indexOf('#');
      if (comment >= 0) line = line.substring(0, comment);
      line = line.trim();
      if (!line.isEmpty()) rules.add(line);
    }
    List<String> sample = rules.subList(0, Math.min(8, rules.size()));
    return new AccessTransformer(jar, resource, rules.size(), sample);
  }

  private static List<String> blockers(List<MixinConfig> mixins,
      List<AccessTransformer> transformers, List<Coremod> coremods) {
    List<String> blockers = new ArrayList<>();
    if (!mixins.isEmpty()) {
      int classCount = mixins.stream().mapToInt(config -> config.mixinClasses().size()).sum();
      Map<String, Integer> perJar = new LinkedHashMap<>();
      for (MixinConfig config : mixins) {
        perJar.merge(config.jar(), 1, Integer::sum);
      }
      List<String> listedClasses = mixins.stream()
          .flatMap(config -> config.mixinClasses().stream()).distinct().sorted().toList();
      String preview = String.join(", ", listedClasses.subList(0,
          Math.min(6, listedClasses.size()))) + (listedClasses.size() > 6 ? ", ..." : "");
      blockers.add("Mixin transformation is not implemented; " + mixins.size()
          + " config(s) in " + perJar.size() + " JAR(s) declare " + classCount
          + " mixin class(es): " + preview);
    }
    if (!transformers.isEmpty()) {
      int rules = transformers.stream().mapToInt(AccessTransformer::ruleCount).sum();
      String detail = transformers.stream()
          .map(at -> at.resource() + " (" + at.ruleCount() + " rule(s))")
          .reduce((a, b) -> a + ", " + b).orElse("");
      blockers.add("Access-transformer rules are not applied by the JavaScript pipeline; "
          + rules + " rule(s) across " + transformers.size() + " file(s): " + detail
          + ". Only the verified original-Forge runtime path executes AT widening.");
    }
    for (Coremod coremod : coremods) {
      blockers.add("Loading transformation is not implemented: " + coremod.key()
          + "=" + coremod.value() + " in " + coremod.jar());
    }
    return List.copyOf(blockers);
  }

  private static String string(JsonObject object, String key) {
    JsonElement value = object.get(key);
    return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
  }
}
