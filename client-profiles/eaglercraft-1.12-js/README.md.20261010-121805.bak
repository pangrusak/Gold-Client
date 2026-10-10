# Eaglercraft 1.12.2 JS client profile — partial original JEI integration

This is a partial integration, not complete JEI compatibility. It translates
original JEI 4.16.1.301 bytecode from the external
`test-mods/jei1.12.2.jar` (SHA-256
`DAD60317D8FDF891AD7841ABF838564EA7CBD317EC85D92F57DCB8F55602124B`) and
uses original JEI ingredient-element construction, suffix-tree search, and
`IngredientFilter` with client-derived item IDs, display names, and real
tooltip data from the supplied Eaglercraft 1.12.2 JavaScript client.

## Run

From the repository root:

```powershell
.\client-profiles\eaglercraft-1.12-js\run-demo.ps1
```

This verifies/downloads the pinned Forge/Minecraft baseline, runs injector and
original-JVM/TeaVM parity tests, translates the selected JEI code, creates an
isolated client under ignored `target/`, then serves it at
`http://127.0.0.1:4173/`. Open that URL in a browser. After the client registry
is ready, the panel titled **“Partial JEI original-code search — diagnostic
UI”** invokes original `IngredientListElementFactory.createBaseList` and
`IngredientFilter`; the search query and returned matches use JEI's original
filter/indexing classes. The original suffix-tree tests remain a separate
regression path. The panel displays up to 100 matching client-derived
registry IDs and names. The server exports the runtime corpus to ignored
`target/jei-suffix-tree/jei-real-item-corpus.json`.

To verify the client-backed tooltip service in the browser developer console,
run `window.goldClientJeiTooltip("minecraft:iron_ingot", false)`. The supplied
build returns `["Iron Ingot§r"]`; passing `true` exercises the advanced
`ITooltipFlag`.

The build requires PowerShell, Node.js, Maven, and network access for the
checksum-pinned official runtime bootstrap. It uses Temurin JDK 8 for the
legacy ForgeGradle setup and Temurin JDK 17 for TeaVM/tests.

To rebuild and compare against the exported browser corpus:

```powershell
.\client-profiles\eaglercraft-1.12-js\build.ps1
```

When the exact client-hash/JAR-hash corpus exists, the build compares original
JVM and translated filter results over all 411 registered item types,
including prefixed mod-name/tooltip queries, intersections, absent/repeated
queries, case/Unicode/formatting behavior, and full-index replacement. It
separately compares suffix-tree behavior and profile `NonNullList` operations
with the original/mapped Minecraft implementations.

## Data boundary

The profile adapter enumerates the client's `Item.REGISTRY` after initialization.
It represents **registered item types**, not inventory stacks, creative-tab
variants, JEI ingredients, or recipes. For each registry entry it reads the
registry `ResourceLocation` and asks the client for the display name of a
one-count, metadata-0 `ItemStack`. Original JEI creates and sorts its
`IngredientListElement` objects from those traced records. It does not
synthesize IDs or names. Variants are excluded because this adapter does not
enumerate them. The exported corpus is sorted by registry ID and includes the
exact client and JEI input hashes.

The JS profile is tied to the supplied readable client build. The injection
adapter verifies its title/options/runtime/callback anchors and depends on
generated identifiers including `nmi_Item_REGISTRY`,
`nmur_RegistryNamespaced_iterator`, `nmur_RegistryNamespaced_getNameForObject`,
`nmi_ItemStack__init_10`, and `nmi_ItemStack_getDisplayName`. These are
client-build-specific symbols, not stable public APIs. A new client build must
be re-inspected and separately verified.

## Original code versus compatibility code

The translated slice stages byte-identical original JEI classes for
`IngredientListElementFactory`, `IngredientListElement`,
`IngredientListElementComparator`, `IngredientOrderTracker`,
`IngredientInformation`, `IngredientBlacklistInternal`, `IngredientFilter`,
`IngredientFilterBackgroundBuilder`, `PrefixedSearchTree`, the required
`Config`/`ConfigValues` classes, and the suffix-tree/search closure. The JVM
oracle runs the unmodified JAR against mapped Minecraft 1.12.2 and Forge
14.23.5.2816, then compares factory, filter, and suffix-tree results to TeaVM
output on the client corpus. JEI's Minecraft-bound `Translator` is the one
profile replacement: its locale-lowercase operation receives the real client
language code. All other staged original JEI classes are byte-checked.

Profile compatibility supplies actual item identifiers/display names,
default metadata-0 client stacks and generated tooltips, item-registry/helper
services, Minecraft `NonNullList` behavior, the real client language code,
progress callbacks, and visible warning logging. Guava 21.0 and Commons Lang
3.5 are staged from the matching Forge dependency cache. A mapped Forge/Minecraft
JVM classpath is prepared from ForgeGradle artifacts and remains separate from
TeaVM's browser services. The input JAR is not modified.

## Limits and verification scope

The original filter consumes actual client tooltip strings through the
`IIngredientRenderer` boundary and original `ConfigValues` defaults. In the
verified JAR those defaults require a prefix for mod-name search, enable
tooltip search, and disable ore-dictionary, creative-tab, color, and resource-ID
search. The client adapter uses the actual resource namespace as the mod ID;
Forge's friendly mod display names, ore dictionary, creative tabs, color names,
persisted blacklist, and mutable config are not available and are not
synthesized.

Original `IngredientFilterBackgroundBuilder` indexing completes synchronously
for the tested 411-entry corpus within its 10-second budget. If indexing takes
longer, JEI registers on Forge's event bus and waits for client ticks/player
state; the supplied client has no Forge event lifecycle, and the profile
explicitly fails that registration rather than silently dropping work.
Deferred indexing, larger/slow corpora, runtime ingredient mutations, and
config changes remain unsupported.

The temporary panel is diagnostic UI, not JEI's original UI. The target client
contains no Forge mod loader; the corpus is only the client's registered item
types. No claim of complete or arbitrary mod compatibility is made.

Minified JavaScript clients and the required 26.2 WebAssembly client remain
unsupported. No minified-client generalization or WASM rewriting is included.
