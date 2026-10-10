package com.goldclient.translator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.net.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.jar.*;
import javax.tools.ToolProvider;
import static org.junit.jupiter.api.Assertions.*;
class ForgeClientProxyBootstrapTest {
    @TempDir Path temp;
    @Test void constructsOriginalClientProxyBeforeLifecycleHandler() throws Exception {
        Path sourceRoot = temp.resolve("src"), classes = temp.resolve("classes"); Files.createDirectories(classes);
        Map<String,String> sources = new LinkedHashMap<>();
        sources.put("net/minecraftforge/fml/common/Mod.java", "package net.minecraftforge.fml.common; import java.lang.annotation.*; @Retention(RetentionPolicy.RUNTIME) public @interface Mod { String modid(); @Retention(RetentionPolicy.RUNTIME) public @interface EventHandler {} }");
        sources.put("net/minecraftforge/fml/common/SidedProxy.java", "package net.minecraftforge.fml.common; import java.lang.annotation.*; @Retention(RetentionPolicy.RUNTIME) public @interface SidedProxy { String clientSide(); String serverSide(); String modId() default \"\"; }");
        sources.put("net/minecraftforge/fml/common/event/FMLInitializationEvent.java", "package net.minecraftforge.fml.common.event; public class FMLInitializationEvent {}");
        sources.put("fixture/CommonProxy.java", "package fixture; public class CommonProxy {}");
        sources.put("fixture/ClientProxy.java", "package fixture; public class ClientProxy extends CommonProxy { public static int count; public ClientProxy(){count++;} }");
        sources.put("fixture/ServerProxy.java", "package fixture; public class ServerProxy extends CommonProxy { public ServerProxy(){throw new RuntimeException(\"Server proxy must not execute\");} }");
        sources.put("fixture/Example.java", "package fixture; import net.minecraftforge.fml.common.*; import net.minecraftforge.fml.common.event.*; @Mod(modid=\"example\") public class Example { @SidedProxy(clientSide=\"fixture.ClientProxy\",serverSide=\"fixture.ServerProxy\") public static CommonProxy proxy; public static boolean handled; @Mod.EventHandler public void init(FMLInitializationEvent e){ if(!(proxy instanceof ClientProxy))throw new RuntimeException(\"Proxy not injected\"); handled=true;} }");
        List<String> args = new ArrayList<>(List.of("--release","8","-d",classes.toString()));
        for(var entry:sources.entrySet()){Path file=sourceRoot.resolve(entry.getKey()); Files.createDirectories(file.getParent()); Files.writeString(file,entry.getValue());args.add(file.toString());}
        assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,null,null,args.toArray(String[]::new)));
        Path jar=temp.resolve("mod.jar");
        try(var output=new JarOutputStream(Files.newOutputStream(jar));var paths=Files.walk(classes)){
            for(Path file:paths.filter(Files::isRegularFile).sorted().toList()){output.putNextEntry(new JarEntry(classes.relativize(file).toString().replace('\\','/')));Files.copy(file,output);output.closeEntry();}
        }
        var plan=ForgeJarBootstrap.inspect(jar);assertTrue(plan.blockers().isEmpty(),plan.blockers().toString());
        Path generated=temp.resolve("generated/gold/generated/ForgeModBootstrap.java"),compiled=temp.resolve("compiled");
        Files.createDirectories(generated.getParent());Files.createDirectories(compiled);Files.writeString(generated,plan.bootstrapSource());
        assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,null,null,"--release","8","-classpath",jar.toString(),"-d",compiled.toString(),generated.toString()));
        try(var loader=new URLClassLoader(new URL[]{compiled.toUri().toURL(),jar.toUri().toURL()},ClassLoader.getPlatformClassLoader())){
            Class<?> bootstrap=loader.loadClass("gold.generated.ForgeModBootstrap"),hostType=loader.loadClass("gold.generated.ForgeModBootstrap$Host");
            Object event=loader.loadClass("net.minecraftforge.fml.common.event.FMLInitializationEvent").getConstructor().newInstance();
            Object host=java.lang.reflect.Proxy.newProxyInstance(loader,new Class<?>[]{hostType},(p,m,a)->m.getName().equals("eventFor")?event:null);
            bootstrap.getMethod("bindHost",hostType).invoke(null,host);bootstrap.getMethod("main",String[].class).invoke(null,(Object)new String[0]);
            assertEquals(true,loader.loadClass("fixture.Example").getField("handled").get(null));
            assertEquals(1,loader.loadClass("fixture.ClientProxy").getField("count").get(null));
            assertEquals("fixture.ClientProxy",loader.loadClass("fixture.Example").getField("proxy").get(null).getClass().getName());
        }
    }
}
