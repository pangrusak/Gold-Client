"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { build } = require("./build-forge-client.cjs");

const FIXTURE = `<!DOCTYPE html>
<html><head><title>Eaglercraft 1.12.2</title></head>
<body style="margin:0" id="game_frame">
<script>
function nmc_Minecraft_startGame($this) { return 0; }
function $rt_metadata(data) { return data; }
var main;
(function() {
    main();
});
</script>
</body></html>
`;

function tempDirectory() {
    return fs.mkdtempSync(path.join(os.tmpdir(), "gold-forge-client-"));
}

test("builds an isolated EaglerForge + adapter candidate without touching the input", async () => {
    const root = tempDirectory();
    const input = path.join(root, "input.html");
    fs.writeFileSync(input, FIXTURE);
    const before = fs.readFileSync(input, "utf8");
    const output = path.join(root, "candidate");

    const result = await build(input, output);

    assert.equal(fs.readFileSync(input, "utf8"), before);
    assert.equal(result.originalInputModified, false);
    assert.equal(result.browserExecutionVerified, false);
    assert.equal(result.modExecutionVerified, false);
    assert.equal(result.memberMap.entries > 19000, true);
    assert.equal(result.markers.eaglerforge, true);
    assert.equal(result.markers.memberMap, true);
    assert.equal(result.markers.adapter, true);
    assert.equal(result.bundle.injected, false);

    const html = fs.readFileSync(path.join(output, "eaglercraft-1.12.2-forge.html"), "utf8");
    assert.match(html, /__eaglerforgeinjector_installation_flag__/);
    assert.match(html, /id="gold-client-member-map"/);
    assert.match(html, /id="gold-client-eaglerforge-bindings"/);
    assert.equal(fs.existsSync(path.join(output, "gold-client-member-map.js")), true);
    assert.equal(fs.existsSync(path.join(output, "gold-client-eaglerforge-bindings.js")), true);
    assert.equal(fs.existsSync(path.join(output, "forge-client-provenance.json")), true);
    assert.equal(html.lastIndexOf("gold-client-eaglerforge-bindings") < html.lastIndexOf("</body>"), true);
});

test("refuses to overwrite a non-empty output directory", async () => {
    const root = tempDirectory();
    const input = path.join(root, "input.html");
    fs.writeFileSync(input, FIXTURE);
    const output = path.join(root, "candidate");
    fs.mkdirSync(output);
    fs.writeFileSync(path.join(output, "keep.txt"), "keep");
    await assert.rejects(() => build(input, output), /not empty/);
});

test("injects a supplied single-file bundle after the adapter", async () => {
    const root = tempDirectory();
    const input = path.join(root, "input.html");
    const bundle = path.join(root, "gold-client-forge.js");
    fs.writeFileSync(input, FIXTURE);
    fs.writeFileSync(bundle, "globalThis.GoldClientForge={start(){return [];}};\n");
    const output = path.join(root, "candidate");
    const result = await build(input, output, { bundle });
    assert.equal(result.bundle.injected, true);
    const html = fs.readFileSync(path.join(output, "eaglercraft-1.12.2-forge.html"), "utf8");
    const adapterAt = html.lastIndexOf("gold-client-eaglerforge-bindings");
    const bundleAt = html.lastIndexOf("gold-client-forge-bundle");
    assert.equal(adapterAt >= 0 && bundleAt > adapterAt, true);
    assert.equal(fs.existsSync(path.join(output, "gold-client-forge.js")), true);
});
