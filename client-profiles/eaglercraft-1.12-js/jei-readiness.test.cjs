'use strict';

const assert = require('node:assert/strict');
const test = require('node:test');
const vm = require('node:vm');
const { installJeiReadiness } = require('./jei-readiness.cjs');

test('waits for initialization before invoking original filter', async () => {
    let calls = 0;
    const host = {
        goldClientJeiFilter(query) {
            calls++;
            assert.equal(host.__goldClientJeiIndexReady, true);
            return JSON.stringify([{ id: query }]);
        }
    };
    const api = installJeiReadiness(host, { timeoutMs: 1000, pollMs: 2 });
    const pending = api.filter('iron');
    assert.equal(calls, 0);

    host.__goldClientJeiItems = [{ id: 'minecraft:iron_ingot' }];
    host.__goldClientJeiIndexReady = true;

    assert.deepEqual(await pending, [{ id: 'iron' }]);
    assert.equal(calls, 1);
    assert.equal(api.status().indexedItems, 1);
});

test('returns decoded tooltips and propagates export errors', async () => {
    const host = {
        __goldClientJeiIndexReady: true,
        goldClientJeiTooltip(id, advanced) {
            if (id === 'missing:item') throw new Error('Unknown item');
            return JSON.stringify([advanced ? 'Advanced' : 'Normal']);
        }
    };
    const api = installJeiReadiness(host);
    assert.deepEqual(await api.tooltip('minecraft:iron_ingot'), ['Normal']);
    await assert.rejects(api.tooltip('missing:item'), /Unknown item/);
    await assert.rejects(api.filter(123), /must be a string/);
});

test('initialization failures and timeouts do not invoke JEI', async () => {
    const failed = installJeiReadiness({
        __goldClientJeiIndexError: 'Registry unavailable'
    });
    await assert.rejects(failed.ready(), /Registry unavailable/);

    const waiting = installJeiReadiness({}, { timeoutMs: 10, pollMs: 2 });
    await assert.rejects(waiting.ready(), /Timed out/);
});

test('serialized installer works without CommonJS in the browser', async () => {
    const host = { __goldClientJeiIndexReady: true };
    const sandbox = { window: host, setTimeout, Date, Promise };
    vm.runInNewContext(
        '(' + installJeiReadiness.toString() + ')(window);',
        sandbox);
    assert.equal(host.goldClientJeiAsync.apiVersion, 1);
    assert.equal((await host.goldClientJeiAsync.ready()).ready, true);
});