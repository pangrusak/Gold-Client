package com.goldclient.model;

public record ControlFlowEdgeModel(
    int fromBlock,
    int toBlock,
    String kind
) {}
