package com.goldclient.translator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import java.net.*;
import java.lang.reflect.*;
import javax.tools.ToolProvider;
import static org.junit.jupiter.api.Assertions.*;

class ForgeJarBootstrapTest {
    @TempDir Path temp;
    private Path fixture(String extra, String manifestKey) throws Exception {
        Path root = temp.resolve(UUID.randomUUID().toString());
        Path sources = root.resolve("src");
        Path classes = root.resolve("classes");
        Files.createDirectories(sources); Files.createDirectories(classes);
        Map<String, String> files = new LinkedHashMap<>();
        files.put("net/minecraftforge/fml/common/Mod.java", """
            package net.minecraftforge.fml.common;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.RUNTIME) public @interface Mod {
              String modid(); String dependencies() default "";
              @Retention(RetentionPolicy.RUNTIME) public @interface EventHandler {}
              @Retention(RetentionPolicy.RUNTIME) public @interface Instance { String value() default ""; }
              @Retention(RetentionPolicy.RUNTIME) public @interface Metadata {}
            }
            """);
        files.put("net/minecraftforge/fml/common/ModMetadata.java",
                "package net.minecraftforge.fml.common; public class ModMetadata {}");
        files.put("net/minecraftforge/fml/common/event/FMLInitializationEvent.java",
                "package net.minecraftforge.fml.common.event; public class FMLInitializationEvent {}");
        files.put("fixture/Example.java", """
            package fixture;
            import net.minecraftforge.fml.common.Mod;
            import net.minecraftforge.fml.common.event.FMLInitializationEvent;
            @Mod(modid="example") public class Example {
              public static int constructions;
              public static Object received;
              public static Object current;
              public Example() { constructions++; current = this; }
              @Mod.EventHandler public void initialize(FMLInitializationEvent event) { received = event; }
            """ + extra + "\n}");
        List<String> arguments = new ArrayList<>(List.of("--release", "8", "-d", classes.toString()));
        for (var entry : files.entrySet()) {
            Path file = sources.resolve(entry.getKey()); Files.createDirectories(file.getParent());
            Files.writeString(file, entry.getValue()); arguments.add(file.toString());
        }
        var compiler = ToolProvider.getSystemJavaCompiler(); assertNotNull(compiler);
        assertEquals(0, compiler.run(null, null, null, arguments.toArray(String[]::new)));
        Path jar = root.resolve("mod.jar");
        Manifest manifest = new Manifest(); manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        if (manifestKey != null) manifest.getMainAttributes().putValue(manifestKey, "fixture.Transformer");
        try (var output = new JarOutputStream(Files.newOutputStream(jar), manifest);
             var paths = Files.walk(classes)) {
            for (Path file : paths.filter(Files::isRegularFile).sorted().toList()) {
                output.putNextEntry(new JarEntry(classes.relativize(file).toString().replace('\\', '/')));
                Files.copy(file, output); output.closeEntry();
            }
        }
        return jar;
    }

    @Test void discoversWithoutInitializingOriginalClasses() throws Exception {
        Path jar = fixture("static { if (System.getProperty(\"fixture.initialize\") == null) throw new RuntimeException(\"Must not load while discovering\"); }", null);
        byte[] original = Files.readAllBytes(jar);
        var plan = ForgeJarBootstrap.inspect(jar);
        assertTrue(plan.blockers().isEmpty(), plan.blockers().toString());
        assertEquals("example", plan.modId());
        assertTrue(plan.bootstrapSource().contains("mod.initialize("));
        assertArrayEquals(original, Files.readAllBytes(jar));
    }

    @Test void blocksUnimplementedTransformers() throws Exception {
        var plan = ForgeJarBootstrap.inspect(fixture("", "FMLCorePlugin"));
        assertTrue(plan.blockers().stream().anyMatch(s -> s.contains("FMLCorePlugin")));
        assertEquals("", plan.bootstrapSource());
    }

    @Test void injectsOriginalModInstanceAndMetadataFromHost() throws Exception {
        Path jar = fixture("""
              @Mod.Instance private static Example instance;
              @Mod.Metadata private static net.minecraftforge.fml.common.ModMetadata metadata;
            """, null);
        var plan = ForgeJarBootstrap.inspect(jar);
        assertTrue(plan.blockers().isEmpty(), plan.blockers().toString());
        assertTrue(plan.bootstrapSource().contains("injectField(Example.class, null, \"instance\", mod);"));
        assertTrue(plan.bootstrapSource().contains("injectField(Example.class, null, \"metadata\", ((net.minecraftforge.fml.common.ModMetadata) host.metadataFor(\"example\")));"));

        Path source = temp.resolve("injection/gold/generated/ForgeModBootstrap.java");
        Path compiled = temp.resolve("injection/classes");
        Files.createDirectories(source.getParent());
        Files.createDirectories(compiled);
        Files.writeString(source, plan.bootstrapSource());
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "--release", "8", "-classpath", jar.toString(), "-d", compiled.toString(), source.toString()));

        try (var loader = new URLClassLoader(new URL[] {compiled.toUri().toURL(), jar.toUri().toURL()},
                ClassLoader.getPlatformClassLoader())) {
            Class<?> bootstrap = loader.loadClass("gold.generated.ForgeModBootstrap");
            Class<?> example = loader.loadClass("fixture.Example");
            Class<?> metadataType = loader.loadClass("net.minecraftforge.fml.common.ModMetadata");
            Object metadata = metadataType.getConstructor().newInstance();
            Class<?> hostType = loader.loadClass("gold.generated.ForgeModBootstrap$Host");
            Object host = java.lang.reflect.Proxy.newProxyInstance(loader, new Class<?>[] {hostType},
                    (proxy, method, args) -> {
                        if (method.getName().equals("metadataFor")) return metadata;
                        if (method.getName().equals("eventFor")) {
                            return loader.loadClass("net.minecraftforge.fml.common.event.FMLInitializationEvent")
                                    .getConstructor().newInstance();
                        }
                        return null;
                    });
            bootstrap.getMethod("bindHost", hostType).invoke(null, host);
            bootstrap.getMethod("main", String[].class).invoke(null, (Object) new String[0]);
            Field instanceField = example.getDeclaredField("instance"); instanceField.setAccessible(true);
            Field metadataField = example.getDeclaredField("metadata"); metadataField.setAccessible(true);
            assertSame(example.getField("current").get(null), instanceField.get(null));
            assertSame(metadata, metadataField.get(null));
        }
    }

    @Test void dispatchesEveryHandlerForTheSameLifecyclePhaseInClassFileOrder() throws Exception {
        Path jar = fixture("""
              @Mod.EventHandler public void initializeSecond(FMLInitializationEvent event) { constructions += 10; }
              @Mod.EventHandler public void initializeThird(FMLInitializationEvent event) { constructions += 100; }
            """, null);
        var plan = ForgeJarBootstrap.inspect(jar);
        assertTrue(plan.blockers().isEmpty(), plan.blockers().toString());
        String source = plan.bootstrapSource();
        int first = source.indexOf("mod.initialize(");
        int second = source.indexOf("mod.initializeSecond(");
        int third = source.indexOf("mod.initializeThird(");
        assertTrue(first >= 0, source);
        assertTrue(second > first, source);
        assertTrue(third > second, source);

        Path generated = temp.resolve("multi-handler/gold/generated/ForgeModBootstrap.java");
        Path classes = temp.resolve("multi-handler/classes");
        Files.createDirectories(generated.getParent());
        Files.createDirectories(classes);
        Files.writeString(generated, source);
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "--release", "8", "-classpath", jar.toString(), "-d", classes.toString(), generated.toString()));
    }

    @Test void originalHandlerReceivesTheExactHostEvent() throws Exception {
        Path jar = fixture("", null);
        var plan = ForgeJarBootstrap.inspect(jar);
        Path source = temp.resolve("generated/gold/generated/ForgeModBootstrap.java");
        Path compiled = temp.resolve("bootstrap-classes");
        Files.createDirectories(source.getParent()); Files.createDirectories(compiled);
        Files.writeString(source, plan.bootstrapSource());
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "--release", "8", "-classpath", jar.toString(), "-d", compiled.toString(), source.toString()));
        try (var loader = new URLClassLoader(new URL[] {compiled.toUri().toURL(), jar.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
            Class<?> bootstrap = loader.loadClass("gold.generated.ForgeModBootstrap");
            Class<?> example = loader.loadClass("fixture.Example");
            Class<?> eventClass = loader.loadClass("net.minecraftforge.fml.common.event.FMLInitializationEvent");
            Object event = eventClass.getConstructor().newInstance();
            List<String> calls = new ArrayList<>();
            Class<?> hostType = loader.loadClass("gold.generated.ForgeModBootstrap$Host");
            Object host = java.lang.reflect.Proxy.newProxyInstance(loader, new Class<?>[]{hostType},
                (proxy, method, args) -> {
                    if (method.getName().equals("afterConstruction")) {
                        assertEquals("example", args[0]);
                        assertSame(example.getField("current").get(null), args[1]);
                        assertEquals(1, example.getField("constructions").get(null));
                        assertNull(example.getField("received").get(null));
                        calls.add("afterConstruction:example");
                        return null;
                    }
                    calls.add(method.getName() + ":" + String.join(":", Arrays.stream(args).map(Object::toString).toList()));
                    return method.getName().equals("eventFor") ? event : null;
                });
            InvocationTargetException missing = assertThrows(InvocationTargetException.class,
                    () -> bootstrap.getMethod("main", String[].class).invoke(null, (Object) new String[0]));
            assertTrue(missing.getCause().getMessage().contains("host is required"));
            assertEquals(0, example.getField("constructions").get(null));
            bootstrap.getMethod("bindHost", hostType).invoke(null, host);
            bootstrap.getMethod("main", String[].class).invoke(null, (Object) new String[0]);
            assertEquals(1, example.getField("constructions").get(null));
            assertSame(event, example.getField("received").get(null));
            assertEquals(List.of("beforeConstruction:example", "afterConstruction:example", "afterPhase:example:preInit",
                    "eventFor:example:init", "afterPhase:example:init",
                    "afterPhase:example:postInit", "afterPhase:example:loadComplete"), calls);
            InvocationTargetException repeated = assertThrows(InvocationTargetException.class,
                    () -> bootstrap.getMethod("main", String[].class).invoke(null, (Object) new String[0]));
            assertTrue(repeated.getCause().getMessage().contains("already started"));
        }
    }
}
