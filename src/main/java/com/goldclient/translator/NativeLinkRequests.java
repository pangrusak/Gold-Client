package com.goldclient.translator;

import com.google.gson.GsonBuilder;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.jar.JarFile;

/** Read-only request discovery; neither links nor executes the input JAR. */
public final class NativeLinkRequests {
    public record Request(String owner, String member, String descriptor, String operation,
                          List<String> callSites) {}
    public record Report(int schemaVersion, String scope, String inputSha256,
                         int classesScanned, List<Request> requests, List<String> resources,
                         List<String> dynamicSites, boolean nativeBindingsResolved,
                         boolean forgeRuntimeImplemented, boolean fullModEquivalenceVerified) {}
    private NativeLinkRequests() {}
    private static boolean external(String name) {
        return name.startsWith("net/minecraft/") || name.startsWith("net/minecraftforge/")
            || name.startsWith("net/lax1dude/");
    }
    private static String hash(Path file) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        try (var stream = Files.newInputStream(file)) {
            byte[] buffer = new byte[65536];
            int count;
            while ((count = stream.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
    public static Report inspect(Path supplied) throws Exception {
        Path input = supplied.toRealPath();
        String before = hash(input);
        var members = new TreeMap<String, Request>();
        var sites = new TreeMap<String, TreeSet<String>>();
        var resources = new TreeSet<String>();
        var dynamic = new TreeSet<String>();
        int count = 0;
        try (var jar = new JarFile(input.toFile(), false)) {
            var entries = Collections.list(jar.entries());
            entries.sort(java.util.Comparator.comparing(java.util.jar.JarEntry::getName));
            for (var entry : entries) {
                if (entry.isDirectory()) continue;
                if (!entry.getName().endsWith(".class")) { resources.add(entry.getName()); continue; }
                ClassReader reader;
                try (var stream = jar.getInputStream(entry)) { reader = new ClassReader(stream); }
                if (!entry.getName().equals(reader.getClassName() + ".class"))
                    throw new IOException("Unsupported class entry/name mismatch: " + entry.getName());
                count++;
                final String callingClass = reader.getClassName().replace('/', '.');
                reader.accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                               String signature, String[] exceptions) {
                        final String site = callingClass + "#" + name + descriptor;
                        return new MethodVisitor(Opcodes.ASM9) {
                            private void record(String owner, String member, String desc, String op) {
                                if (!external(owner)) return;
                                String dotted = owner.replace('/', '.');
                                String key = dotted + "#" + member + desc + ":" + op;
                                members.putIfAbsent(key, new Request(dotted, member, desc, op, List.of()));
                                sites.computeIfAbsent(key, ignored -> new TreeSet<>()).add(site);
                            }
                            @Override public void visitMethodInsn(int opcode, String owner, String method,
                                                                  String desc, boolean isInterface) {
                                record(owner, method, desc, switch (opcode) {
                                    case Opcodes.INVOKESTATIC -> "INVOKESTATIC";
                                    case Opcodes.INVOKESPECIAL -> "INVOKESPECIAL";
                                    case Opcodes.INVOKEINTERFACE -> "INVOKEINTERFACE";
                                    default -> "INVOKEVIRTUAL";
                                });
                                if (owner.startsWith("java/lang/reflect/") || owner.equals("java/lang/ClassLoader")
                                    || owner.equals("java/lang/Class") && method.equals("forName"))
                                    dynamic.add(site + " -> " + owner + "." + method + desc);
                            }
                            @Override public void visitFieldInsn(int opcode, String owner, String field, String desc) {
                                record(owner, field, desc, switch (opcode) {
                                    case Opcodes.GETSTATIC -> "GETSTATIC";
                                    case Opcodes.PUTSTATIC -> "PUTSTATIC";
                                    case Opcodes.GETFIELD -> "GETFIELD";
                                    default -> "PUTFIELD";
                                });
                            }
                            @Override public void visitInvokeDynamicInsn(String method, String desc,
                                    org.objectweb.asm.Handle bootstrap, Object... arguments) {
                                dynamic.add(site + " -> invokedynamic " + method + desc + " via " + bootstrap);
                            }
                        };
                    }
                }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            }
        }
        if (!before.equals(hash(input))) throw new IOException("Input changed during discovery");
        var requests = new ArrayList<Request>();
        for (var item : members.entrySet()) {
            var r = item.getValue();
            requests.add(new Request(r.owner(), r.member(), r.descriptor(), r.operation(),
                List.copyOf(sites.get(item.getKey()))));
        }
        return new Report(1, "Direct external method/field instructions only; not a complete dependency closure",
            before, count, List.copyOf(requests), List.copyOf(resources), List.copyOf(dynamic), false, false, false);
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Usage: NativeLinkRequests original-mod.jar output.json");
        Path input = Path.of(args[0]).toRealPath(), output = Path.of(args[1]).toAbsolutePath().normalize();
        if (output.equals(input) || Files.exists(output) && Files.isSameFile(input, output))
            throw new IOException("Output must not overwrite the original input");
        Report report = inspect(input);
        Files.createDirectories(output.getParent());
        Path temporary = Files.createTempFile(output.getParent(), ".native-requests-", ".json");
        try {
            Files.writeString(temporary, new GsonBuilder().setPrettyPrinting().create().toJson(report));
            try { Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally { Files.deleteIfExists(temporary); }
        System.out.println("Classes scanned: " + report.classesScanned());
        System.out.println("Unique direct external requests: " + report.requests().size());
        System.out.println("Discovery only; no native imports or mod execution verified.");
    }
}
