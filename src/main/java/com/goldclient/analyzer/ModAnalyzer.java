package com.goldclient.analyzer;

import com.goldclient.model.*;
import com.google.gson.*;
import org.objectweb.asm.*;
import org.objectweb.asm.util.Printer;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import java.util.regex.*;

public final class ModAnalyzer {
  private static final Pattern MINECRAFT_CLASS =
      Pattern.compile("(?:^|[^A-Za-z0-9_])(?:L)?(net/minecraft/[A-Za-z0-9_$/]+)");

  public ModAnalysis analyze(Path path) throws IOException {
    if (!Files.isRegularFile(path)) throw new IOException("JAR does not exist: " + path);

    ModMetadata metadata = new ModMetadata();
    List<ClassModel> classes = new ArrayList<>();
    Set<String> apis = new LinkedHashSet<>();

    try (JarFile jar = new JarFile(path.toFile())) {
      readMetadata(jar, metadata);

      Enumeration<JarEntry> entries = jar.entries();
      while (entries.hasMoreElements()) {
        JarEntry entry = entries.nextElement();
        if (entry.isDirectory() || !entry.getName().endsWith(".class")) continue;

        try (InputStream in = jar.getInputStream(entry)) {
          ClassReader reader = new ClassReader(in);
          classes.add(inspect(reader, apis));
        }
      }
    }

    return new ModAnalysis(
        metadata,
        classes,
        classes.stream().mapToInt(c -> c.methods().size()).sum(),
        classes.stream().mapToInt(c -> c.fields().size()).sum(),
        List.copyOf(apis)
    );
  }

  private void readMetadata(JarFile jar, ModMetadata m) throws IOException {
    JarEntry fabric = jar.getJarEntry("fabric.mod.json");
    if (fabric != null)
      try (InputStream in = jar.getInputStream(fabric)) {
        readFabric(JsonParser.parseString(new String(in.readAllBytes())).getAsJsonObject(), m);
      }

    JarEntry forge = jar.getJarEntry("META-INF/mods.toml");
    if (forge != null) {
      m.setLoader("Forge");
      try (InputStream in = jar.getInputStream(forge)) {
        readToml(new String(in.readAllBytes()), m);
      }
    }

    JarEntry oldForge = jar.getJarEntry("mcmod.info");
    if (oldForge != null) {
      m.setLoader("Forge");
      try (InputStream in = jar.getInputStream(oldForge)) {
        readMcModInfo(new String(in.readAllBytes()), m);
      }
    }

    Manifest mf = jar.getManifest();
    if (mf != null) {
      String name = mf.getMainAttributes().getValue("Implementation-Title");
      String version = mf.getMainAttributes().getValue("Implementation-Version");
      if ("Unknown".equals(m.getName())) m.setName(name);
      if ("Unknown".equals(m.getVersion())) m.setVersion(version);
    }

    JarEntry mix = jar.getJarEntry("mixins.json");
    if (mix != null) m.addMixin(mix.getName());
  }

  private void readFabric(JsonObject r, ModMetadata m) {
    m.setName(text(r, "name"));
    m.setVersion(text(r, "version"));
    m.setLoader("Fabric");

    JsonElement d = r.get("depends");
    if (d != null && d.isJsonObject()) {
      d.getAsJsonObject().keySet().forEach(m::addDependency);
      String mc = dependencyText(d.getAsJsonObject().get("minecraft"));
      if (mc != null) m.setMinecraftVersion(mc);
    }

    JsonElement x = r.get("mixins");
    if (x != null && x.isJsonArray()) for (JsonElement e : x.getAsJsonArray()) {
      if (e.isJsonPrimitive()) m.addMixin(e.getAsString());
      else if (e.isJsonObject()) m.addMixin(text(e.getAsJsonObject(), "config"));
    }

    JsonElement ep = r.get("entrypoints");
    if (ep != null && ep.isJsonObject())
      for (JsonElement group : ep.getAsJsonObject().entrySet().stream().map(Map.Entry::getValue).toList())
        if (group.isJsonArray()) for (JsonElement e : group.getAsJsonArray()) {
          if (e.isJsonPrimitive()) m.addEntrypoint(e.getAsString());
          else if (e.isJsonObject()) m.addEntrypoint(text(e.getAsJsonObject(), "value"));
        }
  }

  private void readMcModInfo(String json, ModMetadata m) {
    JsonElement root = JsonParser.parseString(json);
    if (root.isJsonArray()) {
      for (JsonElement e : root.getAsJsonArray())
        if (e.isJsonObject()) {
          readMcModEntry(e.getAsJsonObject(), m);
          break;
        }
    } else if (root.isJsonObject()) {
      readMcModEntry(root.getAsJsonObject(), m);
    }
  }

  private void readMcModEntry(JsonObject o, ModMetadata m) {
    m.setName(text(o, "name"));
    m.setVersion(text(o, "version"));
    m.setMinecraftVersion(text(o, "mcversion"));

    JsonElement deps = o.get("dependencies");
    if (deps != null && deps.isJsonArray())
      for (JsonElement e : deps.getAsJsonArray())
        if (e.isJsonPrimitive()) m.addDependency(e.getAsString());
  }

  private void readToml(String t, ModMetadata m) {
    for (String raw : t.split("\\R")) {
      String l = raw.trim();
      if (l.startsWith("version")) m.setVersion(value(l));
      else if (l.startsWith("displayName")) m.setName(value(l));
      else if (l.startsWith("loaderVersion")) m.setLoader("Forge");
      else if (l.startsWith("modId")) m.addDependency("mod:" + value(l));
      else if (l.startsWith("minecraft")) m.setMinecraftVersion(value(l));
    }
  }

  private static String text(JsonObject o, String k) {
    JsonElement e = o.get(k);
    return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
  }

  private static String dependencyText(JsonElement e) {
    if (e == null || e.isJsonNull()) return null;
    if (e.isJsonPrimitive()) return e.getAsString();

    if (e.isJsonArray()) {
      List<String> values = new ArrayList<>();
      for (JsonElement item : e.getAsJsonArray())
        if (item.isJsonPrimitive()) values.add(item.getAsString());
      return values.isEmpty() ? null : String.join(", ", values);
    }

    return null;
  }

  private static String value(String l) {
    int i = l.indexOf('=');
    return i < 0 ? null : l.substring(i + 1).trim().replaceAll("^\"|\"$", "");
  }

  private ClassModel inspect(ClassReader reader, Set<String> minecraftApis) {
    List<FieldModel> fields = new ArrayList<>();
    List<MethodModel> methods = new ArrayList<>();
    List<String> annotations = new ArrayList<>();
    Set<String> referencedClasses = new LinkedHashSet<>();

    final String[] superName = {null};
    final List<String> interfaces = new ArrayList<>();
    final String[] signature = {null};
    final int[] access = {0};

    reader.accept(new ClassVisitor(Opcodes.ASM9) {
      @Override
      public void visit(int version, int classAccess, String name, String classSignature,
                        String superClass, String[] classInterfaces) {
        access[0] = classAccess;
        signature[0] = classSignature;
        superName[0] = normalizeClassName(superClass);

        if (classInterfaces != null)
          for (String i : classInterfaces) interfaces.add(normalizeClassName(i));

        collect(name, minecraftApis, referencedClasses);
        collect(classSignature, minecraftApis, referencedClasses);
        collect(superClass, minecraftApis, referencedClasses);

        if (classInterfaces != null)
          for (String i : classInterfaces) collect(i, minecraftApis, referencedClasses);
      }

      @Override
      public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
        String name = annotationName(descriptor);
        if (name != null) annotations.add(name);
        collect(descriptor, minecraftApis, referencedClasses);
        return null;
      }

      @Override
      public FieldVisitor visitField(int fieldAccess, String name, String descriptor,
                                     String fieldSignature, Object value) {
        List<String> fieldAnnotations = new ArrayList<>();
        collect(descriptor, minecraftApis, referencedClasses);
        collect(fieldSignature, minecraftApis, referencedClasses);

        return new FieldVisitor(Opcodes.ASM9) {
          @Override
          public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            String annotation = annotationName(descriptor);
            if (annotation != null) fieldAnnotations.add(annotation);
            collect(descriptor, minecraftApis, referencedClasses);
            return null;
          }

          @Override
          public void visitEnd() {
            fields.add(new FieldModel(
                name, descriptor, fieldSignature, fieldAccess, fieldAnnotations
            ));
          }
        };
      }

      @Override
      public MethodVisitor visitMethod(int methodAccess, String name, String descriptor,
                                       String methodSignature, String[] exceptions) {
        List<String> methodExceptions = new ArrayList<>();
        List<String> methodAnnotations = new ArrayList<>();
        Set<String> methodReferences = new LinkedHashSet<>();
        List<InstructionModel> instructions = new ArrayList<>();
        List<TryCatchModel> tryCatchBlocks = new ArrayList<>();
        Map<Label, String> labels = new IdentityHashMap<>();
        int[] nextLabel = {0};
        int[] maxStack = {0};
        int[] maxLocals = {0};

        collect(descriptor, minecraftApis, referencedClasses);
        collect(methodSignature, minecraftApis, referencedClasses);

        if (exceptions != null)
          for (String exception : exceptions) {
            methodExceptions.add(normalizeClassName(exception));
            collect(exception, minecraftApis, referencedClasses);
          }

        java.util.function.Function<Label, String> labelId =
            label -> labels.computeIfAbsent(label, ignored -> "L" + nextLabel[0]++);

        return new MethodVisitor(Opcodes.ASM9) {
          @Override
          public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            String annotation = annotationName(descriptor);
            if (annotation != null) methodAnnotations.add(annotation);
            collect(descriptor, minecraftApis, referencedClasses);
            return null;
          }

          @Override public void visitInsn(int opcode) {
            instructions.add(instruction(opcode));
          }

          @Override public void visitIntInsn(int opcode, int operand) {
            instructions.add(instruction(opcode, Integer.toString(operand)));
          }

          @Override public void visitVarInsn(int opcode, int var) {
            instructions.add(instruction(opcode, Integer.toString(var)));
          }

          @Override public void visitTypeInsn(int opcode, String type) {
            collect(type, minecraftApis, referencedClasses);
            methodReferences.add(normalizeClassName(type));
            instructions.add(instruction(opcode, type));
          }

          @Override public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
            collect(owner, minecraftApis, referencedClasses);
            collect(descriptor, minecraftApis, referencedClasses);
            methodReferences.add(normalizeClassName(owner));
            instructions.add(instruction(opcode, owner, name, descriptor));
          }

          @Override public void visitMethodInsn(int opcode, String owner, String name,
                                                String descriptor, boolean isInterface) {
            collect(owner, minecraftApis, referencedClasses);
            collect(descriptor, minecraftApis, referencedClasses);
            methodReferences.add(normalizeClassName(owner));
            instructions.add(instruction(opcode, owner, name, descriptor,
                Boolean.toString(isInterface)));
          }

          @Override public void visitInvokeDynamicInsn(String name, String descriptor,
                                                       Handle bootstrapMethodHandle,
                                                       Object... bootstrapMethodArguments) {
            collect(descriptor, minecraftApis, referencedClasses);
            List<String> operands = new ArrayList<>();
            operands.add(name);
            operands.add(descriptor);
            operands.add(handleText(bootstrapMethodHandle));
            for (Object argument : bootstrapMethodArguments) operands.add(constantText(argument));
            instructions.add(instruction(Opcodes.INVOKEDYNAMIC,
                operands.toArray(String[]::new)));
          }

          @Override public void visitJumpInsn(int opcode, Label label) {
            instructions.add(instruction(opcode, labelId.apply(label)));
          }

          @Override public void visitLabel(Label label) {
            instructions.add(new InstructionModel(-1, "LABEL",
                List.of(labelId.apply(label))));
          }

          @Override public void visitLdcInsn(Object value) {
            if (value instanceof Type type) {
              collect(type.getDescriptor(), minecraftApis, referencedClasses);
              methodReferences.add(normalizeClassName(type.getClassName()));
            } else if (value instanceof Handle handle) {
              collect(handle.getOwner(), minecraftApis, referencedClasses);
              methodReferences.add(normalizeClassName(handle.getOwner()));
            } else if (value instanceof ConstantDynamic dynamic) {
              collect(dynamic.getDescriptor(), minecraftApis, referencedClasses);
            }
            instructions.add(instruction(Opcodes.LDC, constantText(value)));
          }

          @Override public void visitIincInsn(int var, int increment) {
            instructions.add(instruction(Opcodes.IINC,
                Integer.toString(var), Integer.toString(increment)));
          }

          @Override public void visitTableSwitchInsn(int min, int max, Label dflt, Label... labels) {
            List<String> operands = new ArrayList<>();
            operands.add(Integer.toString(min));
            operands.add(Integer.toString(max));
            operands.add(labelId.apply(dflt));
            for (Label label : labels) operands.add(labelId.apply(label));
            instructions.add(instruction(Opcodes.TABLESWITCH,
                operands.toArray(String[]::new)));
          }

          @Override public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {
            List<String> operands = new ArrayList<>();
            operands.add(labelId.apply(dflt));
            for (int i = 0; i < keys.length; i++) {
              operands.add(Integer.toString(keys[i]));
              operands.add(labelId.apply(labels[i]));
            }
            instructions.add(instruction(Opcodes.LOOKUPSWITCH,
                operands.toArray(String[]::new)));
          }

          @Override public void visitMultiANewArrayInsn(String descriptor, int dims) {
            collect(descriptor, minecraftApis, referencedClasses);
            instructions.add(instruction(Opcodes.MULTIANEWARRAY,
                descriptor, Integer.toString(dims)));
          }

          @Override public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
            collect(type, minecraftApis, referencedClasses);
            tryCatchBlocks.add(new TryCatchModel(
                labelId.apply(start), labelId.apply(end), labelId.apply(handler),
                normalizeClassName(type)));
          }

          @Override public void visitMaxs(int stack, int locals) {
            maxStack[0] = stack;
            maxLocals[0] = locals;
          }

          @Override public void visitEnd() {
            methods.add(new MethodModel(
                name, descriptor, methodSignature, methodAccess,
                methodExceptions, methodAnnotations, List.copyOf(methodReferences),
                instructions, tryCatchBlocks, maxStack[0], maxLocals[0]));
          }
        };        };
      }
    }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

    return new ClassModel(
        reader.getClassName(),
        superName[0],
        interfaces,
        access[0],
        signature[0],
        annotations,
        fields,
        methods,
        List.copyOf(referencedClasses)
    );
  }

  private static InstructionModel instruction(int opcode, String... operands) {
    String name = opcode >= 0 && opcode < Printer.OPCODES.length
        ? Printer.OPCODES[opcode] : "UNKNOWN";
    return new InstructionModel(opcode, name, List.of(operands));
  }

  private static String constantText(Object value) {
    if (value == null) return "null";
    if (value instanceof Type type) return "Type(" + type.getDescriptor() + ")";
    if (value instanceof Handle handle) return handleText(handle);
    if (value instanceof ConstantDynamic dynamic)
      return "ConstantDynamic(" + dynamic.getName() + "," + dynamic.getDescriptor() + ")";
    return String.valueOf(value);
  }

  private static String handleText(Handle handle) {
    return "Handle(" + handle.getTag() + "," + handle.getOwner() + ","
        + handle.getName() + "," + handle.getDesc() + "," + handle.isInterface() + ")";
  }

  private void collect(String value, Set<String> minecraftApis, Set<String> referencedClasses) {
    if (value == null || !value.contains("net/minecraft/")) return;

    Matcher matcher = MINECRAFT_CLASS.matcher(value);
    while (matcher.find()) {
      String name = matcher.group(1).replace('/', '.');
      minecraftApis.add(name);
      referencedClasses.add(name);
    }
  }

  private static String normalizeClassName(String name) {
    return name == null ? null : name.replace('/', '.');
  }

  private static String annotationName(String descriptor) {
    if (descriptor == null || !descriptor.startsWith("L") || !descriptor.endsWith(";")) return null;
    return descriptor.substring(1, descriptor.length() - 1).replace('/', '.');
  }
}
