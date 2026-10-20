package com.goldclient.translator;

import java.io.ByteArrayInputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies the transformation surface is parsed and stays explicitly blocked. */
class ForgeTransformationInspectorTest {
  @TempDir
  Path tempDir;

  @Test
  void parsesMixinConfigsAccessTransformersAndCoremods() throws Exception {
    Path jar = tempDir.resolve("transforming-mod.jar");
    writeJar(jar);

    ForgeTransformationInspector.Report report =
        ForgeTransformationInspector.inspect(List.of(jar));

    assertEquals(1, report.mixinConfigs().size());
    ForgeTransformationInspector.MixinConfig config = report.mixinConfigs().get(0);
    assertEquals("mymod.mixins.json", config.resource());
    assertEquals("com.example.mixin", config.packageName());
    assertEquals(List.of(
        "com.example.mixin.CommonMixin",
        "com.example.OtherMixin",
        "com.example.mixin.ClientMixin",
        "com.example.mixin.ServerMixin"), config.mixinClasses());
    assertEquals("com.example.MixinPlugin", config.plugin());
    assertEquals("mymod.refmap.json", config.refmap());
    assertTrue(config.required());

    assertEquals(1, report.accessTransformers().size());
    ForgeTransformationInspector.AccessTransformer at = report.accessTransformers().get(0);
    assertEquals("accesstransformer.cfg", at.resource());
    assertEquals(3, at.ruleCount());

    assertTrue(report.coremods().stream().anyMatch(coremod ->
        coremod.key().equals("FMLCorePlugin")
            && coremod.value().equals("com.example.Core")));

    String blockers = String.join("\n", report.blockers());
    assertTrue(blockers.contains("Mixin transformation is not implemented"), blockers);
    assertTrue(blockers.contains("declare 4 mixin class(es)"), blockers);
    assertTrue(blockers.contains("Access-transformer rules are not applied"), blockers);
    assertTrue(blockers.contains("3 rule(s)"), blockers);
    assertTrue(blockers.contains("FMLCorePlugin=com.example.Core"), blockers);
    assertFalse(blockers.contains("Mixin transformation is implemented"), blockers);
  }

  @Test
  void malformedMixinConfigIsReportedAsAnUnparsedBlockerInsteadOfCrashing()
      throws Exception {
    ForgeTransformationInspector.MixinConfig config =
        ForgeTransformationInspector.parseMixinConfig("mod.jar", "broken.mixins.json",
            new ByteArrayInputStream("{ not json".getBytes(StandardCharsets.UTF_8)));
    assertEquals("broken.mixins.json", config.resource());
    assertTrue(config.mixinClasses().isEmpty());
    assertTrue(config.required());
  }

  @Test
  void exporterWritesTransformationEvidenceAndLifecycleBlockers() throws Exception {
    Path jar = tempDir.resolve("transforming-mod.jar");
    writeJar(jar);
    Path output = tempDir.resolve("export");

    new JavaScriptModTranslationExporter().export(List.of(jar), List.of(), output);

    assertTrue(Files.isRegularFile(output.resolve("forge-transformations.json")));
    String evidence = Files.readString(output.resolve("forge-transformations.json"));
    assertTrue(evidence.contains("mymod.mixins.json"), evidence);
    assertTrue(evidence.contains("accesstransformer.cfg"), evidence);
    String plan = Files.readString(output.resolve("forge-lifecycle-plan.json"));
    assertTrue(plan.contains("Mixin transformation is not implemented"), plan);
    assertTrue(plan.contains("Access-transformer rules are not applied"), plan);
    assertTrue(plan.contains("FMLCorePlugin"), plan);
    assertTrue(plan.contains("com.example.Core"), plan);
  }

  private static void writeJar(Path jar) throws Exception {
    Manifest manifest = new Manifest();
    manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
    manifest.getMainAttributes().putValue("FMLAT", "accesstransformer.cfg");
    manifest.getMainAttributes().putValue("MixinConfigs", "mymod.mixins.json");
    manifest.getMainAttributes().putValue("FMLCorePlugin", "com.example.Core");
    try (OutputStream stream = Files.newOutputStream(jar);
         JarOutputStream out = new JarOutputStream(stream)) {
      out.putNextEntry(new JarEntry("META-INF/MANIFEST.MF"));
      manifest.write(out);
      out.closeEntry();

      write(out, "accesstransformer.cfg", """
          # widen access for integrations
          public net.minecraft.world.World field_123
          public-f net.minecraft.block.Block func_456(I)V
          protected+ net.minecraft.entity.Entity field_789
          """);
      write(out, "mymod.mixins.json", """
          {
            "required": true,
            "package": "com.example.mixin",
            "compatibilityLevel": "JAVA_8",
            "refmap": "mymod.refmap.json",
            "plugin": "com.example.MixinPlugin",
            "mixins": ["CommonMixin", "com.example.OtherMixin"],
            "client": ["ClientMixin"],
            "server": [{"config": "ServerMixin"}]
          }
          """);
    }
  }

  private static void write(JarOutputStream out, String name, String content)
      throws Exception {
    out.putNextEntry(new JarEntry(name));
    out.write(content.getBytes(StandardCharsets.UTF_8));
    out.closeEntry();
  }
}
