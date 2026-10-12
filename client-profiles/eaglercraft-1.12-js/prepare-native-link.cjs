"use strict";
const fs=require("node:fs"),path=require("node:path"),crypto=require("node:crypto");
const install=require("../shared/native-link.cjs");
function prepare(input,outputDirectory) {
    const source=fs.readFileSync(input,"utf8");
    for(const marker of ["__eaglerforgeinjector_installation_flag__","goldClientNativeRuntime"])
        if(!source.includes(marker)) throw new Error("Required native-surface marker missing: "+marker);
    if(source.includes('id="gold-client-native-link-script"')) throw new Error("Native linker already injected");
    const endings=[...source.matchAll(/<\/body\s*>/gi)];
    if(endings.length!==1) throw new Error("Expected exactly one closing body tag");
    const output=path.resolve(outputDirectory),html=path.join(output,path.basename(input));
    if(fs.existsSync(output)) throw new Error("Candidate directory already exists; choose a new directory");
    const script='"use strict";\n(function(){\nconst install='+install.toString()+';\n'+
      'if(globalThis.goldClientNativeLink!==undefined)throw new Error("Native linker already installed");\n'+
      'Object.defineProperty(globalThis,"goldClientNativeLink",{enumerable:true,value:Object.freeze({apiVersion:1,install:function(manifest){return install(globalThis.ModAPI,globalThis.goldClientNativeRuntime,manifest);}})});\n})();\n';
    const insertion='<script id="gold-client-native-link-script" src="./gold-client-native-link.js"></script>\n';
    const position=endings[0].index;
    const candidate=source.slice(0,position)+insertion+source.slice(position);
    const digest=s=>crypto.createHash("sha256").update(s).digest("hex");
    fs.mkdirSync(output,{recursive:true});
    try {
        // Keep relative dependencies beside the candidate. Do not copy HTML clients.
        for(const entry of fs.readdirSync(path.dirname(path.resolve(input)),{withFileTypes:true})) {
            if(entry.isFile() && /\.(?:js|cjs|mjs|json|css|wasm)$/i.test(entry.name))
                fs.copyFileSync(path.join(path.dirname(path.resolve(input)),entry.name),path.join(output,entry.name));
        }
        fs.writeFileSync(path.join(output,"gold-client-native-link.js"),script);
        fs.writeFileSync(html,candidate);
        const proof={scope:"Explicit native linker candidate only",inputSha256:digest(source),candidateSha256:digest(candidate),
          nativeLinkSha256:digest(script),originalInputsModified:false,javaCompilationVerified:false,
          browserExecutionVerified:false,forgeRuntimeImplemented:false,fullModEquivalenceVerified:false};
        fs.writeFileSync(path.join(output,"native-link-provenance.json"),JSON.stringify(proof,null,2));
        if(fs.readFileSync(input,"utf8")!==source) throw new Error("Input changed during preparation");
        return html;
    } catch(error) { fs.rmSync(output,{recursive:true,force:true}); throw error; }
}
if(require.main===module) {
    const [input,output]=process.argv.slice(2);
    if(!input||!output) {console.error("Usage: node prepare-native-link.cjs current-client.html new-candidate-directory");process.exitCode=2;}
    else try {console.log(prepare(input,output));} catch(error){console.error(error.stack);process.exitCode=1;}
}
module.exports=prepare;
