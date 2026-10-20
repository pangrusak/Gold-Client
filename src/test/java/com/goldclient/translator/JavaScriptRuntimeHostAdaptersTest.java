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
}
