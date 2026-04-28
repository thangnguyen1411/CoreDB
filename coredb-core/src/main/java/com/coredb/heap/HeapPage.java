package com.coredb.heap;

import com.coredb.mvcc.Snapshot;
import com.coredb.mvcc.TupleVisibility;
import com.coredb.page.ItemId;
import com.coredb.page.Page;
import com.coredb.page.PageHeader;
import com.coredb.txn.ClogManager;
import com.coredb.util.StorageException;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class HeapPage {

    private final Page page;

    public HeapPage(Page page) {
        this.page = page;
    }

    public RecordId insert(byte[] dataBytes, short natts, byte[] bitmap, int xmin) {
        int headerSize = HeapTupleHeader.computeHeaderSize(natts);
        int tupleSize = headerSize + dataBytes.length;
        if (freeBytes() < tupleSize + ItemId.SIZE) {
            throw new StorageException("page " + page.pageId() + " has no room for " + tupleSize + " bytes");
        }

        int slotNo = slotCount();
        int newUpper = pdUpper() - tupleSize;

        RecordId self = new RecordId(page.pageId(), slotNo);
        HeapTupleHeader header = new HeapTupleHeader(self, natts, bitmap);
        header.setXmin(xmin);
        header.writeTo(page.buffer(), newUpper);

        ByteBuffer buf = page.buffer();
        for (int i = 0; i < dataBytes.length; i++) {
            buf.put(newUpper + headerSize + i, dataBytes[i]);
        }

        page.writeItemId(slotNo, ItemId.pack(newUpper, ItemId.FLAGS_NORMAL, tupleSize));
        page.setPdLower((short) (pdLower() + ItemId.SIZE));
        page.setPdUpper((short) newUpper);

        return self;
    }

    public RecordId insert(byte[] dataBytes, short natts, int xmin) {
        return insert(dataBytes, natts, new byte[(natts + 7) / 8], xmin);
    }

    public byte[] get(int slotNo) {
        checkSlot(slotNo);
        int raw = page.readItemId(slotNo);
        if (ItemId.flags(raw) != ItemId.FLAGS_NORMAL) {
            throw new StorageException("slot " + slotNo + " is not a live tuple");
        }
        return readTupleBytes(raw);
    }

    public Optional<byte[]> get(int slotNo, Snapshot snapshot, ClogManager clog) {
        checkSlot(slotNo);
        int raw = page.readItemId(slotNo);
        if (ItemId.flags(raw) != ItemId.FLAGS_NORMAL) {
            return Optional.empty();
        }
        int offset = ItemId.offset(raw);
        HeapTupleHeader header = HeapTupleHeader.readFrom(page.buffer(), offset);

        if (!TupleVisibility.isVisible(header, snapshot, clog)) {
            return Optional.empty();
        }
        return Optional.of(readTupleBytes(raw));
    }

    public void delete(int slotNo, int xmax) {
        checkSlot(slotNo);
        int raw = page.readItemId(slotNo);
        if (ItemId.flags(raw) != ItemId.FLAGS_NORMAL) {
            throw new StorageException("slot " + slotNo + " is not a live tuple");
        }
        int offset = ItemId.offset(raw);
        int length = ItemId.length(raw);

        HeapTupleHeader header = HeapTupleHeader.readFrom(page.buffer(), offset);
        header.setXmax(xmax);
        header.writeTo(page.buffer(), offset);
        page.writeItemId(slotNo, ItemId.pack(offset, ItemId.FLAGS_DEAD, length));
    }

    public List<RecordId> scan() {
        List<RecordId> live = new ArrayList<>();
        int count = slotCount();
        for (int slotNo = 0; slotNo < count; slotNo++) {
            int raw = page.readItemId(slotNo);
            if (ItemId.flags(raw) != ItemId.FLAGS_NORMAL) {
                continue;
            }
            live.add(new RecordId(page.pageId(), slotNo));
        }
        return live;
    }

    public List<RecordId> scan(Snapshot snapshot, ClogManager clog) {
        List<RecordId> visible = new ArrayList<>();
        int count = slotCount();
        for (int slotNo = 0; slotNo < count; slotNo++) {
            int raw = page.readItemId(slotNo);
            if (ItemId.flags(raw) != ItemId.FLAGS_NORMAL) {
                continue;
            }
            int offset = ItemId.offset(raw);
            HeapTupleHeader header = HeapTupleHeader.readFrom(page.buffer(), offset);
            if (TupleVisibility.isVisible(header, snapshot, clog)) {
                visible.add(new RecordId(page.pageId(), slotNo));
            }
        }
        return visible;
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
