package com.coredb.mvcc;

import com.coredb.heap.HeapTupleHeader;
import com.coredb.txn.ClogManager;
import com.coredb.util.Constants;

/**
 * MVCC tuple visibility predicate.
 *
 * <p>Determines whether a tuple is visible to a given snapshot using
 * PostgreSQL's MVCC visibility rules. The visibility check considers:</p>
 *
 * <ul>
 *   <li>The inserting transaction (xmin) and its status</li>
 *   <li>The deleting transaction (xmax) and its status</li>
 *   <li>The snapshot's view of which transactions were active</li>
 *   <li>The commit log (clog) for transaction status</li>
 * </ul>
 *
 * <p>PostgreSQL equivalent: {@code HeapTupleSatisfiesMVCC}</p>
 */
public final class TupleVisibility {

    private TupleVisibility() {}

    /**
     * Determines if a tuple is visible to the given snapshot.
     *
     * <p>Visibility is determined by checking:</p>
     * <ol>
     *   <li>Is the inserting transaction (xmin) visible to this snapshot?</li>
     *   <li>If the tuple has been deleted (xmax != 0), is the delete committed
     *       and visible to this snapshot?</li>
     * </ol>
     *
     * <p>A tuple is visible if its xmin is visible AND either:</p>
     * <ul>
     *   <li>It has not been deleted (xmax = 0), OR</li>
     *   <li>The delete is not committed from this snapshot's POV</li>
     * </ul>
     *
     * @param header the tuple header containing xmin/xmax
     * @param snapshot the snapshot defining visibility
     * @param clog the commit log for transaction status lookups
     * @return true if the tuple is visible to this snapshot
     */
    public static boolean isVisible(HeapTupleHeader header, Snapshot snapshot, ClogManager clog) {
        if (!xminVisible(header, snapshot, clog)) {
            return false;
        }

        int xmax = header.xmax();
        if (xmax == Constants.INVALID_XID) {
            return true;
        }

        return !xmaxCommittedAndVisible(header, snapshot, clog);
    }

    /**
     * Checks if the inserting transaction (xmin) is visible.
     *
     * <p>A tuple is visible if:</p>
     * <ul>
     *   <li>It was inserted by BOOTSTRAP_XID (always visible)</li>
     *   <li>Its xmin committed before the snapshot was taken</li>
     *   <li>Its xmin was not in-progress at snapshot time</li>
     * </ul>
     *
     * @param header the tuple header
     * @param snapshot the snapshot
     * @param clog the commit log
     * @return true if xmin is visible
     */
    private static boolean xminVisible(HeapTupleHeader header, Snapshot snapshot, ClogManager clog) {
        int xmin = header.xmin();

        if (xmin == Constants.BOOTSTRAP_XID) {
            return true;
        }

        if (xmin >= snapshot.xmax()) {
            return false;
        }

        if (snapshot.isActive(xmin)) {
            return false;
        }

        ClogManager.Status status = clog.getStatus(xmin);
        return status == ClogManager.Status.COMMITTED;
    }

    /**
     * Checks if a delete is committed and visible to this snapshot.
     *
     * <p>A delete is visible (meaning the tuple should be hidden) if:</p>
     * <ul>
     *   <li>The delete committed before the snapshot was taken</li>
     *   <li>The deleting transaction was not in-progress at snapshot time</li>
     * </ul>
     *
     * @param header the tuple header
     * @param snapshot the snapshot
     * @param clog the commit log
     * @return true if the delete is committed and visible
     */
    private static boolean xmaxCommittedAndVisible(HeapTupleHeader header, Snapshot snapshot, ClogManager clog) {
        int xmax = header.xmax();

        if (xmax >= snapshot.xmax()) {
            return false;
        }

        if (snapshot.isActive(xmax)) {
            return false;
        }

        ClogManager.Status status = clog.getStatus(xmax);
        return status == ClogManager.Status.COMMITTED;
    }
}
