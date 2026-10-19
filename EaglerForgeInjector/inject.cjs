#!/usr/bin/env node
"use strict";

// Minimal CLI for the vendored EaglerForgeInjector client patch path.
// It intentionally has no HTML upload UI, minifier, or dedicated-server mode.
const fsp = require("node:fs/promises");
const path = require("node:path");
const crypto = require("node:crypto");
const EFI = require("./core/core");

function sha256(value) {
    return crypto.createHash("sha256").update(value).digest("hex");
}

async function injectClient(inputPath, outputPath, options = {}) {
    const input = path.resolve(inputPath);
    const output = outputPath ? path.resolve(outputPath) : null;
    if (output && output === input) {
        throw new Error("Refusing to overwrite the input client");
    }
    const source = await fsp.readFile(input, "utf8");
    EFI.conf.verbose = options.verbose === true;
    const patched = await EFI.patchClient(source);
    if (typeof patched !== "string" || !patched.includes("__eaglerforgeinjector_installation_flag__")) {
        throw new Error("EaglerForge injection did not produce the installation marker");
    }
    if (patched === source) {
        throw new Error("EaglerForge injection produced no changes");
    }
    if (output) {
        await fsp.mkdir(path.dirname(output), { recursive: true });
        await fsp.writeFile(output, patched, "utf8");
    }
    return Object.freeze({
        input,
        output,
        inputBytes: Buffer.byteLength(source),
        outputBytes: Buffer.byteLength(patched),
        inputSha256: sha256(source),
        outputSha256: sha256(patched),
        modApiVersion: EFI.conf.ModAPIVersion
    });
}

async function main() {
    const args = process.argv.slice(2);
    const verbose = args.includes("--verbose");
    const positional = args.filter(arg => !arg.startsWith("--"));
    if (!positional[0] || positional.length > 2) {
        console.error("Usage: node inject.cjs <unminified-1.12.2.html> [output.html] [--verbose]");
        process.exitCode = 2;
        return;
    }
    try {
        const result = await injectClient(positional[0], positional[1], { verbose });
        console.log(JSON.stringify(result, null, 2));
    } catch (error) {
        console.error(error.stack || String(error));
        process.exitCode = 1;
    }
}

if (require.main === module) main();
module.exports = { injectClient, sha256 };
