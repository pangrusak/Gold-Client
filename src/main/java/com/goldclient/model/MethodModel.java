package com.goldclient.model;

import java.util.List;

public record MethodModel(
    String name,
    String descriptor,
    String signature,
    int access,
    List<String> exceptions,
    List<String> annotations,
    List<String> referencedClasses,
    List<InstructionModel> instructions,
    List<TryCatchModel> tryCatchBlocks,
    int maxStack,
    int maxLocals
) {
  public MethodModel {
    exceptions = List.copyOf(exceptions);
    annotations = List.copyOf(annotations);
    referencedClasses = List.copyOf(referencedClasses);
    instructions = List.copyOf(instructions);
    tryCatchBlocks = List.copyOf(tryCatchBlocks);
  }
}
