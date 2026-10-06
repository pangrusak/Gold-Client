package com.goldclient.translator;

import com.goldclient.model.IrMethodModel;
import com.goldclient.model.IrOperationModel;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaTranslatorTest {
  @Test
  void translatesLithiumStyleNullCheck() {
    IrMethodModel method = new IrMethodModel(
        "onInitialize",
        "()V",
        List.of(
            new IrOperationModel(
                "FIELD_READ",
                List.of("me.jellysquid.mods.lithium.common.LithiumMod.CONFIG"),
                0),
            new IrOperationModel(
                "CONDITIONAL_BRANCH",
                List.of("me.jellysquid.mods.lithium.common.LithiumMod.CONFIG != null", "L0"),
                1),
            new IrOperationModel(
                "OBJECT_CREATE",
                List.of("new java.lang.IllegalStateException"),
                2),
            new IrOperationModel("DUP", List.of("new java.lang.IllegalStateException"), 3),
            new IrOperationModel(
                "CONSTANT",
                List.of("The mixin plugin did not initialize the config! Did it not load?"),
                4),
            new IrOperationModel(
                "METHOD_CALL",
                List.of("new java.lang.IllegalStateException(The mixin plugin did not initialize the config! Did it not load?)"),
                5),
            new IrOperationModel(
                "THROW",
                List.of("new java.lang.IllegalStateException"),
                6),
            new IrOperationModel("LABEL", List.of("L0"), 7),
            new IrOperationModel("RETURN", List.of(), 8)
        ));

    TranslationResult result = new JavaTranslator().translate(method);

    assertTrue(result.complete());
    assertTrue(result.source().contains("void onInitialize()"));
    assertTrue(result.source().contains(
        "if (me.jellysquid.mods.lithium.common.LithiumMod.CONFIG == null) {"));
    assertTrue(result.source().contains(
        "throw new java.lang.IllegalStateException(\"The mixin plugin did not initialize the config! Did it not load?\");"));
    assertTrue(result.source().contains("return;"));
  }


  @Test
  void translatesConstants() {
    IrMethodModel method = new IrMethodModel(
        "test",
        "()V",
        List.of(
            new IrOperationModel("CONSTANT", List.of("hello"), 0),
            new IrOperationModel("CONSTANT", List.of("42"), 1),
            new IrOperationModel("CONSTANT", List.of("true"), 2),
            new IrOperationModel("CONSTANT", List.of("null"), 3)));

    TranslationResult result = new JavaTranslator().translate(method);

    assertTrue(result.complete());
    assertTrue(result.source().contains("  \"hello\";"));
    assertTrue(result.source().contains("  42;"));
    assertTrue(result.source().contains("  true;"));
    assertTrue(result.source().contains("  null;"));
  }

  @Test
  void translatesMethodCallAndReturnExpression() {
    IrMethodModel method = new IrMethodModel(
        "test",
        "()V",
        List.of(
            new IrOperationModel("METHOD_CALL", List.of("System.out.println(\"hello\")"), 0),
            new IrOperationModel("RETURN", List.of("value"), 1)));

    TranslationResult result = new JavaTranslator().translate(method);

    assertTrue(result.complete());
    assertTrue(result.source().contains("System.out.println(\"hello\");"));
    assertTrue(result.source().contains("return value;"));
  }

  @Test
  void marksUnsupportedOperations() {
    IrMethodModel method = new IrMethodModel(
        "test",
        "()V",
        List.of(new IrOperationModel("UNSUPPORTED", List.of("x"), 0)));

    TranslationResult result = new JavaTranslator().translate(method);

    assertTrue(!result.complete());
    assertTrue(result.source().contains("TODO: UNSUPPORTED"));
  }

  @Test
  void declaresThenAssignsLocals() {
    IrMethodModel method = new IrMethodModel(
        "test",
        "()V",
        List.of(
            new IrOperationModel("LOCAL_WRITE", List.of("local1", "1"), 0),
            new IrOperationModel("LOCAL_WRITE", List.of("local1", "2"), 1),
            new IrOperationModel("RETURN", List.of(), 2)));

    TranslationResult result = new JavaTranslator().translate(method);

    assertTrue(result.source().contains("Object local1 = 1;"));
    assertTrue(result.source().contains("local1 = 2;"));
  }
}
