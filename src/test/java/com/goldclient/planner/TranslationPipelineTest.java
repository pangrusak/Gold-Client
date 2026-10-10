package com.goldclient.planner;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;

import static org.junit.jupiter.api.Assertions.*;
import static com.goldclient.planner.MultiJarPlanner.Role.*;

class TranslationPipelineTest {
    @TempDir Path directory;

    private Path fixture(String filename, boolean validMain) throws Exception {
        Path jar = directory.resolve(filename);
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "fixture/Entry",
                null, "java/lang/Object", null);
        if (validMain) {
            MethodVisitor method = writer.visitMethod(
                    Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                    "main", "([Ljava/lang/String;)V", null, null);
            method.visitCode();
            method.visitInsn(Opcodes.RETURN);
            method.visitMaxs(0, 1);
            method.visitEnd();
        }
        writer.visitEnd();
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("fixture/Entry.class"));
            out.write(writer.toByteArray());
            out.closeEntry();
        }
        return jar;
    }

    private int run(String... args) {
        ByteArrayOutputStream log = new ByteArrayOutputStream();
        try (PrintStream stream = new PrintStream(log)) {
            return PipelineCommand.run(args, stream, stream);
        }
    }

    @Test
    void commandPlansStagesAndWritesProvenance() throws Exception {
        Path input = fixture("input.jar", true);
        Path report = directory.resolve("report.json");
        Path staged = directory.resolve("staged.jar");

        assertEquals(0, run(
                "--input", input.toString(),
                "--root", "fixture.Entry",
                "--external", "java.lang.Object",
                "--external", "java.lang.String",
                "--report", report.toString(),
                "--stage", staged.toString()));

        var json = JsonParser.parseString(Files.readString(report)).getAsJsonObject();
        assertEquals("STAGED", json.get("status").getAsString());
        assertTrue(json.getAsJsonObject("selected").has("fixture/Entry"));
        assertEquals(1, json.getAsJsonObject("sourceSha256").size());
        assertEquals(TranslationPipeline.sha256(staged),
                json.get("stagedJarSha256").getAsString());
    }

    @Test
    void blockedPlanWritesReportWithoutStaging() throws Exception {
        Path input = fixture("blocked.jar", true);
        Path report = directory.resolve("blocked.json");
        Path staged = directory.resolve("must-not-exist.jar");

        assertEquals(2, run(
                "--input", input.toString(),
                "--root", "fixture.Entry",
                "--report", report.toString(),
                "--stage", staged.toString()));

        var json = JsonParser.parseString(Files.readString(report)).getAsJsonObject();
        assertEquals("BLOCKED", json.get("status").getAsString());
        assertTrue(json.getAsJsonObject("missing").has("java/lang/Object"));
        assertFalse(Files.exists(staged));
    }

    @Test
    void changedSourceIsRejectedAndExistingOutputIsPreserved() throws Exception {
        Path input = fixture("change.jar", true);
        var pipeline = new TranslationPipeline();
        var prepared = pipeline.prepare(
                List.of(new MultiJarPlanner.Input(input, TRANSLATION)),
                List.of("fixture.Entry"),
                Set.of("java/lang/Object", "java/lang/String"),
                Map.of());

        Path output = directory.resolve("existing.jar");
        byte[] previous = {5, 6, 7};
        Files.write(output, previous);
        Files.write(input, new byte[] {1, 2, 3});

        assertThrows(IOException.class, () -> pipeline.stage(prepared, output));
        assertArrayEquals(previous, Files.readAllBytes(output));
    }

    @Test
    void reportCannotOverwriteInput() throws Exception {
        Path input = fixture("protected.jar", true);
        byte[] before = Files.readAllBytes(input);

        assertEquals(2, run(
                "--input", input.toString(),
                "--root", "fixture.Entry",
                "--report", input.toString()));

        assertArrayEquals(before, Files.readAllBytes(input));
    }
}