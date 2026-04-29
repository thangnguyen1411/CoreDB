package com.coredb.txn;

public record LockTag(int tableOid, LockType type) {

    public enum LockType { TABLE }
}
