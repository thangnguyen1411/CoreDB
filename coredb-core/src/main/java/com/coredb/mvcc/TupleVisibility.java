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
     * <p>May set {@code XMIN_COMMITTED}, {@code XMIN_INVALID}, {@code XMAX_COMMITTED},
     * or {@code XMAX_INVALID} hint bits on {@code header} as a side-effect when a
     * clog lookup returns a decisive result. The caller must write the header back to
     * the page buffer if {@code header.infomask()} changed. No WAL record is emitted
     * for hint-bit mutations — they are a recomputable clog cache.</p>
     *
     * @param header the tuple header containing xmin/xmax; may be mutated with hint bits
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

    private static boolean xminVisible(HeapTupleHeader header, Snapshot snapshot, ClogManager clog) {
        int xmin = header.xmin();

        // Special XIDs are unconditionally visible without clog lookup.
        if (xmin == Constants.BOOTSTRAP_XID || xmin == Constants.FROZEN_XID) {
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

    private static boolean xmaxCommittedAndVisible(HeapTupleHeader header, Snapshot snapshot, ClogManager clog) {
        int xmax = header.xmax();

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
