'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawnSync } = require('node:child_process');
const test = require('node:test');
const vm = require('node:vm');

const injector = path.join(__dirname, 'inject-client.cjs');

test('keeps injected scripts outside the client script and launch options valid', t => {
    const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'gold-client-inject-'));
    t.after(() => fs.rmSync(directory, { recursive: true, force: true }));

    const client = path.join(directory, 'client.html');
    const translated = path.join(directory, 'eagler-mod.js');
    const output = path.join(directory, 'out', 'client.html');
    const runtimeCall =
        'var$1.$screenChangedHook.call($rt_ustr(var$2), var$3, var$4, var$5, var$6, var$7);';
    fs.writeFileSync(client, [
        '<title>Eaglercraft 1.12.2</title>',
        '<script type="text/javascript">',
        '        window.eaglercraftXOpts = {',
        '            container: "game_frame",',
        '            worldsDB: "worlds"',
        '        };',
        '</script>',
        '<script type="text/javascript">',
        'if(typeof window !== "undefined") window.eaglercraftXClientScriptElement = document.currentScript;',
        runtimeCall,
        '</script>'
    ].join('\r\n'));
    fs.writeFileSync(translated, '/* translated test bundle */');

    const result = spawnSync(process.execPath, [injector, client, translated, output], {
        encoding: 'utf8'
    });
    assert.equal(result.status, 0, result.stderr);

    const html = fs.readFileSync(output, 'utf8');
        const bridgeStart = html.indexOf('    (function() {');
    assert.ok(bridgeStart >= 0, 'generic bridge script must be present');

    const bridgeEnd = html.indexOf('</script>', bridgeStart);
    assert.ok(bridgeEnd > bridgeStart);

    const bridgeSource = html.slice(bridgeStart, bridgeEnd);

    assert.doesNotMatch(
        bridgeSource,
        /module\.exports|require\s*\(/,
        'browser bridge must not contain CommonJS loading code');

    const bridgeHost = {};
    const bridgeSandbox = { window: bridgeHost };

    vm.runInNewContext(bridgeSource, bridgeSandbox);

    assert.equal(bridgeHost.goldClientBridge.apiVersion, 1);
    assert.equal(
        bridgeHost.goldClientBridge.profileId,
        'supplied-eaglercraft-1.12.2-js');

    assert.equal(bridgeHost.goldClientBridge.capabilities.recipes, false);
    assert.equal(bridgeHost.goldClientBridge.capabilities.rendering, false);

    assert.throws(
        () => bridgeHost.goldClientBridge.getRegisteredItemTypes(),
        /not ready/);

    assert.throws(
        () => bridgeHost.goldClientBridge.getLocale(),
        /Capture client items/);

    bridgeHost.__goldClientCaptureItems = () => {
        bridgeHost.__goldClientJeiLocaleTag = 'en_us';

        return [{
            id: 'minecraft:iron_ingot',
            displayName: 'Iron Ingot',
            normalTooltipData: '10:Iron Ingot',
            advancedTooltipData: '10:Iron Ingot'
        }];
    };

    bridgeHost.__goldClientJeiGetTooltipData =
        (id, advanced) => advanced ? '8:Advanced' : '6:Normal';

    const captured = bridgeHost.goldClientBridge.getRegisteredItemTypes();

    assert.equal(captured.length, 1);
    assert.equal(captured[0].id, 'minecraft:iron_ingot');
    assert.equal(bridgeHost.goldClientBridge.getLocale(), 'en_us');
    assert.equal(
        bridgeHost.goldClientBridge.getTooltipData(
            'minecraft:iron_ingot', true),
        '8:Advanced');

    assert.match(html, /bridge\.getRegisteredItemTypes\(\)/);
    assert.match(html, /bridge\.getLocale\(\)/);

    assert.ok(
        bridgeEnd < html.indexOf(
            '<script src="eagler-jei-ingredient-elements.js">'),
        'generic bridge must load before the translated bundle');
    const firstClose = html.indexOf('</script>');
    const adapterScript = html.indexOf('<script src="eagler-jei-ingredient-elements.js">');
    const runtimeScript = html.indexOf('if(typeof window !== "undefined")');
    const adapterClose = html.indexOf('</script>', adapterScript);
    assert.ok(firstClose >= 0);
    assert.ok(adapterScript > firstClose, 'translated bundle must not be nested in launch-options script');
    assert.ok(runtimeScript > adapterClose, 'client runtime must remain a separate script');
    assert.equal((html.match(/<script src="eagler-jei-ingredient-elements\.js">/g) || []).length, 1);
    assert.match(html,
        /goldClientJeiInitializeWithTooltips\(ids, displayNames, normalTooltipData, advancedTooltipData\)/);
    assert.match(html, /goldClientJeiSetLocale\(window\.__goldClientJeiLocaleTag\)/);
    assert.match(html, /settings\.\$language/);
    assert.match(html, /goldClientJeiFilter\(query\)/);
    assert.match(html, /goldClientJeiTooltip/);
    assert.match(html, /__goldClientJeiStacksById/);
    assert.match(html, /nmi_ItemStack_getTooltip/);
    assert.match(html, /ju_ArrayList_size/);
    assert.match(html, /normalTooltipData/);
    assert.match(html, /advancedTooltipData/);
    assert.match(html, /__goldClientJeiProgressUpdate/);

    const launchStart = html.indexOf('window.eaglercraftXOpts = {');
    const launchScriptStart = html.lastIndexOf('<script', launchStart);
    const launchEnd = html.indexOf('</script>', launchStart);
    const launchScript = html.slice(html.indexOf('>', launchScriptStart) + 1, launchEnd);
    const sandbox = { window: {} };
    vm.runInNewContext(launchScript, sandbox);
    assert.deepEqual(
        JSON.parse(JSON.stringify(sandbox.window.eaglercraftXOpts)),
        {
            container: 'game_frame',
            worldsDB: 'worlds',
            hooks: {}
        });
    assert.equal(
        fs.readFileSync(path.join(path.dirname(output), 'eagler-jei-ingredient-elements.js'), 'utf8'),
        '/* translated test bundle */');
});
