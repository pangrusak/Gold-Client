"use strict";

function installClientBridge(host, adapter, profileId) {
    if (!host || !adapter) {
        throw new TypeError("A host and client adapter are required");
    }

    if (typeof profileId !== "string" || profileId.trim() === "") {
        throw new TypeError("A client profile identifier is required");
    }

    const requiredMethods = [
        "getLocale",
        "getRegisteredItemTypes",
        "getTooltipData"
    ];

    for (const method of requiredMethods) {
        if (typeof adapter[method] !== "function") {
            throw new TypeError(`Client adapter is missing ${method}()`);
        }
    }

    if (host.goldClientBridge !== undefined) {
        throw new Error("A Gold Client bridge is already installed");
    }

    const bridge = Object.freeze({
        apiVersion: 1,
        profileId,

        capabilities: Object.freeze({
            locale: true,
            registeredItemTypes: true,
            tooltips: true,
            creativeVariants: false,
            recipes: false,
            rendering: false,
            inputEvents: false
        }),

        getLocale() {
            const locale = adapter.getLocale();

            if (typeof locale !== "string" || locale.trim() === "") {
                throw new Error("The client adapter returned an invalid locale");
            }

            return locale;
        },

        getRegisteredItemTypes() {
            const items = adapter.getRegisteredItemTypes();

            if (!Array.isArray(items)) {
                throw new Error("The client adapter must return an item array");
            }

            const identifiers = new Set();

            return items.map(item => {
                if (!item || typeof item.id !== "string" ||
                    item.id.trim() === "" ||
                    typeof item.displayName !== "string" ||
                    item.displayName.trim() === "" ||
                    typeof item.normalTooltipData !== "string" ||
                    typeof item.advancedTooltipData !== "string") {
                    throw new Error("The client adapter returned an invalid item");
                }

                if (identifiers.has(item.id)) {
                    throw new Error(`Duplicate client item identifier: ${item.id}`);
                }

                identifiers.add(item.id);

                return Object.freeze({
                    id: item.id,
                    displayName: item.displayName,
                    normalTooltipData: item.normalTooltipData,
                    advancedTooltipData: item.advancedTooltipData
                });
            });
        },

        getTooltipData(id, advanced) {
            if (typeof id !== "string" || id.trim() === "") {
                throw new TypeError("An item identifier is required");
            }

            if (typeof advanced !== "boolean") {
                throw new TypeError("The advanced flag must be boolean");
            }

            const data = adapter.getTooltipData(id, advanced);

            if (typeof data !== "string") {
                throw new Error("The client adapter must return encoded tooltip data");
            }

            return data;
        }
    });

    Object.defineProperty(host, "goldClientBridge", {
        value: bridge,
        enumerable: true,
        writable: false,
        configurable: false
    });

    return bridge;
}

module.exports = { installClientBridge };