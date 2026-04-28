package com.coredb.vacuum;

import static org.assertj.core.api.Assertions.assertThat;

import com.coredb.heap.HeapPage;
import com.coredb.heap.HeapTupleHeader;
import com.coredb.heap.RecordId;
import com.coredb.page.ItemId;
import com.coredb.page.Page;
import com.coredb.page.PageHeader;
import com.coredb.txn.ClogManager;
import com.coredb.util.BinaryUtil;
import com.coredb.util.Constants;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PageCompactorTest {

    @TempDir
    Path tempDir;

    private ClogManager clog;

    @BeforeEach
    void setUp() throws Exception {
        clog = ClogManager.create(tempDir);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (clog != null) clog.close();
    }

    // ---- isDead predicate tests ----

    @Test
    void isDead_returnsFalse_whenXmaxIsInvalid() {
        HeapTupleHeader h = headerWithXminXmax(Constants.BOOTSTRAP_XID, Constants.INVALID_XID);
        assertThat(PageCompactor.isDead(h, 10, clog)).isFalse();
    }

    @Test
    void isDead_returnsFalse_whenXmaxIsInProgress() {
        // xmax=5 is in-progress (not committed, not aborted)
        HeapTupleHeader h = headerWithXminXmax(Constants.BOOTSTRAP_XID, 5);
        assertThat(PageCompactor.isDead(h, 10, clog)).isFalse();
    }

    @Test
    void isDead_returnsFalse_whenXmaxIsAborted() {
        clog.setAborted(5);
        HeapTupleHeader h = headerWithXminXmax(Constants.BOOTSTRAP_XID, 5);
        assertThat(PageCompactor.isDead(h, 10, clog)).isFalse();
    }

    @Test
    void isDead_returnsFalse_whenXmaxCommittedButGteOldestXmin() {
        // xmax=10, oldestXmin=10 — the delete xid equals the horizon, not behind it
        clog.setCommitted(10);
        HeapTupleHeader h = headerWithXminXmax(Constants.BOOTSTRAP_XID, 10);
        assertThat(PageCompactor.isDead(h, 10, clog)).isFalse();
    }

    @Test
    void isDead_returnsTrue_whenXmaxCommittedAndLtOldestXmin() {
        clog.setCommitted(5);
        HeapTupleHeader h = headerWithXminXmax(Constants.BOOTSTRAP_XID, 5);
        // oldestXmin=10 > 5 — the deletion is behind every reader
        assertThat(PageCompactor.isDead(h, 10, clog)).isTrue();
    }

    @Test
    void isDead_returnsTrue_whenXminAborted() {
        // Aborted insert: tuple was never visible, even without an xmax
        clog.setAborted(5);
        HeapTupleHeader h = headerWithXminXmax(5, Constants.INVALID_XID);
        assertThat(PageCompactor.isDead(h, 10, clog)).isTrue();
    }

    @Test
    void isDead_returnsFalse_whenXminIsInvalidXid() {
        // A malformed tuple with xmin=0 must not cause a clog lookup (which would throw).
        HeapTupleHeader h = headerWithXminXmax(Constants.INVALID_XID, Constants.INVALID_XID);
        assertThat(PageCompactor.isDead(h, 10, clog)).isFalse();
    }

    @Test
    void isDead_returnsTrue_whenXmaxCommittedHintBitSetAndLtOldestXmin() {
        // XMAX_COMMITTED hint bit should skip clog lookup and still detect dead tuple.
        HeapTupleHeader h = headerWithXminXmax(Constants.BOOTSTRAP_XID, 5);
        h.setInfomaskFlag(HeapTupleHeader.XMAX_COMMITTED);
        // xmax=5 is flagged committed via hint bit, no clog entry required
        assertThat(PageCompactor.isDead(h, 10, clog)).isTrue();
    }

    @Test
    void isDead_returnsFalse_whenXmaxInvalidHintBitSet() {
        // XMAX_INVALID hint bit means the delete was rolled back — tuple is live.
        HeapTupleHeader h = headerWithXminXmax(Constants.BOOTSTRAP_XID, 5);
        h.setInfomaskFlag(HeapTupleHeader.XMAX_INVALID);
        assertThat(PageCompactor.isDead(h, 10, clog)).isFalse();
    }

    @Test
    void isDead_returnsFalse_forBootstrapXmin() {
        // Bootstrap tuples (catalog rows) are always visible — never dead
        HeapTupleHeader h = headerWithXminXmax(Constants.BOOTSTRAP_XID, Constants.INVALID_XID);
        assertThat(PageCompactor.isDead(h, Integer.MAX_VALUE, clog)).isFalse();
    }

    @Test
    void isDead_returnsFalse_forFrozenXmin() {
        HeapTupleHeader h = headerWithXminXmax(Constants.FROZEN_XID, Constants.INVALID_XID);
        assertThat(PageCompactor.isDead(h, Integer.MAX_VALUE, clog)).isFalse();
    }

    // ---- compact algorithm tests ----

    @Test
    void compact_pageWithNoDeadTuples_returnsPageUnchangedAndEmptyDeadList() throws Exception {
        HeapPage hp = freshHeapPage(1);
        hp.insert(new byte[]{10, 20, 30}, (short) 1, Constants.BOOTSTRAP_XID);
        hp.insert(new byte[]{40, 50, 60}, (short) 1, Constants.BOOTSTRAP_XID);

        int freeBefore = hp.freeBytes();
        byte[] pageBytes = pageBytes(hp);

        CompactionResult result = PageCompactor.compact(pageBytes, 100, clog);

        assertThat(result.deadSlots()).isEmpty();
        assertThat(result.reclaimedBytes()).isZero();
        assertThat(result.hasDeadTuples()).isFalse();
        // Page content is identical
        assertThat(freeSpace(result.newPageBytes())).isEqualTo(freeBefore);
    }

    @Test
    void compact_removesSingleDeletedTuple_setsItsSlotUnused() throws Exception {
        HeapPage hp = freshHeapPage(1);
        hp.insert(new byte[]{1, 2, 3}, (short) 1, Constants.BOOTSTRAP_XID); // slot 0 — live
        hp.insert(new byte[]{4, 5, 6}, (short) 1, Constants.BOOTSTRAP_XID); // slot 1 — will be deleted

        int deleteXid = 7;
        hp.delete(1, deleteXid);
        clog.setCommitted(deleteXid);

        int freeBefore = hp.freeBytes();
        byte[] pageBytes = pageBytes(hp);

        // oldestXmin=100 > deleteXid=7, so the deletion is behind every reader
        CompactionResult result = PageCompactor.compact(pageBytes, 100, clog);

        assertThat(result.deadSlots()).containsExactly(1);
        assertThat(result.reclaimedBytes()).isPositive();
        assertThat(result.hasDeadTuples()).isTrue();

        // Slot 1 must be UNUSED in the new page
        assertThat(slotFlags(result.newPageBytes(), 1)).isEqualTo(ItemId.FLAGS_UNUSED);
        // Slot 0 must still be NORMAL
        assertThat(slotFlags(result.newPageBytes(), 0)).isEqualTo(ItemId.FLAGS_NORMAL);
        // Free space increased by the reclaimed bytes
        assertThat(freeSpace(result.newPageBytes())).isEqualTo(freeBefore + result.reclaimedBytes());
    }

    @Test
    void compact_removesAbortedInsert_setsItsSlotUnused() throws Exception {
        HeapPage hp = freshHeapPage(1);
        int abortedXid = 9;
        hp.insert(new byte[]{1, 2, 3}, (short) 1, Constants.BOOTSTRAP_XID); // slot 0 — live
        hp.insert(new byte[]{4, 5, 6}, (short) 1, abortedXid);               // slot 1 — aborted insert

        clog.setAborted(abortedXid);
        byte[] pageBytes = pageBytes(hp);

        CompactionResult result = PageCompactor.compact(pageBytes, 100, clog);

        assertThat(result.deadSlots()).containsExactly(1);
        assertThat(slotFlags(result.newPageBytes(), 1)).isEqualTo(ItemId.FLAGS_UNUSED);
        assertThat(slotFlags(result.newPageBytes(), 0)).isEqualTo(ItemId.FLAGS_NORMAL);
    }

    @Test
    void compact_preservesLiveTupleSlotNumbers() throws Exception {
        // Live tuples must keep their slot numbers after compaction so existing
        // RecordIds (pageId, slotNo) remain valid.
        HeapPage hp = freshHeapPage(2);
        hp.insert(new byte[]{0xAA}, (short) 1, Constants.BOOTSTRAP_XID); // slot 0
        hp.insert(new byte[]{0xBB}, (short) 1, Constants.BOOTSTRAP_XID); // slot 1 — dead
        hp.insert(new byte[]{0xCC}, (short) 1, Constants.BOOTSTRAP_XID); // slot 2

        int deleteXid = 3;
        hp.delete(1, deleteXid);
        clog.setCommitted(deleteXid);

        byte[] pageBytes = pageBytes(hp);
        CompactionResult result = PageCompactor.compact(pageBytes, 100, clog);

        assertThat(result.deadSlots()).containsExactly(1);
        // Slot 0 and slot 2 still NORMAL
        assertThat(slotFlags(result.newPageBytes(), 0)).isEqualTo(ItemId.FLAGS_NORMAL);
        assertThat(slotFlags(result.newPageBytes(), 2)).isEqualTo(ItemId.FLAGS_NORMAL);

        // Verify slot 0 points to the original data 0xAA
        assertThat(firstDataByte(result.newPageBytes(), 0)).isEqualTo((byte) 0xAA);
        // Verify slot 2 points to the original data 0xCC
        assertThat(firstDataByte(result.newPageBytes(), 2)).isEqualTo((byte) 0xCC);
    }

    @Test
    void compact_doesNotReclaimDeleteWhenXmaxGteOldestXmin() throws Exception {
        // xmax is committed but >= oldestXmin — some reader may still see the old version
        HeapPage hp = freshHeapPage(1);
        hp.insert(new byte[]{1, 2, 3}, (short) 1, Constants.BOOTSTRAP_XID);
        int deleteXid = 50;
        hp.delete(0, deleteXid);
        clog.setCommitted(deleteXid);

        byte[] pageBytes = pageBytes(hp);

        // oldestXmin=50 — delete xid equals the horizon, not strictly behind it
        CompactionResult result = PageCompactor.compact(pageBytes, 50, clog);

        assertThat(result.deadSlots()).isEmpty();
        assertThat(slotFlags(result.newPageBytes(), 0)).isEqualTo(ItemId.FLAGS_NORMAL);
    }

    @Test
    void compact_multipleDeadTuples_reclaimsAllOfThem() throws Exception {
        HeapPage hp = freshHeapPage(1);
        for (int i = 0; i < 5; i++) {
            hp.insert(new byte[]{(byte) i}, (short) 1, Constants.BOOTSTRAP_XID);
        }
        // Delete slots 1, 3 with the same committed XID
        int deleteXid = 4;
        hp.delete(1, deleteXid);
        hp.delete(3, deleteXid);
        clog.setCommitted(deleteXid);

        byte[] pageBytes = pageBytes(hp);
        CompactionResult result = PageCompactor.compact(pageBytes, 100, clog);

        assertThat(result.deadSlots()).containsExactlyInAnyOrder(1, 3);
        assertThat(slotFlags(result.newPageBytes(), 0)).isEqualTo(ItemId.FLAGS_NORMAL);
        assertThat(slotFlags(result.newPageBytes(), 1)).isEqualTo(ItemId.FLAGS_UNUSED);
        assertThat(slotFlags(result.newPageBytes(), 2)).isEqualTo(ItemId.FLAGS_NORMAL);
        assertThat(slotFlags(result.newPageBytes(), 3)).isEqualTo(ItemId.FLAGS_UNUSED);
        assertThat(slotFlags(result.newPageBytes(), 4)).isEqualTo(ItemId.FLAGS_NORMAL);
        assertThat(result.reclaimedBytes()).isPositive();
    }

    // ---- helpers ----

    private static HeapTupleHeader headerWithXminXmax(int xmin, int xmax) {
        HeapTupleHeader h = new HeapTupleHeader(new RecordId(1, 0), (short) 1);
        h.setXmin(xmin);
        h.setXmax(xmax);
        return h;
    }

    private static HeapPage freshHeapPage(int pageId) {
        return new HeapPage(Page.Factory.allocateHeapPage(pageId));
    }

    private static byte[] pageBytes(HeapPage hp) {
        return hp.page().buffer().array().clone();
    }

    private static int freeSpace(byte[] pageBytes) {
        ByteBuffer buf = ByteBuffer.wrap(pageBytes).order(ByteOrder.BIG_ENDIAN);
        int pdLower  = Short.toUnsignedInt(BinaryUtil.readU16(buf, PageHeader.OFFSET_PD_LOWER));
        int pdUpper  = Short.toUnsignedInt(BinaryUtil.readU16(buf, PageHeader.OFFSET_PD_UPPER));
        return pdUpper - pdLower;
    }

    private static int slotFlags(byte[] pageBytes, int slot) {
        ByteBuffer buf = ByteBuffer.wrap(pageBytes).order(ByteOrder.BIG_ENDIAN);
        int itemId = BinaryUtil.readU32(buf, PageHeader.SIZE + slot * ItemId.SIZE);
        return ItemId.flags(itemId);
    }

    private static byte firstDataByte(byte[] pageBytes, int slot) {
        ByteBuffer buf = ByteBuffer.wrap(pageBytes).order(ByteOrder.BIG_ENDIAN);
        int itemId = BinaryUtil.readU32(buf, PageHeader.SIZE + slot * ItemId.SIZE);
        int tupleOffset = ItemId.offset(itemId);
        // The first data byte is after the HeapTupleHeader — use hoff to find it.
        HeapTupleHeader header = HeapTupleHeader.readFrom(buf, tupleOffset);
        return buf.get(tupleOffset + header.hoff());
    }
}
