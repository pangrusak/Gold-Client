package com.goldclient.translator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import javax.tools.ToolProvider;
import static org.junit.jupiter.api.Assertions.*;
class ForgeOriginalAccessTransformTest {
    @TempDir Path temp;
    @Test void parsesOriginalDirectivesAndRejectsUnknownSyntax() throws Exception {
        var rules = ForgeOriginalAccessTransform.parse("# heading\npublic-f sample.Target value # note\nprotected sample.Target method()V\n");
        assertEquals(2, rules.size()); assertEquals("sample/Target", rules.get(0).owner());
        assertThrows(java.io.IOException.class, () -> ForgeOriginalAccessTransform.parse("unknown sample.Target"));
    }
    @Test void readsOnlyManifestDeclaredResource() throws Exception {
        Path jar = temp.resolve("mod.jar"); Manifest manifest = new Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        manifest.getMainAttributes().putValue("FMLAT", "example_at.cfg");
        try (var output = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            output.putNextEntry(new JarEntry("META-INF/example_at.cfg"));
            output.write("public example.Target\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)); output.closeEntry();
        }
        assertEquals("public example.Target\n\n", ForgeOriginalAccessTransform.readDeclaredRules(jar));
    }
    @Test void verifiesMemberNamespaceAndResultFlags() throws Exception {
        Path source = temp.resolve("Target.java"), classes = temp.resolve("classes"); Files.createDirectories(classes);
        Files.writeString(source, "package sample; public class Target { private final int value=1; private void method() {} }");
        var compiler = ToolProvider.getSystemJavaCompiler(); assertNotNull(compiler);
        assertEquals(0, compiler.run(null, null, null, "--release", "8", "-d", classes.toString(), source.toString()));
        var shape = ForgeOriginalAccessTransform.shape(Files.readAllBytes(classes.resolve("sample/Target.class")));
        var valid = ForgeOriginalAccessTransform.parse("public-f sample.Target value");
        ForgeOriginalAccessTransform.verifyTargets(shape, valid);
        assertThrows(java.io.IOException.class, () -> ForgeOriginalAccessTransform.verifyChanges(shape, valid));
        assertThrows(java.io.IOException.class, () -> ForgeOriginalAccessTransform.verifyTargets(shape,
                ForgeOriginalAccessTransform.parse("public sample.Target missing")));
    }
    @Test void refusesToOverwriteInput() throws Exception {
        Path jar = temp.resolve("input.jar");
        try (var output = new JarOutputStream(Files.newOutputStream(jar))) {}
        byte[] original = Files.readAllBytes(jar);
        assertThrows(java.io.IOException.class, () -> ForgeOriginalAccessTransform.apply(jar, jar, jar, List.of(jar)));
        assertArrayEquals(original, Files.readAllBytes(jar));
    }
}
