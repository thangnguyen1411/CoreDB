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

    private static byte[] dataOf(byte[] raw) {
        ByteBuffer buf = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN);
        HeapTupleHeader h = HeapTupleHeader.readFrom(buf, 0);
        int hoff = h.hoff();
        byte[] data = new byte[raw.length - hoff];
        System.arraycopy(raw, hoff, data, 0, data.length);
        return data;
    }
}
