package com.goldclient.translator;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ForgeHostRuntimeExporterTest {
  @TempDir
  Path tempDir;

  @Test
  void exportsBrowserEntrypointThatRequiresRealEaglerBindings() throws Exception {
    new ForgeHostRuntimeExporter().export(tempDir);

    Path entrypointPath = tempDir.resolve("eaglercraft-forge-entry.js");
    assertTrue(Files.isRegularFile(entrypointPath), "browser entrypoint must be exported");
    String entrypoint = Files.readString(entrypointPath);
    assertTrue(entrypoint.contains("window.goldClientForgeBindings"));
    assertTrue(entrypoint.contains("goldClientStartForgeMods"));
    assertTrue(entrypoint.contains("startForgeModsWithBindings"));
    assertTrue(entrypoint.contains("assertForgeHostBindings"));
    assertTrue(entrypoint.contains("Install a real Eaglercraft/Forge adapter first"));
    assertTrue(entrypoint.contains("forgeLifecycleBlockers"));
    assertTrue(Files.isRegularFile(tempDir.resolve("forge-host-runtime.js")));
    assertTrue(Files.isRegularFile(tempDir.resolve("forge-host-contract.json")));
  }
}
