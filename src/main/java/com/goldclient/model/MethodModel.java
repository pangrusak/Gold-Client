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
    int maxLocals,
    ControlFlowModel controlFlow,
    IrMethodModel intermediateRepresentation
) {
  public MethodModel {
    exceptions = List.copyOf(exceptions);
    annotations = List.copyOf(annotations);
    referencedClasses = List.copyOf(referencedClasses);
    instructions = List.copyOf(instructions);
    tryCatchBlocks = List.copyOf(tryCatchBlocks);
    intermediateRepresentation = intermediateRepresentation == null
        ? new IrMethodModel(name, descriptor, List.of())
        : intermediateRepresentation;
  }

  public MethodModel(
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
      int maxLocals,
      ControlFlowModel controlFlow
  ) {
    this(
        name, descriptor, signature, access,
        exceptions, annotations, referencedClasses,
        instructions, tryCatchBlocks, maxStack, maxLocals,
        controlFlow,
        new IrMethodModel(name, descriptor, List.of())
    );
  }

  public MethodModel(
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
    this(
        name, descriptor, signature, access,
        exceptions, annotations, referencedClasses,
        instructions, tryCatchBlocks, maxStack, maxLocals,
        new ControlFlowModel(0, List.of()),
        new IrMethodModel(name, descriptor, List.of())
    );
  }
}
