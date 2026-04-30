package com.coredb.txn;

public enum IsolationLevel {
    READ_UNCOMMITTED,
    READ_COMMITTED,
    REPEATABLE_READ,
    SERIALIZABLE;

    /**
     * MVCC cannot expose uncommitted data — READ_UNCOMMITTED aliases to READ_COMMITTED.
     * All engine code branches on the canonical level; READ_UNCOMMITTED is never visible
     * past this call.
     */
    public IsolationLevel canonical() {
        return this == READ_UNCOMMITTED ? READ_COMMITTED : this;
    }
}
