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
  private final Deque<BreakScope> breakScopes = new ArrayDeque<>();
  private final Deque<Set<String>> suppressedTryHandlers = new ArrayDeque<>();

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
  }

  public void exitLoopExit() {
    loopExitLabels.pop();
    breakScopes.pop();
  }

  /** A join label of the switch statement currently being rendered. */
  public void enterSwitchJoin(String label) {
    breakScopes.push(new BreakScope(label, BreakKind.SWITCH_JOIN));
  }

  public void exitSwitchJoin() { breakScopes.pop(); }

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
}
