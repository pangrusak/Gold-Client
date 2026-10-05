package com.goldclient.model;

import java.util.List;

public record InstructionModel(
    int opcode,
    String opcodeName,
    List<String> operands
) {
  public InstructionModel {
    operands = List.copyOf(operands);
  }
}
