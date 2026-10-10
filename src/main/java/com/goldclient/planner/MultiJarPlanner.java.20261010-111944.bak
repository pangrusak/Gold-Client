package com.goldclient.planner;

import org.objectweb.asm.*;
import org.objectweb.asm.signature.SignatureReader;
import org.objectweb.asm.signature.SignatureVisitor;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Conservative class-level dependency planning, not method-level reachability.
 * Runtime inputs resolve dependencies but are not traversed or selected.
 * Reflection, service loading and resource dependencies require explicit roots
 * or a later profile-specific policy.
 * Multi-release JARs are rejected rather than silently indexed incorrectly.
 */
public final class MultiJarPlanner {
    public enum Role { TRANSLATION, RUNTIME }

    public record Input(Path jar, Role role) {
        public Input {
            Objects.requireNonNull(jar, "jar");
            Objects.requireNonNull(role, "role");
            jar = jar.toAbsolutePath().normalize();
        }
    }

    public record Origin(String jar, String entry, Role role) {}

    public record Plan(
            SortedMap<String, Origin> selected,
            SortedSet<String> runtimeClasses,
            SortedMap<String, SortedSet<String>> missing,
            SortedMap<String, List<Origin>> duplicates) {
        public boolean hasBlockingDiagnostics() {
            return !missing.isEmpty() || !duplicates.isEmpty();
        }
    }

    private record Indexed(Origin origin, SortedSet<String> references) {}

    public Plan plan(List<Input> inputs, Collection<String> roots,
                     Set<String> externalClasses) throws IOException {
        Objects.requireNonNull(inputs, "inputs");
        Objects.requireNonNull(roots, "roots");
        Objects.requireNonNull(externalClasses, "externalClasses");
        if (roots.isEmpty()) {
            throw new IllegalArgumentException("At least one root class is required");
        }

        SortedSet<String> external = new TreeSet<>();
        externalClasses.forEach(name -> external.add(normalize(name)));
        SortedMap<String, List<Indexed>> index = new TreeMap<>();
        Set<Path> seenInputs = new HashSet<>();

        for (Input input : inputs) {
            if (!seenInputs.add(input.jar())) {
                throw new IllegalArgumentException(
                        "Input JAR supplied more than once: " + input.jar());
            }
            try (ZipFile zip = new ZipFile(input.jar().toFile())) {
                List<? extends ZipEntry> entries =
                        Collections.list(zip.entries()).stream()
                                .sorted(Comparator.comparing(ZipEntry::getName))
                                .toList();
                for (ZipEntry entry : entries) {
                    String name = entry.getName();
                    if (entry.isDirectory() || !name.endsWith(".class")) continue;
                    if (name.startsWith("META-INF/versions/")) {
                        throw new IOException(
                                "Multi-release JAR is not supported yet: " + input.jar());
                    }
                    if (name.equals("module-info.class")) continue;

                    byte[] bytes;
                    try (var stream = zip.getInputStream(entry)) {
                        bytes = stream.readAllBytes();
                    }

                    try {
                        ClassReader reader = new ClassReader(bytes);
                        String className = reader.getClassName();
                        if (!name.equals(className + ".class")) {
                            throw new IOException(
                                    "Class entry/name mismatch: " + input.jar() + "!" + name);
                        }
                        SortedSet<String> refs = references(reader);
                        refs.remove(className);
                        Origin origin = new Origin(input.jar().toString(), name, input.role());
                        index.computeIfAbsent(className, key -> new ArrayList<>())
                                .add(new Indexed(origin, refs));
                    } catch (IllegalArgumentException exception) {
                        throw new IOException(
                                "Cannot analyze class: " + input.jar() + "!" + name,
                                exception);
                    }
                }
            }
        }

        SortedMap<String, List<Origin>> duplicates = new TreeMap<>();
        for (var entry : index.entrySet()) {
            if (entry.getValue().size() > 1) {
                List<Origin> origins = entry.getValue().stream()
                        .map(Indexed::origin)
                        .sorted(Comparator.comparing(Origin::jar)
                                .thenComparing(Origin::entry))
                        .toList();
                duplicates.put(entry.getKey(), origins);
            }
        }

        SortedMap<String, Origin> selected = new TreeMap<>();
        SortedSet<String> runtime = new TreeSet<>();
        SortedMap<String, SortedSet<String>> missing = new TreeMap<>();
        SortedMap<String, SortedSet<String>> requestedBy = new TreeMap<>();
        SortedSet<String> pending = new TreeSet<>();
        Set<String> visited = new HashSet<>();

        for (String root : roots) {
            String name = normalize(root);
            pending.add(name);
            requestedBy.computeIfAbsent(name, key -> new TreeSet<>()).add("<root>");
        }

        while (!pending.isEmpty()) {
            String name = pending.first();
            pending.remove(name);
            if (!visited.add(name)) continue;
            if (external.contains(name)) {
                runtime.add(name);
                continue;
            }

            List<Indexed> definitions = index.get(name);
            if (definitions == null) {
                missing.put(name, requestedBy.get(name));
                continue;
            }
            if (definitions.size() != 1) continue;

            Indexed definition = definitions.get(0);
            if (definition.origin().role() == Role.RUNTIME) {
                runtime.add(name);
                continue;
            }

            selected.put(name, definition.origin());
            for (String reference : definition.references()) {
                SortedSet<String> referrers = requestedBy.computeIfAbsent(
                        reference, key -> new TreeSet<>());
                referrers.add(name);
                if (!visited.contains(reference)) pending.add(reference);
            }
        }

        return new Plan(
                Collections.unmodifiableSortedMap(selected),
                Collections.unmodifiableSortedSet(runtime),
                freezeSets(missing),
                Collections.unmodifiableSortedMap(duplicates));
    }

    private static SortedMap<String, SortedSet<String>> freezeSets(
            SortedMap<String, SortedSet<String>> values) {
        SortedMap<String, SortedSet<String>> copy = new TreeMap<>();
        values.forEach((key, value) -> copy.put(key,
                Collections.unmodifiableSortedSet(new TreeSet<>(value))));
        return Collections.unmodifiableSortedMap(copy);
    }

    private static String normalize(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Class names must not be blank");
        }
        String result = name.replace('.', '/');
        if (result.startsWith("/") || result.endsWith("/")
                || result.contains("//") || result.contains(";")
                || result.contains("[") || result.contains("\\")
                || result.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException(
                    "Use a dotted or internal class name: " + name);
        }
        return result;
    }

    private static SortedSet<String> references(ClassReader reader) {
        SortedSet<String> refs = new TreeSet<>();

        class Collector {
            void type(Type type) {
                switch (type.getSort()) {
                    case Type.OBJECT -> refs.add(type.getInternalName());
                    case Type.ARRAY -> type(type.getElementType());
                    case Type.METHOD -> {
                        type(type.getReturnType());
                        for (Type argument : type.getArgumentTypes()) type(argument);
                    }
                    default -> { }
                }
            }

            void descriptor(String descriptor) {
                if (descriptor != null) type(Type.getType(descriptor));
            }

            void internal(String name) {
                if (name == null) return;
                if (name.startsWith("[")) descriptor(name);
                else refs.add(name);
            }

            void signature(String signature, boolean typeOnly) {
                if (signature == null) return;
                SignatureVisitor visitor = new SignatureVisitor(Opcodes.ASM9) {
                    private String current;

                    @Override
                    public void visitClassType(String name) {
                        current = name;
                        internal(name);
                    }

                    @Override
                    public void visitInnerClassType(String name) {
                        current = current + "$" + name;
                        internal(current);
                    }

                    @Override
                    public SignatureVisitor visitTypeArgument(char wildcard) {
                        return signatureVisitor();
                    }

                    private SignatureVisitor signatureVisitor() {
                        return new SignatureVisitor(Opcodes.ASM9) {
                            private String nested;
                            @Override public void visitClassType(String name) {
                                nested = name;
                                internal(name);
                            }
                            @Override public void visitInnerClassType(String name) {
                                nested = nested + "$" + name;
                                internal(nested);
                            }
                            @Override public SignatureVisitor visitTypeArgument(char w) {
                                return signatureVisitor();
                            }
                        };
                    }
                };
                SignatureReader parser = new SignatureReader(signature);
                if (typeOnly) parser.acceptType(visitor);
                else parser.accept(visitor);
            }

            void constant(Object value) {
                if (value instanceof Type t) type(t);
                else if (value instanceof Handle h) {
                    internal(h.getOwner());
                    descriptor(h.getDesc());
                } else if (value instanceof ConstantDynamic dynamic) {
                    descriptor(dynamic.getDescriptor());
                    constant(dynamic.getBootstrapMethod());
                    for (int i = 0; i < dynamic.getBootstrapMethodArgumentCount(); i++) {
                        constant(dynamic.getBootstrapMethodArgument(i));
                    }
                }
            }

            AnnotationVisitor annotation(String descriptor) {
                descriptor(descriptor);
                return new AnnotationVisitor(Opcodes.ASM9) {
                    @Override public void visit(String name, Object value) {
                        constant(value);
                    }
                    @Override public void visitEnum(String name, String desc, String value) {
                        descriptor(desc);
                    }
                    @Override public AnnotationVisitor visitAnnotation(String name, String desc) {
                        return annotation(desc);
                    }
                    @Override public AnnotationVisitor visitArray(String name) {
                        return annotation(null);
                    }
                };
            }
        }

        Collector c = new Collector();
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public void visit(int version, int access, String name, String signature,
                              String superName, String[] interfaces) {
                c.internal(superName);
                if (interfaces != null) for (String value : interfaces) c.internal(value);
                c.signature(signature, false);
            }

            @Override public void visitOuterClass(String owner, String name, String desc) {
                c.internal(owner);
                c.descriptor(desc);
            }

            @Override public void visitNestHost(String host) { c.internal(host); }

            @Override
            public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                return c.annotation(desc);
            }

            @Override
            public AnnotationVisitor visitTypeAnnotation(
                    int ref, TypePath path, String desc, boolean visible) {
                return c.annotation(desc);
            }

            @Override
            public RecordComponentVisitor visitRecordComponent(
                    String name, String desc, String signature) {
                c.descriptor(desc);
                c.signature(signature, true);
                return new RecordComponentVisitor(Opcodes.ASM9) {
                    @Override public AnnotationVisitor visitAnnotation(String d, boolean v) {
                        return c.annotation(d);
                    }
                    @Override public AnnotationVisitor visitTypeAnnotation(
                            int r, TypePath p, String d, boolean v) {
                        return c.annotation(d);
                    }
                };
            }

            @Override
            public FieldVisitor visitField(
                    int access, String name, String desc, String signature, Object value) {
                c.descriptor(desc);
                c.signature(signature, true);
                c.constant(value);
                return new FieldVisitor(Opcodes.ASM9) {
                    @Override public AnnotationVisitor visitAnnotation(String d, boolean v) {
                        return c.annotation(d);
                    }
                    @Override public AnnotationVisitor visitTypeAnnotation(
                            int r, TypePath p, String d, boolean v) {
                        return c.annotation(d);
                    }
                };
            }

            @Override
            public MethodVisitor visitMethod(
                    int access, String name, String desc, String signature, String[] exceptions) {
                c.descriptor(desc);
                c.signature(signature, false);
                if (exceptions != null) for (String exception : exceptions) c.internal(exception);

                return new MethodVisitor(Opcodes.ASM9) {
                    @Override public void visitTypeInsn(int opcode, String type) {
                        c.internal(type);
                    }
                    @Override public void visitFieldInsn(int op, String owner, String n, String d) {
                        c.internal(owner);
                        c.descriptor(d);
                    }
                    @Override public void visitMethodInsn(
                            int op, String owner, String n, String d, boolean itf) {
                        c.internal(owner);
                        c.descriptor(d);
                    }
                    @Override public void visitInvokeDynamicInsn(
                            String n, String d, Handle bootstrap, Object... arguments) {
                        c.descriptor(d);
                        c.constant(bootstrap);
                        for (Object argument : arguments) c.constant(argument);
                    }
                    @Override public void visitLdcInsn(Object value) { c.constant(value); }
                    @Override public void visitMultiANewArrayInsn(String d, int dimensions) {
                        c.descriptor(d);
                    }
                    @Override public void visitTryCatchBlock(
                            Label start, Label end, Label handler, String type) {
                        c.internal(type);
                    }
                    @Override public AnnotationVisitor visitAnnotationDefault() {
                        return c.annotation(null);
                    }
                    @Override public AnnotationVisitor visitAnnotation(String d, boolean v) {
                        return c.annotation(d);
                    }
                    @Override public AnnotationVisitor visitParameterAnnotation(
                            int parameter, String d, boolean v) {
                        return c.annotation(d);
                    }
                    @Override public AnnotationVisitor visitTypeAnnotation(
                            int r, TypePath p, String d, boolean v) {
                        return c.annotation(d);
                    }
                    @Override public AnnotationVisitor visitInsnAnnotation(
                            int r, TypePath p, String d, boolean v) {
                        return c.annotation(d);
                    }
                    @Override public AnnotationVisitor visitTryCatchAnnotation(
                            int r, TypePath p, String d, boolean v) {
                        return c.annotation(d);
                    }
                    @Override public AnnotationVisitor visitLocalVariableAnnotation(
                            int r, TypePath p, Label[] starts, Label[] ends,
                            int[] indexes, String d, boolean v) {
                        return c.annotation(d);
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

        return refs;
    }
}