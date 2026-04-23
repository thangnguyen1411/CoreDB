package com.coredb.util;

public class TxnException extends CoreDBException {

    public TxnException(String message) {
        super(message);
    }

    public TxnException(String message, Throwable cause) {
        super(message, cause);
    }
}
