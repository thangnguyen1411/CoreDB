package com.coredb.wal;

import com.coredb.page.ItemId;
import com.coredb.page.Page;
import com.coredb.page.PageHeader;
import com.coredb.page.PageType;
import com.coredb.util.BinaryUtil;
import com.coredb.util.Constants;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for BTreeResourceManager WAL redo operations.
 */
class BTreeResourceManagerTest {

    private static final int TABLE_OID = 1002;
    private static final int PAGE_ID = 1;
    private static final int BTOPAQUE_OFFSET = Constants.PAGE_SIZE - com.coredb.index.BTPageOpaque.SIZE;

    @Test
    void redoLeafInsert_shouldAddEntryToPage() {
        // Create an empty leaf page
        Page page = Page.Factory.allocate(PAGE_ID, PageType.INDEX_LEAF);
        ByteBuffer pageBuf = page.buffer();

        // Initialize btpo_level to 0 (leaf)
        pageBuf.putInt(BTOPAQUE_OFFSET + 8, 0);

        // Prepare the insert payload: (slotNo, key, ridPageId, ridSlotNo)
        ByteBuffer payload = ByteBuffer.allocate(18).order(ByteOrder.BIG_ENDIAN);
        payload.putInt(0);      // slotNo = 0
        payload.putLong(42L);   // key = 42
        payload.putInt(5);      // ridPageId = 5
        payload.putShort((short) 3); // ridSlotNo = 3

        // Create the WAL record
        XLogRecord record = XLogRecord.create(
            16,
            Constants.BOOTSTRAP_XID,
            0,
            XLogRecord.RMGR_BTREE,
            BTreeResourceManager.BTREE_INSERT,
            TABLE_OID,
            PAGE_ID,
            payload.array()
        );

        // Apply the redo
        BTreeResourceManager rmgr = new BTreeResourceManager();
        try {
            rmgr.redo(record, pageBuf);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        // Verify the page was modified
        assertThat(BinaryUtil.readU16(pageBuf, PageHeader.OFFSET_PD_LOWER))
            .isEqualTo((short) (PageHeader.SIZE + ItemId.SIZE));

        // Verify the entry
        int itemId = BinaryUtil.readU32(pageBuf, PageHeader.SIZE);
        int tupleOffset = ItemId.offset(itemId);

        assertThat(ItemId.flags(itemId)).isEqualTo(ItemId.FLAGS_NORMAL);
        assertThat(pageBuf.getLong(tupleOffset)).isEqualTo(42L);
        assertThat(pageBuf.getInt(tupleOffset + 8)).isEqualTo(5);
        assertThat(pageBuf.getShort(tupleOffset + 12)).isEqualTo((short) 3);
    }

    @Test
    void redoInternalInsert_shouldAddEntryToInternalPage() {
        // Create an empty internal page
        Page page = Page.Factory.allocate(PAGE_ID, PageType.INDEX_INTERNAL);
        ByteBuffer pageBuf = page.buffer();

        // Initialize btpo_level to 1 (internal)
        pageBuf.putInt(BTOPAQUE_OFFSET + 8, 1);

        // Prepare the insert payload: (slotNo, key, childPageId)
        ByteBuffer payload = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN);
        payload.putInt(0);      // slotNo = 0
        payload.putLong(100L);  // key = 100
        payload.putInt(7);      // childPageId = 7

        // Create the WAL record
        XLogRecord record = XLogRecord.create(
            16,
            Constants.BOOTSTRAP_XID,
            0,
            XLogRecord.RMGR_BTREE,
            BTreeResourceManager.BTREE_INTERNAL_INSERT,
            TABLE_OID,
            PAGE_ID,
            payload.array()
        );

        // Apply the redo
        BTreeResourceManager rmgr = new BTreeResourceManager();
        try {
            rmgr.redo(record, pageBuf);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        // Verify the entry
        int itemId = BinaryUtil.readU32(pageBuf, PageHeader.SIZE);
        int tupleOffset = ItemId.offset(itemId);

        assertThat(pageBuf.getLong(tupleOffset)).isEqualTo(100L);
        assertThat(pageBuf.getInt(tupleOffset + 8)).isEqualTo(7);
    }

    @Test
    void redoLeafSplit_shouldUpdateBtpoNext() {
        Page page = Page.Factory.allocate(PAGE_ID, PageType.INDEX_LEAF);
        ByteBuffer pageBuf = page.buffer();

        // Prepare the split payload
        ByteBuffer payload = ByteBuffer.allocate(20).order(ByteOrder.BIG_ENDIAN);
        payload.putInt(3);      // newRightPageId = 3
        payload.putInt(5);      // oldRightSibling = 5
        payload.putLong(50L);   // separatorKey = 50
        payload.putInt(2);      // movedCount = 2

        XLogRecord record = XLogRecord.create(
            16,
            Constants.BOOTSTRAP_XID,
            0,
            XLogRecord.RMGR_BTREE,
            BTreeResourceManager.BTREE_SPLIT,
            TABLE_OID,
            PAGE_ID,
            payload.array()
        );

        BTreeResourceManager rmgr = new BTreeResourceManager();
        try {
            rmgr.redo(record, pageBuf);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        // Verify btpo_next was updated
        int btpoNext = pageBuf.getInt(BTOPAQUE_OFFSET + 4);
        assertThat(btpoNext).isEqualTo(3);
    }

    @Test
    void redo_withUnknownOperation_shouldThrow() {
        Page page = Page.Factory.allocate(PAGE_ID, PageType.INDEX_LEAF);

        XLogRecord record = XLogRecord.create(
            16,
            Constants.BOOTSTRAP_XID,
            0,
            XLogRecord.RMGR_BTREE,
            (byte) 0x99, // Unknown operation
            TABLE_OID,
            PAGE_ID,
            new byte[0]
        );

        BTreeResourceManager rmgr = new BTreeResourceManager();
        assertThatThrownBy(() -> rmgr.redo(record, page.buffer()))
            .isInstanceOf(UnsupportedOperationException.class)
            .hasMessageContaining("Unknown btree operation");
    }

    @Test
    void rmgrId_shouldReturnBtreeConstant() {
        BTreeResourceManager rmgr = new BTreeResourceManager();
        assertThat(rmgr.getResourceManagerId()).isEqualTo(XLogRecord.RMGR_BTREE);
    }
}
