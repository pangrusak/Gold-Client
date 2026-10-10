package com.goldclient.planner;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.*;

import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.List;
import java.util.jar.*;

import static org.junit.jupiter.api.Assertions.*;

class GeneratedOverlayPinTest {
    @TempDir Path directory;

    private void writeJar(Path path, int year, String field) throws Exception {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "demo/Language",
                null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PUBLIC, field, "I", null, null).visitEnd();
        writer.visitEnd();

        try (JarOutputStream jar =
                     new JarOutputStream(Files.newOutputStream(path))) {
            JarEntry entry = new JarEntry("demo/Language.class");
            entry.setTimeLocal(LocalDateTime.of(year, 1, 1, 0, 0));
            jar.putNextEntry(entry);
            jar.write(writer.toByteArray());
            jar.closeEntry();
        }
    }

    private Path profile(Path source, boolean generated) throws Exception {
        Path reference = directory.resolve("reference.jar");
        Files.copy(source, reference);
        Path manifest = directory.resolve("profile.json");
        ProfileManifestTool.capture(manifest, reference, List.of(source));

        if (generated) {
            var json = JsonParser.parseString(Files.readString(manifest))
                    .getAsJsonObject();
            json.getAsJsonArray("sources").get(0).getAsJsonObject()
                    .addProperty("generatedArchive", true);
            Files.writeString(manifest, json.toString());
        }
        return manifest;
    }

    @Test
    void generatedArchiveAllowsMetadataChangeButRejectsChangedClass() throws Exception {
        Path source = directory.resolve("overlay.jar");
        writeJar(source, 2000, "same");
        Path manifest = profile(source, true);
        String originalHash = TranslationPipeline.sha256(source);

        writeJar(source, 2001, "same");
        assertNotEquals(originalHash, TranslationPipeline.sha256(source));

        Path output = directory.resolve("assembled.jar");
        assertEquals(1, ProfileManifestTool.assemble(manifest, output));
        byte[] verifiedOutput = Files.readAllBytes(output);

        writeJar(source, 2002, "changed");
        assertThrows(java.io.IOException.class,
                () -> ProfileManifestTool.assemble(manifest, output));
        assertArrayEquals(verifiedOutput, Files.readAllBytes(output));
    }

    @Test
    void originalArchiveStillRejectsMetadataChange() throws Exception {
        Path source = directory.resolve("original.jar");
        writeJar(source, 2000, "same");
        Path manifest = profile(source, false);
        writeJar(source, 2001, "same");

        assertThrows(java.io.IOException.class,
                () -> ProfileManifestTool.assemble(
                        manifest, directory.resolve("blocked.jar")));
    }
}