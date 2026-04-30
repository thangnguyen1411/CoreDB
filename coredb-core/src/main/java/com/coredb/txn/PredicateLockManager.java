package com.coredb.txn;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks SIREAD (predicate) locks for Serializable Snapshot Isolation.
 *
 * <p>SIREAD locks are informational — they never block. They record which pages each
 * serializable transaction has read, so the conflict-detection layer (13.5E) can find
 * rw-antidependency edges at write time.</p>
 *
 * <p>Granularity: one lock per {@code (tableOid, pageId)} pair. Both heap pages and
 * B-tree index leaf pages are locked on read.</p>
 *
 * <p>Lock release: at commit or rollback, not held past txn end (v1 simplification —
 * limits detection of the classic read-only anomaly where T1 commits before the pivot
 * is formed).</p>
 */
public final class PredicateLockManager {

    private final ConcurrentHashMap<PredicateLockTag, Set<Integer>> sireadLocks =
        new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, Set<PredicateLockTag>> tagsByXid =
        new ConcurrentHashMap<>();

    /**
     * Records that {@code xid} read the page at {@code (tableOid, pageId)}.
     *
     * <p>Idempotent — acquiring the same lock twice by the same xid is a no-op.</p>
     */
    public void acquireSiread(int xid, int tableOid, int pageId) {
        PredicateLockTag tag = new PredicateLockTag(tableOid, pageId);
        sireadLocks.computeIfAbsent(tag, k -> ConcurrentHashMap.newKeySet()).add(xid);
        tagsByXid.computeIfAbsent(xid, k -> ConcurrentHashMap.newKeySet()).add(tag);
    }

    /**
     * Returns the set of XIDs that hold a SIREAD lock on the given page.
     *
     * <p>The returned set is a live view; callers should not retain it across commits.</p>
     */
    public Set<Integer> readersOf(int tableOid, int pageId) {
        return sireadLocks.getOrDefault(new PredicateLockTag(tableOid, pageId), Set.of());
    }

    /**
     * Returns the set of predicate lock tags held by {@code xid}, or an empty set.
     */
    public Set<PredicateLockTag> locksHeldBy(int xid) {
        return tagsByXid.getOrDefault(xid, Set.of());
    }

    /**
     * Releases all SIREAD locks held by {@code xid} and removes its entries from
     * the reverse index. Called at commit and rollback.
     */
    public void releaseAll(int xid) {
        Set<PredicateLockTag> tags = tagsByXid.remove(xid);
        if (tags == null) {
            return;
        }
        for (PredicateLockTag tag : tags) {
            Set<Integer> readers = sireadLocks.get(tag);
            if (readers != null) {
                readers.remove(xid);
                if (readers.isEmpty()) {
                    sireadLocks.remove(tag, readers);
                }
            }
        }
    }

    /**
     * Returns a snapshot of all current predicate locks for diagnostic display.
     */
    public Map<PredicateLockTag, Set<Integer>> allLocks() {
        return Map.copyOf(sireadLocks);
    }
}
