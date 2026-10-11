package com.goldclient.translator;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.jar.*;

/** Local artifact consistency checks, not publisher authenticity or whole-mod equivalence. */
public final class VerifiedForgeAccess {
    public static void verify(Path modJar, Path transformedJar) throws IOException {
        Path output = transformedJar.toRealPath();
        Path sidecar = Path.of(output.toString() + ".provenance.properties");
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(sidecar)) { properties.load(input); }
        if (!"net.minecraftforge.fml.common.asm.transformers.AccessTransformer".equals(properties.getProperty("engine"))) {
            throw new IOException("Unexpected access-transformer engine");
        }
        checkHash(modJar, properties.getProperty("mod.sha256"), "Original mod");
        checkHash(output, properties.getProperty("output.sha256"), "Transformed dependency");
        String targetHash = properties.getProperty("target.sha256");
        if (targetHash == null || !targetHash.matches("[0-9a-fA-F]{64}")) throw new IOException("Target hash missing or invalid");
        boolean targetFound = false;
        int runtimeCount = 0;
        for (String key : properties.stringPropertyNames()) {
            if (!key.matches("runtime\\.[0-9]+\\.path")) continue;
            Path runtime = Path.of(properties.getProperty(key));
            String hash = properties.getProperty(key.replace(".path", ".sha256"));
            checkHash(runtime, hash, "Transformation runtime");
            if (targetHash.equalsIgnoreCase(hash)) targetFound = true;
            runtimeCount++;
        }
        if (!targetFound || runtimeCount == 0) throw new IOException("Original target/runtime provenance is incomplete");
        List<ForgeOriginalAccessTransform.Rule> rules = ForgeOriginalAccessTransform.parse(
                ForgeOriginalAccessTransform.readDeclaredRules(modJar));
        if (rules.isEmpty()) throw new IOException("Original mod has no declared AT rules");
        if (!Integer.toString(rules.size()).equals(properties.getProperty("rules.count"))) throw new IOException("AT rule-count provenance mismatch");
        Map<String, List<ForgeOriginalAccessTransform.Rule>> owners = new LinkedHashMap<>();
        for (var rule : rules) owners.computeIfAbsent(rule.owner(), ignored -> new ArrayList<>()).add(rule);
        try (JarFile jar = new JarFile(output.toFile(), false)) {
            for (var owner : owners.entrySet()) {
                JarEntry entry = jar.getJarEntry(owner.getKey() + ".class");
                if (entry == null) throw new IOException("Transformed AT target missing: " + owner.getKey());
                byte[] bytes;
                try (var stream = jar.getInputStream(entry)) { bytes = stream.readAllBytes(); }
                var shape = ForgeOriginalAccessTransform.shape(bytes);
                ForgeOriginalAccessTransform.verifyTargets(shape, owner.getValue());
                ForgeOriginalAccessTransform.verifyChanges(shape, owner.getValue());
            }
            boolean stripped = "strip-on-derived-output".equals(properties.getProperty("signature.policy"));
            for (JarEntry entry : Collections.list(jar.entries())) {
                if (stripped && ForgeOriginalAccessTransform.signatureEntry(entry.getName())) {
                    throw new IOException("Derived output retains signature metadata: " + entry.getName());
                }
            }
        }
    }
    static void verifyClasspath(Path input, List<Path> dependencies) throws IOException {
        if (input == null || dependencies == null) throw new IOException("Input and dependencies are required");
        List<Path> classpath = new ArrayList<>(); classpath.add(input); classpath.addAll(dependencies);
        for (Path candidate : classpath) {
            if (candidate == null) throw new IOException("Null classpath entry");
            Path sidecar = Path.of(candidate.toAbsolutePath().normalize().toString() + ".provenance.properties");
            if (!Files.exists(sidecar)) continue;
            Properties properties = new Properties();
            try (var stream = Files.newInputStream(sidecar)) { properties.load(stream); }
            String modHash = properties.getProperty("mod.sha256");
            Path originalMod = null;
            for (Path supplied : classpath) {
                String hash = sha256(supplied);
                if (hash.equalsIgnoreCase(Objects.toString(modHash, ""))) originalMod = supplied;
                if (hash.equalsIgnoreCase(Objects.toString(properties.getProperty("target.sha256"), "")) &&
                        !supplied.toRealPath().equals(candidate.toRealPath())) {
                    throw new IOException("Classpath supplies original and transformed target together: " + supplied);
                }
            }
            if (originalMod == null) throw new IOException("Original mod for transformed dependency is not in the classpath: " + candidate);
            verify(originalMod, candidate);
            System.out.println("[verified-forge-at] " + candidate.toRealPath());
        }
    }
    private static void checkHash(Path file, String expected, String label) throws IOException {
        if (expected == null || !expected.matches("[0-9a-fA-F]{64}") || !sha256(file).equalsIgnoreCase(expected)) {
            throw new IOException(label + " hash mismatch or missing provenance: " + file);
        }
    }
    static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var stream = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192]; int count;
                while ((count = stream.read(buffer)) != -1) digest.update(buffer, 0, count);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) { throw new IOException(exception); }
    }
}
