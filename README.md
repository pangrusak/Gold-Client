# Gold Client

Gold Client is an experimental tool for analyzing Minecraft Modrinth JARs and preparing them for translation to Eaglercraft-compatible code.

## Current goal

The first milestone is **analysis**, not translation. Given a mod JAR, Gold Client should be able to report:

- mod name and version
- Minecraft version
- loader
- classes, methods, and fields
- declared dependencies
- optional mod dependencies
- referenced Minecraft APIs
- mixin configuration/classes
- entrypoints

The project also has experimental method-to-Java and optional TeaVM
translation paths. They do not load mod-loader lifecycle hooks or provide the
Minecraft/loader APIs a translated mod expects.

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

The analyzer is intentionally deterministic. AI-assisted translation will be added later for cases that cannot be handled by explicit mappings and transformations.


## Experimental Eaglercraft JavaScript entrypoint

The JavaScript export now includes `eaglercraft-forge-entry.js` and a generic HTML injector for the supplied Eaglercraft 1.12.2 client profile. The injector validates the client title and required generated files, then adds a browser ES-module entrypoint without rewriting the client's existing JavaScript.

After generating a JavaScript bundle, inject it with:

```powershell
node .\client-profiles\eaglercraft-1.12-js\inject-gold-client.cjs .\client.html .\translated-js .\target\client-with-gold.html
```

Serve the output HTML and the translated bundle from paths matching the generated relative URL. The entrypoint exposes `window.goldClientStartForgeMods()` and reports its status through `window.goldClientForgeEntrypoint.getStatus()`.

**Important:** injecting the module is not the same as making a Forge mod work. Startup intentionally fails until `window.goldClientForgeBindings` is installed with real implementations for the Eaglercraft client, Forge services, registries, events, rendering, resources, networking, configuration, logging, and logical side. The repository currently generates the strict host contract and startup bridge; it does not automatically implement all of those game APIs. Mods that rely on mixins/coremods, unsupported bytecode, server behavior, or missing APIs will still need compatibility work.
