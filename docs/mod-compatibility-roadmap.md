# Mod compatibility evidence and roadmap

This document records evidence from the exact external JARs in `test-mods/`
and the current supplied client profile. A translated algorithm slice does not
mean that the whole mod, its loader integration, or its transformations work.
Inputs and generated outputs remain outside tracked repository contents.

## Current target and backend status

| Target/backend | Evidence | Status |
| --- | --- | --- |
| Supplied readable Eaglercraft 1.12.2 JavaScript client | The input HTML identifies Eaglercraft 1.12.2. Its generated JavaScript has client-specific item registry, tooltip, language, and screen-change hook symbols. | Original JEI ingredient creation, `IngredientFilter` indexing/querying, and suffix-tree searches run over 411 client-derived records. This is not Forge support or general arbitrary-mod support. |
| Minified JavaScript clients | No matching build profile or runtime verification. | Unsupported; retained as a product requirement. |
| Eaglercraft 26.2 WebAssembly | Existing assessment found WASM exports without Minecraft-facing methods. | Unsupported; retained as a product requirement. |
| Forge 1.12.2 client | No Forge runtime/loader is present in the supplied Eaglercraft client. | Unsupported. |
| Fabric Minecraft 1.16.2-1.16.5 client | No exact client build or Fabric loader supplied. | Unsupported. |

## JEI 1.12.2 coverage checklist

Exact input: `test-mods/jei1.12.2.jar`, JEI 4.16.1.301,
Minecraft 1.12.2, Forge dependency `14.23.5.2816` or later,
SHA-256 `DAD60317D8FDF891AD7841ABF838564EA7CBD317EC85D92F57DCB8F55602124B`.
The JAR manifest declares `FMLAT: jei_at.cfg`; that access transformer
widens renderer, texture map, recipe book, and Ingredient fields.
The `@Mod` class declares a required Forge dependency and delegates pre-init,
init, and load-complete lifecycle calls to JEI startup proxies.

### Demonstrated

- [x] Original JAR identity/hash checked by the targeted test.
- [x] The following original class bytes are staged unchanged and checked
  byte-for-byte: `IngredientListElementFactory`, `IngredientListElement`,
  `IngredientListElementComparator`, `IngredientOrderTracker`,
  `IngredientInformation`, `LegacyUtil`, `Log`, `GeneralizedSuffixTree`,
  nested `Pair`, `Node`, `Edge`, and `ISearchTree`.
- [x] Original `IngredientListElementFactory.createBaseList`,
  `addToBaseList`, and original element/comparator behavior run against the
  matching JVM runtime and translated TeaVM output.
- [x] Original `GeneralizedSuffixTree.put`, `search`, and `trimToSize`
  execute through TeaVM output and are compared to original JVM execution.
- [x] Original `IngredientFilter.addIngredients`, `modesChanged`,
  `IngredientFilterBackgroundBuilder.run`, `setFilterText`, and
  `getIngredientList` run against matching original-JVM results for 837
  corpus-derived, prefixed, intersecting, case, Unicode, absent, and repeated
  queries. A second initialization with a three-item subset verifies that a
  rebuild replaces rather than leaks old filter results.
- [x] Original JEI `ConfigValues` defaults are used unchanged: tooltip search
  enabled; mod-name search requires its prefix; ore-dictionary, creative-tab,
  color, and resource-ID search disabled. The matching JVM test executes the
  unmodified Translator against the mapped runtime; TeaVM uses a locale
  boundary fed by the actual client language setting.
- [x] Search parity against 411 real client registry entries: 829 queries
  include each registry ID and display name, an absent query, repeated query,
  case and Unicode queries, and a post-trim insertion.
- [x] The supplied client registry is read at runtime. Each record is a
  registered item type represented by its registry ID and the display name
  of a one-count, metadata-0 ItemStack.
- [x] The browser diagnostic UI returns those translated search matches.
  This UI is not JEI's original UI.
- [x] Browser execution creates 411 original JEI ingredient elements once;
  the real compatibility progress bar reaches 411/411 and is popped.
- [x] Profile `NonNullList` factory, clear, null-validation, and fixed-size
  behavior are compared with the matching mapped Minecraft 1.12.2 class.
- [x] Client hook installation, query input, and displayed results were
  exercised through profile-edit, main-menu, and options screen transitions.
  The translated factory/search hooks and diagnostic panel install counts
  remain one, and the client returns to its main menu.

### Not demonstrated / unsupported

- [ ] Full `IngredientFilter` lifecycle: JEI screen listeners, persisted
  blacklist/edit-mode behavior, mutable configuration, hidden-state updates,
  and runtime add/remove mutations. The demo exercises the original filter
  methods over a fixed registered-item corpus and supports full reinitialization.
- [ ] Full item variants. The current bridge includes only the default
  metadata-0 stack per item type; it does not enumerate creative variants.
- [ ] JEI ingredient types other than the current item-type records.
- [ ] Recipe registration from vanilla or other mods; original `RecipeMap`,
  `RecipeRegistry`, and `InternalRecipeRegistryPlugin` behavior.
- [ ] Recipe lookup by input/output focus, category/catalyst handling, recipe
  transfer, and third-party recipe plugins.
- [ ] Deferred `IngredientFilterBackgroundBuilder` work: original indexing
  completes synchronously for the current corpus, but the profile cannot
  provide Forge client-tick delivery when its 10-second budget is exceeded.
- [ ] JEI lifecycle, Forge event bus, full registries/mod discovery,
  capabilities, access-transformer application, original UI, rendering,
  input, bookmark/config persistence, resources, and remaining JEI classes.
- [ ] Compatibility with arbitrary Forge mods or any client backend other than
  the exact readable Eaglercraft 1.12.2 JS profile.

### Ingredient/filter compatibility boundaries

The original factory's reachable services are now exercised separately:

- The JVM oracle uses ForgeGradle's mapped Minecraft 1.12.2 output and the
  mapped Forge 14.23.5.2816 artifact. `IngredientListElementFactory` pushes a
  Forge progress bar, steps once per registry value, and pops only after all
  steps; the original-JVM test confirms no bars remain afterward.
- The browser profile's stateful `ProgressManager` reports each step through
  the installed UI callback, rejects over-stepping/incomplete pops, and
  finishes at 411/411.
- `NonNullList.func_191196_a`, `func_191197_a`, `func_193580_a`, `set`, and
  `clear` are implemented at the Minecraft API boundary. Tests compare
  default reset, fixed-size mutation failures, null validation, and negative
  size behavior with the exact mapped Minecraft class.
- `IIngredientRenderer.getTooltip` receives actual normal/advanced tooltip
  lines captured by the supplied client's `ItemStack.getTooltip` for registered
  metadata-0 stacks. The original filter uses the configured normal-tooltip
  flag and original `IngredientInformation` normalization.
- Matching Guava 21.0 and Commons Lang 3.5 classes are included for the
  reachable closure. `NonNullList` operations are compared with mapped
  Minecraft 1.12.2; the stateful progress manager reports and closes original
  JEI's progress bars.
- The JS `Translator` boundary implements only locale-sensitive lowercase,
  configured from the actual `GameSettings.language` client field. The original
  JVM oracle uses the unmodified JEI `Translator`. A narrow Minecraft context
  adapter prevents the desktop client static initializer from running; it
  does not provide a player or general Minecraft APIs.
- The client-derived helper supports actual namespace/mod IDs and tooltip
  search. Ore dictionary, creative-tab data, color names, mod display names,
  blacklist/config persistence, Forge events, and non-default search-mode
  settings remain unavailable. Unsupported helper operations fail explicitly.

The next JEI milestone should add ingredient variants and runtime registry
mutation only after defining corresponding real client data and lifecycle
services. Recipe APIs and the original JEI UI remain separate, larger paths.

Recipes should be a later separate milestone, after original ingredient
filter parity passes. Its initial path should use original JEI recipe registry
and lookup classes (including the exact required portions of `RecipeMap`,
`RecipeRegistry`, and `InternalRecipeRegistryPlugin`) over recipes genuinely
registered by a compatible source. The current Eaglercraft client does not
provide Forge's recipe-plugin registrations, so no recipe corpus is presently
available to claim.

## Universal Tweaks exact-input assessment

Input: `test-mods/universaltweaks.jar`, 2,021,938 bytes,
SHA-256 `9386ED5A00CC8B86AE5369A8C0D71AE8FA12711AD419767C2A7DA1977C7F7870`.

Evidence in the JAR:

- `mcmod.info`: Universal Tweaks 1.21.0, Minecraft 1.12.2.
- Manifest: Forge `FMLCorePlugin` is
  `mod.acgaming.universaltweaks.core.UTLoadingPlugin`; it also declares
  `FMLCorePluginContainsFMLMod`, `ForceLoadAsMod`, and
  `FMLAT: universaltweaks_at.cfg`.
- `UTLoadingPlugin` implements Forge `IFMLLoadingPlugin` and MixinBooter's
  `zone.rong.mixinbooter.IEarlyMixinLoader`. Its ASM transformer list is
  empty; it dynamically queues mixin configs according to client/server side
  and predicates. This does not mean it has no transformations: its mixin
  configs are the transformation mechanism.
- The JAR contains 425 Mixin JSON configs and 610 classes under mixin
  packages, organized into bugfixes, tweaks, vanilla accessors, and optional
  other-mod integrations. Configs declare client, server, and common mixins.
- The loading plugin depends on Forge and the external MixinBooter API/
  implementation; those framework classes are not bundled in this JAR.
  Mixin framework and Minecraft target classes are also external runtime
  dependencies. Optional integrations name many other mods.
- Its access transformer exposes the advancement tab enum and values.
- Representative method-level evidence from original class annotations:
  `UTContainerRepairMixin` redirects the second `ItemStack.isItemStackDamageable`
  invocation in `ContainerRepair.updateRepairOutput`;
  `UTComparatorTimingMixin` redirects the second
  `BlockRedstoneComparator.calculateInputStrength` call in `calculateOutput`
  and injects at the head of `shouldBePowered`; the client-only
  `UTEntityRendererMixin` wraps a block-layer rendering call inside
  `EntityRenderer.renderWorldPass`. These examples show that the transformations
  alter concrete vanilla method bodies; they are not a complete inventory of
  Universal Tweaks' 425 configs.

Disposition:

| Target | Status and reason |
| --- | --- |
| Supplied Eaglercraft 1.12.2 JS client | Not compatible as supplied. The MC version number matches, but the client has no Forge coremod launch, MixinBooter, Forge lifecycle, or class-bytecode Mixin application path. The existing JS hook is not an equivalent for 425 mixin configs and an access transformer. |
| Minified JS client | Unsupported; no build-specific mapping or transformation mechanism established. |
| 26.2 WASM client | Unsupported; no class/method transform interface established. |
| Matching Forge 1.12.2 JVM client | The JAR declares the correct MC family, but requires the exact Forge/coremod/MixinBooter environment and optional mod set. No such runtime was supplied or tested. |

No Universal Tweaks code was translated or run. No bugfix/optimization should
be marked active from this metadata or class inspection.

## Lithium exact-input assessment

Input: `test-mods/lithiumfabric.jar`, 349,400 bytes,
SHA-256 `9067B45509FC3023D76BFF85AD030EF01D30E5A9FBFE7815FF666AD31BBA4D28`.

Evidence in `fabric.mod.json` and `lithium.mixins.json`:

- Lithium 0.6.6, Fabric entry point
  `me.jellysquid.mods.lithium.common.LithiumMod`.
- Declared dependencies: Fabric Loader `>=0.10.5`; Minecraft versions
  `1.16.2`, `1.16.3`, `1.16.4`, and `1.16.5`. Environment is `*`, so the
  artifact is intended for both client and server contexts. It explicitly
  declares a break with OptiFabric.
- The mixin config is required, uses a Mixin plugin and Java 8 compatibility,
  with `defaultRequire: 1`. The JAR contains 134 mixin classes and 219 total
  class files. The mixins patch AI goals/entity tracking, allocation paths,
  chunks/world ticking, entity collisions, block/entity ticking, shapes, and
  other 1.16 internals.
- The single `lithium.mixins.json` declares 130 entries in its common
  `mixins` list and has no explicit `client` or `server` list. The Fabric
  metadata uses `environment: "*"`. `LithiumMixinPlugin.shouldApplyMixin`
  gates entries through Lithium's option/config rules; the exact active set
  is therefore configuration-dependent, not established by compilation.
  Representative targets include `GoalSelector`'s constructor at RETURN and
  redirects within `Entity.adjustMovementForCollisions` for voxel-shape and
  stream operations. The Mixin annotations use intermediary class names and
  reference-map remapping, so target resolution requires the matching game
  version/mappings and a live Mixin runtime.
- The Fabric Loader/Mixin runtime, exact 1.16.2-1.16.5 Minecraft classes and
  mappings, plus any required Fabric API services are external runtime
  requirements. The metadata does not declare a separate Fabric API dependency.

Disposition:

| Target | Status and reason |
| --- | --- |
| Supplied Eaglercraft 1.12.2 JS client | Version and loader mismatch: this Lithium artifact requires Minecraft 1.16.2-1.16.5 and Fabric Loader. It cannot be assessed as a 1.12.2 optimization merely because both are Minecraft clients. |
| Supplied 26.2 WASM client | No matching game-version or Fabric/Mixin integration evidence. Unsupported. |
| Exact intended target for this JAR | A Minecraft 1.16.2, 1.16.3, 1.16.4, or 1.16.5 client/server with a compatible Fabric Loader and Mixin runtime. No such target client was supplied or tested. |

No Lithium code was translated or run; no optimization is claimed active.
To assess it further, supply/select no replacement implicitly: provide the
exact 1.16.2-1.16.5 target build and its matching source/build artifacts if
bytecode-level injection verification is required.

## Artifact handling

The assessed mod JARs remain under ignored `test-mods/*.jar`. Generated
translation, JVM comparison data, client-derived corpus, and isolated browser
client remain under ignored `target/`. The repository's own test fixture
`test-fixture/test-main.jar` is tracked separately and is not a third-party
mod input.

The additional `test-mods/jei.jar` is a separate JEI 19.51.0.418 artifact
(SHA-256 `8BC3936D869E4040A5649C58B7E8BE1C78C82A245C0DC07BDD68AB49121A222C`).
It was not substituted for the exact JEI 1.12.2 input in this assessment.
