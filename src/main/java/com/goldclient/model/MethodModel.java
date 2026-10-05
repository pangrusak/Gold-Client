package com.goldclient.model;

import java.util.List;

public record MethodModel(
    String name,
    String descriptor,
    String signature,
    int access,
    List<String> exceptions,
    List<String> annotations,
    List<String> referencedClasses
) {
  public MethodModel {
    exceptions = List.copyOf(exceptions);
    annotations = List.copyOf(annotations);
    referencedClasses = List.copyOf(referencedClasses);
  }
}
