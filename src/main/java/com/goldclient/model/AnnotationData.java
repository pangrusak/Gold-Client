package com.goldclient.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record AnnotationData(
    String name,
    boolean runtimeVisible,
    Map<String, Object> values
) {
  public AnnotationData {
    values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
  }

  public record EnumValue(String descriptor, String constant) {
  }

  public record ClassValue(String descriptor) {
  }
}