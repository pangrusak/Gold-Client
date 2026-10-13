package com.goldclient.translator;

import com.goldclient.analyzer.ModAnalysis;
import com.goldclient.analyzer.ModAnalyzer;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards against accidentally omitting classes from whole-mod JavaScript exports. */
class JavaScriptWholeJarCoverageTest {
  @TempDir
  Path tempDir;

  @Test
  void exportsEveryAppleSkinClassModule() throws Exception {
    assertWholeJarCoverage(Path.of("test-mods/appleskin1.12.2.jar"), "AppleSkin");
  }

  @Test
  void exportsEveryJeiClassModule() throws Exception {
    Path jei = Files.isRegularFile(Path.of("test-mods/jei1.12.2.jar"))
        ? Path.of("test-mods/jei1.12.2.jar")
        : Path.of("test-mods/jei.jar");
    assertWholeJarCoverage(jei, "JEI");
  }

  private void assertWholeJarCoverage(Path jar, String label) throws Exception {
    assertTrue(Files.isRegularFile(jar), label + " test JAR is missing: " + jar);
    ModAnalysis analysis = new ModAnalyzer().analyze(jar);
    Path output = tempDir.resolve(label.toLowerCase(java.util.Locale.ROOT));
    var export = new JavaScriptModTranslationExporter().export(jar, analysis.classes(), output);

    JsonObject coverage = JsonParser.parseString(
        Files.readString(output.resolve("class-coverage.json"), StandardCharsets.UTF_8))
        .getAsJsonObject();
    assertTrue(coverage.get("complete").getAsBoolean(),
        label + " class coverage is incomplete: " + coverage);
    assertEquals(analysis.classes().size(), coverage.get("modeledClasses").getAsInt());
    assertEquals(analysis.classes().size(), coverage.get("translatedModules").getAsInt());
    assertEquals(analysis.classes().size(), export.fileCount());

    for (var clazz : analysis.classes()) {
      Path module = output.resolve(clazz.name().replace('.', '/') + ".translated.js");
      assertTrue(Files.isRegularFile(module),
          label + " class has no translated module: " + clazz.name());
      assertTrue(Files.size(module) > 0,
          label + " class module is empty: " + clazz.name());
    }
  }
}
