package com.goldclient.model;

import java.util.List;

public record IrMethodModel(
    String name,
    String descriptor,
    List<IrOperationModel> operations
) {
  public IrMethodModel {
    operations = List.copyOf(operations);
  }
}
