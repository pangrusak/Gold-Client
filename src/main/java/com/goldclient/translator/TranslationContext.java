package com.goldclient.translator;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.ArrayDeque;
import java.util.Deque;

public final class TranslationContext {
  private final Set<String> declaredLocals = new LinkedHashSet<>();
  private final Deque<String> loopLabels = new ArrayDeque<>();
  private final Set<String> loopJsLabels = new LinkedHashSet<>();
  private final Deque<String> loopExitLabels = new ArrayDeque<>();
  private final java.util.Map<String, String> loopExitOwners = new java.util.HashMap<>();
  private final Deque<BreakScope> breakScopes = new ArrayDeque<>();
  private final Deque<Set<String>> suppressedTryHandlers = new ArrayDeque<>();
  private final Deque<java.util.Map<String, String>> skipExitLabels = new ArrayDeque<>();
  private boolean suppressNestedTryRegions;

  private enum BreakKind { LOOP, LOOP_EXIT, SWITCH_JOIN }

  private record BreakScope(String label, BreakKind kind) {}

  public boolean declareLocal(String name) {
    return declaredLocals.add(name);
  }

  public boolean isLocalDeclared(String name) {
    return declaredLocals.contains(name);
  }

  public void enterLoop(String label) {
    enterLoop(label, false);
  }

  /**
   * Enters a loop, optionally emitting a JavaScript label for it. A nested
   * loop's {@code continue} for an outer loop needs that label to keep the
   * jump from binding to the innermost loop.
   */
  public void enterLoop(String label, boolean jsLabeled) {
    loopLabels.push(label);
    if (jsLabeled) loopJsLabels.add(label);
    breakScopes.push(new BreakScope(label, BreakKind.LOOP));
  }

  public void exitLoop() {
    loopJsLabels.remove(loopLabels.pop());
    breakScopes.pop();
  }

  public boolean hasLoopLabel(String label) { return loopJsLabels.contains(label); }

  public boolean isActiveLoop(String label) { return loopLabels.contains(label); }

  /** True when the label is the innermost loop being rendered. */
  public boolean isInnermostLoop(String label) {
    return !loopLabels.isEmpty() && loopLabels.peek().equals(label);
  }

  public void enterLoopExit(String label) {
    loopExitLabels.push(label);
    breakScopes.push(new BreakScope(label, BreakKind.LOOP_EXIT));
    String loop = loopLabels.peek();
    if (loop != null && loopJsLabels.contains(loop)) loopExitOwners.put(label, loop);
  }

  public void exitLoopExit() {
    loopExitOwners.remove(loopExitLabels.pop());
    breakScopes.pop();
  }

  /**
   * The JavaScript label of the loop whose exit this is, or null when the loop
   * was not labeled. A jump to the exit from inside a nested switch or loop
   * needs it: a plain {@code break} would leave the nested scope instead.
   */
  public String loopExitLabel(String label) { return loopExitOwners.get(label); }

  /** A join label of the switch statement currently being rendered. */
  public void enterSwitchJoin(String label) {
    breakScopes.push(new BreakScope(label, BreakKind.SWITCH_JOIN));
  }

  public void exitSwitchJoin() { breakScopes.pop(); }

  /**
   * Registers a forward label that is outside the loop's own continuation and
   * therefore needs an explicit block label: a {@code break} to it must skip
   * the statement itself and everything rendered between the loop and the
   * target, which only a labeled block can express.
   */
  public void enterSkipExit(String label, String jsLabel) {
    skipExitLabels.push(java.util.Map.of(label, jsLabel));
  }

  public void exitSkipExit() { skipExitLabels.pop(); }

  public String skipExitLabel(String label) {
    for (java.util.Map<String, String> scope : skipExitLabels) {
      String value = scope.get(label);
      if (value != null) return value;
    }
    return null;
  }

  /**
   * True when the label is the exit of a breakable scope (a loop rendered with
   * breaks or a switch statement) currently being rendered. A {@code break}
   * for it is only emitted when it is also the innermost scope, so a nested
   * loop cannot swallow the jump. Plain loop headers are not break targets:
   * they are {@code continue} destinations.
   */
  public boolean isBreakTarget(String label) {
    for (BreakScope scope : breakScopes) {
      if (scope.kind() != BreakKind.LOOP && scope.label().equals(label)) return true;
    }
    return false;
  }

  public boolean isInnermostBreakTarget(String label) {
    return !breakScopes.isEmpty() && breakScopes.peek().kind() != BreakKind.LOOP
        && breakScopes.peek().label().equals(label);
  }

  /**
   * True when the label is the join of the innermost switch statement and no
   * loop or other switch has been entered since.
   */
  public boolean isInnermostSwitchJoin(String label) {
    return !breakScopes.isEmpty() && breakScopes.peek().kind() == BreakKind.SWITCH_JOIN
        && breakScopes.peek().label().equals(label);
  }

  /**
   * True when the label is the exit of the innermost loop currently rendered
   * as {@code while (true)} with explicit breaks. Branches and jumps to that
   * label become {@code break} statements instead of unresolved jumps.
   */
  public boolean isLoopExit(String label) {
    return !loopExitLabels.isEmpty() && loopExitLabels.peek().equals(label);
  }

  /**
   * Inside a catch clause, exception-table regions that target one of the
   * enclosing try's own handlers repeat that handler over this block. The
   * handler already runs as a sibling clause, so such regions are plumbing and
   * are skipped instead of rendered as a nested try/catch.
   */
  public void enterSuppressedTryHandlers(Set<String> handlerLabels) {
    suppressedTryHandlers.push(handlerLabels);
  }

  public void exitSuppressedTryHandlers() { suppressedTryHandlers.pop(); }

  public boolean isSuppressedTryHandler(String handlerLabel) {
    if (handlerLabel == null) return false;
    for (Set<String> handlers : suppressedTryHandlers) {
      if (handlers.contains(handlerLabel)) return true;
    }
    return false;
  }

  /**
   * While rendering a try whose exception table was split into many disjoint
   * protected ranges (javac's string switch emits one range per case), nested
   * TRY_BEGIN markers are part of that same merged handler. They must not be
   * structured as independent try/catch regions.
   */
  public boolean suppressesNestedTryRegions() { return suppressNestedTryRegions; }

  public void setSuppressNestedTryRegions(boolean value) { suppressNestedTryRegions = value; }
}
