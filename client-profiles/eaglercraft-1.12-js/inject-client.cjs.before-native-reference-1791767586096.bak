'use strict';

const fs = require('node:fs');
const path = require('node:path');
const { installJeiReadiness } = require('./jei-readiness.cjs');
const { installClientBridge } =
    require('../shared/client-bridge.cjs');
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

const bridgeScript =
    '    <script>\n' +
    '    (function() {\n' +
    '        "use strict";\n' +
    '        var install = ' + installClientBridge.toString() + ';\n' +
    '        install(window, {\n' +
    '            getLocale: function() {\n' +
    '                var locale = window.__goldClientJeiLocaleTag;\n' +
    '                if (typeof locale !== "string" || !locale.trim()) {\n' +
    '                    throw new Error("Capture client items before requesting the locale");\n' +
    '                }\n' +
    '                return locale;\n' +
    '            },\n' +
    '            getRegisteredItemTypes: function() {\n' +
    '                if (typeof window.__goldClientCaptureItems !== "function") {\n' +
    '                    throw new Error("The client item registry adapter is not ready");\n' +
    '                }\n' +
    '                return window.__goldClientCaptureItems();\n' +
    '            },\n' +
    '            getTooltipData: function(id, advanced) {\n' +
    '                if (typeof window.__goldClientJeiGetTooltipData !== "function") {\n' +
    '                    throw new Error("The client tooltip adapter is not ready");\n' +
    '                }\n' +
    '                return window.__goldClientJeiGetTooltipData(id, advanced);\n' +
    '            }\n' +
    '        }, "supplied-eaglercraft-1.12.2-js");\n' +
    '    })();\n' +
    '    </script>\n';

const translatedScript =
    '    <script src="eagler-jei-ingredient-elements.js"></script>\n' +
    '    <script>\n' +
    '        if (typeof window.main !== "function" || ' +
        'typeof window.goldClientJeiSearch !== "function" || ' +
        'typeof window.goldClientJeiInitialize !== "function" || ' +
        'typeof window.goldClientJeiInitializeWithTooltips !== "function" || ' +
        'typeof window.goldClientJeiSetLocale !== "function" || ' +
        'typeof window.goldClientJeiFilter !== "function" || ' +
        'typeof window.goldClientJeiTooltip !== "function") {\n' +
    '            throw new Error("Translated JEI ingredient-element exports are missing");\n' +
    '        }\n' +
    '        window.main([], function(error) { if (error) throw error; });\n' +
    '        window.__goldClientJeiProgressUpdate = function(title, steps, step, message, active) {\n' +
    '            var status = document.getElementById("gold-jei-status");\n' +
    '            if (status) status.textContent = (active ? String(title) + ": " + step + "/" + steps : "Completed " + String(title) + " (" + step + "/" + steps + ")");\n' +
    '            window.__goldClientJeiProgress = {title:String(title), steps:steps, step:step, message:String(message), active:active};\n' +
    '        };\n' +
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
    '                var bridge = window.goldClientBridge;\n' +
    '                var items = bridge.getRegisteredItemTypes();\n' +
    '                window.__goldClientJeiLocaleTag = bridge.getLocale();\n' +
    '                if (!items.length) throw new Error("Client item registry returned no entries");\n' +
    '                window.__goldClientJeiSourceItems = items;\n' +
    '                window.__goldClientJeiFactoryCallCount = (window.__goldClientJeiFactoryCallCount || 0) + 1;\n' +
    '                var ids = items.map(function(item) { return item.id; });\n' +
    '                var displayNames = items.map(function(item) { return item.displayName; });\n' +
    '                var normalTooltipData = items.map(function(item) { return item.normalTooltipData; });\n' +
    '                var advancedTooltipData = items.map(function(item) { return item.advancedTooltipData; });\n' +
    '                window.goldClientJeiSetLocale(window.__goldClientJeiLocaleTag);\n' +
    '                var elements = JSON.parse(window.goldClientJeiInitializeWithTooltips(ids, displayNames, normalTooltipData, advancedTooltipData));\n' +
    '                if (!Array.isArray(elements) || elements.length !== items.length) throw new Error("Original JEI factory returned an unexpected element count");\n' +
    '                window.__goldClientJeiItems = elements;\n' +
    '                window.__goldClientJeiIndexError = null; window.__goldClientJeiIndexReady = true;\n' +
    '                document.getElementById("gold-jei-status").textContent = "Original JEI factory created " + elements.length + " ingredient elements from " + items.length + " registered item types (default stack only).";\n' +
    '                document.getElementById("gold-jei-query").disabled = false;\n' +
    '                showJeiDiagnosticResults();\n' +
    '                if (location.protocol !== "file:") {\n' +
    '                    fetch("/__gold-client-corpus", {method:"POST", headers:{"Content-Type":"application/json"}, body:JSON.stringify({profile:"supplied-eaglercraft-1.12.2-js", localeTag:window.__goldClientJeiLocaleTag, category:"registered item types; one default metadata-0 stack per registry item", excluded:["creative variants","item stacks from inventory","ingredients","recipes"], items:items})}).then(function(response) { return response.text().then(function(message) { if (!response.ok) throw new Error(message || ("HTTP " + response.status)); window.__goldClientJeiCorpusExport = {ok:true, message:message, count:items.length}; var status = document.getElementById("gold-jei-status"); if (status) status.textContent += " | " + message; console.info("[JEI corpus export]", message); }); }).catch(function(error) { window.__goldClientJeiCorpusExport = {ok:false, message:String(error)}; var status = document.getElementById("gold-jei-status"); if (status) status.textContent += " | Corpus export failed: " + error; console.error("[JEI corpus export]", error); });\n' +
    '                }\n' +
    '            } catch (error) {\n' +
    '                window.__goldClientJeiIndexing = false;\n' +
    '                window.__goldClientJeiIndexError = String(error); document.getElementById("gold-jei-status").textContent = "Index failed: " + error;\n' +
    '                console.error("[JEI diagnostic index]", error);\n' +
    '                return;\n' +
    '            }\n' +
    '            window.__goldClientJeiIndexing = false;\n' +
    '        }\n' +
    '        function showJeiDiagnosticResults() {\n' +
    '            if (!window.__goldClientJeiIndexReady) return;\n' +
    '            var query = document.getElementById("gold-jei-query").value;\n' +
    '            window.__goldClientJeiFilterCallCount = (window.__goldClientJeiFilterCallCount || 0) + 1;\n' +
    '            var matches = JSON.parse(window.goldClientJeiFilter(query));\n' +
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
    '            var data = JSON.stringify({profile:"supplied-eaglercraft-1.12.2-js", localeTag:window.__goldClientJeiLocaleTag, category:"registered item types; one default metadata-0 stack per registry item", excluded:["creative variants","item stacks from inventory","ingredients","recipes"], items:window.__goldClientJeiSourceItems}, null, 2);\n' +
    '            var link = document.createElement("a");\n' +
    '            link.href = URL.createObjectURL(new Blob([data], {type:"application/json"}));\n' +
    '            link.download = "jei-real-item-corpus.json";\n' +
    '            link.click();\n' +
    '            setTimeout(function() { URL.revokeObjectURL(link.href); }, 1000);\n' +
    '        }\n' +
    '    </script>\n';

const readinessScript =
    '    <script>\n' +
    '    (' + installJeiReadiness.toString() + ')(window);\n' +
    '    </script>\n';

output = output.slice(0, scriptOffset)
    + bridgeScript
    + readinessScript
    + translatedScript
    + output.slice(scriptOffset);
output = output.replace(itemRegistryCall,
    'if (typeof window.__goldClientCaptureItems !== "function") {\n' +
    '                window.__goldClientCaptureItems = function() {\n' +
    '                    nmi_Item_$callClinit();\n' +
    '                    var settings = nmc_Minecraft_getMinecraft().$gameSettings;\n' +
    '                    var language = settings == null ? null : settings.$language;\n' +
    '                    window.__goldClientJeiLocaleTag = language == null ? "en_us" : $rt_ustr(language);\n' +
    '                    var registry = nmi_Item_REGISTRY;\n' +
    '                    window.__goldClientJeiStacksById = Object.create(null);\n' +
    '                    window.__goldClientJeiGetTooltipData = function(id, advanced) {\n' +
    '                        var stack = window.__goldClientJeiStacksById[id];\n' +
    '                        if (!stack) throw new Error("No live client ItemStack for " + id);\n' +
    '                        var minecraft = nmc_Minecraft_getMinecraft();\n' +
    '                        var lines = nmi_ItemStack_getTooltip(stack, minecraft.$player, !!advanced);\n' +
    '                        var encoded = "";\n' +
    '                        for (var index = 0; index < ju_ArrayList_size(lines); index++) {\n' +
    '                            var line = $rt_ustr(ju_ArrayList_get(lines, index));\n' +
    '                            encoded += line.length + ":" + line;\n' +
    '                        }\n' +
    '                        return encoded;\n' +
    '                    };\n' +
    '                    var iterator = nmur_RegistryNamespaced_iterator(registry);\n' +
    '                    var items = [];\n' +
    '                    while (iterator.$hasNext()) {\n' +
    '                        var item = iterator.$next();\n' +
    '                        var id = nmur_RegistryNamespaced_getNameForObject(registry, item);\n' +
    '                        if (id === null) continue;\n' +
    '                        var stack = nmi_ItemStack__init_10(item, 1);\n' +
    '                        var idText = $rt_ustr(nmu_ResourceLocation_toString(id));\n' +
    '                        window.__goldClientJeiStacksById[idText] = stack;\n' +
    '                        items.push({id: idText, displayName: $rt_ustr(nmi_ItemStack_getDisplayName(stack)), normalTooltipData: window.__goldClientJeiGetTooltipData(idText, false), advancedTooltipData: window.__goldClientJeiGetTooltipData(idText, true)});\n' +
    '                    }\n' +
    '                    items.sort(function(a, b) { return a.id < b.id ? -1 : a.id > b.id ? 1 : 0; });\n' +
    '                    return items;\n' +
    '                };\n' +
    '            }\n' + itemRegistryCall);


/* GOLD_CLIENT_RECIPE_PROBE_V1 */
const recipeProbeSource = "\n(function() {\n    // GOLD_CLIENT_RECIPE_PROBE_V1\n    window.__goldClientCaptureRecipes = function() {\n        var registry = nmic_CraftingManager_field_193380_a;\n        var furnace = nmic_FurnaceRecipes_SMELTING_BASE;\n\n        if (!registry || !furnace || !furnace.$smeltingList) {\n            throw new Error(\"Client recipe registries are not initialized\");\n        }\n\n        function stackData(stack) {\n            if (!stack || stack.$field_190928_g ||\n                !stack.$item0 || stack.$stackSize <= 0) {\n                return null;\n            }\n\n            var name = nmur_RegistryNamespaced_getNameForObject(\n                nmi_Item_REGISTRY, stack.$item0);\n\n            if (name === null) {\n                throw new Error(\"Recipe stack item has no registry identifier\");\n            }\n\n            return {\n                id: $rt_ustr(nmu_ResourceLocation_toString(name)),\n                count: stack.$stackSize,\n                metadata: stack.$itemDamage\n            };\n        }\n\n        function ingredientData(ingredient) {\n            if (!ingredient || !ingredient.$field_193371_b ||\n                !ingredient.$field_193371_b.data) {\n                throw new Error(\"Unexpected client Ingredient representation\");\n            }\n\n            var stacks = ingredient.$field_193371_b.data;\n            var alternatives = [];\n\n            for (var i = 0; i < stacks.length; i++) {\n                var stack = stackData(stacks[i]);\n                if (stack) alternatives.push(stack);\n            }\n\n            return alternatives;\n        }\n\n        function ingredientList(list) {\n            if (!list) {\n                throw new Error(\"Recipe ingredient list is missing\");\n            }\n\n            var backing = list.$field_191198_a;\n            if (!backing || typeof backing.$size !== \"function\" ||\n                typeof backing.$get2 !== \"function\") {\n                throw new Error(\"Unexpected client recipe list representation\");\n            }\n\n            var result = [];\n            var size = backing.$size();\n\n            for (var i = 0; i < size; i++) {\n                result.push(ingredientData(backing.$get2(i)));\n            }\n\n            return result;\n        }\n\n        var recipes = [];\n        var unsupported = [];\n        var iterator = nmur_RegistryNamespaced_iterator(registry);\n\n        while (iterator.$hasNext()) {\n            var recipe = iterator.$next();\n            var name = nmur_RegistryNamespaced_getNameForObject(\n                registry, recipe);\n\n            if (name === null) {\n                throw new Error(\"Crafting recipe has no registry identifier\");\n            }\n\n            var id = $rt_ustr(nmu_ResourceLocation_toString(name));\n\n            if (Object.getPrototypeOf(recipe) === nmic_ShapedRecipes.prototype) {\n                var ingredients = ingredientList(recipe.$recipeItems);\n\n                if (ingredients.length !==\n                    recipe.$recipeWidth * recipe.$recipeHeight) {\n                    throw new Error(\"Shaped recipe dimensions mismatch: \" + id);\n                }\n\n                var output = stackData(recipe.$recipeOutput);\n                if (!output) {\n                    throw new Error(\"Shaped recipe output is empty: \" + id);\n                }\n\n                recipes.push({\n                    id: id,\n                    type: \"crafting_shaped\",\n                    width: recipe.$recipeWidth,\n                    height: recipe.$recipeHeight,\n                    ingredients: ingredients,\n                    output: output\n                });\n            } else if (Object.getPrototypeOf(recipe) === nmic_ShapelessRecipes.prototype) {\n                var output = stackData(recipe.$recipeOutput0);\n                if (!output) {\n                    throw new Error(\"Shapeless recipe output is empty: \" + id);\n                }\n\n                recipes.push({\n                    id: id,\n                    type: \"crafting_shapeless\",\n                    ingredients: ingredientList(recipe.$recipeItems0),\n                    output: output\n                });\n            } else {\n                unsupported.push({\n                    id: id,\n                    reason: \"Crafting class not handled by this probe\"\n                });\n            }\n        }\n\n        var entries = ju_HashMap_entrySet(furnace.$smeltingList);\n        var smelting = ju_HashMap$HashMapEntrySet_iterator(entries);\n        var smeltingIndex = 0;\n\n        while (ju_HashMap$AbstractMapIterator_hasNext(smelting)) {\n            var entry = ju_HashMap$EntryIterator_next(smelting);\n            var input = stackData(entry.$key);\n            var output = stackData(entry.$value3);\n\n            if (!input || !output) {\n                throw new Error(\"Smelting entry contains an empty stack\");\n            }\n\n            recipes.push({\n                diagnosticId: \"smelting-entry-\" + smeltingIndex++,\n                type: \"smelting\",\n                ingredients: [[input]],\n                output: output\n            });\n        }\n\n        return {\n            profile: \"supplied-eaglercraft-1.12.2-js\",\n            recipes: recipes,\n            unsupported: unsupported,\n            limitations: [\n                \"Stack NBT is not serialized\",\n                \"Smelting experience is not captured\",\n                \"Smelting diagnostic IDs are not stable registry IDs\",\n                \"Other crafting classes are not handled\"\n            ]\n        };\n    };\n})();\n";
requireUnique(output, itemRegistryCall, 'recipe probe screen-change callback');
output = output.replace(
    itemRegistryCall,
    recipeProbeSource + "\n" + itemRegistryCall
);

const absoluteOutput = path.resolve(outputPath);
fs.mkdirSync(path.dirname(absoluteOutput), { recursive: true });
fs.copyFileSync(translatedPath,
    path.join(path.dirname(absoluteOutput), 'eagler-jei-ingredient-elements.js'));
fs.writeFileSync(absoluteOutput, output, 'utf8');

process.stdout.write(`Created isolated JEI JS-profile client: ${absoluteOutput}\n`);
process.stdout.write('The original client HTML and mod JAR were left unchanged.\n');
