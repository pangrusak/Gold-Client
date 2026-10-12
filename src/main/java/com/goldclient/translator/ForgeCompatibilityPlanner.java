package com.goldclient.translator;

import com.goldclient.model.ClassModel;
import com.goldclient.model.IrOperationModel;
import com.goldclient.model.MethodModel;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Produces an actionable compatibility inventory for translated Forge mods.
 *
 * This planner deliberately separates bytecode translation from runtime compatibility:
 * seeing an API reference is not proof that the browser target implements that API.
 * Known Java/Forge/Minecraft references are grouped into adapter families, and every
 * unresolved external owner is retained in the report rather than silently discarded.
 */
public final class ForgeCompatibilityPlanner {
  private static final Pattern DESCRIPTOR_OWNER = Pattern.compile("L([^;]+);");
  private static final Pattern INTERNAL_OWNER = Pattern.compile(
      "(?:^|[^A-Za-z0-9_$])([a-zA-Z_$][\\w$]*(?:/[a-zA-Z_$][\\w$]*)+)(?=$|[^A-Za-z0-9_$])");

  public record ApiReference(String owner, String family, String supportLevel,
      Set<String> operations, int occurrenceCount, List<String> usedBy) {}
  public record CompatibilityPlan(String formatVersion, String target,
      int classCount, int methodCount, int completeMethodCount,
      int incompleteMethodCount, int stubbedMethodCount,
      List<ApiReference> references, List<String> blockingFamilies,
      List<String> translationWarnings, List<String> nextActions) {}

  private static final class MutableReference {
    final String owner;
    final String family;
    final String supportLevel;
    final Set<String> operations = new LinkedHashSet<>();
    final Set<String> usedBy = new LinkedHashSet<>();
    int count;

    MutableReference(String owner, String family, String supportLevel) {
      this.owner = owner;
      this.family = family;
      this.supportLevel = supportLevel;
    }

    ApiReference freeze() {
      return new ApiReference(owner, family, supportLevel, Set.copyOf(operations),
          count, List.copyOf(usedBy));
    }
  }

  public CompatibilityPlan plan(List<ClassModel> classes) {
    if (classes == null) throw new IllegalArgumentException("Classes must not be null.");
    Map<String, MutableReference> refs = new TreeMap<>();
    int methodCount = 0;
    int complete = 0;
    int incomplete = 0;
    int stubbed = 0;
    List<String> warnings = new ArrayList<>();

    for (ClassModel clazz : classes) {
      for (MethodModel method : clazz.methods()) {
        methodCount++;
        String methodKey = clazz.name().replace('/', '.') + "#" + method.name() + method.descriptor();
        String status = methodStatus(method);
        if ("complete".equals(status)) complete++;
        else if ("stubbed".equals(status)) stubbed++;
        else incomplete++;

        scanText(clazz.superName(), methodKey, "SUPER", refs);
        clazz.interfaces().forEach(owner -> scanText(owner, methodKey, "IMPLEMENTS", refs));
        scanDescriptor(method.descriptor(), methodKey, refs);
        method.exceptions().forEach(owner -> scanText(owner, methodKey, "THROWS", refs));

        for (IrOperationModel operation : method.intermediateRepresentation().operations()) {
          String kind = operation.kind() == null ? "UNKNOWN" : operation.kind();
          for (String operand : operation.operands()) {
            scanDescriptor(operand, methodKey, refs);
            scanText(operand, methodKey, kind, refs);
          }
        }
        if (!"complete".equals(status)) {
          warnings.add(methodKey + " is " + status + "; compatibility planning cannot repair missing bytecode.");
        }
      }
    }

    List<ApiReference> references = refs.values().stream()
        .map(MutableReference::freeze)
        .sorted(Comparator.comparing(ApiReference::family).thenComparing(ApiReference::owner))
        .toList();
    Set<String> families = new LinkedHashSet<>();
    references.stream().filter(ref -> "UNBOUND".equals(ref.supportLevel()))
        .map(ApiReference::family).forEach(families::add);
    List<String> blockers = families.stream().sorted().toList();
    List<String> actions = new ArrayList<>();
    if (references.stream().anyMatch(ref -> "MINECRAFT".equals(ref.family()))) {
      actions.add("Bind each referenced net.minecraft owner to a real Eaglercraft 1.12.2 implementation.");
    }
    if (references.stream().anyMatch(ref -> "FORGE".equals(ref.family()))) {
      actions.add("Implement the referenced Forge services and event classes; lifecycle discovery alone is not enough.");
    }
    if (references.stream().anyMatch(ref -> "UNBOUND".equals(ref.supportLevel()))) {
      actions.add("Review every UNBOUND owner and implement or explicitly exclude it in the target compatibility layer.");
    }
    if (stubbed > 0 || incomplete > 0) {
      actions.add("Resolve incomplete/stubbed methods before treating the export as runnable.");
    }
    if (actions.isEmpty()) actions.add("Run the generated module against the real target runtime and test behavior, not just imports.");
    return new CompatibilityPlan("gold-forge-compatibility-plan-v1", "Eaglercraft 1.12.2",
        classes.size(), methodCount, complete, incomplete, stubbed, references,
        blockers, List.copyOf(warnings), List.copyOf(actions));
  }

  public CompatibilityPlan export(List<ClassModel> classes, Path outputDirectory) throws IOException {
    if (outputDirectory == null) throw new IllegalArgumentException("Output directory must not be null.");
    Path root = outputDirectory.toAbsolutePath().normalize();
    Files.createDirectories(root);
    CompatibilityPlan result = plan(classes);
    Files.writeString(root.resolve("forge-compatibility-plan.json"),
        new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create().toJson(result)
            + System.lineSeparator(), StandardCharsets.UTF_8);
    Files.writeString(root.resolve("forge-compatibility-plan.txt"), summary(result),
        StandardCharsets.UTF_8);
    return result;
  }

  private static String summary(CompatibilityPlan plan) {
    StringBuilder out = new StringBuilder();
    out.append("Gold Client Forge compatibility plan\n")
        .append("Target: ").append(plan.target()).append('\n')
        .append("Classes: ").append(plan.classCount()).append('\n')
        .append("Methods: ").append(plan.methodCount()).append('\n')
        .append("Complete / incomplete / stubbed: ").append(plan.completeMethodCount()).append(" / ")
        .append(plan.incompleteMethodCount()).append(" / ").append(plan.stubbedMethodCount()).append('\n')
        .append("External API references: ").append(plan.references().size()).append('\n')
        .append("Unbound families: ").append(plan.blockingFamilies().isEmpty()
            ? "none detected" : String.join(", ", plan.blockingFamilies())).append("\n\n");
    out.append("API inventory\n");
    for (ApiReference ref : plan.references()) {
      out.append("- ").append(ref.owner()).append(" [").append(ref.family()).append("/")
          .append(ref.supportLevel()).append("] x").append(ref.occurrenceCount()).append('\n');
    }
    out.append("\nNext actions\n");
    for (String action : plan.nextActions()) out.append("- ").append(action).append('\n');
    out.append("\nThis is a reference inventory, not proof that the mod runs in Eaglercraft.\n");
    return out.toString();
  }

  private static String methodStatus(MethodModel method) {
    if ((method.access() & (0x0100 | 0x0400)) != 0
        || method.intermediateRepresentation().operations().isEmpty()) return "stubbed";
    return method.intermediateRepresentation().operations().stream()
        .anyMatch(op -> "UNKNOWN".equals(op.kind()) || "UNSUPPORTED".equals(op.kind()))
            ? "incomplete" : "complete";
  }

  private static void scanDescriptor(String text, String methodKey,
      Map<String, MutableReference> refs) {
    if (text == null || text.isEmpty()) return;
    Matcher matcher = DESCRIPTOR_OWNER.matcher(text);
    while (matcher.find()) addReference(matcher.group(1), methodKey, "DESCRIPTOR", refs);
  }

  private static void scanText(String text, String methodKey, String operation,
      Map<String, MutableReference> refs) {
    if (text == null || text.isEmpty()) return;
    String normalized = text.replace('.', '/');
    Matcher matcher = INTERNAL_OWNER.matcher(normalized);
    while (matcher.find()) {
      String owner = matcher.group(1);
      if (owner.indexOf('/') >= 0) addReference(owner, methodKey, operation, refs);
    }
    if (normalized.startsWith("net/") || normalized.startsWith("org/") || normalized.startsWith("com/")) {
      String owner = normalized.split("[^A-Za-z0-9_$/]", 2)[0];
      if (owner.indexOf('/') >= 0 && owner.indexOf(' ') < 0) addReference(owner, methodKey, operation, refs);
    }
  }

  private static void addReference(String owner, String methodKey, String operation,
      Map<String, MutableReference> refs) {
    if (owner == null || owner.isBlank() || owner.startsWith("[")) return;
    String normalizedOwner = owner.replace('.', '/');
    if (!normalizedOwner.contains("/")) return;
    String[] classification = classify(normalizedOwner);
    MutableReference ref = refs.computeIfAbsent(normalizedOwner,
        ignored -> new MutableReference(normalizedOwner, classification[0], classification[1]));
    ref.count++;
    ref.operations.add(operation);
    ref.usedBy.add(methodKey);
  }

  private static String[] classify(String owner) {
    if (owner.startsWith("net/minecraft/")) return new String[] {"MINECRAFT", "TARGET_BINDING_REQUIRED"};
    if (owner.startsWith("net/minecraftforge/")) return new String[] {"FORGE", "TARGET_BINDING_REQUIRED"};
    if (owner.startsWith("java/") || owner.startsWith("javax/")) {
      return new String[] {"JAVA_PLATFORM", "TEAVM_OR_RUNTIME_DEPENDENT"};
    }
    if (owner.startsWith("org/lwjgl/")) return new String[] {"GRAPHICS_INPUT", "TARGET_BINDING_REQUIRED"};
    if (owner.startsWith("org/apache/") || owner.startsWith("com/google/")) {
      return new String[] {"THIRD_PARTY", "DEPENDENCY_OR_ADAPTER_REQUIRED"};
    }
    if (owner.startsWith("com/") || owner.startsWith("org/")) {
      return new String[] {"MOD_OR_LIBRARY", "UNBOUND"};
    }
    return new String[] {"APPLICATION", "TRANSLATED_CLASS_EXPECTED"};
  }
}
