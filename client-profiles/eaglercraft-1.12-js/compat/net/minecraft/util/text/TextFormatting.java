package net.minecraft.util.text;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

public final class TextFormatting {
  private static final Pattern FORMATTING_CODE = Pattern.compile("(?i)§[0-9A-FK-OR]");
  private static final Map<String, TextFormatting> FRIENDLY_NAMES = Map.ofEntries(
      value("black", '0'), value("dark_blue", '1'), value("dark_green", '2'),
      value("dark_aqua", '3'), value("dark_red", '4'), value("dark_purple", '5'),
      value("gold", '6'), value("gray", '7'), value("dark_gray", '8'),
      value("blue", '9'), value("green", 'a'), value("aqua", 'b'),
      value("red", 'c'), value("light_purple", 'd'), value("yellow", 'e'),
      value("white", 'f'), value("obfuscated", 'k'), value("bold", 'l'),
      value("strikethrough", 'm'), value("underline", 'n'), value("italic", 'o'),
      value("reset", 'r'));

  private final char code;

  private TextFormatting(char code) {
    this.code = code;
  }

  private static Map.Entry<String, TextFormatting> value(String name, char code) {
    return Map.entry(name, new TextFormatting(code));
  }

  public static TextFormatting func_96300_b(String name) {
    return name == null ? null : FRIENDLY_NAMES.get(name.toLowerCase(Locale.ROOT));
  }

  public static String func_110646_a(String text) {
    return text == null ? null : FORMATTING_CODE.matcher(text).replaceAll("");
  }

  @Override
  public String toString() {
    return "\u00a7" + code;
  }
}
