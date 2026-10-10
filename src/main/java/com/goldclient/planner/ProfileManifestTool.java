package com.goldclient.planner;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.objectweb.asm.ClassReader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.jar.*;
import java.util.zip.*;

/**
 * Migrates a verified class selection into explicit, pinned source records.
 * Assembly subsequently uses only those records, not the reference selection.
 * Resources are deliberately excluded.
 */
public final class ProfileManifestTool {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();

    public record Source(
            String path, boolean directory, String sha256,
            boolean generatedArchive) {
        public Source(String path, boolean directory, String sha256) {
            this(path, directory, sha256, false);
        }
    }
    public record Candidate(int source, String entry) {}
    public record Selection(
            int source, String entry, String sha256,
            List<Candidate> byteIdenticalCandidates) {}
    public record Manifest(
            int schemaVersion, String scope, String referenceSha256,
            List<Source> sources, SortedMap<String, Selection> classes) {}

    private ProfileManifestTool() {}

    public static void main(String[] args) {
        try {
            if (args.length >= 4 && args[0].equals("capture")) {
                Path manifest = Path.of(args[1]);
                Path reference = Path.of(args[2]);
                List<Path> sources = Arrays.stream(args)
                        .skip(3).map(Path::of).toList();
                Manifest result = capture(manifest, reference, sources);
                System.out.println("Captured explicit class origins: " + result.classes().size());
                System.out.println("Pinned source locations: " + result.sources().size());
                System.out.println("Manifest: " + manifest.toAbsolutePath());
            } else if (args.length == 3 && args[0].equals("assemble")) {
                int count = assemble(Path.of(args[1]), Path.of(args[2]));
                System.out.println("Assembled pinned classes: " + count);
                System.out.println("Output: " + Path.of(args[2]).toAbsolutePath());
            } else {
                System.err.println("""
                        Usage:
                          ProfileManifestTool capture <manifest.json> <verified-selection.jar>
                            <original-or-dependency.jar|compatibility-directory>...
                          ProfileManifestTool assemble <manifest.json> <output.jar>

                        Capture is a one-time migration from a verified selection.
                        Assembly does not read the verified selection.
                        Source paths are local absolute paths; manifests are not portable yet.
                        """);
                System.exit(2);
            }
        } catch (Exception exception) {
            System.err.println("Profile operation failed: " + exception.getMessage());
            System.exit(2);
        }
    }

    public static Manifest capture(
            Path manifestPath, Path referencePath, List<Path> sourcePaths)
            throws IOException {
        Path manifest = absolute(manifestPath);
        Path reference = absolute(referencePath);
        if (sourcePaths.isEmpty()) {
            throw new IllegalArgumentException("At least one source is required");
        }

        List<Source> sources = new ArrayList<>();
        List<ZipFile> archives = new ArrayList<>();
        Set<Path> unique = new HashSet<>();
        String referenceHash = TranslationPipeline.sha256(reference);

        try {
            for (Path supplied : sourcePaths) {
                Path source = absolute(supplied);
                if (same(source, reference) || same(source, manifest)) {
                    throw new IllegalArgumentException(
                            "Source must differ from reference and manifest: " + source);
                }
                if (!unique.add(source)) {
                    throw new IllegalArgumentException("Repeated source: " + source);
                }

                boolean directory = Files.isDirectory(source);
                if (!directory && !Files.isRegularFile(source)) {
                    throw new IOException("Source not found: " + source);
                }
                if (Files.exists(manifest) && directory && manifest.startsWith(source)) {
                    throw new IllegalArgumentException(
                            "Manifest must not be inside a compatibility source directory");
                }

                sources.add(new Source(source.toString(), directory,
                        directory ? null : TranslationPipeline.sha256(source)));
                archives.add(directory ? null : new ZipFile(source.toFile()));
            }

            SortedMap<String, Selection> selections = new TreeMap<>();
            try (ZipFile zip = new ZipFile(reference.toFile())) {
                List<? extends ZipEntry> entries = Collections.list(zip.entries()).stream()
                        .filter(entry -> !entry.isDirectory()
                                && entry.getName().endsWith(".class"))
                        .sorted(Comparator.comparing(ZipEntry::getName))
                        .toList();

                for (ZipEntry entry : entries) {
                    String name = entry.getName();
                    validateEntry(name);
                    byte[] expected;
                    try (var stream = zip.getInputStream(entry)) {
                        expected = stream.readAllBytes();
                    }
                    validateClass(name, expected);
                    String hash = hash(expected);
                    List<Candidate> candidates = new ArrayList<>();

                    for (int i = 0; i < sources.size(); i++) {
                        byte[] bytes = readIfPresent(sources.get(i), archives.get(i), name);
                        if (bytes != null && Arrays.equals(expected, bytes)) {
                            candidates.add(new Candidate(i, name));
                        }
                    }

                    if (candidates.isEmpty()) {
                        throw new IOException(
                                "No byte-identical original/compatibility source for " + name);
                    }

                    // Migration authority is the verified reference bytes.
                    // Multiple matching candidates are recorded, not hidden.
                    Candidate chosen = candidates.get(0);
                    Selection selection = new Selection(
                            chosen.source(), chosen.entry(), hash, List.copyOf(candidates));
                    if (selections.putIfAbsent(name, selection) != null) {
                        throw new IOException("Duplicate reference class entry: " + name);
                    }
                }
            }

            if (selections.isEmpty()) throw new IOException("Reference contains no classes");
            verifyArchives(sources);
            if (!referenceHash.equals(TranslationPipeline.sha256(reference))) {
                throw new IOException("Reference changed during capture");
            }

            Manifest result = new Manifest(
                    1,
                    "Pinned class-only profile migrated from verified selection; "
                            + "not automatic compatibility discovery",
                    referenceHash, List.copyOf(sources), selections);

            writeAtomic(manifest, JSON.toJson(result).getBytes(StandardCharsets.UTF_8));
            return result;
        } finally {
            closeArchives(archives);
        }
    }

    public static int assemble(Path manifestPath, Path outputPath) throws IOException {
        Path manifestFile = absolute(manifestPath);
        Path output = absolute(outputPath);
        if (same(manifestFile, output)) {
            throw new IllegalArgumentException("Output must not overwrite manifest");
        }

        Manifest manifest = JSON.fromJson(
                Files.readString(manifestFile, StandardCharsets.UTF_8), Manifest.class);
        if (manifest == null || manifest.schemaVersion() != 1
                || manifest.sources() == null || manifest.classes() == null
                || manifest.classes().isEmpty()) {
            throw new IOException("Invalid or unsupported profile manifest");
        }

        for (Source source : manifest.sources()) {
            Path input = Path.of(source.path());
            if ((!source.directory() && same(input, output))
                    || (source.directory() && output.startsWith(absolute(input)))) {
                throw new IllegalArgumentException("Output would overwrite a source");
            }
        }

        verifyArchives(manifest.sources());
        Files.createDirectories(output.getParent());
        Path temporary = Files.createTempFile(output.getParent(), ".gold-profile-", ".jar");
        List<ZipFile> archives = new ArrayList<>();

        try {
            for (Source source : manifest.sources()) {
                archives.add(source.directory() ? null : new ZipFile(source.path()));
            }

            try (JarOutputStream jar = new JarOutputStream(
                    Files.newOutputStream(temporary))) {
                for (var item : new TreeMap<>(manifest.classes()).entrySet()) {
                    String name = item.getKey();
                    Selection selection = item.getValue();
                    validateEntry(name);

                    if (selection == null || !name.equals(selection.entry())
                            || selection.source() < 0
                            || selection.source() >= manifest.sources().size()) {
                        throw new IOException("Invalid class selection: " + name);
                    }

                    Source source = manifest.sources().get(selection.source());
                    byte[] bytes = readIfPresent(
                            source, archives.get(selection.source()), selection.entry());
                    if (bytes == null || !hash(bytes).equals(selection.sha256())) {
                        throw new IOException("Pinned class missing or changed: " + name);
                    }
                    validateClass(name, bytes);

                    JarEntry entry = new JarEntry(name);
                    entry.setTimeLocal(LocalDateTime.of(1980, 1, 1, 0, 0));
                    jar.putNextEntry(entry);
                    jar.write(bytes);
                    jar.closeEntry();
                }
            }

            verifyArchives(manifest.sources());
            moveAtomic(temporary, output);
            return manifest.classes().size();
        } finally {
            closeArchives(archives);
            Files.deleteIfExists(temporary);
        }
    }

    private static byte[] readIfPresent(Source source, ZipFile zip, String entry)
            throws IOException {
        if (source.directory()) {
            Path root = absolute(Path.of(source.path()));
            Path file = root.resolve(entry).normalize();
            if (!file.startsWith(root)) throw new IOException("Unsafe source entry");
            if (!Files.isRegularFile(file)) return null;
            Path realRoot = root.toRealPath();
            if (!file.toRealPath().startsWith(realRoot)) {
                throw new IOException("Compatibility symlink escapes source directory");
            }
            return Files.readAllBytes(file);
        }
        ZipEntry item = zip.getEntry(entry);
        if (item == null || item.isDirectory()) return null;
        try (var stream = zip.getInputStream(item)) {
            return stream.readAllBytes();
        }
    }

    private static void verifyArchives(List<Source> sources) throws IOException {
        for (Source source : sources) {
            Path file = Path.of(source.path());
            if (source.directory()) {
                if (!Files.isDirectory(file)) {
                    throw new IOException("Compatibility directory missing: " + file);
                }
            } else {
                if (!Files.isRegularFile(file)) {
                    throw new IOException("Source JAR missing: " + file);
                }
                if (!source.generatedArchive()
                        && (source.sha256() == null
                            || !source.sha256().equals(
                                    TranslationPipeline.sha256(file)))) {
                    throw new IOException("Pinned source JAR changed: " + file);
                }
                // Generated archives are checked by the selected-class hashes
                // during assembly. Their container metadata is not pinned.
            }
        }
    }

    private static void validateEntry(String entry) throws IOException {
        if (entry == null || !entry.endsWith(".class") || entry.startsWith("/")
                || entry.contains("\\") || entry.contains(":")
                || Arrays.asList(entry.split("/", -1)).stream()
                    .anyMatch(part -> part.isEmpty() || part.equals(".") || part.equals(".."))
                || entry.startsWith("META-INF/")) {
            throw new IOException("Unsafe or unsupported class entry: " + entry);
        }
    }

    private static void validateClass(String entry, byte[] bytes) throws IOException {
        try {
            if (!entry.equals(new ClassReader(bytes).getClassName() + ".class")) {
                throw new IOException("Class name/entry mismatch: " + entry);
            }
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid class: " + entry, exception);
        }
    }

    private static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static Path absolute(Path path) {
        return path.toAbsolutePath().normalize();
    }

    private static boolean same(Path first, Path second) throws IOException {
        first = absolute(first);
        second = absolute(second);
        return first.equals(second)
                || (Files.exists(first) && Files.exists(second)
                    && Files.isSameFile(first, second));
    }

    private static void writeAtomic(Path destination, byte[] bytes) throws IOException {
        Files.createDirectories(destination.getParent());
        Path temporary = Files.createTempFile(
                destination.getParent(), ".gold-manifest-", ".json");
        try {
            Files.write(temporary, bytes);
            moveAtomic(temporary, destination);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void moveAtomic(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void closeArchives(List<ZipFile> archives) throws IOException {
        IOException failure = null;
        for (ZipFile archive : archives) {
            if (archive == null) continue;
            try { archive.close(); }
            catch (IOException exception) {
                if (failure == null) failure = exception;
                else failure.addSuppressed(exception);
            }
        }
        if (failure != null) throw failure;
    }
}