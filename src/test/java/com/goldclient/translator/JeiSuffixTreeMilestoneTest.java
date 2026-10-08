package com.goldclient.translator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.teavm.jso.JSExport;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class JeiSuffixTreeMilestoneTest {
  private static final String EXPECTED_JEI_SHA256 =
      "DAD60317D8FDF891AD7841ABF838564EA7CBD317EC85D92F57DCB8F55602124B";
  private static final String ENTRY_CLASS = "JeiSuffixTreeEntry";
  private static final String[] ORIGINAL_TREE_CLASSES = {
      "mezz/jei/suffixtree/GeneralizedSuffixTree.class",
      "mezz/jei/suffixtree/GeneralizedSuffixTree$Pair.class",
      "mezz/jei/suffixtree/Node.class",
      "mezz/jei/suffixtree/Edge.class",
      "mezz/jei/suffixtree/ISearchTree.class",
      "mezz/jei/util/Log.class"
  };
  private static final List<String> CORPUS = List.of(
      "minecraft:iron_ingot",
      "minecraft:iron_block",
      "minecraft:gold_ingot",
      "minecraft:oak_planks",
      "minecraft:caf\u00e9",
      "modded:enchanted_iron_sword",
      "modded:cr\u00e8me_brulee",
      "minecraft:stone",
      "minecraft:iron_ingot"
  );
  private static final List<String> QUERIES = List.of(
      "iron",
      "ingot",
      "gold",
      "oak",
      "stone",
      "missing",
      "",
      "IRON",
      "\u00e9",
      "caf\u00e9",
      "cr\u00e8me",
      "after_trim"
  );
  private static final String GENERATED_ENTRY_SOURCE = """
      import it.unimi.dsi.fastutil.ints.IntSet;
      import mezz.jei.suffixtree.GeneralizedSuffixTree;
      import org.teavm.jso.JSExport;
      import java.util.Arrays;

      public final class JeiSuffixTreeEntry {
          private static final String[] CORPUS = {
              "minecraft:iron_ingot",
              "minecraft:iron_block",
              "minecraft:gold_ingot",
              "minecraft:oak_planks",
              "minecraft:caf\\u00e9",
              "modded:enchanted_iron_sword",
              "modded:cr\\u00e8me_brulee",
              "minecraft:stone",
              "minecraft:iron_ingot"
          };
          private static GeneralizedSuffixTree tree;

          private JeiSuffixTreeEntry() {
          }

          public static void main(String[] args) {
              GeneralizedSuffixTree index = new GeneralizedSuffixTree();
              for (int i = 0; i < CORPUS.length; i++) {
                  index.put(CORPUS[i], i);
              }
              index.trimToSize();
              index.put("modded:after_trim_item", CORPUS.length);
              tree = index;
          }

          @JSExport
          public static String goldClientJeiSearch(String query) {
              if (tree == null) {
                  throw new IllegalStateException("JEI suffix-tree entry was not initialized");
              }
              IntSet matches = tree.search(query);
              int[] indices = matches.toIntArray();
              Arrays.sort(indices);
              return Arrays.toString(indices);
          }

          @JSExport
          public static void goldClientJeiReset() {
              tree = new GeneralizedSuffixTree();
          }

          @JSExport
          public static void goldClientJeiIndex(String text, int index) {
              if (tree == null) {
                  throw new IllegalStateException("JEI suffix-tree entry was not initialized");
              }
              tree.put(text, index);
          }

          @JSExport
          public static void goldClientJeiTrim() {
              if (tree == null) {
                  throw new IllegalStateException("JEI suffix-tree entry was not initialized");
              }
              tree.trimToSize();
          }
      }
      """;
  private static final String NODE_COMPARISON_SCRIPT = """
      const assert = require("node:assert/strict");
      const fs = require("node:fs");
      const path = require("node:path");

      const mod = require(path.resolve(process.argv[2]));
      const testData = JSON.parse(fs.readFileSync(process.argv[3], "utf8"));
      assert.equal(typeof mod.main, "function");
      assert.equal(typeof mod.goldClientJeiSearch, "function");
      assert.equal(typeof mod.goldClientJeiReset, "function");
      assert.equal(typeof mod.goldClientJeiIndex, "function");
      assert.equal(typeof mod.goldClientJeiTrim, "function");
      const originalError = console.error;
      const originalWarn = console.warn;
      const warnings = [];
      console.error = console.warn = (...args) => warnings.push(args.join(" "));
      mod.main([], error => {
          if (error) throw error;
      });
      const actual = testData.queries.map(query => mod.goldClientJeiSearch(query));
      console.error = originalError;
      console.warn = originalWarn;
      assert.deepEqual(actual, testData.expected);
      assert.deepEqual(warnings, testData.warnings);

      const dynamicWarnings = [];
      console.error = console.warn = (...args) => dynamicWarnings.push(args.join(" "));
      mod.goldClientJeiReset();
      testData.corpus.forEach((text, index) => mod.goldClientJeiIndex(text, index));
      mod.goldClientJeiTrim();
      mod.goldClientJeiIndex("modded:after_trim_item", testData.corpus.length);
      const dynamicActual = testData.queries.map(query => mod.goldClientJeiSearch(query));
      console.error = originalError;
      console.warn = originalWarn;
      assert.deepEqual(dynamicActual, testData.expected);
      assert.deepEqual(dynamicWarnings, testData.warnings);
      console.log(`Translated JEI suffix-tree JVM comparison passed (${actual.length} fixture queries, trim/reinsert exercised).`);

      if (process.argv[4]) {
          const realData = JSON.parse(fs.readFileSync(process.argv[4], "utf8"));
          const realWarnings = [];
          console.error = console.warn = (...args) => realWarnings.push(args.join(" "));
          mod.goldClientJeiReset();
          realData.corpus.forEach((text, index) => mod.goldClientJeiIndex(text, index));
          mod.goldClientJeiTrim();
          mod.goldClientJeiIndex(realData.mutationText, realData.corpus.length);
          const realActual = realData.queries.map(query => mod.goldClientJeiSearch(query));
          console.error = originalError;
          console.warn = originalWarn;
          assert.deepEqual(realActual, realData.expected);
          assert.deepEqual(realWarnings, realData.warnings);
          console.log(`Translated JEI suffix-tree matches original JVM on ${realData.corpus.length} client items (${realActual.length} queries, trim/reinsert exercised).`);
      }
      """;

  @Test
  void originalSuffixTreeMatchesItsTranslatedOutput(@TempDir Path temp) throws Exception {
    Path repository = Path.of("").toAbsolutePath();
    Path jeiJar = repository.resolve("test-mods/jei1.12.2.jar");
    assertTrue(Files.isRegularFile(jeiJar),
        "The exact external JEI input is required at test-mods/jei1.12.2.jar");
    assertEquals(EXPECTED_JEI_SHA256, sha256(jeiJar),
        "Refusing to test a different JEI JAR");

    Path generatedRoot = repository.resolve("target/jei-suffix-tree");
    Path generatedSource = generatedRoot.resolve("generated-src/JeiSuffixTreeEntry.java");
    Path generatedClasses = generatedRoot.resolve("generated-classes");
    Path stagedJar = generatedRoot.resolve("jei-original-suffix-tree-slice.jar");
    Path outputDirectory = generatedRoot.resolve("translated");
    Files.createDirectories(generatedSource.getParent());
    Files.createDirectories(generatedClasses);
    Files.writeString(generatedSource, GENERATED_ENTRY_SOURCE, StandardCharsets.UTF_8);

    compileEntry(repository, jeiJar, generatedSource, generatedClasses);
    stageOriginalClasses(jeiJar, generatedClasses, stagedJar);

    JvmRunResult expected = runOriginalJvm(jeiJar, generatedClasses);
    TeaVmTranslator.translate(stagedJar, outputDirectory, ENTRY_CLASS);
    Path translatedJs = outputDirectory.resolve("eagler-mod.js");
    String translatedCode = Files.readString(translatedJs);
    assertTrue(translatedCode.contains("GeneralizedSuffixTree"),
        "Generated output should retain the original JEI class identity");
    assertTrue(translatedCode.contains("goldClientJeiSearch"),
        "Generated output should export the test entry around original JEI calls");

    Path comparisonData = generatedRoot.resolve("comparison.json");
    Path comparisonScript = generatedRoot.resolve("compare.cjs");
    Files.writeString(comparisonData, comparisonJson(expected), StandardCharsets.UTF_8);
    Files.writeString(comparisonScript, NODE_COMPARISON_SCRIPT, StandardCharsets.UTF_8);
    Path realComparisonData = generatedRoot.resolve("real-corpus-comparison.json");
    boolean hasRealCorpus = writeRealCorpusComparison(
        repository, jeiJar, generatedClasses, generatedRoot, realComparisonData);
    List<String> nodeArguments = new ArrayList<>(List.of(
        "node", comparisonScript.toString(), translatedJs.toString(), comparisonData.toString()));
    if (hasRealCorpus) {
      nodeArguments.add(realComparisonData.toString());
    }
    Process process = new ProcessBuilder(
        nodeArguments)
        .directory(repository.toFile())
        .redirectErrorStream(true)
        .start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    int exitCode = process.waitFor();
    assertEquals(0, exitCode, "Translated output comparison failed:\n" + output);
    assertTrue(output.contains("JVM comparison passed"), output);
    if (hasRealCorpus) {
      assertTrue(output.contains("matches original JVM on"), output);
    }
  }

  private static void compileEntry(
      Path repository, Path jeiJar, Path source, Path classes) throws IOException {
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    if (compiler == null) {
      fail("A JDK compiler is required to compile the generated call harness");
    }
    Path fastutil = codeSourcePath(it.unimi.dsi.fastutil.ints.IntSet.class);
    Path teaVmJso = codeSourcePath(JSExport.class);
    String classpath = String.join(System.getProperty("path.separator"),
        jeiJar.toString(), fastutil.toString(), teaVmJso.toString());
    Path compatSource = repository.resolve(
        "client-profiles/eaglercraft-1.12-js/compat/org/apache/logging/log4j");
    List<String> arguments = new ArrayList<>(List.of(
        "--release", "17",
        "-encoding", "UTF-8",
        "-classpath", classpath,
        "-d", classes.toString()));
    try (var files = Files.list(compatSource)) {
      files.filter(p -> p.toString().endsWith(".java"))
          .sorted()
          .map(Path::toString)
          .forEach(arguments::add);
    }
    arguments.add(source.toString());
    int result = compiler.run(null, null, null, arguments.toArray(String[]::new));
    assertEquals(0, result, "Could not compile the temporary TeaVM entry harness");
  }

  private static Path codeSourcePath(Class<?> type) throws IOException {
    try {
      URL location = type.getProtectionDomain().getCodeSource().getLocation();
      return Path.of(location.toURI());
    } catch (Exception e) {
      throw new IOException("Could not locate runtime dependency " + type.getName(), e);
    }
  }

  private static void stageOriginalClasses(
      Path jeiJar, Path generatedClasses, Path stagedJar) throws IOException {
    try (ZipFile source = new ZipFile(jeiJar.toFile());
         OutputStream output = Files.newOutputStream(stagedJar);
         JarOutputStream staged = new JarOutputStream(output)) {
      for (String name : ORIGINAL_TREE_CLASSES) {
        ZipEntry original = source.getEntry(name);
        if (original == null) {
          throw new IOException("JEI input is missing original class " + name);
        }
        byte[] originalBytes;
        try (var stream = source.getInputStream(original)) {
          originalBytes = stream.readAllBytes();
        }
        JarEntry copy = new JarEntry(name);
        staged.putNextEntry(copy);
        staged.write(originalBytes);
        staged.closeEntry();
      }
      Path entryClass = generatedClasses.resolve(ENTRY_CLASS + ".class");
      staged.putNextEntry(new JarEntry(ENTRY_CLASS + ".class"));
      Files.copy(entryClass, staged);
      staged.closeEntry();

      Path loggerClasses = generatedClasses.resolve("org/apache/logging/log4j");
      try (var files = Files.walk(loggerClasses)) {
        for (Path classFile : files.filter(p -> p.toString().endsWith(".class"))
            .sorted().toList()) {
          String name = generatedClasses.relativize(classFile).toString()
              .replace('\\', '/');
          staged.putNextEntry(new JarEntry(name));
          Files.copy(classFile, staged);
          staged.closeEntry();
        }
      }
    }

    try (ZipFile verify = new ZipFile(stagedJar.toFile());
         ZipFile original = new ZipFile(jeiJar.toFile())) {
      for (String name : ORIGINAL_TREE_CLASSES) {
        assertEntryBytesEqual(original, verify, name);
      }
    }
  }

  private static void assertEntryBytesEqual(ZipFile left, ZipFile right, String name)
      throws IOException {
    ZipEntry leftEntry = left.getEntry(name);
    ZipEntry rightEntry = right.getEntry(name);
    if (leftEntry == null || rightEntry == null) {
      throw new IOException("Missing staged/original class entry " + name);
    }
    try (var leftStream = left.getInputStream(leftEntry);
         var rightStream = right.getInputStream(rightEntry)) {
      assertTrue(Arrays.equals(leftStream.readAllBytes(), rightStream.readAllBytes()),
          "Staged class must preserve original JEI bytecode: " + name);
    }
  }

  private static JvmRunResult runOriginalJvm(Path jeiJar, Path compatClasses) throws Exception {
    var previousError = System.err;
    var capturedError = new java.io.ByteArrayOutputStream();
    try (var loggerOutput = new java.io.PrintStream(
             capturedError, true, StandardCharsets.UTF_8);
         URLClassLoader loader = new URLClassLoader(new URL[] {
             jeiJar.toUri().toURL(), compatClasses.toUri().toURL()
         }, JeiSuffixTreeMilestoneTest.class.getClassLoader())) {
      System.setErr(loggerOutput);
      try {
        Class<?> treeClass = loader.loadClass("mezz.jei.suffixtree.GeneralizedSuffixTree");
        Object tree = treeClass.getConstructor().newInstance();
        Method put = treeClass.getMethod("put", String.class, int.class);
        Method trimToSize = treeClass.getMethod("trimToSize");
        Method search = treeClass.getMethod("search", String.class);
        Class<?> intSetType = loader.loadClass("it.unimi.dsi.fastutil.ints.IntSet");
        Method toIntArray = intSetType.getMethod("toIntArray");

        for (int i = 0; i < CORPUS.size(); i++) {
          put.invoke(tree, CORPUS.get(i), i);
        }
        trimToSize.invoke(tree);
        put.invoke(tree, "modded:after_trim_item", CORPUS.size());

        List<String> results = new ArrayList<>();
        for (String query : QUERIES) {
          Object matchSet = search.invoke(tree, query);
          int[] matches = (int[]) toIntArray.invoke(matchSet);
          Arrays.sort(matches);
          results.add(Arrays.toString(matches));
        }
        loggerOutput.flush();
        List<String> warnings = capturedError.toString(StandardCharsets.UTF_8)
            .lines().toList();
        return new JvmRunResult(results, warnings);
      } finally {
        System.setErr(previousError);
      }
    }
  }

  private static String comparisonJson(JvmRunResult expected) {
    Map<String, Object> values = new TreeMap<>();
    values.put("queries", QUERIES);
    values.put("corpus", CORPUS);
    values.put("expected", expected.results());
    values.put("warnings", expected.warnings());
    return new com.google.gson.Gson().toJson(values);
  }

  private static boolean writeRealCorpusComparison(
      Path repository, Path jeiJar, Path compatClasses, Path generatedRoot, Path comparisonPath)
      throws Exception {
    Path corpusPath = generatedRoot.resolve("jei-real-item-corpus.json");
    if (!Files.isRegularFile(corpusPath)) {
      return false;
    }
    var gson = new com.google.gson.Gson();
    var root = com.google.gson.JsonParser.parseString(
        Files.readString(corpusPath, StandardCharsets.UTF_8)).getAsJsonObject();
    assertEquals("supplied-eaglercraft-1.12.2-js", root.get("profile").getAsString());
    assertEquals(EXPECTED_JEI_SHA256, root.get("sourceModSha256").getAsString());
    assertEquals(sha256(repository.resolve(
        "unminified-clients/Eaglercraft_1.12_Offline_en_US.html")),
        root.get("sourceClientSha256").getAsString());

    List<ClientItem> items = new ArrayList<>();
    var identifiers = new java.util.HashSet<String>();
    for (var element : root.getAsJsonArray("items")) {
      var item = element.getAsJsonObject();
      String id = item.get("id").getAsString();
      String displayName = item.get("displayName").getAsString();
      assertTrue(!id.isBlank(), "Client item identifier must not be empty");
      assertTrue(!displayName.isBlank(), "Client-derived display name must not be empty: " + id);
      assertTrue(identifiers.add(id), "Duplicate client item identifier: " + id);
      items.add(new ClientItem(id, displayName));
    }
    assertEquals(items.size(), root.get("count").getAsInt());
    assertTrue(!items.isEmpty(), "Client item registry must not be empty");

    List<String> corpus = items.stream()
        .map(item -> item.id() + " " + item.displayName()).toList();
    List<String> queries = new ArrayList<>();
    items.forEach(item -> {
      queries.add(item.id());
      queries.add(item.displayName());
    });
    queries.add(items.get(0).id().toUpperCase(java.util.Locale.ROOT));
    String unicodeQuery = items.stream().map(ClientItem::displayName)
        .flatMapToInt(String::codePoints)
        .filter(codePoint -> codePoint > 127)
        .findFirst()
        .stream()
        .mapToObj(Character::toString)
        .findFirst()
        .orElse("\u00e9");
    queries.add(unicodeQuery);
    queries.add("iron");
    queries.add("Iron");
    queries.add("goldclient-no-such-registered-item-4b4a9a5e");
    queries.add(items.get(0).id());
    String mutationText = "goldclient:post_trim_mutation_marker";
    queries.add(mutationText);

    JvmRunResult expected =
        runOriginalJvmForCorpus(jeiJar, compatClasses, corpus, queries, mutationText);
    Map<String, Object> values = new TreeMap<>();
    values.put("corpus", corpus);
    values.put("queries", queries);
    values.put("expected", expected.results());
    values.put("warnings", expected.warnings());
    values.put("mutationText", mutationText);
    Files.writeString(comparisonPath, gson.toJson(values), StandardCharsets.UTF_8);
    return true;
  }

  private static JvmRunResult runOriginalJvmForCorpus(
      Path jeiJar, Path compatClasses, List<String> corpus, List<String> queries,
      String mutationText) throws Exception {
    var previousError = System.err;
    var capturedError = new java.io.ByteArrayOutputStream();
    try (var loggerOutput = new java.io.PrintStream(
             capturedError, true, StandardCharsets.UTF_8);
         URLClassLoader loader = new URLClassLoader(new URL[] {
             jeiJar.toUri().toURL(), compatClasses.toUri().toURL()
         }, JeiSuffixTreeMilestoneTest.class.getClassLoader())) {
      System.setErr(loggerOutput);
      try {
        Class<?> treeClass = loader.loadClass("mezz.jei.suffixtree.GeneralizedSuffixTree");
        Object tree = treeClass.getConstructor().newInstance();
        Method put = treeClass.getMethod("put", String.class, int.class);
        Method trimToSize = treeClass.getMethod("trimToSize");
        Method search = treeClass.getMethod("search", String.class);
        Class<?> intSetType = loader.loadClass("it.unimi.dsi.fastutil.ints.IntSet");
        Method toIntArray = intSetType.getMethod("toIntArray");
        for (int i = 0; i < corpus.size(); i++) {
          put.invoke(tree, corpus.get(i), i);
        }
        trimToSize.invoke(tree);
        put.invoke(tree, mutationText, corpus.size());

        List<String> results = new ArrayList<>();
        for (String query : queries) {
          Object matchSet = search.invoke(tree, query);
          int[] matches = (int[]) toIntArray.invoke(matchSet);
          Arrays.sort(matches);
          results.add(Arrays.toString(matches));
        }
        loggerOutput.flush();
        return new JvmRunResult(results,
            capturedError.toString(StandardCharsets.UTF_8).lines().toList());
      } finally {
        System.setErr(previousError);
      }
    }
  }

  private record ClientItem(String id, String displayName) {
  }

  private record JvmRunResult(List<String> results, List<String> warnings) {
  }

  private static String sha256(Path path) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    try (var input = Files.newInputStream(path)) {
      byte[] buffer = new byte[8192];
      int count;
      while ((count = input.read(buffer)) >= 0) {
        digest.update(buffer, 0, count);
      }
    }
    return HexFormat.of().withUpperCase().formatHex(digest.digest());
  }
}
