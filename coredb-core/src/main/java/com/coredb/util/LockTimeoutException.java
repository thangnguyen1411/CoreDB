package com.coredb.util;

public final class LockTimeoutException extends TxnException {

    public LockTimeoutException(int xid, long timeoutMs) {
        super("lock timeout after " + timeoutMs + "ms: xid=" + xid);
    }
}
