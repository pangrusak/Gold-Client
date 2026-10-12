"use strict";
function installNativeRuntime(host, getLiveMinecraft, profileId) {
    if (!host || typeof getLiveMinecraft !== "function" ||
        typeof profileId !== "string" || !profileId.trim()) {
        throw new TypeError("A host, original Minecraft getter, and profile ID are required");
    }
    if (host.goldClientNativeRuntime !== undefined) {
        throw new Error("Native client runtime binding is already installed");
    }
    const api = Object.freeze({
        apiVersion: 1,
        profileId,
        capabilities: Object.freeze({
            originalMinecraftReference: true,
            javaMinecraftTypeBinding: false,
            forgeLoaderContext: false,
            forgeEventDelivery: false
        }),
        getMinecraft() {
            const client = getLiveMinecraft();
            if (!client || (typeof client !== "object" && typeof client !== "function")) {
                throw new Error("Original client Minecraft instance is not available");
            }
            return client;
        },
        isCurrentMinecraft(reference) {
            return reference === api.getMinecraft();
        }
    });
    Object.defineProperty(host, "goldClientNativeRuntime", {
        value: api, enumerable: true, writable: false, configurable: false
    });
    return api;
}
module.exports = { installNativeRuntime };
