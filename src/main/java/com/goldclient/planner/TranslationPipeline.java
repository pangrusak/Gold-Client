package com.goldclient.planner;

import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * Pins every supplied JAR around planning and checks it around staging.
 * This detects changed inputs; it is not an independent authenticity check.
 * Callers must not mutate source files concurrently.
 */
public final class TranslationPipeline {
    public record Prepared(
            MultiJarPlanner.Plan plan,
            SortedMap<String, String> sourceSha256) {}

    public Prepared prepare(
            List<MultiJarPlanner.Input> inputs,
            Collection<String> roots,
            Set<String> externalClasses,
            Map<String, Path> overlays) throws IOException {
        SortedMap<String, String> before = fingerprints(inputs);
        var plan = new MultiJarPlanner().plan(
                inputs, roots, externalClasses, overlays);
        verify(before);
        return new Prepared(plan, Collections.unmodifiableSortedMap(before));
    }

    public PlanJarStager.Result stage(Prepared prepared, Path output)
            throws IOException {
        Path destination = output.toAbsolutePath().normalize();
        for (String source : prepared.sourceSha256().keySet()) {
            Path input = Path.of(source);
            if (input.equals(destination)
                    || (Files.exists(destination)
                        && Files.isSameFile(input, destination))) {
                throw new IllegalArgumentException(
                        "Output must not overwrite any input JAR: " + source);
            }
        }

        verify(prepared.sourceSha256());

        Files.createDirectories(destination.getParent());
        Path candidate = Files.createTempFile(
                destination.getParent(), ".gold-pipeline-", ".jar");
        try {
            var staged = new PlanJarStager().stage(prepared.plan(), candidate);
            verify(prepared.sourceSha256());

            try {
                Files.move(candidate, destination,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(candidate, destination,
                        StandardCopyOption.REPLACE_EXISTING);
            }

            return new PlanJarStager.Result(destination, staged.classes());
        } finally {
            Files.deleteIfExists(candidate);
        }
    }

    public static void verify(Map<String, String> expected) throws IOException {
        for (var entry : expected.entrySet()) {
            Path source = Path.of(entry.getKey());
            if (!Files.isRegularFile(source)
                    || !entry.getValue().equals(sha256(source))) {
                throw new IOException(
                        "Source JAR changed after planning: " + source);
            }
        }
    }

    private static SortedMap<String, String> fingerprints(
            List<MultiJarPlanner.Input> inputs) throws IOException {
        SortedMap<String, String> hashes = new TreeMap<>();
        for (var input : inputs) {
            String path = input.jar().toString();
            if (hashes.putIfAbsent(path, sha256(input.jar())) != null) {
                throw new IllegalArgumentException(
                        "Input JAR supplied more than once: " + path);
            }
        }
        return hashes;
    }

    public static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var stream = Files.newInputStream(path)) {
                byte[] buffer = new byte[65536];
                int count;
                while ((count = stream.read(buffer)) != -1) {
                    digest.update(buffer, 0, count);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}