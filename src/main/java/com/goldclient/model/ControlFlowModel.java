package com.goldclient.model;

import java.util.List;

public record ControlFlowModel(
    int entryBlock,
    List<BasicBlockModel> blocks
) {
  public ControlFlowModel {
    blocks = List.copyOf(blocks);
  }
}
