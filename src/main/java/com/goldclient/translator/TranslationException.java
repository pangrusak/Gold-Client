package com.goldclient.translator;

/**
 * Thrown when TeaVM compilation fails or configuration is invalid.
 * Callers should catch this and exit with a non-zero code.
 */
public class TranslationException extends Exception {
    public TranslationException(String message) {
        super(message);
    }

    public TranslationException(String message, Throwable cause) {
        super(message, cause);
    }
}
