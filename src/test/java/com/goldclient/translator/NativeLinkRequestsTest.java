package com.goldclient.translator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import static org.junit.jupiter.api.Assertions.*;

class NativeLinkRequestsTest {
    @TempDir Path temporary;
    @Test void discoversExactDescriptorsWithoutExecutingMod() throws Exception {
        var writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "fixture/Mod", null, "java/lang/Object", null);
        var method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "()V", null, null);
        method.visitCode();
        method.visitMethodInsn(Opcodes.INVOKESTATIC, "net/minecraft/client/Minecraft", "func_71410_x",
            "()Lnet/minecraft/client/Minecraft;", false);
        method.visitInsn(Opcodes.POP);
        method.visitFieldInsn(Opcodes.GETSTATIC, "net/minecraftforge/common/MinecraftForge", "EVENT_BUS",
            "Lnet/minecraftforge/fml/common/eventhandler/EventBus;");
        method.visitInsn(Opcodes.POP);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(1,0); method.visitEnd(); writer.visitEnd();
        Path input = temporary.resolve("original.jar");
        try (var jar = new JarOutputStream(Files.newOutputStream(input))) {
            jar.putNextEntry(new JarEntry("fixture/Mod.class")); jar.write(writer.toByteArray()); jar.closeEntry();
            jar.putNextEntry(new JarEntry("assets/fixture/test.txt")); jar.write(new byte[]{1}); jar.closeEntry();
        }
        byte[] original = Files.readAllBytes(input);
        var report = NativeLinkRequests.inspect(input);
        assertEquals(1,report.classesScanned()); assertEquals(2,report.requests().size());
        assertTrue(report.requests().stream().anyMatch(r -> r.descriptor().equals("()Lnet/minecraft/client/Minecraft;")));
        assertEquals(java.util.List.of("assets/fixture/test.txt"),report.resources());
        assertFalse(report.nativeBindingsResolved()); assertFalse(report.fullModEquivalenceVerified());
        assertArrayEquals(original,Files.readAllBytes(input));
        assertThrows(java.io.IOException.class, () -> NativeLinkRequests.main(new String[]{input.toString(),input.toString()}));
    }
}
