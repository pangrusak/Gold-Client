package com.goldclient.translator;

import com.goldclient.model.ClassModel;
import com.goldclient.model.IrOperationModel;
import com.goldclient.model.MethodModel;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
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
  private static final Pattern DESCRIPTOR_OWNER = Pattern.compile("L([A-Za-z_$][\\w$]*(?:/[A-Za-z_$][\\w$]*)+);");
  private static final Pattern INTERNAL_CLASS_NAME = Pattern.compile("[A-Za-z_$][\\w$]*(?:/[A-Za-z_$][\\w$]*)+");
  private static final Pattern INTERNAL_OWNER = Pattern.compile(
      "(?:^|[^A-Za-z0-9_$])([a-zA-Z_$][\\w$]*(?:/[a-zA-Z_$][\\w$]*)+)(?=$|[^A-Za-z0-9_$])");
  /** Field references arrive as owner/member paths; only member-shaped tails resolve to adapter owners. */
  private static final Pattern MEMBER_TAIL = Pattern.compile("[a-z_][A-Za-z0-9_]*|[A-Z][A-Z0-9_]*");
  /** Static calls carry a dotted class owner; instance calls carry a stack expression instead. */
  private static final Pattern STATIC_CALL_OWNER = Pattern.compile(
      "^((?:net|org|com|java|javax)(?:\\.[A-Za-z0-9_$]+)+)\\.[A-Za-z_$][A-Za-z0-9_$]*\\(");
  private static final String ADAPTER_MANIFEST_RESOURCE = "gold-client-adapters.json";

  public record ApiReference(String owner, String family, String supportLevel,
      Set<String> operations, int occurrenceCount, List<String> usedBy) {}

  private record AdapterManifest(String formatVersion, List<AdapterEntry> adapters) {}
  private record AdapterEntry(String owner, String kind) {}

  /** Internal owner names (slash-separated) served by built-in runtime adapters. */
  private final Set<String> runtimeAdapterOwners;

  public ForgeCompatibilityPlanner() {
    this(loadBuiltinAdapterOwners());
  }

  ForgeCompatibilityPlanner(Set<String> runtimeAdapterOwners) {
    this.runtimeAdapterOwners = Set.copyOf(runtimeAdapterOwners == null ? Set.of() : runtimeAdapterOwners);
  }

  public record CompatibilityPlan(String formatVersion, String target,
      int classCount, int methodCount, int completeMethodCount,
      int incompleteMethodCount, int stubbedMethodCount, int declarationOnlyMethodCount,
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
    Set<String> inputClasses = new LinkedHashSet<>();
    for (ClassModel clazz : classes) inputClasses.add(clazz.name());
    int methodCount = 0;
    int complete = 0;
    int incomplete = 0;
    int stubbed = 0;
    int declarationOnly = 0;
    List<String> warnings = new ArrayList<>();

    for (ClassModel clazz : classes) {
      for (MethodModel method : clazz.methods()) {
        methodCount++;
        String methodKey = clazz.name().replace('/', '.') + "#" + method.name() + method.descriptor();
        String status = methodStatus(method);
        if ("complete".equals(status)) complete++;
        else if ("declaration-only".equals(status)) declarationOnly++;
        else if ("stubbed".equals(status)) stubbed++;
        else incomplete++;

        scanClassName(clazz.superName(), methodKey, "SUPER", inputClasses, refs);
        clazz.interfaces().forEach(owner -> scanClassName(owner, methodKey, "IMPLEMENTS", inputClasses, refs));
        scanDescriptor(method.descriptor(), methodKey, inputClasses, refs);
        method.exceptions().forEach(owner -> scanClassName(owner, methodKey, "THROWS", inputClasses, refs));

        for (IrOperationModel operation : method.intermediateRepresentation().operations()) {
          String kind = operation.kind() == null ? "UNKNOWN" : operation.kind();
          for (String operand : operation.operands()) {
            scanDescriptor(operand, methodKey, inputClasses, refs);
            scanText(operand, methodKey, kind, inputClasses, refs);
          }
        }
        if ("stubbed".equals(status) || "incomplete".equals(status)) {
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
    long adapterBacked = references.stream()
        .filter(ref -> "RUNTIME_BUILTIN_ADAPTER".equals(ref.supportLevel()))
        .mapToInt(ApiReference::occurrenceCount).sum();
    if (references.stream().anyMatch(ref -> "MINECRAFT".equals(ref.family()))) {
      actions.add("Bind each referenced net.minecraft owner that a built-in adapter does not cover"
          + " to a real Eaglercraft 1.12.2 implementation.");
    }
    if (references.stream().anyMatch(ref -> "FORGE".equals(ref.family()))) {
      actions.add("Implement the referenced Forge services and event classes; lifecycle discovery alone is not enough.");
    }
    if (references.stream().anyMatch(ref -> "UNBOUND".equals(ref.supportLevel()))) {
      actions.add("Review every UNBOUND owner and implement or explicitly exclude it in the target compatibility layer.");
    }
    if (adapterBacked > 0) {
      actions.add("Built-in runtime adapters cover " + adapterBacked
          + " reference(s); coverage is owner-level, so verify the used members against the real target runtime.");
    }
    if (stubbed > 0 || incomplete > 0) {
      actions.add("Resolve incomplete/stubbed methods before treating the export as runnable.");
    }
    if (actions.isEmpty()) actions.add("Run the generated module against the real target runtime and test behavior, not just imports.");
    return new CompatibilityPlan("gold-forge-compatibility-plan-v1", "Eaglercraft 1.12.2",
        classes.size(), methodCount, complete, incomplete, stubbed, declarationOnly, references,
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
        .append("Complete / incomplete / stubbed / declaration-only: ")
        .append(plan.completeMethodCount()).append(" / ")
        .append(plan.incompleteMethodCount()).append(" / ").append(plan.stubbedMethodCount())
        .append(" / ").append(plan.declarationOnlyMethodCount()).append('\n')
        .append("External API references: ").append(plan.references().size()).append('\n')
        .append("Built-in adapter-backed references: ")
        .append(plan.references().stream()
            .filter(ref -> "RUNTIME_BUILTIN_ADAPTER".equals(ref.supportLevel()))
            .mapToInt(ApiReference::occurrenceCount).sum())
        .append(" in ")
        .append(plan.references().stream()
            .filter(ref -> "RUNTIME_BUILTIN_ADAPTER".equals(ref.supportLevel())).count())
        .append(" owner(s)\n")
        .append("Unbound families: ").append(plan.blockingFamilies().isEmpty()
            ? "none detected" : String.join(", ", plan.blockingFamilies())).append("\n\n");
    out.append("API inventory\n");
    for (ApiReference ref : plan.references()) {
      out.append("- ").append(ref.owner()).append(" [").append(ref.family()).append("/")
          .append(ref.supportLevel()).append("] x").append(ref.occurrenceCount()).append('\n');
    }
    out.append("\nNext actions\n");
    for (String action : plan.nextActions()) out.append("- ").append(action).append('\n');
    out.append("\nNote: the complete/incomplete counts above describe method-body recovery.\n")
        .append("JavaScript-lowering completeness (for example, merged catch clauses) is\n")
        .append("reported separately in loader-plan.json and incomplete-diagnostics.txt.\n");
    out.append("\nThis is a reference inventory, not proof that the mod runs in Eaglercraft.\n");
    return out.toString();
  }

  private static String methodStatus(MethodModel method) {
    if ((method.access() & 0x0400) != 0) return "declaration-only";
    if ((method.access() & 0x0100) != 0
        || method.intermediateRepresentation().operations().isEmpty()) return "stubbed";
    return method.intermediateRepresentation().operations().stream()
        .anyMatch(op -> "UNKNOWN".equals(op.kind()) || "UNSUPPORTED".equals(op.kind()))
            ? "incomplete" : "complete";
  }

  private void scanDescriptor(String text, String methodKey,
      Set<String> inputClasses, Map<String, MutableReference> refs) {
    if (text == null || text.isEmpty()) return;
    Matcher matcher = DESCRIPTOR_OWNER.matcher(text);
    while (matcher.find()) addReference(matcher.group(1), methodKey, "DESCRIPTOR", inputClasses, refs);
  }

  /** Scans an authoritative single internal name; obfuscated lowercase names must survive. */
  private void scanClassName(String text, String methodKey, String operation,
      Set<String> inputClasses, Map<String, MutableReference> refs) {
    if (text == null || text.isEmpty()) return;
    String candidate = text.trim();
    if (INTERNAL_CLASS_NAME.matcher(candidate).matches()) {
      addReference(candidate, methodKey, operation, inputClasses, refs);
    }
  }

  private void scanText(String text, String methodKey, String operation,
      Set<String> inputClasses, Map<String, MutableReference> refs) {
    if (text == null || text.isEmpty()) return;
    String candidate = text.trim();
    // Operand text also contains resource paths such as icons/arrow_next; only class-shaped
    // tokens (capitalized simple name) are treated as owners on this free-form path.
    if (INTERNAL_CLASS_NAME.matcher(candidate).matches() && looksLikeEmbeddedClassName(candidate)) {
      addReference(candidate, methodKey, operation, inputClasses, refs);
      return;
    }
    // Keep dotted method separators intact so owner extraction never absorbs a method name.
    Matcher matcher = INTERNAL_OWNER.matcher(text);
    while (matcher.find()) {
      String owner = matcher.group(1);
      if (owner.indexOf('/') >= 0 && looksLikeEmbeddedClassName(owner)) {
        addReference(owner, methodKey, operation, inputClasses, refs);
      }
    }
    // ASM metadata usually uses internal names; annotations and textual operands may use
    // binary names. Convert only the class-like prefix, never an entire method expression.
    if (candidate.matches("(?:net|org|com|java|javax)\\.[A-Za-z0-9_$.]+")
        && candidate.indexOf('(') < 0) {
      addReference(candidate.replace('.', '/'), methodKey, operation, inputClasses, refs);
      return;
    }
    // Static calls carry their owner before the method name. Instance calls carry a
    // receiver expression instead, so only a package-prefixed class-shaped path counts.
    Matcher staticCall = STATIC_CALL_OWNER.matcher(candidate);
    if (staticCall.find()) {
      String owner = staticCall.group(1).replace('.', '/');
      if (looksLikeEmbeddedClassName(owner)) {
        addReference(owner, methodKey, operation, inputClasses, refs);
      }
    }
  }

  private static boolean looksLikeEmbeddedClassName(String owner) {
    String simpleName = owner.substring(owner.lastIndexOf('/') + 1);
    if (simpleName.isEmpty()) return false;
    char first = simpleName.charAt(0);
    return Character.isUpperCase(first) || first == '$';
  }

  private void addReference(String owner, String methodKey, String operation,
      Set<String> inputClasses, Map<String, MutableReference> refs) {
    if (owner == null || owner.isBlank() || owner.startsWith("[")) return;
    String normalizedOwner = owner.replace('.', '/');
    if (!INTERNAL_CLASS_NAME.matcher(normalizedOwner).matches()) return;
    String[] classification = classify(normalizedOwner, inputClasses);
    MutableReference ref = refs.computeIfAbsent(normalizedOwner,
        ignored -> new MutableReference(normalizedOwner, classification[0], classification[1]));
    ref.count++;
    ref.operations.add(operation);
    ref.usedBy.add(methodKey);
  }

  private String[] classify(String owner, Set<String> inputClasses) {
    // Only claim a class is provided by the translation when it is actually in the input.
    if (inputClasses.contains(owner)) return new String[] {"APPLICATION", "TRANSLATED_CLASS_EXPECTED"};
    String[] family = classifyFamily(owner);
    // Built-in runtime adapters only change the support level, never the family.
    if (runtimeAdapterOwnerFor(owner) != null) return new String[] {family[0], "RUNTIME_BUILTIN_ADAPTER"};
    return family;
  }

  /**
   * Resolves an owner to a registered adapter owner. Field references are reported as
   * owner/member paths, so a member-shaped trailing segment strips back to its class;
   * title-cased tails (possible nested classes) and slash-free owners never strip.
   */
  private String runtimeAdapterOwnerFor(String owner) {
    if (runtimeAdapterOwners.contains(owner)) return owner;
    int lastSlash = owner.lastIndexOf('/');
    if (lastSlash <= 0 || lastSlash == owner.length() - 1) return null;
    if (!MEMBER_TAIL.matcher(owner.substring(lastSlash + 1)).matches()) return null;
    String base = owner.substring(0, lastSlash);
    return runtimeAdapterOwners.contains(base) ? base : null;
  }

  private static String[] classifyFamily(String owner) {
    if (owner.startsWith("net/minecraft/")) return new String[] {"MINECRAFT", "TARGET_BINDING_REQUIRED"};
    if (owner.startsWith("net/minecraftforge/")) return new String[] {"FORGE", "TARGET_BINDING_REQUIRED"};
    if (owner.startsWith("java/") || owner.startsWith("javax/")) {
      return new String[] {"JAVA_PLATFORM", "TEAVM_OR_RUNTIME_DEPENDENT"};
    }
    if (owner.startsWith("org/lwjgl/")) return new String[] {"GRAPHICS_INPUT", "TARGET_BINDING_REQUIRED"};
    if (owner.startsWith("org/apache/") || owner.startsWith("com/google/") || owner.startsWith("it/")) {
      return new String[] {"THIRD_PARTY", "DEPENDENCY_OR_ADAPTER_REQUIRED"};
    }
    return new String[] {"MOD_OR_LIBRARY", "UNBOUND"};
  }

  /**
   * Loads the runtime adapter manifest so the planner and gold-client-runtime.js agree
   * on which owners are served without host bindings.
   */
  private static Set<String> loadBuiltinAdapterOwners() {
    try (InputStream stream = ForgeCompatibilityPlanner.class.getResourceAsStream(ADAPTER_MANIFEST_RESOURCE)) {
      if (stream == null) {
        throw new IllegalStateException("Missing " + ADAPTER_MANIFEST_RESOURCE + " resource.");
      }
      AdapterManifest manifest = new Gson().fromJson(
          new InputStreamReader(stream, StandardCharsets.UTF_8), AdapterManifest.class);
      if (manifest == null || manifest.adapters() == null) {
        throw new IllegalStateException(ADAPTER_MANIFEST_RESOURCE + " has no adapters list.");
      }
      Set<String> owners = new LinkedHashSet<>();
      for (AdapterEntry entry : manifest.adapters()) {
        if (entry == null || entry.owner() == null || entry.owner().isBlank()) {
          throw new IllegalStateException(
              ADAPTER_MANIFEST_RESOURCE + " contains an adapter without an owner.");
        }
        owners.add(entry.owner().trim().replace('.', '/'));
      }
      return Set.copyOf(owners);
    } catch (IOException exception) {
      throw new UncheckedIOException("Failed to read " + ADAPTER_MANIFEST_RESOURCE + ".", exception);
    }
  }
}
