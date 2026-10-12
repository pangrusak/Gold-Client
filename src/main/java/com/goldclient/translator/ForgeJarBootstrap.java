package com.goldclient.translator;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import java.util.zip.*;

/** Build-time discovery only: never loads or initializes the input mod. */
public final class ForgeJarBootstrap {
    private static final String MOD = "Lnet/minecraftforge/fml/common/Mod;";
    private static final String HANDLER = "Lnet/minecraftforge/fml/common/Mod$EventHandler;";
    private static final Map<String, String> PHASES = new LinkedHashMap<>();
    static {
        PHASES.put("preInit", "net/minecraftforge/fml/common/event/FMLPreInitializationEvent");
        PHASES.put("init", "net/minecraftforge/fml/common/event/FMLInitializationEvent");
        PHASES.put("postInit", "net/minecraftforge/fml/common/event/FMLPostInitializationEvent");
        PHASES.put("loadComplete", "net/minecraftforge/fml/common/event/FMLLoadCompleteEvent");
    }
    record Annotation(String descriptor, Map<String, Object> values) {}
    record Member(int access, String name, String descriptor, List<Annotation> annotations) {}
    record FieldInjection(String owner, String field, String descriptor, boolean isStatic, String kind) {}
    record Type(int access, String name, String parent, List<Member> fields,
                List<Member> methods, List<Annotation> annotations) {}
    public record Plan(String modId, String modClass, List<String> blockers,
                       String bootstrapSource, int classCount) {}

    public static void main(String[] args) throws Exception {
        // GOLD_FORGE_ACCESS_PROXY_V1
        // GOLD_FORGE_SUBSCRIBER_RUNTIME_V1
        if (args.length < 2 || args.length > 4) {
            throw new IllegalArgumentException("Usage: ForgeJarBootstrap <mod.jar> <output-directory> [verified-transformed-dependency.jar] [runtime-evidence.properties]");
        }
        Path input = Path.of(args[0]);
        Path output = Path.of(args[1]).toAbsolutePath().normalize();
        Files.createDirectories(output);
        Path generated = output.resolve("generated/gold/generated/ForgeModBootstrap.java");
        Files.deleteIfExists(generated);
        Path verifiedDependency = args.length >= 3 ? Path.of(args[2]) : null;
        Path runtimeEvidence = args.length == 4 ? Path.of(args[3]) : null;
        Plan plan = inspect(input, verifiedDependency, runtimeEvidence);
        String report = "Input: " + input.toRealPath() + "\n" +
                "Verified AT dependency: " + (verifiedDependency == null ? "not supplied" : verifiedDependency.toRealPath()) + "\n" +
                "Forge version evidence: " + (runtimeEvidence == null ? "not supplied" : ForgeRuntimeEvidence.verify(runtimeEvidence, verifiedDependency)) + "\n" +
                "Classes scanned: " + plan.classCount() + "\n" +
                "Mod ID: " + plan.modId() + "\n" +
                "Mod class: " + plan.modClass() + "\n" +
                "Bootstrap eligible: " + plan.blockers().isEmpty() + "\n" +
                "Forge runtime verified: false\nFull mod equivalence verified: false\n" +
                "Required host: per-mod original Forge events and loading context\n" +
                "Blockers:\n" + String.join("\n", plan.blockers()) + "\n";
        Files.writeString(output.resolve("forge-bootstrap-plan.txt"), report);
        System.out.print(report);
        if (!plan.blockers().isEmpty()) {
            throw new IllegalStateException("Bootstrap preparation blocked; see forge-bootstrap-plan.txt");
        }
        Files.createDirectories(generated.getParent());
        Files.writeString(generated, plan.bootstrapSource());
        System.out.println("Generated original-handler bootstrap: " + generated);
        System.out.println("This is source preparation, NOT browser mod execution.");
    }

    public static Plan inspect(Path jar) throws IOException {
        return inspect(jar, null);
    }
    public static Plan inspect(Path jar, Path verifiedDependency) throws IOException {
        return inspect(jar, verifiedDependency, null);
    }
    public static Plan inspect(Path jar, Path verifiedDependency, Path runtimeEvidence) throws IOException {
        if (verifiedDependency != null) VerifiedForgeAccess.verify(jar, verifiedDependency);
        if (runtimeEvidence != null) ForgeRuntimeEvidence.verify(runtimeEvidence, verifiedDependency);
        List<Type> types = new ArrayList<>();
        List<String> blockers = new ArrayList<>();
        try (JarFile input = new JarFile(jar.toFile(), false)) {
            Manifest manifest = input.getManifest();
            if (manifest != null) {
                for (String key : List.of("FMLCorePlugin", "FMLAT", "TweakClass", "MixinConfigs")) {
                    String value = manifest.getMainAttributes().getValue(key);
                    if (value != null && !value.isBlank() && !(key.equals("FMLAT") && verifiedDependency != null)) {
                        blockers.add("Loading transformation not implemented: " + key + "=" + value);
                    }
                }
            }
            List<JarEntry> entries = Collections.list(input.entries());
            entries.sort(Comparator.comparing(JarEntry::getName));
            for (JarEntry entry : entries) {
                if (!entry.isDirectory() && entry.getName().endsWith(".class")) {
                    try (InputStream stream = input.getInputStream(entry)) {
                        types.add(new Reader(stream).type());
                    }
                }
            }
        }
        List<Type> mods = types.stream().filter(t -> find(t.annotations(), MOD) != null).toList();
        if (mods.size() != 1) blockers.add("Exactly one @Mod class is currently required; found " + mods.size());
        for (Type type : types) {
            for (Member method : type.methods()) {
                if (find(method.annotations(), "Lnet/minecraftforge/fml/common/Mod$InstanceFactory;") != null) {
                    blockers.add("Instance factory not implemented: " + type.name() + "." + method.name());
                }
            }
        }
        if (mods.size() != 1) return new Plan("", "", List.copyOf(blockers), "", types.size());
        Type mod = mods.get(0);
        Annotation annotation = find(mod.annotations(), MOD);
        String id = Objects.toString(annotation.values().get("modid"), "");
        if (!id.matches("[a-z][a-z0-9_]{0,63}")) blockers.add("Mod ID requires unsupported validation: " + id);
        List<FieldInjection> injections = fieldInjections(types, mod, id, blockers);
        List<ProxyBinding> proxies = clientProxies(types, mod, id, blockers);
        String dependencies = Objects.toString(annotation.values().get("dependencies"), "");
        if (!dependencies.isBlank()) ForgeRuntimeEvidence.dependencies(dependencies, runtimeEvidence, verifiedDependency, blockers);
        List<SubscriberBinding> subscriberBindings = subscribers(types, id, blockers);
        if (Boolean.TRUE.equals(annotation.values().get("serverSideOnly"))) blockers.add("Server-only mod cannot be bootstrapped as client-only");
        if ((mod.access() & 1) == 0 || (mod.access() & 0x600) != 0) blockers.add("Mod entry must be a public concrete class");
        if (!"java/lang/Object".equals(mod.parent())) blockers.add("Inherited lifecycle discovery not implemented: " + mod.parent());
        if (!mod.name().matches("[A-Za-z_$][A-Za-z0-9_$]*(/[A-Za-z_$][A-Za-z0-9_$]*)*")) blockers.add("Mod class name requires unsupported Java-source escaping");
        if (mod.name().contains("$")) blockers.add("Nested mod entry source naming not implemented");
        if (mod.methods().stream().noneMatch(m -> m.name().equals("<init>") && m.descriptor().equals("()V") && (m.access() & 1) != 0)) {
            blockers.add("Public no-argument constructor required; custom construction not implemented");
        }
        Map<String, List<Member>> handlers = new LinkedHashMap<>();
        for (String phase : PHASES.keySet()) handlers.put(phase, new ArrayList<>());
        for (Member method : mod.methods()) {
            if (find(method.annotations(), HANDLER) == null) continue;
            String phase = null;
            for (var candidate : PHASES.entrySet()) {
                if (method.descriptor().equals("(L" + candidate.getValue() + ";)V")) phase = candidate.getKey();
            }
            if (phase == null || (method.access() & 1) == 0 || (method.access() & 0x408) != 0 ||
                    !method.name().matches("[A-Za-z_$][A-Za-z0-9_$]*")) {
                blockers.add("Lifecycle handler needs unsupported dispatch: " + method.name() + method.descriptor());
            } else handlers.get(phase).add(method);
        }
        // Methods are collected in class-file declaration order. Preserve that order
        // when dispatching multiple handlers for the same Forge lifecycle phase.
        return new Plan(id, mod.name().replace('/', '.'), List.copyOf(blockers),
                blockers.isEmpty() ? generate(id, mod, handlers, injections, proxies, subscriberBindings) : "", types.size());
    }

    // GOLD_PRIVATE_PROXY_BRIDGE_V1
    record SubscriberBinding(String owner, String method, String eventType, String priority, boolean receiveCanceled) {}
    private static List<SubscriberBinding> subscribers(List<Type> types, String modId, List<String> blockers) {
        List<SubscriberBinding> result=new ArrayList<>();
        for(Type type:types){
            Annotation registration=find(type.annotations(),"Lnet/minecraftforge/fml/common/Mod$EventBusSubscriber;");
            if(registration==null)continue;
            String ownerMod=Objects.toString(registration.values().get("modid"),"");
            if(!ownerMod.isEmpty()&&!ownerMod.equals(modId)){blockers.add("Cross-mod subscriber ownership not implemented: "+type.name());continue;}
            Object sides=registration.values().get("value");
            if(sides!=null){
                if(!(sides instanceof List<?> list)){blockers.add("Unsupported subscriber side metadata: "+type.name());continue;}
                boolean client=false,valid=true;
                for(Object side:list){
                    if(!(side instanceof List<?> pair)||pair.size()!=2||!"Lnet/minecraftforge/fml/relauncher/Side;".equals(pair.get(0))||!Set.of("CLIENT","SERVER").contains(pair.get(1))){valid=false;break;}
                    if("CLIENT".equals(pair.get(1)))client=true;
                }
                if(!valid){blockers.add("Unsupported subscriber side metadata: "+type.name());continue;}
                if(!client)continue;
            }
            if((type.access()&1)==0||type.name().contains("$")||!type.parent().equals("java/lang/Object")){
                blockers.add("Subscriber visibility/inheritance needs unsupported handling: "+type.name());continue;
            }
            for(Member method:type.methods()){
                Annotation annotation=find(method.annotations(),"Lnet/minecraftforge/fml/common/eventhandler/SubscribeEvent;");
                if(annotation==null)continue;
                var signature=java.util.regex.Pattern.compile("^\\(L([^;]+);\\)V$").matcher(method.descriptor());
                if(!signature.matches()||(method.access()&9)!=9||(method.access()&0x400)!=0||!method.name().matches("[A-Za-z_$][A-Za-z0-9_$]*")){
                    blockers.add("Static subscriber signature unsupported: "+type.name()+"."+method.name()+method.descriptor());continue;
                }
                String priority="NORMAL";Object configured=annotation.values().get("priority");
                if(configured!=null){
                    if(!(configured instanceof List<?> pair)||pair.size()!=2||!"Lnet/minecraftforge/fml/common/eventhandler/EventPriority;".equals(pair.get(0))||!Set.of("HIGHEST","HIGH","NORMAL","LOW","LOWEST").contains(pair.get(1))){
                        blockers.add("Subscriber priority metadata unsupported: "+type.name()+"."+method.name());continue;
                    }
                    priority=pair.get(1).toString();
                }
                result.add(new SubscriberBinding(type.name().replace('/','.'),method.name(),signature.group(1).replace('/','.').replace('$','.'),priority,Boolean.TRUE.equals(annotation.values().get("receiveCanceled"))));
            }
        }
        return result;
    }

    private static List<FieldInjection> fieldInjections(
            List<Type> types, Type mod, String modId, List<String> blockers) {
        List<FieldInjection> result = new ArrayList<>();
        String instanceAnnotation = "Lnet/minecraftforge/fml/common/Mod$Instance;";
        String metadataAnnotation = "Lnet/minecraftforge/fml/common/Mod$Metadata;";
        String metadataType = "Lnet/minecraftforge/fml/common/ModMetadata;";
        for (Type type : types) {
            for (Member field : type.fields()) {
                Annotation instance = find(field.annotations(), instanceAnnotation);
                Annotation metadata = find(field.annotations(), metadataAnnotation);
                if (instance == null && metadata == null) continue;
                if (instance != null && metadata != null) {
                    blockers.add("Conflicting Forge field injections: " + type.name() + "." + field.name());
                    continue;
                }
                if (!type.name().equals(mod.name())) {
                    blockers.add("Forge field injection outside the @Mod entry class is not implemented: "
                            + type.name() + "." + field.name());
                    continue;
                }
                if ((field.access() & 0x10) != 0) {
                    blockers.add("Forge injected fields must be non-final: 
                            + type.name() + "." + field.name());
                    continue;
                }
                String kind;
                if (instance != null) {
                    String requested = Objects.toString(instance.values().get("value"), "");
                    if (!requested.isEmpty() && !requested.equals(modId)) {
                        blockers.add("Cross-mod @Mod.Instance injection is not implemented: "
                                + type.name() + "." + field.name() + " -> " + requested);
                        continue;
                    }
                    if (!field.descriptor().equals("L" + mod.name() + ";")) {
                        blockers.add("@Mod.Instance field type must exactly match the mod entry class: "
                                + type.name() + "." + field.name() + " " + field.descriptor());
                        continue;
                    }
                    kind = "instance";
                } else {
                    if (!field.descriptor().equals(metadataType)) {
                        blockers.add("@Mod.Metadata field must use Forge ModMetadata: "
                                + type.name() + "." + field.name() + " " + field.descriptor());
                        continue;
                    }
                    kind = "metadata";
                }
                result.add(new FieldInjection(type.name().replace('/', '.'),
                        field.name(), field.descriptor(), (field.access() & 0x0008) != 0, kind));
            }
        }
        return result;
    }

    record ProxyBinding(String owner, String field, String implementation, String setter) {}
    private static List<ProxyBinding> clientProxies(List<Type> types, Type mod, String modId,
                                                   List<String> blockers) {
        Map<String, Type> indexed = new HashMap<>();
        for (Type type : types) indexed.put(type.name(), type);
        List<ProxyBinding> bindings = new ArrayList<>();
        for (Type owner : types) for (Member field : owner.fields()) {
            Annotation annotation = find(field.annotations(), "Lnet/minecraftforge/fml/common/SidedProxy;");
            if (annotation == null) continue;
            String implementation = Objects.toString(annotation.values().get("clientSide"), "").replace('.', '/');
            String requestedMod = Objects.toString(annotation.values().get("modId"), "");
            Type proxy = indexed.get(implementation);
            boolean valid = owner.name().equals(mod.name()) &&
                    ((field.access() & 0x18) == 0x8) &&
                    field.name().matches("[A-Za-z_$][A-Za-z0-9_$]*") &&
                    (requestedMod.isEmpty() || requestedMod.equals(modId)) &&
                    proxy != null && (proxy.access() & 1) != 0 && (proxy.access() & 0x600) == 0 &&
                    !implementation.contains("$") &&
                    implementation.matches("[A-Za-z_$][A-Za-z0-9_$]*(/[A-Za-z_$][A-Za-z0-9_$]*)*") &&
                    proxy.methods().stream().anyMatch(m -> m.name().equals("<init>") &&
                            m.descriptor().equals("()V") && (m.access() & 1) != 0);
            String setter = (field.access() & 1) != 0 ? "" : "$gold$setProxy$" + field.name();
            if (!setter.isEmpty()) {
                String setterDescriptor = "(" + field.descriptor() + ")V";
                valid &= owner.methods().stream().anyMatch(m -> m.name().equals(setter) &&
                        m.descriptor().equals(setterDescriptor) && (m.access() & 0x1009) == 0x1009);
            }
            String declared = field.descriptor();
            if (!declared.startsWith("L") || !declared.endsWith(";")) valid = false;
            else {
                declared = declared.substring(1, declared.length() - 1);
                String cursor = implementation; Set<String> visited = new HashSet<>();
                boolean assignable = false;
                while (visited.add(cursor)) {
                    if (cursor.equals(declared)) { assignable = true; break; }
                    Type current = indexed.get(cursor);
                    if (current == null) break;
                    cursor = current.parent();
                }
                valid &= assignable;
            }
            if (!valid) blockers.add("Client proxy requires unsupported injection/typing: " + owner.name() + "." + field.name());
            else bindings.add(new ProxyBinding(owner.name().replace('/', '.'), field.name(), implementation.replace('/', '.'), setter));
        }
        return bindings;
    }

    private static String generate(String id, Type mod, Map<String, List<Member>> handlers,
                                   List<FieldInjection> injections, List<ProxyBinding> proxies,
                                   List<SubscriberBinding> subscribers) {
        String type = mod.name().replace('/', '.');
        StringBuilder code = new StringBuilder("package gold.generated;\npublic final class ForgeModBootstrap {\n");
        code.append("  private static void injectField(Class<?> owner, Object target, String name, Object value) throws Exception {\\n")
            .append("    java.lang.reflect.Field field = owner.getDeclaredField(name);\\n")
            .append("    field.setAccessible(true);\\n")
            .append("    field.set(target, value);\\n")
            .append("  }\\n")
        code.append("  public interface SubscriberInvoker { void invoke(Object event) throws Exception; }\n")
            .append("  public interface Host {\n")
            .append("    void beforeConstruction(String modId);\n")
            .append("    void afterConstruction(String modId, Object originalModInstance);\n")
            .append("    Object metadataFor(String modId);\n")
            .append("    Object eventFor(String modId, String phase);\n")
            .append("    void afterPhase(String modId, String phase);\n")
            .append("    void registerStaticSubscriber(String modId, Class<?> owner, Class<?> eventType, String method, String priority, boolean receiveCanceled, SubscriberInvoker invoker);\n  }\n")
            .append("  private static Host host;\n  private static boolean started;\n")
            .append("  public static void bindHost(Host value) {\n")
            .append("    if (started || host != null || value == null) throw new IllegalStateException(\"Invalid lifecycle host binding\");\n")
            .append("    host = value;\n  }\n")
            .append("  public static void main(String[] args) throws Exception {\n")
            .append("    if (host == null) throw new IllegalStateException(\"Original Forge lifecycle host is required; refusing synthetic events\");\n")
            .append("    if (started) throw new IllegalStateException(\"Mod bootstrap already started\");\n")
            .append("    started = true;\n    host.beforeConstruction(\"").append(id).append("\");\n")
            .append("    ").append(type).append(" mod = new ").append(type).append("();\n");
        // GOLD_ORIGINAL_MOD_INSTANCE_HANDOFF_V1
        code.append("    host.afterConstruction(").append((char) 34).append(id)
            .append((char) 34).append(", mod);").append((char) 10);
        for (FieldInjection injection : injections) {
            String target = injection.isStatic() ? "null" : "mod";
            String value = injection.kind().equals("instance")
                    ? "mod"
                    : "((" + injection.descriptor().substring(1, injection.descriptor().length() - 1)
                            .replace('/', '.') + ") host.metadataFor(\\"" + id + "\\"))";
            code.append("    injectField(").append(injection.owner()).append(".class, ")
                    .append(target).append(", \\"").append(injection.field()).append("\\", ")
                    .append(value).append(");\\n");
        }
        for (ProxyBinding proxy : proxies) {
            if (proxy.setter().isEmpty()) {
                code.append("    ").append(proxy.owner()).append(".").append(proxy.field())
                    .append(" = new ").append(proxy.implementation()).append("();\n");
            } else {
                code.append("    ").append(proxy.owner()).append(".").append(proxy.setter())
                    .append("(new ").append(proxy.implementation()).append("());\n");
            }
        }
        for (SubscriberBinding subscriber : subscribers) {
            code.append("    host.registerStaticSubscriber(")
                .append((char) 34).append(id).append((char) 34)
                .append(", ").append(subscriber.owner()).append(".class, ")
                .append(subscriber.eventType()).append(".class, ")
                .append((char) 34).append(subscriber.method()).append((char) 34)
                .append(", ")
                .append((char) 34).append(subscriber.priority()).append((char) 34)
                .append(", ").append(subscriber.receiveCanceled())
                .append(", new SubscriberInvoker() {").append((char) 10)
                .append("      public void invoke(Object event) throws Exception { ")
                .append(subscriber.owner()).append(".")
                .append(subscriber.method()).append("((")
                .append(subscriber.eventType()).append(")event); }")
                .append((char) 10)
                .append("    });").append((char) 10);
        }
        for (var phase : handlers.entrySet()) {
            String phaseName = phase.getKey();
            if (!phase.getValue().isEmpty()) {
                String eventType = PHASES.get(phaseName).replace('/', '.');
                code.append("    Object ").append(phaseName).append(" = host.eventFor(\"").append(id)
                    .append("\", \"").append(phaseName).append("\");\n")
                    .append("    if (!( ").append(phaseName).append(" instanceof ").append(eventType)
                    .append(")) throw new IllegalStateException(\"Invalid original event for ").append(phaseName).append("\");\n");
                for (Member handler : phase.getValue()) {
                    code.append("    mod.").append(handler.name()).append("((").append(eventType).append(") ")
                        .append(phaseName).append(");\n");
                }
            }
            code.append("    host.afterPhase(\"").append(id).append("\", \"").append(phaseName).append("\");\n");
        }
        return code.append("  }\n}\n").toString();
    }

    private static Annotation find(List<Annotation> annotations, String descriptor) {
        return annotations.stream().filter(a -> a.descriptor().equals(descriptor)).findFirst().orElse(null);
    }

    private static final class Reader {
        final DataInputStream input;
        Object[] pool;
        Reader(InputStream stream) { input = new DataInputStream(stream); }
        String utf(int index) throws IOException {
            if (index <= 0 || index >= pool.length || !(pool[index] instanceof String)) throw new IOException("Invalid UTF constant");
            return (String) pool[index];
        }
        String className(int index) throws IOException { return index == 0 ? "" : utf((Integer) pool[index]); }
        Type type() throws IOException {
            if (input.readInt() != 0xCAFEBABE) throw new IOException("Invalid class file magic");
            input.readUnsignedShort(); input.readUnsignedShort();
            pool = new Object[input.readUnsignedShort()];
            for (int i = 1; i < pool.length; i++) {
                int tag = input.readUnsignedByte();
                switch (tag) {
                    case 1 -> pool[i] = input.readUTF();
                    case 3 -> pool[i] = input.readInt();
                    case 4 -> pool[i] = input.readFloat();
                    case 5 -> { pool[i] = input.readLong(); i++; }
                    case 6 -> { pool[i] = input.readDouble(); i++; }
                    case 7, 8, 16, 19, 20 -> pool[i] = input.readUnsignedShort();
                    case 9, 10, 11, 12, 17, 18 -> { input.readUnsignedShort(); input.readUnsignedShort(); }
                    case 15 -> { input.readUnsignedByte(); input.readUnsignedShort(); }
                    default -> throw new IOException("Unsupported constant pool tag: " + tag);
                }
            }
            int access = input.readUnsignedShort();
            String name = className(input.readUnsignedShort());
            String parent = className(input.readUnsignedShort());
            int interfaces = input.readUnsignedShort();
            for (int i = 0; i < interfaces; i++) input.readUnsignedShort();
            List<Member> fields = members();
            List<Member> methods = members();
            return new Type(access, name, parent, fields, methods, attributes(input));
        }
        List<Member> members() throws IOException {
            int count = input.readUnsignedShort();
            List<Member> members = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                int access = input.readUnsignedShort();
                String name = utf(input.readUnsignedShort());
                String descriptor = utf(input.readUnsignedShort());
                members.add(new Member(access, name, descriptor, attributes(input)));
            }
            return members;
        }
        List<Annotation> attributes(DataInputStream stream) throws IOException {
            List<Annotation> annotations = new ArrayList<>();
            int count = stream.readUnsignedShort();
            for (int i = 0; i < count; i++) {
                String name = utf(stream.readUnsignedShort());
                long length = Integer.toUnsignedLong(stream.readInt());
                if (name.equals("RuntimeVisibleAnnotations") || name.equals("RuntimeInvisibleAnnotations")) {
                    if (length > 16 * 1024 * 1024) throw new IOException("Annotation attribute exceeds preparation limit");
                    byte[] bytes = stream.readNBytes((int) length);
                    if (bytes.length != length) throw new EOFException();
                    try (var data = new DataInputStream(new ByteArrayInputStream(bytes))) {
                        int size = data.readUnsignedShort();
                        for (int j = 0; j < size; j++) annotations.add(annotation(data));
                        if (data.available() != 0) throw new IOException("Trailing annotation attribute bytes");
                    }
                } else stream.skipNBytes(length);
            }
            return annotations;
        }
        Annotation annotation(DataInputStream stream) throws IOException {
            String descriptor = utf(stream.readUnsignedShort());
            Map<String, Object> values = new LinkedHashMap<>();
            int count = stream.readUnsignedShort();
            for (int i = 0; i < count; i++) values.put(utf(stream.readUnsignedShort()), element(stream));
            return new Annotation(descriptor, values);
        }
        Object element(DataInputStream stream) throws IOException {
            int tag = stream.readUnsignedByte();
            return switch (tag) {
                case 's', 'c' -> utf(stream.readUnsignedShort());
                case 'Z' -> ((Integer) pool[stream.readUnsignedShort()]) != 0;
                case 'B', 'C', 'D', 'F', 'I', 'J', 'S' -> pool[stream.readUnsignedShort()];
                case 'e' -> List.of(utf(stream.readUnsignedShort()), utf(stream.readUnsignedShort()));
                case '@' -> annotation(stream);
                case '[' -> {
                    List<Object> array = new ArrayList<>();
                    int count = stream.readUnsignedShort();
                    for (int i = 0; i < count; i++) array.add(element(stream));
                    yield array;
                }
                default -> throw new IOException("Unsupported annotation element: " + tag);
            };
        }
    }
}
