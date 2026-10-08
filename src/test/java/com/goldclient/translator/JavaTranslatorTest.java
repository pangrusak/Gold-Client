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
    assertTrue(result.source().contains(
        "if (me.jellysquid.mods.lithium.common.LithiumMod.CONFIG == null) {"));
    assertTrue(result.source().contains(
        "throw new java.lang.IllegalStateException(\"The mixin plugin did not initialize the config! Did it not load?\");"));
    assertTrue(result.source().contains("return;"));
  }

  @Test
  void preservesPunctuationInConstructorStringArguments() {
    IrMethodModel method = new IrMethodModel(
        "test",
        "()V",
        List.of(new IrOperationModel(
            "THROW",
            List.of("new java.lang.IllegalStateException(The config did not initialize!)"),
            0)));

    TranslationResult result = new JavaTranslator().translate(method);

    assertTrue(result.complete());
    assertTrue(result.source().contains(
        "throw new java.lang.IllegalStateException(\"The config did not initialize!\");"));
  }

  @Test
  void translatesConstantsAndExpressions() {
    IrMethodModel method = new IrMethodModel(
        "test",
        "()V",
        List.of(
            new IrOperationModel("CONSTANT", List.of("2"), 0),
            new IrOperationModel("CONSTANT", List.of("3"), 1),
            new IrOperationModel("ARITHMETIC", List.of("(2 + 3)"), 2),
            new IrOperationModel("LOCAL_WRITE", List.of("local1", "(2 + 3)"), 3),
            new IrOperationModel("LOCAL_READ", List.of("local1"), 4),
            new IrOperationModel("METHOD_CALL", List.of("System.out.println(local1)"), 5),
            new IrOperationModel("RETURN", List.of(), 6)));

    TranslationResult result = new JavaTranslator().translate(method);

    assertTrue(result.complete());
    assertTrue(result.source().contains("var local1 = (2 + 3);"));
    assertTrue(result.source().contains("System.out.println(local1);"));
  }

  @Test
  void translatesMethodArgumentsAndObjectConstruction() {
    IrMethodModel method = new IrMethodModel(
        "test",
        "()V",
        List.of(
            new IrOperationModel("CONSTANT", List.of("hello"), 0),
            new IrOperationModel(
                "METHOD_CALL",
                List.of("new java.lang.IllegalStateException(hello)"),
                1),
            new IrOperationModel("THROW", List.of("new java.lang.IllegalStateException(hello)"), 2)));

    TranslationResult result = new JavaTranslator().translate(method);

    assertTrue(result.complete());
    assertTrue(result.source().contains(
        "throw new java.lang.IllegalStateException(\"hello\");"));
  }

  @Test
  void translatesIfElse() {
    IrMethodModel method = new IrMethodModel(
        "test",
        "()V",
        List.of(
            new IrOperationModel("CONDITIONAL_BRANCH", List.of("local1 == 0", "Lelse"), 0),
            new IrOperationModel("METHOD_CALL", List.of("foo()"), 1),
            new IrOperationModel("JUMP", List.of("Lend"), 2),
            new IrOperationModel("LABEL", List.of("Lelse"), 3),
            new IrOperationModel("METHOD_CALL", List.of("bar()"), 4),
            new IrOperationModel("LABEL", List.of("Lend"), 5),
            new IrOperationModel("RETURN", List.of(), 6)));

    TranslationResult result = new JavaTranslator().translate(method);

    assertTrue(result.complete());
    assertTrue(result.source().contains("if (local1 == 0) {"));
    assertTrue(result.source().contains("foo();"));
    assertTrue(result.source().contains("} else {"));
    assertTrue(result.source().contains("bar();"));
    assertTrue(result.source().indexOf("bar();")
        < result.source().indexOf("foo();"));
  }

  @Test
  void translatesNestedIfStatements() {
    IrMethodModel method = new IrMethodModel(
        "test",
        "()V",
        List.of(
            new IrOperationModel("CONDITIONAL_BRANCH", List.of("a == 0", "LouterEnd"), 0),
            new IrOperationModel("CONDITIONAL_BRANCH", List.of("b == 0", "LinnerEnd"), 1),
            new IrOperationModel("METHOD_CALL", List.of("nested()"), 2),
            new IrOperationModel("LABEL", List.of("LinnerEnd"), 3),
            new IrOperationModel("LABEL", List.of("LouterEnd"), 4),
            new IrOperationModel("RETURN", List.of(), 5)));

    TranslationResult result = new JavaTranslator().translate(method);

    assertTrue(result.complete());
    assertTrue(result.complete());
    assertTrue(result.source().contains("if (a != 0) {"));
    assertTrue(result.source().contains("if (b != 0) {"));
    assertTrue(result.source().contains("nested();"));
  }

  @Test
  void translatesDoWhileLoop() {
    IrMethodModel method = new IrMethodModel(
        "test",
        "()V",
        List.of(
            new IrOperationModel("LABEL", List.of("Lloop"), 0),
            new IrOperationModel("METHOD_CALL", List.of("tick()"), 1),
            new IrOperationModel("CONDITIONAL_BRANCH", List.of("local1 < 10", "Lloop"), 2),
            new IrOperationModel("RETURN", List.of(), 3)));

    TranslationResult result = new JavaTranslator().translate(method);

    assertTrue(result.complete());
    assertTrue(result.source().contains("do {"));
    assertTrue(result.source().contains("tick();"));
    assertTrue(result.source().contains("} while (local1 < 10);"));
  }

  @Test
  void translatesFieldWriteAndReturn() {
    IrMethodModel method = new IrMethodModel(
        "test",
        "()V",
        List.of(
            new IrOperationModel(
                "FIELD_WRITE",
                List.of("example.Test.VALUE", "42"),
                0),
            new IrOperationModel("RETURN", List.of("example.Test.VALUE"), 1)));

    TranslationResult result = new JavaTranslator().translate(method);

    assertTrue(result.complete());
    assertTrue(result.source().contains("example.Test.VALUE = 42;"));
    assertTrue(result.source().contains("return example.Test.VALUE;"));
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
  void translatesMethodDescriptorSignature() {
    IrMethodModel method = new IrMethodModel(
        "compute",
        "(Ljava/lang/String;I)Z",
        List.of(new IrOperationModel("RETURN", List.of("true"), 0)));

    TranslationResult result = new JavaTranslator().translate(method);

    assertTrue(result.complete());
    assertTrue(result.source().startsWith("boolean compute(java.lang.String local0, int local1)"));
    assertTrue(result.source().contains("return true;"));
  }

  @Test
  void translatesArrayAndLongReturnTypes() {
    TranslationResult array = new JavaTranslator().translate(new IrMethodModel(
        "values",
        "()[I",
        List.of(new IrOperationModel("RETURN", List.of("local0"), 0))));
    TranslationResult wide = new JavaTranslator().translate(new IrMethodModel(
        "count",
        "()J",
        List.of(new IrOperationModel("RETURN", List.of("42L"), 0))));

    assertTrue(array.source().startsWith("int[] values()"));
    assertTrue(wide.source().startsWith("long count()"));
  }

  @Test
  void preservesNumericConstructorArguments() {
    IrMethodModel method = new IrMethodModel(
        "test",
        "()V",
        List.of(
            new IrOperationModel(
                "METHOD_CALL",
                List.of("new java.lang.IllegalArgumentException(42)"),
                0),
            new IrOperationModel(
                "THROW",
                List.of("new java.lang.IllegalArgumentException(42)"),
                1)));

    TranslationResult result = new JavaTranslator().translate(method);

    assertTrue(result.complete());
    assertTrue(result.source().contains(
        "throw new java.lang.IllegalArgumentException(42);"));
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

    assertTrue(result.source().contains("var local1 = 1;"));
    assertTrue(result.source().contains("local1 = 2;"));
  }
}
