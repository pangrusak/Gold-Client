package com.goldclient.model;

import java.util.List;

public record BasicBlockModel(
    int id,
    int startInstruction,
    int endInstruction,
    List<Integer> successors,
    List<Integer> predecessors
) {
  public BasicBlockModel {
    successors = List.copyOf(successors);
    predecessors = List.copyOf(predecessors);
  }
}
