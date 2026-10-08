package org.apache.logging.log4j;

public final class LogManager {
  private LogManager() {
  }

  public static Logger getLogger(String name) {
    return new ConsoleLogger(name);
  }

  public static Logger getLogger() {
    return getLogger("root");
  }

  private static final class ConsoleLogger implements Logger {
    private final String name;

    private ConsoleLogger(String name) {
      this.name = name;
    }

    @Override
    public void warn(String message, Object first, Object second) {
      System.err.println("WARN [" + name + "] "
          + format(message, first, second));
    }

    @Override
    public void warn(String message, Throwable cause) {
      System.err.println("WARN [" + name + "] " + message + ": " + cause);
    }

    @Override
    public void error(String message, Object argument) {
      System.err.println("ERROR [" + name + "] " + format(message, argument, null));
    }

    @Override
    public void error(String message, Object first, Object second) {
      System.err.println("ERROR [" + name + "] " + format(message, first, second));
    }

    private static String format(String message, Object first, Object second) {
      StringBuilder result = new StringBuilder(message.length());
      int argument = 0;
      for (int index = 0; index < message.length(); index++) {
        if (index + 1 < message.length()
            && message.charAt(index) == '{'
            && message.charAt(index + 1) == '}'
            && argument < 2) {
          Object value = argument++ == 0 ? first : second;
          result.append(value == null ? "null" : value);
          index++;
        } else {
          result.append(message.charAt(index));
        }
      }
      return result.toString();
    }
  }
}
