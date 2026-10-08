package com.goldclient.analyzer;

import com.goldclient.model.ClassModel;
import java.util.List;
import java.util.Set;

public record ModAnalysis(
    ModMetadata metadata,
    List<ClassModel> classes,
    int totalMethods,
    int totalFields,
    List<String> minecraftApis,
    Set<PlatformRequirement> platformRequirements
) {
  public ModAnalysis {
    classes = List.copyOf(classes);
    minecraftApis = List.copyOf(minecraftApis);
    platformRequirements = Set.copyOf(platformRequirements);
  }

  public int totalClasses() {
    return classes.size();
  }
}
