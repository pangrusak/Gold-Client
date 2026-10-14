package com.goldclient.translator;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.ArrayDeque;
import java.util.Deque;

public final class TranslationContext {
  private final Set<String> declaredLocals = new LinkedHashSet<>();
  private final Deque<String> loopLabels = new ArrayDeque<>();

  public boolean declareLocal(String name) {
    return declaredLocals.add(name);
  }

  public boolean isLocalDeclared(String name) {
    return declaredLocals.contains(name);
  }

  public void enterLoop(String label) { loopLabels.push(label); }

  public void exitLoop() { loopLabels.pop(); }

  public boolean isActiveLoop(String label) { return loopLabels.contains(label); }
}
