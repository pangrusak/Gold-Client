package com.goldclient.translator;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
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
 * Builds one dependency order from Forge 1.12 mod JARs and supporting mod JARs.
 * This is metadata planning only; it does not claim to execute or translate mods.
 */
public final class ForgeMultiModPlan {
    private static final String MOD_ANNOTATION = "Lnet/minecraftforge/fml/common/Mod;";

    private ForgeMultiModPlan() {}

    private record LocatedMod(Path jar, ForgeDependencyGraph.Mod mod, String metadataSource) {}

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            throw new IllegalArgumentException(
                    "Usage: ForgeMultiModPlan <output-plan.txt> <mod-or-dependency.jar> [jar ...]");
        }
        Path output = Path.of(args[0]).toAbsolutePath().normalize();
        List<Path> jars = new ArrayList<>();
        for (int i = 1; i < args.length; i++) jars.add(Path.of(args[i]).toRealPath());
        List<LocatedMod> discovered = discover(jars);
        ForgeDependencyGraph.Plan plan = ForgeDependencyGraph.plan(
                discovered.stream().map(LocatedMod::mod).toList());

        Map<String, Path> jarById = new LinkedHashMap<>();
        Map<String, String> sourceById = new LinkedHashMap<>();
        for (LocatedMod located : discovered) {
            jarById.put(located.mod().id(), located.jar());
            sourceById.put(located.mod().id(), located.metadataSource());
        }
        StringBuilder report = new StringBuilder();
        report.append("Gold Client multi-mod dependency plan\n");
        report.append("Input JARs: ").append(jars.size()).append('\n');
        report.append("Discovered mod metadata entries: ").append(discovered.size()).append('\n');
        long annotationCount = discovered.stream()
                .filter(located -> located.metadataSource().equals("@Mod annotation")).count();
        long metadataOnlyCount = discovered.size() - annotationCount;
        report.append("Forge @Mod entries: ").append(annotationCount).append('\n');
        report.append("mcmod.info-only entries: ").append(metadataOnlyCount).append('\n');
        report.append("Ready: ").append(plan.ready()).append('\n');
        report.append("Order:\n");
        if (plan.ready()) {
            int index = 1;
            for (ForgeDependencyGraph.Mod mod : plan.ordered()) {
                report.append(index++).append(". ").append(mod.id())
                        .append(" (version ").append(mod.version().isBlank() ? "unspecified" : mod.version())
                        .append("; metadata: ").append(sourceById.get(mod.id())).append(") <- ")
                        .append(jarById.get(mod.id())).append('\n');
            }
        } else {
            report.append("(not generated because blockers remain)\n");
        }
        report.append("Blockers:\n");
        if (plan.blockers().isEmpty()) report.append("(none)\n");
        else for (String blocker : plan.blockers()) report.append("- ").append(blocker).append('\n');
        report.append("Note: mcmod.info-only entries satisfy dependency IDs, but their dependency declarations ")
                .append("are not inferred from that file.\n");
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
                int before = result.size();
                var entries = file.stream()
                        .filter(entry -> !entry.isDirectory() && entry.getName().endsWith(".class"))
                        .sorted(Comparator.comparing(java.util.jar.JarEntry::getName))
                        .toList();
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
                                                new ForgeDependencyGraph.Mod(id, version, dependencies),
                                                "@Mod annotation"));
                                    }
                                };
                            }
                        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                    } catch (InvalidModMetadataException error) {
                        throw new IOException(error.getMessage(), error);
                    } catch (IllegalArgumentException error) {
                        throw new IOException("Could not parse class metadata in " + jar + "!" + entry.getName()
                                + ": " + error.getMessage(), error);
                    }
                }
                if (result.size() == before) {
                    List<ForgeDependencyGraph.Mod> metadataMods = readMcModInfo(file, jar);
                    if (metadataMods.isEmpty()) {
                        throw new IOException("No Forge @Mod class or usable mcmod.info mod ID found in " + jar
                                + ". It may be a library/coremod, or the JAR may not be a Forge mod.");
                    }
                    for (ForgeDependencyGraph.Mod mod : metadataMods) {
                        result.add(new LocatedMod(jar, mod, "mcmod.info (metadata-only)"));
                    }
                }
            }
        }
        return List.copyOf(result);
    }

    private static List<ForgeDependencyGraph.Mod> readMcModInfo(JarFile file, Path jar) throws IOException {
        var entry = file.getJarEntry("mcmod.info");
        if (entry == null) return List.of();
        try (var input = file.getInputStream(entry);
             var reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            JsonElement root = JsonParser.parseReader(reader);
            List<JsonObject> objects = new ArrayList<>();
            if (root.isJsonArray()) {
                JsonArray array = root.getAsJsonArray();
                for (JsonElement element : array) {
                    if (element.isJsonObject()) objects.add(element.getAsJsonObject());
                }
            } else if (root.isJsonObject()) {
                JsonObject object = root.getAsJsonObject();
                if (object.has("modList") && object.get("modList").isJsonArray()) {
                    for (JsonElement element : object.getAsJsonArray("modList")) {
                        if (element.isJsonObject()) objects.add(element.getAsJsonObject());
                    }
                } else {
                    objects.add(object);
                }
            } else {
                throw new IOException("Unsupported mcmod.info JSON root in " + jar);
            }

            List<ForgeDependencyGraph.Mod> mods = new ArrayList<>();
            for (JsonObject object : objects) {
                if (!object.has("modid") || object.get("modid").isJsonNull()) continue;
                String id = object.get("modid").getAsString().trim();
                if (id.isEmpty()) continue;
                String version = object.has("version") && !object.get("version").isJsonNull()
                        ? object.get("version").getAsString() : "";
                try {
                    mods.add(new ForgeDependencyGraph.Mod(id, version, ""));
                } catch (IllegalArgumentException error) {
                    throw new IOException("Invalid mod ID in mcmod.info for " + jar + ": " + id, error);
                }
            }
            return List.copyOf(mods);
        } catch (com.google.gson.JsonParseException | IllegalStateException error) {
            throw new IOException("Could not parse mcmod.info in " + jar + ": " + error.getMessage(), error);
        }
    }

    private static final class InvalidModMetadataException extends RuntimeException {
        private InvalidModMetadataException(String message) { super(message); }
    }
}
