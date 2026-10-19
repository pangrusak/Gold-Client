package com.goldclient.model;

import java.util.List;

public record FieldModel(
    String name,
    String descriptor,
    String signature,
    int access,
    List<String> annotations,
    Object constantValue,
    List<AnnotationData> annotationData
) {
  public FieldModel {
    annotations = List.copyOf(annotations);
    annotationData = List.copyOf(annotationData);
  }

  public FieldModel(String name, String descriptor, String signature, int access,
      List<String> annotations, Object constantValue) {
    this(name, descriptor, signature, access, annotations, constantValue, List.of());
  }

  /** Backward-compatible constructor for fields without a ConstantValue attribute. */
  public FieldModel(String name, String descriptor, String signature, int access,
      List<String> annotations) {
    this(name, descriptor, signature, access, annotations, null, List.of());
  }
}
