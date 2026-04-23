package com.coredb.heap;

import com.coredb.api.Row;
import com.coredb.api.Schema;
import com.coredb.page.ItemId;
import com.coredb.page.Page;
import com.coredb.page.PageType;
import com.coredb.storage.DiskManager;
import com.coredb.util.Constants;
import com.coredb.util.StorageException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Iterator;
import java.util.Optional;

public final class HeapFile implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(HeapFile.class);

    private final DiskManager diskManager;
    private final Schema schema;

    public HeapFile(DiskManager diskManager, Schema schema) {
        this.diskManager = diskManager;
        this.schema = schema;
    }

    public RecordId insert(Row row) throws IOException {
        // 1. SERIALIZE: Convert Row → bytes using schema
        SerializedRow serialized = RowSerializer.serialize(row, schema);
        byte[] dataBytes = serialized.data();
        byte[] nullBitmap = serialized.nullBitmap();
        short natts = (short) schema.columnCount();

        // 2. CALCULATE: How much space we need on the page
        int tupleHeaderSize = HeapTupleHeader.computeHeaderSize(natts);
        int tupleSize = tupleHeaderSize + dataBytes.length;
        int spaceNeeded = tupleSize + ItemId.SIZE;

        // 3. FIND PAGE: Linear scan for first page with enough space
        int pageCount = diskManager.pageCount();

        // Page 0 is the meta page; data pages are 1..pageCount-1.
        for (int pageId = 1; pageId < pageCount; pageId++) {
            Page page = diskManager.readPage(pageId);
            if (page.pageType() != PageType.HEAP) {
                continue;
            }
            HeapPage heapPage = new HeapPage(page);
            if (heapPage.freeBytes() >= spaceNeeded) {
                RecordId rid = heapPage.insert(dataBytes, natts, nullBitmap);
                diskManager.writePage(page);
                return rid;
            }
        }

        // 4. ALLOCATE NEW PAGE: No existing page has room
        Page newPage = diskManager.allocatePage(PageType.HEAP);
        HeapPage heapPage = new HeapPage(newPage);
        RecordId rid = heapPage.insert(dataBytes, natts, nullBitmap);
        diskManager.writePage(newPage);
        return rid;
    }

    public Optional<Row> get(RecordId recordId) throws IOException {
        if (recordId.pageId() < 1 || recordId.pageId() >= diskManager.pageCount()) {
            return Optional.empty();
        }

        // 1. Read Page
        Page page = diskManager.readPage(recordId.pageId());
        if (page.pageType() != PageType.HEAP) {
            return Optional.empty();
        }
        HeapPage heapPage = new HeapPage(page);

        try {
            // 2. Get raw bytes (header + data)
            byte[] raw = heapPage.get(recordId.slotNo());
            HeapTupleHeader header = HeapTupleHeader.readFrom(
                    ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN), 0);

            // Stub visibility: BOOTSTRAP_XID = always visible, INVALID_XID = not deleted
            if (header.xmin() != Constants.BOOTSTRAP_XID) {
                return Optional.empty();
            }
            if (header.xmax() != Constants.INVALID_XID) {
                return Optional.empty();
            }

            // Extract data portion (after header)
            int hoff = header.hoff();
            byte[] data = new byte[raw.length - hoff];
            System.arraycopy(raw, hoff, data, 0, data.length);

            Row row = RowSerializer.deserialize(data, schema, header);
            return Optional.of(row);
        } catch (StorageException e) {
            log.debug("Failed to read row at {}: {}", recordId, e.getMessage());
            return Optional.empty();
        }
    }

    public void delete(RecordId rid) throws IOException {
        if (rid.pageId() < 1 || rid.pageId() >= diskManager.pageCount()) {
            throw new StorageException("page " + rid.pageId() + " does not exist");
        }
        Page page = diskManager.readPage(rid.pageId());
        if (page.pageType() != PageType.HEAP) {
            throw new StorageException("page " + rid.pageId() + " is not a heap page");
        }
        HeapPage heapPage = new HeapPage(page);
        heapPage.delete(rid.slotNo());
        diskManager.writePage(page);
    }

    public Iterator<Row> scan() throws IOException {
        // Lazy iterator: one page in memory at a time for large scans
        return new LazyRowIterator();
    }

    public int pageCount() {
        return diskManager.pageCount();
    }

    @Override
    public void close() throws IOException {
        // DiskManager is managed by the caller; nothing to close here
    }

    // Iterates pages sequentially, yielding live rows without materializing all into memory
    private final class LazyRowIterator implements Iterator<Row> {
        private int nextPageId = 1;
        private Page currentPage = null;
        private Iterator<RecordId> currentPageRids = null;
        private Row nextRow = null;

        LazyRowIterator() throws IOException {
            advance();
        }

        @Override
        public boolean hasNext() {
            return nextRow != null;
        }

        @Override
        public Row next() {
            if (nextRow == null) {
                throw new java.util.NoSuchElementException();
            }
            Row result = nextRow;
            try {
                advance();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            return result;
        }

        private void advance() throws IOException {
            while (nextPageId < diskManager.pageCount() ||
                   (currentPageRids != null && currentPageRids.hasNext())) {

                if (currentPageRids != null && currentPageRids.hasNext()) {
                    RecordId rid = currentPageRids.next();
                    Optional<Row> row = fetchFromCurrentPage(rid);
                    if (row.isPresent()) {
                        nextRow = row.get();
                        return;
                    }
                    continue;
                }

                currentPage = diskManager.readPage(nextPageId);
                nextPageId++;
                if (currentPage.pageType() != PageType.HEAP) {
                    currentPageRids = null;
                    continue;
                }
                HeapPage heapPage = new HeapPage(currentPage);
                currentPageRids = heapPage.scan().iterator();
            }
            nextRow = null;
        }

        private Optional<Row> fetchFromCurrentPage(RecordId recordId) {
            HeapPage heapPage = new HeapPage(currentPage);
            try {
                byte[] raw = heapPage.get(recordId.slotNo());
                HeapTupleHeader header = HeapTupleHeader.readFrom(
                        ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN), 0);
                if (header.xmin() != Constants.BOOTSTRAP_XID) {
                    return Optional.empty();
                }
                if (header.xmax() != Constants.INVALID_XID) {
                    return Optional.empty();
                }
                int hoff = header.hoff();
                byte[] data = new byte[raw.length - hoff];
                System.arraycopy(raw, hoff, data, 0, data.length);
                return Optional.of(RowSerializer.deserialize(data, schema, header));
            } catch (StorageException e) {
                return Optional.empty();
            }
        }
    }
}
