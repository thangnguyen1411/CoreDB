package com.coredb.heap;

import com.coredb.mvcc.Snapshot;
import com.coredb.mvcc.TupleVisibility;
import com.coredb.page.ItemId;
import com.coredb.page.Page;
import com.coredb.page.PageHeader;
import com.coredb.txn.ClogManager;
import com.coredb.util.PageFullException;
import com.coredb.util.StorageException;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class HeapPage {

    private final Page page;
    private boolean hintBitsModified = false;

    public HeapPage(Page page) {
        this.page = page;
    }

    /**
     * Returns true if any t_infomask hint bit was written to the page buffer
     * during a visibility check on this instance. The caller should mark the
     * buffer-pool frame dirty when this returns true.
     */
    public boolean wasHintBitsModified() {
        return hintBitsModified;
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

        short infomaskBefore = header.infomask();
        boolean visible = TupleVisibility.isVisible(header, snapshot, clog);
        if (header.infomask() != infomaskBefore) {
            // Hint bits were set during visibility check — write back to page buffer.
            // No WAL emitted: hint bits are a recomputable clog cache.
            header.writeTo(page.buffer(), offset);
            hintBitsModified = true;
        }
        if (!visible) {
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

        HeapTupleHeader header = HeapTupleHeader.readFrom(page.buffer(), offset);
        if (header.xmax() != com.coredb.util.Constants.INVALID_XID) {
            throw new StorageException("slot " + slotNo + " is already deleted");
        }
        header.setXmax(xmax);
        header.writeTo(page.buffer(), offset);
        // Keep ItemId as FLAGS_NORMAL — TupleVisibility filters deleted tuples via xmax
    }

    /**
     * Implements UPDATE as a version chain on a single page.
     *
     * <p>Allocates a new slot S' for the new tuple version, writes it with
     * {@code xmin=xid} and {@code ctid} pointing to itself, then updates the old
     * tuple at {@code slotNo} in-place: sets {@code xmax=xid} and {@code ctid}
     * pointing to S'. Both versions stay on the same page with {@code FLAGS_NORMAL}
     * so MVCC visibility governs which version readers see.</p>
     *
     * @param slotNo slot holding the old tuple version
     * @param dataBytes serialized column data for the new version (no header)
     * @param natts column count for the new tuple header
     * @param bitmap null bitmap for the new tuple
     * @param xid transaction ID performing the update
     * @return the RecordId of the newly inserted version
     * @throws PageFullException if the page has no room for the new version
     */
    public RecordId update(int slotNo, byte[] dataBytes, short natts, byte[] bitmap, int xid) {
        checkSlot(slotNo);
        int oldRaw = page.readItemId(slotNo);
        if (ItemId.flags(oldRaw) != ItemId.FLAGS_NORMAL) {
            throw new StorageException("slot " + slotNo + " is not a live tuple");
        }
        int oldOffset = ItemId.offset(oldRaw);

        HeapTupleHeader oldHeader = HeapTupleHeader.readFrom(page.buffer(), oldOffset);
        if (oldHeader.xmax() != com.coredb.util.Constants.INVALID_XID) {
            throw new StorageException("slot " + slotNo + " is already deleted");
        }

        int headerSize = HeapTupleHeader.computeHeaderSize(natts);
        int tupleSize = headerSize + dataBytes.length;
        if (freeBytes() < tupleSize + ItemId.SIZE) {
            throw new PageFullException("page " + page.pageId() + " has no room for new tuple version");
        }

        int newSlotNo = slotCount();
        int newUpper = pdUpper() - tupleSize;
        RecordId newRid = new RecordId(page.pageId(), newSlotNo);

        HeapTupleHeader newHeader = new HeapTupleHeader(newRid, natts, bitmap);
        newHeader.setXmin(xid);
        newHeader.writeTo(page.buffer(), newUpper);

        ByteBuffer buf = page.buffer();
        for (int i = 0; i < dataBytes.length; i++) {
            buf.put(newUpper + headerSize + i, dataBytes[i]);
        }

        page.writeItemId(newSlotNo, ItemId.pack(newUpper, ItemId.FLAGS_NORMAL, tupleSize));
        page.setPdLower((short) (pdLower() + ItemId.SIZE));
        page.setPdUpper((short) newUpper);

        // Update old tuple in-place: xmax marks it deleted, ctid chains to new version.
        oldHeader.setXmax(xid);
        oldHeader.setCtid(newRid);
        oldHeader.writeTo(page.buffer(), oldOffset);

        return newRid;
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
            short infomaskBefore = header.infomask();
            boolean vis = TupleVisibility.isVisible(header, snapshot, clog);
            if (header.infomask() != infomaskBefore) {
                // Hint bits were set — write back to page buffer (no WAL).
                header.writeTo(page.buffer(), offset);
                hintBitsModified = true;
            }
            if (vis) {
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
