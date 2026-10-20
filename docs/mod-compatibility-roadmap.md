# Mod compatibility evidence and roadmap

This document records evidence from the exact external JARs in `test-mods/`
and the current supplied client profile. A translated algorithm slice does not
mean that the whole mod, its loader integration, or its transformations work.
Inputs and generated outputs remain outside tracked repository contents.

The goal is generic: any arbitrary Minecraft 1.12.2 Forge JAR should translate
to one Eaglercraft/EaglerForge-loadable JavaScript mod file. The table and
checklists below record how far the current implementation is from that goal for
each input, and what remains explicitly unsupported.

## Current target and backend status

| Target/backend | Evidence | Status |
| --- | --- | --- |
| Supplied readable Eaglercraft 1.12.2 JavaScript client | The input HTML identifies Eaglercraft 1.12.2. Its generated JavaScript has client-specific item registry, tooltip, language, and screen-change hook symbols. | Original JEI ingredient creation, `IngredientFilter` indexing/querying, and suffix-tree searches run over 411 client-derived records. This is not Forge support or general arbitrary-mod support. |
| Minified JavaScript clients | No matching build profile or runtime verification. | Unsupported; retained as a product requirement. |
| Eaglercraft 26.2 WebAssembly | Existing assessment found WASM exports without Minecraft-facing methods. | Unsupported; retained as a product requirement. |
| Forge 1.12.2 client | No Forge runtime/loader is present in the supplied Eaglercraft client. | Unsupported. |
| Eaglercraft 1.12.2 with EaglerForge injected | The stripped EaglerForge injector, SRG-to-MCP table, binding adapter, and client-candidate builder are implemented. A headless-Firefox candidate run resolved real client members and completed AppleSkin's four client lifecycle phases. JEI still fails explicitly at a native `TextureMap` superclass, and rendering/network I/O remain unsupported. | Partial host adapter; AppleSkin lifecycle works, arbitrary mod support does not. |
| Fabric Minecraft 1.16.2-1.16.5 client | No exact client build or Fabric loader supplied. | Unsupported. |

## Multi-mod Forge loader bundle

The `loader` subcommand translates several Forge 1.12.2 JARs into one shared
JavaScript class registry and emits `forge-lifecycle.js`,
`eaglercraft-forge-entry.js`, `loader-plan.json`, and the existing translated
class/resource modules. It discovers `@Mod` entry classes and statically
declared event subscribers from original annotations.

### Single-file injection output

`loader --single-file [--compact]` additionally writes
`gold-client-forge.js`, a classic-script bundle that inlines the runtime, every
translated class module, the lifecycle, the host runtime, and the browser
entrypoint. It exposes `globalThis.GoldClientForge` with `start(bindings)`,
`stop(reason)`, `status()`, `mods`, `blockers`, `runtime`, `classes` (module
namespaces), and `getClass(name)` (the translated constructor). The file is
self-contained JavaScript, but it still requires
a real host binding object (`window.goldClientForgeBindings`) before `start()`.
The profile injector prefers this file when present and emits a classic
`<script src="...">`; otherwise it keeps using the ES-module entrypoint.

### EaglerForge adapter evidence

- [x] `client-profiles/eaglercraft-1.12-js/build-forge-client.cjs` injects the
  stripped EaglerForge 2.7.97 client runtime, the pinned 19,659-entry
  SRG-to-MCP table, and `goldClientForgeBindings` into an isolated copy of
  `unminified-clients/1.12.2.html`. The input hash is
  `b23decdca4ff9af58f63eea4e93772d8a0a1dcbb59781a32e505e9c9735d1e67`.
  The generated HTML is 85,514,257 bytes and has SHA-256
  `7c896eed194d83cdd56a0656ca9563f8ab3489619d224557a2fd6aff556d0894` for
  the AppleSkin candidate. The separate bundle and adapter are hashed in
  `forge-client-provenance.json`.
- [x] A real headless-Firefox load exposes `ModAPI`, `goldClientForgeBindings`,
  and `GoldClientForge`; the adapter reports 19,659 member mappings.
- [x] Real-client member resolution was exercised without starting a world:
  `TextFormatting.RED.getColorIndex()` returned `12`, SRG
  `TextFormatting.func_96300_b("red")` mapped to `getValueByName` and returned
  the red enum, and a wrong-arity call failed with an explicit
  `missing-method-arity` error.
- [x] An AppleSkin candidate completed `GoldClientForge.start(bindings)`:
  `status().started` became true and `preInit`, `init`, `postInit`, and
  `loadComplete` all ran. This proves the translated constructor, lifecycle
  dispatcher, event objects, virtual config file, Forge `Configuration`,
  `DecimalFormat`, Locale, Log4j, and virtual channel registration are
  reachable against the real injected client. It does not prove AppleSkin's
  HUD/tooltip rendering or gameplay effects work.
- [x] `NetworkRegistry.INSTANCE.newSimpleChannel(...)` and
  `registerMessage(...)` are virtual metadata registration. Any packet send
  throws `unsupported-network`; the adapter does not silently drop it.
- [x] A translated class extending a native Minecraft class fails with
  `unsupported-platform-superclass`. JEI currently reaches this at
  `mezz.jei.gui.textures.JeiTextureMap extends TextureMap`; the supplied
  client only exposes the one-argument `TextureMap` constructor, so copying
  or guessing the Forge constructor state would be unsafe.

### Demonstrated

- [x] `loader` accepts repeated `--input` arguments, rejects duplicate class
  definitions across inputs, and writes one merged bundle plus a report.
- [x] AppleSkin translates to 22 classes and one `appleskin` mod entry with a
  ready lifecycle plan.
- [x] AppleSkin plus JEI translates to 393 classes and two mod entries
  (`jei`, `appleskin`) with dependency ordering and a ready lifecycle plan.
  Four JEI methods remain incomplete: two `JeiTextureMap` methods combine
  several typed handlers with catch-all/finally cleanup, and two methods
  (`RecipeRegistry#addRecipe`, `JeiStarter#registerCategories`) merge
  multi-catch bodies with non-trivial control flow. The command reports them,
  lists them in `loader-plan.json` (`incompleteMethods`), and exits with status
  `2` rather than claiming a complete translation. Two `BookmarkList`
  try-with-resources methods were corrected by rendering the JVM catch-all
  handler as `finally` instead of an unreachable sibling `catch (Throwable)`.
  The two `JeiTextureMap` methods now leave their try block through a labeled
  break when the normal-path join sits past catch-only code; they remain
  incomplete because the merged multi-catch cannot prove finally behavior when
  a typed handler itself throws.
- [x] General mod smoke survey with `loader --single-file --compact` on the
  tracked inputs: AppleSkin (22 classes, 0 incomplete, ready), Waystones
  (85 classes, 0 incomplete, ready), JEI (371 classes, 4 incomplete), Xaero's
  Minimap (1280 classes, 13 incomplete; coremod and missing `xaerolib`
  blockers), Universal Tweaks (1191 classes, 3 incomplete; Mixin/coremod and
  missing dependency blockers), ConfigAnytime (3 classes, 0 incomplete;
  coremod blocker), and MixinBooter (1305 classes, 67 incomplete; no `@Mod`
  entry plus Mixin/coremod blockers). These are structural translation
  results, not proof that the mods run without real Minecraft/Forge bindings.
- [x] Generated AppleSkin and AppleSkin+JEI modules pass `node --check` and
  import through `forge-lifecycle.js` without executing game APIs.
- [x] `--single-file --compact` packs the AppleSkin+JEI build (398 modules) into
  one classic script. It loads without ESM imports, installs the browser
  entrypoint, and runs the same phase-major synthetic lifecycle, event
  hierarchy, subscriber, status, and stop assertions as the module bundle. This
  is a packaging result, not proof that a Forge host can execute either mod.
- [x] Static `@Mod.EventBusSubscriber` methods are discovered with owner,
  priority, and `receiveCanceled` metadata and registered before preInit.
- [x] Translated `MinecraftForge.EVENT_BUS.register(...)` calls are intercepted
  by the runtime and routed to the host event bus. Instance `@SubscribeEvent`
  methods are not registered automatically by the loader. A
  `register(SomeClass.class)` call registers that class's static
  `@SubscribeEvent` methods, and translated class literals are cached so
  `unregister`/identity checks use one object per class.
- [x] `stopForgeMods` drops every runtime-mirrored event-bus registration,
  including `manual:<class>` owners created outside a per-mod context, so a
  full stop does not leave those listeners behind.
- [x] The lifecycle dispatcher is phase-major: all mods are constructed before
  preInit, and every preInit handler runs before any init handler.
- [x] `@SidedProxy` client implementations, `@Mod.Instance`,
  `@Mod.Metadata`, and `@Mod.InstanceFactory` are discovered and validated.
- [x] A `@Mod(useMetadata = true)` entry with no literal `modid` resolves its
  mod ID from a single-entry `mcmod.info`, and a metadata ID that disagrees
  with a literal `@Mod(modid=...)` becomes an explicit blocker. A JAR with
  several `mcmod.info` entries does not claim a single metadata ID.
- [x] Missing required mod dependencies, duplicate IDs, missing proxies,
  unsupported handler signatures, server-only entries, and absent superclasses
  become explicit blockers rather than guessed behavior.
- [x] Java throwables keep their class identity. `new java.*Exception(...)` is
  lowered to a runtime throwable, and `instanceof`/`checkcast`/catch dispatch
  follow both translated superclasses/interfaces and a built-in hierarchy for
  common JDK exception, error, I/O, and concurrency classes.
- [x] Superclass and same-class constructor delegation (`super(...)` /
  `this(...)`) is routed through the runtime instead of emitting an invalid
  JavaScript `this(...)` call. Translated classes extending a JDK throwable
  initialize message, cause, suppression, and stack-trace state.
- [x] `Loader.isModLoaded(id)` is answered from the started bundle's mod set
  (plus `forge` and `minecraft`) while a generated loader is present, so
  optional-dependency checks do not need a host binding. The set is installed
  after `mod-index.js` is imported, so a query made from a class static field
  initializer still falls through to host bindings; lifecycle handlers see it.
  This runtime query is not part of `forge-compatibility-plan.json`, which stays
  conservative about `Loader` as a whole.
- [x] `net.minecraftforge.fml.relauncher.Side` is a built-in enum adapter.
  `Side.CLIENT`/`Side.SERVER` are stable singletons with `isClient`, `isServer`,
  `getOpposite`, `name`, `ordinal`, `values`, and `valueOf`, so generated side
  comparisons and network-message side filters work without a host binding.
  Host bindings and lifecycle events should return these same objects.
- [x] Static fields are installed with `defineStaticField` as own data properties
  on the translated constructor, so Java fields named `name`/`length` do not
  read or write JavaScript function metadata. A field named `prototype` is kept
  in a runtime side map instead of replacing the class prototype, and a static
  method named `prototype` (illegal as a JavaScript static class member)
  dispatches through the runtime method table. This is a lowering/runtime
  guarantee, not evidence that a mod runs.

### Not demonstrated / unsupported

- [ ] A complete real client host. The EaglerForge adapter implements the
  strict binding surface and AppleSkin's client lifecycle, but rendering,
  input, resource lookup, real packet I/O, capabilities, and most game APIs
  remain explicit unsupported boundaries.
- [ ] Mixins, coremods, access transformers, and Forge class transformation.
  Classes carrying `org.spongepowered.asm.mixin` annotations, or implementing
  `IFMLLoadingPlugin`/`IClassTransformer`, are detected and make the lifecycle
  plan `PARTIAL_WITH_BLOCKERS`; the loader never silently translates them as
  ordinary classes and claims the bundle is ready.
- [ ] Rendering, input, recipe registration/lookup, networking, resources,
  capabilities, and most Minecraft/Forge API calls.
- [ ] Precise classification of platform exceptions thrown by host bindings.
  A plain JavaScript error is treated as `java.lang.Throwable` unless its
  message starts with a known JDK exception class; hosts can throw
  `GoldClientRuntime.javaException(...)` when a specific Java type is required.
- [ ] Server-only mods in this client-side host. They are blocked explicitly.
- [ ] Any claim that `READY_FOR_REAL_FORGE_HOST` means the mods run. It means
  the discovered lifecycle plan has no structural blockers and is ready to be
  exercised against a real host.

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
  The multi-mod loader now discovers JEI lifecycle methods, its client proxy,
  and a static event subscriber, but no real host bindings have been exercised
  for them; this checklist item remains unchecked.
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
