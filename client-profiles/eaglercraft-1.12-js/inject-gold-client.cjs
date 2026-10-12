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
const closeBody = '</body>';
const firstBody = source.indexOf(closeBody);
if (firstBody < 0 || source.indexOf(closeBody, firstBody + closeBody.length) >= 0) {
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
const output = source.slice(0, firstBody) + tag + source.slice(firstBody);
fs.mkdirSync(path.dirname(outputPath), { recursive: true });
fs.writeFileSync(outputPath, output, 'utf8');
process.stdout.write('Injected Gold Client entrypoint: ' + relativeEntry + '\n');
process.stdout.write('Note: translated classes only start after window.goldClientForgeBindings is installed.\n');
