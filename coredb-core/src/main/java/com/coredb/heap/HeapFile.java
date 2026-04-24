package com.coredb.heap;

import com.coredb.api.Row;
import com.coredb.api.Schema;
import com.coredb.page.ItemId;
import com.coredb.page.Page;
import com.coredb.page.PageType;
import com.coredb.storage.DiskManager;
import com.coredb.util.BinaryUtil;
import com.coredb.util.Constants;
import com.coredb.util.CorruptionException;
import com.coredb.util.StorageException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Iterator;
import java.util.Optional;

public final class HeapFile implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(HeapFile.class);

    // Meta page layout (page 0 of per-table files)
    private static final int META_OFFSET_MAGIC = 0;
    private static final int META_OFFSET_VERSION = 4;
    private static final int META_OFFSET_OID = 8;
    private static final int META_OFFSET_NEXT_PAGE_ID = 12;
    private static final int META_FORMAT_VERSION = 1;

    // Legacy DiskManager-based mode
    private final DiskManager diskManager;
    private final Schema schema;

    // Per-table file mode
    private final Path tablePath;
    private final int oid;
    private final FileChannel channel;
    private int nextPageId;

    /**
     * Legacy constructor: DiskManager-based heap file
     */
    public HeapFile(DiskManager diskManager, Schema schema) {
        this.diskManager = diskManager;
        this.schema = schema;
        this.tablePath = null;
        this.oid = -1;
        this.channel = null;
        this.nextPageId = -1;
    }

    /**
     * Private constructor for per-table file mode.
     */
    private HeapFile(Path tablePath, int oid, Schema schema, FileChannel channel, int nextPageId) {
        this.diskManager = null;
        this.schema = schema;
        this.tablePath = tablePath;
        this.oid = oid;
        this.channel = channel;
        this.nextPageId = nextPageId;
    }

    /**
     * Creates a new per-table heap file with a meta page.
     *
     * @param tablePath path to the table file (e.g., dataDir/base/1/1000)
     * @param oid       the table OID
     * @param schema    the table schema
     * @return a new HeapFile instance
     * @throws IOException if creation fails
     */
    public static HeapFile create(Path tablePath, int oid, Schema schema) throws IOException {
        Path parent = tablePath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        FileChannel channel = FileChannel.open(tablePath,
                StandardOpenOption.READ, StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

        // Write meta page (page 0)
        Page metaPage = buildMetaPage(oid);
        writePageToChannel(channel, metaPage);

        int nextPageId = 1; // Page 0 is meta, data pages start at 1

        log.info("Created heap file: {} (oid={})", tablePath.toAbsolutePath(), oid);
        return new HeapFile(tablePath, oid, schema, channel, nextPageId);
    }

    /**
     * Opens an existing per-table heap file and validates the meta page.
     *
     * @param tablePath path to the table file
     * @param oid       expected table OID
     * @param schema    the table schema
     * @return a HeapFile instance
     * @throws IOException         if file cannot be read
     * @throws CorruptionException if meta page validation fails
     */
    public static HeapFile open(Path tablePath, int oid, Schema schema) throws IOException {
        if (!Files.exists(tablePath)) {
            throw new IOException("Heap file not found: " + tablePath);
        }

        FileChannel channel = FileChannel.open(tablePath,
                StandardOpenOption.READ, StandardOpenOption.WRITE);

        // Read and validate meta page
        Page metaPage = readPageFromChannel(channel, 0);
        int nextPageId = validateMetaPage(metaPage, tablePath, oid);

        log.info("Opened heap file: {} (oid={}, pages={})", tablePath.toAbsolutePath(), oid, nextPageId);
        return new HeapFile(tablePath, oid, schema, channel, nextPageId);
    }

    /**
     * Returns true if this heap file is using per-table file mode.
     */
    public boolean isPerTableMode() {
        return channel != null;
    }

    /**
     * Returns the table path (per-table mode only).
     */
    public Path tablePath() {
        if (tablePath == null) {
            throw new IllegalStateException("Not in per-table file mode");
        }
        return tablePath;
    }

    /**
     * Returns the table OID (per-table mode only).
     */
    public int oid() {
        if (oid < 0) {
            throw new IllegalStateException("Not in per-table file mode");
        }
        return oid;
    }

    /**
     * Returns the next page ID (per-table mode only).
     */
    public int nextPageId() {
        if (nextPageId < 0) {
            throw new IllegalStateException("Not in per-table file mode");
        }
        return nextPageId;
    }

    public RecordId insert(Row row) throws IOException {
        if (diskManager == null) {
            throw new UnsupportedOperationException("insert() not available in per-table mode (use per-table allocation path)");
        }
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
        if (diskManager == null) {
            throw new UnsupportedOperationException("get() not available in per-table mode (use per-table allocation path)");
        }
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
        if (diskManager == null) {
            throw new UnsupportedOperationException("delete() not available in per-table mode (use per-table allocation path)");
        }
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
        if (diskManager == null) {
            throw new UnsupportedOperationException("scan() not available in per-table mode (use per-table allocation path)");
        }
        // Lazy iterator: one page in memory at a time for large scans
        return new LazyRowIterator();
    }

    public int pageCount() {
        if (diskManager == null) {
            throw new UnsupportedOperationException("pageCount() not available in per-table mode (use per-table allocation path)");
        }
        return diskManager.pageCount();
    }

    @Override
    public void close() throws IOException {
        if (channel != null) {
            channel.force(true);
            channel.close();
        }
        // DiskManager is managed by the caller; nothing to close for legacy mode
    }

    // ==================== Per-table file helper methods ====================

    /**
     * Builds the meta page (page 0) for a per-table heap file.
     */
    private static Page buildMetaPage(int oid) {
        Page page = new Page(0, PageType.META);
        ByteBuffer buf = page.buffer();
        BinaryUtil.writeU32(buf, META_OFFSET_MAGIC, Constants.HEAP_FILE_MAGIC);
        BinaryUtil.writeU32(buf, META_OFFSET_VERSION, META_FORMAT_VERSION);
        BinaryUtil.writeU32(buf, META_OFFSET_OID, oid);
        BinaryUtil.writeU32(buf, META_OFFSET_NEXT_PAGE_ID, 1); // Data pages start at 1
        return page;
    }

    /**
     * Validates the meta page and returns the nextPageId.
     *
     * @throws CorruptionException if validation fails
     */
    private static int validateMetaPage(Page metaPage, Path tablePath, int expectedOid) {
        ByteBuffer buf = metaPage.buffer();

        int magic = BinaryUtil.readU32(buf, META_OFFSET_MAGIC);
        if (magic != Constants.HEAP_FILE_MAGIC) {
            throw new CorruptionException(String.format(
                "Heap file magic mismatch: expected 0x%08X, got 0x%08X",
                Constants.HEAP_FILE_MAGIC, magic));
        }

        int version = BinaryUtil.readU32(buf, META_OFFSET_VERSION);
        if (version != META_FORMAT_VERSION) {
            throw new CorruptionException(String.format(
                "Heap file version mismatch: expected %d, got %d",
                META_FORMAT_VERSION, version));
        }

        int oid = BinaryUtil.readU32(buf, META_OFFSET_OID);
        if (oid != expectedOid) {
            throw new CorruptionException(String.format(
                "Heap file OID mismatch: expected %d, got %d", expectedOid, oid));
        }

        return BinaryUtil.readU32(buf, META_OFFSET_NEXT_PAGE_ID);
    }

    /**
     * Writes a page to the file channel at the correct position.
     */
    private static void writePageToChannel(FileChannel channel, Page page) throws IOException {
        ByteBuffer buf = page.buffer().duplicate();
        buf.clear();
        long pos = (long) page.pageId() * Constants.PAGE_SIZE;
        while (buf.hasRemaining()) {
            pos += channel.write(buf, pos);
        }
    }

    /**
     * Reads a page from the file channel at the given page ID.
     */
    private static Page readPageFromChannel(FileChannel channel, int pageId) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(Constants.PAGE_SIZE).order(ByteOrder.BIG_ENDIAN);
        long pos = (long) pageId * Constants.PAGE_SIZE;
        while (buf.hasRemaining()) {
            int n = channel.read(buf, pos);
            if (n == -1) {
                throw new StorageException("Unexpected EOF reading page " + pageId);
            }
            pos += n;
        }
        return new Page(pageId, buf);
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
