package com.goldclient.planner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.*;

import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

class ProfileManifestToolTest {
    @TempDir Path directory;

    private Path jar(String filename, String field) throws Exception {
        Path path = directory.resolve(filename);
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "demo/Example",
                null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PUBLIC, field, "I", null, null).visitEnd();
        writer.visitEnd();
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(path))) {
            out.putNextEntry(new JarEntry("demo/Example.class"));
            out.write(writer.toByteArray());
            out.closeEntry();
        }
        return path;
    }

    @Test
    void assemblyDoesNotRequireReferenceAndPreservesSelectedBytes() throws Exception {
        Path original = jar("original.jar", "original");
        Path overlay = jar("overlay.jar", "replacement");
        Path reference = directory.resolve("reference.jar");
        Files.copy(overlay, reference);
        Path manifest = directory.resolve("profile.json");

        var profile = ProfileManifestTool.capture(
                manifest, reference, List.of(original, overlay));
        assertEquals(1, profile.classes().get("demo/Example.class").source());

        Files.delete(reference);
        Path first = directory.resolve("first.jar");
        Path second = directory.resolve("second.jar");
        assertEquals(1, ProfileManifestTool.assemble(manifest, first));
        ProfileManifestTool.assemble(manifest, second);
        assertArrayEquals(Files.readAllBytes(first), Files.readAllBytes(second));

        try (ZipFile source = new ZipFile(overlay.toFile());
             ZipFile result = new ZipFile(first.toFile());
             var a = source.getInputStream(source.getEntry("demo/Example.class"));
             var b = result.getInputStream(result.getEntry("demo/Example.class"))) {
            assertArrayEquals(a.readAllBytes(), b.readAllBytes());
        }
    }

    @Test
    void changedOriginalIsRejectedWithoutReplacingOutput() throws Exception {
        Path original = jar("original.jar", "value");
        Path reference = directory.resolve("reference.jar");
        Files.copy(original, reference);
        Path manifest = directory.resolve("profile.json");
        ProfileManifestTool.capture(manifest, reference, List.of(original));

        Path output = directory.resolve("output.jar");
        byte[] previous = {8, 9};
        Files.write(output, previous);
        Files.write(original, new byte[] {1, 2});

        assertThrows(java.io.IOException.class,
                () -> ProfileManifestTool.assemble(manifest, output));
        assertArrayEquals(previous, Files.readAllBytes(output));
    }

    @Test
    void captureRejectsClassesWithoutMatchingSource() throws Exception {
        Path original = jar("original.jar", "original");
        Path reference = jar("reference.jar", "different");
        Path manifest = directory.resolve("profile.json");

        assertThrows(java.io.IOException.class,
                () -> ProfileManifestTool.capture(
                        manifest, reference, List.of(original)));
        assertFalse(Files.exists(manifest));
    }
}