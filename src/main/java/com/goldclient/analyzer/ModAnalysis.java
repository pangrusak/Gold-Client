package com.goldclient.analyzer;

import com.goldclient.model.ClassModel;
import java.util.List;

public record ModAnalysis(
    ModMetadata metadata,
    List<ClassModel> classes,
    int totalMethods,
    int totalFields,
    List<String> minecraftApis
) {
  public ModAnalysis {
    classes = List.copyOf(classes);
    minecraftApis = List.copyOf(minecraftApis);
  }

  public int totalClasses() {
    return classes.size();
  }
}
