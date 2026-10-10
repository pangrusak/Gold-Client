package com.goldclient.planner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static com.goldclient.planner.MultiJarPlanner.Role.*;

class MultiJarPlannerTest {
    @TempDir Path directory;

    private Path jar(String filename, Map<String, String> classes) throws Exception {
        Path result = directory.resolve(filename);
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(result))) {
            for (var entry : new TreeMap<>(classes).entrySet()) {
                ClassWriter writer = new ClassWriter(0);
                writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, entry.getKey(),
                        null, "java/lang/Object", null);
                if (!entry.getValue().isEmpty()) {
                    writer.visitField(Opcodes.ACC_PUBLIC, "dependency",
                            "L" + entry.getValue() + ";", null, null).visitEnd();
                }
                writer.visitEnd();
                JarEntry item = new JarEntry(entry.getKey() + ".class");
                item.setTime(0);
                out.putNextEntry(item);
                out.write(writer.toByteArray());
                out.closeEntry();
            }
        }
        return result;
    }

    private MultiJarPlanner.Input input(Path path, MultiJarPlanner.Role role) {
        return new MultiJarPlanner.Input(path, role);
    }

    @Test
    void followsCrossJarDependenciesAndHandlesCycles() throws Exception {
        Path a = jar("a.jar", Map.of("demo/A", "demo/B"));
        Path b = jar("b.jar", Map.of("demo/B", "demo/A", "demo/Unused", ""));
        var plan = new MultiJarPlanner().plan(
                List.of(input(a, TRANSLATION), input(b, TRANSLATION)),
                List.of("demo.A"), Set.of("java.lang.Object"));

        assertEquals(Set.of("demo/A", "demo/B"), plan.selected().keySet());
        assertFalse(plan.hasBlockingDiagnostics());
        assertEquals(a.toAbsolutePath().toString(), plan.selected().get("demo/A").jar());
        assertEquals(b.toAbsolutePath().toString(), plan.selected().get("demo/B").jar());
    }

    @Test
    void runtimeInputResolvesButDoesNotExpandDependencies() throws Exception {
        Path app = jar("app.jar", Map.of("demo/App", "runtime/Boundary"));
        Path runtime = jar("runtime.jar", Map.of("runtime/Boundary", "unavailable/Backend"));
        var plan = new MultiJarPlanner().plan(
                List.of(input(app, TRANSLATION), input(runtime, RUNTIME)),
                List.of("demo/App"), Set.of("java/lang/Object"));

        assertEquals(Set.of("demo/App"), plan.selected().keySet());
        assertTrue(plan.runtimeClasses().contains("runtime/Boundary"));
        assertTrue(plan.missing().isEmpty());
    }

    @Test
    void reportsMissingClassesWithReferrers() throws Exception {
        Path app = jar("missing.jar", Map.of("demo/App", "missing/Dependency"));
        var plan = new MultiJarPlanner().plan(
                List.of(input(app, TRANSLATION)),
                List.of("demo/App"), Set.of("java/lang/Object"));

        assertEquals(Set.of("demo/App"), plan.missing().get("missing/Dependency"));
        assertTrue(plan.hasBlockingDiagnostics());
    }

    @Test
    void duplicatesAreNotSilentlyResolvedByInputOrder() throws Exception {
        Path first = jar("first.jar", Map.of("demo/Duplicate", ""));
        Path second = jar("second.jar", Map.of("demo/Duplicate", ""));
        var planner = new MultiJarPlanner();
        var forward = planner.plan(
                List.of(input(first, TRANSLATION), input(second, TRANSLATION)),
                List.of("demo/Duplicate"), Set.of("java/lang/Object"));
        var reverse = planner.plan(
                List.of(input(second, TRANSLATION), input(first, TRANSLATION)),
                List.of("demo/Duplicate"), Set.of("java/lang/Object"));

        assertEquals(forward, reverse);
        assertEquals(2, forward.duplicates().get("demo/Duplicate").size());
        assertTrue(forward.selected().isEmpty());
        assertTrue(forward.hasBlockingDiagnostics());
    }

    @Test
    void reportsMissingRootAndReturnsImmutableCollections() throws Exception {
        var plan = new MultiJarPlanner().plan(
                List.of(), List.of("absent.Entry"), Set.of());

        assertEquals(Set.of("<root>"), plan.missing().get("absent/Entry"));
        assertThrows(UnsupportedOperationException.class,
                () -> plan.missing().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> plan.missing().get("absent/Entry").add("changed"));
        assertThrows(IllegalArgumentException.class,
                () -> new MultiJarPlanner().plan(List.of(), List.of(), Set.of()));
    }
}