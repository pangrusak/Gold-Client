package com.goldclient.translator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class ForgeMultiModPlanTest {
    @TempDir
    Path tempDir;

    @Test
    void discoversMcmodInfoOnlyDependencyAndUsesItToSatisfyRequiredDependency() throws Exception {
        Path mixinBooter = createJar("mixinbooter.jar",
                "[{\"modid\":\"mixinbooter\",\"name\":\"MixinBooter\",\"version\":\"9.1\"}]");

        var discovered = ForgeMultiModPlan.discover(List.of(mixinBooter));

        assertEquals(1, discovered.size());
        assertEquals("mixinbooter", discovered.get(0).mod().id());
        assertEquals("9.1", discovered.get(0).mod().version());
        assertEquals("mcmod.info (metadata-only)", discovered.get(0).metadataSource());

        var plan = ForgeDependencyGraph.plan(List.of(
                new ForgeDependencyGraph.Mod("universaltweaks", "1.12.2", "required-after:mixinbooter"),
                discovered.get(0).mod()));

        assertTrue(plan.ready(), () -> String.join("\n", plan.blockers()));
        assertEquals(List.of("mixinbooter", "universaltweaks"),
                plan.ordered().stream().map(ForgeDependencyGraph.Mod::id).toList());
    }

    @Test
    void rejectsJarWithNeitherModAnnotationNorUsableMcmodInfo() throws Exception {
        Path library = createJar("plain-library.jar", null);
        IOException error = assertThrows(IOException.class,
                () -> ForgeMultiModPlan.discover(List.of(library)));
        assertTrue(error.getMessage().contains("No Forge @Mod class or usable mcmod.info mod ID found"));
    }

    private Path createJar(String name, String mcmodInfo) throws IOException {
        Path path = tempDir.resolve(name);
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(path))) {
            if (mcmodInfo != null) {
                output.putNextEntry(new JarEntry("mcmod.info"));
                output.write(mcmodInfo.getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
        return path;
    }
}
