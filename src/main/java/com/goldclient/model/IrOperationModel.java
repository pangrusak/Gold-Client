package com.goldclient.model;

import java.util.List;

public record IrOperationModel(
    String kind,
    List<String> operands,
    int sourceInstruction
) {
  public IrOperationModel {
    operands = List.copyOf(operands);
  }
}
