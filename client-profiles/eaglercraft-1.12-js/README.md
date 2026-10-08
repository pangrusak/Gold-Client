# Eaglercraft 1.12.2 JavaScript client profile

This profile is a narrow integration test for original JEI 4.16.1.301 bytecode
from the external `test-mods/jei1.12.2.jar` input. It stages the unchanged
suffix-tree class files under ignored `target/`, translates their deterministic
index/query behavior, and compares it with the original JAR on the JVM.
JEI's original `mezz.jei.util.Log` is also staged unchanged. Its one reachable
Log4j warning operation is supplied by the profile's functional console adapter;
the adapter emits the warning and cause to stderr rather than suppressing it.

Run from the repository root:

```powershell
.\client-profiles\eaglercraft-1.12-js\build.ps1
```

Open the generated client at
`target\jei-suffix-tree\client\Eaglercraft_1.12_Offline_en_US.html`, enter a
world, then trigger a screen change. The client profile uses the actual
`eaglercraftXOpts.hooks.screenChanged` callback and passes the active screen
class name to the exported translated JEI search method. Calls are recorded in
`window.__goldClientJeiSearchCalls` and the browser console.

This proves only the `GeneralizedSuffixTree.put` / `search` original-code slice,
`trimToSize` recovery behavior, JVM parity for the checked corpus, and invocation
through this JS client hook.
It does not connect JEI to Minecraft's ingredient registry or UI. JEI lifecycle,
Forge events, registries, rendering, input, networking, the remaining JEI
classes, and full JEI behavior are unsupported.

This adapter is tied to the supplied readable Eaglercraft 1.12.2 HTML anchors.
It does not establish support for minified JavaScript clients. The required
26.2 WebAssembly target remains unsupported; its current WASM exports expose no
Minecraft-facing methods for this adapter.
