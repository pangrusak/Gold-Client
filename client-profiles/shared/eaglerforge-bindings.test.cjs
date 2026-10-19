"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const source = fs.readFileSync(path.join(__dirname, "eaglerforge-bindings.js"), "utf8");

function installFixture() {
    const sideClient = { name: "CLIENT", ordinal: 0 };
    const sideServer = { name: "SERVER", ordinal: 1 };
    const world = { name: "world" };

    function Minecraft() {}
    Minecraft.prototype.$getWorld = function () { return this.$theWorld; };
    Minecraft.prototype.$getItemColors = function () { return 7; };
    Minecraft.prototype.$setWorld = function (value) { this.$theWorld = value; };

    const minecraft = {
        $theWorld: world,
        $getItemColors: function () { return 7; },
        $getWorld: function () { return this.$theWorld; },
        $setWorld: function (value) { this.$theWorld = value; }
    };

    const minecraftEntry = {
        id: "net.minecraft.client.Minecraft",
        compiledName: "nmc_Minecraft",
        class: Minecraft,
        methods: {
            getWorld: { method: Minecraft.prototype.$getWorld, methodName: "nmc_Minecraft_getWorld" },
            getItemColors: { method: Minecraft.prototype.$getItemColors, methodName: "nmc_Minecraft_getItemColors" }
        },
        staticMethods: {
            getMinecraft: {
                method: function getMinecraft() { return minecraft; },
                methodName: "nmc_Minecraft_getMinecraft"
            },
            func_71410_x: {
                method: function func_71410_x() { return minecraft; },
                methodName: "nmc_Minecraft_func_71410_x"
            }
        },
        staticVariables: Object.freeze({
            theWorld: world,
            staticField: 41
        }),
        constructors: [function MinecraftConstructor() { return minecraft; }]
    };

    const ModAPI = {
        isServer: false,
        hooks: {},
        reflect: {
            getClassById(id) {
                return id === "net.minecraft.client.Minecraft" ? minecraftEntry : null;
            },
            getClassByName(name) {
                return name === "Minecraft" ? minecraftEntry : null;
            }
        },
        util: {
            str(value) {
                if (value === null) return null;
                return {
                    $characters: { data: Array.from(String(value), ch => ch.charCodeAt(0)) },
                    toString() { return String(value); }
                };
            },
            ustr(value) {
                if (value === null) return null;
                return String.fromCharCode(...value.$characters.data);
            }
        }
    };

    const context = {
        console,
        ModAPI,
        GoldClientRuntime: {
            getStaticField(owner, field) {
                if (owner === "net.minecraftforge.fml.relauncher.Side") {
                    return field === "SERVER" ? sideServer : sideClient;
                }
                throw new Error("unexpected field " + owner + "." + field);
            }
        },
        goldClientForgeMemberMap: {
            func_71410_x: "getMinecraft",
            field_71441_e: "theWorld",
            func_184125_al: "getItemColors"
        },
        goldClientForgeModIds: new Set(["appleskin", "forge", "minecraft"]),
        goldClientForgeAutoStart: false
    };
    context.window = context;
    vm.createContext(context);
    vm.runInContext(source, context, { filename: "eaglerforge-bindings.js" });
    return { context, bindings: context.goldClientForgeBindings, minecraft, world, sideClient };
}

test("installs the Forge binding surface on the global window", () => {
    const { context, bindings } = installFixture();
    assert.equal(context.goldClientEaglerForgeBindingsInstalled, true);
    assert.equal(typeof context.goldClientInstallEaglerForgeBindings, "function");
    for (const service of ["events", "minecraft", "forge", "render", "input",
        "resources", "network", "config", "registries", "logger", "side"]) {
        assert.equal(typeof bindings[service], "object", service);
    }
});

test("resolves SRG static methods through ModAPI", () => {
    const { bindings, minecraft } = installFixture();
    const result = bindings.minecraft.call("net.minecraft.client.Minecraft", "func_71410_x", []);
    assert.equal(result.getRef(), minecraft);
    assert.equal(result.isModProxy, true);
});

test("native proxies expose mapped fields and methods without leaking $ names", () => {
    const { bindings, world } = installFixture();
    const client = bindings.minecraft.call("net.minecraft.client.Minecraft", "getMinecraft", []);
    assert.equal(client.field_71441_e.getRef(), world);
    assert.equal(client.theWorld.getRef(), world);
    assert.equal(client.getWorld().getRef(), world);
    assert.equal(client.func_184125_al(), 7);
    assert.equal(client.getItemColors(), 7);
    client.field_71441_e = null;
    assert.equal(client.$theWorld, null);
    assert.throws(() => client.func_999999_z(), /missing-native-member/);
});

test("converts JS strings and booleans at the native boundary", () => {
    const { bindings, context } = installFixture();
    let observed;
    context.ModAPI.reflect.getClassById = id => ({
        id,
        compiledName: "nmi_Item",
        class: function Item() {},
        methods: {},
        staticMethods: {
            probe: {
                method: function probe(a, b) { observed = [a, b]; }
            }
        },
        staticVariables: {},
        constructors: []
    });
    bindings.minecraft.call("net.minecraft.item.Item", "probe", ["hello", true]);
    assert.equal(observed[1], 1);
    assert.equal(context.ModAPI.util.ustr(observed[0]), "hello");
});

test("creates lifecycle events with a real side, metadata, and config path", () => {
    const { bindings, sideClient } = installFixture();
    const event = bindings.events.createLifecycleEvent(
        { modId: "appleskin", className: "squeek.appleskin.AppleSkin" },
        { name: "preInit", eventType: "net.minecraftforge.fml.common.event.FMLPreInitializationEvent" },
        { metadata: { modId: "appleskin", name: "AppleSkin", version: "1.0.9" } },
        "CLIENT"
    );
    assert.equal(event.getSide(), sideClient);
    assert.equal(event.getModMetadata().getModId(), "appleskin");
    assert.equal(event.getSuggestedModMetadata().getName(), "AppleSkin");
    assert.equal(event.getSuggestedConfigurationFile().getPath(), "/config/appleskin.cfg");
    assert.equal(event.getModConfigurationDirectory().isDirectory(), true);
    assert.throws(() => event.getAsmData(), /unsupported-asm-data/);
    assert.equal(event.isCancelable(), false);
});

test("supports a persistent Forge-style Configuration object", () => {
    const { bindings } = installFixture();
    const file = new bindings.virtualFileClass("config", "test.cfg");
    const config = bindings.forge.construct(
        "net.minecraftforge.common.config.Configuration", [file, "1.0.0"]);
    assert.equal(config.get("client", "enabled", true, "toggle").getBoolean(true), true);
    const property = config.get("client", "enabled", true, "toggle");
    property.setValue(false);
    assert.equal(config.hasChanged(), true);
    config.save();
    const reloaded = bindings.forge.construct(
        "net.minecraftforge.common.config.Configuration", [file, "1.0.0"]);
    assert.equal(reloaded.get("client", "enabled", true, "toggle").getBoolean(true), false);
    assert.equal(reloaded.hasChanged(), false);
});

test("initializes a translated Event subclass receiver in place", () => {
    const { bindings } = installFixture();
    const receiver = {};
    const result = bindings.forge.construct(
        "net.minecraftforge.fml.common.eventhandler.Event", [], receiver);
    assert.equal(result, receiver);
    assert.equal(receiver.isCanceled(), false);
    assert.equal(receiver.isCancelable(), false);
});

test("starts the injected bundle once the client is ready", () => {
    const { context, bindings } = installFixture();
    let startedWith = null;
    context.ModAPI.mcinstance = { $gameSettings: {} };
    context.ModAPI.settings = {};
    context.GoldClientForge = {
        start(value) { startedWith = value; return Promise.resolve([]); },
        status() { return { started: false }; }
    };
    context.setTimeout = (callback) => { callback(); return 1; };
    context.goldClientForgeAutoStart = true;
    bindings.startWhenReady();
    assert.equal(startedWith, bindings);
});

test("keeps Forge channel registration virtual and rejects real network sends", () => {
    const { bindings } = installFixture();
    const registry = bindings.forge.get(
        "net.minecraftforge.fml.common.network.NetworkRegistry", "INSTANCE");
    const channel = registry.newSimpleChannel("appleskin");
    channel.registerMessage(function Message() {}, function Message() {}, 1, null);
    assert.equal(channel.getChannelName(), "appleskin");
    assert.equal(channel.registrations.length, 1);
    assert.throws(() => channel.sendToServer({}), /unsupported-network/);
    assert.throws(() => registry.registerGuiHandler({}, {}), /unsupported-gui/);
});

test("unimplemented services fail explicitly instead of returning fake success", () => {
    const { bindings } = installFixture();
    assert.throws(() => bindings.render.call("draw"), /unsupported-service/);
    assert.throws(() => bindings.network.call("send"), /unsupported-service/);
    assert.throws(() => bindings.registries.register("item", "x:y"), /unsupported-registry/);
    assert.throws(() => bindings.registries.lookup("item", "minecraft:stone"), /unsupported-registry/);
});
