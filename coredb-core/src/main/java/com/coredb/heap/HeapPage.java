package com.coredb.heap;

import com.coredb.page.ItemId;
import com.coredb.page.Page;
import com.coredb.page.PageHeader;
import com.coredb.util.Constants;
import com.coredb.util.StorageException;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public final class HeapPage {

    private final Page page;

    public HeapPage(Page page) {
        this.page = page;
    }

    public RecordId insert(byte[] dataBytes) {
        int tupleSize = HeapTupleHeader.HEADER_SIZE + dataBytes.length;
        if (freeBytes() < tupleSize + ItemId.SIZE) {
            throw new StorageException("page " + page.pageId() + " has no room for " + tupleSize + " bytes");
        }

        int slotNo = slotCount();
        int newUpper = pdUpper() - tupleSize;

        RecordId self = new RecordId(page.pageId(), slotNo);
        new HeapTupleHeader(self).writeTo(page.buffer(), newUpper);

        ByteBuffer buf = page.buffer();
        for (int i = 0; i < dataBytes.length; i++) {
            buf.put(newUpper + HeapTupleHeader.HEADER_SIZE + i, dataBytes[i]);
        }

        page.writeItemId(slotNo, ItemId.pack(newUpper, ItemId.FLAGS_NORMAL, tupleSize));
        page.setPdLower((short) (pdLower() + ItemId.SIZE));
        page.setPdUpper((short) newUpper);

        return self;
    }

    public byte[] get(int slotNo) {
        checkSlot(slotNo);
        int raw = page.readItemId(slotNo);
        if (ItemId.flags(raw) != ItemId.FLAGS_NORMAL) {
            throw new StorageException("slot " + slotNo + " is not a live tuple");
        }
        return readTupleBytes(raw);
    }

    /**
     * Marks a tuple as deleted. Two-step process:
     * 1. Set t_xmax in the tuple header — records which transaction deleted it.
     *    The tuple bytes remain on disk; only the header is modified.
     * 2. Flip the ItemId flag to DEAD — the primary visibility gate.
     *    get() and scan() skip slots with flags != NORMAL, making the tuple invisible.
     * Space is not reclaimed; VACUUM (Phase 10) will reclaim it later.
     */
    public void delete(int slotNo) {
        checkSlot(slotNo);
        int raw = page.readItemId(slotNo);
        if (ItemId.flags(raw) != ItemId.FLAGS_NORMAL) {
            throw new StorageException("slot " + slotNo + " is not a live tuple");
        }
        int offset = ItemId.offset(raw);
        int length = ItemId.length(raw);

        // Step 1: Update tuple header — set xmax to mark the deleting transaction.
        HeapTupleHeader header = HeapTupleHeader.readFrom(page.buffer(), offset);
        header.setXmax(Constants.BOOTSTRAP_XID);
        header.writeTo(page.buffer(), offset);

        // Step 2: Flip ItemId flag to DEAD — this is the primary visibility gate.
        page.writeItemId(slotNo, ItemId.pack(offset, ItemId.FLAGS_DEAD, length));
    }

    /**
     * Returns RecordIds of all live tuples on this page.
     * A tuple is live if its ItemId flag is NORMAL and its xmin matches
     * the bootstrap transaction.
     */
    public List<RecordId> scan() {
        List<RecordId> live = new ArrayList<>();
        int count = slotCount();
        for (int slotNo = 0; slotNo < count; slotNo++) {
            int raw = page.readItemId(slotNo);
            if (ItemId.flags(raw) != ItemId.FLAGS_NORMAL) {
                continue;
            }
            int offset = ItemId.offset(raw);
            HeapTupleHeader header = HeapTupleHeader.readFrom(page.buffer(), offset);
            if (header.xmin() == Constants.BOOTSTRAP_XID) {
                live.add(new RecordId(page.pageId(), slotNo));
            }
        }
        return live;
    }

    public int freeBytes() {
        return page.freeBytes();
    }

    public int slotCount() {
        return (pdLower() - PageHeader.SIZE) / ItemId.SIZE;
    }

    public Page page() {
        return page;
    }

    private byte[] readTupleBytes(int itemId) {
        int offset = ItemId.offset(itemId);
        int length = ItemId.length(itemId);
        byte[] bytes = new byte[length];
        ByteBuffer buf = page.buffer();
        for (int i = 0; i < length; i++) {
            bytes[i] = buf.get(offset + i);
        }
        return bytes;
    }

    private void checkSlot(int slotNo) {
        if (slotNo < 0 || slotNo >= slotCount()) {
            throw new StorageException("slot " + slotNo + " out of range (page has " + slotCount() + " slots)");
        }
    }

    private int pdLower() {
        return Short.toUnsignedInt(page.pdLower());
    }

    private int pdUpper() {
        return Short.toUnsignedInt(page.pdUpper());
    }
}
