# AGENTS.md

## What this repo is

Gold Client is a Java 17 / Maven CLI that analyzes Minecraft mod JARs, exports per-class JavaScript, and can compile an explicitly selected class with TeaVM. It is not itself a Minecraft mod: the root `fabric.mod.json` is a tracked Lithium test fixture, and `target/gold-client-1.jar` is the actual tool.

The product goal is a generic Minecraft 1.12.2 Forge → Eaglercraft translator: any arbitrary 1.12.2 Forge JAR in, one JavaScript mod file out for EaglerForgeInjector's Mod Manager ("Upload Mod (.js)"). Treat arbitrary-mod support as the target and every incomplete/stubbed/blocked method as a gap to close, never as silently-faked support.

A successful export only means the represented bytecode was emitted. It does not prove semantic equivalence or that a mod runs. Preserve the repo's evidence discipline: never report stubbed, adapter-required, guessed, or unsupported behavior as complete or working, and do not replace missing platform APIs with silent no-ops.

## Build and verification

Prerequisites: JDK 17, Maven, and Node.js 22. There is no lint/formatter/typecheck configuration; the CI gate is Maven plus two standalone Node test files.

```bash
mvn package                    # builds the shaded CLI JAR; does not clean target/
mvn test                       # all JUnit tests
mvn -Dtest=Class#method test   # one test
mvn --batch-mode verify        # full Maven CI gate
node --test client-profiles/eaglercraft-1.12-js/inject-gold-client.test.cjs
node --test bridge-proof/test-injector.cjs
```

- Do not run `mvn clean` casually after preparing the JEI/Forge integration inputs. It deletes the expensive `target/forge-baseline/` runtime. Prefer `mvn package` / `mvn test` locally; CI is free to clean.
- Maven does not run the two Node commands above. Some Maven tests also spawn `node`, so Node must be installed for the full suite.
- The optional JEI/Forge tests self-skip unless `target/forge-baseline/mdk/build/tmp/deobfMcSRG/deobfed.jar`, `target/forge-baseline/forge-mapped.jar`, and fastutil `7.1.0` in `~/.m2` exist. Preparing them requires the Windows PowerShell profile scripts, network access, and pinned JDK 8/17 archives, so Linux CI normally skips these paths. Do not treat a green run without them as JEI/Forge coverage.
- `-Dgoldclient.requireClientCorpus=true` rejects the small fixture fallback and requires the real 411-record browser corpus.
- CI runs only the two Node commands above; other `*.test.cjs` suites under `client-profiles/` (e.g. `build-forge-client.test.cjs`, `shared/*.test.cjs`) are run manually with `node --test <file>`.

## Entrypoints and flow

- CLI entry: `src/main/java/com/goldclient/Main.java`.
- `ModAnalyzer` builds `ModAnalysis` / `ClassModel`; `IntermediateRepresentationAnalyzer` and `ControlFlowAnalyzer` produce the IR.
- `JavaScriptTranslator` and `JavaTranslator` lower that IR; `JavaScriptModTranslationExporter` writes class modules, `mod-index.js`, manifests, runtime resources, and diagnostics.
- `JavaScriptBundleExporter` packs that module tree into one classic script; `EaglerForgeModExporter` adds the pinned member map, the EaglerForge binding adapter, and auto-start metadata for the Mod Manager upload file.
- `planner/` handles multi-JAR selection (`MultiJarPlanner`), byte-preserving staging (`PlanJarStager`), pinned inputs (`TranslationPipeline`), and the `pipeline` subcommand.

CLI distinctions that are easy to get wrong:

```bash
java -jar target/gold-client-1.jar mod.jar --translate-js out
java -jar target/gold-client-1.jar mod.jar --translate --output-dir out
java -jar target/gold-client-1.jar mod.jar --class a.b.C --method m --translate
java -jar target/gold-client-1.jar mod.jar --to-js out --main-class a.b.C
java -jar target/gold-client-1.jar pipeline --input a.jar --root a.b.C --report report.json
java -jar target/gold-client-1.jar loader --input a.jar --input b.jar --output out/ --report out/loader-plan.json [--single-file] [--mod-file out/gold-client-mod.js] [--compact]
```

- `pipeline` and `loader` are first-argument subcommands; everything else takes the mod JAR as first argument. `loader` builds one shared JS bundle from multiple JARs and exits `2` on blockers while still writing the bundle and plan.
- `--mod-file` is the Mod Manager upload artifact: one classic script with the translated bundle, member map, binding adapter, and auto-start. It is packaging evidence, not proof that an upload or the mod's game APIs work. The adapter/member-map sources are classpath resources copied from `client-profiles/shared/`, so keep those inputs in the Maven resource configuration.
- `--translate-js` is the custom whole-mod JavaScript exporter; `--to-js` is TeaVM compilation.
- `--translate` needs both `--class` and `--method`, or a whole-mod export with `--output-dir`.
- TeaVM requires a real `public static void main(String[])` selected via `--main-class`. Forge/Fabric loader entrypoints are never auto-selected.
- In `pipeline`, default `--reachability strict` blocks missing references. `--reachability teavm` defers structural references and requires `--stage`, `--to-js`, and `--main-class`; JS output is refused if runtime/external boundaries remain. Outputs must never overwrite input JARs.

## Fixtures and generated files

- `test-mods/*.jar` are exact, hash-pinned external inputs (especially JEI `test-mods/jei1.12.2.jar`). Never edit or substitute them; adjust fixtures/tests instead of weakening hash checks.
- `test-fixture/test-main.jar` is the repo's own tracked fixture, not a third-party mod.
- `dependency-reduced-pom.xml` is generated by the Maven shade plugin; do not hand-edit it.
- Tracked `*.bak` files and the root `*-inspection.txt`, `*-source.txt`, `translation-log.txt`, and `platform-context.txt` files are historical evidence, not live source. Leave them alone unless the task is explicitly about them.
- Generated output belongs under ignored `target/` or `output/` (both gitignored). `unminified-clients/1.12.2.html` is a tracked client input, but the Windows profile scripts (`build.ps1`, `serve-demo.cjs`) expect the absent `unminified-clients/Eaglercraft_1.12_Offline_en_US.html`; the forge-client builder accepts a path argument instead (see the profile README).
- `client-profiles/.../compat` classes are deliberate API-boundary implementations, not generic stubs. Keep unsupported operations explicit rather than making them silently succeed.
- `src/main/resources/com/goldclient/translator/gold-client-runtime.js` and `gold-client-adapters.json` are exported/copied into generated bundles; changes there affect generated output and parity tests.

## Conventions

- Main code targets Java 17, but generated Forge bootstrap/host Java is compiled with `--release 8`; do not introduce newer syntax into that output path.
- Tests are JUnit 6 and write generated artifacts under `target/` (usually `@TempDir`). Tests assume Maven runs from the repository root because some read `test-mods/...` relative paths.
- Keep diagnostics and provenance intact: reports distinguish complete, incomplete, stubbed, declaration-only, and adapter-required methods. If behavior changes, update the relevant docs (`README.md`, `docs/mod-compatibility-roadmap.md`, profile READMEs) rather than overstating support.
