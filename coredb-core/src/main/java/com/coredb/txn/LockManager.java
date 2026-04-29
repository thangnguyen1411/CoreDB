package com.coredb.txn;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public final class LockManager {

    public record WaiterInfo(int xid, LockMode mode) {}

    public record LockSnapshot(
        LockTag tag,
        Map<Integer, LockMode> holders,
        List<WaiterInfo> waiters
    ) {}

    private final ConcurrentHashMap<LockTag, Lock> lockTable = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, Set<LockTag>> xidLocks = new ConcurrentHashMap<>();

    /**
     * Acquires a lock for xid on the given tag in the given mode.
     *
     * <p>Returns true immediately if the lock can be granted (including re-entrant
     * acquisition by the same xid). If blocked, waits up to timeoutMs milliseconds.
     * Returns false if the timeout expires without acquiring the lock.
     *
     * <p>Re-entrant: if xid already holds any lock on this tag, the call succeeds
     * immediately with a single holder entry.
     */
    public boolean acquire(int xid, LockTag tag, LockMode mode, long timeoutMs) {
        Lock lock = lockTable.computeIfAbsent(tag, Lock::new);
        lock.mutex().lock();
        try {
            if (lock.canGrant(xid, mode)) {
                lock.grant(xid, mode);
                trackLock(xid, tag);
                return true;
            }

            LockRequest req = new LockRequest(xid, tag, mode, Thread.currentThread());
            lock.waitQueue().addLast(req);
            long nanosRemaining = TimeUnit.MILLISECONDS.toNanos(timeoutMs);
            try {
                while (!lock.canGrant(xid, mode)) {
                    if (nanosRemaining <= 0) {
                        lock.waitQueue().removeIf(r -> r.thread() == Thread.currentThread());
                        return false;
                    }
                    nanosRemaining = lock.condition().awaitNanos(nanosRemaining);
                }
                lock.waitQueue().removeIf(r -> r.thread() == Thread.currentThread());
                lock.grant(xid, mode);
                trackLock(xid, tag);
                return true;
            } catch (InterruptedException e) {
                lock.waitQueue().removeIf(r -> r.thread() == Thread.currentThread());
                Thread.currentThread().interrupt();
                return false;
            }
        } finally {
            lock.mutex().unlock();
        }
    }

    /**
     * Releases the lock held by xid on tag and wakes any waiters.
     */
    public void release(int xid, LockTag tag) {
        Lock lock = lockTable.get(tag);
        if (lock == null) return;
        lock.mutex().lock();
        try {
            lock.release(xid);
            lock.condition().signalAll();
        } finally {
            lock.mutex().unlock();
        }
        untrackLock(xid, tag);
    }

    /**
     * Releases all locks held by xid and wakes waiters on each.
     * Called by TransactionManager at commit/rollback.
     */
    public void releaseAll(int xid) {
        Set<LockTag> tags = xidLocks.remove(xid);
        if (tags == null) return;
        for (LockTag tag : new HashSet<>(tags)) {
            Lock lock = lockTable.get(tag);
            if (lock == null) continue;
            lock.mutex().lock();
            try {
                lock.release(xid);
                lock.condition().signalAll();
            } finally {
                lock.mutex().unlock();
            }
        }
    }

    /**
     * Returns the set of XIDs currently holding a lock on tag.
     */
    public Set<Integer> holdersOf(LockTag tag) {
        Lock lock = lockTable.get(tag);
        if (lock == null) return Set.of();
        lock.mutex().lock();
        try {
            return new HashSet<>(lock.holderXids());
        } finally {
            lock.mutex().unlock();
        }
    }

    /**
     * Returns the set of LockTags currently held by xid.
     */
    public Set<LockTag> locksHeldBy(int xid) {
        Set<LockTag> tags = xidLocks.get(xid);
        return tags == null ? Set.of() : new HashSet<>(tags);
    }

    /**
     * Returns a diagnostic snapshot of all active locks, ordered for display.
     */
    public List<LockSnapshot> allLocks() {
        List<LockSnapshot> result = new ArrayList<>();
        for (Map.Entry<LockTag, Lock> entry : lockTable.entrySet()) {
            Lock lock = entry.getValue();
            lock.mutex().lock();
            try {
                if (!lock.holderXids().isEmpty() || !lock.waitQueue().isEmpty()) {
                    List<WaiterInfo> waiters = new ArrayList<>();
                    for (LockRequest req : lock.waitQueue()) {
                        waiters.add(new WaiterInfo(req.xid(), req.mode()));
                    }
                    result.add(new LockSnapshot(
                        entry.getKey(),
                        lock.holdersCopy(),
                        waiters
                    ));
                }
            } finally {
                lock.mutex().unlock();
            }
        }
        return result;
    }

    private void trackLock(int xid, LockTag tag) {
        xidLocks.computeIfAbsent(xid, k -> ConcurrentHashMap.newKeySet()).add(tag);
    }

    private void untrackLock(int xid, LockTag tag) {
        Set<LockTag> tags = xidLocks.get(xid);
        if (tags != null) {
            tags.remove(tag);
        }
    }
}
