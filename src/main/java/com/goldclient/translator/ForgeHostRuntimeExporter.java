package com.goldclient.translator;

import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Writes a strict host-side compatibility boundary for Forge 1.12.2 mods.
 *
 * This is intentionally an adapter framework, not a fake Minecraft implementation:
 * absent target-side services throw a diagnostic instead of returning success-shaped
 * placeholders. A browser/Eagler host must bind its actual engine operations.
 */
public final class ForgeHostRuntimeExporter {
  public record ServiceContract(String name, List<String> requiredBindings,
      List<String> exposedOperations, String purpose) {}
  public record HostContract(String formatVersion, String runtimeFile, String lifecycleFile,
      boolean strictMissingBindingErrors, List<ServiceContract> services,
      List<String> unsupportedWithoutBindings) {}

  public HostContract export(Path outputDirectory) throws IOException {
    if (outputDirectory == null) throw new IllegalArgumentException("Output directory must not be null.");
    Path root = outputDirectory.toAbsolutePath().normalize();
    Files.createDirectories(root);
    HostContract contract = contract();
    Files.writeString(root.resolve("forge-host-contract.json"),
        new GsonBuilder().setPrettyPrinting().create().toJson(contract) + System.lineSeparator(),
        StandardCharsets.UTF_8);
    Files.writeString(root.resolve("forge-host-runtime.js"), runtimeSource(), StandardCharsets.UTF_8);
    Files.writeString(root.resolve("eaglercraft-forge-entry.js"), entrypointSource(), StandardCharsets.UTF_8);
    return contract;
  }

  public HostContract contract() {
    return new HostContract("gold-forge-host-v1", "forge-host-runtime.js", "forge-lifecycle.js",
        true, List.of(
            new ServiceContract("events", List.of("bindings.events.createLifecycleEvent"),
                List.of("subscribe", "unsubscribe", "post", "clearOwner"),
                "Ordered event bus with cancellation and owner-scoped cleanup."),
            new ServiceContract("minecraft", List.of("bindings.minecraft"),
                List.of("call", "construct", "get", "set"),
                "Real Minecraft client operations such as world, player, screen and item access."),
            new ServiceContract("forge", List.of("bindings.forge"),
                List.of("call", "construct", "get", "set"),
                "Forge services including registries, network, config, capabilities and lifecycle."),
            new ServiceContract("render", List.of("bindings.render"),
                List.of("call"),
                "Rendering and GUI drawing forwarded to the actual Eagler renderer."),
            new ServiceContract("input", List.of("bindings.input"),
                List.of("call"),
                "Keyboard, mouse and keybinding operations."),
            new ServiceContract("resources", List.of("bindings.resources"),
                List.of("call"),
                "Resource-manager and mod asset lookup."),
            new ServiceContract("network", List.of("bindings.network"),
                List.of("call"),
                "Packet registration and transport; target implementation must enforce side rules."),
            new ServiceContract("config", List.of("bindings.config"),
                List.of("call"),
                "Persistent mod configuration storage and change notifications."),
            new ServiceContract("registries", List.of("bindings.registries"),
                List.of("register", "lookup", "entries"),
                "Target registry bridge; registration is rejected when no real registry binding exists."),
            new ServiceContract("logger", List.of("bindings.logger"),
                List.of("debug", "info", "warn", "error"),
                "Structured per-mod logging."),
            new ServiceContract("side", List.of("bindings.side.current"),
                List.of("current", "isClient", "isServer"),
                "Explicit logical-side checks.")
        ), List.of(
            "Minecraft classes and object behavior not present in translated code",
            "Forge event classes and event constructors",
            "real game renderer, textures, input, world and player implementations",
            "network packet codecs and server-side behavior",
            "registry identity, item/block semantics and lifecycle ordering beyond discovered handlers",
            "mixins, coremods, ASM transformers and access-transformer effects",
            "native methods and unsupported bytecode operations"
        ));
  }

  private static String entrypointSource() {
    return """
        // Browser entrypoint for a translated Forge mod bundle.
        // The Eaglercraft-specific binding object must be installed by the real client adapter.
        import { startForgeModsWithBindings, forgeMods, forgeLifecycleBlockers }
          from "./forge-lifecycle.js";
        import { assertForgeHostBindings, describeForgeHost }
          from "./forge-host-runtime.js";

        export async function startGoldClientForgeMods(bindings = globalThis.window?.goldClientForgeBindings) {
          if (!bindings) {
            throw new Error("Gold Client: window.goldClientForgeBindings is missing. Install a real Eaglercraft/Forge adapter first.");
          }
          if (forgeLifecycleBlockers.length) {
            throw new Error("Gold Client lifecycle blockers: " + forgeLifecycleBlockers.join("; "));
          }
          const validation = assertForgeHostBindings(bindings);
          const started = await startForgeModsWithBindings(bindings);
          return Object.freeze({
            started: Object.freeze(started),
            modCount: forgeMods.length,
            bindingValidation: validation,
            host: describeForgeHost()
          });
        }

        export function installGoldClientForgeEntrypoint(target = globalThis.window) {
          if (!target || (typeof target !== "object" && typeof target !== "function")) {
            throw new TypeError("Gold Client requires a browser window object");
          }
          const start = bindings => startGoldClientForgeMods(
            bindings ?? target.goldClientForgeBindings
          );
          Object.defineProperty(target, "goldClientStartForgeMods", {
            value: start, configurable: true, writable: false
          });
          Object.defineProperty(target, "goldClientForgeEntrypoint", {
            value: Object.freeze({
              formatVersion: "gold-eaglercraft-entry-v1",
              start,
              getStatus: () => ({
                modCount: forgeMods.length,
                blockers: forgeLifecycleBlockers.slice(),
                bindingsInstalled: !!target.goldClientForgeBindings
              })
            }),
            configurable: true,
            writable: false
          });
          return target.goldClientForgeEntrypoint;
        }

        if (typeof globalThis.window !== "undefined") {
          installGoldClientForgeEntrypoint(globalThis.window);
        }
        """;
  }

  private static String runtimeSource() {
    return """
        // Gold Client Forge host runtime contract.
        // Missing bindings are errors by design: this layer never fabricates a working game object.
        const REQUIRED = Object.freeze([
          "events", "minecraft", "forge", "render", "input",
          "resources", "network", "config", "registries", "logger", "side"
        ]);

        export class ForgeHostBindingError extends Error {
          constructor(service, operation, detail) {
            super("Gold Client Forge host binding missing: " + service + "." + operation
              + (detail ? " (" + detail + ")" : ""));
            this.name = "ForgeHostBindingError";
            this.service = service;
            this.operation = operation;
            this.detail = detail ?? null;
          }
        }

        export class ForgeLifecycleStateError extends Error {
          constructor(modId, from, to) {
            super("Invalid lifecycle transition for " + modId + ": " + from + " -> " + to);
            this.name = "ForgeLifecycleStateError";
          }
        }

        const own = (object, key) => Object.prototype.hasOwnProperty.call(object, key);
        const asError = value => value instanceof Error ? value : new Error(String(value));
        const validOwner = owner => typeof owner === "string" && owner.length > 0;

        function requireFunction(object, service, operation, path) {
          if (!object || typeof object[operation] !== "function") {
            throw new ForgeHostBindingError(service, operation, path);
          }
          return object[operation].bind(object);
        }

        function requireObject(object, service) {
          if (!object || typeof object !== "object") {
            throw new ForgeHostBindingError(service, "<service>", "bind a real target implementation");
          }
          return object;
        }

        function invokeBinding(bindings, service, operation, args) {
          const target = requireObject(bindings[service], service);
          return requireFunction(target, service, operation, "target adapter")( ...args );
        }

        export class ForgeEventBus {
          #listeners = new Map();
          #sequence = 0;
          #dispatchDepth = 0;
          #pendingRemovals = new Set();

          subscribe(eventType, callback, options = {}) {
            if (typeof eventType !== "string" || !eventType) throw new TypeError("eventType is required");
            if (typeof callback !== "function") throw new TypeError("event callback must be a function");
            const owner = options.owner ?? "anonymous";
            const priority = Number.isFinite(options.priority) ? options.priority : 0;
            const record = Object.freeze({
              id: ++this.#sequence, eventType, callback, owner, priority,
              receiveCanceled: options.receiveCanceled === true,
              once: options.once === true
            });
            const list = this.#listeners.get(eventType) ?? [];
            list.push(record);
            list.sort((a, b) => b.priority - a.priority || a.id - b.id);
            this.#listeners.set(eventType, list);
            return Object.freeze({
              id: record.id,
              unsubscribe: () => this.unsubscribe(record.id)
            });
          }

          unsubscribe(id) {
            if (this.#dispatchDepth) {
              this.#pendingRemovals.add(id);
              return true;
            }
            let removed = false;
            for (const [type, list] of this.#listeners) {
              const next = list.filter(listener => {
                if (listener.id !== id) return true;
                removed = true;
                return false;
              });
              if (next.length) this.#listeners.set(type, next);
              else this.#listeners.delete(type);
            }
            return removed;
          }

          clearOwner(owner) {
            if (!validOwner(owner)) throw new TypeError("owner must be a non-empty string");
            let count = 0;
            for (const list of this.#listeners.values()) {
              for (const listener of list) {
                if (listener.owner !== owner) continue;
                this.#pendingRemovals.add(listener.id);
                count++;
              }
            }
            if (!this.#dispatchDepth) this.#flushRemovals();
            return count;
          }

          listeners(eventType) {
            return Object.freeze([...(this.#listeners.get(eventType) ?? [])]);
          }

          post(eventType, event, options = {}) {
            const listeners = [...(this.#listeners.get(eventType) ?? [])];
            const failures = [];
            const results = [];
            this.#dispatchDepth++;
            try {
              for (const listener of listeners) {
                if (this.#pendingRemovals.has(listener.id)) continue;
                if (event?.isCanceled === true && !listener.receiveCanceled) continue;
                try {
                  results.push(listener.callback(event));
                } catch (error) {
                  failures.push({ listenerId: listener.id, owner: listener.owner, error: asError(error) });
                  if (options.stopOnError === true) throw error;
                }
                if (listener.once) this.#pendingRemovals.add(listener.id);
              }
            } finally {
              this.#dispatchDepth--;
              if (!this.#dispatchDepth) this.#flushRemovals();
            }
            if (failures.length && options.throwAggregate === true) {
              throw new AggregateError(failures.map(item => item.error),
                "One or more Forge event listeners failed for " + eventType);
            }
            return Object.freeze({ event, listenerCount: listeners.length, results, failures });
          }

          #flushRemovals() {
            if (!this.#pendingRemovals.size) return;
            const removed = this.#pendingRemovals;
            this.#pendingRemovals = new Set();
            for (const [type, list] of this.#listeners) {
              const next = list.filter(listener => !removed.has(listener.id));
              if (next.length) this.#listeners.set(type, next);
              else this.#listeners.delete(type);
            }
          }

          get size() {
            let count = 0;
            for (const list of this.#listeners.values()) count += list.length;
            return count;
          }
        }

        const TRANSITIONS = Object.freeze({
          discovered: ["constructing", "failed"],
          constructing: ["constructed", "failed"],
          constructed: ["preInit", "failed"],
          preInit: ["init", "failed"],
          init: ["postInit", "failed"],
          postInit: ["loadComplete", "failed"],
          loadComplete: ["started", "failed"],
          started: ["stopping", "failed"],
          stopping: ["stopped", "failed"],
          stopped: [],
          failed: ["stopping", "stopped"]
        });

        export class ForgeModState {
          constructor(modId, className) {
            if (!modId || !className) throw new TypeError("modId and className are required");
            this.modId = modId;
            this.className = className;
            this.state = "discovered";
            this.history = [{ state: this.state, at: Date.now() }];
            this.instance = null;
            this.error = null;
          }

          transition(next, detail = null) {
            const allowed = TRANSITIONS[this.state] ?? [];
            if (!allowed.includes(next)) {
              throw new ForgeLifecycleStateError(this.modId, this.state, next);
            }
            this.state = next;
            this.history.push({ state: next, at: Date.now(), detail });
            if (next === "failed" && detail) this.error = asError(detail);
            return this.state;
          }

          snapshot() {
            return Object.freeze({
              modId: this.modId, className: this.className, state: this.state,
              history: Object.freeze(this.history.map(item => Object.freeze({ ...item }))),
              error: this.error ? String(this.error.message) : null
            });
          }
        }

        export class ForgeModRegistry {
          #mods = new Map();
          #classes = new Map();

          register(mod) {
            if (!mod || !mod.modId || !mod.className) throw new TypeError("mod metadata is incomplete");
            if (this.#mods.has(mod.modId)) throw new Error("Duplicate mod id: " + mod.modId);
            this.#mods.set(mod.modId, new ForgeModState(mod.modId, mod.className));
            this.#classes.set(mod.className, mod.modId);
            return this.#mods.get(mod.modId);
          }

          get(modId) { return this.#mods.get(modId) ?? null; }
          byClass(className) {
            const id = this.#classes.get(className);
            return id ? this.#mods.get(id) ?? null : null;
          }
          all() { return Object.freeze([...this.#mods.values()]); }
          snapshots() { return Object.freeze(this.all().map(mod => mod.snapshot())); }
        }

        export class ForgeServiceFacade {
          constructor(bindings, serviceName) {
            this.bindings = bindings;
            this.serviceName = serviceName;
            Object.freeze(this);
          }

          call(operation, ...args) {
            if (typeof operation !== "string" || !operation) throw new TypeError("operation is required");
            return invokeBinding(this.bindings, this.serviceName, operation, args);
          }

          get(operation, ...args) {
            const target = requireObject(this.bindings[this.serviceName], this.serviceName);
            if (typeof target.get !== "function") {
              throw new ForgeHostBindingError(this.serviceName, "get", operation);
            }
            return target.get(operation, ...args);
          }

          set(operation, value, ...args) {
            const target = requireObject(this.bindings[this.serviceName], this.serviceName);
            if (typeof target.set !== "function") {
              throw new ForgeHostBindingError(this.serviceName, "set", operation);
            }
            return target.set(operation, value, ...args);
          }
        }

        export class ForgeLogger {
          constructor(binding, modId) {
            this.binding = requireObject(binding, "logger");
            this.modId = modId;
          }
          #write(level, values) {
            const writer = requireFunction(this.binding, "logger", level, "logger adapter");
            return writer(this.modId, ...values);
          }
          debug(...values) { return this.#write("debug", values); }
          info(...values) { return this.#write("info", values); }
          warn(...values) { return this.#write("warn", values); }
          error(...values) { return this.#write("error", values); }
        }

        export class ForgeRegistrationLedger {
          #entries = new Map();
          #sequence = 0;
          constructor(binding) { this.binding = requireObject(binding, "registries"); }

          register(kind, id, value, options = {}) {
            if (!kind || !id) throw new TypeError("registry kind and id are required");
            const key = kind + ":" + id;
            if (this.#entries.has(key)) throw new Error("Duplicate local registry entry: " + key);
            const register = requireFunction(this.binding, "registries", "register", "real registry bridge");
            const result = register(kind, id, value, options);
            if (result === false || result == null) {
              throw new Error("Target registry rejected " + key + "; registration was not recorded");
            }
            const record = Object.freeze({
              sequence: ++this.#sequence, kind, id, value, result,
              owner: options.owner ?? null
            });
            this.#entries.set(key, record);
            return result;
          }

          lookup(kind, id) {
            const key = kind + ":" + id;
            if (this.#entries.has(key)) return this.#entries.get(key).result;
            const lookup = requireFunction(this.binding, "registries", "lookup", "real registry bridge");
            return lookup(kind, id);
          }

          entries(kind = null) {
            const values = [...this.#entries.values()];
            return Object.freeze(values.filter(item => kind == null || item.kind === kind));
          }

          removeOwner(owner) {
            const removed = [];
            for (const [key, entry] of this.#entries) {
              if (entry.owner === owner) {
                this.#entries.delete(key);
                removed.push(key);
              }
            }
            return Object.freeze(removed);
          }
        }

        function validateBindings(bindings) {
          requireObject(bindings, "bindings");
          const missing = REQUIRED.filter(name => !bindings[name] || typeof bindings[name] !== "object");
          if (missing.length) {
            throw new ForgeHostBindingError("bindings", "services", "missing: " + missing.join(", "));
          }
          requireFunction(bindings.events, "events", "createLifecycleEvent", "construct real Forge events");
          requireFunction(bindings.side, "side", "current", "determine the actual logical side");
          return bindings;
        }

        function modMetadata(mod) {
          return Object.freeze({
            modId: mod.modId,
            name: mod.name ?? mod.modId,
            version: mod.version ?? "unknown",
            className: mod.className,
            source: "Gold Client translated mod"
          });
        }

        export function createForgeHost(bindings) {
          validateBindings(bindings);
          const eventBus = new ForgeEventBus();
          const mods = new ForgeModRegistry();
          const registrations = new ForgeRegistrationLedger(bindings.registries);
          const contexts = new Map();
          const facades = Object.freeze({
            minecraft: new ForgeServiceFacade(bindings, "minecraft"),
            forge: new ForgeServiceFacade(bindings, "forge"),
            render: new ForgeServiceFacade(bindings, "render"),
            input: new ForgeServiceFacade(bindings, "input"),
            resources: new ForgeServiceFacade(bindings, "resources"),
            network: new ForgeServiceFacade(bindings, "network"),
            config: new ForgeServiceFacade(bindings, "config")
          });

          function stateFor(mod) {
            let state = mods.get(mod.modId);
            if (!state) state = mods.register(mod);
            return state;
          }

          function contextFor(mod) {
            const key = mod.modId;
            if (!contexts.has(key)) {
              contexts.set(key, Object.freeze({
                metadata: modMetadata(mod),
                logger: new ForgeLogger(bindings.logger, mod.modId),
                services: facades,
                eventBus,
                registrations,
                side: Object.freeze({
                  current: () => bindings.side.current(),
                  isClient: () => bindings.side.current() === "CLIENT",
                  isServer: () => bindings.side.current() === "SERVER"
                }),
                subscribe: (eventType, callback, options = {}) => eventBus.subscribe(
                  eventType, callback, { ...options, owner: mod.modId }),
                register: (kind, id, value, options = {}) => registrations.register(
                  kind, id, value, { ...options, owner: mod.modId })
              }));
            }
            return contexts.get(key);
          }

          const host = {
            bindings,
            eventBus,
            mods,
            registrations,
            services: facades,

            beforeConstruction(mod) {
              const state = stateFor(mod);
              state.transition("constructing");
              bindings.logger.info(mod.modId, "Constructing translated mod", mod.className);
              return contextFor(mod);
            },

            injectFields(mod, instance, runtime) {
              const context = contextFor(mod);
              for (const field of mod.injections ?? []) {
                let value;
                if (field.kind === "instance") {
                  const target = runtime.resolveClass(mod.className);
                  if (!target) throw new Error("Mod class disappeared during @Mod.Instance injection");
                  value = instance;
                } else if (field.kind === "metadata") {
                  value = context.metadata;
                } else {
                  throw new Error("Unknown Forge injected field kind: " + field.kind);
                }
                if (field.isStatic) {
                  runtime.setStaticField(mod.className, field.name, value);
                } else {
                  instance[field.name] = value;
                }
              }
              return instance;
            },

            afterConstruction(mod, instance) {
              const state = stateFor(mod);
              state.instance = instance;
              state.transition("constructed");
              return instance;
            },

            async createEvent(mod, phase) {
              const state = stateFor(mod);
              const expected = {
                preInit: "constructed", init: "preInit", postInit: "init",
                loadComplete: "postInit"
              }[phase.name];
              if (state.state !== expected) {
                throw new ForgeLifecycleStateError(mod.modId, state.state, phase.name);
              }
              const event = await bindings.events.createLifecycleEvent(
                mod, phase, contextFor(mod), bindings.side.current());
              if (!event || typeof event !== "object") {
                throw new ForgeHostBindingError("events", "createLifecycleEvent",
                  "adapter returned no real event object for " + phase.eventType);
              }
              Object.defineProperties(event, {
                goldClientModId: { value: mod.modId, configurable: true },
                goldClientPhase: { value: phase.name, configurable: true }
              });
              return event;
            },

            async afterPhase(mod, phase, instance) {
              const state = stateFor(mod);
              const expected = {
                preInit: "constructed", init: "preInit", postInit: "init",
                loadComplete: "postInit"
              }[phase.name];
              if (state.state !== expected) {
                throw new ForgeLifecycleStateError(mod.modId, state.state, phase.name);
              }
              state.transition(phase.name);
              await bindings.forge.callLifecycleHook?.(mod, phase, instance, contextFor(mod));
              if (phase.name === "loadComplete") state.transition("started");
              return state.snapshot();
            },

            async stopMod(modId, reason = "requested") {
              const state = mods.get(modId);
              if (!state) return false;
              if (state.state === "stopped") return false;
              if (state.state !== "stopping") state.transition("stopping", reason);
              eventBus.clearOwner(modId);
              registrations.removeOwner(modId);
              await bindings.forge.stopMod(modId, state.instance, reason);
              state.transition("stopped", reason);
              return true;
            },

            postEvent(eventType, event, options) {
              return eventBus.post(eventType, event, options);
            },

            subscribe(eventType, callback, options) {
              return eventBus.subscribe(eventType, callback, options);
            },

            getModState(modId) {
              return mods.get(modId)?.snapshot() ?? null;
            },

            getModContext(modId) {
              return contexts.get(modId) ?? null;
            },

            assertSide(expected) {
              const actual = bindings.side.current();
              if (actual !== expected) {
                throw new Error("Wrong logical side: expected " + expected + ", got " + actual);
              }
              return true;
            },

            requireService(service, operation) {
              if (!facades[service]) throw new ForgeHostBindingError(service, operation, "unknown service");
              return facades[service];
            },

            diagnostics() {
              return Object.freeze({
                mods: mods.snapshots(),
                eventListenerCount: eventBus.size,
                registrations: registrations.entries().map(entry => ({
                  kind: entry.kind, id: entry.id, owner: entry.owner
                })),
                services: REQUIRED.slice(),
                bindingStatus: Object.freeze(Object.fromEntries(REQUIRED.map(name => [name, "bound"])))
              });
            }
          };
          return Object.freeze(host);
        }

        export function assertForgeHostBindings(bindings) {
          validateBindings(bindings);
          const operations = {
            events: ["createLifecycleEvent"],
            minecraft: [],
            forge: [],
            render: [],
            input: [],
            resources: [],
            network: [],
            config: [],
            registries: ["register", "lookup"],
            logger: ["info", "warn", "error"],
            side: ["current"]
          };
          const errors = [];
          for (const [service, required] of Object.entries(operations)) {
            for (const operation of required) {
              if (typeof bindings[service][operation] !== "function") {
                errors.push(service + "." + operation);
              }
            }
          }
          if (errors.length) {
            throw new ForgeHostBindingError("bindings", "validate", errors.join(", "));
          }
          return Object.freeze({
            valid: true,
            serviceCount: REQUIRED.length,
            services: REQUIRED.slice(),
            currentSide: bindings.side.current()
          });
        }

        export function createRequiredServiceProxy(bindings, service) {
          requireObject(bindings?.[service], service);
          return new Proxy(Object.create(null), {
            get(_target, operation) {
              if (operation === "then") return undefined;
              if (operation === "serviceName") return service;
              if (typeof operation !== "string") return undefined;
              return (...args) => invokeBinding(bindings, service, operation, args);
            },
            set(_target, operation, value) {
              const target = requireObject(bindings[service], service);
              if (typeof target.set !== "function") {
                throw new ForgeHostBindingError(service, "set", String(operation));
              }
              target.set(String(operation), value);
              return true;
            }
          });
        }

        export function createModApi(host, modId) {
          if (!host || !host.mods.get(modId)) throw new Error("Unknown active mod: " + modId);
          const context = host.getModContext(modId);
          if (!context) throw new Error("Mod context is not initialized: " + modId);
          return Object.freeze({
            metadata: context.metadata,
            logger: context.logger,
            side: context.side,
            events: Object.freeze({
              subscribe: context.subscribe,
              post: (type, event, options) => host.postEvent(type, event, options)
            }),
            services: context.services,
            register: context.register,
            state: () => host.getModState(modId)
          });
        }

        export function describeForgeHost() {
          return Object.freeze({
            formatVersion: "gold-forge-host-v1",
            strict: true,
            services: REQUIRED.slice(),
            lifecyclePhases: ["preInit", "init", "postInit", "loadComplete"],
            guarantee: "Adapter framework only; game behavior is provided by explicit bindings."
          });
        }
        """;
  }
}
