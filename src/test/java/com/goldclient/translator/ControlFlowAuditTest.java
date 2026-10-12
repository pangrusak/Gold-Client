package com.goldclient.translator;

import com.goldclient.model.IrOperationModel;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControlFlowAuditTest {
  @Test
  void buildsConditionalBranchEdgesToTargetAndFallthrough() {
    var report = ControlFlowAudit.analyze(List.of(
        op("CONDITIONAL_BRANCH", "x > 0", "Lyes"),
        op("RETURN", "0"),
        op("LABEL", "Lyes"),
        op("RETURN", "1")));

    assertTrue(report.valid(), report.issues().toString());
    assertEquals(Set.of(2, 1), report.edges().get(0));
  }

  @Test
  void reportsUnresolvedJumpTargets() {
    var report = ControlFlowAudit.analyze(List.of(
        op("JUMP", "Lmissing"),
        op("RETURN")));

    assertFalse(report.valid());
    assertTrue(report.errors().stream()
        .anyMatch(issue -> issue.message().contains("unresolved branch target 'Lmissing'")));
  }

  @Test
  void detectsDuplicateLabelsWithoutLosingFirstDeclaration() {
    var report = ControlFlowAudit.analyze(List.of(
        op("LABEL", "Lsame"),
        op("LABEL", "Lsame"),
        op("RETURN")));

    assertFalse(report.valid());
    assertEquals(0, report.labels().get("Lsame"));
    assertTrue(report.errors().stream().anyMatch(issue -> issue.message().contains("duplicate label")));
  }

  @Test
  void validatesTableSwitchArityAndEveryTarget() {
    var valid = ControlFlowAudit.analyze(List.of(
        op("TABLE_SWITCH", "local0", "2", "3", "Ldefault", "Ltwo", "Lthree"),
        op("LABEL", "Ltwo"),
        op("RETURN", "2"),
        op("LABEL", "Lthree"),
        op("RETURN", "3"),
        op("LABEL", "Ldefault"),
        op("RETURN", "-1")));
    assertTrue(valid.valid(), valid.issues().toString());
    assertEquals(Set.of(1, 3, 5), valid.edges().get(0));

    var invalid = ControlFlowAudit.analyze(List.of(
        op("TABLE_SWITCH", "local0", "2", "4", "Ldefault", "Lmissing")));
    assertFalse(invalid.valid());
    assertTrue(invalid.errors().stream()
        .anyMatch(issue -> issue.message().contains("require 3 case labels")));
  }

  @Test
  void validatesLookupSwitchAndWarnsAboutRepeatedKeys() {
    var report = ControlFlowAudit.analyze(List.of(
        op("LOOKUP_SWITCH", "local0", "Ldefault", "5", "Lcase", "5", "Lcase"),
        op("LABEL", "Lcase"),
        op("RETURN", "5"),
        op("LABEL", "Ldefault"),
        op("RETURN", "0")));

    assertTrue(report.valid(), report.issues().toString());
    assertEquals(1, report.warnings().size());
    assertEquals(Set.of(1, 3), report.edges().get(0));
  }

  @Test
  void exceptionMarkersNeedExactlyOneOfEachAndKnownLabels() {
    var metadata = List.of("Lstart", "Lend", "Lhandler", "java.lang.RuntimeException");
    var report = ControlFlowAudit.analyze(List.of(
        op("TRY_BEGIN", metadata.toArray(String[]::new)),
        op("LABEL", "Lstart"),
        op("METHOD_CALL", "work()"),
        op("TRY_END", metadata.toArray(String[]::new)),
        op("LABEL", "Lend"),
        op("JUMP", "Ljoin"),
        op("CATCH_BEGIN", metadata.toArray(String[]::new)),
        op("LABEL", "Lhandler"),
        op("THROW", "local0"),
        op("LABEL", "Ljoin"),
        op("RETURN")));

    assertTrue(report.valid(), report.issues().toString());
  }

  @Test
  void flagsMalformedExceptionRegionMarkers() {
    var report = ControlFlowAudit.analyze(List.of(
        op("TRY_BEGIN", "Lstart", "Lend", "Lmissing", "java.lang.Exception"),
        op("LABEL", "Lstart"),
        op("LABEL", "Lend"),
        op("RETURN")));

    assertFalse(report.valid());
    assertTrue(report.errors().stream()
        .anyMatch(issue -> issue.message().contains("exactly one TRY_BEGIN")));
    assertTrue(report.errors().stream()
        .anyMatch(issue -> issue.message().contains("undefined protected-range label")));
  }

  @Test
  void findsUnreachableInstructionsAfterReturn() {
    var report = ControlFlowAudit.analyze(List.of(
        op("RETURN"),
        op("METHOD_CALL", "neverCalled()"),
        op("LABEL", "Ldead"),
        op("RETURN")));

    assertEquals(Set.of(1), report.unreachableOperations());
  }

  @Test
  void doesNotCallMetadataUnreachable() {
    var report = ControlFlowAudit.analyze(List.of(
        op("RETURN"),
        op("LABEL", "Ldead"),
        op("TRY_BEGIN", "Lstart", "Lend", "Lhandler", "java.lang.Exception")));

    assertTrue(report.unreachableOperations().isEmpty());
  }

  @Test
  void reportsProtectedRangeWithReversedLabels() {
    var metadata = List.of("Lend", "Lstart", "Lhandler", "java.lang.Exception");
    var report = ControlFlowAudit.analyze(List.of(
        op("TRY_BEGIN", metadata.toArray(String[]::new)),
        op("LABEL", "Lstart"),
        op("LABEL", "Lend"),
        op("LABEL", "Lhandler"),
        op("TRY_END", metadata.toArray(String[]::new)),
        op("CATCH_BEGIN", metadata.toArray(String[]::new)),
        op("RETURN")));

    assertFalse(report.valid());
    assertTrue(report.errors().stream()
        .anyMatch(issue -> issue.message().contains("must start before")));
  }

  @Test
  void terminalOperationsDoNotFallThrough() {
    var report = ControlFlowAudit.analyze(List.of(
        op("THROW", "error"),
        op("RETURN")));

    assertTrue(report.edges().get(0).isEmpty());
    assertEquals(Set.of(1), report.unreachableOperations());
  }

  private static IrOperationModel op(String kind, String... operands) {
    return new IrOperationModel(kind, List.of(operands), 0);
  }
}