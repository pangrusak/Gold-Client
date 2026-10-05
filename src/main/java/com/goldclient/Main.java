package com.goldclient;

import com.goldclient.analyzer.*;
import java.nio.file.Path;

public final class Main {
  public static void main(String[] args) {
    if (args.length != 1) { System.err.println("Usage: java -jar gold-client.jar <mod.jar>"); System.exit(2); }
    try {
      ModAnalysis a = new ModAnalyzer().analyze(Path.of(args[0]));
      System.out.println("=== Gold Client Mod Analyzer ===\n");
      System.out.println("Mod: "+a.metadata().getName());
      System.out.println("Version: "+a.metadata().getVersion());
      System.out.println("Minecraft: "+a.metadata().getMinecraftVersion());
      System.out.println("Loader: "+a.metadata().getLoader());
      System.out.println("\nClasses: "+a.totalClasses());
      System.out.println("Methods: "+a.totalMethods());
      System.out.println("Fields: "+a.totalFields());
      printList("Dependencies",a.metadata().getDependencies());
      printList("Minecraft APIs",a.minecraftApis());
      printList("Mixins",a.metadata().getMixins());
      printList("Entrypoints",a.metadata().getEntrypoints());
      System.out.println("\nClasses:");
      for (ClassInfo i:a.classes()) System.out.printf("  %s (%d methods, %d fields)%n",i.name(),i.methods(),i.fields());
    } catch(Exception e) { System.err.println("Gold Client analysis failed: "+e.getMessage()); System.exit(1); }
  }
  private static void printList(String title, java.util.List<String> values) {
    System.out.println("\n"+title+":");
    if(values.isEmpty()){System.out.println("  (none detected)");return;}
    values.forEach(v->System.out.println("  "+v));
  }
}
