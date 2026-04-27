package com.coredb.mvcc;

import java.util.Collections;
import java.util.Set;

/**
 * MVCC snapshot capturing a point-in-time view of transaction state.
 *
 * <p>A snapshot is immutable — it captures the state of active transactions
 * at the moment it was taken and never changes. This is the foundation of
 * REPEATABLE READ isolation: all queries in a transaction use the same
 * snapshot.</p>
 *
 * <p>PostgreSQL equivalent: {@code SnapshotData}</p>
 *
 * @param xmin every xid < xmin is decided (committed or aborted)
 * @param xmax every xid >= xmax has not started yet
 * @param activeXids in-progress xids in the range [xmin, xmax)
 */
public record Snapshot(int xmin, int xmax, Set<Integer> activeXids) {

    /**
     * Bootstrap snapshot that sees everything.
     *
     * <p>This snapshot is used for catalog bootstrap and VACUUM reads.
     * By construction, it satisfies every visibility check:</p>
     * <ul>
     *   <li>No xmin can be < Integer.MAX_VALUE, so all xids appear committed</li>
     *   <li>No xmax can be >= Integer.MAX_VALUE, so no xids are "in the future"</li>
     *   <li>The active set is empty, so no xids appear in-progress</li>
     * </ul>
     */
    public static final Snapshot BOOTSTRAP =
            new Snapshot(Integer.MAX_VALUE, Integer.MAX_VALUE, Collections.emptySet());

    /**
     * Creates a snapshot with defensive copy of active XIDs.
     *
     * <p>The activeXids set is copied to ensure the snapshot remains immutable
     * even if the caller modifies the original set.</p>
     */
    public Snapshot {
        activeXids = Set.copyOf(activeXids);
    }

    /**
     * Returns true if the given XID was in-progress at snapshot time.
     *
     * @param xid the transaction ID to check
     * @return true if xid is in the active set
     */
    public boolean isActive(int xid) {
        return activeXids.contains(xid);
    }
}
