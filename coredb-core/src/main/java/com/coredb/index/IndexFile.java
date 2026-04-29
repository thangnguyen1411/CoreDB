package com.coredb.index;

import com.coredb.buffer.BufferDescriptor;
import com.coredb.buffer.BufferPool;
import com.coredb.page.Page;
import com.coredb.page.PageIO;
import com.coredb.page.PageType;
import com.coredb.util.BinaryUtil;
import com.coredb.util.Constants;
import com.coredb.util.CorruptionException;
import com.coredb.util.StorageException;
import com.coredb.wal.XLogWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Manages a B+ tree index file for a single table.
 *
 * <p>File format (mirrors HeapFile's page-0 meta page):</p>
 * <pre>
 * Page 0: Index meta page
 *   bytes 0-3   magic         0x49445850 ("IDXP")
 *   bytes 4-7   format version (1)
 *   bytes 8-11  table OID
 *   bytes 12-15 root page id   (initially 1)
 *   bytes 16-19 tree height    (0 = root is leaf)
 *   bytes 20-23 next page id   (allocator cursor, starts at 2 after root)
 *
 * Pages 1..N: B+ tree pages (leaves + internals)
 * </pre>
 *
 * <p>The index uses append-only allocation; no FSM is needed because index pages
 * don't reuse space until VACUUM.</p>
 */
public final class IndexFile implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(IndexFile.class);

    // Meta page layout (page 0)
    private static final int META_OFFSET_MAGIC = 0;
    private static final int META_OFFSET_VERSION = 4;
    private static final int META_OFFSET_OID = 8;
    private static final int META_OFFSET_ROOT_PAGE_ID = 12;
    private static final int META_OFFSET_TREE_HEIGHT = 16;
    private static final int META_OFFSET_NEXT_PAGE_ID = 20;
    private static final int META_FORMAT_VERSION = 1;

    private final Path indexPath;
    private final int oid;
    private final BufferPool bufferPool;
    private final FileChannel channel; // Used for bootstrap/test mode when bufferPool is null
    private final XLogWriter xlogWriter; // May be null during early startup
    private final int xid; // Transaction ID for WAL records
    private Page metaPage;
    private int nextPageId;
    private int rootPageId;
    private int treeHeight;

    private IndexFile(Path indexPath, int oid, BufferPool bufferPool, Page metaPage,
                      int rootPageId, int treeHeight, int nextPageId, FileChannel channel,
                      XLogWriter xlogWriter, int xid) {
        this.indexPath = indexPath;
        this.oid = oid;
        this.bufferPool = bufferPool;
        this.channel = channel;
        this.xlogWriter = xlogWriter;
        this.xid = xid;
        this.metaPage = metaPage;
        this.rootPageId = rootPageId;
        this.treeHeight = treeHeight;
        this.nextPageId = nextPageId;
    }

    /**
     * Creates a new index file with an initial empty root leaf page (for bootstrap/test use).
     * This version does not use the buffer pool and writes directly to disk.
     *
     * @param indexPath path to the index file (e.g., dataDir/base/1/1002_pk)
     * @param oid       the table OID
     * @return a new IndexFile instance
     * @throws IOException if creation fails
     */
    public static IndexFile create(Path indexPath, int oid) throws IOException {
        Path parent = indexPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        FileChannel channel = FileChannel.open(indexPath,
                StandardOpenOption.READ, StandardOpenOption.WRITE,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

        // Create meta page (page 0)
        Page metaPage = buildInitialMetaPage(oid);
        PageIO.writePage(channel, metaPage);

        // Create initial root page as empty leaf (page 1)
        IndexPageLayout rootLayout = IndexPageLayout.createEmpty(1, PageType.INDEX_LEAF);
        PageIO.writePage(channel, rootLayout.page());

        int rootPageId = 1;
        int treeHeight = 0; // Root is a leaf
        int nextPageId = 2; // Next available page

        log.info("Created index file: {} (oid={})", indexPath.toAbsolutePath(), oid);
        return new IndexFile(indexPath, oid, null, metaPage, rootPageId, treeHeight, nextPageId, channel, null, Constants.BOOTSTRAP_XID);
    }

    /**
     * Creates a new index file with an initial empty root leaf page.
     *
     * @param indexPath path to the index file (e.g., dataDir/base/1/1002_pk)
     * @param oid       the table OID
     * @param bufferPool the buffer pool for caching pages
     * @return a new IndexFile instance
     * @throws IOException if creation fails
     */
    public static IndexFile create(Path indexPath, int oid, BufferPool bufferPool) throws IOException {
        Path parent = indexPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        // Create file directly first
        FileChannel channel = FileChannel.open(indexPath,
                StandardOpenOption.READ, StandardOpenOption.WRITE,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

        // Create meta page (page 0)
        Page metaPage = buildInitialMetaPage(oid);
        PageIO.writePage(channel, metaPage);

        // Create initial root page as empty leaf (page 1)
        IndexPageLayout rootLayout = IndexPageLayout.createEmpty(1, PageType.INDEX_LEAF);
        PageIO.writePage(channel, rootLayout.page());

        channel.force(true);
        channel.close();

        // Register with buffer pool
        bufferPool.registerFile(oid, indexPath);

        int rootPageId = 1;
        int treeHeight = 0; // Root is a leaf
        int nextPageId = 2; // Next available page

        log.info("Created index file: {} (oid={})", indexPath.toAbsolutePath(), oid);
        return new IndexFile(indexPath, oid, bufferPool, metaPage, rootPageId, treeHeight, nextPageId, null, null, Constants.BOOTSTRAP_XID);
    }

    /**
     * Opens an existing index file and validates the meta page (for bootstrap/test use).
     * This version does not use the buffer pool and reads directly from disk.
     *
     * @param indexPath path to the index file
     * @param oid       expected table OID
     * @return an IndexFile instance
     * @throws IOException         if file cannot be read
     * @throws CorruptionException if meta page validation fails
     */
    public static IndexFile open(Path indexPath, int oid) throws IOException {
        if (!Files.exists(indexPath)) {
            throw new IOException("Index file not found: " + indexPath);
        }

        FileChannel channel = FileChannel.open(indexPath,
                StandardOpenOption.READ, StandardOpenOption.WRITE);

        // Read and validate meta page
        Page metaPage = PageIO.readPage(channel, 0);
        MetaInfo metaInfo = validateMetaPage(metaPage, indexPath, oid);

        log.info("Opened index file: {} (oid={}, root={}, height={}, pages={})",
                indexPath.toAbsolutePath(), oid, metaInfo.rootPageId,
                metaInfo.treeHeight, metaInfo.nextPageId);

        return new IndexFile(indexPath, oid, null, metaPage,
                metaInfo.rootPageId, metaInfo.treeHeight, metaInfo.nextPageId, channel, null, Constants.BOOTSTRAP_XID);
    }

    /**
     * Opens an existing index file and validates the meta page.
     *
     * @param indexPath path to the index file
     * @param oid       expected table OID
     * @param bufferPool the buffer pool for caching pages
     * @return an IndexFile instance
     * @throws IOException         if file cannot be read
     * @throws CorruptionException if meta page validation fails
     */
    public static IndexFile open(Path indexPath, int oid, BufferPool bufferPool) throws IOException {
        if (!Files.exists(indexPath)) {
            throw new IOException("Index file not found: " + indexPath);
        }

        // Register with buffer pool
        bufferPool.registerFile(oid, indexPath);

        // Read and validate meta page via buffer pool
        BufferDescriptor metaFrame = bufferPool.fetchPage(oid, 0);
        Page metaPage = Page.Factory.wrap(0, metaFrame.page().duplicate());
        MetaInfo metaInfo = validateMetaPage(metaPage, indexPath, oid);
        bufferPool.unpinPage(metaFrame, false);

        log.info("Opened index file: {} (oid={}, root={}, height={}, pages={})",
                indexPath.toAbsolutePath(), oid, metaInfo.rootPageId,
                metaInfo.treeHeight, metaInfo.nextPageId);

        return new IndexFile(indexPath, oid, bufferPool, metaPage,
                metaInfo.rootPageId, metaInfo.treeHeight, metaInfo.nextPageId, null, null, Constants.BOOTSTRAP_XID);
    }

    /**
     * Opens an existing index file and recovers the file ID from the meta page.
     *
     * The meta page stores the OID that was used at creation time, so this
     * method recovers the correct file ID without requiring the caller to
     * know it. This avoids buffer pool key collisions between heap and index
     * files that share the same table OID.
     *
     * @param indexPath path to the index file
     * @param bufferPool the buffer pool for caching pages
     * @return an IndexFile instance
     * @throws IOException         if file cannot be read
     * @throws CorruptionException if meta page validation fails
     */
    public static IndexFile open(Path indexPath, BufferPool bufferPool) throws IOException {
        if (!Files.exists(indexPath)) {
            throw new IOException("Index file not found: " + indexPath);
        }

        // Read meta page directly to recover the stored file ID
        FileChannel probe = FileChannel.open(indexPath,
                StandardOpenOption.READ, StandardOpenOption.WRITE);
        Page metaPageProbe = PageIO.readPage(probe, 0);
        int storedOid = BinaryUtil.readU32(metaPageProbe.buffer(), META_OFFSET_OID);
        probe.close();

        // Use the stored OID as the file ID for buffer pool operations
        bufferPool.registerFile(storedOid, indexPath);

        BufferDescriptor metaFrame = bufferPool.fetchPage(storedOid, 0);
        Page metaPage = Page.Factory.wrap(0, metaFrame.page().duplicate());
        MetaInfo metaInfo = validateMetaPage(metaPage, indexPath, storedOid);
        bufferPool.unpinPage(metaFrame, false);

        log.info("Opened index file: {} (oid={}, root={}, height={}, pages={})",
                indexPath.toAbsolutePath(), storedOid, metaInfo.rootPageId,
                metaInfo.treeHeight, metaInfo.nextPageId);

        return new IndexFile(indexPath, storedOid, bufferPool, metaPage,
                metaInfo.rootPageId, metaInfo.treeHeight, metaInfo.nextPageId, null, null, Constants.BOOTSTRAP_XID);
    }

    // === Public accessors ===

    public Path indexPath() {
        return indexPath;
    }

    public int oid() {
        return oid;
    }

    public int rootPageId() {
        return rootPageId;
    }

    public int treeHeight() {
        return treeHeight;
    }

    public int nextPageId() {
        return nextPageId;
    }

    public int pageCount() {
        return nextPageId;
    }

    // === Page operations ===

    /**
     * Holder for a pinned page and its buffer descriptor.
     * Callers must call unpin(dirty) when done to release the frame.
     */
    public record PinnedPage(Page page, BufferDescriptor frame, BufferPool pool, java.nio.channels.FileChannel channel) {
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
     * Reads a page from the index file by page ID.
     * The returned page is backed by a buffer pool frame that remains pinned.
     * Callers MUST call unpin() on the returned PinnedPage when done.
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
            // Bootstrap/test mode: read directly from channel
            Page page = PageIO.readPage(channel, pageId);
            return new PinnedPage(page, null, null, channel);
        }
    }

    /**
     * Writes a page to the index file.
     * In buffer pool mode, caller should unpin with dirty=true.
     * In bootstrap mode, writes directly to disk.
     *
     * @param page the page to write
     * @throws IOException if write fails
     * @deprecated Use PinnedPage.unpin(dirty) instead
     */
    @Deprecated
    public void writePage(Page page) throws IOException {
        if (bufferPool == null && channel != null) {
            // Bootstrap/test mode: write directly to channel
            PageIO.writePage(channel, page);
        }
        // Buffer pool mode: caller should unpin the frame with dirty=true
    }

    /**
     * Allocates a new page at the end of the file.
     * Updates the meta page to persist the new nextPageId.
     * The returned PinnedPage must be unpinned by the caller when done.
     *
     * @param type the type of page to allocate (INDEX_LEAF or INDEX_INTERNAL)
     * @return a PinnedPage containing the newly allocated page
     * @throws IOException if allocation fails
     */
    public synchronized PinnedPage allocateNewPage(PageType type) throws IOException {
        int newPageId = nextPageId;

        if (bufferPool != null) {
            // Get new page from buffer pool
            BufferDescriptor newFrame = bufferPool.fetchNewPage(oid, newPageId);

            // Initialize the frame buffer with empty page structure
            IndexPageLayout layout = IndexPageLayout.createEmpty(newPageId, type);
            newFrame.page().clear();
            newFrame.page().put(layout.page().buffer().duplicate());
            newFrame.page().flip();

            // Return pinned page - caller must unpin when done
            Page page = Page.Factory.wrap(newPageId, newFrame.page());
            PinnedPage pinned = new PinnedPage(page, newFrame, bufferPool, null);

            // Update and persist nextPageId in meta page
            nextPageId = newPageId + 1;
            updateMetaPage();

            log.debug("Allocated page id={} in index file oid={}", newPageId, oid);
            return pinned;
        } else {
            // Bootstrap/test mode: write directly to channel
            IndexPageLayout layout = IndexPageLayout.createEmpty(newPageId, type);
            PageIO.writePage(channel, layout.page());

            // Update and persist nextPageId in meta page
            nextPageId = newPageId + 1;
            updateMetaPage();

            log.debug("Allocated page id={} in index file oid={}", newPageId, oid);
            return new PinnedPage(layout.page(), null, null, channel);
        }
    }

    /**
     * Updates the root page ID (called when root splits and tree grows).
     */
    public synchronized void setRootPageId(int newRootPageId) throws IOException {
        this.rootPageId = newRootPageId;
        this.treeHeight++;
        updateMetaPage();
    }

    /**
     * Atomic snapshot of (rootPageId, treeHeight) so a descender always observes
     * a consistent pair, even if the root grows under it.
     */
    public synchronized RootSnapshot rootSnapshot() {
        return new RootSnapshot(rootPageId, treeHeight);
    }

    public record RootSnapshot(int rootPageId, int treeHeight) {}

    // === File operations ===

    /**
     * Returns the file size in bytes.
     */
    public long fileSize() throws IOException {
        return (long) nextPageId * Constants.PAGE_SIZE;
    }

    public void flush() throws IOException {
        if (bufferPool != null) {
            // Flush all dirty pages for this table via buffer pool
            bufferPool.flushAllForFile(oid);
        } else if (channel != null) {
            // Bootstrap/test mode: fsync the channel directly
            channel.force(true);
        }
    }

    @Override
    public void close() throws IOException {
        if (bufferPool != null) {
            // Unregister from buffer pool (this flushes dirty pages and closes channel)
            bufferPool.unregisterFile(oid);
        } else if (channel != null) {
            // Bootstrap/test mode: close the channel directly
            channel.force(true);
            channel.close();
        }
    }

    // === Private helper methods ===

    private record MetaInfo(int rootPageId, int treeHeight, int nextPageId) {}

    private static MetaInfo validateMetaPage(Page metaPage, Path indexPath, int expectedOid) {
        ByteBuffer buf = metaPage.buffer();

        int magic = BinaryUtil.readU32(buf, META_OFFSET_MAGIC);
        if (magic != Constants.INDEX_FILE_MAGIC) {
            throw new CorruptionException(String.format(
                    "Index file magic mismatch: expected 0x%08X, got 0x%08X",
                    Constants.INDEX_FILE_MAGIC, magic));
        }

        int version = BinaryUtil.readU32(buf, META_OFFSET_VERSION);
        if (version != META_FORMAT_VERSION) {
            throw new CorruptionException(String.format(
                    "Index file version mismatch: expected %d, got %d",
                    META_FORMAT_VERSION, version));
        }

        int oid = BinaryUtil.readU32(buf, META_OFFSET_OID);
        if (oid != expectedOid) {
            throw new CorruptionException(String.format(
                    "Index file OID mismatch: expected %d, got %d", expectedOid, oid));
        }

        int rootPageId = BinaryUtil.readU32(buf, META_OFFSET_ROOT_PAGE_ID);
        int treeHeight = BinaryUtil.readU32(buf, META_OFFSET_TREE_HEIGHT);
        int nextPageId = BinaryUtil.readU32(buf, META_OFFSET_NEXT_PAGE_ID);

        return new MetaInfo(rootPageId, treeHeight, nextPageId);
    }

    private void updateMetaPage() throws IOException {
        if (bufferPool != null) {
            BufferDescriptor metaFrame = bufferPool.fetchPage(oid, 0);
            ByteBuffer buf = metaFrame.page();
            BinaryUtil.writeU32(buf, META_OFFSET_ROOT_PAGE_ID, rootPageId);
            BinaryUtil.writeU32(buf, META_OFFSET_TREE_HEIGHT, treeHeight);
            BinaryUtil.writeU32(buf, META_OFFSET_NEXT_PAGE_ID, nextPageId);
            bufferPool.unpinPage(metaFrame, true); // Mark dirty and unpin
        } else if (channel != null) {
            // Bootstrap/test mode: read-modify-write directly
            Page metaPage = PageIO.readPage(channel, 0);
            ByteBuffer buf = metaPage.buffer();
            BinaryUtil.writeU32(buf, META_OFFSET_ROOT_PAGE_ID, rootPageId);
            BinaryUtil.writeU32(buf, META_OFFSET_TREE_HEIGHT, treeHeight);
            BinaryUtil.writeU32(buf, META_OFFSET_NEXT_PAGE_ID, nextPageId);
            PageIO.writePage(channel, metaPage);
        }
    }

    private static Page buildInitialMetaPage(int oid) {
        Page page = Page.Factory.allocateMetadataPage();
        ByteBuffer buf = page.buffer();
        BinaryUtil.writeU32(buf, META_OFFSET_MAGIC, Constants.INDEX_FILE_MAGIC);
        BinaryUtil.writeU32(buf, META_OFFSET_VERSION, META_FORMAT_VERSION);
        BinaryUtil.writeU32(buf, META_OFFSET_OID, oid);
        BinaryUtil.writeU32(buf, META_OFFSET_ROOT_PAGE_ID, 1); // Root starts at page 1
        BinaryUtil.writeU32(buf, META_OFFSET_TREE_HEIGHT, 0);  // Root is initially a leaf
        BinaryUtil.writeU32(buf, META_OFFSET_NEXT_PAGE_ID, 2); // Pages 1 allocated, next is 2
        return page;
    }

}
