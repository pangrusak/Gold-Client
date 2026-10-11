package com.goldclient.translator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import javax.tools.ToolProvider;
import static org.junit.jupiter.api.Assertions.*;
class VerifiedForgeAccessTest {
    @TempDir Path temp;
    private Path jar(String name, Map<String, byte[]> entries, boolean mod) throws Exception {
        Path file = temp.resolve(name);
        Manifest manifest = new Manifest(); manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        if (mod) manifest.getMainAttributes().putValue("FMLAT", "example_at.cfg");
        try (var output = new JarOutputStream(Files.newOutputStream(file), manifest)) {
            for (var entry : entries.entrySet()) {
                output.putNextEntry(new JarEntry(entry.getKey())); output.write(entry.getValue()); output.closeEntry();
            }
        }
        return file;
    }
    @Test void checksHashesFlagsAndClasspathWithoutClaimingEquivalence() throws Exception {
        Path classes = temp.resolve("classes"); Files.createDirectories(classes);
        Path sourceFile = temp.resolve("Target.java"); Files.writeString(sourceFile, "package sample; public class Target { public int value; }");
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "8", "-d", classes.toString(), sourceFile.toString()));
        Path mod = jar("mod.jar", Map.of("META-INF/example_at.cfg", "public sample.Target value\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)), true);
        Path original = jar("original.jar", Map.of("original.txt", new byte[]{1}), false);
        Path transformed = jar("transformed.jar", Map.of("sample/Target.class", Files.readAllBytes(classes.resolve("sample/Target.class"))), false);
        Properties proof = new Properties();
        proof.setProperty("engine", "net.minecraftforge.fml.common.asm.transformers.AccessTransformer");
        proof.setProperty("mod.sha256", VerifiedForgeAccess.sha256(mod));
        proof.setProperty("target.sha256", VerifiedForgeAccess.sha256(original));
        proof.setProperty("output.sha256", VerifiedForgeAccess.sha256(transformed));
        proof.setProperty("runtime.0.path", original.toString());
        proof.setProperty("runtime.0.sha256", VerifiedForgeAccess.sha256(original));
        proof.setProperty("rules.count", "1"); proof.setProperty("signature.policy", "strip-on-derived-output");
        Path sidecar = Path.of(transformed.toString() + ".provenance.properties");
        try (var output = Files.newOutputStream(sidecar)) { proof.store(output, "Synthetic test provenance; not an original engine run"); }
        VerifiedForgeAccess.verify(mod, transformed);
        VerifiedForgeAccess.verifyClasspath(mod, List.of(transformed));
        assertThrows(java.io.IOException.class, () -> VerifiedForgeAccess.verifyClasspath(mod, List.of(original, transformed)));
        Files.write(transformed, new byte[]{0}, StandardOpenOption.APPEND);
        assertThrows(java.io.IOException.class, () -> VerifiedForgeAccess.verify(mod, transformed));
    }
    @Test void rejectsMissingProvenance() throws Exception {
        Path mod = jar("mod.jar", Map.of(), false);
        Path transformed = jar("output.jar", Map.of(), false);
        assertThrows(java.io.IOException.class, () -> VerifiedForgeAccess.verify(mod, transformed));
    }
}
