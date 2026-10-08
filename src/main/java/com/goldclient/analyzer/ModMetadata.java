package com.goldclient.analyzer;

import java.util.ArrayList;
import java.util.List;

public final class ModMetadata {
  private String name = "Unknown";
  private String version = "Unknown";
  private String minecraftVersion = "Unknown";
  private String loader = "Unknown";
  private final List<String> dependencies = new ArrayList<>();
  private final List<String> optionalDependencies = new ArrayList<>();
  private final List<String> entrypoints = new ArrayList<>();
  private final List<String> mixins = new ArrayList<>();

  public String getName() { return name; }
  public String getVersion() { return version; }
  public String getMinecraftVersion() { return minecraftVersion; }
  public String getLoader() { return loader; }
  public List<String> getDependencies() { return List.copyOf(dependencies); }
  public List<String> getOptionalDependencies() { return List.copyOf(optionalDependencies); }
  public List<String> getEntrypoints() { return List.copyOf(entrypoints); }
  public List<String> getMixins() { return List.copyOf(mixins); }

  void setName(String value) {
    if (value != null && !value.isBlank()) name = value;
  }

  void setVersion(String value) {
    if (value != null && !value.isBlank()) version = value;
  }

  void setMinecraftVersion(String value) {
    if (value != null && !value.isBlank()) minecraftVersion = value;
  }

  void setLoader(String value) {
    if (value != null && !value.isBlank()) loader = value;
  }

  void addDependency(String value) {
    if (value != null && !value.isBlank() && !dependencies.contains(value)) {
      dependencies.add(value);
      optionalDependencies.remove(value);
    }
  }

  void addOptionalDependency(String value) {
    if (value != null && !value.isBlank()
        && !dependencies.contains(value) && !optionalDependencies.contains(value)) {
      optionalDependencies.add(value);
    }
  }

  void addEntrypoint(String value) {
    if (value != null && !value.isBlank() && !entrypoints.contains(value)) {
      entrypoints.add(value);
    }
  }

  void addMixin(String value) {
    if (value != null && !value.isBlank() && !mixins.contains(value)) {
      mixins.add(value);
    }
  }
}
