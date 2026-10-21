#!/usr/bin/env node
"use strict";

// Builds one self-contained Eaglercraft 1.12.2 HTML file from the pinned
// client, EaglerForge injection, the Gold Client bindings adapter, an optional
// translated Forge bundle, embedded mod resources, and provenance.
const fs = require("node:fs");
const path = require("node:path");
const zlib = require("node:zlib");
const EFI = require("./eaglerforge/core/core");

const BEGIN = "<!-- GOLD_CLIENT_BEGIN -->";
const END = "<!-- GOLD_CLIENT_END -->";

function fail(message) {
    throw new Error(message);
}

function parseArgs(argv) {
    const options = {
        client: null,
        bundle: null,
        resources: null,
        provenance: null,
        output: null,
        memberMap: path.join(__dirname, "shared", "mcp-1.12.2-members.json.gz"),
        adapter: path.join(__dirname, "shared", "eaglerforge-bindings.js"),
        verbose: false
    };
    for (let i = 0; i < argv.length; i++) {
        const arg = argv[i];
        if (arg === "--verbose") {
            options.verbose = true;
            continue;
        }
        if (!arg.startsWith("--")) fail("Unknown argument: " + arg);
        if (++i >= argv.length) fail("Missing value for " + arg);
        switch (arg) {
            case "--client": options.client = argv[i]; break;
            case "--bundle": options.bundle = argv[i]; break;
            case "--resources": options.resources = argv[i]; break;
            case "--provenance": options.provenance = argv[i]; break;
            case "--output": options.output = argv[i]; break;
            case "--member-map": options.memberMap = argv[i]; break;
            case "--adapter": options.adapter = argv[i]; break;
            default: fail("Unknown option: " + arg);
        }
    }
    if (!options.client) fail("--client is required");
    if (!options.output) fail("--output is required");
    return options;
}

function readRequired(file, label) {
    if (!file || !fs.existsSync(file) || !fs.statSync(file).isFile()) {
        fail(label + " does not exist: " + file);
    }
    return fs.readFileSync(file, "utf8");
}

function escapeScript(source) {
    return source
        .replace(/<\/script/gi, "<\\/script")
        .replace(/<!--/g, "<\\!--");
}

function scriptTag(id, source) {
    return '    <script id="' + id + '">\n'
        + escapeScript(source) + "\n    </script>\n";
}

function escapeJsonForHtml(source) {
    return source.replace(/<\//g, "<\\/");
}

function insertBeforeLastBody(source, html) {
    const endings = [...source.matchAll(/<\/body\s*>/gi)];
    if (!endings.length) fail("No </body> tag found in client");
    const position = endings[endings.length - 1].index;
    return source.slice(0, position) + html + source.slice(position);
}

function inlineResources(resourcesPath) {
    if (!resourcesPath) {
        return JSON.stringify({ formatVersion: "gold-client-resources-v1", files: {} });
    }
    const source = readRequired(resourcesPath, "Resource bundle");
    const parsed = JSON.parse(source);
    if (!parsed || typeof parsed !== "object" || !parsed.files
        || typeof parsed.files !== "object") {
        fail("Resource bundle must contain a files object");
    }
    return JSON.stringify(parsed);
}

async function build(options) {
    const client = path.resolve(options.client);
    const output = path.resolve(options.output);
    const source = readRequired(client, "Client HTML");
    if (source.includes("__eaglerforgeinjector_installation_flag__")) {
        fail("Input client already contains EaglerForge");
    }

    EFI.conf.verbose = options.verbose === true;
    let candidate = await EFI.patchClient(source);
    if (!candidate.includes("__eaglerforgeinjector_installation_flag__")) {
        fail("EaglerForge injection did not produce its installation marker");
    }

    const memberMap = JSON.parse(zlib.gunzipSync(
        fs.readFileSync(options.memberMap)).toString("utf8"));
    const memberMapSource = "globalThis.goldClientForgeMemberMap=Object.freeze("
        + JSON.stringify(memberMap) + ");";
    const adapterSource = readRequired(options.adapter, "Bindings adapter");
    const resourceSource = "globalThis.GoldClientResources=Object.freeze("
        + inlineResources(options.resources) + ");";
    const provenanceSource = options.provenance
        ? readRequired(options.provenance, "Provenance")
        : "{}\n";
    const bundleSource = options.bundle
        ? readRequired(options.bundle, "Gold Client bundle")
        : "";

    const blocks = [
        BEGIN + "\n",
        scriptTag("gold-client-member-map", memberMapSource),
        scriptTag("gold-client-resources", resourceSource),
        bundleSource ? scriptTag("gold-client-forge-bundle", bundleSource) : "",
        scriptTag("gold-client-eaglerforge-bindings", adapterSource),
        '    <script type="application/json" id="gold-client-provenance">\n'
            + escapeJsonForHtml(provenanceSource) + "\n    </script>\n",
        END + "\n"
    ];
    candidate = insertBeforeLastBody(candidate, blocks.join(""));

    if (/<script\b[^>]*\bsrc\s*=/i.test(candidate)) {
        fail("Self-contained output still contains an external script source");
    }
    const linkMatches = [...candidate.matchAll(/<link\b[^>]*\bhref\s*=\s*(?:"([^"]*)"|'([^']*)')/gi)];
    for (const match of linkMatches) {
        const href = match[1] ?? match[2] ?? "";
        if (!href.startsWith("data:") && !href.startsWith("#")) {
            fail("Self-contained output still contains an external link: " + href);
        }
    }

    fs.mkdirSync(path.dirname(output), { recursive: true });
    const temporary = output + ".tmp-" + process.pid;
    fs.writeFileSync(temporary, candidate, "utf8");
    fs.renameSync(temporary, output);
    return {
        output,
        bytes: Buffer.byteLength(candidate, "utf8"),
        memberMapEntries: Object.keys(memberMap).length,
        bundleInjected: Boolean(bundleSource),
        resourcesInjected: true
    };
}

if (require.main === module) {
    (async () => {
        try {
            const options = parseArgs(process.argv.slice(2));
            const result = await build(options);
            process.stdout.write(JSON.stringify(result) + "\n");
        } catch (error) {
            process.stderr.write((error.stack || String(error)) + "\n");
            process.exitCode = 1;
        }
    })();
}

module.exports = { build };
