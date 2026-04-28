package com.coredb.heap;

import com.coredb.api.Row;
import com.coredb.api.Schema;
import com.coredb.vacuum.PageCompactor;
import com.coredb.buffer.BufferDescriptor;
import com.coredb.buffer.BufferPool;
import com.coredb.fsm.FreeSpaceMap;
import com.coredb.mvcc.Snapshot;
import com.coredb.page.ItemId;
import com.coredb.page.Page;
import com.coredb.page.PageHeader;
import com.coredb.page.PageIO;
import com.coredb.page.PageType;
import com.coredb.txn.ClogManager;
import com.coredb.util.BinaryUtil;
import com.coredb.util.Constants;
import com.coredb.util.CorruptionException;
import com.coredb.util.PageFullException;
import com.coredb.util.StorageException;
import com.coredb.wal.HeapResourceManager;
import com.coredb.wal.XLogRecord;
import com.coredb.wal.XLogWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
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

    // Per-table file mode
    private final Path tablePath;
    private final int oid;
    private final Schema schema;
    private final BufferPool bufferPool;
    private final FileChannel channel; // Used for bootstrap mode when bufferPool is null
    private final XLogWriter xlogWriter; // May be null during bootstrap
    private final int xid; // Transaction ID for WAL records (BOOTSTRAP_XID for now)
    private int nextPageId;
    private Page metaPage;
    private final FreeSpaceMap fsm;

    /**
     * Private constructor for per-table file mode.
     */
    private HeapFile(Path tablePath, int oid, Schema schema, BufferPool bufferPool, int nextPageId, Page metaPage, FreeSpaceMap fsm, FileChannel channel, XLogWriter xlogWriter, int xid) {
        this.schema = schema;
        this.tablePath = tablePath;
        this.oid = oid;
        this.bufferPool = bufferPool;
        this.channel = channel;
        this.xlogWriter = xlogWriter;
        this.xid = xid;
        this.nextPageId = nextPageId;
        this.metaPage = metaPage;
        this.fsm = fsm;
    }

    /**
     * Creates a new per-table heap file with a meta page (for bootstrap use only).
     * This version does not use the buffer pool and writes directly to disk.
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
        Page metaPage = buildInitialMetaPage(oid);
        PageIO.writePage(channel, metaPage);

        int nextPageId = 1; // Page 0 is meta, data pages start at 1

        // Create FSM file alongside heap file (initially 0 pages tracked)
        Path fsmPath = tablePath.getParent().resolve(tablePath.getFileName() + "_fsm");
        FreeSpaceMap fsm = FreeSpaceMap.create(fsmPath, 0);

        log.info("Created heap file: {} (oid={})", tablePath.toAbsolutePath(), oid);
        return new HeapFile(tablePath, oid, schema, null, nextPageId, metaPage, fsm, channel, null, Constants.BOOTSTRAP_XID);
    }

    /**
     * Creates a new per-table heap file with a meta page.
     *
     * @param tablePath path to the table file (e.g., dataDir/base/1/1000)
     * @param oid       the table OID
     * @param schema    the table schema
     * @param bufferPool the buffer pool for caching pages
     * @return a new HeapFile instance
     * @throws IOException if creation fails
     */
    public static HeapFile create(Path tablePath, int oid, Schema schema, BufferPool bufferPool) throws IOException {
        Path parent = tablePath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        // Create the file first (will be opened by BufferPool)
        FileChannel channel = FileChannel.open(tablePath,
                StandardOpenOption.READ, StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

        // Write meta page (page 0) directly
        Page metaPage = buildInitialMetaPage(oid);
        PageIO.writePage(channel, metaPage);
        channel.force(true);
        channel.close();

        // Now register with buffer pool
        bufferPool.registerFile(oid, tablePath);

        int nextPageId = 1; // Page 0 is meta, data pages start at 1

        // Create FSM file alongside heap file (initially 0 pages tracked)
        Path fsmPath = tablePath.getParent().resolve(tablePath.getFileName() + "_fsm");
        FreeSpaceMap fsm = FreeSpaceMap.create(fsmPath, 0);

        log.info("Created heap file: {} (oid={})", tablePath.toAbsolutePath(), oid);
        return new HeapFile(tablePath, oid, schema, bufferPool, nextPageId, metaPage, fsm, null, null, Constants.BOOTSTRAP_XID);
    }

    /**
     * Opens an existing per-table heap file and validates the meta page (for bootstrap use only).
     * This version does not use the buffer pool and reads directly from disk.
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
        Page metaPage = PageIO.readPage(channel, 0);
        int nextPageId = validateMetaPage(metaPage, tablePath, oid);

        // Open or create FSM file
        Path fsmPath = tablePath.getParent().resolve(tablePath.getFileName() + "_fsm");
        FreeSpaceMap fsm;
        if (Files.exists(fsmPath)) {
            fsm = FreeSpaceMap.open(fsmPath);
        } else {
            // FSM missing (e.g., deleted manually) — create fresh one
            log.warn("FSM file missing for oid={}, creating fresh: {}", oid, fsmPath);
            int dataPageCount = Math.max(0, nextPageId - 1); // Exclude meta page
            fsm = FreeSpaceMap.create(fsmPath, dataPageCount);
        }

        log.info("Opened heap file: {} (oid={}, pages={})", tablePath.toAbsolutePath(), oid, nextPageId);
        return new HeapFile(tablePath, oid, schema, null, nextPageId, metaPage, fsm, channel, null, Constants.BOOTSTRAP_XID);
    }

    /**
     * Opens an existing per-table heap file and validates the meta page.
     *
     * @param tablePath path to the table file
     * @param oid       expected table OID
     * @param schema    the table schema
     * @param bufferPool the buffer pool for caching pages
     * @return a HeapFile instance
     * @throws IOException         if file cannot be read
     * @throws CorruptionException if meta page validation fails
     */
    public static HeapFile open(Path tablePath, int oid, Schema schema, BufferPool bufferPool) throws IOException {
        if (!Files.exists(tablePath)) {
            throw new IOException("Heap file not found: " + tablePath);
        }

        // Register with buffer pool (opens the channel)
        bufferPool.registerFile(oid, tablePath);

        // Read and validate meta page via buffer pool
        BufferDescriptor metaFrame = bufferPool.fetchPage(oid, 0);
        Page metaPage = Page.Factory.wrap(0, metaFrame.page().duplicate());
        int nextPageId = validateMetaPage(metaPage, tablePath, oid);
        bufferPool.unpinPage(metaFrame, false);

        // Open or create FSM file
        Path fsmPath = tablePath.getParent().resolve(tablePath.getFileName() + "_fsm");
        FreeSpaceMap fsm;
        if (Files.exists(fsmPath)) {
            fsm = FreeSpaceMap.open(fsmPath);
        } else {
            // FSM missing (e.g., deleted manually) — create fresh one
            log.warn("FSM file missing for oid={}, creating fresh: {}", oid, fsmPath);
            int dataPageCount = Math.max(0, nextPageId - 1); // Exclude meta page
            fsm = FreeSpaceMap.create(fsmPath, dataPageCount);
        }

        log.info("Opened heap file: {} (oid={}, pages={})", tablePath.toAbsolutePath(), oid, nextPageId);
        return new HeapFile(tablePath, oid, schema, bufferPool, nextPageId, metaPage, fsm, null, null, Constants.BOOTSTRAP_XID);
    }

    /**
     * Creates a new per-table heap file with WAL support.
     *
     * @param tablePath path to the table file (e.g., dataDir/base/1/1002)
     * @param oid       the table OID
     * @param schema    the table schema
     * @param bufferPool the buffer pool for caching pages
     * @param xlogWriter the WAL writer for durability
     * @param xid       the transaction ID for WAL records
     * @return a new HeapFile instance
     * @throws IOException if creation fails
     */
    public static HeapFile create(Path tablePath, int oid, Schema schema, BufferPool bufferPool,
                                  XLogWriter xlogWriter, int xid) throws IOException {
        Path parent = tablePath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        FileChannel channel = FileChannel.open(tablePath,
                StandardOpenOption.READ, StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

        Page metaPage = buildInitialMetaPage(oid);
        PageIO.writePage(channel, metaPage);
        channel.force(true);
        channel.close();

        bufferPool.registerFile(oid, tablePath);

        int nextPageId = 1;

        Path fsmPath = tablePath.getParent().resolve(tablePath.getFileName() + "_fsm");
        FreeSpaceMap fsm = FreeSpaceMap.create(fsmPath, 0);

        log.info("Created heap file with WAL: {} (oid={})", tablePath.toAbsolutePath(), oid);
        return new HeapFile(tablePath, oid, schema, bufferPool, nextPageId, metaPage, fsm, null, xlogWriter, xid);
    }

    /**
     * Opens an existing per-table heap file with WAL support.
     * This is the main entry point for normal database operation.
     *
     * @param tablePath path to the table file
     * @param oid       expected table OID
     * @param schema    the table schema
     * @param bufferPool the buffer pool for caching pages
     * @param xlogWriter the WAL writer for durability
     * @param xid       the transaction ID for WAL records
     * @return a HeapFile instance
     * @throws IOException         if file cannot be read
     * @throws CorruptionException if meta page validation fails
     */
    public static HeapFile open(Path tablePath, int oid, Schema schema, BufferPool bufferPool,
                                XLogWriter xlogWriter, int xid) throws IOException {
        if (!Files.exists(tablePath)) {
            throw new IOException("Heap file not found: " + tablePath);
        }

        // Register with buffer pool (opens the channel)
        bufferPool.registerFile(oid, tablePath);

        // Read and validate meta page via buffer pool
        BufferDescriptor metaFrame = bufferPool.fetchPage(oid, 0);
        Page metaPage = Page.Factory.wrap(0, metaFrame.page().duplicate());
        int nextPageId = validateMetaPage(metaPage, tablePath, oid);
        bufferPool.unpinPage(metaFrame, false);

        // After crash recovery, the meta page's nextPageId may be stale
        // (updateMetaPage goes through the buffer pool, which may not have been flushed).
        // Use the actual file size as the authoritative page count.
        int pagesOnDisk = (int)(Files.size(tablePath) / Constants.PAGE_SIZE);
        nextPageId = Math.max(nextPageId, pagesOnDisk);

        // Open or create FSM file
        Path fsmPath = tablePath.getParent().resolve(tablePath.getFileName() + "_fsm");
        FreeSpaceMap fsm;
        if (Files.exists(fsmPath)) {
            fsm = FreeSpaceMap.open(fsmPath);
        } else {
            log.warn("FSM file missing for oid={}, creating fresh: {}", oid, fsmPath);
            int dataPageCount = Math.max(0, nextPageId - 1);
            fsm = FreeSpaceMap.create(fsmPath, dataPageCount);
        }

        log.info("Opened heap file with WAL: {} (oid={}, pages={})", tablePath.toAbsolutePath(), oid, nextPageId);
        return new HeapFile(tablePath, oid, schema, bufferPool, nextPageId, metaPage, fsm, null, xlogWriter, xid);
    }

    /**
     * Returns the table path (per-table mode only).
     */
    public Path tablePath() {
        return tablePath;
    }

    /**
     * Returns the table OID (per-table mode only).
     */
    public int oid() {
        return oid;
    }

    /**
     * Returns the next page ID (per-table mode only).
     */
    public int nextPageId() {
        return nextPageId;
    }

    public RecordId insert(Row row, int xmin) throws IOException {
        // 1. SERIALIZE: Convert Row → bytes using schema
        SerializedRow serialized = RowSerializer.serialize(row, schema);
        byte[] dataBytes = serialized.data();
        byte[] nullBitmap = serialized.nullBitmap();
        short natts = (short) schema.columnCount();

        // 2. CALCULATE: How much space we need on the page
        int tupleHeaderSize = HeapTupleHeader.computeHeaderSize(natts);
        int tupleSize = tupleHeaderSize + dataBytes.length;
        int spaceNeeded = tupleSize + ItemId.SIZE;

        // 3. FIND PAGE: Use FSM to find a page with enough space
        // FSM is a hint — it may be stale. We retry with correction if it lies.
        final int MAX_RETRIES = 3;
        for (int retry = 0; retry < MAX_RETRIES; retry++) {
            int pageId = fsm.requestPage(spaceNeeded);

            if (pageId == -1) {
                // No page with enough space according to FSM
                break;
            }

            // Verify the page actually has enough space (FSM is a hint)
            PinnedPage pinned = readPage(pageId);
            Page page = pinned.page();
            if (page.pageType() != PageType.HEAP) {
                // Zero out FSM entry so requestPage skips this non-HEAP page
                fsm.updatePage(pageId, 0);
                pinned.unpin(false);
                continue;
            }
            HeapPage heapPage = new HeapPage(page);
            int actualFree = heapPage.freeBytes();

            if (actualFree >= spaceNeeded) {
                // WAL-before-data: emit WAL first, then mutate, then mark dirty
                int slotNo = heapPage.slotCount(); // slot that will be assigned

                if (xlogWriter != null && pinned.frame() != null) {
                    byte[] walPayload = buildInsertWalPayload(slotNo, natts, nullBitmap, dataBytes);
                    appendWalWithFPW(
                        pinned.frame(),
                        page.buffer(),
                        XLogRecord.RMGR_HEAP,
                        HeapResourceManager.HEAP_INSERT,
                        pageId,
                        walPayload
                    );
                }

                RecordId rid = heapPage.insert(dataBytes, natts, nullBitmap, xmin);
                pinned.unpin(true); // Mark dirty since we modified the page
                fsm.updatePage(pageId, heapPage.freeBytes());
                return rid;
            }

            // FSM was stale (claimed more free space than actual)
            // Correct the FSM entry and retry
            int category = fsm.getCategory(pageId);
            log.debug("FSM stale for page {}: category {} (promised >= {} bytes), actual {} bytes",
                      pageId, category, category * 32, actualFree);
            fsm.updatePage(pageId, actualFree);
            pinned.unpin(false);
            // Continue to next retry iteration
        }

        // 4. ALLOCATE NEW PAGE: No existing page has room (or FSM kept lying)
        PinnedPage newPinned = allocateNewPage();
        Page newPage = newPinned.page();
        HeapPage heapPage = new HeapPage(newPage);
        int slotNo = heapPage.slotCount();

        // WAL-before-data: emit WAL first, then mutate, then mark dirty
        if (xlogWriter != null && newPinned.frame() != null) {
            byte[] walPayload = buildInsertWalPayload(slotNo, natts, nullBitmap, dataBytes);
            appendWalWithFPW(
                newPinned.frame(),
                newPage.buffer(),
                XLogRecord.RMGR_HEAP,
                HeapResourceManager.HEAP_INSERT,
                newPage.pageId(),
                walPayload
            );
        }

        RecordId rid = heapPage.insert(dataBytes, natts, nullBitmap, xmin);
        // Unpin the new page (dirty since we inserted data)
        newPinned.unpin(true);
        fsm.updatePage(newPage.pageId(), heapPage.freeBytes());
        return rid;
    }

    /**
     * Builds the WAL payload for a HEAP_INSERT record.
     * Format: (int slotNo, short natts, byte[] bitmap, byte[] tupleBytes)
     */
    private byte[] buildInsertWalPayload(int slotNo, short natts, byte[] nullBitmap, byte[] dataBytes) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        dos.writeInt(slotNo);
        dos.writeShort(natts);
        dos.write(nullBitmap);
        dos.write(dataBytes);
        dos.flush();
        return baos.toByteArray();
    }

    /**
     * Builds a full-page write WAL payload.
     * Format: (byte[8192] pageImage, byte[] originalPayload)
     *
     * <p>The page image is captured before mutation and is used to restore
     * the entire page during recovery in case of torn-page writes.</p>
     */
    private byte[] buildFullPageWritePayload(ByteBuffer pageBuffer, byte[] originalPayload) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);

        // Write the full page image (pre-mutation snapshot)
        byte[] pageImage = new byte[Constants.PAGE_SIZE];
        ByteBuffer dup = pageBuffer.duplicate();
        dup.clear();
        dup.get(pageImage);
        dos.write(pageImage);

        // Write the original payload
        dos.write(originalPayload);

        dos.flush();
        return baos.toByteArray();
    }

    /**
     * Appends a WAL record with full-page write support.
     *
     * <p>If the frame has needsFullPageWrite set, embeds the page image and
     * clears the flag. Otherwise, writes the original payload.</p>
     */
    private long appendWalWithFPW(com.coredb.buffer.BufferDescriptor frame, ByteBuffer pageBuffer,
                                   byte rmgr, byte info, int pageId, byte[] payload) throws IOException {
        if (xlogWriter == null) {
            return XLogWriter.INVALID_LSN;
        }

        byte[] walPayload;
        byte infoWithFlags = info;

        if (frame.needsFullPageWrite()) {
            // Embed full page image + original payload
            walPayload = buildFullPageWritePayload(pageBuffer, payload);
            infoWithFlags = (byte) (info | XLogRecord.XLOG_FPW);
            frame.clearNeedsFullPageWrite();
        } else {
            walPayload = payload;
        }

        long lsn = xlogWriter.append(
            rmgr,
            infoWithFlags,
            xid,
            oid,
            pageId,
            walPayload
        );

        frame.setPdLsn(lsn);
        return lsn;
    }

    public Optional<Row> get(RecordId recordId, Snapshot snapshot, ClogManager clog) throws IOException {
        return get(recordId, snapshot, clog, Constants.INVALID_XID);
    }

    public Optional<Row> get(RecordId recordId, Snapshot snapshot, ClogManager clog, int currentXid) throws IOException {
        if (recordId.pageId() < 1 || recordId.pageId() >= pageCount()) {
            return Optional.empty();
        }

        PinnedPage pinned = readPage(recordId.pageId());
        Page page = pinned.page();
        if (page.pageType() != PageType.HEAP) {
            pinned.unpin(false);
            return Optional.empty();
        }
        HeapPage heapPage = new HeapPage(page);

        try {
            Optional<byte[]> rawOpt = heapPage.get(recordId.slotNo(), snapshot, clog, currentXid);
            // Hint bits written to the page buffer must be flushed to disk eventually.
            pinned.unpin(heapPage.wasHintBitsModified());
            if (rawOpt.isEmpty()) {
                return Optional.empty();
            }
            byte[] raw = rawOpt.get();
            HeapTupleHeader header = HeapTupleHeader.readFrom(
                    ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN), 0);

            int hoff = header.hoff();
            byte[] data = new byte[raw.length - hoff];
            System.arraycopy(raw, hoff, data, 0, data.length);

            return Optional.of(RowSerializer.deserialize(data, schema, header));
        } catch (StorageException e) {
            log.debug("Failed to read row at {}: {}", recordId, e.getMessage());
            pinned.unpin(heapPage.wasHintBitsModified());
            return Optional.empty();
        }
    }

    /**
     * Updates the row at {@code rid} by creating a new tuple version on the same page.
     *
     * <p>Sets {@code xmax=xid} and {@code ctid} on the old tuple to chain it to the
     * new version. Emits a {@code HEAP_UPDATE} WAL record before mutating the page.</p>
     *
     * @param rid     RecordId of the existing tuple to supersede
     * @param newRow  the replacement row data
     * @param xid     transaction ID performing the update
     * @return RecordId of the newly inserted version
     * @throws PageFullException if the page has no room for the new version
     */
    public RecordId update(RecordId rid, Row newRow, int xid) throws IOException {
        if (rid.pageId() < 1 || rid.pageId() >= pageCount()) {
            throw new StorageException("page " + rid.pageId() + " does not exist");
        }

        SerializedRow serialized = RowSerializer.serialize(newRow, schema);
        byte[] dataBytes = serialized.data();
        byte[] nullBitmap = serialized.nullBitmap();
        short natts = (short) schema.columnCount();

        PinnedPage pinned = readPage(rid.pageId());
        Page page = pinned.page();
        if (page.pageType() != PageType.HEAP) {
            pinned.unpin(false);
            throw new StorageException("page " + rid.pageId() + " is not a heap page");
        }
        HeapPage heapPage = new HeapPage(page);

        // Validate old slot is live before emitting WAL — a stale WAL record
        // on a dead tuple would replay an invalid version chain during recovery.
        int oldRaw = page.buffer().getInt(PageHeader.SIZE + rid.slotNo() * ItemId.SIZE);
        if (ItemId.flags(oldRaw) != ItemId.FLAGS_NORMAL) {
            pinned.unpin(false);
            throw new StorageException("slot " + rid.slotNo() + " is not a live tuple");
        }
        int oldOffset = ItemId.offset(oldRaw);
        HeapTupleHeader existingHeader = HeapTupleHeader.readFrom(page.buffer(), oldOffset);
        if (existingHeader.xmax() != Constants.INVALID_XID) {
            pinned.unpin(false);
            throw new StorageException("slot " + rid.slotNo() + " is already deleted");
        }

        int headerSize = HeapTupleHeader.computeHeaderSize(natts);
        int tupleSize = headerSize + dataBytes.length;
        if (heapPage.freeBytes() < tupleSize + ItemId.SIZE) {
            pinned.unpin(false);
            throw new PageFullException("page " + rid.pageId() + " has no room for new tuple version");
        }

        int newSlotNo = heapPage.slotCount();

        if (xlogWriter != null && pinned.frame() != null) {
            byte[] walPayload = buildUpdateWalPayload(rid.slotNo(), newSlotNo, natts, nullBitmap, dataBytes);
            appendWalWithFPW(
                pinned.frame(),
                page.buffer(),
                XLogRecord.RMGR_HEAP,
                HeapResourceManager.HEAP_UPDATE,
                rid.pageId(),
                walPayload
            );
        }

        RecordId newRid = heapPage.update(rid.slotNo(), dataBytes, natts, nullBitmap, xid);
        pinned.unpin(true);
        fsm.updatePage(rid.pageId(), heapPage.freeBytes());
        return newRid;
    }

    private byte[] buildUpdateWalPayload(int oldSlotNo, int newSlotNo, short natts,
                                          byte[] nullBitmap, byte[] dataBytes) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        dos.writeInt(oldSlotNo);
        dos.writeInt(newSlotNo);
        dos.writeShort(natts);
        dos.write(nullBitmap);
        dos.write(dataBytes);
        dos.flush();
        return baos.toByteArray();
    }

    public void delete(RecordId rid, int xmax) throws IOException {
        if (rid.pageId() < 1 || rid.pageId() >= pageCount()) {
            throw new StorageException("page " + rid.pageId() + " does not exist");
        }
        PinnedPage pinned = readPage(rid.pageId());
        Page page = pinned.page();
        if (page.pageType() != PageType.HEAP) {
            pinned.unpin(false);
            throw new StorageException("page " + rid.pageId() + " is not a heap page");
        }
        HeapPage heapPage = new HeapPage(page);

        // WAL-before-data: emit WAL first, then mutate, then mark dirty
        if (xlogWriter != null && pinned.frame() != null) {
            byte[] walPayload = buildDeleteWalPayload(rid.slotNo());
            appendWalWithFPW(
                pinned.frame(),
                page.buffer(),
                XLogRecord.RMGR_HEAP,
                HeapResourceManager.HEAP_DELETE,
                rid.pageId(),
                walPayload
            );
        }

        heapPage.delete(rid.slotNo(), xmax);
        pinned.unpin(true); // Mark dirty since we modified the page

        // Update FSM to reflect freed space
        fsm.updatePage(rid.pageId(), heapPage.freeBytes());
    }

    /**
     * Builds the WAL payload for a HEAP_DELETE record.
     * Format: (int slotNo)
     */
    private byte[] buildDeleteWalPayload(int slotNo) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        dos.writeInt(slotNo);
        dos.flush();
        return baos.toByteArray();
    }

    public Iterator<Row> scan(Snapshot snapshot, ClogManager clog) throws IOException {
        return scan(snapshot, clog, Constants.INVALID_XID);
    }

    public Iterator<Row> scan(Snapshot snapshot, ClogManager clog, int currentXid) throws IOException {
        // Lazy iterator: one page in memory at a time for large scans
        return new LazyRowIterator(snapshot, clog, currentXid);
    }

    /**
     * Writes a compacted version of a heap page back to the buffer pool.
     *
     * <p>Ordering: emit {@code HEAP_VACUUM} WAL first (using the original page bytes for
     * any full-page write), then overwrite the frame buffer with {@code newPageBytes},
     * then mark the frame dirty. Callers must flush WAL for index changes before calling
     * this method.</p>
     *
     * @param pageId       page to rewrite (1-based, skip meta page 0)
     * @param newPageBytes compacted page bytes produced by {@link com.coredb.vacuum.PageCompactor}
     * @param deadSlots    slots that were removed (recorded in the WAL payload for redo)
     * @param xid          transaction ID to stamp on the WAL record
     * @throws IOException if the page cannot be read or written
     */
    public void vacuumPage(int pageId, byte[] newPageBytes, int[] deadSlots, int xid) throws IOException {
        PinnedPage pinned = readPage(pageId);
        Page page = pinned.page();
        if (page.pageType() != PageType.HEAP) {
            pinned.unpin(false);
            return;
        }

        if (xlogWriter != null && pinned.frame() != null) {
            byte[] walPayload = buildVacuumPayload(deadSlots);
            // appendWalWithFPW captures the ORIGINAL page bytes if a full-page write is needed,
            // then sets frame.pdLsn. We overwrite the frame buffer afterwards.
            long lsn = appendWalWithFPW(
                    pinned.frame(), page.buffer(),
                    XLogRecord.RMGR_HEAP, HeapResourceManager.HEAP_VACUUM, pageId, walPayload);
            // Copy compacted bytes into the frame (overwrites original content).
            ByteBuffer frameBuf = page.buffer();
            frameBuf.position(0);
            frameBuf.put(newPageBytes, 0, Constants.PAGE_SIZE);
            // Restore the LSN that appendWalWithFPW set, since we just overwrote it.
            pinned.frame().setPdLsn(lsn);
        } else {
            ByteBuffer frameBuf = page.buffer();
            frameBuf.position(0);
            frameBuf.put(newPageBytes, 0, Constants.PAGE_SIZE);
        }

        pinned.unpin(true);

        // Update FSM with the new free space count.
        ByteBuffer newBuf = ByteBuffer.wrap(newPageBytes).order(ByteOrder.BIG_ENDIAN);
        int pdLower = Short.toUnsignedInt(BinaryUtil.readU16(newBuf, PageHeader.OFFSET_PD_LOWER));
        int pdUpper = Short.toUnsignedInt(BinaryUtil.readU16(newBuf, PageHeader.OFFSET_PD_UPPER));
        fsm.updatePage(pageId, pdUpper - pdLower);
    }

    private byte[] buildVacuumPayload(int[] deadSlots) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        dos.writeInt(deadSlots.length);
        for (int slot : deadSlots) {
            dos.writeInt(slot);
        }
        dos.flush();
        return baos.toByteArray();
    }

    public int pageCount() {
        return nextPageId;
    }

    public FreeSpaceMap fsm() {
        return fsm;
    }

    /**
     * Counts live and dead tuples across all heap pages.
     *
     * @param oldestXmin horizon for dead-tuple determination
     * @param clog       transaction status lookup
     * @return two-element array: [liveCount, deadCount]
     */
    public long[] countTuples(int oldestXmin, ClogManager clog) throws IOException {
        long live = 0;
        long dead = 0;
        for (int pageId = 1; pageId < nextPageId; pageId++) {
            PinnedPage pinned = readPage(pageId);
            if (pinned.page().pageType() != PageType.HEAP) {
                pinned.unpin(false);
                continue;
            }
            ByteBuffer buf = pinned.page().buffer().duplicate().order(java.nio.ByteOrder.BIG_ENDIAN);
            int pdLower = Short.toUnsignedInt(BinaryUtil.readU16(buf, PageHeader.OFFSET_PD_LOWER));
            int slotCount = (pdLower - PageHeader.SIZE) / ItemId.SIZE;
            for (int slot = 0; slot < slotCount; slot++) {
                int rawItemId = buf.getInt(PageHeader.SIZE + slot * ItemId.SIZE);
                if (ItemId.flags(rawItemId) != ItemId.FLAGS_NORMAL) {
                    continue;
                }
                int offset = ItemId.offset(rawItemId);
                HeapTupleHeader header = HeapTupleHeader.readFrom(buf, offset);
                if (PageCompactor.isDead(header, oldestXmin, clog)) {
                    dead++;
                } else {
                    live++;
                }
            }
            pinned.unpin(false);
        }
        return new long[]{live, dead};
    }

    public void flush() throws IOException {
        fsm.flush();
        if (bufferPool != null) {
            // Flush all dirty pages for this file via buffer pool
            bufferPool.flushAllForFile(oid);
        } else if (channel != null) {
            // Bootstrap mode: fsync the channel directly
            channel.force(true);
        }
    }

    @Override
    public void close() throws IOException {
        // Close FSM first (it has its own file channel)
        fsm.close();

        if (bufferPool != null) {
            // Unregister from buffer pool (this flushes dirty pages and closes channel)
            bufferPool.unregisterFile(oid);
        } else if (channel != null) {
            // Bootstrap mode: close the channel directly
            channel.force(true);
            channel.close();
        }
    }

    // ==================== Per-table file helper methods ====================

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
     * Allocates a new page at the end of the file.
     * Updates the meta page to persist the new nextPageId.
     * Grows the FSM to track the new page if needed.
     * The returned PinnedPage must be unpinned by the caller after modifications.
     *
     * @return a PinnedPage containing the newly allocated HEAP page
     * @throws IOException if allocation fails
     */
    private PinnedPage allocateNewPage() throws IOException {
        int newPageId = nextPageId;

        PinnedPage pinned;
        if (bufferPool != null) {
            // Buffer pool mode: get new page from buffer pool
            BufferDescriptor newFrame = bufferPool.fetchNewPage(oid, newPageId);

            // Initialize frame with properly initialized heap page
            Page initializedPage = Page.Factory.allocateHeapPage(newPageId);
            newFrame.page().clear();
            newFrame.page().put(initializedPage.buffer().duplicate());
            newFrame.page().flip();

            Page newPage = Page.Factory.wrap(newPageId, newFrame.page());

            // Return pinned page - caller must unpin after modifying
            pinned = new PinnedPage(newPage, newFrame, bufferPool, null);
        } else {
            // Bootstrap mode: create page in memory, caller will write via unpin
            Page newPage = Page.Factory.allocateHeapPage(newPageId);
            pinned = new PinnedPage(newPage, null, null, channel);
        }

        // Update and persist nextPageId in meta page
        nextPageId = newPageId + 1;
        updateMetaPage();

        // Grow FSM if needed to track the new page
        // nextPageId - 1 = number of data pages (since page 0 is meta)
        int dataPageCount = nextPageId - 1;
        if (dataPageCount > fsm.trackedDataPageCount()) {
            fsm.grow(dataPageCount);
        }

        log.debug("Allocated page id={} in heap file oid={}", newPageId, oid);
        return pinned;
    }

    /**
     * Holder for a pinned page and its buffer descriptor.
     * Callers must call unpin(dirty) when done to release the frame.
     */
    public record PinnedPage(Page page, BufferDescriptor frame, BufferPool pool, FileChannel channel) {
        /**
         * Unpins this page. If frame is null (bootstrap mode), writes to disk if dirty.
         * @param dirty true if the page was modified
         */
        public void unpin(boolean dirty) {
            if (frame != null && pool != null) {
                pool.unpinPage(frame, dirty);
            } else if (dirty && channel != null) {
                // Bootstrap mode: write directly to disk and sync
                try {
                    PageIO.writePage(channel, page);
                    channel.force(true);
                } catch (java.io.IOException e) {
                    throw new RuntimeException("Failed to write page in bootstrap mode", e);
                }
            }
        }
    }

    /**
     * Reads a page from this heap file by page ID.
     * The returned page is backed by a buffer pool frame that remains pinned (buffer pool mode).
     * Callers MUST call unpin() on the returned PinnedPage when done.
     * In bootstrap mode, returns a standalone Page that doesn't need unpinning (frame will be null).
     *
     * @param pageId the page ID to read
     * @return PinnedPage containing the Page and its backing frame
     * @throws IOException if read fails or page doesn't exist
     */
    public PinnedPage readPage(int pageId) throws IOException {
        if (pageId < 1 || pageId >= nextPageId) {
            throw new StorageException("page " + pageId + " does not exist (allocated=" + nextPageId + ")");
        }
        if (bufferPool != null) {
            BufferDescriptor frame = bufferPool.fetchPage(oid, pageId);
            Page page = Page.Factory.wrap(pageId, frame.page());
            return new PinnedPage(page, frame, bufferPool, null);
        } else {
            // Bootstrap mode: read directly from channel, no frame to track
            Page page = PageIO.readPage(channel, pageId);
            return new PinnedPage(page, null, null, channel);
        }
    }

    /**
     * Writes a page to this heap file.
     * In buffer pool mode, the caller must unpin the frame with dirty=true.
     * In bootstrap mode, writes directly to disk.
     *
     * @param page the page to write
     * @throws IOException if write fails
     * @deprecated Use PinnedPage.unpin(dirty) instead
     */
    @Deprecated
    public void writePage(Page page) throws IOException {
        if (bufferPool == null && channel != null) {
            // Bootstrap mode: write directly to channel
            PageIO.writePage(channel, page);
        }
        // Buffer pool mode: caller should unpin the frame with dirty=true
    }

    /**
     * Returns the file size in bytes.
     *
     * @return file size
     * @throws IOException if size cannot be determined
     */
    public long fileSize() throws IOException {
        // Calculate from nextPageId (each page is PAGE_SIZE bytes)
        return (long) nextPageId * Constants.PAGE_SIZE;
    }

    /**
     * Updates the meta page (page 0) with the current nextPageId.
     * In buffer pool mode: fetched via buffer pool, modified, and unpinned dirty.
     * In bootstrap mode: write directly to channel.
     */
    private void updateMetaPage() throws IOException {
        if (bufferPool != null) {
            BufferDescriptor metaFrame = bufferPool.fetchPage(oid, 0);
            ByteBuffer buf = metaFrame.page();
            BinaryUtil.writeU32(buf, META_OFFSET_NEXT_PAGE_ID, nextPageId);
            bufferPool.unpinPage(metaFrame, true); // Mark dirty and unpin
        } else if (channel != null) {
            // Bootstrap mode: update via PageIO
            Page metaPage = PageIO.readPage(channel, 0);
            ByteBuffer buf = metaPage.buffer();
            BinaryUtil.writeU32(buf, META_OFFSET_NEXT_PAGE_ID, nextPageId);
            PageIO.writePage(channel, metaPage);
        }
    }

    /**
     * Builds the meta page (page 0) for a per-table heap file.
     */
    private static Page buildInitialMetaPage(int oid) {
        Page page = Page.Factory.allocateMetadataPage();
        ByteBuffer buf = page.buffer();
        BinaryUtil.writeU32(buf, META_OFFSET_MAGIC, Constants.HEAP_FILE_MAGIC);
        BinaryUtil.writeU32(buf, META_OFFSET_VERSION, META_FORMAT_VERSION);
        BinaryUtil.writeU32(buf, META_OFFSET_OID, oid);
        BinaryUtil.writeU32(buf, META_OFFSET_NEXT_PAGE_ID, 1); // Data pages start at 1
        return page;
    }

    /**
     * Writes a page to the file channel at the correct position.
     */

    // Iterates pages sequentially, yielding visible rows without materializing all into memory
    private final class LazyRowIterator implements Iterator<Row> {
        private final Snapshot snapshot;
        private final ClogManager clog;
        private final int currentXid;
        private int nextPageId = 1;
        private PinnedPage currentPinned = null;
        private Page currentPage = null;
        private HeapPage currentHeapPage = null;
        private Iterator<RecordId> currentPageRids = null;
        private Row nextRow = null;

        LazyRowIterator(Snapshot snapshot, ClogManager clog, int currentXid) throws IOException {
            this.snapshot = snapshot;
            this.clog = clog;
            this.currentXid = currentXid;
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
            while (nextPageId < HeapFile.this.pageCount() ||
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

                // Unpin previous page before loading the next one.
                if (currentPinned != null) {
                    currentPinned.unpin(currentHeapPage != null && currentHeapPage.wasHintBitsModified());
                    currentPinned = null;
                    currentPage = null;
                    currentHeapPage = null;
                }

                currentPinned = HeapFile.this.readPage(nextPageId);
                currentPage = currentPinned.page();
                nextPageId++;
                if (currentPage.pageType() != PageType.HEAP) {
                    currentPinned.unpin(false);
                    currentPinned = null;
                    currentPage = null;
                    currentHeapPage = null;
                    currentPageRids = null;
                    continue;
                }
                currentHeapPage = new HeapPage(currentPage);
                currentPageRids = currentHeapPage.scan(snapshot, clog, currentXid).iterator();
            }
            // End of iteration — unpin the last page.
            if (currentPinned != null) {
                currentPinned.unpin(currentHeapPage != null && currentHeapPage.wasHintBitsModified());
                currentPinned = null;
                currentPage = null;
                currentHeapPage = null;
            }
            nextRow = null;
        }

        private Optional<Row> fetchFromCurrentPage(RecordId recordId) {
            try {
                Optional<byte[]> rawOpt = currentHeapPage.get(recordId.slotNo(), snapshot, clog, currentXid);
                if (rawOpt.isEmpty()) {
                    return Optional.empty();
                }
                byte[] raw = rawOpt.get();
                HeapTupleHeader header = HeapTupleHeader.readFrom(
                        ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN), 0);
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
