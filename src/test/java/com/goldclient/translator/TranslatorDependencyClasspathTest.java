package com.goldclient.translator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import javax.tools.ToolProvider;
import static org.junit.jupiter.api.Assertions.*;

class TranslatorDependencyClasspathTest {
    @TempDir Path temporary;

    private Path jar(String name, Path classes) throws Exception {
        Path result = temporary.resolve(name);
        try (var output = new JarOutputStream(Files.newOutputStream(result))) {
            if (classes != null) {
                try (var files = Files.walk(classes)) {
                    for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                        output.putNextEntry(new JarEntry(classes.relativize(file)
                                .toString().replace('\\', '/')));
                        Files.copy(file, output);
                        output.closeEntry();
                    }
                }
            }
        }
        return result;
    }

    @Test void preservesExplicitOrderAndDeduplicatesPaths() throws Exception {
        Path input = jar("input.jar", null);
        Path dependency = jar("dependency.jar", null);
        assertArrayEquals(new java.net.URL[] {
                input.toRealPath().toUri().toURL(),
                dependency.toRealPath().toUri().toURL()
        }, TeaVmTranslator.translationUrls(input, List.of(dependency, input, dependency)));
    }

    @Test void rejectsMissingAndMalformedDependencies() throws Exception {
        Path input = jar("input.jar", null);
        assertThrows(java.io.IOException.class, () -> TeaVmTranslator.translationUrls(
                input, List.of(temporary.resolve("missing.jar"))));
        Path malformed = temporary.resolve("malformed.jar");
        Files.writeString(malformed, "not a JAR");
        assertThrows(java.io.IOException.class, () -> TeaVmTranslator.translationUrls(
                input, List.of(malformed)));
    }

    @Test void removesStaleOutputOnFailure() throws Exception {
        Path input = jar("input.jar", null);
        Path output = temporary.resolve("failed-output");
        Files.createDirectories(output);
        Files.writeString(output.resolve("eagler-mod.js"), "stale JavaScript");
        Files.writeString(output.resolve("translation-inputs.properties"), "stale provenance");
        assertThrows(java.io.IOException.class, () -> TeaVmTranslator.translate(
                input, output, "fixture.entry.Main", Set.of(),
                List.of(temporary.resolve("missing.jar"))));
        assertFalse(Files.exists(output.resolve("eagler-mod.js")));
        assertFalse(Files.exists(output.resolve("translation-inputs.properties")));
    }

    @Test void translatesOriginalCodeAcrossSeparateJars() throws Exception {
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "A JDK is required");
        Path depSource = temporary.resolve("Dependency.java");
        Path entrySource = temporary.resolve("Main.java");
        Path depClasses = temporary.resolve("dep-classes");
        Path entryClasses = temporary.resolve("entry-classes");
        Files.createDirectories(depClasses);
        Files.createDirectories(entryClasses);
        Files.writeString(depSource,
                "package fixture.dep; public final class Dependency { " +
                "public static String value() { return \"ORIGINAL_DEPENDENCY_EXECUTED\"; } }");
        assertEquals(0, compiler.run(null, null, null, "--release", "8", "-d",
                depClasses.toString(), depSource.toString()));
        Path dependency = jar("dependency.jar", depClasses);
        Files.writeString(entrySource,
                "package fixture.entry; public final class Main { " +
                "public static void main(String[] args) { " +
                "System.out.println(fixture.dep.Dependency.value()); } }");
        assertEquals(0, compiler.run(null, null, null, "--release", "8", "-classpath",
                dependency.toString(), "-d", entryClasses.toString(), entrySource.toString()));
        Path input = jar("entry.jar", entryClasses);
        byte[] originalInput = Files.readAllBytes(input);
        byte[] originalDependency = Files.readAllBytes(dependency);
        Path output = temporary.resolve("translated");
        TeaVmTranslator.translate(input, output, "fixture.entry.Main",
                Set.of("fixture."), List.of(dependency));
        assertArrayEquals(originalInput, Files.readAllBytes(input));
        assertArrayEquals(originalDependency, Files.readAllBytes(dependency));
        var provenance = new Properties();
        try (var stream = Files.newInputStream(output.resolve("translation-inputs.properties"))) {
            provenance.load(stream);
        }
        assertEquals("2", provenance.getProperty("input.count"));
        assertEquals("false", provenance.getProperty("fullModEquivalenceVerified"));
        Path runner = temporary.resolve("run.cjs");
        Files.writeString(runner,
                "const m=require(process.argv[2]);" +
                "m.main([], e=>{if(e)throw e;});");
        Process process = new ProcessBuilder("node", runner.toString(),
                output.resolve("eagler-mod.js").toString()).redirectErrorStream(true).start();
        String console = new String(process.getInputStream().readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), console);
        assertTrue(console.contains("ORIGINAL_DEPENDENCY_EXECUTED"), console);
    }
}
