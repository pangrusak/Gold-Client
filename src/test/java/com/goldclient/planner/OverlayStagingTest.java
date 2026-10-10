package com.goldclient.planner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;
import static com.goldclient.planner.MultiJarPlanner.Role.*;

class OverlayStagingTest {
    @TempDir Path directory;

    private Path jar(String filename, String name, String dependency)
            throws Exception {
        Path path = directory.resolve(filename);
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name,
                null, "java/lang/Object", null);
        if (dependency != null) {
            writer.visitField(Opcodes.ACC_PUBLIC, "dependency",
                    "L" + dependency + ";", null, null).visitEnd();
        }
        writer.visitEnd();

        try (JarOutputStream out =
                     new JarOutputStream(Files.newOutputStream(path))) {
            out.putNextEntry(new JarEntry(name + ".class"));
            out.write(writer.toByteArray());
            out.closeEntry();
        }
        return path;
    }

    private MultiJarPlanner.Input input(Path path) {
        return new MultiJarPlanner.Input(path, TRANSLATION);
    }

    private byte[] entryBytes(Path path, String entry) throws Exception {
        try (ZipFile zip = new ZipFile(path.toFile());
             var stream = zip.getInputStream(zip.getEntry(entry))) {
            return stream.readAllBytes();
        }
    }

    @Test
    void approvedOverlayUsesWinnerDependenciesAndRecordsLoser() throws Exception {
        Path original = jar("original.jar", "demo/Boundary", "missing/Backend");
        Path overlay = jar("overlay.jar", "demo/Boundary", null);

        var plan = new MultiJarPlanner().plan(
                List.of(input(original), input(overlay)),
                List.of("demo.Boundary"),
                Set.of("java/lang/Object"),
                Map.of("demo.Boundary", overlay));

        assertFalse(plan.hasBlockingDiagnostics());
        assertEquals(overlay.toAbsolutePath().toString(),
                plan.selected().get("demo/Boundary").jar());
        assertEquals(original.toAbsolutePath().toString(),
                plan.replacedDefinitions().get("demo/Boundary").get(0).jar());
        assertTrue(plan.missing().isEmpty());
    }

    @Test
    void ambiguousPlansAndInvalidOverridesAreRejected() throws Exception {
        Path original = jar("a.jar", "demo/Boundary", null);
        Path overlay = jar("b.jar", "demo/Boundary", null);
        var inputs = List.of(input(original), input(overlay));
        var planner = new MultiJarPlanner();

        var ambiguous = planner.plan(
                inputs, List.of("demo/Boundary"), Set.of("java/lang/Object"));

        Path output = directory.resolve("blocked.jar");
        byte[] existing = {1, 2, 3};
        Files.write(output, existing);

        assertThrows(IllegalArgumentException.class,
                () -> new PlanJarStager().stage(ambiguous, output));
        assertArrayEquals(existing, Files.readAllBytes(output));

        assertThrows(IllegalArgumentException.class,
                () -> planner.plan(inputs, List.of("demo/Boundary"),
                        Set.of("java/lang/Object"),
                        Map.of("demo/Boundary", directory.resolve("wrong.jar"))));

        assertThrows(IllegalArgumentException.class,
                () -> planner.plan(inputs, List.of("demo/Boundary"),
                        Set.of("java/lang/Object"),
                        Map.of("absent/Class", overlay)));
    }

    @Test
    void stagingPreservesBytesAndProducesRepeatableJar() throws Exception {
        Path original = jar("original.jar", "demo/Boundary", "missing/Backend");
        Path overlay = jar("overlay.jar", "demo/Boundary", null);
        var planner = new MultiJarPlanner();

        var forward = planner.plan(
                List.of(input(original), input(overlay)),
                List.of("demo/Boundary"), Set.of("java/lang/Object"),
                Map.of("demo/Boundary", overlay));

        var reverse = planner.plan(
                List.of(input(overlay), input(original)),
                List.of("demo/Boundary"), Set.of("java/lang/Object"),
                Map.of("demo/Boundary", overlay));

        assertEquals(forward, reverse);

        var stager = new PlanJarStager();
        var first = stager.stage(forward, directory.resolve("first.jar"));
        var second = stager.stage(reverse, directory.resolve("second.jar"));

        assertArrayEquals(Files.readAllBytes(first.jar()),
                Files.readAllBytes(second.jar()));
        assertArrayEquals(entryBytes(overlay, "demo/Boundary.class"),
                entryBytes(first.jar(), "demo/Boundary.class"));
        assertEquals(64, first.classes().get("demo/Boundary").sha256().length());

        assertThrows(UnsupportedOperationException.class,
                () -> first.classes().clear());
        assertThrows(IllegalArgumentException.class,
                () -> stager.stage(forward, overlay));

        try (ZipFile zip = new ZipFile(first.jar().toFile())) {
            assertEquals(1, zip.size());
            assertNotNull(zip.getEntry("demo/Boundary.class"));
        }
    }
}