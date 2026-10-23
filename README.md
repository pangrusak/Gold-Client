# Gold Client

Gold Client's target product is a generic Minecraft 1.12.2 Forge → Eaglercraft translator: point it at an arbitrary 1.12.2 Forge mod JAR and get one JavaScript mod file to upload to the EaglerForge mod loader (the injected Eaglercraft 1.12.2 client's "Upload Mod (.js)" button). It also analyzes mod JARs and exports per-class JavaScript for inspection.

## Goal

- **Target input:** any arbitrary Minecraft 1.12.2 Forge mod, plus the Forge 1.12.2 artifacts it needs.
- **Target output:** one JavaScript mod file that loads through EaglerForgeInjector's `ModAPI`/Mod Manager without hand-editing the translated code.
- **Not the goal:** per-mod hand porting, support limited to the tracked test mods, or shipping stubbed/no-op platform APIs so a mod only appears to load.

"Arbitrary" is the requirement, not a current achievement. This document is deliberately stricter than the goal: each checkpoint reports what is complete, incomplete, stubbed, declaration-only, adapter-required, or blocked instead of claiming the goal is met. `docs/mod-compatibility-roadmap.md` holds the per-input evidence.

## Current status (October 2026)

Gold Client is an experimental Java-bytecode-to-JavaScript translation pipeline aimed at Forge 1.12.2 mods and an Eaglercraft 1.12.2 client. It is beyond metadata analysis: it can export per-class JavaScript modules, a mod index/manifest, preserved resources, diagnostics, and a Forge startup bridge. The translator handles a growing subset of Java control flow, exceptions, lambdas/method references, fields, and class initialization. Generated static fields are installed as own data properties on the translated class constructor, so Java fields named `name` or `length` do not read or write JavaScript function metadata; a field that cannot be installed (such as `prototype`) is kept in a runtime side map instead of replacing the class prototype, and a static method with that name dispatches through the runtime method table rather than emitting invalid JavaScript. Built-in adapters cover the self-contained `ResourceLocation`/`BlockPos`/`Vec3i`/`ChunkPos` value types, common 1.12.2 enums, text components/styles, and the full NBT tag family, so those owners do not need a host binding. Adapter values also receive SRG aliases from the loaded member map, so obfuscated inputs can call `func_*`/`field_*` names against the same implementations. Translated subclasses of native Minecraft classes are initialized through EaglerForge's `Class__init_N($this, ...)` super initializers after attaching the native prototype to the translated prototype chain; a host that exposes only an allocating constructor fails explicitly instead of copying native state.

**This is not yet a general-purpose Forge mod converter.** Successful export means the represented bytecode was emitted; it does not prove semantic equivalence or that the translated mod runs in Minecraft. The target runtime still needs concrete implementations for Minecraft/Forge APIs, events, registries, rendering, resources, networking, configuration, logging, and other services.

The latest recorded AppleSkin + JEI loader build translates 393 classes and 2,287 methods with zero adapter-required declarations and **zero incomplete methods**; `translationComplete` is true for that pair. That is a structural translation result, not a runtime claim: zero incomplete methods does not mean zero platform bindings, and an adapter declaration is not a runtime implementation. The remaining platform work is listed in `loader-plan.json`, `forge-transformations.json`, and the diagnostics files.

The current `loader --single-file --compact` smoke matrix covers 12 tracked JARs, including Biomes O' Plenty, Clumps, EnchantmentDescriptions, FancyMenu, Konkrete, MixinBooter, Universal Tweaks, and Xaero's Minimap. Every generated `gold-client-forge.js` passes `node --check`; per-input class counts, incomplete methods, and explicit blockers are recorded in `docs/mod-compatibility-roadmap.md`.

**Build/CI status:** the current checkpoint passed `mvn --batch-mode test` (292 tests, 0 failures, 0 errors, 3 optional skips), `mvn --batch-mode verify`, and the standalone Node suites. The older `5e7ef56` failure note is historical.

## Usage

Build with Maven (this does not run `mvn clean` or erase the rest of `target/`):

```bash
mvn package
```

Run the unit tests without packaging with `mvn test`. The optional JEI/Forge integration tests run when their external test JAR and prepared runtime inputs are present; otherwise Maven marks those tests as skipped. The profile's `build.ps1` prepares its pinned runtime inputs and then runs the full package-and-injection pipeline.

Analyze a mod:

```bash
java -jar target/gold-client-1.jar path/to/mod.jar
```

The report separates required and optional dependencies declared in mod
metadata from platform adapter requirements. It does not call a dependency
"missing" unless a target runtime classpath is supplied; no runtime
classpath-resolution option is currently implemented.

Create one self-contained Eaglercraft 1.12.2 HTML client from an explicit set of
mod and dependency JARs:

```bash
java -jar target/gold-client-1.jar --create-client \
  test-mods/appleskin1.12.2.jar \
  --output clients/build/appleskin/gold-client.html
```

The command downloads the pinned 75,729,830-byte client into `cache/clients/`,
verifies its SHA-256, builds the shared loader bundle, and inlines the bundle,
member map, bindings adapter, mod resources, and provenance into one HTML file.
`--input <jar>` and positional JARs are equivalent. Existing outputs are kept
unless `--force` is passed. Builds are one-shot: there is no append or
incremental update, so any mod-set change requires creating a new client.

Exit status `0` means the represented translation is complete, `2` means the
HTML was written but blockers or incomplete methods remain, and `1` means a
fatal error. The plain-text report names the affected classes and methods.
Node.js is required because the packaged inliner runs as a Node script.

The HTML embeds each input JAR's resources under `globalThis.GoldClientResources`.
The runtime exposes them through `GoldClientRuntime.hasResource`,
`getResourceBytes`, `getResourceText`, and `listResources`, and the host
adapter exposes the `resources` service with `has`, `list`, `get`, `getBytes`,
and `getText`. Translated class literals also provide Java-style
`getResource`, `getResourceAsStream`, and `getClassLoader()` for relative and
absolute JAR paths. This does not cover `getClass().getResourceAsStream(...)`,
Forge resource-pack APIs, or Minecraft `IResourceManager`; those remain
unbound. Embedding a resource is not evidence that the mod loads or renders it.

Start the local browser UI for the same workflow:

```bash
java -jar target/gold-client-1.jar --ui --open
```

The UI binds to `127.0.0.1` (default port `8080`), accepts dropped or selected
JARs, shows translation blockers, and offers the generated HTML for download.
It uses the same one-shot build path as `--create-client`; it is not a
multi-mod project editor.

TeaVM compilation is a separate, opt-in path and requires an explicitly
selected class with `public static void main(String[])`:

```bash
java -jar target/gold-client-1.jar path/to/mod.jar --to-js output --main-class example.Entry
```

Fabric/Forge mod entrypoints are loader lifecycle hooks, not TeaVM `main`
methods, and are never selected automatically. Successful TeaVM compilation
does not mean that an arbitrary Minecraft mod runs in Eaglercraft: Minecraft,
Forge/Fabric, rendering, event-bus, and other platform APIs still need real
runtime implementations or adapters. Gold Client does not provide no-op
loader or graphics stubs.

Build one shared JavaScript loader bundle from several Forge 1.12.2 mod JARs:

```bash
java -jar target/gold-client-1.jar loader \
  --input test-mods/appleskin1.12.2.jar \
  --input test-mods/jei1.12.2.jar \
  --output output/forge-loader \
  --report output/forge-loader/loader-plan.json \
  --single-file --compact
```

`--input` may be repeated. `--library <dependency.jar>` repeats an API or
dependency JAR whose classes and resources are bundled, but whose own `@Mod`
and `@Mod.EventBusSubscriber` declarations are excluded from automatic
lifecycle loading. Plain mod inputs still supply the load order. Duplicate
class definitions across inputs and libraries are rejected. The bundle contains
one class registry, merged resources, a
phase-major FML lifecycle dispatcher (including `FMLConstructionEvent` before
`preInit`), dependency-ordered mod construction, static
`@Mod.EventBusSubscriber` registration, and runtime interception for
`MinecraftForge.EVENT_BUS.register(...)` calls made by translated code. Plain
`required:`/`optional:` dependency clauses are parsed for presence; only
`before`/`after` clauses constrain load order. The generated
`loader-plan.json` records inputs, hashes, roles, counts, library class counts,
load order, blockers, and whether the lifecycle plan is ready for a real host.
A library is not proof that its code is API-compatible: Mixin/coremod/AT
surfaces in any bundled JAR remain explicit blockers.

`--single-file` additionally writes `gold-client-forge.js`, a classic-script
bundle that inlines every generated module plus the JAR resources under
`globalThis.GoldClientResources`, and exposes
`globalThis.GoldClientForge` (`start`, `stop`, `status`, `mods`, `blockers`,
`runtime`, `classes`, `getClass`). `--compact` strips full-line comments and
blank lines from that file. The Eaglercraft profile injector prefers this file
when present and emits a classic `<script src="...">` instead of the ES-module
entrypoint. The bundle is self-contained JavaScript, but it still needs
`window.goldClientForgeBindings` from a real host before `start()`.

A successful loader build is still only a translated bundle. It is not proof
that the mods run. The command exits with status `2` when lifecycle blockers
or incomplete methods remain, but still writes the bundle and plan for
diagnostics. A real host must supply `window.goldClientForgeBindings`;
Mixin/coremods, access transformers, and most Minecraft/Forge APIs remain
unsupported or unbound.

### EaglerForge client candidate

The stripped `EaglerForgeInjector/` and the host adapter can package an isolated
Eaglercraft 1.12.2 client candidate without modifying the input:

```bash
node client-profiles/eaglercraft-1.12-js/build-forge-client.cjs \
  unminified-clients/1.12.2.html /tmp/gold-client-forge \
  --bundle output/appleskin-only/gold-client-forge.js
```

The builder injects EaglerForge 2.7.97, the pinned SRG-to-MCP member table,
the `goldClientForgeBindings` adapter, and an optional single-file Gold Client
bundle. It writes `forge-client-provenance.json` with input/output hashes and
the adapter/member-map hashes, and refuses to overwrite a non-empty output
directory. `browserExecutionVerified` and `modExecutionVerified` stay `false`
in that provenance file because packaging is not execution evidence.

A real headless-Firefox load of a candidate has been performed separately. It
confirmed that `ModAPI`, `goldClientForgeBindings`, and `GoldClientForge` exist,
that the adapter loads all 19,659 member mappings, and that SRG calls such as
`TextFormatting.func_96300_b` resolve through the real client. Native field
proxies, virtual config persistence, and explicit failure for unsupported
registry/network operations were also exercised.

The same candidate completed an AppleSkin `GoldClientForge.start(bindings)`
call: `status().started` became true, the single mod was constructed, and its
preInit, init, postInit, and loadComplete phases ran without a binding error.
This is real lifecycle execution evidence for the translated AppleSkin class,
not proof that its HUD/tooltip rendering, gameplay effects, or event handlers
work once the game starts. The adapter remains strict: a missing platform API
throws instead of returning a no-op.

`NetworkRegistry.INSTANCE.newSimpleChannel(...)` and packet-class registration
are kept virtual because that registration is metadata. Any actual packet send
throws `unsupported-network`; it is not silently dropped. Translated classes
that extend native Minecraft classes still fail explicitly with
`unsupported-platform-superclass` rather than faking the superclass state.

`test-mods/xaeros.jar` is a legacy Xaero's Minimap 26.6.0 Forge mod targeting
Minecraft 1.12.2. Its metadata declares `xaerolib` as a required dependency;
`test-mods/xaerolib.jar` is tracked so the pair can be analyzed together. A
`--create-client` build of the pair still exits `2`: both JARs contain coremod
plugins and ASM class transformers that Gold Client does not execute, and one
`DropDownWidget.drawSlot` method has an unresolved control-flow jump. This is
analysis/blocker evidence, not evidence that Xaero can currently be translated
or run.

## Design

```
Mod JAR
  ↓
Gold Client Analyzer
  ├─ Metadata
  ├─ Class structure
  ├─ Dependencies
  ├─ Minecraft API references
  └─ Mixins / entrypoints
        ↓
Experimental method translation / explicit TeaVM entrypoint
  (partial bytecode support; platform APIs still need real adapters)
        ↓
Java source / JavaScript output (not a runnable mod by itself)
```

The translator prioritizes deterministic bytecode transformations and explicit compatibility mappings. Any future AI-assisted path must preserve diagnostics and must not count guessed or unsupported behavior as complete.


## Experimental Eaglercraft JavaScript entrypoint

The JavaScript export now includes `eaglercraft-forge-entry.js` and a generic HTML injector for the supplied Eaglercraft 1.12.2 client profile. The injector validates the client title and required generated files, then adds a browser ES-module entrypoint without rewriting the client's existing JavaScript.

After generating a JavaScript bundle, inject it with:

```powershell
node .\client-profiles\eaglercraft-1.12-js\inject-gold-client.cjs .\client.html .\translated-js .\target\client-with-gold.html
```

Serve the output HTML and the translated bundle from paths matching the generated relative URL. The entrypoint exposes `window.goldClientStartForgeMods()` and reports its status through `window.goldClientForgeEntrypoint.getStatus()`.

**Important:** injecting the module is not the same as making a Forge mod work. Startup intentionally fails until `window.goldClientForgeBindings` is installed with real implementations for the Eaglercraft client, Forge services, registries, events, rendering, resources, networking, configuration, logging, and logical side. The repository currently generates the strict host contract and startup bridge; it does not automatically implement all of those game APIs. Mods that rely on mixins/coremods, unsupported bytecode, server behavior, or missing APIs will still need compatibility work.
