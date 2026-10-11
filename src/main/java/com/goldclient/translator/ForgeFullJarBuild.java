package com.goldclient.translator;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import javax.tools.ToolProvider;

/** Full input JARs, no mod-class whitelist and no replacement lifecycle host. */
public final class ForgeFullJarBuild {
    public record HostLink(String implementation, Path jar) {}
    public record Build(Path bootstrapJar, Path compilerView, List<Path> runtimeDependencies,
                        String entryClass, boolean hostLinked) {}
    public static void main(String[] args) throws Exception {
        if(args.length<4)throw new IllegalArgumentException("Usage: ForgeFullJarBuild <derived-mod.jar> <at.jar> <runtime-evidence.properties> <output-dir> [--dependency jar] [--host-class name --host-jar jar] [--translate]");
        Path mod=Path.of(args[0]),at=Path.of(args[1]),evidence=Path.of(args[2]),output=Path.of(args[3]);
        List<Path> extra=new ArrayList<>();String hostClass=null;Path hostJar=null;boolean translate=false;
        for(int i=4;i<args.length;i++)switch(args[i]){
            case "--dependency" -> {if(++i>=args.length)throw new IllegalArgumentException("Missing dependency path");extra.add(Path.of(args[i]));}
            case "--host-class" -> {if(++i>=args.length)throw new IllegalArgumentException("Missing host class");hostClass=args[i];}
            case "--host-jar" -> {if(++i>=args.length)throw new IllegalArgumentException("Missing host JAR");hostJar=Path.of(args[i]);}
            case "--translate" -> translate=true;
            default -> throw new IllegalArgumentException("Unknown argument: "+args[i]);
        }
        if((hostClass==null)!=(hostJar==null))throw new IllegalArgumentException("Host class and host JAR must be supplied together");
        VerifiedForgeAccess.verify(mod,at);ForgeRuntimeEvidence.verify(evidence,at);
        var plan=ForgeJarBootstrap.inspect(mod,at,evidence);
        if(!plan.blockers().isEmpty())throw new IllegalStateException("Bootstrap preparation blocked: "+plan.blockers());
        Properties proof=new Properties();try(var stream=Files.newInputStream(Path.of(at.toRealPath().toString()+".provenance.properties"))){proof.load(stream);}
        List<Path> dependencies=new ArrayList<>();dependencies.add(at);
        List<Integer> indices=new ArrayList<>();
        for(String key:proof.stringPropertyNames())if(key.matches("runtime\\.[0-9]+\\.path"))indices.add(Integer.parseInt(key.split("\\.")[1]));
        Collections.sort(indices);
        for(int index:indices){
            String hash=proof.getProperty("runtime."+index+".sha256");
            if(hash.equals(proof.getProperty("target.sha256")))continue;
            dependencies.add(Path.of(proof.getProperty("runtime."+index+".path")));
        }
        dependencies.addAll(extra);
        Build built=compile(mod,plan.bootstrapSource(),dependencies,output,hostClass==null?null:new HostLink(hostClass,hostJar));
        System.out.println("Whole mod derivative supplied: "+mod.toRealPath());
        System.out.println("Compiled bootstrap JAR: "+built.bootstrapJar());
        System.out.println("Host linked: "+built.hostLinked());
        System.out.println("Build report: "+output.toAbsolutePath().resolve("full-jar-build.properties"));
        if(translate)translate(built,output.resolve("translated"));
        else if(!built.hostLinked())System.out.println("Next gate: real lifecycle-host implementation. No JavaScript translation or browser execution occurred.");
    }
    public static Build compile(Path modJar,String bootstrapSource,List<Path> suppliedDependencies,
                                Path outputDirectory,HostLink host)throws Exception {
        Path mod=modJar.toRealPath();ForgeProxyAccessJar.originalSource(mod);
        Path root=outputDirectory.toAbsolutePath().normalize();Files.createDirectories(root);
        Path bootstrapJar=root.resolve("original-mod-bootstrap.jar"),report=root.resolve("full-jar-build.properties");
        if(bootstrapJar.equals(mod))throw new IOException("Output must not overwrite input mod");
        Files.deleteIfExists(bootstrapJar);Files.deleteIfExists(report);
        Path sources=root.resolve("generated"),classes=root.resolve("bootstrap-classes");
        if(Files.exists(classes)){try(var paths=Files.walk(classes)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}}
        Files.createDirectories(classes);Files.createDirectories(sources.resolve("gold/generated"));
        Path source=sources.resolve("gold/generated/ForgeModBootstrap.java");Files.writeString(source,bootstrapSource);
        Path entry=sources.resolve("gold/generated/ForgeTranslationEntry.java");Files.deleteIfExists(entry);
        Path view=root.resolve("mod-compile-only.jar");ForgeProxyAccessJar.compilerView(mod,view);
        LinkedHashSet<Path> runtime=new LinkedHashSet<>();runtime.add(mod);
        for(Path p:suppliedDependencies)runtime.add(p.toRealPath());
        if(host!=null){
            if(host.implementation()==null||!host.implementation().matches("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)+")||host.implementation().contains("$"))throw new IOException("Unsupported host implementation class name");
            runtime.add(host.jar().toRealPath());
            Files.writeString(entry,"package gold.generated;\npublic final class ForgeTranslationEntry {\n"+
                    " public static void main(String[] args) throws Exception {\n"+
                    "  ForgeModBootstrap.bindHost(new "+host.implementation()+"());\n"+
                    "  ForgeModBootstrap.main(args);\n }\n}\n");
        }
        VerifiedForgeAccess.verifyClasspath(mod,new ArrayList<>(runtime).subList(1,runtime.size()));
        List<Path> compilerClasspath=new ArrayList<>();compilerClasspath.add(view);
        for(Path p:runtime)if(!p.equals(mod))compilerClasspath.add(p);
        String classpath=String.join(File.pathSeparator,compilerClasspath.stream().map(Path::toString).toList());
        List<String> arguments=new ArrayList<>(List.of("--release","8","-encoding","UTF-8","-classpath",classpath,"-d",classes.toString(),source.toString()));
        if(host!=null)arguments.add(entry.toString());
        var compiler=ToolProvider.getSystemJavaCompiler();if(compiler==null)throw new IOException("A JDK compiler is required");
        int result=compiler.run(null,null,null,arguments.toArray(String[]::new));
        if(result!=0)throw new IOException("Complete bootstrap compilation failed; no output JAR published");
        Path staged=Files.createTempFile(root,"bootstrap-stage-",".jar");
        try {
            try(var output=new JarOutputStream(Files.newOutputStream(staged));var paths=Files.walk(classes)){
                for(Path p:paths.filter(Files::isRegularFile).sorted().toList()){
                    JarEntry item=new JarEntry(classes.relativize(p).toString().replace('\\','/'));item.setTime(0L);output.putNextEntry(item);Files.copy(p,output);output.closeEntry();
                }
            }
            Files.move(staged,bootstrapJar,StandardCopyOption.REPLACE_EXISTING);
        }finally{Files.deleteIfExists(staged);}
        Properties properties=new Properties();properties.setProperty("format","gold-full-jar-build-v1");
        properties.setProperty("mod.inputScope","whole runtime derivative JAR; no class whitelist");
        properties.setProperty("host.linked",Boolean.toString(host!=null));
        properties.setProperty("entryClass",host==null?"not linked":"gold.generated.ForgeTranslationEntry");
        properties.setProperty("bootstrap.sha256",VerifiedForgeAccess.sha256(bootstrapJar));
        properties.setProperty("bootstrap.source.sha256",VerifiedForgeAccess.sha256(source));
        properties.setProperty("compilerView.runtimeUseAllowed","false");
        properties.setProperty("clientRuntimeVerified","false");properties.setProperty("fullModEquivalenceVerified","false");
        int index=0;for(Path p:runtime){properties.setProperty("runtime."+index+".path",p.toString());properties.setProperty("runtime."+index+".sha256",VerifiedForgeAccess.sha256(p));index++;}
        try(var stream=Files.newOutputStream(report)){properties.store(stream,"Full-JAR build inputs; not a browser execution certificate");}
        return new Build(bootstrapJar,view,List.copyOf(runtime),host==null?null:"gold.generated.ForgeTranslationEntry",host!=null);
    }
    public static void translate(Build build,Path output)throws TranslationException,IOException {
        if(!build.hostLinked()||build.entryClass()==null)throw new TranslationException("Refusing translation: real lifecycle host is not linked. Unbound bootstrap is not a runnable mod.");
        Set<String> prefixes=new HashSet<>();prefixes.add("gold.generated.");
        try(JarFile mod=new JarFile(build.runtimeDependencies().get(0).toFile(),false)){
            for(JarEntry e:Collections.list(mod.entries())){
                String name=e.getName();int slash=name.lastIndexOf('/');
                if(name.endsWith(".class")&&slash>=0&&!name.startsWith("java/")&&!name.startsWith("jdk/")&&!name.startsWith("sun/"))prefixes.add(name.substring(0,slash+1).replace('/','.'));
            }
        }
        TeaVmTranslator.translate(build.bootstrapJar(),output,build.entryClass(),prefixes,build.runtimeDependencies());
    }
}
