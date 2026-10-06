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
        if (entry.isDirectory()) continue;

        if (entry.getName().endsWith(".mixins.json")) {
          metadata.addMixin(entry.getName());
          continue;
        }

        if (!entry.getName().endsWith(".class")) continue;

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
      for (JsonElement group : ep.getAsJsonObject().values())
        addEntrypoints(group, m);
  }

  private void addEntrypoints(JsonElement value, ModMetadata m) {
    if (value == null || value.isJsonNull()) return;
    if (value.isJsonPrimitive()) {
      m.addEntrypoint(value.getAsString());
      return;
    }
    if (value.isJsonArray()) {
      for (JsonElement e : value.getAsJsonArray()) addEntrypoints(e, m);
      return;
    }
    if (value.isJsonObject()) {
      String entrypoint = text(value.getAsJsonObject(), "value");
      if (entrypoint != null) m.addEntrypoint(entrypoint);
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
    String section = "";
    String dependencySectionId = null;

    for (String raw : t.split("\\R")) {
      String l = stripTomlComment(raw).trim();
      if (l.isEmpty()) continue;

      if (l.startsWith("[[") && l.endsWith("]]")) {
        section = l.substring(2, l.length() - 2).trim();
        dependencySectionId = section.startsWith("dependencies.")
            ? section.substring("dependencies.".length())
            : null;
        continue;
      }

      int equals = l.indexOf('=');
      if (equals < 0) continue;

      String key = l.substring(0, equals).trim();
      String parsedValue = value(l);

      if ("mods".equals(section)) {
        if ("displayName".equals(key)) m.setName(parsedValue);
        else if ("version".equals(key)) m.setVersion(parsedValue);
      } else if (dependencySectionId != null) {
        if ("modId".equals(key)) {
          dependencySectionId = parsedValue;
          m.addDependency(parsedValue);
        } else if ("versionRange".equals(key) && "minecraft".equals(dependencySectionId)) {
          m.setMinecraftVersion(parsedValue);
        }
      } else if ("modLoader".equals(key)) {
        m.setLoader("Forge");
      }
    }
  }

  private static String stripTomlComment(String line) {
    boolean quoted = false;
    char quote = 0;
    for (int i = 0; i < line.length(); i++) {
      char c = line.charAt(i);
      if ((c == '"' || c == '\\'') && (i == 0 || line.charAt(i - 1) != '\\\\')) {
        if (!quoted) {
          quoted = true;
          quote = c;
        } else if (quote == c) {
          quoted = false;
        }
      } else if (c == '#' && !quoted) {
        return line.substring(0, i);
      }
    }
    return line;
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
          for (String i : classInterfaces) {
            interfaces.add(normalizeClassName(i));
            addReference(i, referencedClasses);
          }

        collect(name, minecraftApis, referencedClasses);
        addReference(classSignature, referencedClasses);
        collect(classSignature, minecraftApis, referencedClasses);
        addReference(superClass, referencedClasses);
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
        addReference(descriptor, referencedClasses);
        addReference(fieldSignature, referencedClasses);
        collect(descriptor, minecraftApis, referencedClasses);
        collect(fieldSignature, minecraftApis, referencedClasses);

        return new FieldVisitor(Opcodes.ASM9) {
          @Override
          public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            String annotation = annotationName(descriptor);
            if (annotation != null) fieldAnnotations.add(annotation);
            addReference(descriptor, referencedClasses);
            addReference(descriptor, referencedClasses);
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

        addReference(descriptor, referencedClasses);
        addReference(methodSignature, referencedClasses);
        collect(descriptor, minecraftApis, referencedClasses);
        collect(methodSignature, minecraftApis, referencedClasses);

        if (exceptions != null)
          for (String exception : exceptions) {
            methodExceptions.add(normalizeClassName(exception));
            addReference(exception, referencedClasses);
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
            addReference(type, referencedClasses);
            collect(type, minecraftApis, referencedClasses);
            methodReferences.add(normalizeClassName(type));
            instructions.add(instruction(opcode, type));
          }

          @Override public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
            addReference(owner, referencedClasses);
            addReference(descriptor, referencedClasses);
            addReference(owner, referencedClasses);
            addReference(descriptor, referencedClasses);
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
            addReference(descriptor, referencedClasses);
            collect(descriptor, minecraftApis, referencedClasses);
            addHandleReference(bootstrapMethodHandle, referencedClasses, minecraftApis);
            for (Object argument : bootstrapMethodArguments)
              addConstantReference(argument, referencedClasses, minecraftApis);
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
              addReference(type.getDescriptor(), referencedClasses);
              collect(type.getDescriptor(), minecraftApis, referencedClasses);
              methodReferences.add(normalizeClassName(type.getClassName()));
            } else if (value instanceof Handle handle) {
              addHandleReference(handle, referencedClasses, minecraftApis);
              methodReferences.add(normalizeClassName(handle.getOwner()));
            } else if (value instanceof ConstantDynamic dynamic) {
              addReference(dynamic.getDescriptor(), referencedClasses);
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
            addReference(descriptor, referencedClasses);
            collect(descriptor, minecraftApis, referencedClasses);
            instructions.add(instruction(Opcodes.MULTIANEWARRAY,
                descriptor, Integer.toString(dims)));
          }

          @Override public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
            addReference(type, referencedClasses);
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
            MethodModel rawMethod = new MethodModel(
                name, descriptor, methodSignature, methodAccess,
                methodExceptions, methodAnnotations, List.copyOf(methodReferences),
                instructions, tryCatchBlocks, maxStack[0], maxLocals[0],
                new ControlFlowModel(0, List.of()));
            ControlFlowModel controlFlow = new ControlFlowAnalyzer().analyze(rawMethod);
            MethodModel analyzedMethod = new MethodModel(
                rawMethod.name(), rawMethod.descriptor(), rawMethod.signature(),
                rawMethod.access(), rawMethod.exceptions(), rawMethod.annotations(),
                rawMethod.referencedClasses(), rawMethod.instructions(),
                rawMethod.tryCatchBlocks(), rawMethod.maxStack(), rawMethod.maxLocals(),
                controlFlow);
            methods.add(new MethodModel(
                analyzedMethod.name(), analyzedMethod.descriptor(), analyzedMethod.signature(),
                analyzedMethod.access(), analyzedMethod.exceptions(), analyzedMethod.annotations(),
                analyzedMethod.referencedClasses(), analyzedMethod.instructions(),
                analyzedMethod.tryCatchBlocks(), analyzedMethod.maxStack(), analyzedMethod.maxLocals(),
                controlFlow,
                new IntermediateRepresentationAnalyzer().analyze(analyzedMethod)));
          }
        };
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

  private static void addReference(String value, Set<String> referencedClasses) {
    if (value == null || value.isBlank()) return;

    try {
      if (value.indexOf('(') >= 0) {
        addTypeReference(Type.getMethodType(value), referencedClasses);
        return;
      }
      if (value.startsWith("L") || value.startsWith("[")) {
        addTypeReference(Type.getType(value), referencedClasses);
        return;
      }
    } catch (IllegalArgumentException ignored) {
      // Not a valid JVM descriptor; it may still be an internal class name.
    }

    if (value.indexOf('/') >= 0 && value.matches("[A-Za-z0-9_$/.]+"))
      referencedClasses.add(value.replace('/', '.'));
  }

  private static void addTypeReference(Type type, Set<String> referencedClasses) {
    if (type.getSort() == Type.METHOD) {
      for (Type argument : type.getArgumentTypes())
        addTypeReference(argument, referencedClasses);
      addTypeReference(type.getReturnType(), referencedClasses);
    } else if (type.getSort() == Type.ARRAY) {
      addTypeReference(type.getElementType(), referencedClasses);
    } else if (type.getSort() == Type.OBJECT) {
      referencedClasses.add(type.getClassName());
    }
  }

  private static void addConstantReference(Object value, Set<String> referencedClasses,
                                           Set<String> minecraftApis) {
    if (value instanceof Type type) {
      addReference(type.getDescriptor(), referencedClasses);
      collect(type.getDescriptor(), minecraftApis, referencedClasses);
    } else if (value instanceof Handle handle) {
      addHandleReference(handle, referencedClasses, minecraftApis);
    } else if (value instanceof ConstantDynamic dynamic) {
      addReference(dynamic.getDescriptor(), referencedClasses);
      collect(dynamic.getDescriptor(), minecraftApis, referencedClasses);
      addHandleReference(dynamic.getBootstrapMethod(), referencedClasses, minecraftApis);
      for (int i = 0; i < dynamic.getBootstrapMethodArgumentCount(); i++)
        addConstantReference(dynamic.getBootstrapMethodArgument(i), referencedClasses, minecraftApis);
    }
  }

  private static void addHandleReference(Handle handle, Set<String> referencedClasses,
                                         Set<String> minecraftApis) {
    if (handle == null) return;
    addReference(handle.getOwner(), referencedClasses);
    addReference(handle.getDesc(), referencedClasses);
    collect(handle.getOwner(), minecraftApis, referencedClasses);
    collect(handle.getDesc(), minecraftApis, referencedClasses);
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
