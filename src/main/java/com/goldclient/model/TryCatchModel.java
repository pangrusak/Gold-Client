package com.goldclient.model;

public record TryCatchModel(
    String startLabel,
    String endLabel,
    String handlerLabel,
    String exceptionType
) {}
