package com.goldclient;

import com.goldclient.analyzer.*;
import com.goldclient.model.ClassModel;
import com.goldclient.model.InstructionModel;
import com.goldclient.model.MethodModel;
import com.goldclient.model.TryCatchModel;
import java.nio.file.Path;
import java.util.List;

public final class Main {
  public static void main(String[] args) {
    if (args.length < 1 || args.length > 5) {
      printUsage();
      System.exit(2);
    }

    String jar = args[0];
    String classFilter = null;
    String methodFilter = null;

    for (int i = 1; i < args.length; i++) {
      switch (args[i]) {
        case "--class" -> {
          if (++i >= args.length) {
            printUsage();
            System.exit(2);
          }
          classFilter = args[i].replace('.', '/');
        }
        case "--method" -> {
          if (++i >= args.length) {
            printUsage();
            System.exit(2);
          }
          methodFilter = args[i];
        }
        default -> {
          printUsage();
          System.exit(2);
        }
      }
    }

    try {
      ModAnalysis a = new ModAnalyzer().analyze(Path.of(jar));
      printAnalysis(a);

      if (classFilter != null || methodFilter != null)
        printBytecode(a.classes(), classFilter, methodFilter);
    } catch(Exception e) {
      System.err.println("Gold Client analysis failed: " + e.getMessage());
      System.exit(1);
    }
  }

  private static void printUsage() {
    System.err.println("Usage: java -jar gold-client.jar <mod.jar> [--class <class>] [--method <method>]");
  }

  private static void printAnalysis(ModAnalysis a) {
    System.out.println("=== Gold Client Mod Analyzer ===\n");
    System.out.println("Mod: " + a.metadata().getName());
    System.out.println("Version: " + a.metadata().getVersion());
    System.out.println("Minecraft: " + a.metadata().getMinecraftVersion());
    System.out.println("Loader: " + a.metadata().getLoader());
    System.out.println("\nClasses: " + a.totalClasses());
    System.out.println("Methods: " + a.totalMethods());
    System.out.println("Fields: " + a.totalFields());
    printList("Dependencies", a.metadata().getDependencies());
    printList("Minecraft APIs", a.minecraftApis());
    printList("Mixins", a.metadata().getMixins());
    printList("Entrypoints", a.metadata().getEntrypoints());
    System.out.println("\nClasses:");
    for (ClassModel i : a.classes())
      System.out.printf("  %s (%d methods, %d fields)%n",
          i.name(), i.methods().size(), i.fields().size());
  }

  private static void printBytecode(
      List<ClassModel> classes, String classFilter, String methodFilter) {
    System.out.println("\n=== Bytecode Debug ===");

    int matchedClasses = 0;
    int matchedMethods = 0;

    for (ClassModel clazz : classes) {
      if (classFilter != null && !clazz.name().equals(classFilter))
        continue;

      matchedClasses++;
      System.out.println("\nClass: " + clazz.name());

      for (MethodModel method : clazz.methods()) {
        if (methodFilter != null && !method.name().equals(methodFilter))
          continue;

        matchedMethods++;
        System.out.printf("  Method: %s%s%n", method.name(), method.descriptor());
        System.out.printf("    Instructions: %d%n", method.instructions().size());
        System.out.printf("    Max stack: %d, Max locals: %d%n",
            method.maxStack(), method.maxLocals());

        for (int i = 0; i < method.instructions().size(); i++) {
          InstructionModel instruction = method.instructions().get(i);
          System.out.printf("    %04d: %s%n", i, formatInstruction(instruction));
        }

        if (!method.tryCatchBlocks().isEmpty()) {
          System.out.println("    Try/catch:");
          for (TryCatchModel block : method.tryCatchBlocks()) {
            System.out.printf("      %s -> %s -> %s (%s)%n",
                block.startLabel(), block.endLabel(),
                block.handlerLabel(), block.exceptionType());
          }
        }
      }
    }

    if (matchedClasses == 0)
      System.out.println("\nNo classes matched the requested filter.");
    else if (matchedMethods == 0)
      System.out.println("\nNo methods matched the requested filter.");
  }

  private static String formatInstruction(InstructionModel instruction) {
    if (instruction.operands().isEmpty())
      return instruction.opcodeName();

    return instruction.opcodeName() + " " + String.join(", ", instruction.operands());
  }

  private static void printList(String title, List<String> values) {
    System.out.println("\n" + title + ":");
    if (values.isEmpty()) {
      System.out.println("  (none detected)");
      return;
    }
    values.forEach(v -> System.out.println("  " + v));
  }
}
