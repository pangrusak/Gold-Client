package com.goldclient.translator;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A narrowly scoped platform-remapping experiment.
 *
 * Remaps Forge's original Language class from MCP member names to SRG
 * member names. This is not a general-purpose Forge JAR remapper.
 *
 * The input files are not modified. Generated output stays under target/.
 */
class ForgeLanguageRemapTest {

    private static final String LANGUAGE_OWNER =
            "net/minecraft/client/resources/Language";

    private static final String LANGUAGE_ENTRY =
            LANGUAGE_OWNER + ".class";

    @Test
    void originalForgeLanguageCanBeRemappedToSrg() throws Exception {
        Path repository = Path.of("").toAbsolutePath();

        Path forgeJar = repository.resolve(
                "target/forge-baseline/forge-mapped.jar"
        );

        Path mappings = Path.of(
                System.getProperty("user.home"),
                ".gradle",
                "caches",
                "minecraft",
                "de",
                "oceanlabs",
                "mcp",
                "mcp_snapshot",
                "20171003",
                "1.12.2",
                "srgs",
                "mcp-srg.srg"
        );

        assertTrue(
                Files.isRegularFile(forgeJar),
                "Prepared Forge input is missing: " + forgeJar
        );

        assertTrue(
                Files.isRegularFile(mappings),
                "MCP-to-SRG mappings are missing: " + mappings
        );

        MemberMappings memberMappings = readMappings(mappings);

        assertEquals(
                "func_135034_a",
                memberMappings.methodName(
                        LANGUAGE_OWNER,
                        "getLanguageCode",
                        "()Ljava/lang/String;"
                ),
                "Unexpected Language mapping"
        );

        assertEquals(
                "getJavaLocale",
                memberMappings.methodName(
                        LANGUAGE_OWNER,
                        "getJavaLocale",
                        "()Ljava/util/Locale;"
                ),
                "Forge-added getJavaLocale must retain its name"
        );

        byte[] originalBytes = readClass(forgeJar, LANGUAGE_ENTRY);
        byte[] remappedBytes = remapLanguage(
                originalBytes,
                memberMappings
        );

        Path outputJar = repository.resolve(
                "target/forge-baseline/forge-language-srg.jar"
        );

        Files.createDirectories(outputJar.getParent());

        try (JarOutputStream output = new JarOutputStream(
                Files.newOutputStream(outputJar)
        )) {
            output.putNextEntry(new JarEntry(LANGUAGE_ENTRY));
            output.write(remappedBytes);
            output.closeEntry();
        }

        try (
                URLClassLoader originalLoader = isolatedLoader(forgeJar);
                URLClassLoader remappedLoader = isolatedLoader(outputJar)
        ) {
            Class<?> original = originalLoader.loadClass(
                    LANGUAGE_OWNER.replace('/', '.')
            );

            Class<?> remapped = remappedLoader.loadClass(
                    LANGUAGE_OWNER.replace('/', '.')
            );

            assertNotNull(
                    remapped.getMethod("func_135034_a"),
                    "SRG language-code method must exist"
            );

            assertNotNull(
                    remapped.getMethod("func_135035_b"),
                    "SRG bidirectional method must exist"
            );

            assertNotNull(
                    remapped.getMethod("getJavaLocale"),
                    "Forge locale method must exist"
            );

            boolean hasMcpLanguageCodeMethod = false;

            for (var method : remapped.getDeclaredMethods()) {
                if (method.getName().equals("getLanguageCode")) {
                    hasMcpLanguageCodeMethod = true;
                }
            }

            assertFalse(
                    hasMcpLanguageCodeMethod,
                    "Mapped method should no longer use its MCP name"
            );

            for (String languageCode : List.of(
                    "en_us",
                    "tr_tr",
                    "de_de",
                    "ja_jp",
                    "en"
            )) {
                Object originalLanguage = newLanguage(
                        original,
                        languageCode
                );

                Object remappedLanguage = newLanguage(
                        remapped,
                        languageCode
                );

                assertEquals(
                        original.getMethod("getLanguageCode")
                                .invoke(originalLanguage),
                        remapped.getMethod("func_135034_a")
                                .invoke(remappedLanguage)
                );

                assertEquals(
                        original.getMethod("isBidirectional")
                                .invoke(originalLanguage),
                        remapped.getMethod("func_135035_b")
                                .invoke(remappedLanguage)
                );

                Locale originalLocale = (Locale) original
                        .getMethod("getJavaLocale")
                        .invoke(originalLanguage);

                Locale remappedLocale = (Locale) remapped
                        .getMethod("getJavaLocale")
                        .invoke(remappedLanguage);

                assertEquals(
                        originalLocale,
                        remappedLocale,
                        "Locale changed during remapping: " + languageCode
                );

                String probe = "IRON \u0130 I\u0307";

                assertEquals(
                        probe.toLowerCase(originalLocale),
                        probe.toLowerCase(remappedLocale),
                        "Locale-sensitive case behavior changed"
                );

                assertEquals(
                        originalLanguage.toString(),
                        remappedLanguage.toString()
                );

                assertEquals(
                        originalLanguage.hashCode(),
                        remappedLanguage.hashCode()
                );
            }
        }

        System.out.println(
                "Original Forge Language remapped and verified: "
                        + outputJar
        );
    }

    private static URLClassLoader isolatedLoader(Path jar)
            throws IOException {
        return new URLClassLoader(
                new URL[]{jar.toUri().toURL()},
                ClassLoader.getPlatformClassLoader()
        );
    }

    private static Object newLanguage(
            Class<?> type,
            String code
    ) throws Exception {
        return type.getConstructor(
                String.class,
                String.class,
                String.class,
                boolean.class
        ).newInstance(code, "Test region", "Test language", false);
    }

    private static byte[] readClass(
            Path jar,
            String entryName
    ) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            var entry = zip.getEntry(entryName);

            if (entry == null) {
                throw new IOException(
                        "Required original class is missing: " + entryName
                );
            }

            try (var input = zip.getInputStream(entry)) {
                return input.readAllBytes();
            }
        }
    }

    private static MemberMappings readMappings(Path path)
            throws IOException {
        Map<String, String> methods = new HashMap<>();
        Map<String, String> fields = new HashMap<>();

        for (String line : Files.readAllLines(path)) {
            String trimmed = line.trim();

            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }

            String[] parts = trimmed.split("\\s+");

            if (parts[0].equals("CL:") && parts.length == 3) {
                if (!parts[1].equals(parts[2])) {
                    throw new IOException(
                            "This narrow remapper requires unchanged class names: "
                                    + trimmed
                    );
                }
            } else if (parts[0].equals("MD:") && parts.length == 5) {
                String sourceMember = parts[1];
                String sourceDescriptor = parts[2];
                String targetMember = parts[3];
                String targetDescriptor = parts[4];

                requireSameOwner(sourceMember, targetMember, trimmed);

                if (!sourceDescriptor.equals(targetDescriptor)) {
                    throw new IOException(
                            "Descriptor remapping is outside this test's scope: "
                                    + trimmed
                    );
                }

                methods.put(
                        sourceMember + " " + sourceDescriptor,
                        memberName(targetMember)
                );
            } else if (parts[0].equals("FD:") && parts.length == 3) {
                requireSameOwner(parts[1], parts[2], trimmed);
                fields.put(parts[1], memberName(parts[2]));
            }
        }

        return new MemberMappings(methods, fields);
    }

    private static void requireSameOwner(
            String source,
            String target,
            String line
    ) throws IOException {
        String sourceOwner = source.substring(0, source.lastIndexOf('/'));
        String targetOwner = target.substring(0, target.lastIndexOf('/'));

        if (!sourceOwner.equals(targetOwner)) {
            throw new IOException(
                    "Owner remapping is outside this test's scope: " + line
            );
        }
    }

    private static String memberName(String member) {
        return member.substring(member.lastIndexOf('/') + 1);
    }

    private static byte[] remapLanguage(
            byte[] source,
            MemberMappings mappings
    ) {
        ClassReader reader = new ClassReader(source);

        if (!reader.getClassName().equals(LANGUAGE_OWNER)) {
            throw new IllegalArgumentException(
                    "This test remaps only the original Forge Language class"
            );
        }

        ClassWriter writer = new ClassWriter(0);

        ClassVisitor visitor = new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public FieldVisitor visitField(
                    int access,
                    String name,
                    String descriptor,
                    String signature,
                    Object value
            ) {
                return super.visitField(
                        access,
                        mappings.fieldName(LANGUAGE_OWNER, name),
                        descriptor,
                        signature,
                        value
                );
            }

            @Override
            public MethodVisitor visitMethod(
                    int access,
                    String name,
                    String descriptor,
                    String signature,
                    String[] exceptions
            ) {
                MethodVisitor destination = super.visitMethod(
                        access,
                        mappings.methodName(
                                LANGUAGE_OWNER,
                                name,
                                descriptor
                        ),
                        descriptor,
                        signature,
                        exceptions
                );

                return new MethodVisitor(Opcodes.ASM9, destination) {
                    @Override
                    public void visitFieldInsn(
                            int opcode,
                            String owner,
                            String name,
                            String descriptor
                    ) {
                        super.visitFieldInsn(
                                opcode,
                                owner,
                                mappings.fieldName(owner, name),
                                descriptor
                        );
                    }

                    @Override
                    public void visitMethodInsn(
                            int opcode,
                            String owner,
                            String name,
                            String descriptor,
                            boolean isInterface
                    ) {
                        super.visitMethodInsn(
                                opcode,
                                owner,
                                mappings.methodName(
                                        owner,
                                        name,
                                        descriptor
                                ),
                                descriptor,
                                isInterface
                        );
                    }

                    @Override
                    public void visitInvokeDynamicInsn(
                            String name,
                            String descriptor,
                            org.objectweb.asm.Handle bootstrapMethodHandle,
                            Object... bootstrapMethodArguments
                    ) {
                        throw new IllegalStateException(
                                "Unexpected invokedynamic in Language; "
                                        + "this narrow remapper does not handle it"
                        );
                    }
                };
            }
        };

        reader.accept(visitor, 0);
        return writer.toByteArray();
    }

    private record MemberMappings(
            Map<String, String> methods,
            Map<String, String> fields
    ) {
        String methodName(
                String owner,
                String name,
                String descriptor
        ) {
            return methods.getOrDefault(
                    owner + "/" + name + " " + descriptor,
                    name
            );
        }

        String fieldName(String owner, String name) {
            return fields.getOrDefault(owner + "/" + name, name);
        }
    }
}