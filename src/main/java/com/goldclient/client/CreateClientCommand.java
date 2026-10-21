package com.goldclient.client;

import com.goldclient.planner.LoaderCommand;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Creates one self-contained Eaglercraft 1.12.2 HTML client with an optional
 * set of translated Forge mods.
 */
public final class CreateClientCommand {
  private static final List<String> TOOL_RESOURCES = List.of(
      "build-self-contained.cjs",
      "eaglerforge/core/core.js",
      "eaglerforge/core/modgui.js",
      "eaglerforge/core/modloader.js",
      "eaglerforge/core/patches.js",
      "eaglerforge/core/postinit.js",
      "shared/eaglerforge-bindings.js",
      "shared/mcp-1.12.2-members.json.gz");

  private CreateClientCommand() {}

  public static int run(String[] args, PrintStream out, PrintStream err) {
    boolean json = false;
    try {
      Options options = parse(args, out);
      if (options == null) return 0;
      json = options.json;

      List<Path> inputs = validateInputs(options.inputs);
      List<InputRecord> inputRecords = recordInputs(inputs);
      Path output = options.output != null
          ? normalizeOutput(options.output)
          : defaultOutput(inputRecords);
      if (Files.exists(output) && !options.force) {
        throw new IOException("Output already exists (use --force to replace): " + output);
      }
      Files.createDirectories(output.getParent());

      Path cache = options.cacheDirectory.toAbsolutePath().normalize().resolve("clients");
      ClientSourceCache.CachedClient client =
          ClientSourceCache.ensure(cache, options.refreshClient, out);

      Path work = cache.resolve("work").resolve(UUID.randomUUID().toString());
      Files.createDirectories(work);
      try {
        Path bundleDirectory = work.resolve("bundle");
        LoaderResult loader = inputs.isEmpty()
            ? LoaderResult.empty()
            : runLoader(inputs, bundleDirectory, out, err, options.verbose);
        Path resources = writeResources(bundleDirectory, work.resolve("resources.json"));
        Path provenance = writeProvenance(work.resolve("provenance.json"),
            client, inputRecords, loader);
        Path tool = extractTool(work.resolve("tool"));

        Path bundle = bundleDirectory.resolve("gold-client-forge.js");
        List<String> command = new ArrayList<>();
        command.add(nodeExecutable());
        command.add(tool.resolve("build-self-contained.cjs").toString());
        command.add("--client");
        command.add(client.path().toString());
        command.add("--output");
        command.add(output.toString());
        command.add("--member-map");
        command.add(tool.resolve("shared/mcp-1.12.2-members.json.gz").toString());
        command.add("--adapter");
        command.add(tool.resolve("shared/eaglerforge-bindings.js").toString());
        command.add("--resources");
        command.add(resources.toString());
        command.add("--provenance");
        command.add(provenance.toString());
        if (Files.isRegularFile(bundle)) {
          command.add("--bundle");
          command.add(bundle.toString());
        }
        if (options.verbose) command.add("--verbose");

        String nodeOutput = runNode(command);
        if (options.verbose && !nodeOutput.isBlank()) {
          out.print(nodeOutput);
          if (!nodeOutput.endsWith("\n")) out.println();
        }

        long bytes = Files.size(output);
        String htmlSha256 = ClientSourceCache.sha256(output);
        boolean complete = loader.blockers.isEmpty() && loader.blockedClasses.isEmpty();
        printSummary(out, output, bytes, htmlSha256, client, inputRecords, loader, complete);

        if (json) {
          JsonObject result = resultJson(true, output, bytes, htmlSha256, client,
              inputRecords, loader, complete);
          out.println(new Gson().toJson(result));
        }
        return complete ? 0 : 2;
      } finally {
        if (options.keepWork) {
          out.println("Work directory: " + work);
        } else {
          deleteRecursively(work);
        }
      }
    } catch (Exception exception) {
      String message = exception.getMessage() == null
          ? exception.getClass().getSimpleName() : exception.getMessage();
      if (json) {
        JsonObject failure = new JsonObject();
        failure.addProperty("ok", false);
        failure.addProperty("error", message);
        out.println(new Gson().toJson(failure));
      }
      err.println("Create client failed: " + message);
      if (System.getenv("GOLD_CLIENT_DEBUG") != null) exception.printStackTrace(err);
      return 1;
    }
  }

  private static Options parse(String[] args, PrintStream out) {
    Options options = new Options();
    for (int i = 0; i < args.length; i++) {
      String arg = args[i];
      switch (arg) {
        case "--help" -> {
          usage(out);
          return null;
        }
        case "--input" -> {
          options.inputs.add(Path.of(value(args, ++i, arg)));
        }
        case "--output" -> options.output = Path.of(value(args, ++i, arg));
        case "--cache-dir" -> options.cacheDirectory = Path.of(value(args, ++i, arg));
        case "--refresh-client" -> options.refreshClient = true;
        case "--force" -> options.force = true;
        case "--json" -> options.json = true;
        case "--verbose" -> options.verbose = true;
        case "--keep-work" -> options.keepWork = true;
        default -> {
          if (arg.startsWith("--")) throw new IllegalArgumentException("Unknown option: " + arg);
          options.inputs.add(Path.of(arg));
        }
      }
    }
    return options;
  }

  private static String value(String[] args, int index, String flag) {
    if (index >= args.length) throw new IllegalArgumentException("Missing value for " + flag);
    return args[index];
  }

  private static List<Path> validateInputs(List<Path> raw) throws IOException {
    Set<Path> unique = new LinkedHashSet<>();
    for (Path input : raw) {
      Path normalized = input.toAbsolutePath().normalize();
      if (!Files.isRegularFile(normalized)) {
        throw new IOException("Input JAR does not exist: " + normalized);
      }
      if (!unique.add(normalized.toRealPath())) {
        throw new IllegalArgumentException("Input JAR supplied more than once: " + normalized);
      }
    }
    return List.copyOf(unique);
  }

  private static List<InputRecord> recordInputs(List<Path> inputs) throws IOException {
    List<InputRecord> records = new ArrayList<>();
    for (Path input : inputs) {
      records.add(new InputRecord(input, ClientSourceCache.sha256(input), Files.size(input)));
    }
    return List.copyOf(records);
  }

  private static Path normalizeOutput(Path output) throws IOException {
    Path normalized = output.toAbsolutePath().normalize();
    if (Files.isDirectory(normalized)
        || output.toString().endsWith("/") || output.toString().endsWith("\\")) {
      normalized = normalized.resolve("gold-client.html");
    }
    return normalized;
  }

  private static Path defaultOutput(List<InputRecord> inputs) {
    String name;
    String hashSeed;
    if (inputs.isEmpty()) {
      name = "vanilla";
      hashSeed = "vanilla";
    } else {
      name = inputs.stream()
          .map(record -> stripJar(record.path().getFileName().toString()))
          .collect(Collectors.joining("+"));
      name = name.replaceAll("[^A-Za-z0-9._+-]", "_");
      if (name.length() > 48) name = name.substring(0, 48);
      hashSeed = inputs.stream().map(InputRecord::sha256).collect(Collectors.joining(":"));
    }
    String shortHash = sha256Text(hashSeed).substring(0, 8).toLowerCase();
    return Path.of("clients", "build", name + "-" + shortHash, "gold-client.html")
        .toAbsolutePath().normalize();
  }

  private static String stripJar(String name) {
    return name.toLowerCase().endsWith(".jar")
        ? name.substring(0, name.length() - 4) : name;
  }

  private static LoaderResult runLoader(List<Path> inputs, Path output,
      PrintStream out, PrintStream err, boolean verbose) throws IOException {
    List<String> args = new ArrayList<>();
    for (Path input : inputs) {
      args.add("--input");
      args.add(input.toString());
    }
    args.add("--output");
    args.add(output.toString());
    args.add("--report");
    args.add(output.resolve("loader-plan.json").toString());
    args.add("--single-file");
    args.add("--compact");

    ByteArrayOutputStream capturedOut = new ByteArrayOutputStream();
    ByteArrayOutputStream capturedErr = new ByteArrayOutputStream();
    int code;
    try (PrintStream loaderOut = new PrintStream(capturedOut, true, StandardCharsets.UTF_8);
         PrintStream loaderErr = new PrintStream(capturedErr, true, StandardCharsets.UTF_8)) {
      code = LoaderCommand.run(args.toArray(String[]::new), loaderOut, loaderErr);
    }
    if (verbose) {
      out.print(capturedOut.toString(StandardCharsets.UTF_8));
      err.print(capturedErr.toString(StandardCharsets.UTF_8));
    }
    Path bundle = output.resolve("gold-client-forge.js");
    if (!Files.isRegularFile(bundle)) {
      String detail = capturedErr.toString(StandardCharsets.UTF_8).strip();
      throw new IOException("Loader did not produce a bundle"
          + (detail.isEmpty() ? "" : ": " + detail));
    }

    JsonObject plan = JsonParser.parseString(Files.readString(
        output.resolve("loader-plan.json"), StandardCharsets.UTF_8)).getAsJsonObject();
    JsonArray incomplete = plan.has("incompleteMethods")
        ? plan.getAsJsonArray("incompleteMethods") : new JsonArray();
    JsonArray blockers = plan.has("blockers")
        ? plan.getAsJsonArray("blockers") : new JsonArray();

    Map<String, List<String>> blocked = new LinkedHashMap<>();
    Path details = output.resolve("incomplete-method-details.json");
    if (Files.isRegularFile(details)) {
      JsonArray entries = JsonParser.parseString(Files.readString(
          details, StandardCharsets.UTF_8)).getAsJsonArray();
      for (JsonElement element : entries) {
        JsonObject entry = element.getAsJsonObject();
        String owner = getString(entry, "className", "<unknown>");
        String method = getString(entry, "methodName", "?");
        String descriptor = getString(entry, "descriptor", "");
        String reason = getString(entry, "reason", "no reason recorded");
        blocked.computeIfAbsent(owner, ignored -> new ArrayList<>())
            .add(method + descriptor + " - " + reason);
      }
    } else {
      for (JsonElement element : incomplete) {
        String text = element.getAsString();
        int split = text.indexOf('#');
        String owner = split < 0 ? text : text.substring(0, split);
        String method = split < 0 ? "?" : text.substring(split + 1);
        blocked.computeIfAbsent(owner, ignored -> new ArrayList<>()).add(method);
      }
    }
    List<String> blockerList = new ArrayList<>();
    for (JsonElement blocker : blockers) blockerList.add(blocker.getAsString());

    return new LoaderResult(
        getInt(plan, "classCount"), getInt(plan, "methodCount"),
        getInt(plan, "incompleteCount"), getInt(plan, "adapterRequiredCount"),
        getString(plan, "lifecycleStatus", "NOT_ASSESSED"),
        List.copyOf(blockerList),
        java.util.Collections.unmodifiableMap(new LinkedHashMap<>(blocked)), code);
  }

  private static Path writeResources(Path bundleDirectory, Path output) throws IOException {
    JsonObject files = new JsonObject();
    Path root = bundleDirectory.resolve("resources").normalize();
    if (Files.isDirectory(root)) {
      List<Path> entries;
      try (Stream<Path> stream = Files.walk(root)) {
        entries = stream.filter(Files::isRegularFile)
            .sorted(Comparator.comparing(path -> root.relativize(path).toString()))
            .toList();
      }
      Base64.Encoder encoder = Base64.getEncoder();
      for (Path entry : entries) {
        String name = root.relativize(entry).toString().replace('\\', '/');
        files.addProperty(name, encoder.encodeToString(Files.readAllBytes(entry)));
      }
    }
    JsonObject rootObject = new JsonObject();
    rootObject.addProperty("formatVersion", "gold-client-resources-v1");
    rootObject.add("files", files);
    Files.writeString(output, new Gson().toJson(rootObject), StandardCharsets.UTF_8);
    return output;
  }

  private static Path writeProvenance(Path output, ClientSourceCache.CachedClient client,
      List<InputRecord> inputs, LoaderResult loader) throws IOException {
    JsonObject provenance = new JsonObject();
    provenance.addProperty("formatVersion", "gold-client-self-contained-build-v1");
    provenance.addProperty("client", "Eaglercraft 1.12.2");
    provenance.addProperty("clientUrl", ClientSourceCache.CLIENT_URL);
    provenance.addProperty("clientSha256", client.sha256());
    provenance.addProperty("clientBytes", client.bytes());

    JsonArray mods = new JsonArray();
    for (InputRecord input : inputs) {
      JsonObject mod = new JsonObject();
      mod.addProperty("file", input.path().getFileName().toString());
      mod.addProperty("sha256", input.sha256());
      mod.addProperty("bytes", input.bytes());
      mods.add(mod);
    }
    provenance.add("inputs", mods);

    JsonObject translation = new JsonObject();
    translation.addProperty("classCount", loader.classCount());
    translation.addProperty("methodCount", loader.methodCount());
    translation.addProperty("incompleteCount", loader.incompleteCount());
    translation.addProperty("adapterRequiredCount", loader.adapterRequiredCount());
    translation.addProperty("lifecycleStatus", loader.lifecycleStatus());
    provenance.add("translation", translation);

    JsonArray blockers = new JsonArray();
    loader.blockers().forEach(blockers::add);
    provenance.add("blockers", blockers);

    JsonArray blockedClasses = new JsonArray();
    loader.blockedClasses().forEach((owner, methods) -> {
      JsonObject entry = new JsonObject();
      entry.addProperty("className", owner);
      JsonArray methodList = new JsonArray();
      methods.forEach(methodList::add);
      entry.add("methods", methodList);
      blockedClasses.add(entry);
    });
    provenance.add("blockedClasses", blockedClasses);
    boolean complete = loader.blockers().isEmpty() && loader.blockedClasses().isEmpty();
    provenance.addProperty("translationComplete", loader.incompleteCount() == 0);
    provenance.addProperty("ready", complete);
    Files.writeString(output,
        new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create().toJson(provenance)
            + System.lineSeparator(), StandardCharsets.UTF_8);
    return output;
  }

  private static Path extractTool(Path output) throws IOException {
    Files.createDirectories(output);
    for (String resource : TOOL_RESOURCES) {
      Path destination = output.resolve(resource);
      Files.createDirectories(destination.getParent());
      try (InputStream stream = CreateClientCommand.class.getResourceAsStream(
          "/com/goldclient/client/" + resource)) {
        if (stream == null) {
          throw new IOException("Packaged client builder resource is missing: " + resource);
        }
        Files.copy(stream, destination, StandardCopyOption.REPLACE_EXISTING);
      }
    }
    return output;
  }

  private static String nodeExecutable() {
    String configured = System.getenv("GOLD_CLIENT_NODE");
    return configured == null || configured.isBlank() ? "node" : configured;
  }

  private static String runNode(List<String> command) throws IOException, InterruptedException {
    Process process;
    try {
      process = new ProcessBuilder(command).redirectErrorStream(true).start();
    } catch (IOException missing) {
      throw new IOException("Node.js is required for --create-client. "
          + "Install Node 22 or set GOLD_CLIENT_NODE.", missing);
    }
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    Thread reader = new Thread(() -> {
      try (InputStream stream = process.getInputStream()) {
        stream.transferTo(output);
      } catch (IOException ignored) {
      }
    }, "gold-client-builder-output");
    reader.setDaemon(true);
    reader.start();
    if (!process.waitFor(10, TimeUnit.MINUTES)) {
      process.destroyForcibly();
      throw new IOException("Client builder timed out");
    }
    reader.join(5_000L);
    String text = output.toString(StandardCharsets.UTF_8);
    if (process.exitValue() != 0) {
      throw new IOException("Client builder failed: " + text.strip());
    }
    return text;
  }

  private static void printSummary(PrintStream out, Path output, long bytes, String sha256,
      ClientSourceCache.CachedClient client, List<InputRecord> inputs, LoaderResult loader,
      boolean complete) {
    out.println("Gold Client self-contained client");
    out.println("Client: Eaglercraft 1.12.2");
    out.println("Client SHA-256: " + client.sha256());
    out.println("Inputs: " + inputs.size());
    for (InputRecord input : inputs) {
      out.println("  - " + input.path().getFileName() + " (" + input.sha256() + ")");
    }
    if (!inputs.isEmpty()) {
      out.println("Classes: " + loader.classCount());
      out.println("Methods inspected: " + loader.methodCount());
      out.println("Incomplete methods: " + loader.incompleteCount());
      out.println("Adapter-required declarations: " + loader.adapterRequiredCount());
      out.println("Lifecycle: " + loader.lifecycleStatus());
    }
    if (complete) {
      out.println("Status: COMPLETE");
    } else {
      out.println("Status: INCOMPLETE (exit 2)");
      if (!loader.blockers().isEmpty()) {
        out.println("Transformation blockers:");
        for (String blocker : loader.blockers()) out.println("  - " + blocker);
      }
      if (!loader.blockedClasses().isEmpty()) {
        out.println("Classes that are not functional:");
        loader.blockedClasses().forEach((owner, methods) -> {
          out.println("  - " + owner);
          for (String method : methods) out.println("      " + method);
        });
      }
      out.println("The HTML was written for diagnostics. These classes are not working.");
    }
    out.println("Output: " + output);
    out.println("Output bytes: " + bytes);
    out.println("Output SHA-256: " + sha256);
  }

  private static JsonObject resultJson(boolean ok, Path output, long bytes, String sha256,
      ClientSourceCache.CachedClient client, List<InputRecord> inputs, LoaderResult loader,
      boolean complete) {
    JsonObject result = new JsonObject();
    result.addProperty("ok", ok);
    result.addProperty("complete", complete);
    result.addProperty("exitCode", complete ? 0 : 2);
    result.addProperty("output", output.toString());
    result.addProperty("bytes", bytes);
    result.addProperty("sha256", sha256);
    result.addProperty("clientSha256", client.sha256());
    result.addProperty("classCount", loader.classCount());
    result.addProperty("methodCount", loader.methodCount());
    result.addProperty("incompleteCount", loader.incompleteCount());
    result.addProperty("adapterRequiredCount", loader.adapterRequiredCount());
    result.addProperty("lifecycleStatus", loader.lifecycleStatus());
    JsonArray blockers = new JsonArray();
    loader.blockers().forEach(blockers::add);
    result.add("blockers", blockers);
    JsonArray blocked = new JsonArray();
    loader.blockedClasses().forEach((owner, methods) -> {
      JsonObject entry = new JsonObject();
      entry.addProperty("className", owner);
      JsonArray methodList = new JsonArray();
      methods.forEach(methodList::add);
      entry.add("methods", methodList);
      blocked.add(entry);
    });
    result.add("blockedClasses", blocked);
    JsonArray mods = new JsonArray();
    for (InputRecord input : inputs) {
      JsonObject mod = new JsonObject();
      mod.addProperty("file", input.path().getFileName().toString());
      mod.addProperty("sha256", input.sha256());
      mods.add(mod);
    }
    result.add("inputs", mods);
    return result;
  }

  private static void usage(PrintStream out) {
    out.println("""
        Usage: java -jar gold-client-1.jar --create-client [mod.jar ...] [options]

        Creates one self-contained Eaglercraft 1.12.2 HTML client.

        Options:
          --input <mod.jar>       Add a mod or dependency JAR. May be repeated.
          --output <file.html>    Output file (default: clients/build/<mods>/gold-client.html)
          --cache-dir <dir>       Client download cache (default: cache/clients)
          --refresh-client        Re-download the pinned client
          --force                 Replace an existing output file
          --json                  Print a machine-readable result
          --verbose               Print loader and client-builder output
          --keep-work             Keep temporary build files
          --help                  Show this help

        Exits 0 when complete, 2 when the HTML was written with blockers, and 1 on
        fatal errors.
        """);
  }

  private static String getString(JsonObject object, String name, String fallback) {
    return object.has(name) && !object.get(name).isJsonNull()
        ? object.get(name).getAsString() : fallback;
  }

  private static int getInt(JsonObject object, String name) {
    return object.has(name) && !object.get(name).isJsonNull()
        ? object.get(name).getAsInt() : 0;
  }

  private static String sha256Text(String value) {
    try {
      java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
      return java.util.HexFormat.of().formatHex(
          digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static void deleteRecursively(Path root) {
    if (root == null || !Files.exists(root)) return;
    try (Stream<Path> stream = Files.walk(root)) {
      for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
        try {
          Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
      }
    } catch (IOException ignored) {
    }
  }

  private static final class Options {
    final List<Path> inputs = new ArrayList<>();
    Path output;
    Path cacheDirectory = Path.of("cache");
    boolean refreshClient;
    boolean force;
    boolean json;
    boolean verbose;
    boolean keepWork;
  }

  private record InputRecord(Path path, String sha256, long bytes) {}

  private record LoaderResult(int classCount, int methodCount, int incompleteCount,
      int adapterRequiredCount, String lifecycleStatus, List<String> blockers,
      Map<String, List<String>> blockedClasses, int code) {
    static LoaderResult empty() {
      return new LoaderResult(0, 0, 0, 0, "NOT_ASSESSED", List.of(), Map.of(), 0);
    }
  }
}
