"use strict";

// Gold Client <-> EaglerForge bindings.
//
// This adapter is the missing link between translated Forge 1.12.2 mods and an
// Eaglercraft client that has EaglerForge's ModAPI injected. It does not
// implement Minecraft; it resolves the real client classes through ModAPI and
// fails loudly when a member or service is not present.
//
// Load order:
//   1. EaglerForge core scripts (ModAPI is installed when the client starts)
//   2. gold-client-member-map.js (SRG -> MCP member names)
//   3. this script
//   4. gold-client-forge.js / eaglercraft-forge-entry.js
(function installEaglerForgeGoldClient(global) {
    if (global.goldClientEaglerForgeBindingsInstalled) return;

    const VERSION = 1;
    const MEMBER_MAP = global.goldClientForgeMemberMap || Object.create(null);
    let memberForward = null;
    let memberReverse = null;

    function fail(operation, detail) {
        const error = new Error("[GoldForge " + operation + "] " + detail);
        error.name = "GoldClientForgeBindingError";
        throw error;
    }

    function api() {
        const value = global.ModAPI;
        if (!value || !value.reflect || !value.hooks || !value.util) {
            fail("install", "EaglerForge ModAPI is not available");
        }
        return value;
    }

    function runtime() {
        const value = global.GoldClientRuntime ?? global.GoldClientForge?.runtime;
        if (!value || typeof value.getStaticField !== "function") {
            fail("install", "Gold Client runtime is not loaded");
        }
        return value;
    }

    function buildMemberMaps() {
        if (memberForward) return;
        memberForward = new Map();
        memberReverse = new Map();
        for (const [srg, mcp] of Object.entries(MEMBER_MAP)) {
            if (!srg || !mcp || srg === mcp) continue;
            memberForward.set(srg, mcp);
            if (!memberReverse.has(mcp)) memberReverse.set(mcp, srg);
        }
    }

    function memberNames(name) {
        buildMemberMaps();
        const values = [name];
        const forward = memberForward.get(name);
        if (forward && !values.includes(forward)) values.push(forward);
        const reverse = memberReverse.get(name);
        if (reverse && !values.includes(reverse)) values.push(reverse);
        // Forge/vanilla names that have no SRG/MCP pair in the supplied table.
        const aliasTable = {
            "func_82582_d": ["hasNoTags"],
            "isEmpty": ["hasNoTags"]
        };
        const aliases = Object.prototype.hasOwnProperty.call(aliasTable, name)
            ? aliasTable[name] : [];
        for (const alias of aliases) {
            if (!values.includes(alias)) values.push(alias);
        }
        return values;
    }

    function classNameOf(raw) {
        return raw?.constructor?.__goldClassName
            ?? raw?.$meta?.name
            ?? null;
    }

    function isJavaString(value) {
        return value !== null && typeof value === "object"
            && value.$characters && value.$characters.data
            && typeof value.$characters.data.length === "number";
    }

    function isTeaVMArray(value) {
        return value !== null && typeof value === "object"
            && value.data && typeof value.data.length === "number"
            && typeof value.type === "function";
    }

    function isProxy(value) {
        return value !== null && typeof value === "object"
            && value.isModProxy === true && typeof value.getRef === "function";
    }

    function adaptNative(value) {
        if (value === null || value === undefined) return value;
        if (typeof value === "string" || typeof value === "number"
            || typeof value === "boolean" || typeof value === "bigint") {
            return value;
        }
        if (isProxy(value)) return value;
        if (isJavaString(value)) {
            return api().util.ustr(value);
        }
        if (isTeaVMArray(value)) {
            return adaptArray(value);
        }
        if (typeof value !== "object" && typeof value !== "function") return value;
        return nativeProxy(value);
    }

    function adaptArray(native) {
        if (native.__goldClientArrayProxy) return native.__goldClientArrayProxy;
        const data = native.data;
        const proxy = new Proxy(data, {
            get(target, property) {
                if (property === "isModProxy") return true;
                if (property === "getRef") return () => native;
                if (property === "getCorrective") return () => proxy;
                if (property === "length") return target.length;
                if (typeof property === "string" && /^(?:0|[1-9][0-9]*)$/.test(property)) {
                    return adaptNative(target[property]);
                }
                const value = Reflect.get(target, property, target);
                return typeof value === "function" ? value.bind(target) : value;
            },
            set(target, property, value) {
                target[property] = unwrapArgument(value);
                return true;
            }
        });
        Object.defineProperty(native, "__goldClientArrayProxy", {
            value: proxy, configurable: true
        });
        return proxy;
    }

    const nativeProxies = new WeakMap();

    function createTextureStitchEvent(map) {
        const event = new GoldClientForgeEvent();
        event.getMap = () => map;
        return event;
    }

    function postTextureStitch(phase, map) {
        if (map === null || map === undefined) return;
        const bus = global.goldClientForgeEventBus;
        if (!bus || typeof bus.post !== "function") return;
        try {
            const type = "net.minecraftforge.client.event.TextureStitchEvent$" + phase;
            if (typeof console.warn === "function") {
                console.warn("[GoldForge texture-stitch-event] " + phase
                    + " listeners=" + (bus.listeners?.(type)?.length ?? "?"),
                    classNameOf(map) ?? map?.constructor?.name ?? typeof map);
            }
            const result = bus.post(type, createTextureStitchEvent(map));
            for (const failure of result?.failures ?? []) {
                console.error("[GoldForge texture-stitch-event] " + phase
                    + " listener " + failure.owner + " failed: "
                    + (failure.error?.message ?? String(failure.error)), failure.error);
            }
        } catch (error) {
            console.error("[GoldForge texture-stitch-event] " + phase + " dispatch failed", error);
        }
    }

    /**
     * Mirrors Forge's TextureStitchEvent around a real native texture-map load.
     * The event is only posted when a Forge event bus is installed and a map
     * object is supplied; the native load still performs all texture work.
     */
    function invokeNativeMethod(raw, name, method, args) {
        const mapped = name?.startsWith("$") ? name.slice(1) : name;
        const textureLoad = (mapped === "loadTexture"
            || mapped === "loadTickableTexture" || mapped === "func_110580_a")
            && args.length >= 2 && args[1] !== null && typeof args[1] === "object";
        if (textureLoad) postTextureStitch("Pre", args[1]);
        try {
            return Reflect.apply(method, raw, args.map(unwrapArgument));
        } finally {
            if (textureLoad) postTextureStitch("Post", args[1]);
        }
    }

    function nativeProxy(target) {
        const existing = nativeProxies.get(target);
        if (existing) return existing;
        const proxy = new Proxy(target, {
            get(raw, property) {
                if (property === "isModProxy") return true;
                // Runtime metadata probes must not be treated as missing
                // native members. Native objects intentionally have no
                // translated class name; return their real property (usually
                // undefined) instead of throwing from the binding layer.
                if (property === "javaClassName" || property === "__goldClassName") {
                    return raw?.constructor?.$meta?.name
                        ?? raw?.$meta?.name
                        ?? Reflect.get(raw, property, raw);
                }
                if (property === "javaThrowable" || property === "constructor"
                    || property === "$meta") {
                    return Reflect.get(raw, property, raw);
                }
                if (property === "getRef") return () => raw;
                if (property === "getCorrective") return () => proxy;
                if (property === "then") return undefined;
                if (property === Symbol.toPrimitive) {
                    return hint => {
                        const toString = resolveNativeMember(raw, "toString");
                        if (toString) {
                            const value = toString();
                            return hint === "number" ? Number(value) : String(value);
                        }
                        return hint === "number" ? NaN : "[native object]";
                    };
                }
                if (typeof property === "symbol") return Reflect.get(raw, property, raw);
                if (property === "ordinal") {
                    if (typeof raw.$ordinal === "number") return raw.$ordinal;
                    if (typeof raw.ordinal === "number") return raw.ordinal;
                }
                if (property === "name") {
                    if (typeof raw.$name2 === "string") return api().util.ustr(raw.$name2);
                    if (typeof raw.$name === "string") return api().util.ustr(raw.$name);
                }
                if (property.startsWith("$")) {
                    const direct = Reflect.get(raw, property, raw);
                    return typeof direct === "function"
                        ? (...args) => adaptNative(invokeNativeMethod(raw, property, direct, args))
                        : adaptNative(direct);
                }
                const names = memberNames(property);
                for (const name of names) {
                    const key = "$" + name;
                    if (key in raw) {
                        const value = Reflect.get(raw, key, raw);
                        return typeof value === "function"
                            ? (...args) => adaptNative(invokeNativeMethod(raw, name, value, args))
                            : adaptNative(value);
                    }
                }
                for (const name of names) {
                    if (name in raw) {
                        const value = Reflect.get(raw, name, raw);
                        return typeof value === "function"
                            ? (...args) => adaptNative(invokeNativeMethod(raw, name, value, args))
                            : adaptNative(value);
                    }
                }
                // TeaVM fields may receive numeric suffixes when names collide.
                for (const name of names) {
                    const prefix = "$" + name;
                    const key = Reflect.ownKeys(raw).find(candidate =>
                        typeof candidate === "string" && candidate.startsWith(prefix)
                        && /^[0-9]+$/.test(candidate.slice(prefix.length)));
                    if (key) {
                        const value = Reflect.get(raw, key, raw);
                        return typeof value === "function"
                            ? (...args) => adaptNative(invokeNativeMethod(raw, name, value, args))
                            : adaptNative(value);
                    }
                }
                if (isNativeItemStack(raw)) {
                    if (property === "hasCapability") {
                        return (capability, facing) =>
                            stackHasCapability(raw, capability, facing) ? 1 : 0;
                    }
                    if (property === "getCapability") {
                        return (capability, facing) =>
                            adaptNative(stackGetCapability(raw, capability, facing));
                    }
                    if (property === "serializeNBT") {
                        return () => {
                            const compound = constructNative(
                                "net.minecraft.nbt.NBTTagCompound", []);
                            const rawCompound = isProxy(compound)
                                ? compound.getRef() : compound;
                            const write = raw.$writeToNBT ?? raw.writeToNBT;
                            if (typeof write !== "function") {
                                fail("missing-native-member",
                                    "ItemStack cannot write its NBT");
                            }
                            write.call(raw, rawCompound);
                            return adaptNative(compound);
                        };
                    }
                }
                if (property === "getRegistryName") {
                    return () => {
                        const registryName = nativeRegistryName(raw);
                        return registryName === undefined ? undefined : registryName;
                    };
                }
                if (property === "getCreativeTabs"
                    && ("$tabToDisplayOn" in raw || "tabToDisplayOn" in raw)) {
                    // Forge adds getCreativeTabs() to Item; Eaglercraft keeps
                    // the vanilla single tabToDisplayOn field. Forge's default
                    // implementation is a one-element array of that tab.
                    return () => {
                        const tab = raw.$tabToDisplayOn ?? raw.tabToDisplayOn;
                        return tab === null || tab === undefined ? [] : [adaptNative(tab)];
                    };
                }
                if (property === "getCreativeTab"
                    && ("$tabToDisplayOn" in raw || "tabToDisplayOn" in raw)) {
                    return () => adaptNative(raw.$tabToDisplayOn ?? raw.tabToDisplayOn);
                }
                if (property === "iterator" && isNativeListLike(raw)) {
                    return () => nativeListIterator(raw);
                }
                fail("missing-native-member", (classNameOf(raw)
                    || raw?.constructor?.name || "<unknown>") + "." + property
                    + "; mapped candidates: " + names.join(", ")
                    + "; own keys: " + Reflect.ownKeys(raw).slice(0, 12).map(String).join(", "));
            },
            set(raw, property, value) {
                if (typeof property !== "string") return Reflect.set(raw, property, value, raw);
                const names = property.startsWith("$") ? [property.slice(1)] : memberNames(property);
                for (const name of names) {
                    const key = "$" + name;
                    if (key in raw || name in raw) {
                        const targetKey = key in raw ? key : name;
                        return Reflect.set(raw, targetKey, unwrapArgument(value), raw);
                    }
                }
                fail("missing-native-field", (classNameOf(raw) || "<unknown>") + "." + property
                    + " is not a writable native field");
            },
            has(raw, property) {
                if (typeof property !== "string") return Reflect.has(raw, property);
                return Reflect.has(raw, property)
                    || memberNames(property).some(name => ("$" + name) in raw || name in raw);
            }
        });
        nativeProxies.set(target, proxy);
        return proxy;
    }

    function resolveNativeMember(raw, property) {
        for (const name of memberNames(property)) {
            const key = "$" + name;
            if (key in raw && typeof raw[key] === "function") return (...args) => raw[key](...args);
            if (name in raw && typeof raw[name] === "function") return (...args) => raw[name](...args);
        }
        return null;
    }

    /**
     * Forge 1.12 adds `RegistryEntry.getRegistryName()`. Eaglercraft's native
     * Item/Block classes predate that convenience method but expose the same
     * information through `Item.REGISTRY` / `Block.REGISTRY`. Use the real
     * native registry lookup instead of inventing a name.
     *
     * Returns undefined when the receiver is not known to either registry, so
     * the caller still reports a genuine missing member.
     */
    /**
     * Structural/native type check used by overload selection. Eaglercraft
     * classes are plain TeaVM classes, so their native prototype chain answers
     * class tests; iterable/collection/map interfaces are structural because
     * TeaVM does not materialize an interface prototype chain.
     */
    function nativeInstanceOf(value, className) {
        if (value === null || value === undefined) return false;
        const raw = isProxy(value) ? value.getRef() : value;
        let entry = null;
        try {
            entry = api().reflect.getClassById(className);
        } catch (error) {
            entry = null;
        }
        if (!entry) {
            try {
                entry = api().reflect.getClassByName(
                    String(className).slice(String(className).lastIndexOf(".") + 1));
            } catch (error) {
                entry = null;
            }
        }
        if (entry?.class?.prototype?.isPrototypeOf?.(raw)) return true;
        if (className === "java.lang.Iterable") {
            return typeof raw.$iterator === "function" || typeof raw.iterator === "function";
        }
        if (className === "java.util.Collection") {
            return typeof raw.$size === "function"
                && (typeof raw.$iterator === "function" || typeof raw.iterator === "function");
        }
        if (className === "java.util.Map") {
            return typeof raw.$entrySet === "function" || typeof raw.entrySet === "function";
        }
        return false;
    }

    function nativeRegistryName(raw) {        let lookedUp = false;
        for (const owner of ["net.minecraft.item.Item", "net.minecraft.block.Block"]) {
            let entry = null;
            try {
                entry = api().reflect.getClassById(owner);
            } catch (error) {
                entry = null;
            }
            const registry = entry?.staticVariables?.REGISTRY;
            if (!registry) continue;
            const lookup = registry.$getNameForObject ?? registry.getNameForObject;
            if (typeof lookup !== "function") continue;
            lookedUp = true;
            const found = lookup.call(registry, raw);
            if (found !== null && found !== undefined) return adaptNative(found);
        }
        return lookedUp ? null : undefined;
    }

    /** Returns true when a native object behaves like a java.util.List (size/get). */
    function isNativeListLike(raw) {
        if (raw == null) return false;
        try {
            const hasGet = typeof raw.$get === "function" || typeof raw.get === "function";
            const size = raw.$tagCount ?? raw.tagCount ?? raw.$size ?? raw.size;
            const hasSize = typeof size === "number" || typeof size === "function";
            return hasGet && hasSize;
        } catch (error) {
            return false;
        }
    }

    /** Builds a java.util.Iterator adapter over a native list-like (NBTTagList, etc.). */
    function nativeListIterator(raw) {
        const sizeFn = typeof raw.$tagCount === "function" ? raw.$tagCount
            : typeof raw.tagCount === "function" ? raw.tagCount
            : () => raw.$tagCount ?? raw.tagCount;
        const getFn = typeof raw.$get === "function" ? raw.$get : raw.get;
        let index = 0;
        return {
            hasNext: () => index < sizeFn.call(raw),
            next: () => {
                if (index >= sizeFn.call(raw)) throw new Error("NoSuchElementException");
                return adaptNative(getFn.call(raw, index++));
            }
        };
    }

    const callbackProxies = new WeakMap();

    /**
     * TeaVM calls callbacks through their Java interface method names (for
     * example `$onResourceManagerReload`). A lowered Java lambda is a plain
     * JavaScript closure, which has no such member. Wrap it lazily so any
     * TeaVM-style `$method` call invokes the closure with the native arguments;
     * the generated lambda already has the exact functional-interface body.
     */
    function adaptCallback(callback) {
        let proxy = callbackProxies.get(callback);
        if (proxy) return proxy;
        proxy = new Proxy(callback, {
            get(target, property, receiver) {
                if (typeof property === "string" && property.startsWith("$")
                    && !Reflect.has(target, property)) {
                    return (...args) => adaptNative(
                        Reflect.apply(target, target, args.map(unwrapArgument)));
                }
                return Reflect.get(target, property, target);
            },
            apply(target, thisArg, args) {
                return Reflect.apply(target, thisArg, args);
            }
        });
        callbackProxies.set(callback, proxy);
        return proxy;
    }

    function unwrapArgument(value) {
        if (isProxy(value)) return value.getRef();
        if (value === null || value === undefined) return value;
        if (typeof value === "string") {
            const util = api().util;
            if (typeof util.str !== "function") fail("string-adapter", "ModAPI.util.str is unavailable");
            return util.str(value);
        }
        if (typeof value === "boolean") return value ? 1 : 0;
        if (typeof value === "function") return adaptCallback(value);
        if (Array.isArray(value)) return value.map(unwrapArgument);
        return value;
    }

    function reflection(owner) {
        const modApi = api();
        const normalized = String(owner).replaceAll("/", ".");
        let entry = modApi.reflect.getClassById(normalized);
        if (!entry) {
            const simple = normalized.slice(normalized.lastIndexOf(".") + 1);
            entry = modApi.reflect.getClassByName(simple);
        }
        if (!entry) fail("unknown-class", normalized);
        return entry;
    }

    function chooseMethod(table, names, args, owner, kind) {
        // Prefer the name as written by the caller. The SRG/MCP alternative is
        // only consulted when the direct name is absent or has no matching
        // arity; this avoids treating an alias as an overload.
        let sawAnyName = false;
        for (const name of names) {
            const record = table?.[name];
            if (!record || typeof record.method !== "function") continue;
            sawAnyName = true;
            if (record.method.length === args.length) return { name, record };
            if (record.method.length === 0) return { name, record };
        }
        if (!sawAnyName) return null;
        fail("missing-method-arity", owner + "." + names[0] + "/" + args.length
            + " exists but not with that arity");
    }

    function callStatic(owner, method, args) {
        const entry = reflection(owner);
        const found = chooseMethod(entry.staticMethods, memberNames(method), args, owner, "static");
        if (!found) {
            if (entry.methods && memberNames(method).some(name => entry.methods[name])) {
                fail("instance-method-without-receiver", owner + "." + method);
            }
            fail("missing-static-method", owner + "." + method + "/" + args.length);
        }
        const result = Reflect.apply(found.record.method, entry.class,
            Array.from(args, unwrapArgument));
        return adaptNative(result);
    }

    let warnedKeyBindingAdapter = false;

    function adaptKeyBindingArguments(owner, args) {
        if (!String(owner).endsWith("net.minecraft.client.settings.KeyBinding")) {
            return { args, context: null, modifier: null };
        }
        if (args.length === 4) {
            return { args: [args[0], args[2], args[3]], context: args[1], modifier: null };
        }
        if (args.length === 5) {
            return { args: [args[0], args[3], args[4]], context: args[1], modifier: args[2] };
        }
        return { args, context: null, modifier: null };
    }

    function constructNative(owner, args, receiver = null) {
        const entry = reflection(owner);
        const adapted = adaptKeyBindingArguments(owner, Array.from(args));
        if (adapted.context !== null && !warnedKeyBindingAdapter) {
            warnedKeyBindingAdapter = true;
            if (global.console && typeof global.console.warn === "function") {
                global.console.warn("[GoldForge key-binding-adapter] Forge key conflict context"
                    + " and modifier metadata is retained, but the Eaglercraft KeyBinding does not"
                    + " enforce it");
            }
        }
        const encoded = Array.from(adapted.args, unwrapArgument);
        if (receiver) return initializeNativeSuperclass(owner, entry, encoded, receiver);
        const constructors = entry.constructors ?? [];
        if (!constructors.length) {
            fail("missing-constructor", owner + " has no constructors in ModAPI reflection");
        }
        const exact = constructors.filter(fn => typeof fn === "function" && fn.length === encoded.length);
        let constructor;
        if (exact.length === 1) constructor = exact[0];
        else if (exact.length > 1) {
            fail("ambiguous-constructor", owner + "/" + encoded.length + " has multiple constructors");
        } else if (constructors.length === 1 && typeof constructors[0] === "function") {
            constructor = constructors[0];
        } else {
            fail("missing-constructor-arity", owner + "/" + encoded.length);
        }
        const result = Reflect.apply(constructor, receiver ?? entry.class, encoded);
        if (result === null || result === undefined) fail("null-constructor", owner);
        if (adapted.context !== null) {
            result.$goldKeyConflictContext = adapted.context;
            if (adapted.modifier !== null) result.$goldKeyModifier = adapted.modifier;
        }
        return adaptNative(result);
    }

    /**
     * Initializes the native part of a translated subclass. Translated classes
     * have no JavaScript `extends` chain, so this attaches the native prototype
     * to the translated prototype chain and then runs the platform's real super
     * initializer against the receiver.
     *
     * <p>EaglerForge exposes `internalConstructors` (the `Class__init_N($this,
     * ...)` functions) precisely for calling super() on custom classes. Those
     * are preferred because they initialize an existing object instead of
     * allocating a replacement. Hosts that only expose regular constructors
     * fall back to invoking the real constructor with the receiver as `this`;
     * if that constructor allocates a different object we fail instead of
     * copying or guessing its state.</p>
     */
    /**
     * Some public Minecraft methods are absent from Eaglercraft's TeaVM method
     * table even though their behavior is still needed by translated Forge
     * subclasses. TextureMap.registerSprite is one such method: the native
     * implementation populates `mapRegisteredSprites` with a
     * TextureAtlasSprite constructed from the location's Java string. Rebuild
     * that behavior from visible native fields and the real constructor rather
     * than returning a placeholder.
     */
    function installNativeSuperclassAdapters(owner, prototype) {
        if (owner !== "net.minecraft.client.renderer.texture.TextureMap"
            || prototype == null) return;
        const registerSprite = function goldClientRegisterSprite(location) {
            if (location === null || location === undefined) {
                fail("texture-map-register-sprite", "cannot register a null sprite location");
            }
            const map = this.$mapRegisteredSprites;
            if (!map || typeof map.$get !== "function" || typeof map.$put !== "function") {
                fail("texture-map-register-sprite",
                    "native TextureMap has no initialized mapRegisteredSprites");
            }
            let sprite = map.$get(location);
            if (sprite === null || sprite === undefined) {
                const util = api().util;
                if (typeof util.str !== "function") {
                    fail("texture-map-register-sprite", "ModAPI.util.str is unavailable");
                }
                const created = constructNative(
                    "net.minecraft.client.renderer.texture.TextureAtlasSprite",
                    [util.str(String(location))]);
                sprite = isProxy(created) ? created.getRef() : created;
                map.$put(location, sprite);
            }
            return adaptNative(sprite);
        };
        for (const name of ["registerSprite", "func_174942_a"]) {
            if (typeof prototype[name] === "function") continue;
            try {
                Object.defineProperty(prototype, name, {
                    value: registerSprite, configurable: true, writable: true
                });
            } catch (ignored) {
                // Frozen prototypes keep their existing surface.
            }
        }
    }

    function initializeNativeSuperclass(owner, entry, args, receiver) {
        const nativeClass = entry.class;
        if (typeof nativeClass !== "function" || !nativeClass.prototype) {
            fail("missing-platform-superclass-binding", owner
                + " has no native class in ModAPI reflection");
        }
        if (!nativeClass.prototype.isPrototypeOf(receiver)) {
            const receiverPrototype = Object.getPrototypeOf(receiver);
            const translatedPrototype = receiverPrototype != null
                && receiverPrototype !== Object.prototype
                && typeof receiverPrototype.constructor === "function"
                && (receiverPrototype.constructor.__goldClassName != null
                    || String(receiverPrototype.constructor.name ?? "")
                        .startsWith("Translated_"));
            if (translatedPrototype) {
                // Translated classes do not use JavaScript `extends`, so their
                // prototype is the place to attach the native chain. Replacing
                // the receiver's prototype would hide every translated method.
                Object.setPrototypeOf(receiverPrototype, nativeClass.prototype);
            } else if (receiverPrototype == null || receiverPrototype === Object.prototype) {
                // Plain host object: this instance can adopt the native
                // prototype directly without affecting a shared prototype.
                Object.setPrototypeOf(receiver, nativeClass.prototype);
            } else {
                fail("unsupported-platform-superclass", "cannot attach the native prototype of "
                    + owner + " to an unexpected receiver prototype");
            }
        }

        installNativeSuperclassAdapters(owner, Object.getPrototypeOf(receiver));

        const internal = (entry.internalConstructors ?? []).filter(fn => typeof fn === "function");
        const exact = internal.filter(fn => fn.length === args.length + 1);
        let initializer = null;
        if (exact.length === 1) initializer = exact[0];
        else if (exact.length > 1) {
            fail("ambiguous-super-constructor", owner + "/" + args.length
                + " has multiple native super initializers");
        } else if (internal.length === 1) {
            initializer = internal[0];
        }

        if (initializer) {
            try {
                if (typeof nativeClass === "function") nativeClass.call(receiver);
                initializer(receiver, ...args);
            } catch (error) {
                if (error && error.name === "GoldClientForgeBindingError") throw error;
                fail("unsupported-platform-superclass", "native super initializer for " + owner
                    + " failed on a translated receiver: " + describe(error));
            }
            restoreTextureMapFramebufferState(owner, receiver);
            return receiver;
        }

        const constructors = entry.constructors ?? [];
        const matching = constructors.filter(
            fn => typeof fn === "function" && fn.length === args.length);
        const constructor = matching.length === 1
            ? matching[0]
            : (constructors.length === 1 && typeof constructors[0] === "function"
                ? constructors[0] : null);
        if (!constructor) {
            fail("missing-platform-super-constructor-binding", "cannot initialize translated "
                + "subclass of " + owner + "/" + args.length
                + ": the host exposes no internal constructor");
        }
        try {
            const result = Reflect.apply(constructor, receiver, args);
            if (result !== null && result !== undefined && result !== receiver) {
                fail("unsupported-platform-superclass", "native constructor for " + owner
                    + " allocated a replacement object instead of initializing the translated receiver");
            }
        } catch (error) {
            if (error && error.name === "GoldClientForgeBindingError") throw error;
            fail("unsupported-platform-superclass", "native constructor for " + owner
                + " could not initialize a translated receiver: " + describe(error));
        }
        restoreTextureMapFramebufferState(owner, receiver);
        return receiver;
    }

    /**
     * Eaglercraft's TeaVM `TextureMap.tick()` dereferences
     * `copyColorFramebuffer.data` unconditionally. Forge's TextureMap leaves
     * that field null when a subclass replaces `loadTextureAtlas` without
     * allocating mipmap framebuffers (JEI's JeiTextureMap does exactly that).
     * A translated subclass therefore needs the field to be a real empty native
     * array so the native tick treats the framebuffer copy pass as absent,
     * instead of failing on a null dereference. Existing arrays are never
     * replaced, so maps that do allocate mipmaps keep the native behavior.
     */
    function restoreTextureMapFramebufferState(owner, receiver) {
        if (owner !== "net.minecraft.client.renderer.texture.TextureMap"
            || receiver === null || receiver === undefined) return;
        const createArray = api().hooks?._teavm?.$rt_createArray;
        if (typeof createArray !== "function") return;
        const objectEntry = api().reflect.getClassByName("Object")
            ?? api().reflect.getClassById("java.lang.Object");
        const elementClass = objectEntry?.class;
        if (typeof elementClass !== "function") return;
        for (const field of ["copyColorFramebuffer", "copyMaterialFramebuffer"]) {
            for (const key of [field, "$" + field]) {
                if (receiver[key] === null || receiver[key] === undefined) {
                    receiver[key] = createArray(elementClass, 0);
                }
            }
        }
    }

    function describe(error) {
        return error && error.message ? error.message : String(error);
    }


    function getStaticField(owner, field) {
        // ForgeRegistries is a Forge-only class not present in Eaglercraft.
        // Map its ITEMS/BLOCKS views onto the corresponding native registries.
        if (owner === "net.minecraftforge.fml.common.registry.ForgeRegistries") {
            const names = memberNames(field);
            if (names.includes("ITEMS") || names.includes("BLOCKS")) {
                const nativeOwner = names.includes("ITEMS")
                    ? "net.minecraft.item.Item" : "net.minecraft.block.Block";
                return getStaticField(nativeOwner, "REGISTRY");
            }
        }
        const entry = reflection(owner);
        const names = memberNames(field);
        const slots = entry.staticVariables;
        for (const name of names) {
            if (slots && name in slots) return adaptNative(slots[name]);
            if (entry.class && name in entry.class) return adaptNative(entry.class[name]);
        }
        fail("missing-static-field", owner + "." + field);
    }

    function setStaticField(owner, field, value) {
        const entry = reflection(owner);
        const names = memberNames(field);
        const slots = entry.staticVariables;
        for (const name of names) {
            if (slots && name in slots) {
                slots[name] = unwrapArgument(value);
                return value;
            }
            if (entry.class && name in entry.class) {
                entry.class[name] = unwrapArgument(value);
                return value;
            }
        }
        fail("missing-static-field", owner + "." + field);
    }

    function stringArgument(value) {
        if (isJavaString(value)) return api().util.ustr(value);
        return value === null || value === undefined ? "" : String(value);
    }

    function sideObject() {
        const rt = runtime();
        return rt.getStaticField("net.minecraftforge.fml.relauncher.Side",
            api().isServer ? "SERVER" : "CLIENT");
    }

    // ---- virtual config files -------------------------------------------------

    const FILE_STORAGE_KEY = "gold-client-virtual-files-v1";
    let fileSystem = null;

    function loadFileSystem() {
        if (fileSystem) return fileSystem;
        fileSystem = new Map();
        try {
            const stored = global.localStorage?.getItem(FILE_STORAGE_KEY);
            if (stored) {
                const parsed = JSON.parse(stored);
                for (const [path, entry] of Object.entries(parsed.files ?? {})) {
                    fileSystem.set(path, { ...entry });
                }
            }
        } catch (error) {
            // A missing/denied localStorage is not a reason to lose file
            // operations for this session. Keep the in-memory view explicit.
            global.goldClientVirtualFileWarning = error;
        }
        return fileSystem;
    }

    function persistFileSystem() {
        try {
            const files = {};
            for (const [path, entry] of loadFileSystem()) {
                if (entry.type === "file") files[path] = entry;
            }
            global.localStorage?.setItem(FILE_STORAGE_KEY, JSON.stringify({ files }));
        } catch (error) {
            global.goldClientVirtualFileWarning = error;
        }
    }

    function normalizePath(value) {
        const input = String(value ?? "").replaceAll("\\", "/");
        const parts = [];
        for (const part of input.split("/")) {
            if (!part || part === ".") continue;
            if (part === "..") parts.pop();
            else parts.push(part);
        }
        return "/" + parts.join("/");
    }

    class GoldClientVirtualFile {
        constructor(...parts) {
            let raw;
            if (parts[0] instanceof GoldClientVirtualFile) {
                raw = parts[0].getPath() + (parts[1] ? "/" + parts[1] : "");
            } else {
                raw = parts.map(part => String(part ?? "")).join("/");
            }
            this.path = normalizePath(raw);
            this.javaClassName = "java.io.File";
        }
        getName() { return this.path.slice(this.path.lastIndexOf("/") + 1); }
        getPath() { return this.path; }
        getAbsolutePath() { return this.path; }
        getCanonicalPath() { return this.path; }
        getParent() {
            if (this.path === "/" ) return null;
            const index = this.path.lastIndexOf("/");
            return index <= 0 ? "/" : this.path.slice(0, index);
        }
        getParentFile() {
            const parent = this.getParent();
            return parent ? new GoldClientVirtualFile(parent) : null;
        }
        exists() { return loadFileSystem().has(this.path); }
        isFile() { return loadFileSystem().get(this.path)?.type === "file"; }
        isDirectory() { return this.path === "/" || loadFileSystem().get(this.path)?.type === "dir"; }
        mkdir() { return this.createDirectory(false); }
        mkdirs() { return this.createDirectory(true); }
        createDirectory(recursive) {
            const fs = loadFileSystem();
            if (recursive) {
                const parts = this.path.split("/").filter(Boolean);
                let current = "";
                for (const part of parts) {
                    current += "/" + part;
                    if (!fs.has(current)) fs.set(current, { type: "dir", lastModified: Date.now() });
                    else if (fs.get(current).type !== "dir") return false;
                }
                persistFileSystem();
                return true;
            }
            if (fs.has(this.path)) return false;
            const parent = this.getParentFile();
            if (parent && !parent.isDirectory()) return false;
            fs.set(this.path, { type: "dir", lastModified: Date.now() });
            persistFileSystem();
            return true;
        }
        createNewFile() {
            const fs = loadFileSystem();
            if (fs.has(this.path)) return false;
            fs.set(this.path, { type: "file", content: "", lastModified: Date.now() });
            persistFileSystem();
            return true;
        }
        delete() {
            const fs = loadFileSystem();
            if (!fs.has(this.path)) return false;
            fs.delete(this.path);
            persistFileSystem();
            return true;
        }
        length() { return this.readText()?.length ?? 0; }
        lastModified() { return loadFileSystem().get(this.path)?.lastModified ?? 0; }
        list() {
            if (!this.isDirectory()) return null;
            return this.listFiles().map(file => file.getName());
        }
        listFiles() {
            if (!this.isDirectory()) return null;
            const prefix = this.path === "/" ? "/" : this.path + "/";
            const result = [];
            for (const path of loadFileSystem().keys()) {
                if (!path.startsWith(prefix)) continue;
                const rest = path.slice(prefix.length);
                if (rest && !rest.includes("/")) result.push(new GoldClientVirtualFile(path));
            }
            return result;
        }
        renameTo(other) {
            const target = other instanceof GoldClientVirtualFile
                ? other : new GoldClientVirtualFile(other);
            const fs = loadFileSystem();
            const entry = fs.get(this.path);
            if (!entry) return false;
            fs.set(target.path, { ...entry, lastModified: Date.now() });
            fs.delete(this.path);
            persistFileSystem();
            return true;
        }
        readText() {
            const entry = loadFileSystem().get(this.path);
            return entry?.type === "file" ? entry.content ?? "" : null;
        }
        writeText(content) {
            const fs = loadFileSystem();
            const parent = this.getParentFile();
            if (parent && !parent.isDirectory()) parent.mkdirs();
            fs.set(this.path, { type: "file", content: String(content), lastModified: Date.now() });
            persistFileSystem();
        }
        toString() { return this.path; }
        equals(other) { return other instanceof GoldClientVirtualFile && other.path === this.path; }
        hashCode() {
            let hash = 0;
            for (let i = 0; i < this.path.length; i++) {
                hash = ((hash << 5) - hash + this.path.charCodeAt(i)) | 0;
            }
            return hash;
        }
    }

    // ---- Forge configuration --------------------------------------------------

    class GoldClientConfiguration {
        constructor(file, version = "1.0", ...rest) {
            this.file = file instanceof GoldClientVirtualFile
                ? file : new GoldClientVirtualFile(file);
            this.version = stringArgument(version);
            this.categories = new Map();
            this.changed = false;
            this.modId = stringArgument(rest[0] ?? "unknown");
            this.load();
        }
        load() {
            const text = this.file.readText();
            if (!text) return;
            let parsed;
            try {
                parsed = JSON.parse(text);
            } catch (error) {
                fail("invalid-config", this.file.getPath() + " is not valid JSON: " + error.message);
            }
            for (const [category, values] of Object.entries(parsed.categories ?? {})) {
                this.category(category).values = { ...values };
            }
            this.changed = false;
        }
        save() {
            const categories = {};
            for (const [name, category] of this.categories) {
                categories[name] = { ...category.values };
            }
            this.file.writeText(JSON.stringify({
                version: this.version, categories
            }, null, 2));
            this.changed = false;
        }
        hasChanged() { return this.changed; }
        getConfigFile() { return this.file; }
        category(name) {
            const key = stringArgument(name);
            if (!this.categories.has(key)) {
                this.categories.set(key, { name: key, values: {}, comment: null, languageKey: null });
            }
            return this.categories.get(key);
        }
        categoryView(category) {
            if (category.view) return category.view;
            const self = this;
            category.view = {
                getName: () => category.name,
                getQualifiedName: () => category.name,
                setComment: comment => { category.comment = String(comment); },
                getComment: () => category.comment,
                setLanguageKey: key => { category.languageKey = String(key); },
                getLanguageKey: () => category.languageKey ?? "config." + category.name,
                getOrderedConfigElements: () => Object.keys(category.values)
                    .sort()
                    .map(key => self.get(category.name, key, category.values[key])),
                getValues: () => ({ ...category.values }),
                get: key => category.values[stringArgument(key)],
                containsKey: key => Object.prototype.hasOwnProperty.call(
                    category.values, stringArgument(key)),
                put: (key, value) => {
                    category.values[stringArgument(key)] = value;
                    self.changed = true;
                },
                remove: key => {
                    const name = stringArgument(key);
                    if (!Object.prototype.hasOwnProperty.call(category.values, name)) return;
                    delete category.values[name];
                    self.changed = true;
                },
                hasChanged: () => self.changed,
                getChildren: () => [],
                getChild: () => null,
                isChild: () => false,
                setChild: () => {}
            };
            return category.view;
        }
        addCategory(name) {
            const category = this.category(name);
            return this.categoryView(category);
        }
        getCategory(name) {
            const key = stringArgument(name);
            if (!this.categories.has(key)) return null;
            return this.categoryView(this.categories.get(key));
        }
        removeCategory(category) {
            const name = typeof category === "string"
                ? stringArgument(category)
                : (typeof category?.getName === "function" ? stringArgument(category.getName()) : null);
            if (name === null || !this.categories.has(name)) return;
            this.categories.delete(name);
            this.changed = true;
        }
        hasCategory(name) { return this.categories.has(stringArgument(name)); }
        getCategoryNames() { return [...this.categories.keys()].sort(); }
        addCustomCategoryComment(category, comment) {
            this.category(category).comment = String(comment);
        }
        setCategoryComment(category, comment) {
            this.addCustomCategoryComment(category, comment);
        }
        setCategoryLanguageKey(category, languageKey) {
            this.category(category).languageKey = String(languageKey);
        }
        getCategoryLanguageKey(category) {
            return this.category(category).languageKey ?? "config." + stringArgument(category);
        }
        getLoadedConfigVersion() {
            const text = this.file.readText();
            if (!text) return null;
            try {
                const parsed = JSON.parse(text);
                return parsed.version === undefined ? null : String(parsed.version);
            } catch (error) {
                fail("invalid-config", this.file.getPath() + " is not valid JSON: " + error.message);
            }
        }
        get(categoryName, key, defaultValue, description, type) {
            const category = this.category(categoryName);
            const name = stringArgument(key);
            const hasValue = Object.prototype.hasOwnProperty.call(category.values, name);
            if (!hasValue) {
                category.values[name] = defaultValue;
            }
            const self = this;
            const property = {
                name,
                category: category.name,
                defaultValue,
                description: description === undefined ? null : String(description),
                type,
                showInGui: true,
                languageKey: null,
                getBoolean(fallback = false) { return Boolean(category.values[name] ?? fallback); },
                getInt(fallback = 0) { return Number(category.values[name] ?? fallback) | 0; },
                getFloat(fallback = 0) { return Math.fround(Number(category.values[name] ?? fallback)); },
                getDouble(fallback = 0) { return Number(category.values[name] ?? fallback); },
                getString(fallback = "") { return String(category.values[name] ?? fallback); },
                getStringList(fallback = []) {
                    const value = category.values[name];
                    return Array.isArray(value) ? value.map(String) : fallback;
                },
                get() { return category.values[name]; },
                setValue(value) {
                    category.values[name] = Array.isArray(value)
                        ? value.map(String) : value;
                    self.changed = true;
                    return true;
                },
                hasChanged() {
                    return JSON.stringify(category.values[name]) !== JSON.stringify(defaultValue);
                },
                isDefault() {
                    return JSON.stringify(category.values[name]) === JSON.stringify(defaultValue);
                },
                getDefault() { return defaultValue; },
                getName() { return name; },
                getQualifiedName() { return category.name + "." + name; },
                getLanguageKey() { return property.languageKey ?? "config." + category.name + "." + name; },
                setLanguageKey(languageKey) { property.languageKey = String(languageKey); },
                getComment() { return property.description; },
                setComment(comment) { property.description = String(comment); },
                getType() { return type; },
                setValidValues(values) {
                    property.validValues = Array.isArray(values) ? values.map(String) : [];
                },
                getValidValues() { return property.validValues ?? []; },
                setShowInGui(show) { property.showInGui = Boolean(show); },
                showInGui() { return property.showInGui; },
                setRequiresMcRestart() {},
                setRequiresWorldRestart() {},
                requiresMcRestart() { return false; },
                requiresWorldRestart() { return false; }
            };
            return property;
        }
        enumName(value) {
            if (value === undefined || value === null) return null;
            if (typeof value === "string") return value;
            if (value.name !== undefined) return String(value.name);
            if (value.ordinal !== undefined) return String(value.ordinal);
            return String(value);
        }
        getEnum(name, category, defaultValue, enumValues) {
            const categoryName = stringArgument(category);
            const key = stringArgument(name);
            const values = Array.isArray(enumValues) ? enumValues : [];
            const categoryObject = this.category(categoryName);
            let stored;
            if (Object.prototype.hasOwnProperty.call(categoryObject.values, key)) {
                stored = categoryObject.values[key];
            } else {
                stored = this.enumName(defaultValue);
                categoryObject.values[key] = stored;
            }
            const wanted = (typeof stored === "object" && stored !== null)
                ? this.enumName(stored) : stringArgument(stored);
            const match = values.find(value => this.enumName(value) === wanted);
            return match ?? defaultValue;
        }
        setEnum(name, category, value, enumValues) {
            const key = stringArgument(name);
            const categoryObject = this.category(category);
            categoryObject.values[key] = this.enumName(value);
            this.changed = true;
        }
        getInt(name, category, defaultValue, minValue, maxValue, description) {
            const property = this.get(category, name, defaultValue, description);
            const value = property.getInt(defaultValue);
            if (typeof minValue === "number" && typeof maxValue === "number") {
                return Math.min(Math.max(value, Math.min(minValue, maxValue)), Math.max(minValue, maxValue));
            }
            return value;
        }
        getBoolean(category, key, defaultValue, description) {
            return this.get(category, key, defaultValue, description).getBoolean(defaultValue);
        }
        getString(name, category, defaultValue, description) {
            return this.get(category, name, defaultValue, description).getString(defaultValue);
        }
        getFloat(name, category, defaultValue, description) {
            return this.get(category, name, defaultValue, description).getFloat(defaultValue);
        }
        getStringList(name, category, defaultValue, description) {
            return this.get(category, name, defaultValue, description).getStringList(defaultValue);
        }
    }

    // ---- Forge events ---------------------------------------------------------

    class GoldClientForgeEvent {
        constructor() {
            this._cancelable = false;
            this.isCanceled = false;
            this.result = null;
            this.phase = null;
        }
        isCancelable() { return this._cancelable === true; }
        setCancelable(value) { this._cancelable = Boolean(value); }
        isCanceled() { return this.isCanceled === true; }
        setCanceled(value) {
            if (!this._cancelable) {
                fail("event-not-cancelable", "event cannot be canceled");
            }
            this.isCanceled = Boolean(value);
        }
        getResult() { return this.result; }
        setResult(value) { this.result = value; }
        getPhase() { return this.phase; }
        setPhase(value) { this.phase = value; }
        getSide() { return sideObject(); }
    }

    const EVENT_RESULT = Object.freeze({
        DEFAULT: Object.freeze({ name: "DEFAULT", ordinal: 0, toString: () => "DEFAULT" }),
        ALLOW: Object.freeze({ name: "ALLOW", ordinal: 1, toString: () => "ALLOW" }),
        DENY: Object.freeze({ name: "DENY", ordinal: 2, toString: () => "DENY" })
    });

    const EVENT_PRIORITY_VALUES = ["HIGHEST", "HIGH", "NORMAL", "LOW", "LOWEST"]
        .map((name, ordinal) => Object.freeze({
            name, ordinal, toString: () => name,
            compareTo(other) { return ordinal - (other?.ordinal ?? 0); }
        }));
    const EVENT_PRIORITY = Object.freeze(Object.fromEntries(
        EVENT_PRIORITY_VALUES.map(value => [value.name, value])));

    function metadataFor(mod, context) {
        const metadata = context?.metadata ?? {
            modId: mod.modId, name: mod.name ?? mod.modId,
            version: mod.version ?? "unknown", className: mod.className
        };
        const authors = Object.freeze([]);
        return {
            modId: metadata.modId,
            name: metadata.name,
            version: metadata.version,
            description: null,
            url: null,
            updateJSON: null,
            logoFile: null,
            credits: null,
            authors,
            getModId() { return metadata.modId; },
            getName() { return metadata.name; },
            getVersion() { return metadata.version; },
            getDescription() { return metadata.description ?? null; },
            getURL() { return metadata.url ?? null; },
            getUpdateJSON() { return metadata.updateJSON ?? null; },
            getLogoFile() { return metadata.logoFile ?? null; },
            getCredits() { return metadata.credits ?? null; },
            getAuthorList() { return authors.slice(); },
            getParent() { return null; },
            getChildMods() { return []; },
            getDependencies() { return []; },
            getDependants() { return []; },
            getRequiredMods() { return []; },
            getAcceptedMinecraftVersions() { return "[1.12.2]"; }
        };
    }

    // Forge's ASMDataTable is discovery data harvested from the input JAR. The
    // translated bundle carries the same annotation values in registerClass
    // metadata, so reconstruct the table instead of inventing annotations.
    // Annotations that were not present in the bytecode stay absent.
    function asmAnnotationRecords() {
        const forge = global.GoldClientForge;
        if (Array.isArray(forge?.annotations)) return forge.annotations;
        const value = global.GoldClientRuntime;
        return typeof value?.annotationData === "function" ? value.annotationData() : [];
    }

    function descriptorClassName(descriptor) {
        if (typeof descriptor !== "string" || !descriptor) return null;
        if (descriptor.startsWith("L") && descriptor.endsWith(";")) {
            return descriptor.slice(1, -1).replace(/\//g, ".");
        }
        if (descriptor.startsWith("[")) {
            return descriptorClassName(descriptor.slice(1)) + "[]";
        }
        return ({ Z: "boolean", B: "byte", C: "char", S: "short",
            I: "int", J: "long", F: "float", D: "double", V: "void" })[descriptor] ?? descriptor;
    }

    function asmAnnotationValue(value) {
        if (value === null || value === undefined) return null;
        if (Array.isArray(value)) return value.map(asmAnnotationValue);
        if (typeof value !== "object") return value;
        if (value.$goldEnum) return String(value.$goldEnum.constant);
        if (value.$goldClass) {
            const name = descriptorClassName(value.$goldClass);
            return name === null ? null : runtime().classLiteral(name);
        }
        if (value.$goldAnnotation) {
            return asmAnnotationMap(value.$goldAnnotation.values ?? {});
        }
        return value;
    }

    function asmAnnotationMap(values) {
        const map = runtime().createJava("java.util.HashMap");
        for (const [key, value] of Object.entries(values ?? {})) {
            map.put(key, asmAnnotationValue(value));
        }
        return map;
    }

    function asmDataFor(record) {
        return Object.freeze({
            getClassName: () => record.className,
            getAnnotationName: () => record.name,
            getObject: () => asmAnnotationMap(record.values),
            getMethodName: () => record.target === "method" ? record.member : null,
            getFieldName: () => record.target === "field" ? record.member : null,
            isClass: () => record.target === "class",
            toString: () => record.className + "@" + record.name
        });
    }

    function asmDataSet(predicate) {
        const set = runtime().createJava("java.util.LinkedHashSet");
        for (const record of asmAnnotationRecords()) {
            if (predicate(record)) set.add(asmDataFor(record));
        }
        return set;
    }

    function asmDataTable() {
        return Object.freeze({
            getAll(annotationName) {
                const name = String(annotationName);
                return asmDataSet(record => record.name === name);
            },
            getForClass(className) {
                const name = String(className);
                return asmDataSet(record => record.className === name);
            },
            getAllFor(className) {
                return this.getForClass(className);
            },
            getASMData(className, annotationName) {
                const target = String(className);
                const name = String(annotationName);
                const record = asmAnnotationRecords().find(item =>
                    item.className === target && item.name === name);
                return record ? asmDataFor(record) : null;
            }
        });
    }

    function createLifecycleEvent(mod, phase, context, side) {
        const event = new GoldClientForgeEvent();
        event.eventType = phase.eventType;
        event.phaseName = phase.name;
        event.modId = mod.modId;
        event.side = side === "SERVER" ? "SERVER" : "CLIENT";
        event.getSide = () => sideObject();
        event.metadata = metadataFor(mod, context);
        event.getModMetadata = () => event.metadata;
        event.getSuggestedModMetadata = () => event.metadata;
        event.getModConfigurationDirectory = () => {
            const directory = new GoldClientVirtualFile("config");
            if (!directory.exists()) directory.mkdirs();
            return directory;
        };
        event.getSuggestedConfigurationFile = () =>
            new GoldClientVirtualFile("config", mod.modId + ".cfg");
        event.getAsmData = () => asmDataTable();
        // FMLConstructionEvent exposes the discovery/loader state. The browser
        // host has no Forge discovery table or loader-state list, so these fail
        // explicitly instead of returning invented containers.
        event.getModContainers = () => {
            fail("unsupported-construction-context",
                "FMLConstructionEvent.getModContainers is not available through EaglerForge");
        };
        event.getDiscoveredMods = () => {
            fail("unsupported-construction-context",
                "FMLConstructionEvent.getDiscoveredMods is not available through EaglerForge");
        };
        event.getLoaderState = () => {
            fail("unsupported-construction-context",
                "FMLConstructionEvent.getLoaderState is not available through EaglerForge");
        };
        if (phase.name.startsWith("server")) {
            event.getServer = () => global.ModAPI?.server ?? null;
        }
        return event;
    }

    // ---- Forge-facing facade --------------------------------------------------

    const FML_COMMON_HANDLER = Object.freeze({
        getSide: () => sideObject(),
        getEffectiveSide: () => sideObject(),
        getMinecraftServerInstance: () => global.ModAPI?.server ?? null,
        exitJava: () => fail("unsupported-operation", "FMLCommonHandler.exitJava"),
        getInstance: () => FML_COMMON_HANDLER
    });

    // Forge mods register their packet classes during init even when they never
    // send a packet on a client. Registration itself is metadata, so keep it
    // virtual. Any actual network I/O still fails explicitly instead of
    // pretending the packet was delivered.
    const NETWORK_CHANNELS = new Map();

    function simpleNetworkChannel(name) {
        const channelName = String(name);
        const registrations = [];
        const unsupported = operation => fail("unsupported-network",
            "SimpleNetworkWrapper." + operation + " is not available through the Eaglercraft client");
        const channel = Object.freeze({
            channelName,
            registrations,
            registerMessage(...args) {
                registrations.push(Object.freeze({ args: args.slice() }));
                return undefined;
            },
            sendToServer: () => unsupported("sendToServer"),
            sendTo: () => unsupported("sendTo"),
            sendToAll: () => unsupported("sendToAll"),
            sendToAllAround: () => unsupported("sendToAllAround"),
            sendToDimension: () => unsupported("sendToDimension"),
            getChannelName: () => channelName
        });
        NETWORK_CHANNELS.set(channelName, channel);
        return channel;
    }

    function eventDrivenChannel(name) {
        const channelName = String(name);
        const registrations = [];
        const unsupported = operation => fail("unsupported-network",
            "FMLEventChannel." + operation + " is not available through the Eaglercraft client");
        const channel = Object.freeze({
            channelName,
            registrations,
            register(...args) {
                registrations.push(Object.freeze({ args: args.slice() }));
                return undefined;
            },
            registerMessage(...args) {
                registrations.push(Object.freeze({ args: args.slice() }));
                return undefined;
            },
            sendToServer: () => unsupported("sendToServer"),
            sendTo: () => unsupported("sendTo"),
            sendToAll: () => unsupported("sendToAll"),
            sendToAllAround: () => unsupported("sendToAllAround"),
            sendToDimension: () => unsupported("sendToDimension"),
            getChannelName: () => channelName
        });
        NETWORK_CHANNELS.set(channelName, channel);
        return channel;
    }

    const NETWORK_REGISTRY = Object.freeze({
        newSimpleChannel(...args) { return simpleNetworkChannel(args[0]); },
        newEventDrivenChannel(...args) { return eventDrivenChannel(args[0]); },
        registerGuiHandler: () => fail("unsupported-gui",
            "NetworkRegistry.registerGuiHandler has no Eaglercraft client route")
    });

    // Forge's inter-mod communication bus has no Eaglercraft equivalent. The
    // Java API returns a boolean delivery result, so report non-delivery
    // instead of pretending the message reached another mod.
    function undeliveredImc(operation, args) {
        const target = args && args[0] != null ? String(args[0]) : "";
        if (global.console && typeof global.console.warn === "function") {
            global.console.warn("[GoldForge unsupported-imc] " + operation
                + (target ? " for " + target : "") + " was not delivered");
        }
        return false;
    }

    // Forge client settings enums have no native Eaglercraft counterparts, but
    // they are metadata passed to KeyBinding constructors. Expose the real 1.12.2
    // constants and fail explicitly if translated code tries to evaluate the
    // client-specific conflict logic itself.
    function keySettingsEnum(values, operation) {
        const constants = values.map((name, ordinal) => Object.freeze({
            name, ordinal, toString: () => name,
            compareTo: other => ordinal - (other?.ordinal ?? 0),
            isActive: () => fail(operation, "isActive"),
            conflicts: () => fail(operation, "conflicts"),
            matches: () => fail(operation, "matches")
        }));
        return Object.freeze({
            staticFields: Object.freeze(Object.fromEntries(
                constants.map(value => [value.name, value]))),
            staticMethods: {
                values: () => constants.slice(),
                valueOf: args => constants.find(value => value.name === String(args[0]))
                    ?? fail("unknown-enum", operation + "." + args[0])
            }
        });
    }

    // Forge's ClientRegistry has no Eaglercraft counterpart. Keep a real
    // registration list so mods can query what they registered, but warn that
    // the bindings are not wired into the client's input loop by this bridge.
    const CLIENT_KEY_BINDINGS = [];
    let warnedClientRegistry = false;
    const CLIENT_REGISTRY = Object.freeze({
        registerKeyBinding(...bindings) {
            const list = Array.isArray(bindings) ? bindings : [bindings];
            CLIENT_KEY_BINDINGS.push(...list);
            if (!warnedClientRegistry) {
                warnedClientRegistry = true;
                if (global.console && typeof global.console.warn === "function") {
                    global.console.warn("[GoldForge client-registry] Key bindings are recorded but"
                        + " not attached to the Eaglercraft input loop by this bridge");
                }
            }
        },
        getKeyBinding: () => null
    });

    function forgeModContainer(mod) {
        const modId = String(mod.modId);
        const name = String(mod.name ?? modId);
        const version = String(mod.version ?? "unknown");
        const metadata = Object.freeze({
            getModId: () => modId,
            getName: () => name,
            getVersion: () => version
        });
        return Object.freeze({
            __goldClassName: "net.minecraftforge.fml.common.ModContainer",
            getModId: () => modId,
            getName: () => name,
            getVersion: () => version,
            getMetadata: () => metadata,
            getSource: () => null,
            getOwningMod: () => null,
            getDependencies: () => Object.freeze([]),
            getDependants: () => Object.freeze([]),
            getCustomModProperties: () => new Map(),
            getSharedModDescriptor: () => null
        });
    }

    const FML_LOADER = Object.freeze({
        __goldClassName: "net.minecraftforge.fml.common.Loader",
        instance: () => FML_LOADER,
        getIndexedModList() {
            const indexed = new Map();
            for (const mod of global.GoldClientForge?.mods ?? []) {
                indexed.set(String(mod.modId), forgeModContainer(mod));
            }
            return indexed;
        },
        getModList() { return Array.from(this.getIndexedModList().values()); },
        getModContainerById(modId) {
            return this.getIndexedModList().get(String(modId)) ?? null;
        },
        getModContainerState(modId) {
            return this.getModContainerById(modId) ? "LOADED" : "MISSING";
        },
        isModLoaded(modId) {
            const loaded = global.goldClientForgeModIds?.has(String(modId))
                || this.getIndexedModList().has(String(modId));
            return loaded ? 1 : 0;
        },
        isModActive() { return false; },
        getActiveModList() { return this.getModList(); },
        activeModContainer() { return this.getModList()[0] ?? null; },
        isInitialized() { return true; },
        isLoading() { return false; },
        getLoaderState() { return "AVAILABLE"; }
    });

    const PROGRESS_STACK = [];
    const PROGRESS_MANAGER = Object.freeze({
        push(title, size) {
            const total = Number(size ?? 0);
            const bar = {
                __goldClassName: "net.minecraftforge.fml.common.ProgressManager$ProgressBar",
                title: String(title ?? ""),
                size: Number.isFinite(total) ? total : 0,
                steps: 0,
                step(value) {
                    this.steps = Math.min(this.size, this.steps + 1);
                    return value;
                }
            };
            PROGRESS_STACK.push(bar);
            return bar;
        },
        pop() {
            if (!PROGRESS_STACK.length) {
                fail("progress-manager", "pop without a matching push");
            }
            return PROGRESS_STACK.pop();
        }
    });

    // ---------------------------------------------------------------------
    // Forge capabilities
    //
    // Eaglercraft's native classes have no Forge capability system. The bridge
    // keeps the real 1.12.2 contract: built-in capability instances are stable
    // objects, CapabilityManager registrations create further instances, and
    // ItemStack lookups consult the item's own initCapabilities provider. There
    // is no invented provider: an item that supplies none reports no capability.
    // ---------------------------------------------------------------------
    const CAPABILITY_TYPES = new Map();

    function capabilityTypeName(type) {
        if (type == null) return null;
        if (typeof type === "string") return type.replaceAll("/", ".");
        if (typeof type === "function") {
            return type.__goldClassName ?? type.className ?? type.name ?? null;
        }
        const named = type.getName?.() ?? type.__goldClassName ?? type.name;
        return named == null ? null : String(named).replaceAll("/", ".");
    }

    function createCapability(name, typeName, options = {}) {
        return Object.freeze({
            __goldClassName: "net.minecraftforge.common.capabilities.Capability",
            name: String(name),
            typeName: typeName == null ? null : String(typeName),
            defaultProvider: options.defaultProvider ?? null,
            storage: options.storage ?? null,
            getName: () => String(name),
            getType: () => typeName ?? null,
            getDefault: () => options.defaultProvider ?? null,
            cast: value => value ?? null
        });
    }

    function registerCapability(type, storage, defaultProvider) {
        const typeName = capabilityTypeName(type);
        if (!typeName) {
            fail("capability-registration", "Cannot register a capability without a type");
        }
        const capability = createCapability(typeName, typeName, { storage, defaultProvider });
        CAPABILITY_TYPES.set(typeName, capability);
        CAPABILITY_TYPES.set(typeName.replaceAll("/", "."), capability);
        return capability;
    }

    const CAPABILITY_FLUID_HANDLER = createCapability(
        "net.minecraftforge.fluids.capability.IFluidHandler",
        "net.minecraftforge.fluids.capability.IFluidHandler");
    const CAPABILITY_FLUID_HANDLER_ITEM = createCapability(
        "net.minecraftforge.fluids.capability.IFluidHandlerItem",
        "net.minecraftforge.fluids.capability.IFluidHandlerItem");
    const CAPABILITY_ITEM_HANDLER = createCapability(
        "net.minecraftforge.items.IItemHandler",
        "net.minecraftforge.items.IItemHandler");
    const CAPABILITY_ENERGY = createCapability(
        "net.minecraftforge.energy.IEnergyStorage",
        "net.minecraftforge.energy.IEnergyStorage");
    registerCapability("net.minecraftforge.fluids.capability.IFluidHandler",
        null, null);
    registerCapability("net.minecraftforge.fluids.capability.IFluidHandlerItem",
        null, null);
    registerCapability("net.minecraftforge.items.IItemHandler", null, null);
    registerCapability("net.minecraftforge.energy.IEnergyStorage", null, null);
    // Built-in constants keep their canonical objects even after registration.
    CAPABILITY_TYPES.set("net.minecraftforge.fluids.capability.IFluidHandler",
        CAPABILITY_FLUID_HANDLER);
    CAPABILITY_TYPES.set("net.minecraftforge.fluids.capability.IFluidHandlerItem",
        CAPABILITY_FLUID_HANDLER_ITEM);
    CAPABILITY_TYPES.set("net.minecraftforge.items.IItemHandler", CAPABILITY_ITEM_HANDLER);
    CAPABILITY_TYPES.set("net.minecraftforge.energy.IEnergyStorage", CAPABILITY_ENERGY);

    const CAPABILITY_MANAGER = Object.freeze({
        __goldClassName: "net.minecraftforge.common.capabilities.CapabilityManager",
        register(type, storage, defaultProvider) {
            registerCapability(type, storage, defaultProvider);
        },
        registerInternal(type, storage, defaultProvider) {
            registerCapability(type, storage, defaultProvider);
        }
    });

    const stackCapabilityProviders = new WeakMap();

    function itemStackProviders(stack) {
        const cached = stackCapabilityProviders.get(stack);
        if (cached) return cached;
        const providers = [];
        const getItem = resolveNativeMember(stack, "getItem");
        const item = getItem ? getItem() : null;
        if (item != null) {
            const initCapabilities = resolveNativeMember(item, "initCapabilities");
            if (initCapabilities) {
                const provider = initCapabilities(stack, null);
                if (provider != null) providers.push(isProxy(provider) ? provider.getRef() : provider);
            }
        }
        stackCapabilityProviders.set(stack, providers);
        return providers;
    }

    function providerHasCapability(provider, capability, facing) {
        if (provider == null) return false;
        const has = resolveNativeMember(provider, "hasCapability");
        if (has) return Boolean(has(capability, facing));
        // ICapabilityProvider's default hasCapability() is getCapability() != null.
        const get = resolveNativeMember(provider, "getCapability");
        return get ? get(capability, facing) != null : false;
    }

    function providerGetCapability(provider, capability, facing) {
        if (provider == null) return null;
        const get = resolveNativeMember(provider, "getCapability");
        return get ? get(capability, facing) : null;
    }

    function stackHasCapability(stack, capability, facing) {
        if (capability == null || capability.__goldClassName
                !== "net.minecraftforge.common.capabilities.Capability") {
            return false;
        }
        return itemStackProviders(stack).some(provider =>
            providerHasCapability(provider, capability, facing));
    }

    function stackGetCapability(stack, capability, facing) {
        if (capability == null || capability.__goldClassName
                !== "net.minecraftforge.common.capabilities.Capability") {
            return null;
        }
        for (const provider of itemStackProviders(stack)) {
            const value = providerGetCapability(provider, capability, facing);
            if (value != null) return value;
        }
        return capability.getDefault?.() ?? null;
    }

    let nativeItemStackClass;
    function isNativeItemStack(raw) {
        if (nativeItemStackClass === undefined) {
            try {
                const entry = api().reflect.getClassById("net.minecraft.item.ItemStack");
                nativeItemStackClass = entry?.class ?? null;
            } catch (ignored) {
                nativeItemStackClass = null;
            }
        }
        if (nativeItemStackClass && raw instanceof nativeItemStackClass) return true;
        return raw?.constructor?.name === "nmi_ItemStack";
    }

    const FACADES = new Map([
        ["net.minecraftforge.common.capabilities.CapabilityManager", {
            staticFields: { INSTANCE: CAPABILITY_MANAGER },
            staticMethods: {
                register: args => CAPABILITY_MANAGER.register(args[0], args[1], args[2]),
                registerInternal: args =>
                    CAPABILITY_MANAGER.registerInternal(args[0], args[1], args[2])
            }
        }],
        ["net.minecraftforge.fluids.capability.CapabilityFluidHandler", {
            staticFields: {
                FLUID_HANDLER_CAPABILITY: CAPABILITY_FLUID_HANDLER,
                FLUID_HANDLER_ITEM_CAPABILITY: CAPABILITY_FLUID_HANDLER_ITEM
            }
        }],
        ["net.minecraftforge.items.CapabilityItemHandler", {
            staticFields: { ITEM_HANDLER_CAPABILITY: CAPABILITY_ITEM_HANDLER }
        }],
        ["net.minecraftforge.energy.CapabilityEnergy", {
            staticFields: { ENERGY: CAPABILITY_ENERGY }
        }],
        ["net.minecraftforge.fml.client.registry.ClientRegistry", {
            staticMethods: {
                registerKeyBinding: (...args) => CLIENT_REGISTRY.registerKeyBinding(...args),
                getKeyBinding: () => CLIENT_REGISTRY.getKeyBinding()
            }
        }],
        ["net.minecraftforge.client.settings.KeyConflictContext",
            keySettingsEnum(["UNIVERSAL", "GUI", "IN_GAME"], "KeyConflictContext")],
        ["net.minecraftforge.client.settings.KeyModifier",
            keySettingsEnum(["NONE", "SHIFT", "CONTROL", "ALT"], "KeyModifier")],
        ["net.minecraftforge.fml.common.network.NetworkRegistry", {
            staticFields: { INSTANCE: NETWORK_REGISTRY }
        }],
        ["net.minecraftforge.fml.common.event.FMLInterModComms", {
            staticMethods: {
                sendRuntimeMessage: args => undeliveredImc("sendRuntimeMessage", args),
                sendMessage: args => undeliveredImc("sendMessage", args)
            }
        }],
        ["net.minecraftforge.fml.common.ProgressManager", {
            staticMethods: {
                push: args => PROGRESS_MANAGER.push(args[0], args[1]),
                pop: () => PROGRESS_MANAGER.pop()
            }
        }],
        // Eaglercraft does not carry Forge's FluidRegistry. JEI references it for
        // water/lava fluid stacks and fluid lookups. Provide a minimal facade so
        // translated code does not crash; fluid stacks built with null fluids are
        // tolerated downstream as "no fluid" rather than failing the whole mod.
        ["net.minecraftforge.fluids.FluidRegistry", {
            staticFields: { WATER: null, LAVA: null },
            staticMethods: {
                getRegisteredFluids: () => {
                    const emptyIterator = {
                        hasNext: () => false,
                        next: () => { throw new Error("NoSuchElementException"); }
                    };
                    return {
                        values: () => ({ iterator: () => emptyIterator })
                    };
                },
                isUniversalBucketEnabled: () => 0,
                getDefaultFluidName: () => null,
                lookupFluidForBlock: () => null
            }
        }],
        ["net.minecraftforge.fml.common.FMLCommonHandler", {
            staticMethods: { instance: () => FML_COMMON_HANDLER }
        }],
        ["net.minecraftforge.fml.common.Loader", {
            staticMethods: {
                instance: () => FML_LOADER,
                isModLoaded: args => FML_LOADER.isModLoaded(args[0]),
                isModActive: () => false,
                getModList: () => FML_LOADER.getModList(),
                getIndexedModList: () => FML_LOADER.getIndexedModList(),
                getModContainerById: args => FML_LOADER.getModContainerById(args[0])
            }
        }],
        ["net.minecraftforge.fml.common.eventhandler.EventPriority", {
            staticFields: EVENT_PRIORITY,
            staticMethods: {
                values: () => EVENT_PRIORITY_VALUES.slice(),
                valueOf: args => EVENT_PRIORITY[String(args[0])]
                    ?? fail("unknown-enum", "EventPriority." + args[0])
            }
        }],
        ["net.minecraftforge.fml.common.eventhandler.Event$Result", {
            staticFields: EVENT_RESULT
        }],
        ["net.minecraftforge.common.config.Configuration", {
            construct: (args, receiver) => initializeConfiguration(receiver, args)
        }]
    ]);

    function initializeConfiguration(receiver, args) {
        const config = new GoldClientConfiguration(...args);
        if (!receiver) return config;
        receiver.file = config.file;
        receiver.version = config.version;
        receiver.categories = config.categories;
        receiver.changed = config.changed;
        receiver.modId = config.modId;
        for (const name of Object.getOwnPropertyNames(GoldClientConfiguration.prototype)) {
            if (name === "constructor") continue;
            // A translated subclass (for example JEI's LocalizedConfiguration)
            // already exposes its own override through its prototype. Do not
            // shadow it with the base adapter method. Plain receivers still get
            // the base implementation.
            if (typeof receiver[name] === "function") continue;
            // Assign the prototype function, not a bound copy: every method
            // reads this.categories/this.changed from the translated receiver.
            receiver[name] = GoldClientConfiguration.prototype[name];
        }
        return receiver;
    }

    function initializeEvent(receiver) {
        if (!receiver) return new GoldClientForgeEvent();
        for (const name of Object.getOwnPropertyNames(GoldClientForgeEvent.prototype)) {
            if (name === "constructor") continue;
            receiver[name] = GoldClientForgeEvent.prototype[name].bind(receiver);
        }
        receiver.isCancelable ??= false;
        receiver.isCanceled ??= false;
        receiver.result ??= null;
        receiver.phase ??= null;
        return receiver;
    }

    function forgeConstruct(owner, args, receiver = null) {
        const facade = FACADES.get(owner);
        if (facade?.construct) return facade.construct(args, receiver);
        if (owner === "net.minecraftforge.fml.common.eventhandler.Event") {
            return initializeEvent(receiver);
        }
        if (owner.startsWith("net.minecraftforge.fml.common.event.")) {
            return initializeEvent(receiver);
        }
        return constructNative(owner, args, receiver);
    }

    const NO_FACADE = Symbol("no-facade");

    function facadeCall(owner, method, args) {
        const facade = FACADES.get(owner);
        if (!facade) return NO_FACADE;
        const fn = facade.staticMethods?.[method];
        if (!fn) fail("missing-static-method", owner + "." + method);
        return fn(args);
    }

    function forgeCall(owner, method, args) {
        const facadeResult = facadeCall(owner, method, args);
        if (facadeResult !== NO_FACADE) return facadeResult;
        return callStatic(owner, method, args);
    }

    function forgeGet(owner, field) {
        if (owner === "net.minecraftforge.fml.common.registry.ForgeRegistries") {
            const names = memberNames(field);
            if (names.includes("ITEMS") || names.includes("BLOCKS")) {
                const nativeOwner = names.includes("ITEMS")
                    ? "net.minecraft.item.Item" : "net.minecraft.block.Block";
                // ForgeRegistries entries are iterable IForgeRegistry views.
                // Eaglercraft exposes the corresponding native registry, whose
                // $iterator/$getNameForObject members provide the real data.
                return getStaticField(nativeOwner, "REGISTRY");
            }
        }
        const facade = FACADES.get(owner);
        if (facade?.staticFields && Object.prototype.hasOwnProperty.call(facade.staticFields, field)) {
            return facade.staticFields[field];
        }
        return getStaticField(owner, field);
    }

    function forgeSet(owner, field, value) {
        const facade = FACADES.get(owner);
        if (facade?.staticFields && Object.prototype.hasOwnProperty.call(facade.staticFields, field)) {
            fail("immutable-static-field", owner + "." + field);
        }
        return setStaticField(owner, field, value);
    }

    function unsupportedService(service) {
        return Object.freeze({
            call(operation, ...args) {
                fail("unsupported-service", service + "." + operation
                    + " is not implemented by the EaglerForge bridge"
                    + (args.length ? " (args: " + args.length + ")" : ""));
            }
        });
    }

    let autoStartTimer = null;
    let autoStartDeadline = 0;

    function attemptAutoStart() {
        autoStartTimer = null;
        if (global.goldClientForgeAutoStart === false) return;
        const forge = global.GoldClientForge;
        const modApi = global.ModAPI;
        // postinit initializes ModAPI.mcinstance to {} before Minecraft exists.
        // Wait for the real startGame hook, which installs $gameSettings.
        const clientReady = Boolean(modApi?.settings && modApi?.mcinstance?.$gameSettings);
        if (!forge || typeof forge.start !== "function" || !clientReady) {
            if (Date.now() < autoStartDeadline) {
                autoStartTimer = global.setTimeout(attemptAutoStart, 250);
            }
            return;
        }
        if (typeof forge.status === "function" && forge.status()?.started) return;
        try {
            const started = forge.start(bindings);
            Promise.resolve(started).catch(error => {
                global.goldClientForgeStartError = error;
                console.error("[GoldForge] automatic start failed", error);
            });
        } catch (error) {
            global.goldClientForgeStartError = error;
            console.error("[GoldForge] automatic start failed", error);
        }
    }

    function startWhenReady() {
        if (autoStartTimer !== null) return;
        autoStartDeadline = Date.now() + 120000;
        autoStartTimer = global.setTimeout(attemptAutoStart, 0);
    }

    const bindings = Object.freeze({
        apiVersion: VERSION,
        startWhenReady,
        source: "eaglerforge",
        memberMapSize: Object.keys(MEMBER_MAP).length,
        setMemberMap(map) {
            if (!map || typeof map !== "object") fail("member-map", "expected an object");
            for (const [srg, mcp] of Object.entries(map)) {
                MEMBER_MAP[srg] = mcp;
            }
            memberForward = null;
            memberReverse = null;
            return Object.keys(MEMBER_MAP).length;
        },
        events: Object.freeze({ createLifecycleEvent }),
        minecraft: Object.freeze({
            call: (owner, method, args) => callStatic(owner, method, args),
            construct: (owner, args, receiver = null) => constructNative(owner, args, receiver),
            get: (owner, field) => getStaticField(owner, field),
            set: (owner, field, value) => setStaticField(owner, field, value)
        }),
        adapt: value => (isProxy(value)
            || (value !== null && typeof value === "object"
                && value.constructor?.__goldClassName)
            ? value : adaptNative(value)),
        instanceOf: (value, className) => nativeInstanceOf(value, className),
        forge: Object.freeze({
            call: (owner, method, args) => forgeCall(owner, method, args),
            construct: (owner, args, receiver = null) => forgeConstruct(owner, args, receiver),
            get: (owner, field) => forgeGet(owner, field),
            set: (owner, field, value) => forgeSet(owner, field, value)
        }),
        render: unsupportedService("render"),
        input: unsupportedService("input"),
        resources: unsupportedService("resources"),
        network: unsupportedService("network"),
        config: unsupportedService("config"),
        registries: Object.freeze({
            register(kind, id) {
                fail("unsupported-registry", "cannot register " + kind + ":" + id
                    + " into the Eaglercraft client registry from Forge metadata");
            },
            lookup(kind, id) {
                fail("unsupported-registry", "registry lookup is not mapped for " + kind + ":" + id);
            },
            entries() {
                fail("unsupported-registry", "registry enumeration is not mapped");
            }
        }),
        logger: Object.freeze({
            debug: (modId, ...values) => console.debug("[GoldForge/" + modId + "]", ...values),
            info: (modId, ...values) => console.info("[GoldForge/" + modId + "]", ...values),
            warn: (modId, ...values) => console.warn("[GoldForge/" + modId + "]", ...values),
            error: (modId, ...values) => console.error("[GoldForge/" + modId + "]", ...values)
        }),
        side: Object.freeze({
            current: () => (api().isServer ? "SERVER" : "CLIENT"),
            isClient: () => !api().isServer,
            isServer: () => Boolean(api().isServer),
            object: () => sideObject()
        }),
        configurationClass: GoldClientConfiguration,
        virtualFileClass: GoldClientVirtualFile,
        eventClass: GoldClientForgeEvent
    });

    function installBindingObject() {
        const define = (target, name, value) => {
            if (!target) return;
            Object.defineProperty(target, name, {
                value, configurable: true, writable: false
            });
        };
        define(global, "goldClientForgeBindings", bindings);
        if (global.window && global.window !== global) {
            define(global.window, "goldClientForgeBindings", bindings);
        }
        define(global, "goldClientInstallEaglerForgeBindings",
            options => bindings);
    }

    installBindingObject();
    global.goldClientEaglerForgeBindingsInstalled = true;
    if (global.goldClientForgeAutoStart !== false) startWhenReady();
})(typeof globalThis === "object" ? globalThis : this);
