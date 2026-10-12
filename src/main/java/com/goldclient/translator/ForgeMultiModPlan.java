package com.goldclient.translator;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.jar.JarFile;

/**
 * Builds one dependency order from a set of Forge 1.12 mod JARs.
 * This is metadata planning only; it does not claim to execute or translate mods.
 */
public final class ForgeMultiModPlan {
    private static final String MOD_ANNOTATION = "Lnet/minecraftforge/fml/common/Mod;";

    private ForgeMultiModPlan() {}

    private record LocatedMod(Path jar, ForgeDependencyGraph.Mod mod) {}

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            throw new IllegalArgumentException(
                    "Usage: ForgeMultiModPlan <output-plan.txt> <mod.jar> [mod.jar ...]");
        }
        Path output = Path.of(args[0]).toAbsolutePath().normalize();
        List<Path> jars = new ArrayList<>();
        for (int i = 1; i < args.length; i++) jars.add(Path.of(args[i]).toRealPath());
        List<LocatedMod> discovered = discover(jars);
        ForgeDependencyGraph.Plan plan = ForgeDependencyGraph.plan(
                discovered.stream().map(LocatedMod::mod).toList());

        Map<String, Path> jarById = new LinkedHashMap<>();
        for (LocatedMod located : discovered) jarById.put(located.mod().id(), located.jar());
        StringBuilder report = new StringBuilder();
        report.append("Gold Client multi-mod dependency plan\n");
        report.append("Input JARs: ").append(jars.size()).append('\n');
        report.append("Discovered @Mod entries: ").append(discovered.size()).append('\n');
        report.append("Ready: ").append(plan.ready()).append('\n');
        report.append("Order:\n");
        if (plan.ready()) {
            int index = 1;
            for (ForgeDependencyGraph.Mod mod : plan.ordered()) {
                report.append(index++).append(". ").append(mod.id())
                        .append(" (version ").append(mod.version().isBlank() ? "unspecified" : mod.version())
                        .append(") <- ").append(jarById.get(mod.id())).append('\n');
            }
        } else {
            report.append("(not generated because blockers remain)\n");
        }
        report.append("Blockers:\n");
        if (plan.blockers().isEmpty()) report.append("(none)\n");
        else for (String blocker : plan.blockers()) report.append("- ").append(blocker).append('\n');
        report.append("Runtime execution verified: false\n");
        report.append("Translation and EaglerForgeInject compatibility verified: false\n");
        Files.createDirectories(output.getParent());
        Files.writeString(output, report);
        System.out.print(report);
        if (!plan.ready()) throw new IllegalStateException("Multi-mod plan blocked; see " + output);
    }

    static List<LocatedMod> discover(List<Path> jars) throws IOException {
        List<LocatedMod> result = new ArrayList<>();
        for (Path jar : jars) {
            try (JarFile file = new JarFile(jar.toFile(), false)) {
                var entries = file.stream()
                        .filter(entry -> !entry.isDirectory() && entry.getName().endsWith(".class"))
                        .sorted(Comparator.comparing(java.util.jar.JarEntry::getName))
                        .toList();
                int before = result.size();
                for (var entry : entries) {
                    try (var input = file.getInputStream(entry)) {
                        new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9) {
                            @Override
                            public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                                if (!MOD_ANNOTATION.equals(descriptor)) return null;
                                return new AnnotationVisitor(Opcodes.ASM9) {
                                    String id = "";
                                    String version = "";
                                    String dependencies = "";
                                    @Override
                                    public void visit(String name, Object value) {
                                        if ("modid".equals(name)) id = Objects.toString(value, "");
                                        else if ("version".equals(name)) version = Objects.toString(value, "");
                                        else if ("dependencies".equals(name)) dependencies = Objects.toString(value, "");
                                    }
                                    @Override
                                    public void visitEnd() {
                                        if (id.isBlank()) {
                                            throw new InvalidModMetadataException(
                                                    "Forge @Mod annotation has no modid in " + jar + "!" + entry.getName());
                                        }
                                        result.add(new LocatedMod(jar,
                                                new ForgeDependencyGraph.Mod(id, version, dependencies)));
                                    }
                                };
                            }
                        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                    } catch (InvalidModMetadataException error) {
                        throw new IOException(error.getMessage(), error);
                    }
                }
                if (result.size() == before) {
                    throw new IOException("No Forge 1.12 @Mod class found in " + jar);
                }
            }
        }
        return List.copyOf(result);
    }

    private static final class InvalidModMetadataException extends RuntimeException {
        private InvalidModMetadataException(String message) { super(message); }
    }
}
