"use strict";
const test=require("node:test"),assert=require("node:assert/strict"),vm=require("node:vm");
const install=require("./native-link.cjs");
function fixture(bindings=[]) {
    function Minecraft(){this.$value=3;}
    Minecraft.$meta={name:"net.minecraft.client.Minecraft"};
    const client=new Minecraft(),listeners=[];
    let critical=false;
    const api={mcinstance:client,reflect:{getClassById:owner=>owner===Minecraft.$meta.name?{id:owner,class:Minecraft,compiledName:"nmc_Minecraft"}:null},
      hooks:{methods:{nmc_Minecraft_echo:($this,s)=>s,nmc_Minecraft_value:$this=>$this.$value,nmc_Minecraft_static:()=>0},
      _rippedMethodTypeMap:{nmc_Minecraft_echo:"instance",nmc_Minecraft_value:"instance",nmc_Minecraft_static:"static"},
      _rippedConstructors:{nmc_Minecraft__init_0:()=>new Minecraft()},_rippedStaticProperties:{nmc_Minecraft:{count:0,flag:0}},
      _teavm:{$rt_isInstance:(o,c)=>o instanceof c,$rt_suspending:()=>critical,$rt_resuming:()=>false}},
      util:{str:s=>({nativeString:s}),ustr:o=>o.nativeString},events:{types:["frame"]},
      addEventListener:(n,f)=>listeners.push(f),removeEventListener:(n,f)=>listeners.splice(listeners.indexOf(f),1)};
    const runtime={getMinecraft:()=>client};
    const manifest={schemaVersion:1,bindings};
    return {api,client,manifest,listeners,runtime,link:install(api,runtime,manifest),critical:v=>critical=v};
}
function binding(id,kind,descriptor,extra={}) {return {id,owner:"net.minecraft.client.Minecraft",kind,descriptor,synchronous:true,...extra};}
const echo=binding("echo","instance","(Ljava/lang/String;)Ljava/lang/String;",{symbol:"nmc_Minecraft_echo"});
const value=binding("value","instance","()I",{symbol:"nmc_Minecraft_value"});
test("raw client identity and truthful capabilities",()=>{const f=fixture();assert.equal(f.link.getMinecraft(),f.client);assert.equal(f.link.capabilities.forgeLifecycle,false);assert.equal(f.link.inspect().bindingCount,0);});
test("native string round trip and raw receiver",()=>{const f=fixture([echo]);assert.equal(f.link.invoke("echo",f.client,["hello"]),"hello");assert.equal(f.link.invoke("echo",f.client,[null]),null);});
test("uses current hooks rather than a stale method snapshot",()=>{const f=fixture([value]);f.api.hooks.methods.nmc_Minecraft_value=()=>9;assert.equal(f.link.invoke("value",f.client,[]),9);});
test("constructor retains native reference",()=>{const f=fixture([binding("new","constructor","()V",{symbol:"nmc_Minecraft__init_0"})]);const v=f.link.invoke("new",null,[]);assert.equal(v.constructor,f.client.constructor);});
test("static fields preserve zero and false",()=>{const f=fixture([binding("count","staticField","I",{property:"count",writable:true}),binding("flag","staticField","Z",{property:"flag"})]);assert.equal(f.link.read("count",null),0);assert.equal(f.link.read("flag",null),false);f.link.write("count",null,4);assert.equal(f.link.read("count",null),4);assert.throws(()=>f.link.write("flag",null,true),/writable/);});
test("instance fields use exact properties",()=>{const f=fixture([binding("field","instanceField","I",{property:"$value",writable:true})]);f.link.write("field",f.client,7);assert.equal(f.link.read("field",f.client),7);});
test("rejects duplicate IDs and malformed descriptors",()=>{assert.throws(()=>fixture([value,value]),/duplicate/);for(const d of ["(V)I","()II","(I","Lbad.name;","()J","()[I"]){assert.throws(()=>fixture([binding("x","static",d,{symbol:"nmc_Minecraft_static"})]));}});
test("rejects mismatched owner metadata",()=>{const f=fixture();f.api.reflect.getClassById=()=>({id:"bad",class:f.client.constructor});assert.throws(()=>install(f.api,f.runtime,{schemaVersion:1,bindings:[value]}),/identity/);});
test("no arity or overload guessing",()=>{assert.throws(()=>fixture([binding("x","instance","()I",{symbol:"nmc_Minecraft_missing"})]),/unavailable/);assert.throws(()=>fixture([binding("x","instance","()I",{symbol:"nmc_Minecraft_static"})]),/kind/);});
test("rejects suspension, freezing, and post-call suspension",()=>{const f=fixture([value]);f.critical(true);assert.throws(()=>f.link.invoke("value",f.client,[]),/suspending/);f.critical(false);f.api.hooks.freezeCallstack=true;assert.throws(()=>f.link.getMinecraft(),/freeze/);f.api.hooks.freezeCallstack=false;f.api.hooks.methods.nmc_Minecraft_value=()=>{f.critical(true);return 1;};assert.throws(()=>f.link.invoke("value",f.client,[]),/suspending/);});
test("rejects invalid receivers, arguments and ABI ranges",()=>{const f=fixture([echo,value]);assert.throws(()=>f.link.invoke("echo",{},["x"]),/instance/);assert.throws(()=>f.link.invoke("echo",f.client,[]),/count/);assert.throws(()=>f.link.invoke("echo",f.client,[9]),/String/);f.api.hooks.methods.nmc_Minecraft_value=()=>2147483648;assert.throws(()=>f.link.invoke("value",f.client,[]),/range/);});
test("typed adapters enforce return categories",()=>{const f=fixture([value]);assert.equal(f.link.invokeTyped("value",f.client,[],"int"),3);assert.throws(()=>f.link.invokeTyped("value",f.client,[],"reference"),/category/);});
test("event subscription has idempotent cleanup",()=>{const f=fixture();const remove=f.link.subscribe("frame",()=>{});assert.equal(f.listeners.length,1);remove();remove();assert.equal(f.listeners.length,0);assert.throws(()=>f.link.subscribe("forge:tick",()=>{}),/Unknown/);});
test("rejects unsupported async declaration",()=>{assert.throws(()=>fixture([{...value,synchronous:false}]),/async ABI/);});
test("serializable installer needs no CommonJS in browser",()=>{const f=fixture();const ctx=vm.createContext({api:f.api,native:f.runtime,manifest:f.manifest});vm.runInContext("globalThis.link=("+install.toString()+")(api,native,manifest)",ctx);assert.equal(ctx.link.getMinecraft(),f.client);});
test("checks actual getter identity",()=>{const f=fixture();f.api.mcinstance={};assert.throws(()=>f.link.getMinecraft(),/identity/);});
