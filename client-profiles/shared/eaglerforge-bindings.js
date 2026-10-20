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

    function nativeProxy(target) {
        const existing = nativeProxies.get(target);
        if (existing) return existing;
        const proxy = new Proxy(target, {
            get(raw, property) {
                if (property === "isModProxy") return true;
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
                if (property.startsWith("$")) {
                    const direct = Reflect.get(raw, property, raw);
                    return typeof direct === "function"
                        ? (...args) => adaptNative(Reflect.apply(direct, raw, args.map(unwrapArgument)))
                        : adaptNative(direct);
                }
                const names = memberNames(property);
                for (const name of names) {
                    const key = "$" + name;
                    if (key in raw) {
                        const value = Reflect.get(raw, key, raw);
                        return typeof value === "function"
                            ? (...args) => adaptNative(Reflect.apply(value, raw, args.map(unwrapArgument)))
                            : adaptNative(value);
                    }
                }
                for (const name of names) {
                    if (name in raw) {
                        return adaptNative(Reflect.get(raw, name, raw));
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
                            ? (...args) => adaptNative(Reflect.apply(value, raw, args.map(unwrapArgument)))
                            : adaptNative(value);
                    }
                }
                fail("missing-native-member", (classNameOf(raw) || "<unknown>") + "." + property
                    + "; mapped candidates: " + names.join(", "));
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

    function unwrapArgument(value) {
        if (isProxy(value)) return value.getRef();
        if (value === null || value === undefined) return value;
        if (typeof value === "string") {
            const util = api().util;
            if (typeof util.str !== "function") fail("string-adapter", "ModAPI.util.str is unavailable");
            return util.str(value);
        }
        if (typeof value === "boolean") return value ? 1 : 0;
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

    function constructNative(owner, args, receiver = null) {
        const entry = reflection(owner);
        const encoded = Array.from(args, unwrapArgument);
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
        return receiver;
    }

    function describe(error) {
        return error && error.message ? error.message : String(error);
    }


    function getStaticField(owner, field) {
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
        getCategory(name) {
            const category = this.category(name);
            return Object.freeze({
                getName: () => category.name,
                setComment: comment => { category.comment = String(comment); },
                getComment: () => category.comment,
                setLanguageKey: key => { category.languageKey = String(key); },
                getLanguageKey: () => category.languageKey ?? "config." + category.name,
                getOrderedConfigElements: () => Object.keys(category.values)
                    .sort()
                    .map(key => this.get(category.name, key, category.values[key]))
            });
        }
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
                isDefault() {
                    return JSON.stringify(category.values[name]) === JSON.stringify(defaultValue);
                },
                getDefault() { return defaultValue; },
                getName() { return name; },
                getQualifiedName() { return category.name + "." + name; },
                getLanguageKey() { return "config." + category.name + "." + name; },
                setLanguageKey(languageKey) { property.languageKey = String(languageKey); },
                getType() { return type; }
            };
            return property;
        }
        getInt(category, key, defaultValue, description) {
            return this.get(category, key, defaultValue, description).getInt(defaultValue);
        }
        getBoolean(category, key, defaultValue, description) {
            return this.get(category, key, defaultValue, description).getBoolean(defaultValue);
        }
        getString(category, key, defaultValue, description) {
            return this.get(category, key, defaultValue, description).getString(defaultValue);
        }
        getFloat(category, key, defaultValue, description) {
            return this.get(category, key, defaultValue, description).getFloat(defaultValue);
        }
        getStringList(category, key, defaultValue, description) {
            return this.get(category, key, defaultValue, description).getStringList(defaultValue);
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
        event.getAsmData = () => {
            fail("unsupported-asm-data", "FMLPreInitializationEvent.getAsmData is not available through EaglerForge");
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

    const NETWORK_REGISTRY = Object.freeze({
        newSimpleChannel(...args) { return simpleNetworkChannel(args[0]); },
        registerGuiHandler: () => fail("unsupported-gui",
            "NetworkRegistry.registerGuiHandler has no Eaglercraft client route")
    });

    const FACADES = new Map([
        ["net.minecraftforge.fml.common.network.NetworkRegistry", {
            staticFields: { INSTANCE: NETWORK_REGISTRY }
        }],
        ["net.minecraftforge.fml.common.FMLCommonHandler", {
            staticMethods: { instance: () => FML_COMMON_HANDLER }
        }],
        ["net.minecraftforge.fml.common.Loader", {
            staticMethods: {
                isModLoaded: args => global.goldClientForgeModIds?.has(String(args[0])) ? 1 : 0,
                isModActive: () => fail("unsupported-operation", "Loader.isModActive"),
                getModList: () => fail("unsupported-operation", "Loader.getModList")
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
