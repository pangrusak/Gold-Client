# Eaglercraft 1.12.2 JS client profile — partial JEI search

This is a diagnostic integration milestone, not JEI compatibility. It translates
original JEI 4.16.1.301 bytecode from the external
`test-mods/jei1.12.2.jar` (SHA-256
`DAD60317D8FDF891AD7841ABF838564EA7CBD317EC85D92F57DCB8F55602124B`) and
uses that translated code to search real item data from the supplied
Eaglercraft 1.12.2 JavaScript client.

## Run

From the repository root:

```powershell
.\client-profiles\eaglercraft-1.12-js\run-demo.ps1
```

This runs the injector regression test and JEI bytecode/JVM parity test,
translates the selected JEI code, creates an isolated client copy under ignored
`target/`, then serves it at `http://127.0.0.1:4173/`. Open that URL in a
browser. When the client registry is ready, the panel titled **“Partial JEI
original-code search — diagnostic UI”** accepts queries and displays up to 100
matching registry IDs and display names. The server automatically exports the
runtime corpus to ignored
`target/jei-suffix-tree/jei-real-item-corpus.json`.

To run the original-JVM comparison against the exported corpus after the
browser has populated it:

```powershell
.\client-profiles\eaglercraft-1.12-js\build.ps1
```

When the exact client-hash/JAR-hash corpus exists, that build compares all
exported item IDs and display names, an absent query, repeated query, case and
Unicode queries, and a post-`trimToSize` insertion against the original JEI JVM
classes and the TeaVM-translated output. Without an exported corpus, it still
runs the deterministic fixture comparison and injector regression test.

## Data boundary

The profile adapter enumerates the client's `Item.REGISTRY` after initialization.
It represents **registered item types**, not inventory stacks, creative-tab
variants, JEI ingredients, or recipes. For each registry entry it reads the
registry `ResourceLocation` and asks the client for the display name of a
one-count, metadata-0 `ItemStack`. It does not synthesize IDs or names. Only
these default-stack entries are indexed; variants are excluded because this
adapter does not enumerate them. Corpus records are sorted by registry ID and
include the exact client and JEI input hashes.

The JS profile is tied to the supplied readable client build. The injection
adapter verifies its title/options/runtime/callback anchors and depends on
generated identifiers including `nmi_Item_REGISTRY`,
`nmur_RegistryNamespaced_iterator`, `nmur_RegistryNamespaced_getNameForObject`,
`nmi_ItemStack__init_10`, and `nmi_ItemStack_getDisplayName`. These are
client-build-specific symbols, not stable public APIs. A new client build must
be re-inspected and separately verified.

## Original code versus compatibility code

The original, byte-for-byte JEI class files staged for TeaVM are
`mezz.jei.suffixtree.GeneralizedSuffixTree` (including nested `Pair`),
`Node`, `Edge`, `ISearchTree`, and `mezz.jei.util.Log`. The substantive
translated behavior exercised is original `GeneralizedSuffixTree.put`,
`search`, and `trimToSize`, with reachable original tree-node/edge logic.
`JeiSuffixTreeEntry` is a generated call harness that feeds strings to those
original methods and exports a narrow JS-callable boundary.

Profile compatibility code reads actual client registry values, converts them
to `{id, displayName}` records, and maps the original tree's returned integer
indexes back to those records. The functional Log4j adapter writes original
JEI warnings to stderr/console; it is not a no-op. The query algorithm is not
reimplemented in the adapter.

## Limits and verification scope

This milestone does not translate JEI's `IngredientFilter`, recipe lookup,
Forge lifecycle/events/registries, original JEI screens/rendering/input, or the
remaining JEI classes. The diagnostic panel is temporary browser UI and is
not JEI UI. This target client contains no Forge mod loader, so the corpus is
the client's registered item types only. No claim of complete or arbitrary
mod compatibility is made.

Minified JavaScript clients and the required 26.2 WebAssembly client remain
unsupported. No minified-client generalization or WASM rewriting is included.
