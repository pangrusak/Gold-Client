package com.goldclient.analyzer;

import com.google.gson.*;
import org.objectweb.asm.*;
import java.io.*; import java.nio.file.*; import java.util.*; import java.util.jar.*;

public final class ModAnalyzer {
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
    Manifest mf=jar.getManifest(); if(mf!=null){m.setName(mf.getMainAttributes().getValue("Implementation-Title"));m.setVersion(mf.getMainAttributes().getValue("Implementation-Version"));}
    if("Unknown".equals(m.getLoader())&&f!=null)m.setLoader("Fabric");
    JarEntry mix=jar.getJarEntry("mixins.json"); if(mix!=null)m.addMixin(mix.getName());
  }
  private void readFabric(JsonObject r,ModMetadata m){
    m.setName(text(r,"name"));m.setVersion(text(r,"version"));m.setLoader("Fabric");
    JsonElement d=r.get("depends"); if(d!=null&&d.isJsonObject()){d.getAsJsonObject().keySet().forEach(m::addDependency);String mc=text(d.getAsJsonObject(),"minecraft");if(mc!=null)m.setMinecraftVersion(mc);}
    JsonElement x=r.get("mixins"); if(x!=null&&x.isJsonArray())for(JsonElement e:x.getAsJsonArray()){if(e.isJsonPrimitive())m.addMixin(e.getAsString());else if(e.isJsonObject())m.addMixin(text(e.getAsJsonObject(),"config"));}
    JsonElement ep=r.get("entrypoints"); if(ep!=null&&ep.isJsonObject())for(JsonElement group:ep.getAsJsonObject().entrySet().stream().map(Map.Entry::getValue).toList())if(group.isJsonArray())for(JsonElement e:group.getAsJsonArray()){if(e.isJsonPrimitive())m.addEntrypoint(e.getAsString());else if(e.isJsonObject())m.addEntrypoint(text(e.getAsJsonObject(),"value"));}
  }
  private void readToml(String t,ModMetadata m){for(String raw:t.split("\\R")){String l=raw.trim();if(l.startsWith("version"))m.setVersion(value(l));else if(l.startsWith("displayName"))m.setName(value(l));else if(l.startsWith("loaderVersion"))m.setLoader("Forge");else if(l.startsWith("modId"))m.addDependency("mod:"+value(l));else if(l.startsWith("minecraft"))m.setMinecraftVersion(value(l));}}
  private static String text(JsonObject o,String k){JsonElement e=o.get(k);return e!=null&&e.isJsonPrimitive()?e.getAsString():null;}
  private static String value(String l){int i=l.indexOf('=');return i<0?null:l.substring(i+1).trim().replaceAll("^\"|\"$","");}
  private Stats inspect(ClassReader r,Set<String> apis){Stats s=new Stats();r.accept(new ClassVisitor(Opcodes.ASM9){
    @Override public void visit(int v,int a,String n,String sig,String sup,String[] itf){collect(n,apis,s);collect(sig,apis,s);collect(sup,apis,s);if(itf!=null)for(String i:itf)collect(i,apis,s);}
    @Override public FieldVisitor visitField(int a,String n,String d,String sig,Object val){s.fields++;collect(d,apis,s);collect(sig,apis,s);return null;}
    @Override public MethodVisitor visitMethod(int a,String n,String d,String sig,String[] ex){s.methods++;collect(d,apis,s);collect(sig,apis,s);if(ex!=null)for(String x:ex)collect(x,apis,s);return new MethodVisitor(Opcodes.ASM9){
      @Override public void visitTypeInsn(int op,String t){collect(t,apis,s);}
      @Override public void visitFieldInsn(int op,String o,String n,String d){collect(o,apis,s);collect(d,apis,s);}
      @Override public void visitMethodInsn(int op,String o,String n,String d,boolean itf){collect(o,apis,s);collect(d,apis,s);}
      @Override public void visitLdcInsn(Object v){if(v instanceof Type t)collect(t.getDescriptor(),apis,s);}
    };}
  },ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);return s;}
  private void collect(String v,Set<String> apis,Stats s){if(v!=null&&v.contains("net/minecraft/")){s.refs++;apis.add(v.replace('/','.'));}}
  private static final class Stats{int methods,fields,refs;}
}
