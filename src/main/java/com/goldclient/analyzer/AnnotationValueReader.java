package com.goldclient.analyzer;

import com.goldclient.model.AnnotationData;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public final class AnnotationValueReader extends AnnotationVisitor {
  private final String annotationName;
  private final boolean runtimeVisible;
  private final Consumer<AnnotationData> destination;
  private final Map<String, Object> values = new LinkedHashMap<>();

  public AnnotationValueReader(
      String descriptor,
      boolean runtimeVisible,
      Consumer<AnnotationData> destination
  ) {
    super(Opcodes.ASM9);
    this.annotationName = Type.getType(descriptor).getClassName();
    this.runtimeVisible = runtimeVisible;
    this.destination = destination;
  }

  @Override
  public void visit(String name, Object value) {
    values.put(name, normalize(value));
  }

  @Override
  public void visitEnum(String name, String descriptor, String value) {
    values.put(name, new AnnotationData.EnumValue(descriptor, value));
  }

  @Override
  public AnnotationVisitor visitAnnotation(String name, String descriptor) {
    return new AnnotationValueReader(
        descriptor,
        runtimeVisible,
        annotation -> values.put(name, annotation)
    );
  }

  @Override
  public AnnotationVisitor visitArray(String name) {
    return arrayReader(array -> values.put(name, array), runtimeVisible);
  }

  @Override
  public void visitEnd() {
    destination.accept(
        new AnnotationData(annotationName, runtimeVisible, values)
    );
  }

  private static AnnotationVisitor arrayReader(
      Consumer<List<Object>> destination,
      boolean runtimeVisible
  ) {
    List<Object> values = new ArrayList<>();

    return new AnnotationVisitor(Opcodes.ASM9) {
      @Override
      public void visit(String name, Object value) {
        values.add(normalize(value));
      }

      @Override
      public void visitEnum(String name, String descriptor, String value) {
        values.add(new AnnotationData.EnumValue(descriptor, value));
      }

      @Override
      public AnnotationVisitor visitAnnotation(
          String name, String descriptor
      ) {
        return new AnnotationValueReader(
            descriptor,
            runtimeVisible,
            values::add
        );
      }

      @Override
      public AnnotationVisitor visitArray(String name) {
        return arrayReader(values::add, runtimeVisible);
      }

      @Override
      public void visitEnd() {
        destination.accept(List.copyOf(values));
      }
    };
  }

  private static Object normalize(Object value) {
    if (value instanceof Type type) {
      return new AnnotationData.ClassValue(type.getDescriptor());
    }

    if (value != null && value.getClass().isArray()) {
      List<Object> values = new ArrayList<>();

      for (int index = 0; index < Array.getLength(value); index++) {
        values.add(normalize(Array.get(value, index)));
      }

      return List.copyOf(values);
    }

    return value;
  }
}