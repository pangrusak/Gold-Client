package com.goldclient.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CreateClientCommandTest {
  @TempDir Path directory;

  private record Result(int code, String out, String err) {}

  private Result run(String... args) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    int code;
    try (PrintStream stdout = new PrintStream(out, true, StandardCharsets.UTF_8);
         PrintStream stderr = new PrintStream(err, true, StandardCharsets.UTF_8)) {
      code = CreateClientCommand.run(args, stdout, stderr);
    }
    return new Result(code, out.toString(StandardCharsets.UTF_8),
        err.toString(StandardCharsets.UTF_8));
  }

  @Test
  void helpDocumentsTheCommandAndExitsZero() {
    Result result = run("--help");
    assertEquals(0, result.code());
    assertTrue(result.out().contains("--create-client"));
    assertTrue(result.out().contains("--output"));
  }

  @Test
  void unknownOptionFailsWithoutWritingAFile() {
    Result result = run("--not-an-option");
    assertEquals(1, result.code());
    assertTrue(result.err().contains("Unknown option"));
  }

  @Test
  void missingInputFailsBeforeDownloadingTheClient() {
    Result result = run("--input", directory.resolve("missing.jar").toString());
    assertEquals(1, result.code());
    assertTrue(result.err().contains("Input JAR does not exist"));
  }

  @Test
  void duplicateInputIsRejected() throws Exception {
    Path jar = directory.resolve("mod.jar");
    Files.write(jar, new byte[] {0x50, 0x4b, 0x03, 0x04});
    Result result = run("--input", jar.toString(), "--input", jar.toString());
    assertEquals(1, result.code());
    assertTrue(result.err().contains("supplied more than once"));
  }

  @Test
  void existingOutputRequiresForce() throws Exception {
    Path jar = directory.resolve("mod.jar");
    Files.write(jar, new byte[] {0x50, 0x4b, 0x03, 0x04});
    Path output = directory.resolve("gold-client.html");
    Files.writeString(output, "existing");
    Result result = run("--input", jar.toString(), "--output", output.toString());
    assertEquals(1, result.code());
    assertTrue(result.err().contains("Output already exists"));
  }

  @Test
  void jsonFailureIsMachineReadable() {
    Result result = run("--json", "--input", directory.resolve("missing.jar").toString());
    assertEquals(1, result.code());
    assertTrue(result.out().contains("\"ok\":false"));
    assertTrue(result.out().contains("\"error\""));
  }

  @Test
  void requiredBuilderResourcesArePackaged() throws Exception {
    for (String resource : List.of(
        "build-self-contained.cjs",
        "ui.html",
        "eaglerforge/core/core.js",
        "eaglerforge/core/patches.js",
        "shared/eaglerforge-bindings.js",
        "shared/mcp-1.12.2-members.json.gz")) {
      try (InputStream stream = CreateClientCommand.class.getResourceAsStream(
          "/com/goldclient/client/" + resource)) {
        assertNotNull(stream, resource);
        assertTrue(stream.readAllBytes().length > 0, resource);
      }
    }
  }

  @Test
  void buildsOneSelfContainedHtmlWhenPinnedClientIsCached() throws Exception {
    Path cached = Path.of("cache", "clients", ClientSourceCache.CLIENT_SHA256 + ".html");
    Assumptions.assumeTrue(Files.isRegularFile(cached),
        "pinned client is not cached; skipping end-to-end build");
    Path output = directory.resolve("gold-client.html");
    Result result = run("--input", "test-mods/appleskin1.12.2.jar",
        "--output", output.toString(), "--force");
    assertEquals(0, result.code(), result.err());
    assertTrue(Files.size(output) > ClientSourceCache.CLIENT_BYTES);

    boolean marker = false;
    try (BufferedReader reader = Files.newBufferedReader(output, StandardCharsets.UTF_8)) {
      String line;
      while ((line = reader.readLine()) != null) {
        assertFalse(line.matches("(?i).*<script\\b[^>]*\\bsrc\\s*=.*"), line);
        if (line.contains("gold-client-forge-bundle")) marker = true;
      }
    }
    assertTrue(marker, "translated bundle was not inlined");
  }

  @Test
  void uiHelpAndInvalidOptionsAreHandled() {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    int help;
    int invalid;
    try (PrintStream stdout = new PrintStream(out, true, StandardCharsets.UTF_8);
         PrintStream stderr = new PrintStream(err, true, StandardCharsets.UTF_8)) {
      help = UiCommand.run(new String[] {"--help"}, stdout, stderr);
      invalid = UiCommand.run(new String[] {"--bogus"}, stdout, stderr);
    }
    assertEquals(0, help);
    assertTrue(out.toString(StandardCharsets.UTF_8).contains("--port"));
    assertEquals(1, invalid);
    assertTrue(err.toString(StandardCharsets.UTF_8).contains("Unknown option"));
    assertFalse(err.toString(StandardCharsets.UTF_8).isBlank());
  }
}
