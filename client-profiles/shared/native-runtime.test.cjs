"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const { installNativeRuntime } = require("./native-runtime.cjs");
test("returns original live identity without cloning or caching", () => {
    const host = {}; let client = { textures: { original: true } };
    const api = installNativeRuntime(host, () => client, "fixture");
    assert.equal(api.getMinecraft(), client);
    assert.equal(api.getMinecraft().textures, client.textures);
    assert.equal(api.isCurrentMinecraft(client), true);
    const previous = client; client = { replacement: true };
    assert.equal(api.getMinecraft(), client);
    assert.equal(api.isCurrentMinecraft(previous), false);
    assert.equal(api.capabilities.javaMinecraftTypeBinding, false);
    assert.equal(api.capabilities.forgeEventDelivery, false);
    assert.equal(Object.isFrozen(api), true);
});
test("rejects missing original instance and duplicate installation", () => {
    const host = {}; const api = installNativeRuntime(host, () => null, "fixture");
    assert.throws(() => api.getMinecraft(), /not available/);
    assert.throws(() => installNativeRuntime(host, () => ({}), "fixture"), /already installed/);
    assert.throws(() => installNativeRuntime({}, null, "fixture"), /required/);
});
test("serializable installer keeps same pointer in isolated browser fixture", () => {
    const vm = require("node:vm"); const original = { runtimeMarker: 1 };
    const context = vm.createContext({ window: {}, original });
    vm.runInContext("(" + installNativeRuntime.toString() + ")(window, function(){return original;}, 'fixture');", context);
    assert.equal(context.window.goldClientNativeRuntime.getMinecraft(), original);
    assert.equal(context.window.goldClientNativeRuntime.isCurrentMinecraft(original), true);
});
