'use strict';

const fs = require('node:fs');
const path = require('node:path');

const [clientPath, translatedPath, outputPath] = process.argv.slice(2);
if (!clientPath || !translatedPath || !outputPath) {
    process.stderr.write(
        'Usage: node inject-client.cjs <client.html> <jei-slice.js> <output.html>\n');
    process.exit(2);
}

const source = fs.readFileSync(clientPath, 'utf8').replace(/\r\n/g, '\n');
const expectedOptions = `        window.eaglercraftXOpts = {
            container: "game_frame",
            worldsDB: "worlds"
        };`;
const scriptMarker =
    'if(typeof window !== "undefined") window.eaglercraftXClientScriptElement = document.currentScript;';

function requireUnique(text, marker, description) {
    const first = text.indexOf(marker);
    if (first < 0 || text.indexOf(marker, first + marker.length) >= 0) {
        throw new Error(`Expected exactly one ${description} anchor in supplied client`);
    }
    return first;
}

if (!source.includes('<title>Eaglercraft 1.12.2</title>')) {
    throw new Error('Unsupported client: expected the supplied Eaglercraft 1.12.2 profile');
}
if (!fs.existsSync(translatedPath)) {
    throw new Error(`Translated JEI slice not found: ${translatedPath}`);
}

requireUnique(source, expectedOptions, 'Eaglercraft launch options');
let output = source.replace(expectedOptions, expectedOptions.replace(
    '            worldsDB: "worlds"',
    '            worldsDB: "worlds",\n            hooks: {}'));
const markerOffset = requireUnique(output, scriptMarker, 'client runtime script');
const scriptOffset = output.lastIndexOf('<script', markerOffset);
if (scriptOffset < 0) {
    throw new Error('Could not locate Eaglercraft runtime script element');
}

const translatedScript =
    '    <script src="eagler-jei-suffix-tree.js"></script>\n' +
    '    <script>\n' +
    '        if (typeof window.main !== "function" || ' +
        'typeof window.goldClientJeiSearch !== "function") {\n' +
    '            throw new Error("Translated JEI suffix-tree exports are missing");\n' +
    '        }\n' +
    '        window.main([], function(error) { if (error) throw error; });\n' +
    '        window.eaglercraftXOpts.hooks = {\n' +
    '            screenChanged: function(screenName) {\n' +
    '                var query = String(screenName == null ? "" : screenName);\n' +
    '                var result = window.goldClientJeiSearch(query);\n' +
    '                if (!Array.isArray(window.__goldClientJeiSearchCalls)) {\n' +
    '                    window.__goldClientJeiSearchCalls = [];\n' +
    '                }\n' +
    '                window.__goldClientJeiSearchCalls.push({query: query, result: result});\n' +
    '                console.info("[JEI original suffix-tree adapter]", query, result);\n' +
    '            }\n' +
    '        };\n' +
    '    </script>\n';

output = output.slice(0, scriptOffset) + translatedScript + output.slice(scriptOffset);

const absoluteOutput = path.resolve(outputPath);
fs.mkdirSync(path.dirname(absoluteOutput), { recursive: true });
fs.copyFileSync(translatedPath,
    path.join(path.dirname(absoluteOutput), 'eagler-jei-suffix-tree.js'));
fs.writeFileSync(absoluteOutput, output, 'utf8');

process.stdout.write(`Created isolated JEI JS-profile client: ${absoluteOutput}\n`);
process.stdout.write('The original client HTML and mod JAR were left unchanged.\n');
