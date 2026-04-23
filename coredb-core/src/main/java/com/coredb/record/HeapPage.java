package com.coredb.record;

import com.coredb.page.ItemId;
import com.coredb.page.Page;
import com.coredb.page.PageHeader;
import com.coredb.util.StorageException;

import java.nio.ByteBuffer;

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

        int slotNo   = slotCount();
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
