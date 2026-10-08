package net.minecraft.client.resources;

public final class LanguageManager {
  private Language currentLanguage;

  public LanguageManager(Language currentLanguage) {
    func_135045_a(currentLanguage);
  }

  public Language func_135041_c() {
    return currentLanguage;
  }

  public void func_135045_a(Language language) {
    if (language == null) {
      throw new IllegalArgumentException(
          "The client language must not be null");
    }
    currentLanguage = language;
  }
}