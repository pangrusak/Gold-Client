package com.goldclient.translator;

import java.util.LinkedHashSet;
import java.util.Set;

public final class TranslationContext {
  private final Set<String> declaredLocals = new LinkedHashSet<>();

  public boolean declareLocal(String name) {
    return declaredLocals.add(name);
  }

  public boolean isLocalDeclared(String name) {
    return declaredLocals.contains(name);
  }
}
