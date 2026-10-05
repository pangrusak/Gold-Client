package com.goldclient.analyzer;

import com.google.gson.*;
import org.objectweb.asm.*;
import java.io.*; import java.nio.file.*; import java.util.*; import java.util.jar.*;
import java.util.regex.*;

public final class ModAnalyzer {
  private static final Pattern MINECRAFT_CLASS = Pattern.compile("(?:^|[^A-Za-z0-9_])(?:L)?(net/minecraft/[A-Za-z0-9_$/]+)");

  public ModAnalysis analyze(Path path) throws IOException {
    if(!Files.isRegularFile(path)) throw new IOException("JAR does not exist: "+path);
    ModMetadata metadata=new ModMetadata(); List<ClassInfo> classes=new ArrayList<>(); Set<String> apis=new LinkedHashSet<>();
    try(JarFile jar=new JarFile(path.toFile())) {
      readMetadata(jar,metadata);
      Enumeration<JarEntry> es=jar.entries();
      while(es.hasMoreElements()){JarEntry e=es.nextElement(); if(e.isDirectory()||!e.getName().endsWith(".class"))continue;
        try(InputStream in=jar.getInputStream(e)){ClassReader r=new ClassReader(in); Stats s=inspect(r,apis); classes.add(new ClassInfo(r.getClassName(),s.methods,s.fields,s.refs));}
      }
    }
    return new ModAnalysis(metadata,List.copyOf(classes),classes.stream().mapToInt(ClassInfo::methods).sum(),classes.stream().mapToInt(ClassInfo::fields).sum(),List.copyOf(apis));
  }

  private void readMetadata(JarFile jar,ModMetadata m)throws IOException{
    JarEntry f=jar.getJarEntry("fabric.mod.json");
    if(f!=null)try(InputStream in=jar.getInputStream(f)){readFabric(JsonParser.parseString(new String(in.readAllBytes())).getAsJsonObject(),m);}

    JarEntry forge=jar.getJarEntry("META-INF/mods.toml");
    if(forge!=null){m.setLoader("Forge");try(InputStream in=jar.getInputStream(forge)){readToml(new String(in.readAllBytes()),m);}}

    JarEntry oldForge=jar.getJarEntry("mcmod.info");
    if(oldForge!=null){m.setLoader("Forge");try(InputStream in=jar.getInputStream(oldForge)){readMcModInfo(new String(in.readAllBytes()),m);}}

    Manifest mf=jar.getManifest();
    if(mf!=null){
      String name=mf.getMainAttributes().getValue("Implementation-Title");
      String version=mf.getMainAttributes().getValue("Implementation-Version");
      if("Unknown".equals(m.getName()))m.setName(name);
      if("Unknown".equals(m.getVersion()))m.setVersion(version);
    }

    JarEntry mix=jar.getJarEntry("mixins.json");
    if(mix!=null)m.addMixin(mix.getName());
  }

  private void readFabric(JsonObject r,ModMetadata m){
    m.setName(text(r,"name"));m.setVersion(text(r,"version"));m.setLoader("Fabric");
    JsonElement d=r.get("depends");
    if(d!=null&&d.isJsonObject()){
      d.getAsJsonObject().keySet().forEach(m::addDependency);
      String mc=dependencyText(d.getAsJsonObject().get("minecraft"));
      if(mc!=null)m.setMinecraftVersion(mc);
    }
    JsonElement x=r.get("mixins");
    if(x!=null&&x.isJsonArray())for(JsonElement e:x.getAsJsonArray()){
      if(e.isJsonPrimitive())m.addMixin(e.getAsString());
      else if(e.isJsonObject())m.addMixin(text(e.getAsJsonObject(),"config"));
    }
    JsonElement ep=r.get("entrypoints");
    if(ep!=null&&ep.isJsonObject())for(JsonElement group:ep.getAsJsonObject().entrySet().stream().map(Map.Entry::getValue).toList())
      if(group.isJsonArray())for(JsonElement e:group.getAsJsonArray()){
        if(e.isJsonPrimitive())m.addEntrypoint(e.getAsString());
        else if(e.isJsonObject())m.addEntrypoint(text(e.getAsJsonObject(),"value"));
      }
  }

  private void readMcModInfo(String json,ModMetadata m){
    JsonElement root=JsonParser.parseString(json);
    if(root.isJsonArray()){
      for(JsonElement e:root.getAsJsonArray())if(e.isJsonObject()){readMcModEntry(e.getAsJsonObject(),m);break;}
    } else if(root.isJsonObject()) {
      readMcModEntry(root.getAsJsonObject(),m);
    }
  }

  private void readMcModEntry(JsonObject o,ModMetadata m){
    m.setName(text(o,"name"));
    m.setVersion(text(o,"version"));
    m.setMinecraftVersion(text(o,"mcversion"));
    JsonElement deps=o.get("dependencies");
    if(deps!=null&&deps.isJsonArray())for(JsonElement e:deps.getAsJsonArray())if(e.isJsonPrimitive())m.addDependency(e.getAsString());
  }

  private void readToml(String t,ModMetadata m){
    for(String raw:t.split("\\R")){
      String l=raw.trim();
      if(l.startsWith("version"))m.setVersion(value(l));
      else if(l.startsWith("displayName"))m.setName(value(l));
      else if(l.startsWith("loaderVersion"))m.setLoader("Forge");
      else if(l.startsWith("modId"))m.addDependency("mod:"+value(l));
      else if(l.startsWith("minecraft"))m.setMinecraftVersion(value(l));
    }
  }

  private static String text(JsonObject o,String k){JsonElement e=o.get(k);return e!=null&&e.isJsonPrimitive()?e.getAsString():null;}
  private static String dependencyText(JsonElement e){
    if(e==null||e.isJsonNull())return null;
    if(e.isJsonPrimitive())return e.getAsString();
    if(e.isJsonArray()){
      List<String> values=new ArrayList<>();
      for(JsonElement item:e.getAsJsonArray())if(item.isJsonPrimitive())values.add(item.getAsString());
      return values.isEmpty()?null:String.join(", ",values);
    }
    return null;
  }
  private static String value(String l){int i=l.indexOf('=');return i<0?null:l.substring(i+1).trim().replaceAll("^\"|\"$","");}

  private Stats inspect(ClassReader r,Set<String> apis){
    Stats s=new Stats();
    r.accept(new ClassVisitor(Opcodes.ASM9){
      @Override public void visit(int v,int a,String n,String sig,String sup,String[] itf){
        collect(n,apis,s);collect(sig,apis,s);collect(sup,apis,s);if(itf!=null)for(String i:itf)collect(i,apis,s);
      }
      @Override public FieldVisitor visitField(int a,String n,String d,String sig,Object val){
        s.fields++;collect(d,apis,s);collect(sig,apis,s);return null;
      }
      @Override public MethodVisitor visitMethod(int a,String n,String d,String sig,String[] ex){
        s.methods++;collect(d,apis,s);collect(sig,apis,s);if(ex!=null)for(String x:ex)collect(x,apis,s);
        return new MethodVisitor(Opcodes.ASM9){
          @Override public void visitTypeInsn(int op,String t){collect(t,apis,s);}
          @Override public void visitFieldInsn(int op,String o,String n,String d){collect(o,apis,s);collect(d,apis,s);}
          @Override public void visitMethodInsn(int op,String o,String n,String d,boolean itf){collect(o,apis,s);collect(d,apis,s);}
          @Override public void visitLdcInsn(Object v){if(v instanceof Type t)collect(t.getDescriptor(),apis,s);}
        };
      }
    },ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);
    return s;
  }

  private void collect(String v,Set<String> apis,Stats s){
    if(v==null||!v.contains("net/minecraft/"))return;
    Matcher matcher=MINECRAFT_CLASS.matcher(v);
    boolean found=false;
    while(matcher.find()){
      found=true;
      String name=matcher.group(1).replace('/','.');
      apis.add(name);
    }
    if(found)s.refs++;
  }

  private static final class Stats{int methods,fields,refs;}
}
