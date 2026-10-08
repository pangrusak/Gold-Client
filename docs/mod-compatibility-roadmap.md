# Mod compatibility evidence and roadmap

This document records evidence from the exact external JARs in `test-mods/`
and the current supplied client profile. A translated algorithm slice does not
mean that the whole mod, its loader integration, or its transformations work.
Inputs and generated outputs remain outside tracked repository contents.

## Current target and backend status

| Target/backend | Evidence | Status |
| --- | --- | --- |
| Supplied readable Eaglercraft 1.12.2 JavaScript client | The input HTML identifies Eaglercraft 1.12.2. Its generated JavaScript has client-specific item registry and screen-change hook symbols. | One JEI diagnostic search slice was exercised in the browser. This is not Forge support or general arbitrary-mod support. |
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
  byte-for-byte: `GeneralizedSuffixTree`, nested `Pair`, `Node`, `Edge`,
  `ISearchTree`, and `mezz.jei.util.Log`.
- [x] Original `GeneralizedSuffixTree.put`, `search`, and `trimToSize`
  execute through TeaVM output and are compared to original JVM execution.
- [x] Search parity against 411 real client registry entries: 829 queries
  include each registry ID and display name, an absent query, repeated query,
  case and Unicode queries, and a post-trim insertion.
- [x] The supplied client registry is read at runtime. Each record is a
  registered item type represented by its registry ID and the display name
  of a one-count, metadata-0 ItemStack.
- [x] The browser diagnostic UI returns those translated search matches.
  This UI is not JEI's original UI.
- [x] Client hook installation, query input, and displayed results were
  exercised in the browser; the client remained playable.

### Not demonstrated / unsupported

- [ ] JEI `IngredientListElementFactory` and `IngredientListElement` creating
  its original ingredient models from `IIngredientRegistry`.
- [ ] JEI `IngredientFilter` end-to-end: original text parsing, prefixed
  filters, intersections, blacklist/edit-mode behavior, cache invalidation,
  hidden-state updates, and listeners.
- [ ] Full item variants. The current bridge includes only the default
  metadata-0 stack per item type; it does not enumerate creative variants.
- [ ] JEI ingredient types other than the current item-type records.
- [ ] Recipe registration from vanilla or other mods; original `RecipeMap`,
  `RecipeRegistry`, and `InternalRecipeRegistryPlugin` behavior.
- [ ] Recipe lookup by input/output focus, category/catalyst handling, recipe
  transfer, and third-party recipe plugins.
- [ ] JEI lifecycle, Forge event bus, registries, mod discovery, capabilities,
  access-transformer application, original UI, rendering, input, config,
  bookmarks, resources, and all remaining JEI classes.
- [ ] Compatibility with arbitrary Forge mods or any client backend other than
  the exact readable Eaglercraft 1.12.2 JS profile.

### Next JEI milestone selected: original ingredient filtering

Translate and exercise JEI's original ingredient model and filtering path,
not a replacement filter:

1. Start from the exact 411 client-derived item records, retaining provenance
   hashes and the explicit default-stack-only limitation.
2. Resolve a bounded original-bytecode closure for
   `IngredientListElementFactory.createBaseList`,
   `IngredientListElement`, and `IngredientFilter`, plus their reachable JEI
   helpers. Do not stage substitute JEI classes.
3. Provide only the real client compatibility services that this closure
   requires, including the narrow ingredient registry/helper and required
   Minecraft collection/stack semantics. The services must use client data;
   unresolved behavior must fail explicitly rather than becoming a no-op.
4. Exercise original JEI filter queries, mode/prefix parsing, exclusions,
   repeated changes, and cache invalidation against original JVM behavior and
   translated output. Compare stable registry IDs and observable listener/
   result changes, not only compile success.
5. Retain the diagnostic UI only as a host for the translated original
   `IngredientFilter`; do not implement query matching or recipe behavior in
   the UI adapter.

**Stop condition:** stop this milestone if TeaVM's reachable original-code
closure requires a broad Forge runtime, an unprovided JEI lifecycle service,
or a Minecraft behavior that cannot be mapped to the actual supplied client.
Report the exact first unresolved type/method and do not replace it with a
sample implementation.

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
