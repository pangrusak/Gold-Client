package com.goldclient.model;

import java.util.List;

public record ControlFlowModel(
    int entryBlock,
    List<BasicBlockModel> blocks,
    List<ControlFlowEdgeModel> edges
) {
  public ControlFlowModel {
    blocks = List.copyOf(blocks);
    edges = List.copyOf(edges);
  }

  public ControlFlowModel(int entryBlock, List<BasicBlockModel> blocks) {
    this(entryBlock, blocks, List.of());
  }
}
