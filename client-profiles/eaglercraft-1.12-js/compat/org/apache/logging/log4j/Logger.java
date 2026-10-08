package org.apache.logging.log4j;

public interface Logger {
  void warn(String message, Object first, Object second);

  void warn(String message, Throwable cause);

  void error(String message, Object argument);

  void error(String message, Object first, Object second);
}
