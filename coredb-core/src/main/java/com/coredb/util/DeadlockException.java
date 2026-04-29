package com.coredb.util;

public final class DeadlockException extends TxnException {

    private final int victimXid;

    public DeadlockException(int victimXid) {
        super("deadlock detected: xid=" + victimXid + " chosen as victim");
        this.victimXid = victimXid;
    }

    public int victimXid() {
        return victimXid;
    }
}
