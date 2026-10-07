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
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.jar.Attributes;

public class TeaVmTranslator {

    // ---------------------------------------------------------------------
    // Generic overload that works for any JAR (including test-mods).
    // It attempts to read the JAR manifest's Main-Class attribute. If found,
    // that class is used as the entry point; otherwise we fall back to a
    // safe default (no main class) which still preserves all classes.
    // ---------------------------------------------------------------------
    /**
     * Translate a JAR to JavaScript, automatically determining the main class
     * from the JAR’s manifest if present. This makes the translator usable with
     * arbitrary mods, including the ones placed in the <code>/test-mods</code>
     * directory.
     *
     * @param jarPath   Path to the input JAR file.
     * @param outputDir Directory where the translated files will be written.
     */
    public static void translate(Path jarPath, Path outputDir) {
        String inferredMain = null;
        try (java.util.jar.JarFile jarFile = new java.util.jar.JarFile(jarPath.toFile())) {
            java.util.jar.Manifest manifest = jarFile.getManifest();
            if (manifest != null) {
                java.util.jar.Attributes attrs = manifest.getMainAttributes();
                inferredMain = attrs.getValue(java.util.jar.Attributes.Name.MAIN_CLASS);
            }
        } catch (IOException e) {
            System.err.println("Failed to read JAR manifest: " + e.getMessage());
        }
        // Forward to the original method. If we couldn't infer a main class we
        // simply pass {@code null} – the existing method will handle it.
        translate(jarPath, outputDir, inferredMain);
    }


    public static void translate(
            Path jarPath, Path outputDir, String mainClass) {

        System.out.println("\n=== TeaVM Translation ===");
        System.out.println("Translating: " + jarPath.getFileName());

        TeaVMTool tool = new TeaVMTool();
        tool.setTargetDirectory(outputDir.toFile());
        tool.setTargetFileName("eagler-mod.js");
        tool.setTargetType(TeaVMTargetType.JAVASCRIPT);
        tool.setOptimizationLevel(TeaVMOptimizationLevel.SIMPLE);
        tool.setDebugInformationGenerated(true);
        tool.setObfuscated(false);

        Path outputFile = outputDir.resolve("eagler-mod.js");

        try {
            Files.createDirectories(outputDir);

            URL[] urls = {jarPath.toUri().toURL()};

            try (URLClassLoader classLoader = new URLClassLoader(
                    urls, TeaVmTranslator.class.getClassLoader())) {

                tool.setClassLoader(classLoader);

                if (mainClass != null && !mainClass.isEmpty()) {
                    tool.setMainClass(mainClass);
                    System.out.println("Main class: " + mainClass);
                }

                try (ZipFile zipFile = new ZipFile(jarPath.toFile())) {
                    Enumeration<? extends ZipEntry> entries = zipFile.entries();
                    int added = 0;
                    while (entries.hasMoreElements()) {
                        ZipEntry entry = entries.nextElement();
                        String name = entry.getName();
                        if (name.endsWith(".class") && !entry.isDirectory()) {
                            String className = name.substring(0, name.length() - 6)
                                    .replace('/', '.');
                            try {
                                tool.getClassesToPreserve().add(className);
                                added++;
                            } catch (Exception ignored) {
                            }
                        }
                    }
                    System.out.println("Added " + added + " class entry points");
                }

                tool.generate();
                // Copy non-class resources (e.g., images, JSON, assets, etc.) from the JAR to the output directory
                try (java.util.zip.ZipFile resourceZip = new java.util.zip.ZipFile(jarPath.toFile())) {
                    java.util.Enumeration<? extends java.util.zip.ZipEntry> resEntries = resourceZip.entries();
                    long totalBytesCopied = 0L;
                    while (resEntries.hasMoreElements()) {
                        java.util.zip.ZipEntry resEntry = resEntries.nextElement();
                        String resName = resEntry.getName();
                        // Skip class files and directories
                        if (resEntry.isDirectory() || resName.endsWith(".class")) {
                            continue;
                        }
                        java.nio.file.Path outPath = outputDir.resolve(resName);
                        java.nio.file.Files.createDirectories(outPath.getParent());
                        try (java.io.InputStream is = resourceZip.getInputStream(resEntry)) {
                            java.nio.file.Files.copy(is, outPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                            totalBytesCopied += resEntry.getSize();
                        }
                    }
                    System.out.println("Copied non-class resources (" + totalBytesCopied + " bytes).");
                } catch (java.io.IOException e) {
                    System.err.println("Failed to copy resources: " + e.getMessage());
                }

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