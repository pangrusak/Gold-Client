package com.goldclient.translator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.net.*;
import java.util.*;
import java.util.jar.*;
import javax.tools.ToolProvider;
import static org.junit.jupiter.api.Assertions.*;
class ForgeStaticSubscriberBootstrapTest {
    @TempDir Path temp;
    private Path fixture(boolean serverOnly)throws Exception {
        Path root=temp.resolve("src"),classes=temp.resolve("classes");Files.createDirectories(classes);
        Map<String,String> sources=new LinkedHashMap<>();
        sources.put("net/minecraftforge/fml/relauncher/Side.java","package net.minecraftforge.fml.relauncher;public enum Side{CLIENT,SERVER}");
        sources.put("net/minecraftforge/fml/common/Mod.java","package net.minecraftforge.fml.common;import java.lang.annotation.*;import net.minecraftforge.fml.relauncher.Side;@Retention(RetentionPolicy.RUNTIME) public @interface Mod{String modid();@Retention(RetentionPolicy.RUNTIME) public @interface EventBusSubscriber{String modid() default \"\";Side[] value() default {Side.CLIENT,Side.SERVER};}}");
        sources.put("net/minecraftforge/fml/common/eventhandler/EventPriority.java","package net.minecraftforge.fml.common.eventhandler;public enum EventPriority{HIGHEST,HIGH,NORMAL,LOW,LOWEST}");
        sources.put("net/minecraftforge/fml/common/eventhandler/SubscribeEvent.java","package net.minecraftforge.fml.common.eventhandler;import java.lang.annotation.*;@Retention(RetentionPolicy.RUNTIME) public @interface SubscribeEvent{EventPriority priority() default EventPriority.NORMAL;boolean receiveCanceled() default false;}");
        sources.put("fixture/Example.java","package fixture;import net.minecraftforge.fml.common.Mod;@Mod(modid=\"example\") public class Example{}");
        sources.put("fixture/MouseEvent.java","package fixture;public class MouseEvent{}");
        sources.put("fixture/Subscriber.java","package fixture;import net.minecraftforge.fml.common.*;import net.minecraftforge.fml.common.eventhandler.*;import net.minecraftforge.fml.relauncher.*;@Mod.EventBusSubscriber("+(serverOnly?"value={Side.SERVER}":"modid=\"example\",value={Side.CLIENT}")+") public class Subscriber{public static Object last;@SubscribeEvent(priority=EventPriority.HIGHEST,receiveCanceled=true)public static void mouse(MouseEvent event){last=event;}}");
        List<String> args=new ArrayList<>(List.of("--release","8","-d",classes.toString()));for(var e:sources.entrySet()){Path f=root.resolve(e.getKey());Files.createDirectories(f.getParent());Files.writeString(f,e.getValue());args.add(f.toString());}
        assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,null,null,args.toArray(String[]::new)));
        Path jar=temp.resolve("mod.jar");try(var output=new JarOutputStream(Files.newOutputStream(jar));var paths=Files.walk(classes)){
            for(Path f:paths.filter(Files::isRegularFile).toList()){output.putNextEntry(new JarEntry(classes.relativize(f).toString().replace('\\','/')));Files.copy(f,output);output.closeEntry();}}
        return jar;
    }
    @Test void registersOriginalHandlerAndPreservesEventIdentityAndMetadata()throws Exception {
        Path jar=fixture(false);var plan=ForgeJarBootstrap.inspect(jar);assertTrue(plan.blockers().isEmpty(),plan.blockers().toString());
        Path source=temp.resolve("generated/gold/generated/ForgeModBootstrap.java"),compiled=temp.resolve("bootstrap-classes");Files.createDirectories(source.getParent());Files.createDirectories(compiled);Files.writeString(source,plan.bootstrapSource());
        assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,null,null,"--release","8","-classpath",jar.toString(),"-d",compiled.toString(),source.toString()));
        try(var loader=new URLClassLoader(new URL[]{compiled.toUri().toURL(),jar.toUri().toURL()},ClassLoader.getPlatformClassLoader())){
            Class<?> bootstrap=loader.loadClass("gold.generated.ForgeModBootstrap"),hostType=loader.loadClass("gold.generated.ForgeModBootstrap$Host"),invokerType=loader.loadClass("gold.generated.ForgeModBootstrap$SubscriberInvoker");
            Object event=loader.loadClass("fixture.MouseEvent").getConstructor().newInstance();List<String> registrations=new ArrayList<>();
            Object host=java.lang.reflect.Proxy.newProxyInstance(loader,new Class<?>[]{hostType},(p,m,a)->{
                if(m.getName().equals("registerStaticSubscriber")){
                    assertEquals("example",a[0]);assertEquals("fixture.Subscriber",((Class<?>)a[1]).getName());assertEquals(event.getClass(),a[2]);
                    assertEquals("mouse",a[3]);assertEquals("HIGHEST",a[4]);assertEquals(true,a[5]);registrations.add(a[3].toString());
                    invokerType.getMethod("invoke",Object.class).invoke(a[6],event);
                }return null;
            });
            bootstrap.getMethod("bindHost",hostType).invoke(null,host);bootstrap.getMethod("main",String[].class).invoke(null,(Object)new String[0]);
            assertEquals(List.of("mouse"),registrations);assertSame(event,loader.loadClass("fixture.Subscriber").getField("last").get(null));
        }
    }
    @Test void excludesServerOnlySubscriberFromClientBootstrap()throws Exception {
        var plan=ForgeJarBootstrap.inspect(fixture(true));assertTrue(plan.blockers().isEmpty(),plan.blockers().toString());
        assertFalse(plan.bootstrapSource().contains("fixture.Subscriber.class"));
    }
}
