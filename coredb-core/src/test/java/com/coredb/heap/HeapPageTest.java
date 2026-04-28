package com.coredb.heap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.coredb.mvcc.Snapshot;
import com.coredb.page.Page;
import com.coredb.txn.ClogManager;
import com.coredb.util.Constants;
import com.coredb.util.StorageException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HeapPageTest {

    @TempDir
    Path tempDir;

    private HeapPage heapPage;
    private ClogManager clog;

    @BeforeEach
    void setUp() throws Exception {
        heapPage = new HeapPage(Page.Factory.allocateHeapPage(1));
        clog = ClogManager.create(tempDir);
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() throws Exception {
        if (clog != null) {
            clog.close();
        }
    }

    @Test
    void freshPage_hasFullFreeSpace() {
        // 8192 - 16 (page header) = 8176 bytes free
        assertThat(heapPage.freeBytes()).isEqualTo(Constants.PAGE_SIZE - 16);
    }

    @Test
    void freshPage_hasZeroSlots() {
        assertThat(heapPage.slotCount()).isZero();
    }

    @Test
    void insert_returnsCorrectRecordId() {
        RecordId rid = heapPage.insert(new byte[]{1, 2, 3}, (short) 0, Constants.BOOTSTRAP_XID);

        assertThat(rid.pageId()).isEqualTo(1);
        assertThat(rid.slotNo()).isEqualTo(0);
    }

    @Test
    void insert_secondTuple_incrementsSlotNo() {
        heapPage.insert(new byte[]{1}, (short) 0, Constants.BOOTSTRAP_XID);
        RecordId rid = heapPage.insert(new byte[]{2}, (short) 0, Constants.BOOTSTRAP_XID);

        assertThat(rid.slotNo()).isEqualTo(1);
    }

    @Test
    void insert_reducesFreeBytes() {
        int before = heapPage.freeBytes();
        int dataLen = 10;
        heapPage.insert(new byte[dataLen], (short) 0, Constants.BOOTSTRAP_XID);

        // natts=0 → headerSize=23; ItemId=4 bytes
        int expected = before - HeapTupleHeader.computeHeaderSize(0) - dataLen - 4;
        assertThat(heapPage.freeBytes()).isEqualTo(expected);
    }

    @Test
    void insert_differentNatts_correctHeaderSize() {
        int before = heapPage.freeBytes();
        int dataLen = 5;
        heapPage.insert(new byte[dataLen], (short) 9, Constants.BOOTSTRAP_XID); // 9 cols → headerSize=25

        int expected = before - HeapTupleHeader.computeHeaderSize(9) - dataLen - 4;
        assertThat(heapPage.freeBytes()).isEqualTo(expected);
    }

    @Test
    void get_returnsStoredDataBytes() {
        byte[] data = new byte[]{10, 20, 30, 40};
        RecordId rid = heapPage.insert(data, (short) 0, Constants.BOOTSTRAP_XID);

        byte[] raw = heapPage.get(rid.slotNo());

        // natts=0 → hsize=23
        int hsize = HeapTupleHeader.computeHeaderSize(0);
        assertThat(raw).hasSize(hsize + data.length);
        for (int i = 0; i < data.length; i++) {
            assertThat(raw[hsize + i]).isEqualTo(data[i]);
        }
    }

    @Test
    void get_dataOffset_usesHoffFromHeader() {
        byte[] data = new byte[]{10, 20, 30};
        // 4 columns → hsize=24 (1 bitmap byte)
        RecordId rid = heapPage.insert(data, (short) 4, Constants.BOOTSTRAP_XID);

        byte[] raw = heapPage.get(rid.slotNo());
        ByteBuffer buf = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN);
        HeapTupleHeader h = HeapTupleHeader.readFrom(buf, 0);

        assertThat(h.hoff()).isEqualTo(HeapTupleHeader.computeHeaderSize(4));
        for (int i = 0; i < data.length; i++) {
            assertThat(raw[h.hoff() + i]).isEqualTo(data[i]);
        }
    }

    @Test
    void get_tupleHeader_hasBootstrapXminAndZeroXmax() {
        byte[] data = new byte[]{99};
        RecordId rid = heapPage.insert(data, (short) 5, Constants.BOOTSTRAP_XID);

        byte[] raw = heapPage.get(rid.slotNo());
        HeapTupleHeader h = HeapTupleHeader.readFrom(
                ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN), 0);

        assertThat(h.xmin()).isEqualTo(Constants.BOOTSTRAP_XID);
        assertThat(h.xmax()).isEqualTo(Constants.INVALID_XID);
        assertThat(h.ctid()).isEqualTo(rid);
        assertThat(h.natts()).isEqualTo((short) 5);
    }

    @Test
    void get_multipleTuples_returnsCorrectData() {
        byte[] a = {1, 2};
        byte[] b = {3, 4, 5};
        byte[] c = {6};
        RecordId ridA = heapPage.insert(a, (short) 0, Constants.BOOTSTRAP_XID);
        RecordId ridB = heapPage.insert(b, (short) 0, Constants.BOOTSTRAP_XID);
        RecordId ridC = heapPage.insert(c, (short) 0, Constants.BOOTSTRAP_XID);

        assertThat(dataOf(heapPage.get(ridA.slotNo()))).isEqualTo(a);
        assertThat(dataOf(heapPage.get(ridB.slotNo()))).isEqualTo(b);
        assertThat(dataOf(heapPage.get(ridC.slotNo()))).isEqualTo(c);
    }

    @Test
    void get_outOfRange_throwsStorageException() {
        assertThatThrownBy(() -> heapPage.get(0))
                .isInstanceOf(StorageException.class);
    }

    @Test
    void insert_manyTuples_incrementsSlotCount() {
        for (int i = 0; i < 50; i++) {
            heapPage.insert(new byte[]{(byte) i}, (short) 0, Constants.BOOTSTRAP_XID);
        }
        assertThat(heapPage.slotCount()).isEqualTo(50);
    }

    @Test
    void delete_slotRemainsReadable_xmaxIsSet() {
        RecordId rid = heapPage.insert(new byte[]{1, 2, 3}, (short) 0, Constants.BOOTSTRAP_XID);
        heapPage.delete(rid.slotNo(), Constants.BOOTSTRAP_XID);

        // Raw get still works — slot is not marked dead, MVCC visibility filters it
        assertThat(heapPage.get(rid.slotNo())).isNotNull();
    }

    @Test
    void delete_setsXmaxInTupleHeader() {
        RecordId rid = heapPage.insert(new byte[]{5}, (short) 0, Constants.BOOTSTRAP_XID);
        heapPage.delete(rid.slotNo(), Constants.BOOTSTRAP_XID);

        int raw = heapPage.page().readItemId(rid.slotNo());
        int offset = com.coredb.page.ItemId.offset(raw);
        HeapTupleHeader h = HeapTupleHeader.readFrom(heapPage.page().buffer(), offset);
        assertThat(h.xmax()).isNotEqualTo(Constants.INVALID_XID);
    }

    @Test
    void delete_alreadyDead_throwsStorageException() {
        RecordId rid = heapPage.insert(new byte[]{7}, (short) 0, Constants.BOOTSTRAP_XID);
        heapPage.delete(rid.slotNo(), Constants.BOOTSTRAP_XID);

        assertThatThrownBy(() -> heapPage.delete(rid.slotNo(), Constants.BOOTSTRAP_XID))
                .isInstanceOf(StorageException.class);
    }

    @Test
    void scan_emptyPage_returnsEmptyList() {
        assertThat(heapPage.scan()).isEmpty();
    }

    @Test
    void scan_afterInserts_returnsAllSlots() {
        heapPage.insert(new byte[]{1}, (short) 0, Constants.BOOTSTRAP_XID);
        heapPage.insert(new byte[]{2}, (short) 0, Constants.BOOTSTRAP_XID);
        heapPage.insert(new byte[]{3}, (short) 0, Constants.BOOTSTRAP_XID);

        assertThat(heapPage.scan()).hasSize(3);
    }

    @Test
    void scan_afterDeleteSome_returnsOnlyLiveSlots() {
        RecordId r0 = heapPage.insert(new byte[]{1}, (short) 0, Constants.BOOTSTRAP_XID);
        RecordId r1 = heapPage.insert(new byte[]{2}, (short) 0, Constants.BOOTSTRAP_XID);
        RecordId r2 = heapPage.insert(new byte[]{3}, (short) 0, Constants.BOOTSTRAP_XID);
        RecordId r3 = heapPage.insert(new byte[]{4}, (short) 0, Constants.BOOTSTRAP_XID);
        RecordId r4 = heapPage.insert(new byte[]{5}, (short) 0, Constants.BOOTSTRAP_XID);

        heapPage.delete(r1.slotNo(), Constants.BOOTSTRAP_XID);
        heapPage.delete(r3.slotNo(), Constants.BOOTSTRAP_XID);

        List<RecordId> live = heapPage.scan(Snapshot.BOOTSTRAP, clog);
        assertThat(live).hasSize(3);
        assertThat(live).containsExactly(r0, r2, r4);
    }

    @Test
    void scan_deleteAll_returnsEmptyList() {
        RecordId r0 = heapPage.insert(new byte[]{1}, (short) 0, Constants.BOOTSTRAP_XID);
        RecordId r1 = heapPage.insert(new byte[]{2}, (short) 0, Constants.BOOTSTRAP_XID);
        heapPage.delete(r0.slotNo(), Constants.BOOTSTRAP_XID);
        heapPage.delete(r1.slotNo(), Constants.BOOTSTRAP_XID);

        assertThat(heapPage.scan(Snapshot.BOOTSTRAP, clog)).isEmpty();
    }

    @Test
    void insert_withExplicitBitmap_nullBitsPreservedInHeader() {
        // column 1 is null → bit 1 set → 0b00000010
        byte[] bitmap = {(byte) 0b00000010};
        RecordId rid = heapPage.insert(new byte[]{42}, (short) 4, bitmap, Constants.BOOTSTRAP_XID);

        byte[] raw = heapPage.get(rid.slotNo());
        HeapTupleHeader h = HeapTupleHeader.readFrom(
                ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN), 0);

        assertThat(h.isNull(0)).isFalse();
        assertThat(h.isNull(1)).isTrue();
        assertThat(h.isNull(2)).isFalse();
        assertThat(h.isNull(3)).isFalse();
    }

    // ─── update() ──────────────────────────────────────────────────────────────

    @Test
    void update_returnsNewRecordId() {
        RecordId oldRid = heapPage.insert(new byte[]{1, 2, 3}, (short) 0, Constants.BOOTSTRAP_XID);
        RecordId newRid = heapPage.update(oldRid.slotNo(), new byte[]{4, 5, 6}, (short) 0, new byte[0], 5);

        assertThat(newRid.pageId()).isEqualTo(1);
        assertThat(newRid.slotNo()).isEqualTo(1);
    }

    @Test
    void update_oldTuple_hasXmaxAndCtidPointingToNewVersion() {
        RecordId oldRid = heapPage.insert(new byte[]{1}, (short) 0, Constants.BOOTSTRAP_XID);
        RecordId newRid = heapPage.update(oldRid.slotNo(), new byte[]{2}, (short) 0, new byte[0], 7);

        HeapTupleHeader oldHeader = headerAt(heapPage, oldRid.slotNo());
        assertThat(oldHeader.xmax()).isEqualTo(7);
        assertThat(oldHeader.ctid()).isEqualTo(newRid);
    }

    @Test
    void update_newTuple_hasXminAndCtidPointingToSelf() {
        heapPage.insert(new byte[]{1}, (short) 0, Constants.BOOTSTRAP_XID);
        RecordId newRid = heapPage.update(0, new byte[]{2}, (short) 0, new byte[0], 8);

        HeapTupleHeader newHeader = headerAt(heapPage, newRid.slotNo());
        assertThat(newHeader.xmin()).isEqualTo(8);
        assertThat(newHeader.xmax()).isEqualTo(Constants.INVALID_XID);
        assertThat(newHeader.ctid()).isEqualTo(newRid);
    }

    @Test
    void update_bothVersionsOnSamePage_bothNormalFlags() {
        RecordId oldRid = heapPage.insert(new byte[]{1}, (short) 0, Constants.BOOTSTRAP_XID);
        RecordId newRid = heapPage.update(oldRid.slotNo(), new byte[]{2}, (short) 0, new byte[0], 9);

        // Both slots must have FLAGS_NORMAL so MVCC visibility can filter them.
        int oldItemId = heapPage.page().readItemId(oldRid.slotNo());
        int newItemId = heapPage.page().readItemId(newRid.slotNo());
        assertThat(com.coredb.page.ItemId.flags(oldItemId)).isEqualTo(com.coredb.page.ItemId.FLAGS_NORMAL);
        assertThat(com.coredb.page.ItemId.flags(newItemId)).isEqualTo(com.coredb.page.ItemId.FLAGS_NORMAL);
    }

    @Test
    void update_oldVersionInvisible_newVersionVisible_toSnapshotAfterCommit() throws Exception {
        // Insert by xid=5
        RecordId oldRid = heapPage.insert(new byte[]{1}, (short) 0, 5);
        clog.setCommitted(5);

        // Update by xid=6
        heapPage.update(oldRid.slotNo(), new byte[]{2}, (short) 0, new byte[0], 6);
        clog.setCommitted(6);

        // Snapshot taken after both committed: only new version is visible.
        Snapshot snap = new Snapshot(10, 20, java.util.Collections.emptySet());
        List<RecordId> visible = heapPage.scan(snap, clog);

        assertThat(visible).hasSize(1);
        assertThat(visible.get(0).slotNo()).isEqualTo(1); // slot 1 is the new version
    }

    @Test
    void update_oldVersionVisible_toSnapshotBeforeUpdateCommitted() throws Exception {
        // Insert by xid=5
        RecordId oldRid = heapPage.insert(new byte[]{1}, (short) 0, 5);
        clog.setCommitted(5);

        // Update by xid=6, but xid=6 is in-progress from the snapshot's POV
        heapPage.update(oldRid.slotNo(), new byte[]{2}, (short) 0, new byte[0], 6);
        // xid=6 is NOT committed yet

        // Snapshot where xid=6 is active
        Snapshot snap = new Snapshot(5, 7, java.util.Set.of(6));
        List<RecordId> visible = heapPage.scan(snap, clog);

        // Only old version visible (xmax=6 is in-progress, so delete is not visible)
        assertThat(visible).hasSize(1);
        assertThat(visible.get(0)).isEqualTo(oldRid);
    }

    // ─── hint bits ─────────────────────────────────────────────────────────────

    @Test
    void scan_setsXminCommittedHintBit_afterClogLookup() throws Exception {
        RecordId rid = heapPage.insert(new byte[]{1}, (short) 0, 5);
        clog.setCommitted(5);

        Snapshot snap = new Snapshot(7, 10, java.util.Collections.emptySet());
        heapPage.scan(snap, clog);

        // Hint bit must be written back to the page buffer.
        HeapTupleHeader header = headerAt(heapPage, rid.slotNo());
        assertThat(header.hasInfomaskFlag(HeapTupleHeader.XMIN_COMMITTED)).isTrue();
    }

    @Test
    void scan_setsXminInvalidHintBit_forAbortedInsert() throws Exception {
        heapPage.insert(new byte[]{1}, (short) 0, 5);
        clog.setAborted(5);

        Snapshot snap = new Snapshot(7, 10, java.util.Collections.emptySet());
        heapPage.scan(snap, clog);

        HeapTupleHeader header = headerAt(heapPage, 0);
        assertThat(header.hasInfomaskFlag(HeapTupleHeader.XMIN_INVALID)).isTrue();
    }

    @Test
    void scan_hintBit_flagsPageAsDirty() throws Exception {
        heapPage.insert(new byte[]{1}, (short) 0, 5);
        clog.setCommitted(5);

        Snapshot snap = new Snapshot(7, 10, java.util.Collections.emptySet());
        heapPage.scan(snap, clog);

        assertThat(heapPage.wasHintBitsModified()).isTrue();
    }

    @Test
    void scan_noHintBitSet_whenXidIsInFuture() throws Exception {
        heapPage.insert(new byte[]{1}, (short) 0, 25);
        clog.setCommitted(25);

        // xmin=25 >= snap.xmax=20 → filtered before clog — no hint bit
        Snapshot snap = new Snapshot(10, 20, java.util.Collections.emptySet());
        heapPage.scan(snap, clog);

        HeapTupleHeader header = headerAt(heapPage, 0);
        assertThat(header.hasInfomaskFlag(HeapTupleHeader.XMIN_COMMITTED)).isFalse();
        assertThat(header.hasInfomaskFlag(HeapTupleHeader.XMIN_INVALID)).isFalse();
        assertThat(heapPage.wasHintBitsModified()).isFalse();
    }

    // ─── helpers ───────────────────────────────────────────────────────────────

    private static HeapTupleHeader headerAt(HeapPage hp, int slotNo) {
        int raw = hp.page().readItemId(slotNo);
        int offset = com.coredb.page.ItemId.offset(raw);
        return HeapTupleHeader.readFrom(hp.page().buffer(), offset);
    }

    private static byte[] dataOf(byte[] raw) {
        ByteBuffer buf = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN);
        HeapTupleHeader h = HeapTupleHeader.readFrom(buf, 0);
        int hoff = h.hoff();
        byte[] data = new byte[raw.length - hoff];
        System.arraycopy(raw, hoff, data, 0, data.length);
        return data;
    }
}
