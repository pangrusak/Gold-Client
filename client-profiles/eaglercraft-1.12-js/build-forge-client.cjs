#!/usr/bin/env node
"use strict";

// Builds an isolated Eaglercraft 1.12.2 client candidate with:
//   1. EaglerForge ModAPI injected
//   2. the SRG -> MCP member table
//   3. the Gold Client EaglerForge binding adapter
//   4. optionally, a generated Gold Client Forge bundle
//
// The input client is never modified. This command proves packaging and
// injection only; it does not prove that the game or translated mods run in a
// browser.
const fs = require("node:fs");
const fsp = require("node:fs/promises");
const path = require("node:path");
const zlib = require("node:zlib");
const crypto = require("node:crypto");
const EFI = require("../../EaglerForgeInjector/core/core");

const MEMBER_MAP_GZ = path.join(__dirname, "..", "shared", "mcp-1.12.2-members.json.gz");
const ADAPTER = path.join(__dirname, "..", "shared", "eaglerforge-bindings.js");

function sha256(value) {
    return crypto.createHash("sha256").update(value).digest("hex");
}

function insertBeforeBody(source, html, label) {
    // Eaglercraft embeds HTML strings (including </body>) inside the generated
    // JavaScript. The real document closing tag is the last occurrence.
    const endings = [...source.matchAll(/<\/body\s*>/gi)];
    if (!endings.length) {
        throw new Error("No </body> tag found for " + label);
    }
    const position = endings[endings.length - 1].index;
    return source.slice(0, position) + html + source.slice(position);
}

function parseArgs(argv) {
    const options = { bundle: null, verbose: false };
    const positional = [];
    for (let i = 0; i < argv.length; i++) {
        const arg = argv[i];
        if (arg === "--bundle") options.bundle = argv[++i];
        else if (arg === "--verbose") options.verbose = true;
        else if (arg.startsWith("--")) throw new Error("Unknown option: " + arg);
        else positional.push(arg);
    }
    if (positional.length !== 2) {
        throw new Error("Usage: node build-forge-client.cjs <client.html> <new-output-dir> [--bundle gold-client-forge.js] [--verbose]");
    }
    return { input: positional[0], outputDirectory: positional[1], ...options };
}

async function build(inputPath, outputDirectory, options = {}) {
    const input = path.resolve(inputPath);
    const output = path.resolve(outputDirectory);
    if (!fs.existsSync(input) || !fs.statSync(input).isFile()) {
        throw new Error("Client HTML does not exist: " + input);
    }
    if (fs.existsSync(output) && fs.readdirSync(output).length) {
        throw new Error("Output directory is not empty; choose a new directory: " + output);
    }
    let bundle = null;
    if (options.bundle) {
        bundle = path.resolve(options.bundle);
        if (!fs.existsSync(bundle) || !fs.statSync(bundle).isFile()) {
            throw new Error("Gold Client bundle does not exist: " + bundle);
        }
    }

    const source = await fsp.readFile(input, "utf8");
    if (source.includes("__eaglerforgeinjector_installation_flag__")) {
        throw new Error("Input client already contains EaglerForge");
    }
    if (!source.includes("function nmc_Minecraft_startGame(")) {
        throw new Error("Input client is not the supported unminified Eaglercraft 1.12.2 build");
    }

    EFI.conf.verbose = options.verbose === true;
    let candidate = await EFI.patchClient(source);
    if (!candidate.includes("__eaglerforgeinjector_installation_flag__")) {
        throw new Error("EaglerForge injection did not produce its installation marker");
    }

    const compressed = await fsp.readFile(MEMBER_MAP_GZ);
    const memberMap = JSON.parse(zlib.gunzipSync(compressed).toString("utf8"));
    const memberMapSource = "globalThis.goldClientForgeMemberMap=Object.freeze("
        + JSON.stringify(memberMap) + ");\n";
    const adapterSource = await fsp.readFile(ADAPTER, "utf8");

    candidate = insertBeforeBody(candidate,
        '    <script id="gold-client-member-map" src="./gold-client-member-map.js"></script>\n'
        + '    <script id="gold-client-eaglerforge-bindings" src="./gold-client-eaglerforge-bindings.js"></script>\n',
        "Gold Client adapter scripts");

    let bundleSource = null;
    if (bundle) {
        bundleSource = await fsp.readFile(bundle, "utf8");
        candidate = insertBeforeBody(candidate,
            '    <script id="gold-client-forge-bundle" src="./'
            + path.basename(bundle).replace(/[^A-Za-z0-9._-]/g, "_") + '"></script>\n',
            "Gold Client bundle");
    }

    const outputHtml = path.join(output, "eaglercraft-1.12.2-forge.html");
    await fsp.mkdir(output, { recursive: true });
    await fsp.writeFile(path.join(output, "gold-client-member-map.js"), memberMapSource, "utf8");
    await fsp.writeFile(path.join(output, "gold-client-eaglerforge-bindings.js"), adapterSource, "utf8");
    if (bundle && bundleSource !== null) {
        await fsp.writeFile(path.join(output, path.basename(bundle)), bundleSource);
    }
    await fsp.writeFile(outputHtml, candidate, "utf8");

    const provenance = {
        formatVersion: "gold-eaglerforge-client-v1",
        input: {
            path: input,
            bytes: Buffer.byteLength(source),
            sha256: sha256(source)
        },
        output: {
            path: outputHtml,
            bytes: Buffer.byteLength(candidate),
            sha256: sha256(candidate)
        },
        eaglerforge: {
            version: EFI.conf.ModAPIVersion,
            installationMarker: "__eaglerforgeinjector_installation_flag__",
            modApiInstalledAtRuntime: true
        },
        adapter: {
            path: "gold-client-eaglerforge-bindings.js",
            sha256: sha256(adapterSource),
            installedGlobal: "goldClientForgeBindings"
        },
        memberMap: {
            path: "gold-client-member-map.js",
            sha256: sha256(memberMapSource),
            entries: Object.keys(memberMap).length,
            sourceSha256: sha256(compressed)
        },
        bundle: bundle ? {
            path: path.basename(bundle),
            sha256: sha256(bundleSource),
            injected: true
        } : { injected: false },
        markers: {
            eaglerforge: candidate.includes("__eaglerforgeinjector_installation_flag__"),
            memberMap: candidate.includes('id="gold-client-member-map"'),
            adapter: candidate.includes('id="gold-client-eaglerforge-bindings"'),
            bundle: bundle ? candidate.includes('id="gold-client-forge-bundle"') : null
        },
        originalInputModified: false,
        browserExecutionVerified: false,
        modExecutionVerified: false,
        notes: [
            "Packaging evidence only.",
            "The bundle still requires GoldClientForge.start() after the client has started.",
            "Unsupported Minecraft/Forge services throw explicit binding errors."
        ]
    };
    await fsp.writeFile(path.join(output, "forge-client-provenance.json"),
        JSON.stringify(provenance, null, 2) + "\n", "utf8");

    if ((await fsp.readFile(input, "utf8")) !== source) {
        throw new Error("Input client changed during the build");
    }
    return provenance;
}

if (require.main === module) {
    (async () => {
        try {
            const options = parseArgs(process.argv.slice(2));
            const result = await build(options.input, options.outputDirectory, options);
            console.log(JSON.stringify({
                output: result.output.path,
                outputSha256: result.output.sha256,
                bytes: result.output.bytes,
                memberMapEntries: result.memberMap.entries,
                bundleInjected: result.bundle.injected,
                browserExecutionVerified: false
            }, null, 2));
        } catch (error) {
            console.error(error.stack || String(error));
            process.exitCode = 1;
        }
    })();
}

module.exports = { build, sha256 };
