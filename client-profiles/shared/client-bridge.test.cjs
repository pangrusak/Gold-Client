"use strict";

const assert = require("node:assert/strict");
const test = require("node:test");
const { installClientBridge } = require("./client-bridge.cjs");

function createAdapter() {
    return {
        getLocale: () => "en_us",

        getRegisteredItemTypes: () => [{
            id: "minecraft:iron_ingot",
            displayName: "Iron Ingot",
            normalTooltipData: "10:Iron Ingot",
            advancedTooltipData: "10:Iron Ingot"
        }],

        getTooltipData: (id, advanced) => {
            if (id !== "minecraft:iron_ingot") {
                throw new Error("Unknown client item identifier");
            }

            return advanced ? "8:Advanced" : "6:Normal";
        }
    };
}

test("installs a generic item/locale bridge", () => {
    const host = {};
    const bridge = installClientBridge(
        host, createAdapter(), "test-client");

    assert.equal(host.goldClientBridge, bridge);
    assert.equal(bridge.apiVersion, 1);
    assert.equal(bridge.getLocale(), "en_us");
    assert.equal(bridge.getRegisteredItemTypes().length, 1);
    assert.equal(
        bridge.getTooltipData("minecraft:iron_ingot", true),
        "8:Advanced");

    assert.equal(bridge.capabilities.recipes, false);
    assert.equal(bridge.capabilities.rendering, false);
    assert(Object.isFrozen(bridge));
    assert(Object.isFrozen(bridge.capabilities));
});

test("rejects incomplete adapters and duplicate installation", () => {
    assert.throws(
        () => installClientBridge({}, {}, "test-client"),
        /missing getLocale/);

    const host = {};
    installClientBridge(host, createAdapter(), "test-client");

    assert.throws(
        () => installClientBridge(host, createAdapter(), "test-client"),
        /already installed/);
});

test("rejects duplicate item identifiers and invalid flags", () => {
    const adapter = createAdapter();
    const original = adapter.getRegisteredItemTypes;

    adapter.getRegisteredItemTypes = () => {
        const item = original()[0];
        return [item, item];
    };

    const bridge = installClientBridge({}, adapter, "test-client");

    assert.throws(
        () => bridge.getRegisteredItemTypes(),
        /Duplicate client item/);

    assert.throws(
        () => bridge.getTooltipData("minecraft:iron_ingot", "false"),
        /must be boolean/);
});