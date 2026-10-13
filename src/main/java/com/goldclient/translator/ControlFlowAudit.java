package com.goldclient.translator;

import com.goldclient.model.IrOperationModel;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Audits the control-flow metadata emitted by the bytecode-to-IR stage.
 *
 * <p>This deliberately does not try to reconstruct source code. It checks the
 * graph invariants that a source structurer depends on, and returns stable,
 * actionable diagnostics instead of guessing when the IR is malformed.</p>
 */
public final class ControlFlowAudit {
  private ControlFlowAudit() {}

  public static Report analyze(List<IrOperationModel> operations) {
    List<Issue> issues = new ArrayList<>();
    Map<String, Integer> labels = new LinkedHashMap<>();
    Map<Integer, Set<Integer>> edges = new LinkedHashMap<>();
    Map<String, List<Integer>> markerPositions = new LinkedHashMap<>();

    indexLabels(operations, labels, issues);
    buildEdges(operations, labels, edges, issues);
    auditExceptionMarkers(operations, labels, markerPositions, issues);
    Set<Integer> reachable = reachableFromEntry(operations.size(), edges);
    Set<Integer> unreachable = new TreeSet<>();
    for (int index = 0; index < operations.size(); index++) {
      if (!reachable.contains(index) && !isMetadata(operations.get(index))) {
        unreachable.add(index);
      }
    }

    return new Report(labels, edges, issues, reachable, unreachable);
  }

  private static void indexLabels(
      List<IrOperationModel> operations,
      Map<String, Integer> labels,
      List<Issue> issues) {
    for (int index = 0; index < operations.size(); index++) {
      IrOperationModel operation = operations.get(index);
      if (!"LABEL".equals(operation.kind()) || operation.operands().isEmpty()) continue;
      String name = labelKey(operation.operands().get(0));
      Integer previous = labels.putIfAbsent(name, index);
      if (previous != null) {
        issues.add(new Issue(Severity.ERROR, index,
            "duplicate label '" + name + "' (first declared at operation " + previous + ")"));
      }
    }
  }

  private static void buildEdges(
      List<IrOperationModel> operations,
      Map<String, Integer> labels,
      Map<Integer, Set<Integer>> edges,
      List<Issue> issues) {
    for (int index = 0; index < operations.size(); index++) {
      IrOperationModel operation = operations.get(index);
      Set<Integer> targets = new LinkedHashSet<>();
      String kind = operation.kind();
      List<String> operands = operation.operands();

      if ("JUMP".equals(kind)) {
        addTarget(index, operands, 0, labels, targets, issues);
      } else if ("CONDITIONAL_BRANCH".equals(kind)) {
        addTarget(index, operands, 1, labels, targets, issues);
        addFallthrough(index, operations.size(), targets);
      } else if ("TABLE_SWITCH".equals(kind)) {
        auditTableSwitch(index, operands, labels, targets, issues);
      } else if ("LOOKUP_SWITCH".equals(kind)) {
        auditLookupSwitch(index, operands, labels, targets, issues);
      } else if (!isTerminal(operation)) {
        addFallthrough(index, operations.size(), targets);
      }

      // Try/catch marker records carry their target label in slot 2. They are
      // metadata, not executable instructions, so they do not add normal edges.
      edges.put(index, Collections.unmodifiableSet(targets));
    }
  }

  private static void auditTableSwitch(
      int index, List<String> operands, Map<String, Integer> labels,
      Set<Integer> targets, List<Issue> issues) {
    if (operands.size() < 4) {
      issues.add(new Issue(Severity.ERROR, index, "TABLE_SWITCH has fewer than four operands"));
      return;
    }
    addNamedTarget(index, operands.get(3), labels, targets, issues);
    final int low;
    final int high;
    try {
      low = Integer.parseInt(operands.get(1));
      high = Integer.parseInt(operands.get(2));
    } catch (NumberFormatException exception) {
      issues.add(new Issue(Severity.ERROR, index, "TABLE_SWITCH bounds are not integers"));
      return;
    }
    long expected = (long) high - low + 1;
    if (expected < 0 || expected != operands.size() - 4) {
      issues.add(new Issue(Severity.ERROR, index,
          "TABLE_SWITCH bounds require " + expected + " case labels, found "
              + (operands.size() - 4)));
      return;
    }
    for (int operand = 4; operand < operands.size(); operand++) {
      addNamedTarget(index, operands.get(operand), labels, targets, issues);
    }
  }

  private static void auditLookupSwitch(
      int index, List<String> operands, Map<String, Integer> labels,
      Set<Integer> targets, List<Issue> issues) {
    if (operands.size() < 2 || (operands.size() - 2) % 2 != 0) {
      issues.add(new Issue(Severity.ERROR, index,
          "LOOKUP_SWITCH requires a value, default label, and key/label pairs"));
      return;
    }
    addNamedTarget(index, operands.get(1), labels, targets, issues);
    Set<String> keys = new LinkedHashSet<>();
    for (int operand = 2; operand < operands.size(); operand += 2) {
      if (!keys.add(operands.get(operand))) {
        issues.add(new Issue(Severity.WARNING, index,
            "LOOKUP_SWITCH repeats key '" + operands.get(operand) + "'"));
      }
      addNamedTarget(index, operands.get(operand + 1), labels, targets, issues);
    }
  }

  private static void addTarget(
      int index, List<String> operands, int operandIndex,
      Map<String, Integer> labels, Set<Integer> targets, List<Issue> issues) {
    if (operands.size() <= operandIndex) {
      issues.add(new Issue(Severity.ERROR, index,
          "control-flow instruction is missing target operand " + operandIndex));
      return;
    }
    addNamedTarget(index, operands.get(operandIndex), labels, targets, issues);
  }

  private static void addNamedTarget(
      int index, String name, Map<String, Integer> labels,
      Set<Integer> targets, List<Issue> issues) {
    Integer target = labels.get(labelKey(name));
    if (target == null) {
      issues.add(new Issue(Severity.ERROR, index, "unresolved branch target '" + name + "'"));
    } else {
      targets.add(target);
    }
  }

  private static void addFallthrough(int index, int size, Set<Integer> targets) {
    if (index + 1 < size) targets.add(index + 1);
  }

  private static void auditExceptionMarkers(
      List<IrOperationModel> operations,
      Map<String, Integer> labels,
      Map<String, List<Integer>> markerPositions,
      List<Issue> issues) {
    for (int index = 0; index < operations.size(); index++) {
      IrOperationModel operation = operations.get(index);
      if (!isExceptionMarker(operation.kind())) continue;
      List<String> metadata = operation.operands();
      if (metadata.size() < 4) {
        issues.add(new Issue(Severity.ERROR, index,
            operation.kind() + " requires start, end, handler, and exception type"));
        continue;
      }
      String key = exceptionKey(metadata);
      markerPositions.computeIfAbsent(key, ignored -> new ArrayList<>()).add(index);
      String start = metadata.get(0);
      String end = metadata.get(1);
      String handler = metadata.get(2);
      if (!labels.containsKey(labelKey(start)) || !labels.containsKey(labelKey(end))
          || !labels.containsKey(labelKey(handler))) {
        issues.add(new Issue(Severity.ERROR, index,
            operation.kind() + " references an undefined protected-range label"));
        continue;
      }
      if (labels.get(labelKey(start)) >= labels.get(labelKey(end))) {
        issues.add(new Issue(Severity.ERROR, index,
            "exception protected range must start before its end label"));
      }
    }

    for (Map.Entry<String, List<Integer>> entry : markerPositions.entrySet()) {
      int begins = 0;
      int ends = 0;
      int catches = 0;
      for (int index : entry.getValue()) {
        String kind = operations.get(index).kind();
        if ("TRY_BEGIN".equals(kind)) begins++;
        else if ("TRY_END".equals(kind)) ends++;
        else if ("CATCH_BEGIN".equals(kind)) catches++;
      }
      if (begins != 1 || ends != 1 || catches != 1) {
        int first = entry.getValue().get(0);
        issues.add(new Issue(Severity.ERROR, first,
            "exception region requires exactly one TRY_BEGIN, TRY_END, and CATCH_BEGIN; found "
                + begins + "/" + ends + "/" + catches));
      }
    }
  }

  private static String exceptionKey(List<String> metadata) {
    return labelKey(metadata.get(0)) + "|" + labelKey(metadata.get(1)) + "|"
        + labelKey(metadata.get(2)) + "|" + metadata.get(3);
  }

  private static String labelKey(String label) {
    if (label == null) return "";
    String normalized = label.trim();
    while (normalized.length() >= 2 && normalized.startsWith("[") && normalized.endsWith("]"))
      normalized = normalized.substring(1, normalized.length() - 1).trim();
    if (normalized.endsWith(":")) normalized = normalized.substring(0, normalized.length() - 1).trim();
    return normalized;
  }

  private static boolean isExceptionMarker(String kind) {
    return "TRY_BEGIN".equals(kind) || "TRY_END".equals(kind) || "CATCH_BEGIN".equals(kind);
  }

  private static boolean isMetadata(IrOperationModel operation) {
    return "LABEL".equals(operation.kind()) || isExceptionMarker(operation.kind());
  }

  private static boolean isTerminal(IrOperationModel operation) {
    return "RETURN".equals(operation.kind()) || "THROW".equals(operation.kind())
        || "TERMINATE".equals(operation.kind());
  }

  private static Set<Integer> reachableFromEntry(
      int operationCount, Map<Integer, Set<Integer>> edges) {
    if (operationCount == 0) return Set.of();
    Set<Integer> visited = new LinkedHashSet<>();
    ArrayDeque<Integer> pending = new ArrayDeque<>();
    pending.add(0);
    while (!pending.isEmpty()) {
      int current = pending.removeFirst();
      if (current < 0 || current >= operationCount || !visited.add(current)) continue;
      for (int target : edges.getOrDefault(current, Set.of())) {
        if (!visited.contains(target)) pending.addLast(target);
      }
    }
    return visited;
  }

  public enum Severity { WARNING, ERROR }

  public record Issue(Severity severity, int operationIndex, String message) {
    public Issue {
      if (severity == null) throw new IllegalArgumentException("severity is required");
      if (operationIndex < 0) throw new IllegalArgumentException("operation index cannot be negative");
      if (message == null || message.isBlank()) throw new IllegalArgumentException("message is required");
    }
  }

  public record Report(
      Map<String, Integer> labels,
      Map<Integer, Set<Integer>> edges,
      List<Issue> issues,
      Set<Integer> reachableOperations,
      Set<Integer> unreachableOperations) {
    public Report {
      labels = Collections.unmodifiableMap(new LinkedHashMap<>(labels));
      Map<Integer, Set<Integer>> copiedEdges = new LinkedHashMap<>();
      edges.forEach((key, value) -> copiedEdges.put(key, Set.copyOf(value)));
      edges = Collections.unmodifiableMap(copiedEdges);
      issues = List.copyOf(issues);
      reachableOperations = Set.copyOf(reachableOperations);
      unreachableOperations = Set.copyOf(unreachableOperations);
    }

    public boolean valid() {
      return issues.stream().noneMatch(issue -> issue.severity() == Severity.ERROR);
    }

    public List<Issue> errors() {
      return issues.stream().filter(issue -> issue.severity() == Severity.ERROR).toList();
    }

    public List<Issue> warnings() {
      return issues.stream().filter(issue -> issue.severity() == Severity.WARNING).toList();
    }
  }
}