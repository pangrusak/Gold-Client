# Vendored EaglerForgeInjector

This directory contains only the client patch pipeline needed to add
EaglerForge's `ModAPI` to an unminified, unobfuscated Eaglercraft 1.12.2
offline HTML build.

Upstream: `https://github.com/EaglerForge/EaglerForgeInjector`
Pinned source version: `2.7.97` (`v2.7.97`, upstream commit `cee455d` at the
time this copy was taken).

## What is retained

- `core/core.js` — script/class extraction and HTML injection.
- `core/postinit.js` — the runtime `ModAPI` implementation.
- `core/modloader.js`, `core/modgui.js`, `core/patches.js` — the runtime pieces
  injected by `core/core.js`.
- `inject.cjs` — minimal offline CLI.
- `LICENSE` — upstream license, unchanged.

## What was removed

The upload webpage and browser UI, documentation, tutorials, example mods,
bundled third-party browser libraries, artwork, npm wrapper, minifier, and
dedicated-server extras are not part of the Gold Client integration. The
injector also no longer accepts minified clients or clients that already carry
EaglerForge.

## Use

```bash
node inject.cjs <unminified-1.12.2.html> <output.html>
```

The output contains the upstream `__eaglerforgeinjector_installation_flag__`
marker and defines `globalThis.ModAPI` after the client starts. This command
does not modify the input file.

The Gold Client adapter is injected separately by
`client-profiles/eaglercraft-1.12-js/build-forge-client.cjs`.
