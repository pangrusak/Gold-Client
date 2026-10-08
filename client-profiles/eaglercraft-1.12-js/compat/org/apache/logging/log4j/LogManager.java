package org.apache.logging.log4j;

public final class LogManager {
  private LogManager() {
  }

  public static Logger getLogger(String name) {
    return new ConsoleLogger(name);
  }

  private static final class ConsoleLogger implements Logger {
    private final String name;

    private ConsoleLogger(String name) {
      this.name = name;
    }

    @Override
    public void warn(String message, Throwable cause) {
      System.err.println("WARN [" + name + "] " + message + ": " + cause);
    }
  }
}
