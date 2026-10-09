package com.goldclient.analyzer;

import com.goldclient.model.AnnotationData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnnotationCaptureTest {

  @Test
  void capturesModValuesWithoutLoadingForgeClasses(
      @TempDir Path directory
  ) throws Exception {
    ClassWriter writer = new ClassWriter(0);

    writer.visit(
        Opcodes.V1_8,
        Opcodes.ACC_PUBLIC,
        "fixture/ExampleMod",
        null,
        "java/lang/Object",
        null
    );

    var mod = writer.visitAnnotation(
        "Lnet/minecraftforge/fml/common/Mod;",
        true
    );

    mod.visit("modid", "fixturemod");
    mod.visit("version", "1.0");
    mod.visit("dependencies", "required-after:fixturedependency");
    mod.visit("clientSideOnly", true);
    mod.visitEnd();

    var extra = writer.visitAnnotation("Lfixture/Extra;", false);
    extra.visit("numbers", new int[] {1, 2});

    var nested = extra.visitAnnotation("nested", "Lfixture/Nested;");
    nested.visit("label", "captured");
    nested.visitEnd();

    extra.visitEnum("mode", "Lfixture/Mode;", "CLIENT");
    extra.visitEnd();

    writer.visitEnd();

    Path jar = directory.resolve("fixture.jar");

    try (JarOutputStream output =
        new JarOutputStream(Files.newOutputStream(jar))) {
      output.putNextEntry(new JarEntry("fixture/ExampleMod.class"));
      output.write(writer.toByteArray());
      output.closeEntry();
    }

    var analysis = new ModAnalyzer().analyze(jar);
    var clazz = analysis.classes().get(0);

    assertTrue(clazz.annotations().contains(
        "net.minecraftforge.fml.common.Mod"
    ));

    AnnotationData capturedMod = clazz.annotationData().stream()
        .filter(annotation -> annotation.name().equals(
            "net.minecraftforge.fml.common.Mod"))
        .findFirst()
        .orElseThrow();

    assertEquals("fixturemod", capturedMod.values().get("modid"));
    assertEquals("1.0", capturedMod.values().get("version"));
    assertEquals(
        "required-after:fixturedependency",
        capturedMod.values().get("dependencies")
    );
    assertEquals(true, capturedMod.values().get("clientSideOnly"));

    AnnotationData capturedExtra = clazz.annotationData().stream()
        .filter(annotation -> annotation.name().equals("fixture.Extra"))
        .findFirst()
        .orElseThrow();

    assertEquals(List.of(1, 2), capturedExtra.values().get("numbers"));

    AnnotationData capturedNested =
        (AnnotationData) capturedExtra.values().get("nested");

    assertEquals("captured", capturedNested.values().get("label"));
    assertEquals(
        new AnnotationData.EnumValue("Lfixture/Mode;", "CLIENT"),
        capturedExtra.values().get("mode")
    );
  }
}