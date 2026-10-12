'use strict';

const fs = require('node:fs');
const path = require('node:path');

const [clientPathArg, translatedDirArg, outputPathArg] = process.argv.slice(2);
if (!clientPathArg || !translatedDirArg || !outputPathArg) {
    process.stderr.write(
        'Usage: node inject-eaglercraft.cjs <client.html> <translated-js-dir> <output.html>\n');
    process.exit(2);
}

const clientPath = path.resolve(clientPathArg);
const translatedDir = path.resolve(translatedDirArg);
const outputPath = path.resolve(outputPathArg);
const entrypointPath = path.join(translatedDir, 'eaglercraft-forge-entry.js');
const requiredFiles = [
    'eaglercraft-forge-entry.js',
    'forge-lifecycle.js',
    'forge-host-runtime.js',
    'gold-client-runtime.js',
    'mod-index.js'
];

if (!fs.existsSync(clientPath) || !fs.statSync(clientPath).isFile()) {
    throw new Error('Eaglercraft client HTML file does not exist: ' + clientPath);
}
if (!fs.existsSync(translatedDir) || !fs.statSync(translatedDir).isDirectory()) {
    throw new Error('Translated JS directory does not exist: ' + translatedDir);
}
for (const name of requiredFiles) {
    if (!fs.existsSync(path.join(translatedDir, name))) {
        throw new Error('Translated bundle is incomplete; missing ' + name);
    }
}

const source = fs.readFileSync(clientPath, 'utf8').replace(/\r\n/g, '\n');
if (!source.includes('<title>Eaglercraft 1.12.2</title>')) {
    throw new Error('Unsupported client profile: expected Eaglercraft 1.12.2 HTML');
}
const tickMarker = '    function nmc_Minecraft_runTick($this) {';
const firstTick = source.indexOf(tickMarker);
if (firstTick < 0 || source.indexOf(tickMarker, firstTick + tickMarker.length) >= 0) {
    throw new Error('Unsupported client profile: expected exactly one Minecraft.runTick hook');
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
            var value = goldClientReadNumber(object[objectNames[i]], valueNames);
            if (value !== null) return value;
        }
        return null;
    }
    window.goldClientReadPlayerSnapshot = function() {
        return goldClientBridgePlayerSnapshot
            ? Object.assign({}, goldClientBridgePlayerSnapshot) : null;
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
        var health = goldClientReadNumber($player, ["$health", "health"]);
        var hunger = goldClientReadNestedNumber($player, ["$foodStats", "foodStats"], ["$foodLevel", "foodLevel"]);
        var saturation = goldClientReadNestedNumber($player, ["$foodStats", "foodStats"], ["$foodSaturationLevel", "foodSaturationLevel"]);
        var exhaustion = goldClientReadNestedNumber($player, ["$foodStats", "foodStats"], ["$foodExhaustionLevel", "foodExhaustionLevel"]);
        goldClientBridgePlayerSnapshot = {
            connected: $world !== null && $player !== null,
            x: $player === null ? 0 : $player.$posX,
            y: $player === null ? 0 : $player.$posY,
            z: $player === null ? 0 : $player.$posZ,
            health: health,
            hunger: hunger,
            saturation: saturation,
            exhaustion: exhaustion,
            worldAvailable: $world !== null,
            capabilities: {
                position: $player !== null,
                health: health !== null,
                hunger: hunger !== null,
                saturation: saturation !== null,
                exhaustion: exhaustion !== null,
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
        return goldClientBridgeOriginalRunTick.apply(this, arguments);
    };
`;

const instrumentedSource = source.replace(tickMarker, tickAdapter + tickMarker);
const closeBody = '</body>';
const firstBody = instrumentedSource.indexOf(closeBody);
if (firstBody < 0 || instrumentedSource.indexOf(closeBody, firstBody + closeBody.length) >= 0) {
    throw new Error('Expected exactly one </body> insertion point in client HTML');
}

let relativeEntry = path.relative(path.dirname(outputPath), entrypointPath)
    .split(path.sep).join('/');
relativeEntry = relativeEntry.split('/').map(part => part === '..' || part === '.'
    ? part : encodeURIComponent(part)).join('/');
if (!relativeEntry.startsWith('.') && !relativeEntry.startsWith('/')) {
    relativeEntry = './' + relativeEntry;
}
const tag = '    <script type="module" src="' + relativeEntry + '"></script>\n';
const output = instrumentedSource.slice(0, firstBody) + tag + instrumentedSource.slice(firstBody);
fs.mkdirSync(path.dirname(outputPath), { recursive: true });
fs.writeFileSync(outputPath, output, 'utf8');
process.stdout.write('Injected Gold Client entrypoint: ' + relativeEntry + '\n');
process.stdout.write('Note: translated classes only start after window.goldClientForgeBindings is installed.\n');
