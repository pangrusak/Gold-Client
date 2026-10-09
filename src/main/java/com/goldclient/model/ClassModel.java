package com.goldclient.model;

import java.util.List;

public record ClassModel(
    String name,
    String superName,
    List<String> interfaces,
    int access,
    String signature,
    List<String> annotations,
    List<FieldModel> fields,
    List<MethodModel> methods,
    List<String> referencedClasses,
    List<AnnotationData> annotationData
) {
  public ClassModel {
    interfaces = List.copyOf(interfaces);
    annotations = List.copyOf(annotations);
    fields = List.copyOf(fields);
    methods = List.copyOf(methods);
    referencedClasses = List.copyOf(referencedClasses);
    annotationData = List.copyOf(annotationData);
  }

  public ClassModel(
      String name,
      String superName,
      List<String> interfaces,
      int access,
      String signature,
      List<String> annotations,
      List<FieldModel> fields,
      List<MethodModel> methods,
      List<String> referencedClasses
  ) {
    this(
        name,
        superName,
        interfaces,
        access,
        signature,
        annotations,
        fields,
        methods,
        referencedClasses,
        List.of()
    );
  }
}