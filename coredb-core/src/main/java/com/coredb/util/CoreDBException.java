package com.coredb.util;

public class CoreDBException extends RuntimeException {

    public CoreDBException(String message) {
        super(message);
    }

    public CoreDBException(String message, Throwable cause) {
        super(message, cause);
    }
}
