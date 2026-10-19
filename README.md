# Gold Client

Gold Client is an experimental tool for analyzing Minecraft Modrinth JARs and preparing them for translation to Eaglercraft-compatible code.

## Current status (October 2026)

Gold Client is an experimental Java-bytecode-to-JavaScript translation pipeline aimed at Forge 1.12.2 mods and an Eaglercraft 1.12.2 client. It is beyond metadata analysis: it can export per-class JavaScript modules, a mod index/manifest, preserved resources, diagnostics, and a Forge startup bridge. The translator handles a growing subset of Java control flow, exceptions, lambdas/method references, fields, and class initialization.

**This is not yet a general-purpose Forge mod converter.** Successful export means the represented bytecode was emitted; it does not prove semantic equivalence or that the translated mod runs in Minecraft. The target runtime still needs concrete implementations for Minecraft/Forge APIs, events, registries, rendering, resources, networking, configuration, logging, and other services.

The latest previously recorded JEI 1.12.2 benchmark reported 371 classes, 2,195 methods, zero methods marked incomplete in the represented IR, and **348 adapter-required declarations**. These are different measures: zero incomplete methods does not mean zero adapters or complete game compatibility. The adapter work is intended to reduce the latter count by implementing reusable runtime mappings, not by relabeling unresolved APIs as supported.

**Build/CI status:** the loader work in this worktree has been verified with `mvn package`, `mvn --batch-mode test` (246 tests, 0 failures, 0 errors, 3 optional skips), and both standalone Node test files. The older `5e7ef56` failure note is historical; re-run the build before repeating it.

## Usage

Build with Maven (this does not run `mvn clean` or erase the rest of `target/`):

```bash
mvn package
```

Run the unit tests without packaging with `mvn test`. The optional JEI/Forge integration tests run when their external test JAR and prepared runtime inputs are present; otherwise Maven marks those tests as skipped. The profile's `build.ps1` prepares its pinned runtime inputs and then runs the full package-and-injection pipeline.

Analyze a mod:

```bash
java -jar target/gold-client-0.1.0-SNAPSHOT.jar path/to/mod.jar
```

The report separates required and optional dependencies declared in mod
metadata from platform adapter requirements. It does not call a dependency
"missing" unless a target runtime classpath is supplied; no runtime
classpath-resolution option is currently implemented.

TeaVM compilation is a separate, opt-in path and requires an explicitly
selected class with `public static void main(String[])`:

```bash
java -jar target/gold-client-0.1.0-SNAPSHOT.jar path/to/mod.jar --to-js output --main-class example.Entry
```

Fabric/Forge mod entrypoints are loader lifecycle hooks, not TeaVM `main`
methods, and are never selected automatically. Successful TeaVM compilation
does not mean that an arbitrary Minecraft mod runs in Eaglercraft: Minecraft,
Forge/Fabric, rendering, event-bus, and other platform APIs still need real
runtime implementations or adapters. Gold Client does not provide no-op
loader or graphics stubs.

Build one shared JavaScript loader bundle from several Forge 1.12.2 mod JARs:

```bash
java -jar target/gold-client-0.1.0-SNAPSHOT.jar loader \
  --input test-mods/appleskin1.12.2.jar \
  --input test-mods/jei1.12.2.jar \
  --output output/forge-loader \
  --report output/forge-loader/loader-plan.json
```

`--input` may be repeated. Duplicate class definitions across inputs are
rejected. The bundle contains one class registry, merged resources, a
phase-major FML lifecycle dispatcher, dependency-ordered mod construction,
static `@Mod.EventBusSubscriber` registration, and runtime interception for
`MinecraftForge.EVENT_BUS.register(...)` calls made by translated code. The
generated `loader-plan.json` records inputs, hashes, counts, load order,
blockers, and whether the lifecycle plan is ready for a real host.

A successful loader build is still only a translated bundle. It is not proof
that the mods run. The command exits with status `2` when lifecycle blockers
or incomplete methods remain, but still writes the bundle and plan for
diagnostics. A real host must supply `window.goldClientForgeBindings`;
Mixin/coremods, access transformers, and most Minecraft/Forge APIs remain
unsupported or unbound.

`test-mods/xaeros.jar` is a legacy Xaero's Minimap 26.6.0 Forge mod targeting
Minecraft 1.12.2. Its metadata declares no additional mod dependencies; the
analyzer detects Forge platform integration requirements. This is an analysis
fixture, not evidence that Xaero can currently be translated or run.

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
