package com.coredb.wal;

import com.coredb.page.ItemId;
import com.coredb.page.PageHeader;
import com.coredb.util.BinaryUtil;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Resource manager for B+ tree index operations.
 *
 * <p>Handles redo of BTREE_INSERT and BTREE_SPLIT operations.</p>
 *
 * <p>Payload formats:</p>
 * <ul>
 *   <li>BTREE_INSERT: (int slotNo, long key, int ridPageId, short ridSlotNo)</li>
 *   <li>BTREE_SPLIT: complex format with separator key, new page ID, and moved entries</li>
 * </ul>
 */
public final class BTreeResourceManager implements ResourceManager {

    // Operation codes
    public static final byte BTREE_INSERT = 0x10;
    public static final byte BTREE_SPLIT = 0x11;
    public static final byte BTREE_INTERNAL_INSERT = 0x12;
    public static final byte BTREE_INTERNAL_SPLIT = 0x13;

    @Override
    public byte getResourceManagerId() {
        return XLogRecord.RMGR_BTREE;
    }

    @Override
    public void redo(XLogRecord record, ByteBuffer targetPage) throws IOException {
        targetPage.order(ByteOrder.BIG_ENDIAN);

        switch (record.info()) {
            case BTREE_INSERT:
                redoLeafInsert(record, targetPage);
                break;
            case BTREE_SPLIT:
                redoLeafSplit(record, targetPage);
                break;
            case BTREE_INTERNAL_INSERT:
                redoInternalInsert(record, targetPage);
                break;
            case BTREE_INTERNAL_SPLIT:
                redoInternalSplit(record, targetPage);
                break;
            default:
                throw new UnsupportedOperationException(
                    "Unknown btree operation: 0x" + Integer.toHexString(record.info() & 0xFF));
        }
    }

    /**
     * Redo a BTREE_INSERT operation on a leaf page.
     *
     * <p>Payload format:
     * <pre>
     * Offset  Size  Field
     * 0       4     slotNo         insertion slot position
     * 4       8     key            the key value
     * 12      4     ridPageId      RecordId page ID
     * 16      2     ridSlotNo      RecordId slot number
     * </pre>
     */
    private void redoLeafInsert(XLogRecord record, ByteBuffer page) {
        byte[] data = record.data();
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);

        int slotNo = buf.getInt();
        long key = buf.getLong();
        int ridPageId = buf.getInt();
        short ridSlotNo = buf.getShort();

        // Shift existing ItemIds to make room
        short pdLower = BinaryUtil.readU16(page, PageHeader.OFFSET_PD_LOWER);
        int entryCount = (pdLower - PageHeader.SIZE) / ItemId.SIZE;

        for (int i = entryCount - 1; i >= slotNo; i--) {
            int itemId = page.getInt(PageHeader.SIZE + i * ItemId.SIZE);
            page.putInt(PageHeader.SIZE + (i + 1) * ItemId.SIZE, itemId);
        }

        // Calculate position for tuple data
        short pdUpper = BinaryUtil.readU16(page, PageHeader.OFFSET_PD_UPPER);
        int tupleOffset = pdUpper - 14; // 14 bytes for leaf entry (8 key + 6 RecordId)

        // Write the tuple data
        page.putLong(tupleOffset, key);
        page.putInt(tupleOffset + 8, ridPageId);
        page.putShort(tupleOffset + 12, ridSlotNo);

        // Write the ItemId
        int itemId = ItemId.pack(tupleOffset, ItemId.FLAGS_NORMAL, 14);
        page.putInt(PageHeader.SIZE + slotNo * ItemId.SIZE, itemId);

        // Update page header
        BinaryUtil.writeU16(page, PageHeader.OFFSET_PD_LOWER, (short) (pdLower + ItemId.SIZE));
        BinaryUtil.writeU16(page, PageHeader.OFFSET_PD_UPPER, (short) tupleOffset);
    }

    /**
     * Redo a BTREE_SPLIT operation on a leaf page.
     *
     * <p>Payload format for leaf split:
     * <pre>
     * Offset  Size  Field
     * 0       4     newRightPageId  ID of the new right sibling
     * 4       4     oldRightSibling  previous right sibling (0 if none)
     * 8       8     separatorKey     smallest key on right page
     * 16      4     movedCount       number of entries that moved to right
     * 20      N     entries          array of (key, ridPageId, ridSlotNo) for moved entries
     * </pre>
     *
     * <p>Note: This redo applies to the *left* page (the one being split). The right
     * page is handled by a separate insert record or is reinitialized.</p>
     */
    private void redoLeafSplit(XLogRecord record, ByteBuffer page) {
        byte[] data = record.data();
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);

        int newRightPageId = buf.getInt();
        // These fields are part of the WAL record format but not needed for basic redo
        // oldRightSibling and separatorKey are used for sibling chain maintenance
        // movedCount indicates how many entries moved to the right page
        buf.getInt(); // oldRightSibling
        buf.getLong();  // separatorKey
        buf.getInt();   // movedCount

        // Mark this page as having a new right sibling
        // btpo_next at offset PAGE_SIZE - 12 + 4 (after btpo_prev)
        int btpoNextOffset = page.capacity() - 12 + 4;
        page.putInt(btpoNextOffset, newRightPageId);

        // For a complete redo, we would need to also handle re-initialization of this page
        // with only the left entries. For now, we rely on the fact that the split record
        // contains enough info to reconstruct, but the actual reconstruction is complex.
        // In practice, the full-page write (FPW) mechanism handles the hard cases.
    }

    /**
     * Redo a BTREE_INTERNAL_INSERT operation.
     *
     * <p>Payload format:
     * <pre>
     * Offset  Size  Field
     * 0       4     slotNo         insertion slot position
     * 4       8     key            separator key
     * 12      4     childPageId    child page ID
     * </pre>
     */
    private void redoInternalInsert(XLogRecord record, ByteBuffer page) {
        byte[] data = record.data();
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);

        int slotNo = buf.getInt();
        long key = buf.getLong();
        int childPageId = buf.getInt();

        // Shift existing ItemIds to make room
        short pdLower = BinaryUtil.readU16(page, PageHeader.OFFSET_PD_LOWER);
        int entryCount = (pdLower - PageHeader.SIZE) / ItemId.SIZE;

        for (int i = entryCount - 1; i >= slotNo; i--) {
            int itemId = page.getInt(PageHeader.SIZE + i * ItemId.SIZE);
            page.putInt(PageHeader.SIZE + (i + 1) * ItemId.SIZE, itemId);
        }

        // Calculate position for tuple data
        short pdUpper = BinaryUtil.readU16(page, PageHeader.OFFSET_PD_UPPER);
        int tupleOffset = pdUpper - 12; // 12 bytes for internal entry (8 key + 4 childPageId)

        // Write the tuple data
        page.putLong(tupleOffset, key);
        page.putInt(tupleOffset + 8, childPageId);

        // Write the ItemId
        int itemId = ItemId.pack(tupleOffset, ItemId.FLAGS_NORMAL, 12);
        page.putInt(PageHeader.SIZE + slotNo * ItemId.SIZE, itemId);

        // Update page header
        BinaryUtil.writeU16(page, PageHeader.OFFSET_PD_LOWER, (short) (pdLower + ItemId.SIZE));
        BinaryUtil.writeU16(page, PageHeader.OFFSET_PD_UPPER, (short) tupleOffset);
    }

    /**
     * Redo a BTREE_INTERNAL_SPLIT operation.
     *
     * <p>Similar to leaf split but for internal pages. The middle key is promoted
     * rather than staying on the right page.</p>
     */
    private void redoInternalSplit(XLogRecord record, ByteBuffer page) {
        byte[] data = record.data();
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);

        int newRightPageId = buf.getInt();

        // Update btpo_next to point to new right sibling
        int btpoNextOffset = page.capacity() - 12 + 4;
        page.putInt(btpoNextOffset, newRightPageId);
    }
}
