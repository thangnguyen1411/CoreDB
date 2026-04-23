package com.coredb.util;

public class CorruptionException extends CoreDBException {

    public CorruptionException(String message) {
        super(message);
    }

    public CorruptionException(String message, Throwable cause) {
        super(message, cause);
    }
}
