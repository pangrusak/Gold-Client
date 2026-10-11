package com.goldclient.translator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.net.*;
import java.util.*;
import java.util.jar.*;
import javax.tools.ToolProvider;
import static org.junit.jupiter.api.Assertions.*;
class ForgeFullJarBuildTest {
    @TempDir Path temp;
    private Path fixture()throws Exception {
        Path root=temp.resolve("src"),classes=temp.resolve("classes");Files.createDirectories(classes);
        Map<String,String> sources=Map.of(
          "net/minecraftforge/fml/common/Mod.java","package net.minecraftforge.fml.common;import java.lang.annotation.*;@Retention(RetentionPolicy.RUNTIME) public @interface Mod{String modid();}",
          "fixture/Example.java","package fixture;import net.minecraftforge.fml.common.Mod;@Mod(modid=\"example\") public class Example{public static int executions;public Example(){executions++;}}",
          "fixture/Unused.java","package fixture;public class Unused{public static String value(){return \"NOT_WHITELISTED_OUT\";}}");
        List<String> args=new ArrayList<>(List.of("--release","8","-d",classes.toString()));
        for(var e:sources.entrySet()){Path p=root.resolve(e.getKey());Files.createDirectories(p.getParent());Files.writeString(p,e.getValue());args.add(p.toString());}
        assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,null,null,args.toArray(String[]::new)));
        Path original=temp.resolve("original.jar");jar(classes,original);Path derived=temp.resolve("derived.jar");ForgeProxyAccessJar.prepare(original,derived);return derived;
    }
    private void jar(Path classes,Path file)throws Exception {
        try(var output=new JarOutputStream(Files.newOutputStream(file));var paths=Files.walk(classes)){
            for(Path p:paths.filter(Files::isRegularFile).sorted().toList()){output.putNextEntry(new JarEntry(classes.relativize(p).toString().replace('\\','/')));Files.copy(p,output);output.closeEntry();}}
    }
    @Test void retainsWholeModAndRefusesUnboundTranslation()throws Exception {
        Path mod=fixture();byte[] original=Files.readAllBytes(mod);var plan=ForgeJarBootstrap.inspect(mod);
        var build=ForgeFullJarBuild.compile(mod,plan.bootstrapSource(),List.of(),temp.resolve("unbound"),null);
        assertFalse(build.hostLinked());assertArrayEquals(original,Files.readAllBytes(mod));
        try(var jar=new JarFile(mod.toFile())){assertNotNull(jar.getEntry("fixture/Unused.class"));}
        assertThrows(TranslationException.class,()->ForgeFullJarBuild.translate(build,temp.resolve("refused-js")));
        assertFalse(Files.exists(temp.resolve("refused-js/eagler-mod.js")));
    }
    @Test void linksExplicitHostAndRunsOriginalConstructionOnJvm()throws Exception {
        Path mod=fixture();String bootstrap=ForgeJarBootstrap.inspect(mod).bootstrapSource();
        var first=ForgeFullJarBuild.compile(mod,bootstrap,List.of(),temp.resolve("host-api"),null);
        Path source=temp.resolve("HostImpl.java"),classes=temp.resolve("host-classes");Files.createDirectories(classes);
        Files.writeString(source,"package fixture;public class HostImpl implements gold.generated.ForgeModBootstrap.Host {"+
            "public void beforeConstruction(String id){if(!id.equals(\"example\"))throw new RuntimeException();}"+
            "public Object eventFor(String id,String phase){throw new UnsupportedOperationException();}"+
            "public void afterPhase(String id,String phase){}"+
            "public void registerStaticSubscriber(String id,Class<?> owner,Class<?> event,String name,String priority,boolean canceled,gold.generated.ForgeModBootstrap.SubscriberInvoker invoke){throw new UnsupportedOperationException();}}");
        assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,null,null,"--release","8","-classpath",first.bootstrapJar().toString(),"-d",classes.toString(),source.toString()));
        Path host=temp.resolve("host.jar");jar(classes,host);
        var build=ForgeFullJarBuild.compile(mod,bootstrap,List.of(),temp.resolve("linked"),new ForgeFullJarBuild.HostLink("fixture.HostImpl",host));
        assertTrue(build.hostLinked());
        try(var loader=new URLClassLoader(new URL[]{build.bootstrapJar().toUri().toURL(),mod.toUri().toURL(),host.toUri().toURL()},ClassLoader.getPlatformClassLoader())){
            loader.loadClass(build.entryClass()).getMethod("main",String[].class).invoke(null,(Object)new String[0]);
            assertEquals(1,loader.loadClass("fixture.Example").getField("executions").get(null));
        }
    }
    @Test void rejectsCompileViewAsRuntimeDependency()throws Exception {
        Path mod=fixture(),view=temp.resolve("view.jar");ForgeProxyAccessJar.compilerView(mod,view);
        assertThrows(java.io.IOException.class,()->ForgeFullJarBuild.compile(mod,ForgeJarBootstrap.inspect(mod).bootstrapSource(),List.of(view),temp.resolve("invalid"),null));
    }
}
