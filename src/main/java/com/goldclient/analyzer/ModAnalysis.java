package com.goldclient.analyzer;
import java.util.List;
public record ModAnalysis(ModMetadata metadata,List<ClassInfo> classes,int totalMethods,int totalFields,List<String> minecraftApis){public int totalClasses(){return classes.size();}}
