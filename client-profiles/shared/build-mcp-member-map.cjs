#!/usr/bin/env node
"use strict";

// Builds the compact SRG -> MCP member table used by the EaglerForge bridge.
// Input: MCP stable 39-1.12 methods.csv and fields.csv.
const fs = require("node:fs");
const path = require("node:path");
const zlib = require("node:zlib");

function readCsv(file) {
    const text = fs.readFileSync(file, "utf8");
    const lines = text.split(/\r?\n/);
    const header = lines.shift().split(",");
    const srg = header.indexOf("searge");
    const name = header.indexOf("name");
    if (srg < 0 || name < 0) throw new Error("Unexpected MCP CSV header in " + file);
    const result = {};
    for (const line of lines) {
        if (!line) continue;
        // MCP descriptions can contain commas. The first two fields are safe to
        // split because neither searge nor name contains a comma.
        const first = line.indexOf(",");
        const second = line.indexOf(",", first + 1);
        const srgName = line.slice(0, first).trim();
        const mcpName = line.slice(first + 1, second < 0 ? undefined : second).trim();
        if (srgName && mcpName && srgName !== mcpName) result[srgName] = mcpName;
    }
    return result;
}

function build(methodsFile, fieldsFile) {
    const members = Object.assign({}, readCsv(fieldsFile), readCsv(methodsFile));
    const sorted = {};
    for (const key of Object.keys(members).sort()) sorted[key] = members[key];
    return { members: sorted, json: JSON.stringify(sorted) };
}

if (require.main === module) {
    const [methodsFile, fieldsFile, output] = process.argv.slice(2);
    if (!methodsFile || !fieldsFile || !output) {
        console.error("Usage: node build-mcp-member-map.cjs methods.csv fields.csv output.json.gz");
        process.exitCode = 2;
    } else {
        const built = build(methodsFile, fieldsFile);
        fs.writeFileSync(output, zlib.gzipSync(Buffer.from(built.json), { level: 9 }));
        console.log("wrote " + output + " (" + Object.keys(built.members).length + " members)");
    }
}

module.exports = { build, readCsv };
