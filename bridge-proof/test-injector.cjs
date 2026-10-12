'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawnSync } = require('node:child_process');
const test = require('node:test');

const injector = path.join(__dirname, 'inject-client.cjs');

test('injector exposes best-effort live player stats without fake defaults', t => {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'gold-bridge-inject-'));
    t.after(() => fs.rmSync(root, { recursive: true, force: true }));
    const client = path.join(root, 'client.html');
    const bundle = path.join(root, 'eagler-mod.js');
    const output = path.join(root, 'out', 'client.html');

    fs.writeFileSync(client, [
        '<title>Eaglercraft 1.12.2</title>',
        '<script>',
        'if(typeof window !== "undefined") window.eaglercraftXClientScriptElement = document.currentScript;',
        '    function nmc_Minecraft_runTick($this) { return $this; }',
        '</script>'
    ].join('\n'));
    fs.writeFileSync(bundle, 'window.onClientTick = function() {};');

    const result = spawnSync(process.execPath, [injector, client, bundle, output], {
        encoding: 'utf8'
    });
    assert.equal(result.status, 0, result.stderr);

    const html = fs.readFileSync(output, 'utf8');
    assert.match(html, /goldClientReadPlayerSnapshot/);
    assert.match(html, /goldClientClientApi/);
    assert.match(html, /onClientTick: function\(listener\)/);
    assert.match(html, /getCapabilities: function\(\)/);
    assert.match(html, /goldClientBridgeTickListeners\[listenerIndex\]/);
    assert.match(html, /goldClientReadNestedNumber/);
    assert.match(html, /saturation:/);
    assert.match(html, /exhaustion:/);
    assert.match(html, /blockSampling: false/);
    assert.match(html, /value !== null/);
    assert.match(html, /health: goldClientReadNumber/);
    assert.equal(fs.readFileSync(client, 'utf8').includes('goldClientReadPlayerSnapshot'), false);
});
