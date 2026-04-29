package com.coredb.txn;

import com.coredb.util.DeadlockException;
import com.coredb.util.LockTimeoutException;

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
     * <p>Returns true if the lock is granted. Re-entrant: same xid acquiring the same
     * tag a second time is granted immediately with a single holder entry.
     *
     * @throws DeadlockException   if granting would create a cycle in the waits-for graph
     * @throws LockTimeoutException if timeoutMs elapses before the lock can be granted
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

            long nanosRemaining = TimeUnit.MILLISECONDS.toNanos(timeoutMs);
            while (!lock.canGrant(xid, mode)) {
                if (nanosRemaining <= 0) {
                    throw new LockTimeoutException(xid, timeoutMs);
                }
                if (deadlockCheck(xid, tag)) {
                    throw new DeadlockException(xid);
                }
                LockRequest req = new LockRequest(xid, tag, mode, Thread.currentThread());
                lock.waitQueue().addLast(req);
                try {
                    nanosRemaining = lock.condition().awaitNanos(nanosRemaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new LockTimeoutException(xid, timeoutMs);
                } finally {
                    lock.waitQueue().removeIf(r -> r.thread() == Thread.currentThread());
                }
            }
            lock.grant(xid, mode);
            trackLock(xid, tag);
            return true;
        } finally {
            lock.mutex().unlock();
        }
    }

    /**
     * Returns true if granting (waitingXid → blockingTag) would create a cycle.
     *
     * Called while holding blockingTag's mutex. Builds the waits-for graph on the fly
     * using tryLock() on other lock entries to avoid blocking.
     */
    private boolean deadlockCheck(int waitingXid, LockTag blockingTag) {
        Lock blockingLock = lockTable.get(blockingTag);
        if (blockingLock == null) return false;
        Set<Integer> holders = new HashSet<>(blockingLock.holderXids());
        Set<Integer> visited = new HashSet<>();
        for (int holder : holders) {
            if (canReachViaWaitsFor(holder, waitingXid, visited)) {
                return true;
            }
        }
        return false;
    }

    /**
     * DFS through the waits-for graph. Returns true if `from` can reach `target`.
     *
     * Uses tryLock() on each lock entry so it never blocks. Edges that cannot be
     * inspected (tryLock failed) are skipped — a conservative miss, not a false positive.
     */
    private boolean canReachViaWaitsFor(int from, int target, Set<Integer> visited) {
        if (from == target) return true;
        if (!visited.add(from)) return false;

        for (Map.Entry<LockTag, Lock> entry : lockTable.entrySet()) {
            Lock other = entry.getValue();
            if (!other.mutex().tryLock()) continue;
            try {
                boolean fromIsWaiting = other.waitQueue().stream().anyMatch(r -> r.xid() == from);
                if (fromIsWaiting) {
                    for (int holder : new HashSet<>(other.holderXids())) {
                        if (canReachViaWaitsFor(holder, target, visited)) {
                            return true;
                        }
                    }
                }
            } finally {
                other.mutex().unlock();
            }
        }
        return false;
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
