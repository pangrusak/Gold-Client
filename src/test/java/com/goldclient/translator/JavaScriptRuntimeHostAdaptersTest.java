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

        const calls = [];
        globalThis.goldClientForgeBindings = {
          virtualFileClass: FakeFile,
          forge: {
            construct(owner, args, receiver) {
              calls.push([owner, args.length, !!receiver]);
              if (!receiver) return { owner, args, initialized: true };
              receiver.platformOwner = owner;
              receiver.platformArgs = args;
              receiver.isCanceled = () => false;
              receiver.isCancelable = () => false;
              return receiver;
            }
          }
        };

        const file = runtime.createJava("java.io.File", "config", "sample.cfg");
        if (file.path !== "/config/sample.cfg") {
          throw new Error("java.io.File adapter path: " + file.path);
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
