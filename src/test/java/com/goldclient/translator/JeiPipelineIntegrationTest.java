package com.goldclient.translator;

import com.goldclient.planner.PipelineCommand;
import com.goldclient.planner.TranslationPipeline;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

class JeiPipelineIntegrationTest {
    @Test
    void twoJarPipelinePreservesBytesAndMatchesOriginalJvm(
            @TempDir Path temporary) throws Exception {
        Path repository = Path.of("").toAbsolutePath();

        Path slice = repository.resolve(
                "target/jei-ingredient-elements/jei-original-ingredient-element-slice.jar");
        Path comparisonScript = repository.resolve(
                "target/jei-ingredient-elements/compare.cjs");
        Path comparisonData = repository.resolve(
                "target/jei-ingredient-elements/comparison.json");

        Path fastutil = Path.of(
                System.getProperty("user.home"), ".m2", "repository",
                "it", "unimi", "dsi", "fastutil", "7.1.0", "fastutil-7.1.0.jar");

        Path baseline = repository.resolve(
                "target/forge-baseline/mdk/build/tmp/deobfMcSRG/deobfed.jar");
        Path forge = repository.resolve("target/forge-baseline/forge-mapped.jar");

        assertTrue(Files.isRegularFile(baseline),
                "Prepare the Minecraft baseline before this integration test");
        assertTrue(Files.isRegularFile(forge),
                "Prepare the mapped Forge runtime before this integration test");
        assertTrue(Files.isRegularFile(fastutil),
                "The explicit fastutil 7.1.0 input is required");

        // Generate fresh staging inputs and JVM expectations using the
        // established milestone, including its original-byte assertions.
        new JeiIngredientElementMilestoneTest()
                .originalIngredientElementsAndSearchMatchTranslatedOutput(temporary);

        assertTrue(Files.isRegularFile(slice));
        assertTrue(Files.isRegularFile(comparisonScript));
        assertTrue(Files.isRegularFile(comparisonData));

        JsonObject comparison = JsonParser.parseString(
                Files.readString(comparisonData, StandardCharsets.UTF_8))
                .getAsJsonObject();

        String corpusSource = comparison.get("corpusSource").getAsString();
        int records = comparison.getAsJsonArray("ids").size();
        int searches = comparison.getAsJsonArray("queries").size();

        if (Boolean.getBoolean("goldclient.requireClientCorpus")) {
            assertEquals("browser-exported-client-corpus", corpusSource,
                    "Real client corpus is required; fixture fallback is not acceptable");
            assertEquals(411, records,
                    "The current client corpus contract requires 411 records");
        }

        Path report = temporary.resolve("pipeline-report.json");
        Path staged = temporary.resolve("pipeline-selected.jar");
        Path translated = temporary.resolve("translated");

        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        int exit;
        try (PrintStream log = new PrintStream(
                captured, true, StandardCharsets.UTF_8)) {
            exit = PipelineCommand.run(new String[] {
                    "--input", slice.toString(),
                    "--input", fastutil.toString(),
                    "--root", "JeiIngredientEntry",
                    "--reachability", "teavm",
                    "--report", report.toString(),
                    "--stage", staged.toString(),
                    "--to-js", translated.toString(),
                    "--main-class", "JeiIngredientEntry",
                    "--child-first", "org.apache.logging.log4j."
            }, log, log);
        }

        assertEquals(0, exit,
                "Integrated pipeline failed:\n"
                        + captured.toString(StandardCharsets.UTF_8));

        JsonObject plan = JsonParser.parseString(
                Files.readString(report, StandardCharsets.UTF_8)).getAsJsonObject();

        assertEquals("TRANSLATED_WITH_DEFERRED_STRUCTURAL_REFERENCES",
                plan.get("status").getAsString());
        assertEquals(2, plan.getAsJsonObject("sourceSha256").size());
        assertEquals(0, plan.getAsJsonArray("runtimeClasses").size());
        assertEquals(0, plan.getAsJsonObject("duplicates").size());

        JsonObject selected = plan.getAsJsonObject("selected");
        assertTrue(selected.has("it/unimi/dsi/fastutil/ints/IntSet"),
                "fastutil must be explicitly selected, not borrowed from test classpath");
        assertEquals(fastutil.toAbsolutePath().normalize().toString(),
                selected.getAsJsonObject("it/unimi/dsi/fastutil/ints/IntSet")
                        .get("jar").getAsString());

        assertEquals(TranslationPipeline.sha256(staged),
                plan.get("stagedJarSha256").getAsString());

        // Every selected class must retain the exact bytes of its recorded source.
        int sliceCount = 0;
        int fastutilCount = 0;
        try (ZipFile output = new ZipFile(staged.toFile());
             ZipFile sliceZip = new ZipFile(slice.toFile());
             ZipFile fastutilZip = new ZipFile(fastutil.toFile())) {
            assertEquals(selected.size(), output.size());

            for (var item : selected.entrySet()) {
                JsonObject origin = item.getValue().getAsJsonObject();
                Path source = Path.of(origin.get("jar").getAsString())
                        .toAbsolutePath().normalize();
                String entry = origin.get("entry").getAsString();

                ZipFile input;
                if (source.equals(slice.toAbsolutePath().normalize())) {
                    input = sliceZip;
                    sliceCount++;
                } else if (source.equals(fastutil.toAbsolutePath().normalize())) {
                    input = fastutilZip;
                    fastutilCount++;
                } else {
                    throw new AssertionError("Unexpected source origin: " + source);
                }

                assertNotNull(input.getEntry(entry), "Missing source entry: " + entry);
                assertNotNull(output.getEntry(entry), "Missing staged entry: " + entry);

                try (var originalBytes = input.getInputStream(input.getEntry(entry));
                     var stagedBytes = output.getInputStream(output.getEntry(entry))) {
                    assertArrayEquals(originalBytes.readAllBytes(),
                            stagedBytes.readAllBytes(),
                            "Staging changed class bytes: " + entry);
                }
            }
        }

        assertTrue(sliceCount > 0);
        assertTrue(fastutilCount > 0);

        Path javascript = translated.resolve("eagler-mod.js");
        assertTrue(Files.isRegularFile(javascript));
        assertTrue(Files.size(javascript) > 0);
        assertEquals(TranslationPipeline.sha256(javascript),
                plan.get("javascriptSha256").getAsString());

        Process process = new ProcessBuilder(
                "node", comparisonScript.toString(),
                javascript.toString(), comparisonData.toString())
                .directory(repository.toFile())
                .redirectErrorStream(true)
                .start();

        String output = new String(
                process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(),
                "Pipeline JavaScript differs from JVM expectations:\n" + output);
        assertTrue(output.contains("matches JVM"), output);

        System.out.printf(
                "%nJEI PIPELINE VERIFIED: source=%s, records=%d, searches=%d, "
                        + "sliceClasses=%d, fastutilClasses=%d%n",
                corpusSource, records, searches, sliceCount, fastutilCount);
        System.out.println(output);
    }
}