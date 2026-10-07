package com.goldclient.translator;

import org.teavm.tooling.ConsoleTeaVMToolLog;
import org.teavm.tooling.TeaVMProblemRenderer;
import org.teavm.tooling.TeaVMTargetType;
import org.teavm.tooling.TeaVMTool;
import org.teavm.tooling.TeaVMToolException;
import org.teavm.vm.TeaVMOptimizationLevel;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Translates a JAR file to JavaScript via TeaVM.
 *
 * <p>Usage:</p>
 * <pre>
 *   TeaVmTranslator.translate(jarPath, outputDir, teaVmEntryClass);
 * </pre>
 *
 * <p>The {@code teaVmEntryClass} must be a class that contains a
 * {@code public static void main(String[])} method.  It is the caller's
 * responsibility to supply a valid TeaVM entry class.  This class does
 * NOT automatically promote a Forge {@code @Mod} class or a Fabric
 * initializer to a TeaVM entry — they do not have a {@code main} method
 * and are therefore not valid TeaVM entry points.</p>
 *
 * <p>On severe TeaVM diagnostics this method throws a
 * {@link TranslationException} so that the caller can propagate a
 * non-zero exit code.</p>
 */
public class TeaVmTranslator {

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Translate {@code jarPath} to JavaScript, placing output in
     * {@code outputDir}.
     *
     * @param jarPath        path to the input JAR
     * @param outputDir      directory where {@code eagler-mod.js} will be
     *                       written (created if absent)
     * @param teaVmEntryClass dotted class name that contains
     *                       {@code public static void main(String[])};
     *                       must not be null or blank
     * @throws TranslationException if the entry class is missing/blank, if
     *                              TeaVM reports severe problems, or if the
     *                              output is absent/empty after generation
     * @throws IOException          if the JAR cannot be read or output cannot
     *                              be written
     */
    public static void translate(Path jarPath, Path outputDir,
                                 String teaVmEntryClass)
            throws TranslationException, IOException {

        // --- validate entry class (milestone requirement: fail explicitly) ---
        if (teaVmEntryClass == null || teaVmEntryClass.isBlank()) {
            throw new TranslationException(
                    "No TeaVM entry class specified. "
                    + "Provide a class with public static void main(String[]) "
                    + "via --main-class <ClassName>. "
                    + "A Forge @Mod class or Fabric initializer is NOT a valid "
                    + "TeaVM entry point.");
        }
        // normalise slashes that may come from class-file names
        String entryClass = teaVmEntryClass.replace('/', '.');

        System.out.println("\n=== TeaVM Translation ===");
        System.out.println("Input : " + jarPath.toAbsolutePath());
        System.out.println("Entry : " + entryClass);
        System.out.println("Output: " + outputDir.toAbsolutePath());

        Files.createDirectories(outputDir);

        // Stale-artifact prevention: delete any existing JS before starting
        Path outputJs = outputDir.resolve("eagler-mod.js");
        Files.deleteIfExists(outputJs);

        URL[] urls = {jarPath.toUri().toURL()};
        try (URLClassLoader classLoader =
                     new URLClassLoader(urls, TeaVmTranslator.class.getClassLoader())) {

            TeaVMTool tool = new TeaVMTool();
            tool.setTargetDirectory(outputDir.toFile());
            tool.setTargetFileName("eagler-mod.js");
            tool.setTargetType(TeaVMTargetType.JAVASCRIPT);
            tool.setOptimizationLevel(TeaVMOptimizationLevel.SIMPLE);
            tool.setDebugInformationGenerated(false);
            tool.setObfuscated(false);
            tool.setClassLoader(classLoader);
            tool.setMainClass(entryClass);

            // --- compile ---
            System.out.println("[TeaVM] Compiling...");
            try {
                tool.generate();
            } catch (TeaVMToolException e) {
                throw new TranslationException("TeaVM tool error: " + e.getMessage(), e);
            }

            // --- diagnostics ---
            ConsoleTeaVMToolLog log = new ConsoleTeaVMToolLog(true);
            TeaVMProblemRenderer.describeProblems(
                    tool.getDependencyInfo().getCallGraph(),
                    tool.getProblemProvider(),
                    log);

            int severeCount = tool.getProblemProvider().getSevereProblems().size();
            if (severeCount > 0) {
                throw new TranslationException(
                        "TeaVM compilation finished with " + severeCount
                        + " severe problem(s). See diagnostics above.");
            }

            // --- verify output ---
            if (!Files.isRegularFile(outputJs) || Files.size(outputJs) == 0) {
                throw new TranslationException(
                        "Translation produced no JavaScript output "
                        + "(file missing or empty). "
                        + "This usually means the entry class could not be resolved.");
            }

            System.out.println("[TeaVM] Compilation succeeded.");
            System.out.println("[TeaVM] JS output: " + outputJs
                    + " (" + Files.size(outputJs) + " bytes)");

            // --- copy non-class resources (assets, JSON, etc.) ---
            copyResources(jarPath, outputDir, outputJs);
        }
    }

    // -------------------------------------------------------------------------
    // Resource copying
    // -------------------------------------------------------------------------

    private static void copyResources(Path jarPath, Path outputDir,
                                      Path reservedJs) throws IOException {
        Path normalRoot = outputDir.toRealPath();
        long bytesCopied = 0L;
        int filesCopied = 0;

        try (ZipFile zip = new ZipFile(jarPath.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();

                // skip class files and directory entries
                if (entry.isDirectory() || name.endsWith(".class")) continue;

                // --- path traversal guard ---
                Path dest = outputDir.resolve(name).normalize();
                if (!dest.startsWith(normalRoot)) {
                    System.err.println("[resources] Rejected traversal entry: " + name);
                    continue;
                }

                // do not overwrite the generated JS
                if (dest.equals(reservedJs)) {
                    System.err.println("[resources] Skipped entry that would overwrite output JS: " + name);
                    continue;
                }

                Files.createDirectories(dest.getParent());
                long written;
                try (InputStream is = zip.getInputStream(entry)) {
                    written = Files.copy(is, dest, StandardCopyOption.REPLACE_EXISTING);
                }
                bytesCopied += written;
                filesCopied++;
            }
        }
        System.out.println("[resources] Copied " + filesCopied
                + " asset file(s) (" + bytesCopied + " bytes).");
    }
}