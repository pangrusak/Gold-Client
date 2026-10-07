package com.goldclient.analyzer;

import com.goldclient.model.ClassModel;
import com.goldclient.model.MethodModel;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import static org.junit.jupiter.api.Assertions.*;

class ModAnalyzerTest {
  @Test
  void analyzesFabricMetadataAndBytecodeReferences(@TempDir Path temp) throws Exception {
    Path jar = temp.resolve("test-fabric.jar");
    writeJar(jar,
        "fabric.mod.json",
        """
        {
          "schemaVersion": 1,
          "id": "example",
          "name": "Example Mod",
          "version": "1.2.3",
          "depends": {"minecraft": ">=1.20", "fabricloader": ">=0.15"},
          "suggests": {"cloth-config": "*"},
          "mixins": ["example.mixins.json"],
          "entrypoints": {"main": [{"value": "example.ExampleMod"}]}
        }
        """,
        "example/Example.class",
        exampleClass(),
        "example.mixins.json",
        "{}"
    );

    ModAnalysis analysis = new ModAnalyzer().analyze(jar);

    assertEquals("Example Mod", analysis.metadata().getName());
    assertEquals("1.2.3", analysis.metadata().getVersion());
    assertEquals(">=1.20", analysis.metadata().getMinecraftVersion());
    assertEquals("Fabric", analysis.metadata().getLoader());
    assertTrue(analysis.metadata().getDependencies().contains("minecraft"));
    assertTrue(analysis.metadata().getDependencies().contains("fabricloader"));
    assertTrue(analysis.metadata().getDependencies().contains("cloth-config"));
    assertTrue(analysis.metadata().getMixins().contains("example.mixins.json"));
    assertEquals(List.of("example.ExampleMod"), analysis.metadata().getEntrypoints());

    ClassModel clazz = analysis.classes().get(0);
    assertTrue(clazz.referencedClasses().contains("net.minecraft.Example"));
    MethodModel method = clazz.methods().stream()
        .filter(m -> m.name().equals("run"))
        .findFirst().orElseThrow();
    assertFalse(method.instructions().isEmpty());
    assertFalse(method.controlFlow().blocks().isEmpty());
    assertFalse(method.intermediateRepresentation().operations().isEmpty());
  }

  @Test
  void analyzesForgeDependencySections(@TempDir Path temp) throws Exception {
    Path jar = temp.resolve("test-forge.jar");
    writeJar(jar,
        "META-INF/mods.toml",
        """
        modLoader="javafml"
        loaderVersion="[47,)"
        [[mods]]
        modId="example"
        version="1.0.0"
        displayName="Example Forge"

        [[dependencies.example]]
        modId="forge"
        mandatory=true
        versionRange="[47,)"
        ordering="NONE"
        side="BOTH"

        [[dependencies.example]]
        modId="minecraft"
        mandatory=true
        versionRange="[1.20.1,1.21)"
        ordering="NONE"
        side="BOTH"
        """,
        "example/Example.class",
        forgeExampleClass()
    );

    ModAnalysis analysis = new ModAnalyzer().analyze(jar);

    assertEquals("Example Forge", analysis.metadata().getName());
    assertEquals("1.0.0", analysis.metadata().getVersion());
    assertEquals("[1.20.1,1.21)", analysis.metadata().getMinecraftVersion());
    assertEquals("Forge", analysis.metadata().getLoader());
    assertTrue(analysis.metadata().getDependencies().contains("forge"));
    assertTrue(analysis.metadata().getDependencies().contains("minecraft"));
    assertFalse(analysis.metadata().getDependencies().contains("example"));
    assertTrue(analysis.platformRequirements().contains(PlatformRequirement.FORGE_MOD_METADATA));
    assertTrue(analysis.platformRequirements().contains(PlatformRequirement.FORGE_EVENT_BUS));
    assertTrue(analysis.platformRequirements().contains(PlatformRequirement.FORGE_PLATFORM_CONTEXT));
  }

  private static void writeJar(Path path, String... entries) throws Exception {
    try (OutputStream out = Files.newOutputStream(path);
         JarOutputStream jar = new JarOutputStream(out)) {
      for (int i = 0; i < entries.length; i += 2) {
        JarEntry entry = new JarEntry(entries[i]);
        jar.putNextEntry(entry);
        if (entries[i].endsWith(".class")) {
          jar.write(java.util.Base64.getDecoder().decode(entries[i + 1]));
        } else {
          jar.write(entries[i + 1].getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        jar.closeEntry();
      }
    }
  }

  private static String forgeExampleClass() {
    ClassWriter writer = new ClassWriter(0);
    writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "example/ForgeExample", null,
        "java/lang/Object", null);
    writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "BUS",
        "Lnet/minecraftforge/common/MinecraftForge;", null, null).visitEnd();
    writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "COMMON",
        "Lcom/example/PlatformContextLoaderCommonForge;", null, null).visitEnd();
    org.objectweb.asm.MethodVisitor method =
        writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "()V", null, null);
    method.visitCode();
    method.visitInsn(Opcodes.RETURN);
    method.visitMaxs(0, 0);
    method.visitEnd();
    return java.util.Base64.getEncoder().encodeToString(writer.toByteArray());
  }

  private static String exampleClass() {
    ClassWriter writer = new ClassWriter(0);
    writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "example/Example", null,
        "java/lang/Object", null);
    writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "VALUE",
        "Lnet/minecraft/Example;", null, null).visitEnd();

    org.objectweb.asm.MethodVisitor method =
        writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "()V", null, null);
    method.visitCode();
    method.visitInsn(Opcodes.RETURN);
    method.visitMaxs(0, 0);
    method.visitEnd();
    return java.util.Base64.getEncoder().encodeToString(writer.toByteArray());
  }
}
