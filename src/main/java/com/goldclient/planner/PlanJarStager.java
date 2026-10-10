package com.goldclient.planner;

import org.objectweb.asm.ClassReader;

import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipFile;

/**
 * Writes selected class bytes unchanged, in class-name order.
 * Resources and service metadata are deliberately not staged.
 * Source JARs must remain unchanged between planning and staging.
 */
public final class PlanJarStager {
    public record StagedClass(
            String sourceJar, String sourceEntry, String sha256) {}

    public record Result(
            Path jar, SortedMap<String, StagedClass> classes) {}

    public Result stage(MultiJarPlanner.Plan plan, Path output) throws IOException {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(output, "output");

        if (plan.hasBlockingDiagnostics()) {
            throw new IllegalArgumentException(
                    "Cannot stage a plan with missing classes or unresolved duplicates");
        }
        if (plan.selected().isEmpty()) {
            throw new IllegalArgumentException("Cannot stage an empty selection");
        }

        Path destination = output.toAbsolutePath().normalize();
        for (var origin : plan.selected().values()) {
            Path source = Path.of(origin.jar()).toAbsolutePath().normalize();
            if (source.equals(destination)
                    || (Files.exists(destination)
                        && Files.isSameFile(source, destination))) {
                throw new IllegalArgumentException(
                        "Output must not overwrite an input JAR: " + source);
            }
        }

        Files.createDirectories(destination.getParent());
        Path temporary = Files.createTempFile(
                destination.getParent(), ".gold-stage-", ".jar");

        SortedMap<String, StagedClass> provenance = new TreeMap<>();

        try {
            try (JarOutputStream jar = new JarOutputStream(
                    Files.newOutputStream(temporary))) {
                for (var item : new TreeMap<>(plan.selected()).entrySet()) {
                    String name = item.getKey();
                    var origin = item.getValue();

                    if (origin.role() != MultiJarPlanner.Role.TRANSLATION
                            || !origin.entry().equals(name + ".class")) {
                        throw new IOException("Invalid selected origin: " + name);
                    }

                    byte[] bytes;
                    try (ZipFile source = new ZipFile(origin.jar())) {
                        var entry = source.getEntry(origin.entry());
                        if (entry == null || entry.isDirectory()) {
                            throw new IOException(
                                    "Selected source entry is missing: "
                                            + origin.jar() + "!" + origin.entry());
                        }
                        try (var stream = source.getInputStream(entry)) {
                            bytes = stream.readAllBytes();
                        }
                    }

                    try {
                        if (!new ClassReader(bytes).getClassName().equals(name)) {
                            throw new IOException(
                                    "Selected class name changed: " + name);
                        }
                    } catch (IllegalArgumentException exception) {
                        throw new IOException(
                                "Selected class is invalid: " + name, exception);
                    }

                    JarEntry entry = new JarEntry(name + ".class");
                    entry.setTimeLocal(LocalDateTime.of(1980, 1, 1, 0, 0));
                    jar.putNextEntry(entry);
                    jar.write(bytes);
                    jar.closeEntry();

                    provenance.put(name, new StagedClass(
                            origin.jar(), origin.entry(), sha256(bytes)));
                }
            }

            try {
                Files.move(temporary, destination,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, destination,
                        StandardCopyOption.REPLACE_EXISTING);
            }

            return new Result(destination,
                    Collections.unmodifiableSortedMap(provenance));
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}