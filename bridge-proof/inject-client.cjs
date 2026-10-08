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
    window.goldClientReadPlayerPosition = function() {
        return goldClientBridgePlayerSnapshot;
    };
    var goldClientBridgeOriginalRunTick = nmc_Minecraft_runTick;
    nmc_Minecraft_runTick = function($minecraft) {
        var $player = $minecraft.$player;
        var $world = $minecraft.$world;
        goldClientBridgePlayerSnapshot = {
            connected: $world !== null && $player !== null,
            x: $player === null ? 0 : $player.$posX,
            y: $player === null ? 0 : $player.$posY,
            z: $player === null ? 0 : $player.$posZ
        };
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
