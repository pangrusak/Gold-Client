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

    function TextureMap(glTextureId) {
        this.$glTextureId = glTextureId;
        this.$nativeInitialized = true;
    }
    TextureMap.prototype.$getGlTextureId = function () { return this.$glTextureId; };

    const textureMapEntry = {
        id: "net.minecraft.client.renderer.texture.TextureMap",
        compiledName: "nmc_TextureMap",
        class: TextureMap,
        methods: {
            getGlTextureId: {
                method: TextureMap.prototype.$getGlTextureId,
                methodName: "nmc_TextureMap_getGlTextureId"
            }
        },
        staticMethods: {},
        staticVariables: {},
        constructors: [
            function TextureMapConstructor(glTextureId) { return new TextureMap(glTextureId); }
        ],
        // EaglerForge's Class__init_N($this, ...) form: initializes an
        // existing receiver, which is what calling super() on a translated
        // subclass needs.
        internalConstructors: [
            function TextureMap__init_0($this, glTextureId) {
                $this.$glTextureId = glTextureId;
                $this.$nativeInitialized = true;
            }
        ]
    };

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
                if (id === "net.minecraft.client.Minecraft") return minecraftEntry;
                if (id === "net.minecraft.client.renderer.texture.TextureMap") return textureMapEntry;
                return null;
            },
            getClassByName(name) {
                if (name === "Minecraft") return minecraftEntry;
                if (name === "TextureMap") return textureMapEntry;
                return null;
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

test("initializes a translated subclass with the native internal constructor", () => {
    const { bindings, context } = installFixture();
    const TextureMapClass = context.ModAPI.reflect
        .getClassById("net.minecraft.client.renderer.texture.TextureMap").class;
    function TranslatedJeiTextureMap() {}
    TranslatedJeiTextureMap.__goldClassName = "mezz.jei.gui.textures.JeiTextureMap";
    TranslatedJeiTextureMap.prototype.$translatedMethod = function () {
        return this.$translatedField;
    };
    const receiver = Object.create(TranslatedJeiTextureMap.prototype);
    receiver.$translatedField = 7;
    receiver.$className = "mezz.jei.gui.textures.JeiTextureMap";

    const result = bindings.minecraft.construct(
        "net.minecraft.client.renderer.texture.TextureMap", [42], receiver);

    assert.equal(result, receiver);
    assert.equal(Object.getPrototypeOf(receiver), TranslatedJeiTextureMap.prototype);
    assert.equal(receiver.$translatedMethod(), 7);
    assert.equal(receiver instanceof TextureMapClass, true);
    assert.equal(receiver.$glTextureId, 42);
    assert.equal(receiver.$nativeInitialized, true);
    assert.equal(receiver.$translatedField, 7);
    assert.equal(typeof receiver.$getGlTextureId, "function");
    assert.equal(receiver.$getGlTextureId(), 42);
});

test("fails loudly when a native constructor allocates instead of initializing the receiver", () => {
    const { bindings, context } = installFixture();
    context.ModAPI.reflect.getClassById = id => ({
        id,
        compiledName: "nmc_Allocating",
        class: function Allocating() {},
        methods: {},
        staticMethods: {},
        staticVariables: {},
        // No internalConstructors: the only path is the allocating
        // constructor, whose result cannot be adopted as the receiver.
        constructors: [function AllocatingConstructor() { return { allocated: true }; }]
    });

    assert.throws(
        () => bindings.minecraft.construct("net.minecraft.world.Allocating", [], {}),
        /unsupported-platform-superclass/);
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
