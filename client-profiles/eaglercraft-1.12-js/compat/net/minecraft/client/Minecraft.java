package net.minecraft.client;

import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.resources.Language;
import net.minecraft.client.resources.LanguageManager;

public final class Minecraft {
  public EntityPlayerSP field_71439_g;

  private static Minecraft clientContext;
  private final LanguageManager languageManager;

  private Minecraft(Language language) {
    this.languageManager = new LanguageManager(language);
  }

  public static Minecraft func_71410_x() {
    if (clientContext == null) {
      throw new IllegalStateException(
          "The supplied client context has not been initialized");
    }
    return clientContext;
  }

  public LanguageManager func_135016_M() {
    return languageManager;
  }

  public static void setClientContext(String languageCode) {
    if (languageCode == null || languageCode.isBlank()) {
      throw new IllegalArgumentException(
          "The client language code must not be empty");
    }

    String normalizedCode = languageCode.replace('-', '_');

    Language language = new Language(
        normalizedCode,
        "",
        normalizedCode,
        false);

    if (clientContext == null) {
      clientContext = new Minecraft(language);
    } else {
      clientContext.languageManager.func_135045_a(language);
    }
  }
}