'use strict';

const fs = require('fs');
const path = require('path');

const [clientPath, bridgePath, outputPath] = process.argv.slice(2);
if (!clientPath || !bridgePath || !outputPath) {
    process.stderr.write(
        'Usage: node inject-client.cjs <client.html> <bridge.js> <output.html>\n');
    process.exit(2);
}

const source = fs.readFileSync(clientPath, 'utf8');
const versionMarker = '<title>Eaglercraft 1.12.2</title>';
const tickMarker = '    function nmc_Minecraft_runTick($this) {';
const scriptMarker =
    'if(typeof window !== "undefined") window.eaglercraftXClientScriptElement = document.currentScript;';

function requireUnique(text, marker, description) {
    const first = text.indexOf(marker);
    if (first < 0 || text.indexOf(marker, first + marker.length) >= 0) {
        throw new Error(`Expected exactly one ${description} anchor in supplied client`);
    }
    return first;
}

if (!source.includes(versionMarker)) {
    throw new Error('Unsupported client: expected Eaglercraft Minecraft 1.12.2 version marker');
}

requireUnique(source, tickMarker, 'Minecraft.runTick');
const scriptMarkerOffset = requireUnique(source, scriptMarker, 'client runtime script');
const scriptOffset = source.lastIndexOf('<script', scriptMarkerOffset);
if (scriptOffset < 0) {
    throw new Error('Could not find the opening script tag for the client runtime');
}

const tickAdapter = `    var goldClientBridgePlayerSnapshot = null;
    function goldClientReadNumber(object, names) {
        if (!object) return null;
        for (var i = 0; i < names.length; i++) {
            var value = object[names[i]];
            if (typeof value === "number" && Number.isFinite(value)) return value;
        }
        return null;
    }
    function goldClientReadNestedNumber(object, objectNames, valueNames) {
        if (!object) return null;
        for (var i = 0; i < objectNames.length; i++) {
            var nested = object[objectNames[i]];
            var value = goldClientReadNumber(nested, valueNames);
            if (value !== null) return value;
        }
        return null;
    }
    window.goldClientReadPlayerPosition = function() {
        return goldClientBridgePlayerSnapshot;
    };
    window.goldClientReadPlayerSnapshot = function() {
        if (!goldClientBridgePlayerSnapshot) return null;
        return Object.assign({}, goldClientBridgePlayerSnapshot);
    };
    var goldClientBridgeTickListeners = [];
    window.goldClientClientApi = Object.freeze({
        apiVersion: 1,
        profileId: "supplied-eaglercraft-1.12.2-js",
        getPlayerSnapshot: window.goldClientReadPlayerSnapshot,
        getCapabilities: function() {
            var snapshot = goldClientBridgePlayerSnapshot;
            return snapshot && snapshot.capabilities ? Object.assign({}, snapshot.capabilities) : {
                position: false, health: false, hunger: false, saturation: false,
                exhaustion: false, blockSampling: false
            };
        },
        onClientTick: function(listener) {
            if (typeof listener !== "function") throw new TypeError("Tick listener must be a function");
            goldClientBridgeTickListeners.push(listener);
            var active = true;
            return function() {
                if (!active) return;
                active = false;
                var index = goldClientBridgeTickListeners.indexOf(listener);
                if (index >= 0) goldClientBridgeTickListeners.splice(index, 1);
            };
        }
    });
    var goldClientBridgeOriginalRunTick = nmc_Minecraft_runTick;
    nmc_Minecraft_runTick = function($minecraft) {
        var $player = $minecraft.$player;
        var $world = $minecraft.$world;
        var $food = $player && ($player.$foodStats || $player.foodStats);
        goldClientBridgePlayerSnapshot = {
            connected: $world !== null && $player !== null,
            x: $player === null ? 0 : $player.$posX,
            y: $player === null ? 0 : $player.$posY,
            z: $player === null ? 0 : $player.$posZ,
            health: goldClientReadNumber($player, ["$health", "health"]),
            maxHealth: goldClientReadNumber($player, ["$maxHealth", "maxHealth"]),
            hunger: goldClientReadNestedNumber($player, ["$foodStats", "foodStats"], ["$foodLevel", "foodLevel"]),
            saturation: goldClientReadNestedNumber($player, ["$foodStats", "foodStats"], ["$foodSaturationLevel", "foodSaturationLevel"]),
            exhaustion: goldClientReadNestedNumber($player, ["$foodStats", "foodStats"], ["$foodExhaustionLevel", "foodExhaustionLevel"]),
            worldAvailable: $world !== null,
            capabilities: {
                position: $player !== null,
                health: goldClientReadNumber($player, ["$health", "health"]) !== null,
                hunger: goldClientReadNestedNumber($player, ["$foodStats", "foodStats"], ["$foodLevel", "foodLevel"]) !== null,
                saturation: goldClientReadNestedNumber($player, ["$foodStats", "foodStats"], ["$foodSaturationLevel", "foodSaturationLevel"]) !== null,
                exhaustion: goldClientReadNestedNumber($player, ["$foodStats", "foodStats"], ["$foodExhaustionLevel", "foodExhaustionLevel"]) !== null,
                blockSampling: false
            }
        };
        for (var listenerIndex = 0; listenerIndex < goldClientBridgeTickListeners.length; listenerIndex++) {
            try {
                goldClientBridgeTickListeners[listenerIndex](window.goldClientReadPlayerSnapshot());
            } catch (listenerError) {
                window.__goldClientLastTickListenerError = listenerError;
            }
        }
        if (typeof window.onClientTick !== "function") {
            throw new Error("Gold Client TeaVM bridge export did not load");
        }
        window.onClientTick();
        return goldClientBridgeOriginalRunTick.apply(this, arguments);
    };
`;

let output = source.replace(tickMarker, tickAdapter + tickMarker);
const bridgeScript = '    <script src="eagler-mod.js"></script>\n';
output = output.slice(0, scriptOffset) + bridgeScript + output.slice(scriptOffset);

fs.mkdirSync(path.dirname(path.resolve(outputPath)), { recursive: true });
fs.copyFileSync(bridgePath, path.join(path.dirname(path.resolve(outputPath)), 'eagler-mod.js'));
fs.writeFileSync(outputPath, output, 'utf8');

process.stdout.write(`Created isolated bridge client: ${path.resolve(outputPath)}\n`);
process.stdout.write('The supplied client HTML was read only and left unchanged.\n');
