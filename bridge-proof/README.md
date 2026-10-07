# Eaglercraft 1.12.2 bridge proof

This proof compiles `ClientBridgeFixture` to JavaScript with TeaVM, then adds a
small adapter to a generated copy of the supplied client. It does not alter
`unminified-clients/Eaglercraft_1.12_Offline_en_US.html`.

Build the normal project first, then run:

```powershell
mvn clean package
.\bridge-proof\build.ps1
```

Open `target\bridge-proof\Eaglercraft_1.12_Offline_en_US.html`, skip the
countdown, create or join a world, and move. The fixed green HUD label should
show the player's floored X/Y/Z coordinates and update during ticks. Leaving a
world displays `waiting for world`.

The injection script fails closed unless the client contains the
`<title>Eaglercraft 1.12.2</title>` identity and exactly one
`nmc_Minecraft_runTick($this)` function. On each tick the adapter snapshots
`$world`, `$player`, and `$posX/$posY/$posZ` from the actual Minecraft
instance. It calls TeaVM's exported `onClientTick` Java method; that translated
Java method calls the adapter's `goldClientReadPlayerPosition()` function to
read the snapshot before updating the HUD. These are
TeaVM-generated, deobfuscated names verified in the supplied HTML: the
`runTick` body uses `$this.$world` and `$this.$player`, and the render loop
interpolates the player's `$posX`. They are not stable Java or public client
APIs; an upstream rebuild/obfuscation change requires re-verification.

This is a narrow bridge proof, not a client mod loader, Forge event bus,
render API adapter, or Xaero port. The Java fixture receives position values
through an explicit JavaScript adapter and draws its indicator with browser
DOM overlay elements, not the game's GL HUD renderer. The supplied client has
no Java source tree or source maps, and its documented `hooks` config does not
include world/player/tick callbacks. Runtime verification must use the built
copy; `test-bridge.cjs` alone verifies only the translated fixture's callback
and no-world behavior.
