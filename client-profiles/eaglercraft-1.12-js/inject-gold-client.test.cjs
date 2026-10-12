'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawnSync } = require('node:child_process');
const test = require('node:test');
const vm = require('node:vm');

const script = path.resolve(__dirname, 'inject-gold-client.cjs');

function fixture() {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'gold-client-inject-'));
    const client = path.join(root, 'client.html');
    const bundle = path.join(root, 'translated-js');
    const output = path.join(root, 'out', 'client.html');
    fs.mkdirSync(bundle);
    fs.writeFileSync(client, [
        '<!doctype html><html><head><title>Eaglercraft 1.12.2</title></head><body><main>game</main>',
        '<script>',
        '    function nmc_Minecraft_runTick($this) { return $this; }',
        '</script></body></html>'
    ].join(''));
    for (const name of [
        'eaglercraft-forge-entry.js', 'forge-lifecycle.js', 'forge-host-runtime.js',
        'gold-client-runtime.js', 'mod-index.js'
    ]) fs.writeFileSync(path.join(bundle, name), '// fixture');
    return { root, client, bundle, output };
}

test('injects a module entrypoint without rewriting the original client', () => {
    const f = fixture();
    const original = fs.readFileSync(f.client, 'utf8');
    const result = spawnSync(process.execPath, [script, f.client, f.bundle, f.output], {
        encoding: 'utf8'
    });
    assert.equal(result.status, 0, result.stderr);
    assert.match(fs.readFileSync(f.output, 'utf8'),
        /<script type="module" src="\.\.\/translated-js\/eaglercraft-forge-entry\.js"><\/script>/);
    assert.equal(fs.readFileSync(f.client, 'utf8'), original);
    assert.match(result.stdout, /goldClientForgeBindings/);
    const injected = fs.readFileSync(f.output, "utf8");
    assert.match(injected, /goldClientClientApi/);
    assert.match(injected, /goldClientReadPlayerSnapshot/);
    assert.match(injected, /blockSampling: false/);

    const adapterStart = injected.indexOf('var goldClientBridgePlayerSnapshot = null;');
    const adapterEnd = injected.indexOf('function nmc_Minecraft_runTick($this) {', adapterStart);
    assert.ok(adapterStart >= 0 && adapterEnd > adapterStart, 'tick adapter must wrap the actual client tick');
    const sandbox = {
        window: {},
        nmc_Minecraft_runTick: function(minecraft) { return minecraft; }
    };
    vm.runInNewContext(injected.slice(adapterStart, adapterEnd), sandbox);
    const seen = [];
    sandbox.window.goldClientClientApi.onClientTick(snapshot => seen.push(snapshot));
    const minecraft = {
        $world: {},
        $player: {
            $posX: -1.25, $posY: 64.75, $posZ: 8.5, $health: 17,
            $foodStats: {
                $foodLevel: 14,
                $foodSaturationLevel: 5.5,
                $foodExhaustionLevel: 1.25
            }
        }
    };
    sandbox.nmc_Minecraft_runTick(minecraft);
    assert.equal(seen.length, 1);
    assert.equal(seen[0].connected, true);
    assert.equal(seen[0].x, -1.25);
    assert.equal(seen[0].health, 17);
    assert.equal(seen[0].hunger, 14);
    assert.equal(seen[0].saturation, 5.5);
    assert.equal(seen[0].exhaustion, 1.25);
    assert.equal(seen[0].capabilities.blockSampling, false);
});

test('rejects a partial translated bundle rather than emitting a broken page', () => {
    const f = fixture();
    fs.unlinkSync(path.join(f.bundle, 'forge-host-runtime.js'));
    const result = spawnSync(process.execPath, [script, f.client, f.bundle, f.output], {
        encoding: 'utf8'
    });
    assert.notEqual(result.status, 0);
    assert.match(result.stderr, /missing forge-host-runtime\.js/);
    assert.equal(fs.existsSync(f.output), false);
});

test('rejects a different client version instead of injecting at guessed anchors', () => {
    const f = fixture();
    fs.writeFileSync(f.client, '<html><body>not the supported profile</body></html>');
    const result = spawnSync(process.execPath, [script, f.client, f.bundle, f.output], {
        encoding: 'utf8'
    });
    assert.notEqual(result.status, 0);
    assert.match(result.stderr, /Unsupported client profile/);
});
