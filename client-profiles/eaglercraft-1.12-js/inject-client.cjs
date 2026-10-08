'use strict';

const fs = require('node:fs');
const path = require('node:path');

const [clientPath, translatedPath, outputPath] = process.argv.slice(2);
if (!clientPath || !translatedPath || !outputPath) {
    process.stderr.write(
        'Usage: node inject-client.cjs <client.html> <jei-slice.js> <output.html>\n');
    process.exit(2);
}

const source = fs.readFileSync(clientPath, 'utf8').replace(/\r\n/g, '\n');
const expectedOptions = `        window.eaglercraftXOpts = {
            container: "game_frame",
            worldsDB: "worlds"
        };`;
const scriptMarker =
    'if(typeof window !== "undefined") window.eaglercraftXClientScriptElement = document.currentScript;';
const itemRegistryCall =
    'var$1.$screenChangedHook.call($rt_ustr(var$2), var$3, var$4, var$5, var$6, var$7);';

function requireUnique(text, marker, description) {
    const first = text.indexOf(marker);
    if (first < 0 || text.indexOf(marker, first + marker.length) >= 0) {
        throw new Error(`Expected exactly one ${description} anchor in supplied client`);
    }
    return first;
}

if (!source.includes('<title>Eaglercraft 1.12.2</title>')) {
    throw new Error('Unsupported client: expected the supplied Eaglercraft 1.12.2 profile');
}
if (!fs.existsSync(translatedPath)) {
    throw new Error(`Translated JEI slice not found: ${translatedPath}`);
}

requireUnique(source, expectedOptions, 'Eaglercraft launch options');
let output = source.replace(expectedOptions, expectedOptions.replace(
    '            worldsDB: "worlds"',
    '            worldsDB: "worlds",\n            hooks: {}'));
const markerOffset = requireUnique(output, scriptMarker, 'client runtime script');
const scriptOffset = output.lastIndexOf('<script', markerOffset);
if (scriptOffset < 0) {
    throw new Error('Could not locate Eaglercraft runtime script element');
}
requireUnique(output, itemRegistryCall, 'Eaglercraft screen-change callback');

const translatedScript =
    '    <script src="eagler-jei-suffix-tree.js"></script>\n' +
    '    <script>\n' +
    '        if (typeof window.main !== "function" || ' +
        'typeof window.goldClientJeiSearch !== "function") {\n' +
    '            throw new Error("Translated JEI suffix-tree exports are missing");\n' +
    '        }\n' +
    '        window.main([], function(error) { if (error) throw error; });\n' +
    '        window.eaglercraftXOpts.hooks = {\n' +
    '            screenChanged: function(screenName) {\n' +
    '                window.__goldClientJeiScreenChangeCount = (window.__goldClientJeiScreenChangeCount || 0) + 1;\n' +
    '                window.__goldClientLastScreen = String(screenName == null ? "" : screenName);\n' +
    '                if (!window.__goldClientJeiUiInstalled) installJeiDiagnosticUi();\n' +
    '                if (!window.__goldClientJeiIndexReady && !window.__goldClientJeiIndexing) {\n' +
    '                    window.__goldClientJeiIndexing = true;\n' +
    '                    setTimeout(buildJeiDiagnosticIndex, 0);\n' +
    '                }\n' +
    '            }\n' +
    '        };\n' +
    '        function installJeiDiagnosticUi() {\n' +
    '            var panel = document.createElement("section");\n' +
    '            panel.id = "gold-client-jei-diagnostic";\n' +
    '            panel.style.cssText = "position:fixed;right:12px;top:12px;z-index:2147483647;width:340px;max-height:70vh;overflow:auto;padding:10px;background:rgba(18,18,22,.94);color:#fff;font:13px Arial,sans-serif;border:1px solid #aaa;border-radius:4px";\n' +
    '            panel.innerHTML = "<strong>Partial JEI original-code search — diagnostic UI</strong><div id=\\"gold-jei-status\\">Waiting for client item registry...</div><input id=\\"gold-jei-query\\" type=\\"search\\" placeholder=\\"Search item ID or display name\\" style=\\"box-sizing:border-box;width:100%;margin:8px 0;padding:6px\\"><div id=\\"gold-jei-count\\"></div><ul id=\\"gold-jei-results\\" style=\\"padding-left:20px\\"></ul><button id=\\"gold-jei-export\\" type=\\"button\\">Export client corpus</button>";\n' +
    '            document.body.appendChild(panel);\n' +
    '            document.getElementById("gold-jei-query").addEventListener("input", showJeiDiagnosticResults);\n' +
    '            document.getElementById("gold-jei-export").addEventListener("click", exportJeiDiagnosticCorpus);\n' +
    '            window.__goldClientJeiUiInstalled = true;\n' +
    '            window.__goldClientJeiUiInstallCount = (window.__goldClientJeiUiInstallCount || 0) + 1;\n' +
    '        }\n' +
    '        function buildJeiDiagnosticIndex() {\n' +
    '            try {\n' +
    '                var items = window.__goldClientCaptureItems();\n' +
    '                if (!items.length) throw new Error("Client item registry returned no entries");\n' +
    '                window.goldClientJeiReset();\n' +
    '                items.forEach(function(item, index) {\n' +
    '                    window.goldClientJeiIndex(item.id + " " + item.displayName, index);\n' +
    '                });\n' +
    '                window.goldClientJeiTrim();\n' +
    '                window.__goldClientJeiItems = items;\n' +
    '                window.__goldClientJeiIndexReady = true;\n' +
    '                document.getElementById("gold-jei-status").textContent = "Indexed " + items.length + " registered item types (default stack only).";\n' +
    '                document.getElementById("gold-jei-query").disabled = false;\n' +
    '                showJeiDiagnosticResults();\n' +
    '                if (location.protocol !== "file:") {\n' +
    '                    fetch("/__gold-client-corpus", {method:"POST", headers:{"Content-Type":"application/json"}, body:JSON.stringify({profile:"supplied-eaglercraft-1.12.2-js", category:"registered item types; one default metadata-0 stack per registry item", excluded:["creative variants","item stacks from inventory","ingredients","recipes"], items:items})}).catch(function(error) { console.warn("[JEI corpus export]", error); });\n' +
    '                }\n' +
    '            } catch (error) {\n' +
    '                window.__goldClientJeiIndexing = false;\n' +
    '                document.getElementById("gold-jei-status").textContent = "Index failed: " + error;\n' +
    '                console.error("[JEI diagnostic index]", error);\n' +
    '                return;\n' +
    '            }\n' +
    '            window.__goldClientJeiIndexing = false;\n' +
    '        }\n' +
    '        function showJeiDiagnosticResults() {\n' +
    '            if (!window.__goldClientJeiIndexReady) return;\n' +
    '            var query = document.getElementById("gold-jei-query").value;\n' +
    '            window.__goldClientJeiSearchCallCount = (window.__goldClientJeiSearchCallCount || 0) + 1;\n' +
    '            var indices = JSON.parse(window.goldClientJeiSearch(query));\n' +
    '            var matches = indices.map(function(index) { return window.__goldClientJeiItems[index]; });\n' +
    '            var list = document.getElementById("gold-jei-results");\n' +
    '            list.textContent = "";\n' +
    '            matches.slice(0, 100).forEach(function(item) {\n' +
    '                var row = document.createElement("li");\n' +
    '                row.textContent = item.id + " — " + item.displayName;\n' +
    '                list.appendChild(row);\n' +
    '            });\n' +
    '            document.getElementById("gold-jei-count").textContent = matches.length + " result(s)" + (matches.length > 100 ? " (first 100 shown)" : "");\n' +
    '        }\n' +
    '        function exportJeiDiagnosticCorpus() {\n' +
    '            var data = JSON.stringify({profile:"supplied-eaglercraft-1.12.2-js", category:"registered item types; one default metadata-0 stack per registry item", excluded:["creative variants","item stacks from inventory","ingredients","recipes"], items:window.__goldClientJeiItems}, null, 2);\n' +
    '            var link = document.createElement("a");\n' +
    '            link.href = URL.createObjectURL(new Blob([data], {type:"application/json"}));\n' +
    '            link.download = "jei-real-item-corpus.json";\n' +
    '            link.click();\n' +
    '            setTimeout(function() { URL.revokeObjectURL(link.href); }, 1000);\n' +
    '        }\n' +
    '    </script>\n';

output = output.slice(0, scriptOffset) + translatedScript + output.slice(scriptOffset);
output = output.replace(itemRegistryCall,
    'if (typeof window.__goldClientCaptureItems !== "function") {\n' +
    '                window.__goldClientCaptureItems = function() {\n' +
    '                    nmi_Item_$callClinit();\n' +
    '                    var registry = nmi_Item_REGISTRY;\n' +
    '                    var iterator = nmur_RegistryNamespaced_iterator(registry);\n' +
    '                    var items = [];\n' +
    '                    while (iterator.$hasNext()) {\n' +
    '                        var item = iterator.$next();\n' +
    '                        var id = nmur_RegistryNamespaced_getNameForObject(registry, item);\n' +
    '                        if (id === null) continue;\n' +
    '                        var stack = nmi_ItemStack__init_10(item, 1);\n' +
    '                        items.push({id: $rt_ustr(nmu_ResourceLocation_toString(id)), displayName: $rt_ustr(nmi_ItemStack_getDisplayName(stack))});\n' +
    '                    }\n' +
    '                    items.sort(function(a, b) { return a.id < b.id ? -1 : a.id > b.id ? 1 : 0; });\n' +
    '                    return items;\n' +
    '                };\n' +
    '            }\n' + itemRegistryCall);

const absoluteOutput = path.resolve(outputPath);
fs.mkdirSync(path.dirname(absoluteOutput), { recursive: true });
fs.copyFileSync(translatedPath,
    path.join(path.dirname(absoluteOutput), 'eagler-jei-suffix-tree.js'));
fs.writeFileSync(absoluteOutput, output, 'utf8');

process.stdout.write(`Created isolated JEI JS-profile client: ${absoluteOutput}\n`);
process.stdout.write('The original client HTML and mod JAR were left unchanged.\n');
