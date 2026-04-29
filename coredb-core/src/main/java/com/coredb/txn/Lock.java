package com.coredb.txn;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

final class Lock {

    private final LockTag tag;
    private final Map<Integer, LockMode> holders = new HashMap<>();
    private final Deque<LockRequest> waitQueue = new ArrayDeque<>();
    private final ReentrantLock mutex = new ReentrantLock();
    private final Condition condition = mutex.newCondition();

    Lock(LockTag tag) {
        this.tag = tag;
    }

    LockTag tag() { return tag; }

    ReentrantLock mutex() { return mutex; }

    Condition condition() { return condition; }

    Deque<LockRequest> waitQueue() { return waitQueue; }

    boolean canGrant(int xid, LockMode mode) {
        if (holders.containsKey(xid)) return true;
        if (mode == LockMode.EXCLUSIVE) return holders.isEmpty();
        return holders.values().stream().noneMatch(m -> m == LockMode.EXCLUSIVE);
    }

    void grant(int xid, LockMode mode) { holders.put(xid, mode); }

    void release(int xid) { holders.remove(xid); }

    boolean isHeldBy(int xid) { return holders.containsKey(xid); }

    Set<Integer> holderXids() { return holders.keySet(); }

    Map<Integer, LockMode> holdersCopy() { return new HashMap<>(holders); }
}
