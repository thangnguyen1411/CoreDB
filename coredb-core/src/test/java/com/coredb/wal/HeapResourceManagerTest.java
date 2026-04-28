package com.coredb.wal;

import com.coredb.heap.HeapTupleHeader;
import com.coredb.page.ItemId;
import com.coredb.page.Page;
import com.coredb.page.PageHeader;
import com.coredb.util.BinaryUtil;
import com.coredb.util.Constants;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for HeapResourceManager WAL redo operations.
 */
class HeapResourceManagerTest {

    private static final int TABLE_OID = 1002;
    private static final int PAGE_ID = 1;

    @Test
    void redoInsert_shouldAddTupleToPage() {
        // Create an empty heap page
        Page page = Page.Factory.allocateHeapPage(PAGE_ID);
        ByteBuffer pageBuf = page.buffer();

        // Prepare the insert payload
        short natts = 3;
        byte[] bitmap = new byte[(natts + 7) / 8]; // All non-null
        byte[] tupleData = new byte[]{0x01, 0x02, 0x03, 0x04, 0x05};

        ByteBuffer payload = ByteBuffer.allocate(6 + bitmap.length + tupleData.length)
            .order(ByteOrder.BIG_ENDIAN);
        payload.putInt(0); // slotNo = 0
        payload.putShort(natts);
        payload.put(bitmap);
        payload.put(tupleData);

        // Create the WAL record
        XLogRecord record = XLogRecord.create(
            16, // lsn
            Constants.BOOTSTRAP_XID,
            0, // prevLsn
            XLogRecord.RMGR_HEAP,
            HeapResourceManager.HEAP_INSERT,
            TABLE_OID,
            PAGE_ID,
            payload.array()
        );

        // Apply the redo
        HeapResourceManager rmgr = new HeapResourceManager();
        try {
            rmgr.redo(record, pageBuf);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        // Verify the page was modified correctly
        assertThat(BinaryUtil.readU16(pageBuf, PageHeader.OFFSET_PD_LOWER))
            .isEqualTo((short) (PageHeader.SIZE + ItemId.SIZE));

        // Verify the tuple is present
        int itemId = BinaryUtil.readU32(pageBuf, PageHeader.SIZE);
        int tupleOffset = ItemId.offset(itemId);
        int tupleLength = ItemId.length(itemId);

        assertThat(ItemId.flags(itemId)).isEqualTo(ItemId.FLAGS_NORMAL);
        assertThat(tupleLength).isGreaterThan(tupleData.length);

        // Read and verify the tuple header
        HeapTupleHeader header = HeapTupleHeader.readFrom(pageBuf, tupleOffset);
        assertThat(header.xmin()).isEqualTo(Constants.BOOTSTRAP_XID);
        assertThat(header.xmax()).isEqualTo(Constants.INVALID_XID);
        assertThat(header.natts()).isEqualTo(natts);
    }

    @Test
    void redoDelete_shouldMarkTupleAsDeleted() {
        // Create a page with one tuple
        Page page = Page.Factory.allocateHeapPage(PAGE_ID);
        insertDummyTuple(page, 0);

        ByteBuffer pageBuf = page.buffer();

        // Prepare the delete payload
        ByteBuffer payload = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN);
        payload.putInt(0); // slotNo = 0

        // Create the WAL record
        XLogRecord record = XLogRecord.create(
            16,
            Constants.BOOTSTRAP_XID,
            0,
            XLogRecord.RMGR_HEAP,
            HeapResourceManager.HEAP_DELETE,
            TABLE_OID,
            PAGE_ID,
            payload.array()
        );

        // Apply the redo
        HeapResourceManager rmgr = new HeapResourceManager();
        try {
            rmgr.redo(record, pageBuf);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        // Slot stays FLAGS_NORMAL: MVCC deletion sets xmax but keeps the tuple
        // visible to snapshots that predate the delete. LP_DEAD is set later by VACUUM.
        int itemId = BinaryUtil.readU32(pageBuf, PageHeader.SIZE);
        assertThat(ItemId.flags(itemId)).isEqualTo(ItemId.FLAGS_NORMAL);

        // Verify the header has xmax set
        int tupleOffset = ItemId.offset(itemId);
        HeapTupleHeader header = HeapTupleHeader.readFrom(pageBuf, tupleOffset);
        assertThat(header.xmax()).isEqualTo(Constants.BOOTSTRAP_XID);
    }

    @Test
    void redoUpdate_shouldInsertNewVersionAndMarkOldAsDeleted() {
        // Create a page with one tuple
        Page page = Page.Factory.allocateHeapPage(PAGE_ID);
        insertDummyTuple(page, 1); // Insert at slot 1

        ByteBuffer pageBuf = page.buffer();

        // Prepare the update payload
        short natts = 2;
        byte[] bitmap = new byte[(natts + 7) / 8];
        byte[] tupleData = new byte[]{0x0A, 0x0B, 0x0C};

        ByteBuffer payload = ByteBuffer.allocate(10 + bitmap.length + tupleData.length)
            .order(ByteOrder.BIG_ENDIAN);
        payload.putInt(1); // oldSlotNo = 1
        payload.putInt(2); // newSlotNo = 2
        payload.putShort(natts);
        payload.put(bitmap);
        payload.put(tupleData);

        // Create the WAL record
        XLogRecord record = XLogRecord.create(
            16,
            Constants.BOOTSTRAP_XID,
            0,
            XLogRecord.RMGR_HEAP,
            HeapResourceManager.HEAP_UPDATE,
            TABLE_OID,
            PAGE_ID,
            payload.array()
        );

        // Apply the redo
        HeapResourceManager rmgr = new HeapResourceManager();
        try {
            rmgr.redo(record, pageBuf);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        // Old slot stays FLAGS_NORMAL — xmax chains it to the new version.
        // MVCC keeps the old version visible to snapshots that predate the update.
        int oldItemId = BinaryUtil.readU32(pageBuf, PageHeader.SIZE + 4); // slot 1
        assertThat(ItemId.flags(oldItemId)).isEqualTo(ItemId.FLAGS_NORMAL);

        // Verify new tuple exists at slot 2
        int newItemId = BinaryUtil.readU32(pageBuf, PageHeader.SIZE + 8); // slot 2
        assertThat(ItemId.flags(newItemId)).isEqualTo(ItemId.FLAGS_NORMAL);

        // Verify new tuple data
        int newTupleOffset = ItemId.offset(newItemId);
        HeapTupleHeader newHeader = HeapTupleHeader.readFrom(pageBuf, newTupleOffset);
        assertThat(newHeader.natts()).isEqualTo(natts);
    }

    @Test
    void redo_withUnknownOperation_shouldThrow() {
        Page page = Page.Factory.allocateHeapPage(PAGE_ID);

        XLogRecord record = XLogRecord.create(
            16,
            Constants.BOOTSTRAP_XID,
            0,
            XLogRecord.RMGR_HEAP,
            (byte) 0x99, // Unknown operation
            TABLE_OID,
            PAGE_ID,
            new byte[0]
        );

        HeapResourceManager rmgr = new HeapResourceManager();
        assertThatThrownBy(() -> rmgr.redo(record, page.buffer()))
            .isInstanceOf(UnsupportedOperationException.class)
            .hasMessageContaining("Unknown heap operation");
    }

    /**
     * Helper to insert a dummy tuple at the given slot for test setup.
     */
    private void insertDummyTuple(Page page, int slotNo) {
        ByteBuffer pageBuf = page.buffer();

        short natts = 2;
        byte[] bitmap = new byte[(natts + 7) / 8];
        byte[] tupleData = new byte[]{0x01, 0x02};

        ByteBuffer payload = ByteBuffer.allocate(6 + bitmap.length + tupleData.length)
            .order(ByteOrder.BIG_ENDIAN);
        payload.putInt(slotNo);
        payload.putShort(natts);
        payload.put(bitmap);
        payload.put(tupleData);

        XLogRecord record = XLogRecord.create(
            16,
            Constants.BOOTSTRAP_XID,
            0,
            XLogRecord.RMGR_HEAP,
            HeapResourceManager.HEAP_INSERT,
            TABLE_OID,
            PAGE_ID,
            payload.array()
        );

        HeapResourceManager rmgr = new HeapResourceManager();
        try {
            rmgr.redo(record, pageBuf);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
