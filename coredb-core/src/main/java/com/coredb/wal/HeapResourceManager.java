package com.coredb.wal;

import com.coredb.heap.HeapTupleHeader;
import com.coredb.heap.RecordId;
import com.coredb.page.ItemId;
import com.coredb.page.PageHeader;
import com.coredb.util.BinaryUtil;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Resource manager for heap table operations.
 *
 * <p>Handles redo of HEAP_INSERT, HEAP_DELETE, and HEAP_UPDATE operations.</p>
 *
 * <p>Payload formats:</p>
 * <ul>
 *   <li>HEAP_INSERT: (int slotNo, short natts, byte[] bitmap, byte[] tupleBytes)</li>
 *   <li>HEAP_DELETE: (int slotNo)</li>
 *   <li>HEAP_UPDATE: (int oldSlotNo, int newSlotNo, short natts, byte[] bitmap, byte[] tupleBytes)</li>
 * </ul>
 */
public final class HeapResourceManager implements ResourceManager {

    // Operation codes
    public static final byte HEAP_INSERT = 0x01;
    public static final byte HEAP_DELETE = 0x02;
    public static final byte HEAP_UPDATE = 0x03;

    @Override
    public byte rmgrId() {
        return XLogRecord.RMGR_HEAP;
    }

    @Override
    public void redo(XLogRecord record, ByteBuffer targetPage) throws IOException {
        targetPage.order(ByteOrder.BIG_ENDIAN);

        switch (record.info()) {
            case HEAP_INSERT:
                redoInsert(record, targetPage);
                break;
            case HEAP_DELETE:
                redoDelete(record, targetPage);
                break;
            case HEAP_UPDATE:
                redoUpdate(record, targetPage);
                break;
            default:
                throw new UnsupportedOperationException(
                    "Unknown heap operation: 0x" + Integer.toHexString(record.info() & 0xFF));
        }
    }

    /**
     * Redo a HEAP_INSERT operation.
     *
     * <p>Payload format:
     * <pre>
     * Offset  Size  Field
     * 0       4     slotNo         target slot number
     * 4       2     natts           number of attributes
     * 6       N     bitmap          null bitmap (ceil(natts/8) bytes)
     * 6+N     M     tupleBytes      serialized tuple data
     * </pre>
     */
    private void redoInsert(XLogRecord record, ByteBuffer page) {
        byte[] data = record.data();
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);

        int slotNo = buf.getInt();
        short natts = buf.getShort();

        int bitmapSize = (natts + 7) / 8;
        byte[] bitmap = new byte[bitmapSize];
        buf.get(bitmap);

        int tupleDataSize = data.length - 6 - bitmapSize;
        byte[] tupleBytes = new byte[tupleDataSize];
        buf.get(tupleBytes);

        int headerSize = HeapTupleHeader.computeHeaderSize(natts);
        int tupleSize = headerSize + tupleDataSize;

        // Calculate where tuple will be placed
        short pdUpper = BinaryUtil.readU16(page, PageHeader.OFFSET_PD_UPPER);
        int tupleOffset = pdUpper - tupleSize;

        // Write the tuple header
        RecordId self = new RecordId(record.pageId(), slotNo);
        HeapTupleHeader header = new HeapTupleHeader(self, natts, bitmap);
        header.writeTo(page, tupleOffset);

        // Write the tuple data after the header
        for (int i = 0; i < tupleDataSize; i++) {
            page.put(tupleOffset + headerSize + i, tupleBytes[i]);
        }

        // Write the ItemId
        int itemId = ItemId.pack(tupleOffset, ItemId.FLAGS_NORMAL, tupleSize);
        int itemIdOffset = PageHeader.SIZE + slotNo * ItemId.SIZE;
        BinaryUtil.writeU32(page, itemIdOffset, itemId);

        // Update page header
        short pdLower = BinaryUtil.readU16(page, PageHeader.OFFSET_PD_LOWER);
        BinaryUtil.writeU16(page, PageHeader.OFFSET_PD_LOWER, (short) (pdLower + ItemId.SIZE));
        BinaryUtil.writeU16(page, PageHeader.OFFSET_PD_UPPER, (short) tupleOffset);
    }

    /**
     * Redo a HEAP_DELETE operation.
     *
     * <p>Payload format:
     * <pre>
     * Offset  Size  Field
     * 0       4     slotNo         slot to delete
     * </pre>
     *
     * <p>Sets the tuple's t_xmax to BOOTSTRAP_XID and marks the ItemId as DEAD.</p>
     */
    private void redoDelete(XLogRecord record, ByteBuffer page) {
        byte[] data = record.data();
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);

        int slotNo = buf.getInt();

        // Read the ItemId to find tuple offset
        int itemIdOffset = PageHeader.SIZE + slotNo * ItemId.SIZE;
        int rawItemId = page.getInt(itemIdOffset);
        int tupleOffset = ItemId.offset(rawItemId);
        int tupleLength = ItemId.length(rawItemId);

        // Read and update the tuple header
        HeapTupleHeader header = HeapTupleHeader.readFrom(page, tupleOffset);
        header.setXmax(com.coredb.util.Constants.BOOTSTRAP_XID);
        header.writeTo(page, tupleOffset);

        // Mark ItemId as DEAD (keep same offset and length)
        int deadItemId = ItemId.pack(tupleOffset, ItemId.FLAGS_DEAD, tupleLength);
        page.putInt(itemIdOffset, deadItemId);
    }

    /**
     * Redo a HEAP_UPDATE operation.
     *
     * <p>This is effectively an insert of the new version plus a delete of the old.
     * For simplicity, we treat it as two operations.</p>
     *
     * <p>Payload format:
     * <pre>
     * Offset  Size  Field
     * 0       4     oldSlotNo       slot of old version to mark deleted
     * 4       4     newSlotNo       slot for new version
     * 8       2     natts           number of attributes
     * 10      N     bitmap          null bitmap
     * 10+N    M     tupleBytes      serialized tuple data
     * </pre>
     */
    private void redoUpdate(XLogRecord record, ByteBuffer page) {
        byte[] data = record.data();
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);

        int oldSlotNo = buf.getInt();
        int newSlotNo = buf.getInt();
        short natts = buf.getShort();

        int bitmapSize = (natts + 7) / 8;
        byte[] bitmap = new byte[bitmapSize];
        buf.get(bitmap);

        int tupleDataSize = data.length - 10 - bitmapSize;
        byte[] tupleBytes = new byte[tupleDataSize];
        buf.get(tupleBytes);

        // First, mark the old tuple as deleted
        int oldItemIdOffset = PageHeader.SIZE + oldSlotNo * ItemId.SIZE;
        int oldRawItemId = page.getInt(oldItemIdOffset);
        int oldTupleOffset = ItemId.offset(oldRawItemId);
        int oldTupleLength = ItemId.length(oldRawItemId);

        HeapTupleHeader oldHeader = HeapTupleHeader.readFrom(page, oldTupleOffset);
        oldHeader.setXmax(com.coredb.util.Constants.BOOTSTRAP_XID);
        oldHeader.writeTo(page, oldTupleOffset);

        int deadItemId = ItemId.pack(oldTupleOffset, ItemId.FLAGS_DEAD, oldTupleLength);
        page.putInt(oldItemIdOffset, deadItemId);

        // Then, insert the new tuple
        int headerSize = HeapTupleHeader.computeHeaderSize(natts);
        int tupleSize = headerSize + tupleDataSize;

        short pdUpper = BinaryUtil.readU16(page, PageHeader.OFFSET_PD_UPPER);
        int tupleOffset = pdUpper - tupleSize;

        RecordId self = new RecordId(record.pageId(), newSlotNo);
        HeapTupleHeader newHeader = new HeapTupleHeader(self, natts, bitmap);
        newHeader.writeTo(page, tupleOffset);

        for (int i = 0; i < tupleDataSize; i++) {
            page.put(tupleOffset + headerSize + i, tupleBytes[i]);
        }

        int newItemId = ItemId.pack(tupleOffset, ItemId.FLAGS_NORMAL, tupleSize);
        int newItemIdOffset = PageHeader.SIZE + newSlotNo * ItemId.SIZE;
        BinaryUtil.writeU32(page, newItemIdOffset, newItemId);

        short pdLower = BinaryUtil.readU16(page, PageHeader.OFFSET_PD_LOWER);
        if (newSlotNo >= (pdLower - PageHeader.SIZE) / ItemId.SIZE) {
            BinaryUtil.writeU16(page, PageHeader.OFFSET_PD_LOWER, (short) (pdLower + ItemId.SIZE));
        }
        if (tupleOffset < pdUpper) {
            BinaryUtil.writeU16(page, PageHeader.OFFSET_PD_UPPER, (short) tupleOffset);
        }
    }
}
