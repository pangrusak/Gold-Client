package com.goldclient.translator;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class JeiIngredientElementMilestoneTest {
  private static final String JEI_SHA256 =
      "DAD60317D8FDF891AD7841ABF838564EA7CBD317EC85D92F57DCB8F55602124B";
  private static final String CLIENT_SHA256 =
      "B23DECDCA4FF9AF58F63EEA4E93772D8A0A1DCBB59781A32E505E9C9735D1E67";
  private static final String[] ORIGINAL_JEI_CLASSES = {
      "mezz/jei/ingredients/IngredientListElementFactory.class",
      "mezz/jei/ingredients/IngredientListElement.class",
      "mezz/jei/ingredients/IngredientListElementComparator.class",
      "mezz/jei/ingredients/IngredientOrderTracker.class",
      "mezz/jei/ingredients/IngredientInformation.class",
      "mezz/jei/ingredients/IngredientBlacklistInternal.class",
      "mezz/jei/ingredients/IngredientFilter.class",
      "mezz/jei/ingredients/IngredientFilterBackgroundBuilder.class",
      "mezz/jei/ingredients/PrefixedSearchTree.class",
      "mezz/jei/ingredients/PrefixedSearchTree$IModeGetter.class",
      "mezz/jei/ingredients/PrefixedSearchTree$IStringsGetter.class",
      "mezz/jei/suffixtree/CombinedSearchTrees.class",
      "mezz/jei/config/Config.class",
      "mezz/jei/config/Config$1.class",
      "mezz/jei/config/Config$SearchMode.class",
      "mezz/jei/config/ConfigValues.class",
      "mezz/jei/config/IngredientBlacklistType.class",
      "mezz/jei/config/EditModeToggleEvent.class",
      "mezz/jei/util/GiveMode.class",
      "mezz/jei/util/GiveMode$1.class",
      "mezz/jei/util/GiveMode$2.class",
      "mezz/jei/util/ErrorUtil.class",
      "mezz/jei/util/Translator.class",
      "mezz/jei/Internal.class",
      "mezz/jei/color/ColorNamer.class",
      "mezz/jei/api/IIngredientFilter.class",
      "mezz/jei/gui/overlay/IIngredientGridSource.class",
      "mezz/jei/gui/overlay/IIngredientGridSource$Listener.class",
      "mezz/jei/startup/PlayerJoinedWorldEvent.class",
      "mezz/jei/util/LegacyUtil.class",
      "mezz/jei/util/Log.class",
      "mezz/jei/suffixtree/GeneralizedSuffixTree.class",
      "mezz/jei/suffixtree/GeneralizedSuffixTree$Pair.class",
      "mezz/jei/suffixtree/Node.class",
      "mezz/jei/suffixtree/Edge.class",
      "mezz/jei/suffixtree/ISearchTree.class"
  };
  private static final String[] ORIGINAL_FORGE_CLASSES = {
      "net/minecraftforge/fml/common/eventhandler/Event.class",
      "net/minecraftforge/fml/common/eventhandler/Event$Result.class",
      "net/minecraftforge/fml/common/gameevent/TickEvent.class",
      "net/minecraftforge/fml/common/gameevent/TickEvent$ClientTickEvent.class",
      "net/minecraftforge/fml/common/gameevent/TickEvent$Phase.class",
      "net/minecraftforge/fml/relauncher/Side.class"
  };
  private static final String NODE_SCRIPT = """
      const assert = require("node:assert/strict");
      const fs = require("node:fs");
      const path = require("node:path");
      const mod = require(path.resolve(process.argv[2]));
      const data = JSON.parse(fs.readFileSync(process.argv[3], "utf8"));
      assert.equal(typeof mod.main, "function");
      assert.equal(typeof mod.goldClientJeiInitialize, "function");
      assert.equal(typeof mod.goldClientJeiInitializeWithTooltips, "function");
      assert.equal(typeof mod.goldClientJeiSetLocale, "function");
      assert.equal(typeof mod.goldClientJeiSearch, "function");
      assert.equal(typeof mod.goldClientJeiFilter, "function");
      assert.equal(typeof mod.goldClientJeiTooltip, "function");
      const progressEvents = [];
      global.window = {
        __goldClientJeiProgressUpdate(...event) {
          progressEvents.push(event);
        },
        __goldClientJeiGetTooltipData(id, advanced) {
          const lines = [`Tooltip ${id}`, advanced ? "Advanced" : "Normal: detail"];
          return lines.map(line => `${line.length}:${line}`).join("");
        }
      };
      mod.main([], error => { if (error) throw error; });
      mod.goldClientJeiSetLocale(data.localeTag);
      const basicItems = JSON.parse(mod.goldClientJeiInitialize(data.ids, data.displayNames));
      assert.deepEqual(basicItems, data.expectedItems);
      assert.deepEqual(
        JSON.parse(mod.goldClientJeiTooltip(data.ids[0], false)),
        [`Tooltip ${data.ids[0]}`, "Normal: detail"]);
      assert.deepEqual(
        JSON.parse(mod.goldClientJeiTooltip(data.ids[0], true)),
        [`Tooltip ${data.ids[0]}`, "Advanced"]);
      assert.throws(
        () => mod.goldClientJeiTooltip("missing:item", false),
        /Unknown client item identifier/);

      mod.goldClientJeiSetLocale(data.localeTag);
      const items = JSON.parse(mod.goldClientJeiInitializeWithTooltips(
        data.ids, data.displayNames, data.normalTooltipData, data.advancedTooltipData));
      assert.deepEqual(items, data.expectedItems);
      assert(
        progressEvents.some(event =>
          event[1] === data.ids.length &&
          event[2] === data.ids.length &&
          event[4] === false),
        "Original JEI must complete the ingredient-factory progress bar");
      assert.deepEqual(
        JSON.parse(mod.goldClientJeiTooltip(data.ids[0], false)),
        data.expectedNormalTooltips);
      assert.deepEqual(
        JSON.parse(mod.goldClientJeiTooltip(data.ids[0], true)),
        data.expectedAdvancedTooltips);
      const results = data.queries.map(query => mod.goldClientJeiSearch(query));
      assert.deepEqual(results, data.expectedResults);
      const filterResults = data.queries.map(query => mod.goldClientJeiFilter(query));
      assert.deepEqual(filterResults, data.expectedFilterResults);
      const replacementCount = Math.min(3, data.ids.length);
      const replacementItems = JSON.parse(mod.goldClientJeiInitializeWithTooltips(
        data.ids.slice(0, replacementCount),
        data.displayNames.slice(0, replacementCount),
        data.normalTooltipData.slice(0, replacementCount),
        data.advancedTooltipData.slice(0, replacementCount)));
      assert.deepEqual(replacementItems, data.expectedRebuiltItems);
      const rebuiltFilterResults = data.rebuildQueries.map(query => mod.goldClientJeiFilter(query));
      assert.deepEqual(rebuiltFilterResults, data.expectedRebuiltFilterResults);
      console.log(`Translated original JEI ingredient-element creation matches JVM on ${items.length} records and ${results.length} searches.`);
      """;

  @Test
  void originalIngredientElementsAndSearchMatchTranslatedOutput(@TempDir Path temp)
      throws Exception {
    Path repository = Path.of("").toAbsolutePath();
    Path jeiJar = repository.resolve("test-mods/jei1.12.2.jar");
    assertTrue(Files.isRegularFile(jeiJar), "The exact JEI test JAR is required");
    assertEquals(JEI_SHA256, sha256(jeiJar), "Refusing to use a different JEI JAR");

    Path forgeRoot = repository.resolve("target/forge-baseline");
    Path mcJar = forgeRoot.resolve("mdk/build/tmp/deobfMcSRG/deobfed.jar");
    Path forgeClasses = forgeRoot.resolve("forge-mapped.jar");
    Assumptions.assumeTrue(
        Files.isRegularFile(mcJar) && Files.isRegularFile(forgeClasses),
        "Run client-profiles/eaglercraft-1.12-js/prepare-forge-runtime.ps1 first");

    Path generatedRoot = repository.resolve("target/jei-ingredient-elements");
    Path generatedClasses = generatedRoot.resolve("generated-classes");
    Path stagedJar = generatedRoot.resolve("jei-original-ingredient-element-slice.jar");
    Path translatedDirectory = generatedRoot.resolve("translated");
    new ForgeLanguageRemapTest().originalForgeLanguageCanBeRemappedToSrg();

    if (Files.exists(generatedClasses)) {
      try (var files = Files.walk(generatedClasses)) {
        for (Path path : files.sorted(java.util.Comparator.reverseOrder()).toList()) {
          Files.delete(path);
        }
      }
    }

    Files.createDirectories(generatedClasses);
    compileCompatibility(repository, jeiJar, mcJar, forgeClasses, generatedClasses);
    assertNonNullListMatchesMinecraft(mcJar, forgeClasses, generatedClasses);
    Path guava = gradleJar("com.google.guava", "guava", "21.0");
    Path commonsLang = gradleJar("org.apache.commons", "commons-lang3", "3.5");
    stageOriginalClasses(
        jeiJar, mcJar, forgeClasses, guava, commonsLang, generatedClasses, stagedJar);

    Corpus corpus = readCorpus(repository, generatedRoot);
    List<String> queries = createQueries(corpus.ids(), corpus.displayNames());
    RuntimeResult expected = runOriginalJvm(
        repository, jeiJar, mcJar, forgeClasses, generatedClasses, corpus, queries);

    Path comparisonData = generatedRoot.resolve("comparison.json");
    Path comparisonScript = generatedRoot.resolve("compare.cjs");
    Files.writeString(comparisonData,
        comparisonJson(corpus, queries, expected), StandardCharsets.UTF_8);
    Files.writeString(comparisonScript, NODE_SCRIPT, StandardCharsets.UTF_8);

    TeaVmTranslator.translate(stagedJar, translatedDirectory, "JeiIngredientEntry",
        Set.of("org.apache.logging.log4j."));
    Path translatedJs = translatedDirectory.resolve("eagler-mod.js");
    assertTrue(Files.isRegularFile(translatedJs) && Files.size(translatedJs) > 0,
        "TeaVM must produce the original ingredient-element path");

    Process process = new ProcessBuilder("node", comparisonScript.toString(),
        translatedJs.toString(), comparisonData.toString())
        .directory(repository.toFile())
        .redirectErrorStream(true)
        .start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertEquals(0, process.waitFor(), "Translated JEI comparison failed:\n" + output);
    assertTrue(output.contains("matches JVM"), output);
  }

  private static void compileCompatibility(
      Path repository,
      Path jeiJar,
      Path mcJar,
      Path forgeClasses,
      Path classes) throws IOException {
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    if (compiler == null) {
      fail("A JDK compiler is required to compile the generated JEI profile entry");
    }
    Path fastutil = mavenJar("it/unimi/dsi/fastutil/7.1.0/fastutil-7.1.0.jar");
    Path teavmJso = mavenJar("org/teavm/teavm-jso/0.11.0/teavm-jso-0.11.0.jar");
    Path guava = gradleJar("com.google.guava", "guava", "21.0");
    Path commonsLang = gradleJar("org.apache.commons", "commons-lang3", "3.5");
    Path languageOverlay =
        forgeClasses.resolveSibling("forge-language-srg.jar");

    assertTrue(Files.isRegularFile(languageOverlay),
        "The verified original Forge Language overlay is required");

    String classpath = String.join(System.getProperty("path.separator"),
        languageOverlay.toString(),
        jeiJar.toString(),
        mcJar.toString(),
        forgeClasses.toString(),
        fastutil.toString(),
        teavmJso.toString(),
        guava.toString(),
        commonsLang.toString());
    List<String> arguments = new ArrayList<>(List.of(
        "--release", "17",
        "-encoding", "UTF-8",
        "-classpath", classpath,
        "-d", classes.toString()));
    Path compat = repository.resolve("client-profiles/eaglercraft-1.12-js/compat");
    try (var files = Files.walk(compat)) {
      files.filter(path -> path.toString().endsWith(".java"))
          .sorted()
          .map(Path::toString)
          .forEach(arguments::add);
    }
    int exitCode = compiler.run(null, null, null, arguments.toArray(String[]::new));
    assertEquals(0, exitCode, "Could not compile the JEI compatibility boundary");
  }

  private static Path mavenJar(String relativePath) {
    return Path.of(System.getProperty("user.home"), ".m2", "repository", relativePath);
  }

   private static void stageOriginalClasses(
      Path jeiJar,
      Path mcJar,
      Path forgeClasses,
      Path guavaJar,
      Path commonsLangJar,
      Path generatedClasses,
      Path stagedJar) throws IOException {

    String language = "net/minecraft/client/resources/Language.class";
    String languageManager =
        "net/minecraft/client/resources/LanguageManager.class";
    String translator = "mezz/jei/util/Translator.class";

    Path languageOverlay =
        forgeClasses.resolveSibling("forge-language-srg.jar");

    if (!Files.isRegularFile(languageOverlay)) {
      throw new IOException(
          "Verified Forge Language overlay is missing: " + languageOverlay);
    }

    if (Files.exists(generatedClasses.resolve(translator))) {
      throw new IOException(
          "Stale replacement Translator.class remains in generated classes");
    }

    Set<String> replacements = new HashSet<>(Set.of(
        "net/minecraft/client/Minecraft.class",
        language,
        languageManager,
        "net/minecraft/util/NonNullList.class",
        "net/minecraft/util/text/TextFormatting.class",
        "net/minecraftforge/fml/common/ProgressManager.class",
        "net/minecraftforge/fml/common/ProgressManager$ProgressBar.class"));

    replacements.addAll(List.of(ORIGINAL_FORGE_CLASSES));

    try (OutputStream output = Files.newOutputStream(stagedJar);
         JarOutputStream staged = new JarOutputStream(output);
         ZipFile jei = new ZipFile(jeiJar.toFile());
         ZipFile minecraft = new ZipFile(mcJar.toFile());
         ZipFile forge = new ZipFile(forgeClasses.toFile());
         ZipFile overlay = new ZipFile(languageOverlay.toFile());
         ZipFile guava = new ZipFile(guavaJar.toFile());
         ZipFile commonsLang = new ZipFile(commonsLangJar.toFile())) {

      for (String name : ORIGINAL_JEI_CLASSES) {
        copyEntry(jei, staged, name);
      }

      for (ZipEntry entry : java.util.Collections.list(jei.entries())) {
        String name = entry.getName();

        if (!entry.isDirectory()
            && (name.startsWith("mezz/jei/api/ingredients/")
                || name.equals("mezz/jei/api/recipe/IIngredientType.class")
                || name.equals(
                    "mezz/jei/gui/ingredients/IIngredientListElement.class")
                || name.equals("mezz/jei/startup/IModIdHelper.class"))) {
          copyEntry(jei, staged, name);
        }
      }

      for (ZipEntry entry : java.util.Collections.list(minecraft.entries())) {
        String name = entry.getName();

        if (!entry.isDirectory()
            && name.endsWith(".class")
            && !replacements.contains(name)
            && !name.startsWith(
                "net/minecraftforge/fml/common/ProgressManager$")) {
          copyEntry(minecraft, staged, name);
        }
      }

      copyEntry(overlay, staged, language);

      for (String name : ORIGINAL_FORGE_CLASSES) {
        copyEntry(forge, staged, name);
      }

      for (ZipEntry entry : java.util.Collections.list(guava.entries())) {
        if (!entry.isDirectory()
            && entry.getName().endsWith(".class")
            && !entry.getName().startsWith("META-INF/")) {
          copyEntry(guava, staged, entry.getName());
        }
      }

      for (ZipEntry entry : java.util.Collections.list(commonsLang.entries())) {
        if (!entry.isDirectory()
            && entry.getName().endsWith(".class")
            && !entry.getName().startsWith("META-INF/")) {
          copyEntry(commonsLang, staged, entry.getName());
        }
      }

      try (var files = Files.walk(generatedClasses)) {
        for (Path classFile : files
            .filter(path -> path.toString().endsWith(".class"))
            .sorted()
            .toList()) {

          String name = generatedClasses.relativize(classFile)
              .toString().replace('\\', '/');

          if (name.equals(translator) || name.equals(language)) {
            throw new IOException(
                "Unexpected generated replacement for original class: " + name);
          }

          putFile(staged, name, classFile);
        }
      }
    }

    try (ZipFile original = new ZipFile(jeiJar.toFile());
         ZipFile overlay = new ZipFile(languageOverlay.toFile());
         ZipFile result = new ZipFile(stagedJar.toFile())) {

      for (String name : ORIGINAL_JEI_CLASSES) {
        assertEntryBytesEqual(original, result, name);
      }

      assertEntryBytesEqual(overlay, result, language);

      assertTrue(result.getEntry(languageManager) != null,
          "The client language-manager boundary must be staged");
    }
  }

  private static void copyEntry(ZipFile source, JarOutputStream destination, String name)
      throws IOException {
    ZipEntry entry = source.getEntry(name);
    if (entry == null) {
      throw new IOException("Required original/runtime class is missing: " + name);
    }
    JarEntry copy = new JarEntry(name);
    destination.putNextEntry(copy);
    try (var input = source.getInputStream(entry)) {
      input.transferTo(destination);
    }
    destination.closeEntry();
  }

  private static void putFile(JarOutputStream destination, String name, Path file)
      throws IOException {
    destination.putNextEntry(new JarEntry(name));
    Files.copy(file, destination);
    destination.closeEntry();
  }

  private static void assertEntryBytesEqual(ZipFile original, ZipFile staged, String name)
      throws IOException {
    ZipEntry source = original.getEntry(name);
    ZipEntry result = staged.getEntry(name);
    if (source == null || result == null) {
      throw new IOException("Missing original/staged class " + name);
    }
    try (var sourceBytes = original.getInputStream(source);
         var resultBytes = staged.getInputStream(result)) {
      assertTrue(Arrays.equals(sourceBytes.readAllBytes(), resultBytes.readAllBytes()),
          "Original JEI class bytes must be unchanged: " + name);
    }
  }

  private static Corpus readCorpus(Path repository, Path generatedRoot) throws IOException {
    Path path = repository.resolve("target/jei-suffix-tree/jei-real-item-corpus.json");
    if (Files.isRegularFile(path)) {
      var root = com.google.gson.JsonParser.parseString(
          Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
      assertEquals("supplied-eaglercraft-1.12.2-js", root.get("profile").getAsString());
      assertEquals(JEI_SHA256, root.get("sourceModSha256").getAsString());
      assertEquals(CLIENT_SHA256, root.get("sourceClientSha256").getAsString());
      List<String> ids = new ArrayList<>();
      List<String> displayNames = new ArrayList<>();
      List<String> normalTooltipData = new ArrayList<>();
      List<String> advancedTooltipData = new ArrayList<>();
      String localeTag = root.has("localeTag") ? root.get("localeTag").getAsString() : "en_us";
      for (var element : root.getAsJsonArray("items")) {
        var item = element.getAsJsonObject();
        ids.add(item.get("id").getAsString());
        displayNames.add(item.get("displayName").getAsString());
        normalTooltipData.add(item.get("normalTooltipData").getAsString());
        advancedTooltipData.add(item.get("advancedTooltipData").getAsString());
      }
      assertEquals(411, ids.size(), "The current supplied client registry has 411 item types");
      return new Corpus(ids, displayNames, normalTooltipData, advancedTooltipData,
          localeTag, "browser-exported-client-corpus");
    }
    List<String> ids = List.of(
        "minecraft:iron_ingot", "minecraft:iron_block", "minecraft:gold_ingot");
    List<String> displayNames = List.of("Iron Ingot", "Block of Iron", "Gold Ingot");
    return new Corpus(
        ids,
        displayNames,
        displayNames.stream().map(name -> encodeTooltipLines(List.of(name))).toList(),
        displayNames.stream().map(name -> encodeTooltipLines(List.of(name))).toList(),
        "en_us", "small unit-test fixture; not game data");
  }

  private static String encodeTooltipLines(List<String> lines) {
    StringBuilder encoded = new StringBuilder();
    for (String line : lines) {
      encoded.append(line.length()).append(':').append(line);
    }
    return encoded.toString();
  }

  private static List<String> createQueries(List<String> ids, List<String> displayNames) {
    List<String> queries = new ArrayList<>(ids.size() * 2 + 8);
    for (int index = 0; index < ids.size(); index++) {
      queries.add(ids.get(index));
      queries.add(displayNames.get(index));
    }
    queries.add("iron");
    queries.add("IRON");
    queries.add("Iron");
    queries.add("\u0130RON");
    queries.add("I\u0307RON");
    queries.add("@minecraft");
    queries.add("@missing-mod");
    queries.add("#iron");
    queries.add("@minecraft iron");
    queries.add("@minecraft \"iron ingot\"");
    queries.add(ids.get(0).toUpperCase(java.util.Locale.ROOT));
    queries.add("absent-goldclient-ingredient-query");
    queries.add(ids.get(0));
    queries.add("§6" + displayNames.get(0));
    queries.add("");
    return queries;
  }

  private static RuntimeResult runOriginalJvm(
      Path repository,
      Path jeiJar,
      Path mcJar,
      Path forgeClasses,
      Path generatedClasses,
      Corpus corpus,
      List<String> queries) throws Exception {
    List<URL> classpath = new ArrayList<>();
    classpath.add(mcJar.toUri().toURL());
    classpath.add(forgeClasses.toUri().toURL());
    classpath.add(jeiJar.toUri().toURL());
    classpath.add(mavenJar("it/unimi/dsi/fastutil/7.1.0/fastutil-7.1.0.jar").toUri().toURL());
    classpath.add(mavenJar("org/teavm/teavm-jso/0.11.0/teavm-jso-0.11.0.jar").toUri().toURL());
    classpath.add(gradleJar(
        "org.apache.logging.log4j", "log4j-api", "2.8.1").toUri().toURL());
    classpath.add(gradleJar(
        "org.apache.logging.log4j", "log4j-core", "2.8.1").toUri().toURL());
    addJars(classpath, Path.of(System.getProperty("user.home"), ".gradle/caches/modules-2/files-2.1"));
    addJars(classpath, Path.of(System.getProperty("user.home"), ".m2/repository"));
    classpath.add(generatedClasses.toUri().toURL());
        java.util.Locale previousDefault = java.util.Locale.getDefault();
    try (URLClassLoader loader = new URLClassLoader(
        classpath.toArray(URL[]::new), ClassLoader.getPlatformClassLoader())) {
                Path languageOverlay =
          forgeClasses.resolveSibling("forge-language-srg.jar");

      java.util.Locale expectedLocale;

      try (URLClassLoader languageLoader = new URLClassLoader(
          new URL[] {languageOverlay.toUri().toURL()},
          ClassLoader.getPlatformClassLoader())) {

        Class<?> languageType = languageLoader.loadClass(
            "net.minecraft.client.resources.Language");

        String normalizedCode = corpus.localeTag().replace('-', '_');

        Object selectedLanguage = languageType.getConstructor(
            String.class, String.class, String.class, boolean.class)
            .newInstance(normalizedCode, "", normalizedCode, false);

        expectedLocale = (java.util.Locale) languageType
            .getMethod("getJavaLocale")
            .invoke(selectedLanguage);
      }

      java.util.Locale.setDefault(expectedLocale);

      Class<?> translatorType = loader.loadClass("mezz.jei.util.Translator");
      var getLocale = translatorType.getDeclaredMethod("getLocale");
      getLocale.setAccessible(true);

      assertEquals(expectedLocale, getLocale.invoke(null),
          "Original JEI JVM helper must resolve the comparison locale");

      assertEquals(
          "IRON \u0130 I\u0307".toLowerCase(expectedLocale),
          translatorType.getMethod("toLowercaseWithLocale", String.class)
              .invoke(null, "IRON \u0130 I\u0307"),
          "Original JEI locale-sensitive lowercase must match the selected locale");
      Class<?> entry = loader.loadClass("JeiIngredientEntry");
      entry.getMethod("main", String[].class).invoke(null, (Object) new String[0]);
      String[] ids = corpus.ids().toArray(String[]::new);
      String[] names = corpus.displayNames().toArray(String[]::new);
      String[] normalTooltipData = corpus.normalTooltipData().toArray(String[]::new);
      String[] advancedTooltipData = corpus.advancedTooltipData().toArray(String[]::new);
      String actualItems = (String) entry.getMethod(
          "goldClientJeiInitializeWithTooltips",
          String[].class, String[].class, String[].class, String[].class)
          .invoke(null, ids, names, normalTooltipData, advancedTooltipData);
      Class<?> originalConfig = loader.loadClass("mezz.jei.config.Config");
      assertEquals("ENABLED",
          originalConfig.getMethod("getTooltipSearchMode").invoke(null).toString());
      assertEquals("REQUIRE_PREFIX",
          originalConfig.getMethod("getModNameSearchMode").invoke(null).toString());
      assertEquals("DISABLED",
          originalConfig.getMethod("getOreDictSearchMode").invoke(null).toString());
      assertEquals("DISABLED",
          originalConfig.getMethod("getCreativeTabSearchMode").invoke(null).toString());
      assertEquals("DISABLED",
          originalConfig.getMethod("getColorSearchMode").invoke(null).toString());
      assertEquals("DISABLED",
          originalConfig.getMethod("getResourceIdSearchMode").invoke(null).toString());
      var tooltip = entry.getMethod("goldClientJeiTooltip", String.class, boolean.class);
      String actualNormalTooltip = (String) tooltip.invoke(null, ids[0], false);
      String actualAdvancedTooltip = (String) tooltip.invoke(null, ids[0], true);
      List<String> actualResults = new ArrayList<>();
      var search = entry.getMethod("goldClientJeiSearch", String.class);
      List<String> actualFilterResults = new ArrayList<>();
      var filter = entry.getMethod("goldClientJeiFilter", String.class);
      for (String query : queries) {
        actualResults.add((String) search.invoke(null, query));
        actualFilterResults.add((String) filter.invoke(null, query));
      }

      int replacementCount = Math.min(3, ids.length);
      String[] replacementIds = Arrays.copyOf(ids, replacementCount);
      String[] replacementNames = Arrays.copyOf(names, replacementCount);
      String[] replacementNormal = Arrays.copyOf(normalTooltipData, replacementCount);
      String[] replacementAdvanced = Arrays.copyOf(advancedTooltipData, replacementCount);
      String replacementItems = (String) entry.getMethod(
          "goldClientJeiInitializeWithTooltips",
          String[].class, String[].class, String[].class, String[].class)
          .invoke(null, replacementIds, replacementNames,
              replacementNormal, replacementAdvanced);
      List<String> rebuildQueries = List.of(
          corpus.displayNames().get(0),
          corpus.ids().get(0).toUpperCase(java.util.Locale.ROOT),
          "absent-goldclient-rebuild-query");
      List<String> rebuiltFilterResults = new ArrayList<>();
      for (String query : rebuildQueries) {
        rebuiltFilterResults.add((String) filter.invoke(null, query));
      }

      Class<?> progressManager = loader.loadClass("net.minecraftforge.fml.common.ProgressManager");
      var bars = (java.util.Iterator<?>) progressManager.getMethod("barIterator").invoke(null);
      assertFalse(bars.hasNext(), "JEI must complete and pop its real Forge progress bar");
      return new RuntimeResult(
          actualItems, actualResults, actualFilterResults,
          replacementItems, rebuiltFilterResults, rebuildQueries,
          actualNormalTooltip, actualAdvancedTooltip);
    } finally {
      java.util.Locale.setDefault(previousDefault);
    }
  }

  private static void addJars(List<URL> urls, Path root) throws IOException {
    if (!Files.isDirectory(root)) {
      return;
    }
    try (var files = Files.walk(root)) {
      for (Path file : files.filter(path -> path.toString().endsWith(".jar")).toList()) {
        urls.add(file.toUri().toURL());
      }
    }
  }

  private static Path gradleJar(String group, String artifact, String version)
      throws IOException {
    Path directory = Path.of(System.getProperty("user.home"), ".gradle", "caches",
        "modules-2", "files-2.1", group, artifact, version);
    try (var files = Files.walk(directory)) {
      return files.filter(path -> path.getFileName().toString()
          .equals(artifact + "-" + version + ".jar"))
          .findFirst()
          .orElseThrow(() -> new IOException(
              "Matching Forge baseline dependency is missing: " + directory));
    }
  }

  private static void assertNonNullListMatchesMinecraft(
      Path mcJar, Path forgeJar, Path compatClasses) throws Exception {
    List<URL> originalClasspath = new ArrayList<>(List.of(
        mcJar.toUri().toURL(), forgeJar.toUri().toURL()));
    addJars(originalClasspath,
        Path.of(System.getProperty("user.home"), ".gradle", "caches", "modules-2", "files-2.1"));
    addJars(originalClasspath, Path.of(System.getProperty("user.home"), ".m2", "repository"));

    try (URLClassLoader originalLoader = new URLClassLoader(
             originalClasspath.toArray(URL[]::new), ClassLoader.getPlatformClassLoader());
         URLClassLoader compatLoader = new URLClassLoader(
             new URL[] {compatClasses.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
      assertEquals(runNonNullListScenarios(originalLoader),
          runNonNullListScenarios(compatLoader),
          "The client compatibility NonNullList must preserve Minecraft 1.12.2 behavior");
    }
  }

  private static List<Object> runNonNullListScenarios(ClassLoader loader) throws Exception {
    Class<?> type = loader.loadClass("net.minecraft.util.NonNullList");
    var sizedFactory = type.getMethod("func_191197_a", int.class, Object.class);
    var arrayFactory = type.getMethod("func_193580_a", Object.class, Object[].class);
    @SuppressWarnings("unchecked")
    List<Object> resettable = (List<Object>) sizedFactory.invoke(null, 3, "default");
    resettable.set(1, "changed");
    List<Object> beforeClear = List.copyOf(resettable);
    resettable.clear();
    List<Object> afterClear = List.copyOf(resettable);
    String fixedAdd = exceptionType(() -> resettable.add("extra"));
    String fixedRemove = exceptionType(() -> resettable.remove(0));
    String nullSet = exceptionType(() -> resettable.set(0, null));
    String negativeSize = exceptionType(() -> sizedFactory.invoke(null, -1, "default"));

    @SuppressWarnings("unchecked")
    List<Object> arrayBacked = (List<Object>) arrayFactory.invoke(
        null, "fallback", (Object) new String[] {"first", "second"});
    arrayBacked.set(0, "updated");
    List<Object> arrayAfterClear = List.copyOf(arrayBacked);
    arrayBacked.clear();
    List<Object> arrayAfterReset = List.copyOf(arrayBacked);
    String arrayAdd = exceptionType(() -> arrayBacked.add("extra"));
    String arrayRemove = exceptionType(() -> arrayBacked.remove(0));
    return List.of(beforeClear, afterClear, fixedAdd, fixedRemove, nullSet, negativeSize,
        arrayAfterClear, arrayAfterReset, arrayAdd, arrayRemove);
  }

  private static String exceptionType(ThrowingAction action) throws Exception {
    try {
      action.run();
      return "";
    } catch (Exception exception) {
      Throwable cause = exception instanceof java.lang.reflect.InvocationTargetException invocation
          ? invocation.getCause()
          : exception;
      return cause.getClass().getName();
    }
  }

  @FunctionalInterface
  private interface ThrowingAction {
    void run() throws Exception;
  }

  private static String comparisonJson(
      Corpus corpus, List<String> queries, RuntimeResult expected) {
    Map<String, Object> values = new TreeMap<>();
    values.put("corpusSource", corpus.source());
    values.put("localeTag", corpus.localeTag());
    values.put("ids", corpus.ids());
    values.put("displayNames", corpus.displayNames());
    values.put("normalTooltipData", corpus.normalTooltipData());
    values.put("advancedTooltipData", corpus.advancedTooltipData());
    values.put("expectedItems",
        com.google.gson.JsonParser.parseString(expected.items()).getAsJsonArray());
    values.put("queries", queries);
    values.put("expectedResults", expected.results());
    values.put("expectedFilterResults", expected.filterResults());
    values.put("expectedRebuiltItems",
        com.google.gson.JsonParser.parseString(expected.rebuiltItems()).getAsJsonArray());
    values.put("expectedRebuiltFilterResults", expected.rebuiltFilterResults());
    values.put("rebuildQueries", expected.rebuildQueries());
    values.put("expectedNormalTooltips",
        com.google.gson.JsonParser.parseString(expected.normalTooltip()).getAsJsonArray());
    values.put("expectedAdvancedTooltips",
        com.google.gson.JsonParser.parseString(expected.advancedTooltip()).getAsJsonArray());
    return new com.google.gson.Gson().toJson(values);
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

  private record Corpus(
      List<String> ids,
      List<String> displayNames,
      List<String> normalTooltipData,
      List<String> advancedTooltipData,
      String localeTag,
      String source) {
  }

  private record RuntimeResult(
      String items,
      List<String> results,
      List<String> filterResults,
      String rebuiltItems,
      List<String> rebuiltFilterResults,
      List<String> rebuildQueries,
      String normalTooltip,
      String advancedTooltip) {
  }
}
