package com.goldclient.translator;

import org.teavm.tooling.ConsoleTeaVMToolLog;
import org.teavm.tooling.TeaVMProblemRenderer;
import org.teavm.tooling.TeaVMTargetType;
import org.teavm.tooling.TeaVMTool;
import org.teavm.tooling.TeaVMToolException;
import org.teavm.vm.TeaVMOptimizationLevel;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;

public class TeaVmTranslator {

    public static void translate(
            Path jarPath, Path outputDir, String mainClass) {

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
            System.out.println("Warning: No main class provided.");
        }

        Path outputFile = outputDir.resolve("eagler-mod.js");

        try {
            Files.createDirectories(outputDir);

            URL[] urls = {jarPath.toUri().toURL()};

            try (URLClassLoader classLoader = new URLClassLoader(
                    urls, TeaVmTranslator.class.getClassLoader())) {

                tool.setClassLoader(classLoader);
                tool.generate();

                TeaVMProblemRenderer.describeProblems(
                        tool.getDependencyInfo().getCallGraph(),
                        tool.getProblemProvider(),
                        new ConsoleTeaVMToolLog(true)
                );

                if (!tool.getProblemProvider()
                        .getSevereProblems().isEmpty()) {
                    System.err.println(
                            "TeaVM compilation failed. "
                            + "See diagnostics above."
                    );
                    return;
                }

                if (!Files.isRegularFile(outputFile)
                        || Files.size(outputFile) == 0) {
                    System.err.println(
                            "Translation produced no JavaScript. "
                            + "Not a successful build."
                    );
                    return;
                }

                System.out.println(
                        "Generated JS: " + outputFile
                        + " (" + Files.size(outputFile) + " bytes)"
                );
            }

        } catch (TeaVMToolException | IOException e) {
            System.err.println(
                    "TeaVM compilation failed: " + e.getMessage()
            );
            e.printStackTrace();
        }
    }
}