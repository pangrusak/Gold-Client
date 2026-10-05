package com.goldclient.model;

import java.util.List;

public record FieldModel(
    String name,
    String descriptor,
    String signature,
    int access,
    List<String> annotations
) {
  public FieldModel {
    annotations = List.copyOf(annotations);
  }
}
