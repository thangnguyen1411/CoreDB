package com.coredb.mvcc;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages snapshot creation and the active transaction set.
 *
 * <p>Tracks in-progress transactions and creates snapshots that capture
 * the transaction state at a point in time. The active set is maintained
 * by callers using {@link #registerActiveXid} and {@link #unregisterActiveXid}.</p>
 *
 * <p><strong>Snapshot timing for REPEATABLE READ:</strong></p>
 * <ol>
 *   <li>Transaction allocates XID</li>
 *   <li>Transaction registers itself as active</li>
 *   <li>Snapshot is taken, capturing the active set including self</li>
 * </ol>
 *
 * <p>This ordering ensures that a transaction's own XID is visible as active
 * in its snapshot, preventing it from seeing its own uncommitted changes
 * (though future implementations may special-case this).</p>
 *
 * <p>PostgreSQL equivalent: {@code GetSnapshotData}</p>
 */
public final class SnapshotManager {

    private final AtomicInteger nextXid;
    private final Set<Integer> activeXids;

    /**
     * Creates a new SnapshotManager.
     *
     * @param nextXid the next XID to be allocated (initially FIRST_NORMAL_XID = 3)
     */
    public SnapshotManager(int nextXid) {
        this.nextXid = new AtomicInteger(nextXid);
        this.activeXids = new HashSet<>();
    }

    /**
     * Takes a snapshot of the current transaction state.
     *
     * <p>The snapshot captures:</p>
     * <ul>
     *   <li>{@code xmin}: minimum of active XIDs, or nextXid if none active</li>
     *   <li>{@code xmax}: next XID to be allocated (all xids >= xmax are "future")</li>
     *   <li>{@code activeXids}: copy of currently registered active XIDs</li>
     * </ul>
     *
     * <p>The active set is copied so subsequent register/unregister calls
     * don't affect the snapshot.</p>
     *
     * @return a new Snapshot capturing current transaction state
     */
    public synchronized Snapshot takeSnapshot() {
        int xmax = nextXid.get();

        int xmin;
        if (activeXids.isEmpty()) {
            xmin = xmax;
        } else {
            xmin = activeXids.stream().mapToInt(Integer::intValue).min().orElse(xmax);
        }

        return new Snapshot(xmin, xmax, activeXids);
    }

    /**
     * Registers a transaction as active.
     *
     * <p>Must be called before taking a snapshot that should include this XID.
     * Called immediately after XID allocation in {@code beginTransaction()}.</p>
     *
     * @param xid the transaction ID to register
     */
    public synchronized void registerActiveXid(int xid) {
        activeXids.add(xid);
    }

    /**
     * Unregisters a transaction from the active set.
     *
     * <p>Called at commit or rollback to remove the transaction from activeXids.
     * Subsequent snapshots will see this XID as "decided" (committed or aborted).</p>
     *
     * @param xid the transaction ID to unregister
     */
    public synchronized void unregisterActiveXid(int xid) {
        activeXids.remove(xid);
    }

    /**
     * Returns the oldest active XID (minimum xmin).
     *
     * <p>Used by VACUUM to determine the horizon below which dead tuples
     * can be safely reclaimed. If no transactions are active, returns nextXid.</p>
     *
     * @return the oldest XID among active transactions, or nextXid if none
     */
    public synchronized int oldestActiveXmin() {
        if (activeXids.isEmpty()) {
            return nextXid.get();
        }
        return activeXids.stream().mapToInt(Integer::intValue).min().orElse(nextXid.get());
    }

    /**
     * Allocates a new transaction ID.
     *
     * <p>Atomically increments and returns the next XID. The returned XID
     * should be registered as active before taking a snapshot.</p>
     *
     * @return the newly allocated XID
     */
    public int allocateXid() {
        return nextXid.getAndIncrement();
    }

    /**
     * Returns the next XID that would be allocated.
     *
     * @return the current nextXid value
     */
    public int nextXid() {
        return nextXid.get();
    }

    /**
     * Returns the number of currently active transactions.
     *
     * @return size of activeXids set
     */
    public synchronized int activeCount() {
        return activeXids.size();
    }
}
