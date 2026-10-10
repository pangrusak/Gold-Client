package com.goldclient.translator;

import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.jar.*;
import java.util.zip.*;

/** Invokes original Forge bytecode; this does not implement a substitute AT engine. */
public final class ForgeOriginalAccessTransform {
    private static final String ENGINE = "net.minecraftforge.fml.common.asm.transformers.AccessTransformer";
    public record Result(int rules, int changedClasses, String sourceSha256,
                         String targetSha256, String outputSha256) {}
    record Rule(String access, String owner, String member) {}
    record Shape(String owner, int access, Map<String, Integer> fields, Map<String, Integer> methods) {}

    public static void main(String[] args) throws Exception {
        if (args.length < 4) throw new IllegalArgumentException(
                "Usage: ForgeOriginalAccessTransform <mod.jar> <target.jar> <output.jar> <runtime.jar>...");
        List<Path> runtime = new ArrayList<>();
        for (int i = 3; i < args.length; i++) runtime.add(Path.of(args[i]));
        Result result = apply(Path.of(args[0]), Path.of(args[1]), Path.of(args[2]), runtime);
        System.out.println("Original Forge AT execution completed: " + result);
        System.out.println("This transformed JVM class bytes, not the already-generated client JavaScript.");
    }

    public static Result apply(Path modJar, Path targetJar, Path destination,
                               List<Path> runtimeJars) throws Exception {
        // GOLD_FORGE_UNSIGNED_DERIVATIVE_V1
        boolean allowUnsignedOutput = Boolean.getBoolean("gold.forge.at.allowUnsignedOutput");
        List<String> removedSignatures = new ArrayList<>();
        boolean manifestRewritten = false;
        Path output = destination.toAbsolutePath().normalize();
        Path mod = modJar.toRealPath();
        Path target = targetJar.toRealPath();
        if (output.equals(mod) || output.equals(target) ||
                (Files.exists(output) && (Files.isSameFile(output, mod) || Files.isSameFile(output, target)))) {
            throw new IOException("Output must not overwrite either original input JAR");
        }
        if (runtimeJars == null || runtimeJars.isEmpty()) throw new IOException("Original Forge runtime classpath is required");
        List<Path> runtime = new ArrayList<>();
        List<URL> urls = new ArrayList<>();
        for (Path path : runtimeJars) {
            Path real = path.toRealPath();
            if (!runtime.contains(real)) {
                if (output.equals(real)) throw new IOException("Output must not overwrite a runtime dependency");
                try (JarFile archive = new JarFile(real.toFile(), false)) { archive.size(); }
                runtime.add(real); urls.add(real.toUri().toURL());
            }
        }
        String rulesText = readDeclaredRules(mod);
        List<Rule> rules = parse(rulesText);
        if (rules.isEmpty()) throw new IOException("No declared access-transformer rules found");
        Map<String, List<Rule>> owners = new LinkedHashMap<>();
        for (Rule rule : rules) owners.computeIfAbsent(rule.owner(), ignored -> new ArrayList<>()).add(rule);
        String modHash = sha256(mod), targetHash = sha256(target);
        Files.createDirectories(output.getParent());
        Path report = Path.of(output.toString() + ".provenance.properties");
        Files.deleteIfExists(output); Files.deleteIfExists(report);
        Path staged = Files.createTempFile(output.getParent(), "forge-at-", ".jar");
        int changed = 0;
        boolean complete = false;
        Set<String> foundOwners = new HashSet<>();
        try (URLClassLoader loader = new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader())) {
            Class<?> transformerType = loader.loadClass(ENGINE);
            // Forge's package-private constructor is the empty transformer used by its subclasses.
            Constructor<?> constructor = transformerType.getDeclaredConstructor(Class.class);
            constructor.setAccessible(true);
            Object transformer = constructor.newInstance(transformerType);
            Class<?> charSourceType = loader.loadClass("com.google.common.io.CharSource");
            Object charSource = charSourceType.getMethod("wrap", CharSequence.class).invoke(null, rulesText);
            Method process = transformerType.getDeclaredMethod("processATFile", charSourceType);
            process.setAccessible(true); process.invoke(transformer, charSource);
            Method transform = transformerType.getMethod("transform", String.class, String.class, byte[].class);
            try (JarFile input = new JarFile(target.toFile(), false);
                 JarOutputStream result = new JarOutputStream(Files.newOutputStream(staged))) {
                List<JarEntry> entries = Collections.list(input.entries());
                entries.sort(Comparator.comparing(JarEntry::getName));
                for (JarEntry entry : entries) {
                    String name = entry.getName();
                    if (signatureEntry(name)) {
                        if (!allowUnsignedOutput) {
                            throw new IOException("Signed dependency requires an explicit signature policy; refusing: " + name);
                        }
                        removedSignatures.add(name);
                        continue;
                    }
                    byte[] original;
                    try (InputStream stream = input.getInputStream(entry)) { original = stream.readAllBytes(); }
                    byte[] bytes = original;
                    if (allowUnsignedOutput && name.equalsIgnoreCase("META-INF/MANIFEST.MF")) {
                        bytes = unsignedManifest(original);
                        manifestRewritten = !Arrays.equals(original, bytes);
                    }
                    if (!entry.isDirectory() && name.endsWith(".class")) {
                        Shape before = shape(original);
                        List<Rule> applicable = owners.get(before.owner());
                        if (applicable != null) {
                            foundOwners.add(before.owner());
                            verifyTargets(before, applicable);
                        }
                        String className = before.owner().replace('/', '.');
                        bytes = (byte[]) transform.invoke(transformer, className, className, original);
                        if (bytes == null) throw new IOException("Original transformer returned null for " + name);
                        if (applicable != null) verifyChanges(shape(bytes), applicable);
                        if (!Arrays.equals(original, bytes)) changed++;
                    }
                    result.putNextEntry(new JarEntry(name));
                    result.write(bytes); result.closeEntry();
                }
            }
            Set<String> unresolved = new TreeSet<>(owners.keySet()); unresolved.removeAll(foundOwners);
            if (!unresolved.isEmpty()) throw new IOException("AT classes absent from target JAR; remapping or another target is required: " + unresolved);
            if (!modHash.equals(sha256(mod)) || !targetHash.equals(sha256(target))) throw new IOException("Original input changed during transformation");
            Files.move(staged, output, StandardCopyOption.REPLACE_EXISTING);
            Properties provenance = new Properties();
            provenance.setProperty("engine", ENGINE);
            provenance.setProperty("signature.policy", allowUnsignedOutput ? "strip-on-derived-output" : "reject-signed-input");
            provenance.setProperty("signature.removedEntries", String.join(",", removedSignatures));
            provenance.setProperty("signature.manifestRewritten", Boolean.toString(manifestRewritten));
            provenance.setProperty("signature.originalVerificationPerformed", "false");
            provenance.setProperty("signature.outputAuthenticityVerified", "false");
            provenance.setProperty("mod.sha256", modHash);
            provenance.setProperty("target.sha256", targetHash);
            provenance.setProperty("output.sha256", sha256(output));
            provenance.setProperty("rules.count", Integer.toString(rules.size()));
            provenance.setProperty("changedClasses", Integer.toString(changed));
            provenance.setProperty("clientJavaScriptTransformed", "false");
            provenance.setProperty("fullModEquivalenceVerified", "false");
            for (int i = 0; i < runtime.size(); i++) {
                provenance.setProperty("runtime." + i + ".path", runtime.get(i).toString());
                provenance.setProperty("runtime." + i + ".sha256", sha256(runtime.get(i)));
            }
            try (var stream = Files.newOutputStream(report)) { provenance.store(stream, "Original Forge AT execution provenance"); }
            complete = true;
            return new Result(rules.size(), changed, modHash, targetHash, sha256(output));
        } finally {
            Files.deleteIfExists(staged);
            if (!complete) { Files.deleteIfExists(output); Files.deleteIfExists(report); }
        }
    }

    static boolean signatureEntry(String name) {
        return name.matches("(?i)META-INF/([^/]+\\.(SF|RSA|DSA|EC)|SIG-[^/]+)");
    }
    static byte[] unsignedManifest(byte[] original) throws IOException {
        Manifest manifest;
        try (var stream = new ByteArrayInputStream(original)) { manifest = new Manifest(stream); }
        removeSigningAttributes(manifest.getMainAttributes());
        for (var attributes : manifest.getEntries().values()) removeSigningAttributes(attributes);
        manifest.getEntries().entrySet().removeIf(entry -> entry.getValue().isEmpty());
        try (var output = new ByteArrayOutputStream()) {
            manifest.write(output); return output.toByteArray();
        }
    }
    private static void removeSigningAttributes(Attributes attributes) {
        attributes.keySet().removeIf(key -> {
            String name = key.toString().toLowerCase(Locale.ROOT);
            return name.equals("signature-version") || name.equals("magic") ||
                    name.equals("digest-algorithms") || name.endsWith("-digest") || name.contains("-digest-");
        });
    }

    static String readDeclaredRules(Path mod) throws IOException {
        try (JarFile jar = new JarFile(mod.toFile(), false)) {
            Manifest manifest = jar.getManifest();
            String declaration = manifest == null ? null : manifest.getMainAttributes().getValue("FMLAT");
            if (declaration == null || declaration.isBlank()) return "";
            StringBuilder all = new StringBuilder();
            for (String file : declaration.trim().split("\\s+")) {
                if (file.contains("..") || file.contains("/") || file.contains("\\")) throw new IOException("Unsupported FMLAT resource name: " + file);
                JarEntry entry = jar.getJarEntry("META-INF/" + file);
                if (entry == null) throw new IOException("Declared AT resource missing: META-INF/" + file);
                try (var stream = jar.getInputStream(entry)) {
                    all.append(new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)).append('\n');
                }
            }
            return all.toString();
        }
    }
    static List<Rule> parse(String text) throws IOException {
        List<Rule> rules = new ArrayList<>();
        for (String line : text.split("\\R")) {
            String clean = line.split("#", 2)[0].trim(); if (clean.isEmpty()) continue;
            String[] parts = clean.split("\\s+");
            if (parts.length < 2 || parts.length > 3 || !parts[0].matches("(public|protected|private|default)([+-]f)?")) {
                throw new IOException("Unsupported AT directive; original rules are not being rewritten: " + line);
            }
            rules.add(new Rule(parts[0], parts[1].replace('.', '/'), parts.length == 3 ? parts[2] : ""));
        }
        return rules;
    }
    static void verifyTargets(Shape shape, List<Rule> rules) throws IOException {
        for (Rule rule : rules) {
            if (rule.member().isEmpty()) continue;
            Map<String, Integer> members = rule.member().contains("(") ? shape.methods() : shape.fields();
            if (!rule.member().equals("*") && !rule.member().equals("*()") && !members.containsKey(rule.member())) {
                throw new IOException("AT member absent (possible namespace mismatch): " + rule.owner() + " " + rule.member());
            }
        }
    }
    static void verifyChanges(Shape shape, List<Rule> rules) throws IOException {
        for (Rule rule : rules) {
            List<Integer> flags;
            if (rule.member().isEmpty()) flags = List.of(shape.access());
            else {
                Map<String, Integer> members = rule.member().contains("(") ? shape.methods() : shape.fields();
                flags = rule.member().equals("*") || rule.member().equals("*()") ? new ArrayList<>(members.values()) : List.of(members.get(rule.member()));
            }
            int requested = switch (rule.access().split("[+-]", 2)[0]) {
                case "public" -> 3; case "protected" -> 2; case "default" -> 1; default -> 0;
            };
            for (int access : flags) {
                int actual = (access & 1) != 0 ? 3 : (access & 4) != 0 ? 2 : (access & 2) != 0 ? 0 : 1;
                if (actual < requested || (rule.access().endsWith("-f") && (access & 16) != 0) ||
                        (rule.access().endsWith("+f") && (access & 16) == 0)) {
                    throw new IOException("Original AT result did not satisfy directive: " + rule);
                }
            }
        }
    }
    static Shape shape(byte[] bytes) throws IOException {
        try (var stream = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (stream.readInt() != 0xCAFEBABE) throw new IOException("Invalid class magic");
            stream.readUnsignedShort(); stream.readUnsignedShort(); Object[] pool = new Object[stream.readUnsignedShort()];
            for (int i = 1; i < pool.length; i++) {
                switch (stream.readUnsignedByte()) {
                    case 1 -> pool[i] = stream.readUTF();
                    case 3, 4 -> stream.readInt();
                    case 5, 6 -> { stream.readLong(); i++; }
                    case 7, 8, 16, 19, 20 -> pool[i] = stream.readUnsignedShort();
                    case 9, 10, 11, 12, 17, 18 -> { stream.readUnsignedShort(); stream.readUnsignedShort(); }
                    case 15 -> { stream.readUnsignedByte(); stream.readUnsignedShort(); }
                    default -> throw new IOException("Unsupported class constant");
                }
            }
            int access = stream.readUnsignedShort();
            String owner = (String) pool[(Integer) pool[stream.readUnsignedShort()]];
            stream.readUnsignedShort(); int interfaces = stream.readUnsignedShort();
            for (int i = 0; i < interfaces; i++) stream.readUnsignedShort();
            Map<String, Integer> fields = members(stream, pool, false);
            Map<String, Integer> methods = members(stream, pool, true);
            return new Shape(owner, access, fields, methods);
        }
    }
    private static Map<String, Integer> members(DataInputStream stream, Object[] pool, boolean methods) throws IOException {
        Map<String, Integer> result = new LinkedHashMap<>(); int count = stream.readUnsignedShort();
        for (int i = 0; i < count; i++) {
            int access = stream.readUnsignedShort(); String name = (String) pool[stream.readUnsignedShort()];
            String descriptor = (String) pool[stream.readUnsignedShort()]; result.put(name + (methods ? descriptor : ""), access);
            int attributes = stream.readUnsignedShort();
            for (int j = 0; j < attributes; j++) { stream.readUnsignedShort(); stream.skipNBytes(Integer.toUnsignedLong(stream.readInt())); }
        }
        return result;
    }
    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream stream = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192]; int count;
                while ((count = stream.read(buffer)) != -1) digest.update(buffer, 0, count);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException error) { throw new IOException(error); }
    }
}
