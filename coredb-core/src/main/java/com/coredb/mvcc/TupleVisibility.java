package com.coredb.mvcc;

import com.coredb.heap.HeapTupleHeader;
import com.coredb.txn.ClogManager;
import com.coredb.util.Constants;

/**
 * MVCC tuple visibility predicate.
 *
 * <p>Determines whether a tuple is visible to a given snapshot using
 * PostgreSQL's MVCC visibility rules. Also caches clog lookup results as
 * {@code t_infomask} hint bits on the header object. The caller is responsible
 * for writing the mutated header back to the page buffer when hint bits change.</p>
 *
 * <p>PostgreSQL equivalent: {@code HeapTupleSatisfiesMVCC}</p>
 */
public final class TupleVisibility {

    private TupleVisibility() {}

    /**
     * Determines if a tuple is visible to the given snapshot.
     *
     * <p>Delegates to {@link #isVisible(HeapTupleHeader, Snapshot, ClogManager, int)}
     * with {@code currentXid = INVALID_XID} (no within-transaction self-visibility).</p>
     */
    public static boolean isVisible(HeapTupleHeader header, Snapshot snapshot, ClogManager clog) {
        return isVisible(header, snapshot, clog, Constants.INVALID_XID);
    }

    /**
     * Determines if a tuple is visible to the given snapshot, accounting for
     * within-transaction self-visibility.
     *
     * <p>May set {@code XMIN_COMMITTED}, {@code XMIN_INVALID}, {@code XMAX_COMMITTED},
     * or {@code XMAX_INVALID} hint bits on {@code header} as a side-effect when a
     * clog lookup returns a decisive result. The caller must write the header back to
     * the page buffer if {@code header.infomask()} changed. No WAL record is emitted
     * for hint-bit mutations — they are a recomputable clog cache.</p>
     *
     * @param header the tuple header containing xmin/xmax; may be mutated with hint bits
     * @param snapshot the snapshot defining visibility
     * @param clog the commit log for transaction status lookups
     * @param currentXid the XID of the calling transaction, or {@code INVALID_XID} if none;
     *                   used so a transaction can see its own writes and deletions
     * @return true if the tuple is visible to this snapshot
     */
    public static boolean isVisible(HeapTupleHeader header, Snapshot snapshot, ClogManager clog, int currentXid) {
        if (!xminVisible(header, snapshot, clog, currentXid)) {
            return false;
        }

        int xmax = header.xmax();
        if (xmax == Constants.INVALID_XID) {
            return true;
        }

        return !xmaxCommittedAndVisible(header, snapshot, clog, currentXid);
    }

    private static boolean xminVisible(HeapTupleHeader header, Snapshot snapshot, ClogManager clog, int currentXid) {
        int xmin = header.xmin();

        // Special XIDs are unconditionally visible without clog lookup.
        if (xmin == Constants.BOOTSTRAP_XID || xmin == Constants.FROZEN_XID) {
            return true;
        }

        // A transaction always sees its own inserts.
        if (currentXid != Constants.INVALID_XID && xmin == currentXid) {
            return true;
        }

        // Fast path: hint bits set by a previous visibility check skip the clog.
        if (header.hasInfomaskFlag(HeapTupleHeader.XMIN_INVALID)) {
            return false;
        }
        if (header.hasInfomaskFlag(HeapTupleHeader.XMIN_COMMITTED)) {
            // xmin is known committed; snapshot check still applies.
            if (xmin >= snapshot.xmax()) return false;
            if (snapshot.isActive(xmin)) return false;
            return true;
        }

        // Snapshot checks — no clog needed yet.
        if (xmin >= snapshot.xmax()) return false;
        if (snapshot.isActive(xmin)) return false;

        // Decisive clog lookup: cache the result as a hint bit.
        ClogManager.Status status = clog.getStatus(xmin);
        if (status == ClogManager.Status.COMMITTED) {
            header.setInfomaskFlag(HeapTupleHeader.XMIN_COMMITTED);
            return true;
        }
        if (status == ClogManager.Status.ABORTED) {
            header.setInfomaskFlag(HeapTupleHeader.XMIN_INVALID);
        }
        return false;
    }

    private static boolean xmaxCommittedAndVisible(HeapTupleHeader header, Snapshot snapshot, ClogManager clog, int currentXid) {
        int xmax = header.xmax();

        // A transaction's own delete is immediately visible (row is deleted from its POV).
        if (currentXid != Constants.INVALID_XID && xmax == currentXid) {
            return true;
        }

        // Fast path: hint bits set by a previous visibility check skip the clog.
        if (header.hasInfomaskFlag(HeapTupleHeader.XMAX_INVALID)) {
            return false;
        }
        if (header.hasInfomaskFlag(HeapTupleHeader.XMAX_COMMITTED)) {
            // xmax is known committed; snapshot check still applies.
            if (xmax >= snapshot.xmax()) return false;
            if (snapshot.isActive(xmax)) return false;
            return true;
        }

        // Snapshot checks — no clog needed yet.
        if (xmax >= snapshot.xmax()) return false;
        if (snapshot.isActive(xmax)) return false;

        // Decisive clog lookup: cache the result as a hint bit.
        ClogManager.Status status = clog.getStatus(xmax);
        if (status == ClogManager.Status.COMMITTED) {
            header.setInfomaskFlag(HeapTupleHeader.XMAX_COMMITTED);
            return true;
        }
        if (status == ClogManager.Status.ABORTED) {
            header.setInfomaskFlag(HeapTupleHeader.XMAX_INVALID);
        }
        return false;
    }
}
