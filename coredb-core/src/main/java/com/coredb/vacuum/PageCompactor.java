package com.coredb.vacuum;

import com.coredb.heap.HeapTupleHeader;
import com.coredb.page.ItemId;
import com.coredb.page.PageHeader;
import com.coredb.txn.ClogManager;
import com.coredb.util.BinaryUtil;
import com.coredb.util.Constants;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Single-page compaction for VACUUM.
 *
 * <p>Determines which tuples are dead (invisible to every possible future snapshot)
 * and rewrites the page with those tuples removed. Dead slots are marked LP_UNUSED
 * and their positions in the ItemId array are preserved so that index entries can be
 * cleaned using the returned slot numbers before the new page is written back.</p>
 *
 * <p>This class produces a new buffer — it never mutates the input page. The caller
 * (VacuumExecutor) decides whether to write the result back, after coordinating with
 * the index.</p>
 *
 * <p>PostgreSQL equivalent: {@code lazy_vacuum_heap} / {@code heap_page_prune}</p>
 */
public final class PageCompactor {

    private PageCompactor() {}

    /**
     * Returns true if the tuple is dead — no longer visible to any possible future snapshot.
     *
     * <p>A tuple is dead when its deleting transaction committed and that commit
     * precedes the oldest snapshot any active transaction could take. Two cases:</p>
     * <ul>
     *   <li>Normal deletion: {@code xmax} is set, committed, and older than
     *       {@code oldestXmin}.</li>
     *   <li>Aborted insert: {@code xmin} is aborted — the tuple was never visible.</li>
     * </ul>
     *
     * @param header     the tuple header to test
     * @param oldestXmin lowest xmin of any active snapshot; tuples deleted before
     *                   this horizon are behind every reader
     * @param clog       commit log for transaction status lookups
     * @return true if the tuple can be safely removed
     */
    public static boolean isDead(HeapTupleHeader header, int oldestXmin, ClogManager clog) {
        if (isInsertAborted(header, clog)) {
            return true;
        }
        int xmax = header.xmax();
        if (xmax == Constants.INVALID_XID) {
            return false;
        }
        // Fast-path: hint bits cached from a prior visibility check skip the clog.
        if (header.hasInfomaskFlag(HeapTupleHeader.XMAX_INVALID)) {
            return false;
        }
        boolean xmaxCommitted;
        if (header.hasInfomaskFlag(HeapTupleHeader.XMAX_COMMITTED)) {
            xmaxCommitted = true;
        } else {
            xmaxCommitted = clog.getStatus(xmax) == ClogManager.Status.COMMITTED;
        }
        if (!xmaxCommitted) {
            return false;
        }
        return Integer.compareUnsigned(xmax, oldestXmin) < 0;
    }

    /**
     * Returns true if this tuple's inserting transaction aborted — the tuple was
     * never committed and will never be visible to any snapshot.
     */
    public static boolean isInsertAborted(HeapTupleHeader header, ClogManager clog) {
        int xmin = header.xmin();
        if (xmin == Constants.INVALID_XID
                || xmin == Constants.BOOTSTRAP_XID
                || xmin == Constants.FROZEN_XID) {
            return false;
        }
        if (header.hasInfomaskFlag(HeapTupleHeader.XMIN_COMMITTED)) {
            return false;
        }
        if (header.hasInfomaskFlag(HeapTupleHeader.XMIN_INVALID)) {
            return true;
        }
        return clog.getStatus(xmin) == ClogManager.Status.ABORTED;
    }

    /**
     * Rewrites a heap page by removing all dead tuples.
     *
     * <p>Dead slots are set to LP_UNUSED in the rebuilt ItemId array. Live tuple
     * bytes are packed contiguously toward {@code pd_special}, and {@code pd_upper}
     * is updated to reflect the reclaimed space. The returned buffer is always
     * the same size as the input ({@link Constants#PAGE_SIZE} bytes).</p>
     *
     * <p>Only NORMAL slots are evaluated for liveness. Slots that are already
     * UNUSED, REDIRECT, or DEAD are preserved as-is (carry forward as UNUSED).</p>
     *
     * @param pageBytes  raw bytes of the heap page (exactly PAGE_SIZE bytes)
     * @param oldestXmin horizon for dead-tuple detection (from SnapshotManager)
     * @param clog       commit log for transaction status lookups
     * @return compaction result containing the new page bytes and dead slot list
     */
    public static CompactionResult compact(byte[] pageBytes, int oldestXmin, ClogManager clog) {
        ByteBuffer src = ByteBuffer.wrap(pageBytes).order(ByteOrder.BIG_ENDIAN);

        int pdLower   = Short.toUnsignedInt(BinaryUtil.readU16(src, PageHeader.OFFSET_PD_LOWER));
        int pdSpecial = Short.toUnsignedInt(BinaryUtil.readU16(src, PageHeader.OFFSET_PD_SPECIAL));
        int slotCount = (pdLower - PageHeader.SIZE) / ItemId.SIZE;

        // First pass: classify each slot.
        boolean[] dead = new boolean[slotCount];
        int reclaimedBytes = 0;
        List<Integer> deadSlots = new ArrayList<>();

        for (int slot = 0; slot < slotCount; slot++) {
            int itemId = BinaryUtil.readU32(src, PageHeader.SIZE + slot * ItemId.SIZE);
            if (ItemId.flags(itemId) != ItemId.FLAGS_NORMAL) {
                continue;
            }
            int offset = ItemId.offset(itemId);
            HeapTupleHeader header = HeapTupleHeader.readFrom(src, offset);
            if (isDead(header, oldestXmin, clog)) {
                dead[slot] = true;
                deadSlots.add(slot);
                reclaimedBytes += ItemId.length(itemId);
            }
        }

        if (deadSlots.isEmpty()) {
            return new CompactionResult(pageBytes.clone(), List.of(), 0);
        }

        // Second pass: build the new page.
        ByteBuffer dst = ByteBuffer.allocate(Constants.PAGE_SIZE).order(ByteOrder.BIG_ENDIAN);

        // Copy the full page header as-is; we will overwrite pd_upper at the end.
        for (int i = 0; i < PageHeader.SIZE; i++) {
            dst.put(i, src.get(i));
        }

        // Pack live tuples from pdSpecial downward.
        int newUpper = pdSpecial;
        for (int slot = 0; slot < slotCount; slot++) {
            int itemId = BinaryUtil.readU32(src, PageHeader.SIZE + slot * ItemId.SIZE);

            if (dead[slot]) {
                // Mark LP_UNUSED — tuple bytes are not copied.
                BinaryUtil.writeU32(dst, PageHeader.SIZE + slot * ItemId.SIZE, 0);
                continue;
            }

            if (ItemId.flags(itemId) != ItemId.FLAGS_NORMAL) {
                // Non-normal slots that aren't dead: preserve as UNUSED.
                BinaryUtil.writeU32(dst, PageHeader.SIZE + slot * ItemId.SIZE, 0);
                continue;
            }

            int oldOffset = ItemId.offset(itemId);
            int length    = ItemId.length(itemId);
            newUpper -= length;

            // Copy tuple bytes to new location.
            for (int b = 0; b < length; b++) {
                dst.put(newUpper + b, src.get(oldOffset + b));
            }

            // Write updated ItemId with new offset.
            BinaryUtil.writeU32(dst, PageHeader.SIZE + slot * ItemId.SIZE,
                    ItemId.pack(newUpper, ItemId.FLAGS_NORMAL, length));
        }

        // Update pd_upper.
        BinaryUtil.writeU16(dst, PageHeader.OFFSET_PD_UPPER, (short) newUpper);

        return new CompactionResult(dst.array(), List.copyOf(deadSlots), reclaimedBytes);
    }
}
