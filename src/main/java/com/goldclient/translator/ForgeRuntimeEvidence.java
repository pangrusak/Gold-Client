package com.goldclient.translator;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;

/** Uses version APIs from the exact original Forge runtime recorded by AT execution. */
public final class ForgeRuntimeEvidence {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Usage: ForgeRuntimeEvidence <verified-at.jar> <evidence.properties>");
        write(Path.of(args[0]), Path.of(args[1]));
    }
    private record View(URLClassLoader loader, String forge, String minecraft) implements AutoCloseable {
        public void close() throws IOException { loader.close(); }
        boolean contains(String spec) throws Exception {
            Class<?> rangeType=loader.loadClass("net.minecraftforge.fml.common.versioning.VersionRange");
            Class<?> versionType=loader.loadClass("net.minecraftforge.fml.common.versioning.DefaultArtifactVersion");
            Class<?> artifactType=loader.loadClass("net.minecraftforge.fml.common.versioning.ArtifactVersion");
            Object range=rangeType.getMethod("createFromVersionSpec",String.class).invoke(null,spec);
            Object version=versionType.getConstructor(String.class).newInstance(forge);
            return (Boolean)rangeType.getMethod("containsVersion",artifactType).invoke(range,version);
        }
    }
    private static Properties load(Path file) throws IOException {
        Properties p=new Properties();try(var stream=Files.newInputStream(file)){p.load(stream);}return p;
    }
    private static View open(Properties p) throws Exception {
        List<Integer> indices=new ArrayList<>();
        for(String key:p.stringPropertyNames())if(key.matches("runtime\\.[0-9]+\\.path"))indices.add(Integer.parseInt(key.split("\\.")[1]));
        Collections.sort(indices);if(indices.isEmpty())throw new IOException("Original runtime classpath missing");
        List<URL> urls=new ArrayList<>();
        for(int index:indices){
            Path path=Path.of(p.getProperty("runtime."+index+".path"));
            if(!VerifiedForgeAccess.sha256(path).equals(p.getProperty("runtime."+index+".sha256")))throw new IOException("Runtime dependency hash changed: "+path);
            urls.add(path.toRealPath().toUri().toURL());
        }
        URLClassLoader loader=new URLClassLoader(urls.toArray(URL[]::new),ClassLoader.getPlatformClassLoader());
        try {
            Class<?> version=loader.loadClass("net.minecraftforge.common.ForgeVersion");
            String forge=Objects.toString(version.getMethod("getVersion").invoke(null));
            String minecraft=Objects.toString(version.getField("mcVersion").get(null));
            if(!forge.matches("[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+")||!minecraft.equals("1.12.2"))throw new IOException("Unsupported original runtime version: "+minecraft+" / "+forge);
            return new View(loader,forge,minecraft);
        } catch(Exception error){loader.close();throw error;}
    }
    public static void write(Path atJar,Path destination)throws Exception {
        Path target=atJar.toRealPath();Properties at=load(Path.of(target.toString()+".provenance.properties"));
        if(!"net.minecraftforge.fml.common.asm.transformers.AccessTransformer".equals(at.getProperty("engine"))||
                !VerifiedForgeAccess.sha256(target).equals(at.getProperty("output.sha256")))throw new IOException("AT output provenance mismatch");
        Path output=destination.toAbsolutePath().normalize();
        if(output.equals(target))throw new IOException("Evidence cannot overwrite target JAR");
        Properties p=new Properties();
        for(String key:at.stringPropertyNames())if(key.startsWith("runtime."))p.setProperty(key,at.getProperty(key));
        try(View view=open(p)){
            p.setProperty("format","gold-original-forge-runtime-v1");p.setProperty("forge.version",view.forge());
            p.setProperty("minecraft.version",view.minecraft());p.setProperty("version.source","original ForgeVersion.getVersion");
            p.setProperty("access.path",target.toString());p.setProperty("access.sha256",VerifiedForgeAccess.sha256(target));
            p.setProperty("clientRuntimeImplemented","false");
            Files.createDirectories(output.getParent());try(var stream=Files.newOutputStream(output)){p.store(stream,"Build-time original runtime evidence; not browser compatibility");}
            System.out.println("Original runtime: Minecraft "+view.minecraft()+", Forge "+view.forge());
        }
    }
    public static String verify(Path evidence,Path expectedAt)throws IOException {
        try {
            Properties p=load(evidence);
            if(!"gold-original-forge-runtime-v1".equals(p.getProperty("format")))throw new IOException("Unknown runtime evidence format");
            if(expectedAt==null||!expectedAt.toRealPath().equals(Path.of(p.getProperty("access.path")).toRealPath())||
                    !VerifiedForgeAccess.sha256(expectedAt).equals(p.getProperty("access.sha256")))throw new IOException("Runtime evidence does not match supplied AT artifact");
            try(View view=open(p)){
                if(!view.forge().equals(p.getProperty("forge.version"))||!view.minecraft().equals(p.getProperty("minecraft.version")))throw new IOException("Recorded version differs from original runtime");
                return view.forge();
            }
        }catch(IOException e){throw e;}catch(Exception e){throw new IOException("Original Forge version inspection failed",e);}
    }
    static boolean contains(Path evidence,Path atJar,String range)throws IOException {
        verify(evidence,atJar);
        try(View view=open(load(evidence))){return view.contains(range);}catch(Exception e){throw new IOException("Original Forge version-range evaluation failed: "+range,e);}
    }
    static void dependencies(String declarations,Path evidence,Path atJar,List<String> blockers)throws IOException {
        for(String clause:declarations.split(";")){
            clause=clause.trim();if(clause.isEmpty())continue;
            var match=java.util.regex.Pattern.compile("^(required-after|after):forge(?:@(.+))?$").matcher(clause);
            if(!match.matches()){blockers.add("Dependency/version ordering not implemented: "+clause);continue;}
            if(evidence==null){blockers.add("Original Forge runtime evidence required: "+clause);continue;}
            verify(evidence,atJar);String range=match.group(2);
            if(range!=null&&!contains(evidence,atJar,range))blockers.add("Original Forge runtime version does not satisfy: "+clause);
        }
    }
}
