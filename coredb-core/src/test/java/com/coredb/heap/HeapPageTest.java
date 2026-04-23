package com.coredb.heap;

import com.coredb.page.Page;
import com.coredb.page.PageType;
import com.coredb.util.Constants;
import com.coredb.util.StorageException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HeapPageTest {

    private HeapPage heapPage;

    @BeforeEach
    void setUp() {
        heapPage = new HeapPage(new Page(1, PageType.HEAP));
    }

    @Test
    void freshPage_hasFullFreeSpace() {
        // 8192 - 16 (header) = 8176 bytes free
        assertThat(heapPage.freeBytes()).isEqualTo(Constants.PAGE_SIZE - 16);
    }

    @Test
    void freshPage_hasZeroSlots() {
        assertThat(heapPage.slotCount()).isZero();
    }

    @Test
    void insert_returnsCorrectRecordId() {
        byte[] data = new byte[]{1, 2, 3};
        RecordId rid = heapPage.insert(data, (short) 0);

        assertThat(rid.pageId()).isEqualTo(1);
        assertThat(rid.slotNo()).isEqualTo(0);
    }

    @Test
    void insert_secondTuple_incrementsSlotNo() {
        heapPage.insert(new byte[]{1}, (short) 0);
        RecordId rid = heapPage.insert(new byte[]{2}, (short) 0);

        assertThat(rid.slotNo()).isEqualTo(1);
    }

    @Test
    void insert_reducesFreeBytes() {
        int before = heapPage.freeBytes();
        int dataLen = 10;
        heapPage.insert(new byte[dataLen], (short) 0);

        int expected = before - HeapTupleHeader.HEADER_SIZE - dataLen - 4; // 4 = ItemId
        assertThat(heapPage.freeBytes()).isEqualTo(expected);
    }

    @Test
    void get_returnsStoredDataBytes() {
        byte[] data = new byte[]{10, 20, 30, 40};
        RecordId rid = heapPage.insert(data, (short) 0);

        byte[] raw = heapPage.get(rid.slotNo());

        // raw includes the 20-byte header + data
        assertThat(raw).hasSize(HeapTupleHeader.HEADER_SIZE + data.length);
        for (int i = 0; i < data.length; i++) {
            assertThat(raw[HeapTupleHeader.HEADER_SIZE + i]).isEqualTo(data[i]);
        }
    }

    @Test
    void get_tupleHeader_hasBootstrapXminAndZeroXmax() {
        byte[] data = new byte[]{99};
        RecordId rid = heapPage.insert(data, (short) 5);

        byte[] raw = heapPage.get(rid.slotNo());
        HeapTupleHeader h = HeapTupleHeader.readFrom(
                java.nio.ByteBuffer.wrap(raw).order(java.nio.ByteOrder.BIG_ENDIAN), 0);

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
        RecordId ridA = heapPage.insert(a, (short) 0);
        RecordId ridB = heapPage.insert(b, (short) 0);
        RecordId ridC = heapPage.insert(c, (short) 0);

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
            heapPage.insert(new byte[]{(byte) i}, (short) 0);
        }
        assertThat(heapPage.slotCount()).isEqualTo(50);
    }

    @Test
    void delete_marksSlotDead_getThrows() {
        RecordId rid = heapPage.insert(new byte[]{1, 2, 3}, (short) 0);
        heapPage.delete(rid.slotNo());

        assertThatThrownBy(() -> heapPage.get(rid.slotNo()))
                .isInstanceOf(StorageException.class);
    }

    @Test
    void delete_setsXmaxInTupleHeader() {
        RecordId rid = heapPage.insert(new byte[]{5}, (short) 0);
        heapPage.delete(rid.slotNo());

        // Read the raw bytes directly from the page buffer to verify t_xmax was written
        int raw = heapPage.page().readItemId(rid.slotNo());
        int offset = com.coredb.page.ItemId.offset(raw);
        HeapTupleHeader h = HeapTupleHeader.readFrom(heapPage.page().buffer(), offset);
        assertThat(h.xmax()).isNotEqualTo(Constants.INVALID_XID);
    }

    @Test
    void delete_alreadyDead_throwsStorageException() {
        RecordId rid = heapPage.insert(new byte[]{7}, (short) 0);
        heapPage.delete(rid.slotNo());

        assertThatThrownBy(() -> heapPage.delete(rid.slotNo()))
                .isInstanceOf(StorageException.class);
    }

    @Test
    void scan_emptyPage_returnsEmptyList() {
        assertThat(heapPage.scan()).isEmpty();
    }

    @Test
    void scan_afterInserts_returnsAllSlots() {
        heapPage.insert(new byte[]{1}, (short) 0);
        heapPage.insert(new byte[]{2}, (short) 0);
        heapPage.insert(new byte[]{3}, (short) 0);

        assertThat(heapPage.scan()).hasSize(3);
    }

    @Test
    void scan_afterDeleteSome_returnsOnlyLiveSlots() {
        RecordId r0 = heapPage.insert(new byte[]{1}, (short) 0);
        RecordId r1 = heapPage.insert(new byte[]{2}, (short) 0);
        RecordId r2 = heapPage.insert(new byte[]{3}, (short) 0);
        RecordId r3 = heapPage.insert(new byte[]{4}, (short) 0);
        RecordId r4 = heapPage.insert(new byte[]{5}, (short) 0);

        heapPage.delete(r1.slotNo());
        heapPage.delete(r3.slotNo());

        List<RecordId> live = heapPage.scan();
        assertThat(live).hasSize(3);
        assertThat(live).containsExactly(r0, r2, r4);
    }

    @Test
    void scan_deleteAll_returnsEmptyList() {
        RecordId r0 = heapPage.insert(new byte[]{1}, (short) 0);
        RecordId r1 = heapPage.insert(new byte[]{2}, (short) 0);
        heapPage.delete(r0.slotNo());
        heapPage.delete(r1.slotNo());

        assertThat(heapPage.scan()).isEmpty();
    }

    private static byte[] dataOf(byte[] raw) {
        byte[] data = new byte[raw.length - HeapTupleHeader.HEADER_SIZE];
        System.arraycopy(raw, HeapTupleHeader.HEADER_SIZE, data, 0, data.length);
        return data;
    }
}
