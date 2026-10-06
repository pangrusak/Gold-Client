package com.goldclient.translator;

import org.teavm.tooling.TeaVMTargetType;
import org.teavm.tooling.TeaVMTool;
import org.teavm.tooling.TeaVMToolException;
import org.teavm.vm.TeaVMOptimizationLevel;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;

public class TeaVmTranslator {

    public static void translate(Path jarPath, Path outputDir, String mainClass) {
        System.out.println("\n=== TeaVM Translation ===");
        System.out.println("Translating: " + jarPath.getFileName());
        
        TeaVMTool tool = new TeaVMTool();
        tool.setTargetDirectory(outputDir.toFile());
        tool.setTargetFileName("eagler-mod.js");
        tool.setTargetType(TeaVMTargetType.JAVASCRIPT);
        tool.setOptimizationLevel(TeaVMOptimizationLevel.SIMPLE);
        
        if (mainClass != null && !mainClass.isEmpty()) {
            tool.setMainClass(mainClass);
            System.out.println("Main class: " + mainClass);
        } else {
            System.out.println("Warning: No main class provided. TeaVM may not include entrypoints.");
        }

        try {
            URL[] urls = new URL[]{jarPath.toUri().toURL()};
            // Use URLClassLoader to allow TeaVM to see the classes inside the target JAR
            URLClassLoader classLoader = new URLClassLoader(urls, TeaVmTranslator.class.getClassLoader());
            tool.setClassLoader(classLoader);
            
            tool.generate();
            System.out.println("Success! Generated JS at: " + outputDir.resolve("eagler-mod.js"));
            
        } catch (TeaVMToolException | MalformedURLException e) {
            System.err.println("TeaVM compilation failed: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
