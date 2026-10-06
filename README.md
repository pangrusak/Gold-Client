# Gold Client

Gold Client is an experimental tool for analyzing Minecraft Modrinth JARs and preparing them for translation to Eaglercraft-compatible code.

## Current goal

The first milestone is **analysis**, not translation. Given a mod JAR, Gold Client should be able to report:

- mod name and version
- Minecraft version
- loader
- classes, methods, and fields
- declared dependencies
- referenced Minecraft APIs
- mixin configuration/classes
- entrypoints

Translation will be built on top of this structured information.

## Usage

Build with Maven:

```bash
mvn clean package
```

Analyze a mod:

```bash
java -jar target/gold-client-0.1.0.jar path/to/mod.jar
```

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
Future translation pipeline
        ↓
Eagler-compatible Java / JavaScript
```

The analyzer is intentionally deterministic. AI-assisted translation will be added later for cases that cannot be handled by explicit mappings and transformations.
