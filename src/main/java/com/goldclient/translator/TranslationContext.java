package com.goldclient.translator;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.ArrayDeque;
import java.util.Deque;

public final class TranslationContext {
  private final Set<String> declaredLocals = new LinkedHashSet<>();
  private final Deque<String> loopLabels = new ArrayDeque<>();
  private final Deque<String> loopExitLabels = new ArrayDeque<>();

  public boolean declareLocal(String name) {
    return declaredLocals.add(name);
  }

  public boolean isLocalDeclared(String name) {
    return declaredLocals.contains(name);
  }

  public void enterLoop(String label) { loopLabels.push(label); }

  public void exitLoop() { loopLabels.pop(); }

  public boolean isActiveLoop(String label) { return loopLabels.contains(label); }

  public void enterLoopExit(String label) { loopExitLabels.push(label); }

  public void exitLoopExit() { loopExitLabels.pop(); }

  /**
   * True when the label is the exit of the innermost loop currently rendered
   * as {@code while (true)} with explicit breaks. Branches and jumps to that
   * label become {@code break} statements instead of unresolved jumps.
   */
  public boolean isLoopExit(String label) {
    return !loopExitLabels.isEmpty() && loopExitLabels.peek().equals(label);
  }
}
