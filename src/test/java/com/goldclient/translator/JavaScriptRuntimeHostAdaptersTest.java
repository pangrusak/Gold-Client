package com.goldclient.translator;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the runtime adapters that back EaglerForge host bindings.
 *
 * These checks prove the runtime routes java.io.File, Log4j, Guava
 * preconditions, and platform super-constructors to an installed host binding.
 * They do not prove that a browser client can execute a Forge mod.
 */
class JavaScriptRuntimeHostAdaptersTest {
  @TempDir
  Path tempDir;

  @Test
  void routesFileLoggingGuavaAndPlatformSuperConstructorsThroughBindings() throws Exception {
    Path output = tempDir.resolve("runtime-host-adapters");
    new JavaScriptModTranslationExporter().export(List.of(), output);
    Path runner = output.resolve("runtime-host-adapters.mjs");
    Files.writeString(runner, """
        import { GoldClientRuntime as runtime } from "./gold-client-runtime.js";

        class FakeFile {
          constructor(...parts) {
            this.path = "/" + parts.map(String).join("/").replaceAll("//", "/");
          }
          getPath() { return this.path; }
        }

        function bindingFailure(code, detail) {
          const error = new Error("[GoldForge " + code + "] " + detail);
          error.name = "GoldClientForgeBindingError";
          return error;
        }

        const calls = [];
        globalThis.goldClientForgeBindings = {
          virtualFileClass: FakeFile,
          forge: {
            construct(owner, args, receiver) {
              if (owner === "net.minecraft.block.MissingCtorBlock") {
                throw bindingFailure("missing-constructor", owner + " has no constructors");
              }
              if (owner === "net.minecraft.block.MissingClassBlock") {
                throw bindingFailure("unknown-class", owner);
              }
              calls.push([owner, args.length, !!receiver]);
              if (!receiver) return { owner, args, initialized: true };
              receiver.platformOwner = owner;
              receiver.platformArgs = args;
              receiver.isCanceled = () => false;
              receiver.isCancelable = () => false;
              return receiver;
            },
            call(owner, method) {
              if (owner === "net.minecraft.block.MissingClassBlock") {
                throw bindingFailure("unknown-class", owner);
              }
              throw bindingFailure("missing-static-method", owner + "." + method);
            },
            get(owner, field) {
              throw bindingFailure("missing-static-field", owner + "." + field);
            }
          }
        };
        globalThis.goldClientForgeBindings.minecraft = globalThis.goldClientForgeBindings.forge;

        const contents = new Map([["/config/sample.cfg", "alpha\\nbeta\\n"]]);
        FakeFile.prototype.readText = function () { return contents.get(this.path) ?? null; };
        FakeFile.prototype.writeText = function (value) { contents.set(this.path, value); };
        FakeFile.prototype.exists = function () { return contents.has(this.path); };
        FakeFile.prototype.isDirectory = function () { return false; };

        const file = runtime.createJava("java.io.File", "config", "sample.cfg");
        if (file.path !== "/config/sample.cfg") {
          throw new Error("java.io.File adapter path: " + file.path);
        }
        if (!runtime.invokeJavaStatic("java.nio.file.Files", "exists", file)) {
          throw new Error("java.nio.file.Files.exists did not reach the virtual file");
        }
        if (runtime.invokeJavaStatic("java.lang.Thread", "sleep", 5) !== undefined) {
          throw new Error("Thread.sleep should be a non-blocking no-op");
        }

        const outputStream = runtime.createJava("java.io.FileOutputStream", file);
        const outputWriter = runtime.createJava("java.io.OutputStreamWriter", outputStream);
        const bufferedWriter = runtime.createJava("java.io.BufferedWriter", outputWriter);
        bufferedWriter.write("gamma");
        bufferedWriter.close();
        if (contents.get("/config/sample.cfg") !== "gamma") {
          throw new Error("buffered writer did not persist file contents: " + contents.get("/config/sample.cfg"));
        }

        const inputStream = runtime.createJava("java.io.FileInputStream", file);
        const inputReader = runtime.createJava("java.io.InputStreamReader", inputStream);
        const bufferedReader = runtime.createJava("java.io.BufferedReader", inputReader);
        const lines = [];
        bufferedReader.lines().forEach(line => lines.push(line));
        bufferedReader.close();
        if (lines.join("|") !== "gamma") {
          throw new Error("buffered reader lines: " + lines.join("|"));
        }

        class StaticFieldProbe {}
        runtime.registerClass("probe.StaticFieldProbe", StaticFieldProbe);
        runtime.setStaticField("probe.StaticFieldProbe", "name", "waystone");
        if (runtime.getStaticField("probe.StaticFieldProbe", "name") !== "waystone") {
          throw new Error("static field named name was not writable");
        }
        for (const fieldName of ["length", "caller", "arguments"]) {
          runtime.setStaticField("probe.StaticFieldProbe", fieldName, fieldName + "-value");
          if (runtime.getStaticField("probe.StaticFieldProbe", fieldName) !== fieldName + "-value") {
            throw new Error("static field named " + fieldName + " was not writable");
          }
        }
        runtime.setStaticField("probe.StaticFieldProbe", "prototype", "kept");
        if (runtime.getStaticField("probe.StaticFieldProbe", "prototype") !== "kept") {
          throw new Error("static field named prototype was not readable");
        }
        if (typeof StaticFieldProbe.prototype !== "object") {
          throw new Error("static field named prototype replaced the class prototype");
        }
        new StaticFieldProbe();

        const fixed = runtime.createJava("java.text.DecimalFormat", "0.00");
        if (runtime.invokeInstance(fixed, "format", 3.5) !== "3.50") {
          throw new Error("DecimalFormat fixed pattern: " + fixed.format(3.5));
        }
        const optional = runtime.createJava("java.text.DecimalFormat", "#.##");
        if (optional.format(3.14159) !== "3.14" || optional.format(2) !== "2") {
          throw new Error("DecimalFormat optional pattern: " + optional.format(3.14159));
        }

        let classNotFound = false;
        try {
          runtime.invokeJavaStatic("java.lang.Class", "forName", "absent.Probe");
        } catch (error) {
          classNotFound = error?.javaClassName === "java.lang.ClassNotFoundException";
        }
        if (!classNotFound) {
          throw new Error("Class.forName must throw ClassNotFoundException for unknown classes");
        }

        let noClassDef = false;
        try {
          runtime.getStaticField("absent.Probe", "instance");
        } catch (error) {
          noClassDef = error?.javaClassName === "java.lang.NoClassDefFoundError";
        }
        if (!noClassDef) {
          throw new Error("getStaticField must throw NoClassDefFoundError for absent classes");
        }

        let bindingNoSuchMethod = false;
        try {
          runtime.create("net.minecraft.block.MissingCtorBlock");
        } catch (error) {
          bindingNoSuchMethod = error?.javaClassName === "java.lang.NoSuchMethodError";
        }
        if (!bindingNoSuchMethod) {
          throw new Error("missing host constructor must surface as NoSuchMethodError");
        }

        let bindingNoClass = false;
        try {
          runtime.create("net.minecraft.block.MissingClassBlock");
        } catch (error) {
          bindingNoClass = error?.javaClassName === "java.lang.NoClassDefFoundError";
        }
        if (!bindingNoClass) {
          throw new Error("unknown host constructor class must surface as NoClassDefFoundError");
        }

        let bindingNoSuchField = false;
        try {
          runtime.getStaticField("net.minecraft.block.KnownBlock", "missing");
        } catch (error) {
          bindingNoSuchField = error?.javaClassName === "java.lang.NoSuchFieldError";
        }
        if (!bindingNoSuchField) {
          throw new Error("missing host static field must surface as NoSuchFieldError");
        }

        let bindingNoSuchStaticMethod = false;
        try {
          runtime.invokePlatformStatic("net.minecraft.block.KnownBlock", "missing");
        } catch (error) {
          bindingNoSuchStaticMethod = error?.javaClassName === "java.lang.NoSuchMethodError";
        }
        if (!bindingNoSuchStaticMethod) {
          throw new Error("missing host static method must surface as NoSuchMethodError");
        }

        class StaticBase {}
        class StaticSub {}
        runtime.registerClass("probe.StaticBase", StaticBase);
        runtime.registerClass("probe.StaticSub", StaticSub, null, {
          superName: "probe.StaticBase", interfaces: []
        });
        runtime.setStaticField("probe.StaticBase", "shared", "base-value");
        if (runtime.getStaticField("probe.StaticSub", "shared") !== "base-value") {
          throw new Error("getStaticField did not resolve an inherited static field");
        }

        const vector = runtime.create("org.lwjgl.util.vector.Vector4f", 1, 2, 3, 4);
        if (vector.getX() !== 1 || vector.getY() !== 2 || vector.getZ() !== 3
            || vector.getW() !== 4) {
          throw new Error("Vector4f adapter did not keep its components");
        }

        const gson = runtime.createJava("com.google.gson.GsonBuilder").create();
        if (gson.toJson({ name: "x", count: 2 }) !== '{"name":"x","count":2}') {
          throw new Error("Gson.toJson did not serialize an object: " + gson.toJson({ name: "x", count: 2 }));
        }
        class ProbeData {
          constructor() {
            this.name = "";
            this.count = 0;
          }
        }
        runtime.registerClass("probe.ProbeData", ProbeData);
        const probeData = gson.fromJson('{"name":"y","count":3}',
            runtime.classLiteral("probe.ProbeData"));
        if (!(probeData instanceof ProbeData) || probeData.name !== "y" || probeData.count !== 3) {
          throw new Error("Gson.fromJson did not populate the target class");
        }

        const logger = runtime.invokeStaticClass(
          "org.apache.logging.log4j.LogManager", "getLogger", "gold-test");
        if (logger.getName() !== "gold-test") throw new Error("LogManager did not return a logger");
        logger.info("adapter check");

        const same = runtime.invokeStaticClass(
          "com.google.common.base.Preconditions", "checkNotNull", "value");
        if (same !== "value") throw new Error("checkNotNull changed its value");
        let nullRejected = false;
        try {
          runtime.invokeStaticClass("com.google.common.base.Preconditions", "checkNotNull", null);
        } catch (error) {
          nullRejected = true;
        }
        if (!nullRejected) throw new Error("checkNotNull accepted null");

        const baseMethods = new Map([
          ["label", [{ arity: 0, descriptor: "()Ljava/lang/String;", isStatic: false,
            isAbstract: false, isBridge: false, isAdapterRequired: false,
            fn: function () { return "base"; } }]]
        ]);
        class SuperBase {
          constructor() { this.baseInit = true; }
        }
        runtime.registerClass("probe.SuperBase", SuperBase, null,
          { superName: "java.lang.Object", interfaces: [], methods: baseMethods });

        const childMethods = new Map([
          ["label", [{ arity: 0, descriptor: "()Ljava/lang/String;", isStatic: false,
            isAbstract: false, isBridge: false, isAdapterRequired: false,
            fn: function () { return "child"; } }]]
        ]);
        class SuperChild extends SuperBase {}
        runtime.registerClass("probe.SuperChild", SuperChild, null,
          { superName: "probe.SuperBase", interfaces: [], methods: childMethods });

        const child = new SuperChild();
        if (runtime.invokeInstance(child, "label") !== "child") {
          throw new Error("virtual dispatch did not reach the override");
        }
        if (runtime.invokeSuper(child, "probe.SuperBase", "label") !== "base") {
          throw new Error("invokeSuper re-entered the override instead of the base method");
        }

        const flagCalls = [];
        const flagMethods = new Map([
          ["setFlag", [{ arity: 1, descriptor: "(Z)V", isStatic: false,
            isAbstract: false, isBridge: false, isAdapterRequired: false,
            fn: function (value) { flagCalls.push(value); } }]]
        ]);
        class FlagHolder {}
        runtime.registerClass("probe.FlagHolder", FlagHolder, null,
          { superName: "java.lang.Object", interfaces: [], methods: flagMethods });
        runtime.invoke(new FlagHolder(), flagMethods, "setFlag", [0]);
        if (flagCalls.length !== 1) {
          throw new Error("lowered int 0 was not accepted for a boolean parameter");
        }

        const configReceiver = {};
        const configResult = runtime.invokeNamedConstructor(
          configReceiver, "net.minecraftforge.common.config.Configuration", file, "1.0");
        if (configResult !== configReceiver || configReceiver.platformOwner
            !== "net.minecraftforge.common.config.Configuration") {
          throw new Error("Configuration super-constructor was not routed");
        }

        const eventReceiver = {};
        const eventResult = runtime.invokeNamedConstructor(
          eventReceiver, "net.minecraftforge.fml.common.eventhandler.Event");
        if (eventResult !== eventReceiver || eventReceiver.platformOwner
            !== "net.minecraftforge.fml.common.eventhandler.Event") {
          throw new Error("Event super-constructor was not routed");
        }

        globalThis.GoldClientResources = {
          formatVersion: "gold-client-resources-v1",
          files: {
            "assets/demo/lang/en_us.json":
              Buffer.from('{"item.demo":"Demo"}', "utf8").toString("base64"),
            "demo/data.txt": Buffer.from("relative-resource", "utf8").toString("base64")
          }
        };
        if (!runtime.hasResource("assets/demo/lang/en_us.json")) {
          throw new Error("embedded resource was not visible to the runtime");
        }
        if (runtime.hasResource("assets/demo/missing.json")) {
          throw new Error("missing resource was reported as present");
        }
        if (runtime.getResourceText("assets/demo/lang/en_us.json") !== '{"item.demo":"Demo"}') {
          throw new Error("resource text did not round-trip");
        }
        if (runtime.listResources("assets/demo/").length !== 1) {
          throw new Error("resource listing did not include the embedded file");
        }
        let invalidResource = false;
        try {
          runtime.hasResource("../outside");
        } catch (error) {
          invalidResource = true;
        }
        if (!invalidResource) throw new Error("invalid resource path was accepted");

        const literal = runtime.classLiteral("demo.Plugin");
        const relative = literal.getResourceAsStream("data.txt");
        if (!relative) throw new Error("relative class resource was not found");
        const relativeBytes = relative.readAllBytes();
        if (new TextDecoder().decode(relativeBytes) !== "relative-resource") {
          throw new Error("relative class resource bytes did not round-trip");
        }
        const absolute = literal.getResourceAsStream("/assets/demo/lang/en_us.json");
        if (!absolute) throw new Error("absolute class resource was not found");
        if (new TextDecoder().decode(absolute.readAllBytes()) !== '{"item.demo":"Demo"}') {
          throw new Error("absolute class resource bytes did not round-trip");
        }
        if (literal.getResourceAsStream("missing.txt") !== null) {
          throw new Error("missing class resource did not return null");
        }
        const instanceClass = runtime.invokeInstance(
          {__goldClassName: "demo.Plugin"}, "getClass");
        const instanceStream = instanceClass.getResourceAsStream("data.txt");
        if (!instanceStream || new TextDecoder().decode(instanceStream.readAllBytes())
            !== "relative-resource") {
          throw new Error("getClass().getResourceAsStream did not resolve the class resource");
        }
        const loader = literal.getClassLoader();
        const loaderStream = loader.getResourceAsStream("demo/data.txt");
        if (!loaderStream || new TextDecoder().decode(loaderStream.readAllBytes())
            !== "relative-resource") {
          throw new Error("class loader resource stream did not round-trip");
        }
        if (literal.getResource("data.txt")?.getPath() !== "/demo/data.txt") {
          throw new Error("class resource URL path was wrong");
        }

        if (calls.length !== 2) throw new Error("unexpected host construct calls: " + calls.length);
        console.log("runtime-host-adapters-ok");
        """);
    Process node;
    try {
      node = new ProcessBuilder("node", runner.toString()).redirectErrorStream(true).start();
    } catch (Exception error) {
      throw new AssertionError("Node.js is required for runtime adapter tests", error);
    }
    assertTrue(node.waitFor(10, TimeUnit.SECONDS), "runtime host adapter test timed out");
    String outputText = new String(node.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
    assertEquals(0, node.exitValue(), outputText);
    assertEquals("runtime-host-adapters-ok", outputText.substring(outputText.lastIndexOf('\n') + 1));
  }

  @Test
  void builtinNbtValueAndEnumAdaptersKeepRealSemantics() throws Exception {
    Path output = tempDir.resolve("runtime-nbt-adapters");
    new JavaScriptModTranslationExporter().export(List.of(), output);
    Path runner = output.resolve("runtime-nbt-adapters.mjs");
    Files.writeString(runner, """
        globalThis.goldClientForgeMemberMap = {
          func_74778_a: "setString",
          func_74779_i: "getString",
          func_176766_a: "byMetadata",
          field_test_x: "x"
        };
        const { GoldClientRuntime: r } = await import("./gold-client-runtime.js");

        const srgCompound = r.create("net.minecraft.nbt.NBTTagCompound");
        srgCompound.func_74778_a("key", "value");
        if (srgCompound.func_74779_i("key") !== "value") {
          throw new Error("SRG alias on a built-in adapter value");
        }
        const lightBlueBySrg = r.invokePlatformStatic(
            "net.minecraft.item.EnumDyeColor", "func_176766_a", 3);
        if (lightBlueBySrg !== r.getStaticField("net.minecraft.item.EnumDyeColor", "LIGHT_BLUE")) {
          throw new Error("SRG alias on a built-in adapter static method");
        }

        const compound = r.create("net.minecraft.nbt.NBTTagCompound");
        compound.setString("name", "waystone");
        compound.setInteger("count", 4);
        compound.setBoolean("enabled", true);
        if (compound.getString("name") !== "waystone") throw new Error("NBT string get/set");
        if (compound.getInteger("count") !== 4) throw new Error("NBT int get/set");
        if (!compound.getBoolean("enabled")) throw new Error("NBT boolean get/set");
        if (compound.getInteger("missing") !== 0) throw new Error("NBT missing int default");
        if (!compound.hasKey("name") || !compound.hasKeyOfType("count", 3)) {
          throw new Error("NBT hasKey");
        }
        if (!compound.hasKey("count", 3) || compound.hasKey("count", 8)) {
          throw new Error("NBT hasKey(key, type)");
        }
        const keys = compound.getKeySet();
        if (keys.size() !== 3 || !keys.contains("name")) throw new Error("NBT getKeySet");
        const copy = compound.copy();
        copy.setInteger("count", 9);
        if (compound.getInteger("count") !== 4 || copy.getInteger("count") !== 9) {
          throw new Error("NBT copy must be deep");
        }

        const list = r.create("net.minecraft.nbt.NBTTagList");
        list.appendTag(r.create("net.minecraft.nbt.NBTTagString", "a"));
        list.appendTag(r.create("net.minecraft.nbt.NBTTagString", "b"));
        if (list.tagCount() !== 2 || list.getStringTagAt(1) !== "b" || list.getTagType() !== 8) {
          throw new Error("NBT list append/get");
        }
        const nested = r.create("net.minecraft.nbt.NBTTagCompound");
        nested.setTagList("items", list);
        if (nested.getTagList("items", 8).tagCount() !== 2) throw new Error("NBT nested list");
        if (nested.getTagList("items", 10).tagCount() !== 0) {
          throw new Error("NBT getTagList must reject a mismatched element type");
        }

        const bytes = r.create("net.minecraft.nbt.NBTTagByteArray", [1, -2, 130]);
        if (bytes.getByteArray()[1] !== -2 || bytes.getByteArray()[2] !== -126) {
          throw new Error("NBT byte array signed narrowing");
        }
        const ints = r.create("net.minecraft.nbt.NBTTagIntArray", [3, -4]);
        if (ints.getIntArray()[0] !== 3 || ints.getIntArray()[1] !== -4) {
          throw new Error("NBT int array");
        }
        const uuid = r.createJava("java.util.UUID", "123e4567-e89b-12d3-a456-426614174000");
        compound.setUniqueId("id", uuid);
        if (!compound.getUniqueId("id").equals(uuid)) throw new Error("NBT UUID round trip");

        const vec = r.create("net.minecraft.util.math.Vec3i", 1, 2, 3);
        if (vec.getX() !== 1 || vec.getY() !== 2 || vec.getZ() !== 3) throw new Error("Vec3i");
        if (vec.distanceSq(4, 6, 3) !== 25) throw new Error("Vec3i.distanceSq");
        if (vec.field_test_x !== 1) throw new Error("SRG field alias read");
        vec.field_test_x = 9;
        if (vec.getX() !== 9 || vec.field_test_x !== 9) {
          throw new Error("SRG field alias write");
        }
        const chunk = r.create("net.minecraft.util.math.ChunkPos", 1, -2);
        if (chunk.getXStart() !== 16 || chunk.getXEnd() !== 31
            || chunk.getZStart() !== -32 || chunk.getZEnd() !== -17) {
          throw new Error("ChunkPos bounds");
        }

        const text = r.create("net.minecraft.util.text.TextComponentString", "hello");
        text.appendText(" world");
        if (text.getUnformattedText() !== "hello world") throw new Error("TextComponentString text");
        const red = r.getStaticField("net.minecraft.util.text.TextFormatting", "RED");
        text.setStyle(r.create("net.minecraft.util.text.Style").setColor(red));
        const formatted = text.getFormattedText();
        if (!formatted.startsWith(String.fromCharCode(167) + "c")
            || !formatted.endsWith(String.fromCharCode(167) + "r")) {
          throw new Error("TextComponentString formatting: " + formatted);
        }

        const lightBlue = r.getStaticField("net.minecraft.item.EnumDyeColor", "LIGHT_BLUE");
        if (lightBlue.getMetadata() !== 3 || lightBlue.getDyeDamage() !== 12
            || lightBlue.getColorValue() !== 3847130) {
          throw new Error("EnumDyeColor data");
        }
        if (r.invokePlatformStatic("net.minecraft.item.EnumDyeColor", "byDyeDamage", 15)
            !== r.getStaticField("net.minecraft.item.EnumDyeColor", "WHITE")) {
          throw new Error("EnumDyeColor.byDyeDamage");
        }
        if (r.invokePlatformStatic("net.minecraft.util.EnumHand", "valueOf", "OFF_HAND").ordinal() !== 1) {
          throw new Error("EnumHand.valueOf");
        }
        if (r.getStaticField("net.minecraft.world.EnumSkyBlock", "SKY").defaultLightValue !== 15) {
          throw new Error("EnumSkyBlock defaultLightValue");
        }

        const mutable = r.create("net.minecraft.util.math.MutableBlockPos");
        mutable.setPos(4, 5, 6);
        if (mutable.getX() !== 4 || mutable.getY() !== 5 || mutable.getZ() !== 6) {
          throw new Error("MutableBlockPos.setPos");
        }
        const nestedMutable = r.create("net.minecraft.util.math.BlockPos$MutableBlockPos");
        nestedMutable.setPos(7, 8, 9);
        if (nestedMutable.getX() !== 7 || nestedMutable.getY() !== 8 || nestedMutable.getZ() !== 9) {
          throw new Error("BlockPos$MutableBlockPos.setPos");
        }
        const pooledMutable = r.create("net.minecraft.util.math.BlockPos$PooledMutableBlockPos");
        pooledMutable.setPos(10, 11, 12);
        if (pooledMutable.getX() !== 10 || pooledMutable.getY() !== 11 || pooledMutable.getZ() !== 12) {
          throw new Error("BlockPos$PooledMutableBlockPos.setPos");
        }
        const buffer = r.invokeStaticClass("org.lwjgl.BufferUtils", "createByteBuffer", 8);
        buffer.putInt(0x01020304);
        buffer.flip();
        if (buffer.remaining() !== 4 || buffer.position() !== 0) {
          throw new Error("BufferUtils.createByteBuffer position");
        }
        if (!buffer.bytes || buffer.bytes[0] !== 1 || buffer.bytes[3] !== 4) {
          throw new Error("BufferUtils.createByteBuffer data");
        }
        const miss = r.getStaticField("net.minecraft.util.math.RayTraceResult$Type", "MISS");
        const missResult = r.create("net.minecraft.util.math.RayTraceResult", miss);
        if (missResult.getTypeOfHit() !== miss) throw new Error("RayTraceResult MISS");
        const blockPos = r.create("net.minecraft.util.math.BlockPos", 1, 2, 3);
        const up = r.getStaticField("net.minecraft.util.EnumFacing", "UP");
        const hitVec = r.create("net.minecraft.util.math.Vec3d", 1, 2, 3);
        const blockResult = r.create(
            "net.minecraft.util.math.RayTraceResult", hitVec, up, blockPos);
        if (blockResult.typeOfHit.constantName !== "BLOCK" || blockResult.blockPos !== blockPos
            || blockResult.sideHit !== up || blockResult.hitVec !== hitVec) {
          throw new Error("RayTraceResult BLOCK");
        }
        const entityResult = r.create("net.minecraft.util.math.RayTraceResult", { id: "entity" });
        if (entityResult.typeOfHit.constantName !== "ENTITY" || entityResult.entityHit.id !== "entity") {
          throw new Error("RayTraceResult ENTITY");
        }
        if (r.getStaticField("net.minecraft.util.EnumActionResult", "SUCCESS").ordinal() !== 0) {
          throw new Error("EnumActionResult");
        }
        if (r.getStaticField("net.minecraft.entity.player.EnumPlayerModelParts", "ALL").getPartMask() !== 127) {
          throw new Error("EnumPlayerModelParts.ALL");
        }
        console.log("nbt-value-adapters-ok");
        """, StandardCharsets.UTF_8);
    Process node;
    try {
      node = new ProcessBuilder("node", runner.toString()).redirectErrorStream(true).start();
    } catch (Exception error) {
      throw new AssertionError("Node.js is required for runtime adapter tests", error);
    }
    assertTrue(node.waitFor(10, TimeUnit.SECONDS), "runtime NBT adapter test timed out");
    String outputText = new String(node.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
    assertEquals(0, node.exitValue(), outputText);
    assertEquals("nbt-value-adapters-ok", outputText.substring(outputText.lastIndexOf('\n') + 1));
  }
}
