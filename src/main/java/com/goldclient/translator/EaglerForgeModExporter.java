package com.goldclient.translator;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/**
 * Packs a generated loader bundle into one classic-script file that can be
 * uploaded through EaglerForgeInjector's Mod Manager.
 *
 * <p>The file embeds the pinned SRG-to-MCP member map, the EaglerForge binding
 * adapter, the translated class bundle, and the browser entrypoint. The
 * adapter installs {@code globalThis.goldClientForgeBindings} and starts the
 * Forge lifecycle once the injected client exposes its game settings. Embedding
 * the adapter does not implement Minecraft: unsupported platform operations
 * still fail explicitly through the adapter.</p>
 *
 * <p>This is packaging, not execution evidence. A produced file is not proof
 * that a translated mod runs.</p>
 */
public final class EaglerForgeModExporter {
  public record ModFileSummary(Path path, long bytes, int memberMapEntries, int moduleCount,
      boolean autoStart) {}

  public static final String DEFAULT_FILE_NAME = "gold-client-mod.js";
  static final String MEMBER_MAP_RESOURCE =
      "/com/goldclient/client/shared/mcp-1.12.2-members.json.gz";
  static final String ADAPTER_RESOURCE =
      "/com/goldclient/client/shared/eaglerforge-bindings.js";

  public static ModFileSummary write(Path bundleDirectory, Path outputFile, boolean compact,
      List<String> modIds) throws IOException {
    return write(bundleDirectory, outputFile, compact, modIds, Set.of());
  }

  public static ModFileSummary write(Path bundleDirectory, Path outputFile, boolean compact,
      List<String> modIds, Set<Path> additionalExcludedOutputs) throws IOException {
    Path bundle = bundleDirectory.toAbsolutePath().normalize();
    Path output = outputFile.toAbsolutePath().normalize();
    Set<Path> excluded = new LinkedHashSet<>();
    if (additionalExcludedOutputs != null) excluded.addAll(additionalExcludedOutputs);
    excluded.add(output);
    excluded.add(bundle.resolve(JavaScriptBundleExporter.DEFAULT_FILE_NAME));
    excluded.add(bundle.resolve(DEFAULT_FILE_NAME));

    String bundleSource = JavaScriptBundleExporter.bundle(bundle, compact, excluded);
    int moduleCount = JavaScriptBundleExporter.countModules(bundle, excluded);
    String memberMapSource = readMemberMapSource();
    JsonElement parsed = JsonParser.parseString(memberMapSource);
    if (!parsed.isJsonObject()) {
      throw new IOException("Embedded member map resource is not a JSON object.");
    }
    JsonObject memberMap = parsed.getAsJsonObject();
    String adapterSource = readTextResource(ADAPTER_RESOURCE);
    String modsJson = new Gson().toJson(modIds == null ? List.of() : List.copyOf(modIds));

    StringBuilder source = new StringBuilder(bundleSource.length()
        + memberMapSource.length() + adapterSource.length() + 4096);
    source.append("// Gold Client EaglerForge mod file.\n");
    source.append("// Upload this file through EaglerForge's Mod Manager.\n");
    source.append("// It embeds the member map and binding adapter; game APIs remain host-bound.\n");
    source.append("// Packaging is not proof that a translated mod runs.\n");
    source.append("globalThis.GoldClientModFile = Object.freeze({\n");
    source.append("  formatVersion: \"gold-eaglerforge-mod-v1\",\n");
    source.append("  mods: ").append(modsJson).append(",\n");
    source.append("  memberMapEntries: ").append(memberMap.size()).append(",\n");
    source.append("  adapterIncluded: true,\n");
    source.append("  autoStart: true\n");
    source.append("});\n");
    source.append("globalThis.goldClientForgeMemberMap = Object.freeze(")
        .append(memberMapSource)
        .append(");\n");
    source.append(bundleSource);
    if (!bundleSource.endsWith("\n")) source.append('\n');
    source.append(adapterSource);
    if (!adapterSource.endsWith("\n")) source.append('\n');
    source.append(installModMetadata(modsJson));

    Files.createDirectories(output.getParent());
    Files.writeString(output, source.toString(), StandardCharsets.UTF_8);
    return new ModFileSummary(output, Files.size(output), memberMap.size(), moduleCount, true);
  }

  private static String installModMetadata(String modsJson) {
    return """
        (function (global) {
          "use strict";
          try {
            const api = global.ModAPI;
            const script = global.document && global.document.currentScript;
            const isMod = script && String(script.getAttribute("data-isMod")).toLowerCase() === "true";
            if (!api || !api.meta || !isMod) return;
            const mods = %s;
            const label = mods.length ? mods.join(", ") : "(no mods)";
            if (typeof api.meta.title === "function") api.meta.title("Gold Client: " + label);
            if (typeof api.meta.description === "function") {
              api.meta.description(
                "Translated Forge 1.12.2 mods. Minecraft/Forge APIs require the Gold Client host adapter.");
            }
            if (typeof api.meta.version === "function") api.meta.version("1");
            if (typeof api.meta.credits === "function") {
              api.meta.credits("Generated by Gold Client from original Forge mod bytecode.");
            }
          } catch (error) {
            if (global.console && typeof global.console.warn === "function") {
              global.console.warn("[GoldForge] Mod metadata registration failed", error);
            }
          }
        })(typeof globalThis === "object" ? globalThis : this);
        """.formatted(modsJson);
  }

  static String readMemberMapSource() throws IOException {
    try (InputStream compressed = EaglerForgeModExporter.class
        .getResourceAsStream(MEMBER_MAP_RESOURCE)) {
      if (compressed == null) {
        throw new IOException("Missing classpath resource " + MEMBER_MAP_RESOURCE);
      }
      try (GZIPInputStream gzip = new GZIPInputStream(compressed)) {
        return new String(gzip.readAllBytes(), StandardCharsets.UTF_8);
      }
    }
  }

  static String readTextResource(String resource) throws IOException {
    try (InputStream stream = EaglerForgeModExporter.class.getResourceAsStream(resource)) {
      if (stream == null) throw new IOException("Missing classpath resource " + resource);
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
