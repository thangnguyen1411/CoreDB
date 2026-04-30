package com.coredb.util;

public final class SerializationFailureException extends CoreDBException {

    public SerializationFailureException(String reason) {
        super(reason);
    }
}
