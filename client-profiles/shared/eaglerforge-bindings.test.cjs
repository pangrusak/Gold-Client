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

    function KeyBinding(description, keyCode, category) {
        this.description = description;
        this.keyCode = keyCode;
        this.category = category;
        this.$keyDescription = description;
    }

    const i18nEntry = {
        id: "net.minecraft.client.resources.I18n",
        compiledName: "nmcr_I18n",
        class: function I18n() {},
        methods: {},
        staticMethods: {
            format: {
                method: function format(description, parameters) {
                    return "localized:" + description + "/" + (parameters?.length ?? 0);
                },
                methodName: "nmcr_I18n_format"
            }
        },
        staticVariables: {},
        constructors: []
    };

    function ResourceLocation(name) {
        this.$name = String(name);
    }
    const resourceLocationEntry = {
        id: "net.minecraft.util.ResourceLocation",
        compiledName: "nmcr_ResourceLocation",
        class: ResourceLocation,
        methods: {},
        staticMethods: {},
        staticVariables: {},
        constructors: [function ResourceLocationConstructor(name) { return new ResourceLocation(name); }]
    };

    const keyBindingEntry = {
        id: "net.minecraft.client.settings.KeyBinding",
        compiledName: "nmcs_KeyBinding",
        class: KeyBinding,
        methods: {},
        staticMethods: {},
        staticVariables: {},
        constructors: [
            function KeyBindingConstructor(description, keyCode, category) {
                return new KeyBinding(description, keyCode, category);
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
        events: {
            types: [],
            listeners: Object.create(null),
            lib_map: Object.create(null),
            newEvent(name) {
                if (!this.types.includes(name)) this.types.push(name);
            }
        },
        addEventListener(name, func) {
            if (!this.events.types.includes(name) && !name.startsWith("custom:")) {
                throw new Error("[ModAPI] This event does not exist!");
            }
            (this.events.listeners[name] ??= []).push(func);
        },
        reflect: {
            getClassById(id) {
                if (id === "net.minecraft.client.Minecraft") return minecraftEntry;
                if (id === "net.minecraft.client.renderer.texture.TextureMap") return textureMapEntry;
                if (id === "net.minecraft.client.settings.KeyBinding") return keyBindingEntry;
                if (id === "net.minecraft.util.ResourceLocation") return resourceLocationEntry;
                if (id === "net.minecraft.client.resources.I18n") return i18nEntry;
                return null;
            },
            getClassByName(name) {
                if (name === "Minecraft") return minecraftEntry;
                if (name === "TextureMap") return textureMapEntry;
                if (name === "KeyBinding") return keyBindingEntry;
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
        atob: value => Buffer.from(value, "base64").toString("binary"),
        TextDecoder,
        GoldClientResources: {
            formatVersion: "gold-client-resources-v1",
            files: {
                "assets/demo/lang/en_us.json":
                    Buffer.from('{"item.demo":"Demo"}', "utf8").toString("base64"),
                "assets/demo/lang/en_us.lang":
                    Buffer.from("item.demo=Demo\ntooltip.demo=Demo tooltip\n", "utf8").toString("base64"),
                "assets/demo/textures/gui.png": Buffer.from([1, 2, 3, 4]).toString("base64")
            }
        },
        GoldClientRuntime: {
            getStaticField(owner, field) {
                if (owner === "net.minecraftforge.fml.relauncher.Side") {
                    return field === "SERVER" ? sideServer : sideClient;
                }
                throw new Error("unexpected field " + owner + "." + field);
            },
            classLiteral(name) {
                return { __goldClassName: name, getName() { return name; } };
            },
            createJava(name) {
                if (/Set$/.test(name)) {
                    const values = [];
                    return {
                        values,
                        add(value) { if (!values.includes(value)) values.push(value); },
                        iterator() { return values[Symbol.iterator](); },
                        [Symbol.iterator]() { return values[Symbol.iterator](); }
                    };
                }
                if (/List$/.test(name)) {
                    const values = [];
                    return { values, add(value) { values.push(value); },
                        iterator() { return values[Symbol.iterator](); } };
                }
                if (/Map$/.test(name)) {
                    const values = new Map();
                    return { values, put(key, value) { values.set(key, value); },
                        get(key) { return values.get(key) ?? null; } };
                }
                throw new Error("unexpected createJava " + name);
            },
            annotationData() {
                return [
                    {
                        className: "example.Plugin", target: "class", member: null,
                        descriptor: null, name: "example.PluginAnnotation",
                        runtimeVisible: true, values: { pluginName: "demo" }
                    },
                    {
                        className: "example.Other", target: "field", member: "FIELD",
                        descriptor: "Ljava/lang/String;", name: "example.FieldAnnotation",
                        runtimeVisible: true, values: {}
                    }
                ];
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

test("reads embedded mod resources without inventing missing files", () => {
    const { bindings } = installFixture();
    const resources = bindings.resources;
    assert.equal(resources.call("has", "assets/demo/lang/en_us.json"), true);
    assert.equal(resources.call("has", "/assets/demo/lang/en_us.json"), true);
    assert.equal(resources.call("has", "assets/demo/missing.json"), false);
    assert.equal(resources.call("getText", "assets/demo/lang/en_us.json"),
        '{"item.demo":"Demo"}');
    assert.deepEqual(Array.from(resources.call("getBytes", "assets/demo/textures/gui.png")),
        [1, 2, 3, 4]);
    assert.deepEqual(Array.from(resources.call("list", "assets/demo/textures/")),
        ["assets/demo/textures/gui.png"]);
    assert.equal(resources.call("get", "assets/demo/missing.json"), null);
    assert.throws(() => resources.call("open", "assets/demo/lang/en_us.json"),
        /unsupported-resource-operation/);
    assert.throws(() => resources.call("has", "../secret"), /invalid-resource-path/);
});

test("serves embedded mod resources through Minecraft.getResourceManager", () => {
    const { bindings } = installFixture();
    const manager = bindings.adapt({ $mcResourceManager: {} }).getResourceManager();
    const location = {
        getResourceDomain: () => "demo",
        getResourcePath: () => "lang/en_us.json"
    };
    const resource = manager.getResource(location);
    assert.equal(resource.getResourceLocation().getResourceDomain(), "demo");
    assert.equal(resource.hasMetadata(), false);
    assert.equal(Buffer.from(resource.getInputStream().readAllBytes()).toString("utf8"),
        '{"item.demo":"Demo"}');
    assert.equal(manager.getAllResources(location).size(), 1);
    assert.equal(manager.getResourceDomains().contains("demo"), true);
    assert.throws(() => manager.getResource({
        getResourceDomain: () => "demo",
        getResourcePath: () => "missing.json"
    }), /FileNotFoundException: demo:missing\.json/);
});

test("registers reload listeners without invoking them until a reload", () => {
    const { bindings } = installFixture();
    const registered = [];
    const nativeManager = {
        registerReloadListener(listener) {
            registered.push(listener);
        }
    };
    const manager = bindings.adapt({ $mcResourceManager: nativeManager }).getResourceManager();
    const calls = [];
    const listener = value => calls.push(value);

    manager.registerReloadListener(listener);
    assert.equal(calls.length, 0, "registration must not simulate a resource reload");
    assert.equal(registered.length, 1);
    assert.equal(typeof registered[0]?.$onResourceManagerReload, "function");

    registered[0].$onResourceManagerReload(nativeManager);
    assert.equal(calls.length, 1);
    assert.equal(calls[0], manager);

    registered[0].$onResourceManagerReload(nativeManager);
    assert.equal(calls.length, 2, "each actual reload must reach the listener");

    manager.func_110542_a(listener);
    assert.equal(calls.length, 2, "the SRG alias must also only register");
    assert.equal(registered.length, 2);
    registered[1].$onResourceManagerReload(nativeManager);
    assert.equal(calls.length, 3);
});

test("fails explicitly when native resource reload registration is unavailable", () => {
    const { bindings } = installFixture();
    const manager = bindings.adapt({ $mcResourceManager: {} }).getResourceManager();
    let calls = 0;
    assert.throws(() => manager.registerReloadListener(() => calls++),
        /resource-manager.*native resource reload listener registration is unavailable/);
    assert.equal(calls, 0, "unsupported registration must not fake a one-time reload");
});

test("resolves TeaVM-prefixed SRG resource reload registration", () => {
    const { bindings } = installFixture();
    let registered = null;
    const nativeManager = {
        $func_110542_a(listener) {
            registered = listener;
        }
    };
    const manager = bindings.adapt({ $mcResourceManager: nativeManager }).getResourceManager();
    let calls = 0;
    manager.registerReloadListener(() => calls++);
    assert.equal(calls, 0);
    assert.equal(typeof registered?.$onResourceManagerReload, "function");
    registered.$onResourceManagerReload(nativeManager);
    assert.equal(calls, 1);
});

test("merges native TeaVM resource lists and domain iterators", () => {
    const { bindings } = installFixture();
    const native = {
        $mcResourceManager: {
            $getAllResources() {
                return {
                    $size: () => 1,
                    $get: index => ({ nativeResource: index })
                };
            },
            $getResourceDomains() {
                let index = 0;
                const names = ["vanilla", "forge"];
                return {
                    $iterator: () => ({
                        $hasNext: () => index < names.length,
                        $next: () => names[index++]
                    })
                };
            }
        }
    };
    const manager = bindings.adapt(native).getResourceManager();
    const location = {
        getResourceDomain: () => "demo",
        getResourcePath: () => "lang/en_us.json"
    };
    const resources = manager.getAllResources(location);
    assert.equal(resources.size(), 2);
    assert.deepEqual(resources.get(1), { nativeResource: 0 });
    const domains = manager.getResourceDomains();
    assert.equal(domains.contains("demo"), true);
    assert.equal(domains.contains("vanilla"), true);
    assert.equal(domains.contains("forge"), true);
    assert.deepEqual([...domains], ["demo", "vanilla", "forge"]);
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

test("iterates native size/get collections", () => {
    const { bindings } = installFixture();
    const list = bindings.adapt({
        $size: () => 2,
        $get: index => ({ value: index })
    });
    const iterator = list.iterator();
    assert.equal(iterator.hasNext(), true);
    assert.deepEqual(iterator.next(), { value: 0 });
    assert.equal(iterator.hasNext(), true);
    assert.deepEqual(iterator.next(), { value: 1 });
    assert.equal(iterator.hasNext(), false);
});

test("localizes a native KeyBinding display name", () => {
    const { bindings } = installFixture();
    function KeyBinding(description) { this.$keyDescription = description; }
    const displayName = bindings.adapt(new KeyBinding("key.sneak")).getDisplayName();
    assert.equal(displayName, "localized:key.sneak/0");
});

test("derives a Forge registry name for native enchantments", () => {
    const { bindings } = installFixture();
    function Enchantment() {}
    const raw = new Enchantment();
    raw.$name23 = "sharpness";
    const location = bindings.adapt(raw).getRegistryName();
    assert.equal(location.getRef().$name, "minecraft:sharpness");
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

test("adapts JavaScript lambdas to TeaVM interface method calls", () => {
    const { bindings, context } = installFixture();
    let observed;
    context.ModAPI.reflect.getClassById = id => ({
        id,
        compiledName: "nmi_Listener",
        class: function Listener() {},
        methods: {},
        staticMethods: {
            probe: {
                method: function probe(callback) {
                    return callback.$onResourceManagerReload(41);
                }
            }
        },
        staticVariables: {},
        constructors: []
    });
    const result = bindings.minecraft.call("net.minecraft.item.Listener", "probe",
        [value => { observed = value; return value + 1; }]);
    assert.equal(observed, 41);
    assert.equal(result, 42);
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
    assert.equal(typeof event.getAsmData().getAll, "function");
    assert.equal(event.isCancelable(), false);
});

test("reconstructs Forge ASMDataTable entries from translated annotations", () => {
    const { bindings } = installFixture();
    const event = bindings.events.createLifecycleEvent(
        { modId: "example", className: "example.Plugin" },
        { name: "preInit", eventType: "net.minecraftforge.fml.common.event.FMLPreInitializationEvent" },
        { metadata: { modId: "example", name: "Example", version: "1.0" } },
        "CLIENT"
    );
    const table = event.getAsmData();
    const plugins = table.getAll("example.PluginAnnotation");
    const entries = Array.from(plugins).map(data => data.getClassName());
    assert.deepEqual(entries, ["example.Plugin"]);
    const data = table.getASMData("example.Plugin", "example.PluginAnnotation");
    assert.equal(data.getAnnotationName(), "example.PluginAnnotation");
    assert.equal(data.getMethodName(), null);
    assert.equal(data.getFieldName(), null);
    assert.equal(data.getObject().get("pluginName"), "demo");
    assert.deepEqual(Array.from(table.getForClass("example.Other")).map(item =>
        item.getFieldName()), ["FIELD"]);
    assert.equal(table.getAll("example.MissingAnnotation").values.length, 0);
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

test("supports Forge Configuration category lifecycle", () => {
    const { bindings } = installFixture();
    const file = new bindings.virtualFileClass("config", "categories.cfg");
    const config = bindings.forge.construct(
        "net.minecraftforge.common.config.Configuration", [file, "1.0.0"]);
    const mode = config.getCategory("MODE");
    assert.equal(mode.getName(), "mode");
    assert.equal(mode.getQualifiedName(), "mode");
    assert.equal(config.hasCategory("MODE"), true);
    assert.equal(config.getCategory("mode").getName(), "mode");
    assert.equal(config.hasChanged(), true);
    const search = config.addCategory("search");
    assert.equal(search.getName(), "search");
    assert.equal(config.getCategory("search").getName(), "search");
    config.removeCategory(search);
    assert.equal(config.hasCategory("search"), false);
    assert.deepEqual(Array.from(config.getCategoryNames()), ["mode"]);
    const child = config.getCategory("parent.child");
    assert.equal(child.getName(), "child");
    assert.equal(child.getQualifiedName(), "parent.child");
    assert.equal(config.getCategory("PARENT").getChild("child").getQualifiedName(),
        "parent.child");
    assert.equal(config.getCategory("parent").getChildren().length, 1);
    config.removeCategory(config.getCategory("parent"));
    assert.equal(config.hasCategory("parent"), false);
    assert.equal(config.hasCategory("parent.child"), false);
    assert.deepEqual(Array.from(config.getCategoryNames()), ["mode"]);
});

test("preserves translated Configuration subclass overrides", () => {
    const { bindings } = installFixture();
    const file = new bindings.virtualFileClass("config", "subclass.cfg");
    const receiver = {
        getString() { return "subclass-result"; }
    };
    const result = bindings.forge.construct(
        "net.minecraftforge.common.config.Configuration", [file, "1.0.0"], receiver);
    assert.equal(result, receiver);
    assert.equal(receiver.getString("name", "category", "default"), "subclass-result");
    // Base accessors remain available through the initialized receiver.
    assert.equal(receiver.getBoolean("category", "enabled", true), true);
});

test("supports Forge Configuration typed accessors and versions", () => {
    const { bindings } = installFixture();
    const file = new bindings.virtualFileClass("config", "typed.cfg");
    const config = bindings.forge.construct(
        "net.minecraftforge.common.config.Configuration", [file, "2.0.0"]);
    assert.equal(config.getLoadedConfigVersion(), null);
    assert.equal(config.getInt("columns", "advanced", 9, 4, 16), 9);
    config.get("advanced", "columns", 9, "columns").setValue(99);
    assert.equal(config.getInt("columns", "advanced", 9, 4, 16), 16);
    const property = config.get("advanced", "mode", "A");
    property.setValidValues(["a", "b"]);
    assert.deepEqual(property.getValidValues(), ["a", "b"]);
    const modes = [
        { name: "A", ordinal: 0 },
        { name: "B", ordinal: 1 }
    ];
    assert.equal(config.getEnum("mode", "advanced", modes[0], modes), modes[0]);
    config.setEnum("mode", "advanced", modes[1], modes);
    assert.equal(config.getEnum("mode", "advanced", modes[0], modes), modes[1]);
    assert.equal(config.getCategory("advanced").hasChanged(), true);
    config.save();
    assert.equal(config.getLoadedConfigVersion(), "2.0.0");
    const reloaded = bindings.forge.construct(
        "net.minecraftforge.common.config.Configuration", [file, "2.0.0"]);
    assert.equal(reloaded.getEnum("mode", "advanced", modes[0], modes), modes[1]);
    assert.equal(reloaded.getLoadedConfigVersion(), "2.0.0");
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
    let tooltip = null;
    const languageKeys = new Map();
    const properties = {
        $put(key, value) { languageKeys.set(context.ModAPI.util.ustr(key), context.ModAPI.util.ustr(value)); },
        $containsKey(key) { return languageKeys.has(context.ModAPI.util.ustr(key)); },
        $get(key) { return languageKeys.get(context.ModAPI.util.ustr(key)) ?? null; },
        $size() { return languageKeys.size; }
    };
    const locale = { $languageCode: "en_US", $properties3: properties };
    context.ModAPI.mcinstance = {
        $gameSettings: { $language: "en_us" },
        $renderEngine: { $textureManager: true },
        $mcResourceManager: { $resourceManager: true },
        $mcLanguageManager: {
            $getCurrentLanguage: () => locale
        }
    };
    context.ModAPI.settings = { $language: "en_us" };
    context.ModAPI.hooks.methods = {
        nmutt_LanguageMap_replaceWith(map) { assert.equal(map, properties); }
    };
    context.goldClientForgeEventBus = {
        post(type, event) {
            tooltip = { type, event };
            return { failures: [] };
        }
    };
    context.GoldClientForge = {
        start(value) { startedWith = value; return Promise.resolve([]); },
        status() { return { started: false }; }
    };
    context.setTimeout = (callback) => { callback(); return 1; };
    context.goldClientForgeAutoStart = true;
    bindings.startWhenReady();
    assert.equal(startedWith, bindings);
    assert.equal(context.goldClientLanguageBridge.status, "applied");
    assert.equal(languageKeys.get("tooltip.demo"), "Demo tooltip");
    assert.equal(context.goldClientLanguageBridge.addedKeys, 2);

    assert.equal(context.ModAPI.events.types.includes("gold:itemtooltip"), true);
    const stack = { native: "stack" };
    const player = { native: "player" };
    const list = { native: "tooltip" };
    context.ModAPI.events.listeners["gold:itemtooltip"][0]({
        itemStack: stack, player, toolTip: list, advanced: true
    });
    assert.equal(tooltip.type, "net.minecraftforge.event.entity.player.ItemTooltipEvent");
    assert.equal(tooltip.event.__goldClassName,
        "net.minecraftforge.event.entity.player.ItemTooltipEvent");
    assert.equal(tooltip.event.getItemStack().getRef(), stack);
    assert.equal(tooltip.event.getEntityPlayer().getRef(), player);
    assert.equal(tooltip.event.getToolTip().getRef(), list);
    assert.equal(tooltip.event.isAdvanced(), true);

    const reloadedKeys = new Map();
    locale.$properties3 = {
        $put(key, value) {
            reloadedKeys.set(context.ModAPI.util.ustr(key), context.ModAPI.util.ustr(value));
        },
        $containsKey(key) { return reloadedKeys.has(context.ModAPI.util.ustr(key)); },
        $get(key) { return reloadedKeys.get(context.ModAPI.util.ustr(key)) ?? null; },
        $size() { return reloadedKeys.size; }
    };
    context.ModAPI.events.listeners["gold:itemtooltip"][0]({
        itemStack: stack, player, toolTip: list, advanced: true
    });
    assert.equal(reloadedKeys.get("tooltip.demo"), "Demo tooltip",
        "mod language keys must return after the client replaces its Locale table");
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
    const eventChannel = registry.newEventDrivenChannel("jei");
    eventChannel.register({ onPacket() {} });
    assert.equal(eventChannel.getChannelName(), "jei");
    assert.equal(eventChannel.registrations.length, 1);
    assert.throws(() => eventChannel.sendToServer({}), /unsupported-network/);
});

test("exposes Forge client key-setting enum constants but rejects conflict logic", () => {
    const { bindings } = installFixture();
    const gui = bindings.forge.get(
        "net.minecraftforge.client.settings.KeyConflictContext", "GUI");
    assert.equal(gui.name, "GUI");
    assert.equal(gui.ordinal, 1);
    assert.equal(bindings.forge.call(
        "net.minecraftforge.client.settings.KeyConflictContext", "valueOf", ["GUI"]), gui);
    const control = bindings.forge.get(
        "net.minecraftforge.client.settings.KeyModifier", "CONTROL");
    assert.equal(control.name, "CONTROL");
    assert.equal(bindings.forge.call(
        "net.minecraftforge.client.settings.KeyModifier", "values", []).length, 4);
    assert.throws(() => gui.isActive(), /isActive/);
    assert.throws(() => control.matches({}), /matches/);
});

test("adapts Forge KeyBinding constructors to the vanilla three-argument form", () => {
    const { bindings } = installFixture();
    const gui = bindings.forge.get(
        "net.minecraftforge.client.settings.KeyConflictContext", "GUI");
    const control = bindings.forge.get(
        "net.minecraftforge.client.settings.KeyModifier", "CONTROL");
    const result = bindings.minecraft.construct(
        "net.minecraft.client.settings.KeyBinding",
        ["key.jei.showRecipe", gui, control, 19, "jei"]);
    const raw = result.getRef();
    assert.equal(raw.description.toString(), "key.jei.showRecipe");
    assert.equal(raw.keyCode, 19);
    assert.equal(raw.category.toString(), "jei");
    assert.equal(raw.$goldKeyConflictContext, gui);
    assert.equal(raw.$goldKeyModifier, control);
});

test("records Forge ClientRegistry key bindings with explicit non-integration warning", () => {
    const { bindings } = installFixture();
    const warnings = [];
    const originalWarn = console.warn;
    console.warn = message => warnings.push(String(message));
    try {
        bindings.forge.call("net.minecraftforge.fml.client.registry.ClientRegistry",
            "registerKeyBinding", [{ name: "key.jei.test" }]);
    } finally {
        console.warn = originalWarn;
    }
    assert.equal(warnings.length, 1);
    assert.match(warnings[0], /not attached to the Eaglercraft input loop/);
    assert.equal(bindings.forge.call("net.minecraftforge.fml.client.registry.ClientRegistry",
        "getKeyBinding", []), null);
});

test("reports undelivered Forge inter-mod messages instead of throwing", () => {
    const { bindings } = installFixture();
    const delivered = bindings.forge.call(
        "net.minecraftforge.fml.common.event.FMLInterModComms", "sendRuntimeMessage",
        ["jei", "VersionChecker", "addCurseCheck", {}]);
    assert.equal(delivered, false);
});

test("unimplemented services fail explicitly instead of returning fake success", () => {
    const { bindings } = installFixture();
    assert.throws(() => bindings.render.call("draw"), /unsupported-service/);
    assert.throws(() => bindings.network.call("send"), /unsupported-service/);
    assert.throws(() => bindings.registries.register("item", "x:y"), /unsupported-registry/);
    assert.throws(() => bindings.registries.lookup("item", "minecraft:stone"), /unsupported-registry/);
});
