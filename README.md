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

Build with Maven:

```bash
mvn clean package
```

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
