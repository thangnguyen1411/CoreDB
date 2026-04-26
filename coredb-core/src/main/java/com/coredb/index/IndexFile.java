package com.coredb.index;

import com.coredb.page.Page;
import com.coredb.page.PageIO;
import com.coredb.page.PageType;
import com.coredb.util.BinaryUtil;
import com.coredb.util.Constants;
import com.coredb.util.CorruptionException;
import com.coredb.util.StorageException;
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
    private final FileChannel channel;
    private Page metaPage;
    private int nextPageId;
    private int rootPageId;
    private int treeHeight;

    private IndexFile(Path indexPath, int oid, FileChannel channel, Page metaPage,
                      int rootPageId, int treeHeight, int nextPageId) {
        this.indexPath = indexPath;
        this.oid = oid;
        this.channel = channel;
        this.metaPage = metaPage;
        this.rootPageId = rootPageId;
        this.treeHeight = treeHeight;
        this.nextPageId = nextPageId;
    }

    /**
     * Creates a new index file with an initial empty root leaf page.
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
        return new IndexFile(indexPath, oid, channel, metaPage, rootPageId, treeHeight, nextPageId);
    }

    /**
     * Opens an existing index file and validates the meta page.
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

        return new IndexFile(indexPath, oid, channel, metaPage,
                metaInfo.rootPageId, metaInfo.treeHeight, metaInfo.nextPageId);
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
     * Reads a page from the index file by page ID.
     *
     * @param pageId the page ID to read
     * @return the Page at that ID
     * @throws IOException if read fails or page doesn't exist
     */
    public Page readPage(int pageId) throws IOException {
        if (pageId < 1 || pageId >= nextPageId) {
            throw new StorageException("page " + pageId + " does not exist (allocated=" + nextPageId + ")");
        }
        return PageIO.readPage(channel, pageId);
    }

    /**
     * Writes a page to the index file.
     *
     * @param page the page to write
     * @throws IOException if write fails
     */
    public void writePage(Page page) throws IOException {
        PageIO.writePage(channel, page);
    }

    /**
     * Allocates a new page at the end of the file.
     * Updates the meta page to persist the new nextPageId.
     *
     * @param type the type of page to allocate (INDEX_LEAF or INDEX_INTERNAL)
     * @return a new page with the freshly allocated pageId
     * @throws IOException if allocation fails
     */
    public Page allocateNewPage(PageType type) throws IOException {
        int newPageId = nextPageId;

        Page newPage;
        if (type == PageType.INDEX_LEAF) {
            newPage = IndexPageLayout.createEmpty(newPageId, type).page();
        } else {
            // For internal pages, allocate and caller will set level via IndexPageLayout
            newPage = Page.Factory.allocate(newPageId, type);
        }

        PageIO.writePage(channel, newPage);

        // Update and persist nextPageId in meta page
        nextPageId = newPageId + 1;
        updateMetaPage();

        log.debug("Allocated page id={} in index file oid={}", newPageId, oid);
        return newPage;
    }

    /**
     * Updates the root page ID (called when root splits and tree grows).
     */
    public void setRootPageId(int newRootPageId) throws IOException {
        this.rootPageId = newRootPageId;
        this.treeHeight++;
        updateMetaPage();
    }

    // === File operations ===

    /**
     * Returns the file size in bytes.
     */
    public long fileSize() throws IOException {
        return channel.size();
    }

    @Override
    public void close() throws IOException {
        updateMetaPage();
        if (channel != null) {
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
        ByteBuffer buf = metaPage.buffer();
        BinaryUtil.writeU32(buf, META_OFFSET_ROOT_PAGE_ID, rootPageId);
        BinaryUtil.writeU32(buf, META_OFFSET_TREE_HEIGHT, treeHeight);
        BinaryUtil.writeU32(buf, META_OFFSET_NEXT_PAGE_ID, nextPageId);
        PageIO.writePage(channel, metaPage);
        channel.force(true);
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
