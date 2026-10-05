package com.goldclient.analyzer;
import java.util.*;
public final class ModMetadata {
  private String name="Unknown",version="Unknown",minecraftVersion="Unknown",loader="Unknown";
  private final List<String> dependencies=new ArrayList<>(),entrypoints=new ArrayList<>(),mixins=new ArrayList<>();
  public String getName(){return name;} public String getVersion(){return version;} public String getMinecraftVersion(){return minecraftVersion;} public String getLoader(){return loader;}
  public List<String> getDependencies(){return List.copyOf(dependencies);} public List<String> getEntrypoints(){return List.copyOf(entrypoints);} public List<String> getMixins(){return List.copyOf(mixins);}
  void setName(String v){if(v!=null&&!v.isBlank())name=v;} void setVersion(String v){if(v!=null&&!v.isBlank())version=v;} void setMinecraftVersion(String v){if(v!=null&&!v.isBlank())minecraftVersion=v;} void setLoader(String v){if(v!=null&&!v.isBlank())loader=v;}
  void addDependency(String v){if(v!=null&&!v.isBlank()&&!dependencies.contains(v))dependencies.add(v);} void addEntrypoint(String v){if(v!=null&&!v.isBlank()&&!entrypoints.contains(v))entrypoints.add(v);} void addMixin(String v){if(v!=null&&!v.isBlank()&&!mixins.contains(v))mixins.add(v);}
}
