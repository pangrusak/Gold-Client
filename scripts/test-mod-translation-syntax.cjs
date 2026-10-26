"use strict";

const fs = require("node:fs");
const path = require("node:path");
const { spawnSync } = require("node:child_process");

const root = process.cwd();
const modsDirectory = path.join(root, "test-mods");
const outputRoot = path.join(root, "target", "mod-translation-syntax");

function walk(directory) {
    if (!fs.existsSync(directory)) return [];
    const files = [];
    for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
        const filename = path.join(directory, entry.name);
        if (entry.isDirectory()) files.push(...walk(filename));
        else if (entry.isFile()) files.push(filename);
    }
    return files;
}

function run(command, args, options = {}) {
    return spawnSync(command, args, {
        cwd: root,
        encoding: "utf8",
        maxBuffer: 64 * 1024 * 1024,
        ...options
    });
}

if (!fs.existsSync(modsDirectory)) {
    throw new Error("Missing test-mods directory: " + modsDirectory);
}
const mods = fs.readdirSync(modsDirectory)
    .filter(name => name.toLowerCase().endsWith(".jar"))
    .sort();

if (mods.length === 0) throw new Error("No JAR fixtures found under test-mods/");

fs.rmSync(outputRoot, { recursive: true, force: true });
fs.mkdirSync(outputRoot, { recursive: true });

const failures = [];
for (const mod of mods) {
    const input = path.join(modsDirectory, mod);
    const output = path.join(outputRoot, mod.replace(/\.jar$/i, ""));
    fs.mkdirSync(output, { recursive: true });

    console.log("\n=== Translating " + mod + " ===");
    const translated = run("java", [
        "-jar", path.join(root, "target", "gold-client-1.jar"),
        input, "--translate-js", output
    ]);
    if (translated.stdout) process.stdout.write(translated.stdout);
    if (translated.stderr) process.stderr.write(translated.stderr);
    if (translated.error || translated.status !== 0) {
        failures.push(mod + ": translation process failed ("
            + (translated.error?.message ?? "exit " + translated.status) + ")");
        continue;
    }

    const javascript = walk(output).filter(file => file.endsWith(".js"));
    if (javascript.length === 0) {
        failures.push(mod + ": translation produced no JavaScript files");
        continue;
    }

    let syntaxFailures = 0;
    for (const file of javascript) {
        const checked = run(process.execPath, ["--check", file]);
        if (checked.status !== 0) {
            syntaxFailures++;
            failures.push(mod + ": invalid JavaScript in "
                + path.relative(root, file) + ": "
                + (checked.stderr || checked.stdout || "node --check failed").trim());
        }
    }
    if (syntaxFailures === 0) {
        console.log("PASS " + mod + ": " + javascript.length
            + " generated JavaScript files passed node --check.");
    } else {
        console.error("FAIL " + mod + ": " + syntaxFailures
            + " of " + javascript.length + " generated JavaScript files failed syntax checks.");
    }
}

console.log("\n=== Test-mod translation syntax summary ===");
console.log("JAR fixtures: " + mods.length);
console.log("Failures: " + failures.length);
for (const failure of failures) console.error("- " + failure);
if (failures.length > 0) process.exitCode = 1;
