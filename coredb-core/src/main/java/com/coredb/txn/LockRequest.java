package com.coredb.txn;

record LockRequest(int xid, LockTag tag, LockMode mode, Thread thread) {}
