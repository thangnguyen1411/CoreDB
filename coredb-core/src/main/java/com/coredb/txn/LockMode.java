package com.coredb.txn;

public enum LockMode {
    SHARE,      // multiple holders OK; blocks EXCLUSIVE
    EXCLUSIVE   // one holder; blocks everyone
}
