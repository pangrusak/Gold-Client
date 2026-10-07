'use strict';

const assert = require('assert');
const path = require('path');

const elements = new Map();
let snapshot = null;
global.window = {
    goldClientReadPlayerPosition: () => snapshot
};
global.document = {
    body: { appendChild: element => elements.set(element.id, element) },
    documentElement: {},
    createElement: () => ({ style: {} }),
    getElementById: id => elements.get(id) || null
};

const bridge = require(path.resolve(process.argv[2] || 'target/bridge-proof/eagler-mod.js'));
assert.strictEqual(typeof bridge.onClientTick, 'function');
bridge.main([], error => {
    if (error) throw error;
});
snapshot = { connected: true, x: -12.7, y: 64.9, z: 8.2 };
bridge.onClientTick();
assert.strictEqual(
    elements.get('gold-client-bridge-proof').textContent,
    'Gold Client bridge XYZ: -13 64 8');
snapshot = { connected: false, x: 0, y: 0, z: 0 };
bridge.onClientTick();
assert.strictEqual(
    elements.get('gold-client-bridge-proof').textContent,
    'Gold Client bridge: waiting for world');
process.stdout.write('TeaVM bridge callback and connected/disconnected states passed.\n');
