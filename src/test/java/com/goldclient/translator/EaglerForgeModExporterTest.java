package com.goldclient.translator;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EaglerForgeModExporterTest {
  @TempDir
  Path tempDir;

  @Test
  void packsAdapterMemberMapMetadataAndAutoStartIntoOneModFile() throws Exception {
    Path bundle = tempDir.resolve("bundle");
    Files.createDirectories(bundle);
    Files.writeString(bundle.resolve("eaglercraft-forge-entry.js"), """
        export function startGoldClientForgeMods() { return { started: [] }; }
        export function installGoldClientForgeEntrypoint(target = globalThis.window) {
          target.goldClientForgeEntrypoint = { formatVersion: "test-entry" };
        }
        if (typeof globalThis.window !== "undefined") installGoldClientForgeEntrypoint();
        """, StandardCharsets.UTF_8);
    Files.writeString(bundle.resolve("forge-lifecycle.js"), """
        export const forgeLoaderStatus = () => ({ started: false, modCount: 0 });
        export const forgeMods = [];
        export const forgeLifecycleBlockers = [];
        export function stopForgeMods() { return []; }
        """, StandardCharsets.UTF_8);
    Files.writeString(bundle.resolve("gold-client-runtime.js"), """
        export const GoldClientRuntime = { annotationData: () => ({}), resolveClass: () => null };
        """, StandardCharsets.UTF_8);
    Files.writeString(bundle.resolve("mod-index.js"),
        "export const classes = {};\n", StandardCharsets.UTF_8);

    Path modFile = tempDir.resolve(EaglerForgeModExporter.DEFAULT_FILE_NAME);
    EaglerForgeModExporter.ModFileSummary summary = EaglerForgeModExporter.write(
        bundle, modFile, true, List.of("demo", "library"));

    assertTrue(Files.isRegularFile(modFile));
    assertTrue(summary.autoStart());
    assertTrue(summary.memberMapEntries() > 19_000,
        "the member map should carry the pinned SRG/MCP table");
    assertEquals(4, summary.moduleCount());
    String source = Files.readString(modFile, StandardCharsets.UTF_8);
    assertFalse(source.contains("import "), "classic-script mod file must not use ESM imports");
    assertTrue(source.contains("GoldClientModFile"), source.substring(0, 200));
    assertTrue(source.contains("goldClientForgeMemberMap"));
    assertTrue(source.contains("goldClientForgeBindings"));
    assertFalse(source.contains("./gold-client-forge.js"),
        "the mod file must not reference a sibling bundle");

    Path runner = tempDir.resolve("mod-file-test.cjs");
    Files.writeString(runner, """
        const fs = require("node:fs");
        const path = require("node:path");
        const vm = require("node:vm");
        const source = fs.readFileSync(path.join(__dirname, "gold-client-mod.js"), "utf8");
        const sandbox = {
          console, Date, Math, Map, Set, Promise, Object, Array, String, Number,
          Error, TypeError, RangeError, RegExp, JSON, setTimeout, clearTimeout, queueMicrotask,
          goldClientForgeAutoStart: false
        };
        sandbox.globalThis = sandbox;
        sandbox.window = sandbox;
        vm.createContext(sandbox);
        vm.runInContext(source, sandbox, { filename: "gold-client-mod.js" });
        const metadata = sandbox.GoldClientModFile;
        if (!metadata || metadata.formatVersion !== "gold-eaglerforge-mod-v1") {
          throw new Error("mod file metadata missing");
        }
        if (metadata.mods.join(",") !== "demo,library") {
          throw new Error("mod file did not record its inputs: " + JSON.stringify(metadata.mods));
        }
        if (metadata.memberMapEntries < 19000 || metadata.autoStart !== true
            || metadata.adapterIncluded !== true) {
          throw new Error("mod file metadata mismatch: " + JSON.stringify(metadata));
        }
        const map = sandbox.goldClientForgeMemberMap;
        if (!map || map.func_96300_b !== "getValueByName") {
          throw new Error("embedded member map is missing the pinned SRG mapping");
        }
        const bindings = sandbox.goldClientForgeBindings;
        if (!bindings || bindings.source !== "eaglerforge"
            || bindings.memberMapSize !== metadata.memberMapEntries
            || typeof bindings.startWhenReady !== "function") {
          throw new Error("binding adapter was not installed from the mod file");
        }
        if (!sandbox.GoldClientForge || typeof sandbox.GoldClientForge.start !== "function") {
          throw new Error("translated Forge bundle was not installed");
        }
        if (!sandbox.window.goldClientForgeEntrypoint) {
          throw new Error("browser entrypoint was not installed");
        }
        console.log("mod-file-ok");
        """, StandardCharsets.UTF_8);

    Process node;
    try {
      node = new ProcessBuilder("node", runner.toString()).redirectErrorStream(true).start();
    } catch (java.io.IOException missingNode) {
      org.junit.jupiter.api.Assumptions.assumeTrue(false, "Node.js is not installed");
      return;
    }
    assertTrue(node.waitFor(20, TimeUnit.SECONDS), "mod file load timed out");
    String output = new String(node.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
    assertEquals(0, node.exitValue(), output);
    assertEquals("mod-file-ok", output);
  }

  @Test
  void refusedExistingSiblingBundlesAreNotNestedIntoTheModFile() throws Exception {
    Path bundle = tempDir.resolve("bundle");
    Files.createDirectories(bundle);
    Files.writeString(bundle.resolve("eaglercraft-forge-entry.js"),
        "export function startGoldClientForgeMods() { return { started: [] }; }\n"
            + "export function installGoldClientForgeEntrypoint() {}\n",
        StandardCharsets.UTF_8);
    Files.writeString(bundle.resolve("forge-lifecycle.js"),
        "export const forgeLoaderStatus = () => ({});\n"
            + "export const forgeMods = [];\n"
            + "export const forgeLifecycleBlockers = [];\n"
            + "export function stopForgeMods() { return []; }\n",
        StandardCharsets.UTF_8);
    Files.writeString(bundle.resolve("gold-client-runtime.js"),
        "export const GoldClientRuntime = { annotationData: () => ({}), resolveClass: () => null };\n",
        StandardCharsets.UTF_8);
    Files.writeString(bundle.resolve("mod-index.js"), "export const classes = {};\n",
        StandardCharsets.UTF_8);
    Files.writeString(bundle.resolve(JavaScriptBundleExporter.DEFAULT_FILE_NAME),
        "// stale sibling bundle\nthis.stale = true;\n", StandardCharsets.UTF_8);
    Files.writeString(bundle.resolve(EaglerForgeModExporter.DEFAULT_FILE_NAME),
        "// stale mod file\nthis.staleMod = true;\n", StandardCharsets.UTF_8);

    Path modFile = bundle.resolve(EaglerForgeModExporter.DEFAULT_FILE_NAME);
    EaglerForgeModExporter.ModFileSummary summary = EaglerForgeModExporter.write(
        bundle, modFile, true, List.of("demo"));
    assertEquals(4, summary.moduleCount(),
        "only generated modules may be packed, not previous self-contained outputs");
    String source = Files.readString(modFile, StandardCharsets.UTF_8);
    assertFalse(source.contains("this.stale = true"),
        source.substring(0, Math.min(400, source.length())));
    assertFalse(source.contains("this.staleMod = true"));
  }
}
