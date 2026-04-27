package com.coredb.index;

import com.coredb.heap.RecordId;
import com.coredb.page.Page;
import com.coredb.page.PageType;
import com.coredb.wal.BTreeResourceManager;
import com.coredb.wal.XLogRecord;
import com.coredb.wal.XLogWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.AbstractMap;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * B+ tree index structure.
 *
 * <p>Ties together leaf pages, internal pages, and the index file to provide
 * a complete B+ tree implementation with:
 * <ul>
 *   <li>Descent from root to leaf for search and insert</li>
 *   <li>Split propagation up the tree</li>
 *   <li>Root split handling (tree height growth)</li>
 *   <li>Path tracking using an int[] stack (no parent pointers in pages)</li>
 * </ul>
 *
 * <p>The B+ tree uses the standard PostgreSQL pattern:
 * <ul>
 *   <li>All leaf pages are linked via btpo_prev/btpo_next</li>
 *   <li>Internal pages route searches using separator keys</li>
 *   <li>Splits propagate upward; root split creates new root and increases height</li>
 *   <li>The descent path is tracked on a stack; parent pointers are not stored</li>
 * </ul>
 */
public final class BTree {

    private static final Logger log = LoggerFactory.getLogger(BTree.class);

    // Maximum tree height for path stack (generous limit)
    private static final int MAX_HEIGHT = 32;

    private final IndexFile indexFile;
    private final XLogWriter xlogWriter; // May be null during early startup
    private final int xid; // Transaction ID for WAL records

    // Path tracking stack for descent and split propagation
    // pathStack[i] = pageId at level i during descent
    // Level 0 = leaf, Level height-1 = root
    private final int[] pathStack;
    private int pathDepth;

    private BTree(IndexFile indexFile, XLogWriter xlogWriter, int xid) {
        this.indexFile = indexFile;
        this.xlogWriter = xlogWriter;
        this.xid = xid;
        this.pathStack = new int[MAX_HEIGHT];
        this.pathDepth = 0;
    }

    /**
     * Creates a new B+ tree with an empty root leaf page.
     *
     * <p>The IndexFile must already be created with an initial root page.</p>
     *
     * @param indexFile the index file containing the tree
     * @return a new BTree instance
     */
    public static BTree create(IndexFile indexFile) {
        return new BTree(indexFile, null, com.coredb.util.Constants.BOOTSTRAP_XID);
    }

    public static BTree open(IndexFile indexFile) {
        return new BTree(indexFile, null, com.coredb.util.Constants.BOOTSTRAP_XID);
    }

    public static BTree create(IndexFile indexFile, XLogWriter xlogWriter, int xid) {
        return new BTree(indexFile, xlogWriter, xid);
    }

    public static BTree open(IndexFile indexFile, XLogWriter xlogWriter, int xid) {
        return new BTree(indexFile, xlogWriter, xid);
    }

    /**
     * Returns the index file associated with this tree.
     */
    public IndexFile indexFile() {
        return indexFile;
    }

    /**
     * Returns the current tree height.
     * 0 = root is a leaf, 1 = one internal level above leaves, etc.
     */
    public int treeHeight() {
        return indexFile.treeHeight();
    }

    /**
     * Returns the root page ID.
     */
    public int rootPageId() {
        return indexFile.rootPageId();
    }

    /**
     * Searches for a key in the B+ tree.
     *
     * @param key the key to search for
     * @return Optional containing the RecordId if found, empty otherwise
     * @throws IOException if page read fails
     */
    public Optional<RecordId> search(long key) throws IOException {
        // Descend to the appropriate leaf page
        int leafPageId = descendToLeaf(key);

        // Search in the leaf
        IndexFile.PinnedPage pinned = indexFile.readPage(leafPageId);
        Page page = pinned.page();
        BTreeLeafPage leaf = BTreeLeafPage.of(IndexPageLayout.of(page));
        Optional<RecordId> result = leaf.search(key);
        pinned.unpin(false);
        return result;
    }

    /**
     * Performs a range scan over the B+ tree, returning all keys in [from, to].
     *
     * <p>This implementation uses the leaf chain (btpo_next pointers) to traverse
     * leaves horizontally without re-descending the tree. This is the standard
     * PostgreSQL approach for range scans.</p>
     *
     * <p>The iterator is lazy: it only loads the next leaf page when needed.</p>
     *
     * @param from the starting key (inclusive)
     * @param to the ending key (inclusive)
     * @return an iterator over (key, RecordId) pairs in sorted order
     * @throws IOException if page read fails
     */
    public Iterator<Map.Entry<Long, RecordId>> rangeScan(long from, long to) throws IOException {
        if (from > to) {
            return java.util.Collections.emptyIterator();
        }

        return new RangeScanIterator(from, to);
    }

    /**
     * Iterator implementation for range scans using the leaf chain.
     *
     * <p>Algorithm:
     * <ol>
     *   <li>Descend once to find the leaf containing `from`</li>
     *   <li>Within that leaf, find first slot with key >= from</li>
     *   <li>Iterate forward through entries</li>
     *   <li>When falling off the end of a leaf, follow btpo_next</li>
     *   <li>Stop when key > to or btpo_next == 0</li>
     * </ol>
     */
    private class RangeScanIterator implements Iterator<Map.Entry<Long, RecordId>>, AutoCloseable {
        private final long to;
        private BTreeLeafPage currentLeaf;
        private int currentSlot;
        private Map.Entry<Long, RecordId> nextEntry;
        private boolean hasNext;

        private IndexFile.PinnedPage currentPinned;
        private boolean closed = false;

        RangeScanIterator(long from, long to) throws IOException {
            this.to = to;
            this.currentLeaf = null;
            this.currentSlot = 0;
            this.nextEntry = null;
            this.hasNext = false;
            this.currentPinned = null;
            this.closed = false;

            // Descend to the leaf containing 'from'
            int leafPageId = descendToLeaf(from);
            currentPinned = indexFile.readPage(leafPageId);
            Page page = currentPinned.page();
            this.currentLeaf = BTreeLeafPage.of(IndexPageLayout.of(page));

            // Find first slot with key >= from
            this.currentSlot = currentLeaf.findFirstSlotGe(from);

            // Pre-fetch first entry
            advance();
        }

        private void advance() throws IOException {
            while (true) {
                // Check if we have more entries on current leaf
                if (currentSlot < currentLeaf.entryCount()) {
                    long key = currentLeaf.keyAt(currentSlot);
                    if (key > to) {
                        // Past our range, we're done - unpin the last page
                        if (currentPinned != null) {
                            currentPinned.unpin(false);
                            currentPinned = null;
                        }
                        hasNext = false;
                        nextEntry = null;
                        return;
                    }
                    // Found next entry in range
                    RecordId rid = currentLeaf.ridAt(currentSlot);
                    nextEntry = new AbstractMap.SimpleEntry<>(key, rid);
                    hasNext = true;
                    currentSlot++;
                    return;
                }

                // Need to move to next leaf
                int nextPageId = currentLeaf.btpoNext();
                if (nextPageId == 0) {
                    // No more leaves, we're done - unpin the last page
                    if (currentPinned != null) {
                        currentPinned.unpin(false);
                        currentPinned = null;
                    }
                    hasNext = false;
                    nextEntry = null;
                    return;
                }

                // Unpin previous leaf before loading next
                if (currentPinned != null) {
                    currentPinned.unpin(false);
                }

                // Load next leaf
                currentPinned = indexFile.readPage(nextPageId);
                Page nextPage = currentPinned.page();
                currentLeaf = BTreeLeafPage.of(IndexPageLayout.of(nextPage));
                currentSlot = 0;
                // Continue loop to check this new leaf
            }
        }

        @Override
        public boolean hasNext() {
            return hasNext;
        }

        @Override
        public Map.Entry<Long, RecordId> next() {
            if (!hasNext) {
                throw new NoSuchElementException();
            }
            Map.Entry<Long, RecordId> result = nextEntry;
            try {
                advance();
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
            return result;
        }

        @Override
        public void close() {
            if (!closed && currentPinned != null) {
                currentPinned.unpin(false);
                currentPinned = null;
            }
            closed = true;
        }
    }

    /**
     * Deletes a key from the B+ tree.
     *
     * <p>This removes the entry from the appropriate leaf page. The operation:
     * <ul>
     *   <li>Descends from root to the leaf containing the key</li>
     *   <li>Removes the entry from the leaf's ItemId array</li>
     *   <li>Writes the modified page back</li>
     * </ul>
     *
     * <p>This implementation matches PostgreSQL's behavior:
     * <ul>
     *   <li>No parent adjustment (separators may become stale - this is acceptable)</li>
     *   <li>No sibling redistribution</li>
     *   <li>No page merge on delete</li>
     *   <li>Empty leaves remain in the chain</li>
     * </ul>
     *
     * <p>Page recycling is deferred to VACUUM.</p>
     *
     * @param key the key to delete
     * @return true if the key was found and deleted, false if not found
     * @throws IOException if page operations fail
     */
    public boolean delete(long key) throws IOException {
        // Descend to the appropriate leaf page
        int leafPageId = descendToLeaf(key);

        // Delete from the leaf
        IndexFile.PinnedPage pinned = indexFile.readPage(leafPageId);
        Page page = pinned.page();
        BTreeLeafPage leaf = BTreeLeafPage.of(IndexPageLayout.of(page));

        boolean deleted = leaf.delete(key);

        if (deleted) {
            // Mark page dirty and unpin
            pinned.unpin(true);
        } else {
            pinned.unpin(false);
        }

        return deleted;
    }

    /**
     * Inserts a (key, RecordId) pair into the B+ tree.
     *
     * <p>If the key already exists, throws IllegalStateException.
     * If a leaf splits, the split propagates up the tree.
     * If the root splits, a new root is created and tree height increases.</p>
     *
     * @param key the key to insert
     * @param rid the RecordId associated with the key
     * @throws IOException if file operations fail
     * @throws IllegalStateException if key already exists
     */
    public void insert(long key, RecordId rid) throws IOException {
        // Descend to leaf, tracking the path
        int leafPageId = descendToLeafWithPath(key);

        IndexFile.PinnedPage pinned = indexFile.readPage(leafPageId);
        Page page = pinned.page();
        BTreeLeafPage leaf = BTreeLeafPage.of(IndexPageLayout.of(page));

        // Pre-check duplicate
        if (leaf.search(key).isPresent()) {
            pinned.unpin(false);
            throw new IllegalStateException("Duplicate key: " + key);
        }

        // Pre-check full: 14 bytes leaf entry + 4 bytes ItemId
        if (leaf.freeBytes() < 18) {
            handleLeafSplit(leaf, pinned, key, rid);
            return;
        }

        // WAL-before-data: emit record before mutating the page
        int slotNo = leaf.layout().findInsertionPoint(key);
        if (xlogWriter != null && pinned.frame() != null) {
            byte[] walPayload = buildBtreeInsertPayload(slotNo, key, rid);
            long lsn = xlogWriter.append(
                XLogRecord.RMGR_BTREE,
                BTreeResourceManager.BTREE_INSERT,
                xid,
                indexFile.oid(),
                leafPageId,
                walPayload
            );
            pinned.frame().setPdLsn(lsn);
        }
        InsertResult result = leaf.insert(key, rid);
        if (result != InsertResult.OK) {
            pinned.unpin(false);
            throw new IllegalStateException("Unexpected insert result after pre-check: " + result);
        }
        pinned.unpin(true);
    }

    /**
     * Builds the WAL payload for a BTREE_INSERT record.
     * Format: (int slotNo, long key, int ridPageId, short ridSlotNo)
     */
    private byte[] buildBtreeInsertPayload(int slotNo, long key, RecordId rid) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        dos.writeInt(slotNo);
        dos.writeLong(key);
        dos.writeInt(rid.pageId());
        dos.writeShort((short) rid.slotNo());
        dos.flush();
        return baos.toByteArray();
    }

    /**
     * Builds the WAL payload for a BTREE_SPLIT record on the left (original) page.
     * Format: (int newRightPageId, int oldRightSibling, long separatorKey)
     */
    private byte[] buildBtreeSplitPayload(int newRightPageId, int oldRightSibling, long separatorKey) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        dos.writeInt(newRightPageId);
        dos.writeInt(oldRightSibling);
        dos.writeLong(separatorKey);
        dos.flush();
        return baos.toByteArray();
    }

    /**
     * Builds the WAL payload for a BTREE_SPLIT record on the right (new) page.
     * Format: (int leftPageId, int unused, long separatorKey, int entryCount, entries...)
     */
    private byte[] buildBtreeSplitRightPayload(BTreeLeafPage rightPage, int leftPageId, long separatorKey) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        dos.writeInt(leftPageId);
        dos.writeInt(0); // unused
        dos.writeLong(separatorKey);
        int count = rightPage.entryCount();
        dos.writeInt(count);
        for (int i = 0; i < count; i++) {
            dos.writeLong(rightPage.keyAt(i));
            RecordId rid = rightPage.ridAt(i);
            dos.writeInt(rid.pageId());
            dos.writeShort((short) rid.slotNo());
        }
        dos.flush();
        return baos.toByteArray();
    }

    /**
     * Builds the WAL payload for a BTREE_INTERNAL_INSERT record.
     * Format: (int slotNo, long key, int childPageId)
     */
    private byte[] buildBtreeInternalInsertPayload(int slotNo, long key, int childPageId) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        dos.writeInt(slotNo);
        dos.writeLong(key);
        dos.writeInt(childPageId);
        dos.flush();
        return baos.toByteArray();
    }

    /**
     * Builds the WAL payload for a BTREE_INTERNAL_SPLIT record.
     * Format: (int newRightPageId, long promotedKey)
     */
    private byte[] buildBtreeInternalSplitPayload(int newRightPageId, long promotedKey) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        dos.writeInt(newRightPageId);
        dos.writeLong(promotedKey);
        dos.flush();
        return baos.toByteArray();
    }

    /**
     * Handles a leaf page split and propagates the split upward.
     *
     * @param leaf the full leaf page that needs splitting
     * @param leafPinned the pinned page for the leaf (will be unpinned by this method)
     * @param key the key to insert
     * @param rid the RecordId to insert
     * @throws IOException if file operations fail
     */
    private void handleLeafSplit(BTreeLeafPage leaf, IndexFile.PinnedPage leafPinned, long key, RecordId rid) throws IOException {
        // Capture old right sibling before split changes sibling pointers
        int oldRightSibling = leaf.btpoNext();

        // Split the leaf page (mutates left and right pages in memory)
        SplitResult splitResult = leaf.split(indexFile);

        // WAL-before-data for the left (original) page
        if (xlogWriter != null && leafPinned.frame() != null) {
            byte[] leftPayload = buildBtreeSplitPayload(splitResult.newRightPageId(), oldRightSibling, splitResult.separatorKey());
            long leftLsn = xlogWriter.append(
                XLogRecord.RMGR_BTREE,
                BTreeResourceManager.BTREE_SPLIT,
                xid,
                indexFile.oid(),
                leaf.pageId(),
                leftPayload
            );
            leafPinned.frame().setPdLsn(leftLsn);
        }

        // WAL-before-data for the right (new) page: re-fetch to set pdLsn
        if (xlogWriter != null) {
            IndexFile.PinnedPage rightRefetch = indexFile.readPage(splitResult.newRightPageId());
            if (rightRefetch.frame() != null) {
                byte[] rightPayload = buildBtreeSplitRightPayload(
                    BTreeLeafPage.of(IndexPageLayout.of(rightRefetch.page())),
                    leaf.pageId(),
                    splitResult.separatorKey()
                );
                long rightLsn = xlogWriter.append(
                    XLogRecord.RMGR_BTREE,
                    BTreeResourceManager.BTREE_SPLIT,
                    xid,
                    indexFile.oid(),
                    splitResult.newRightPageId(),
                    rightPayload
                );
                rightRefetch.frame().setPdLsn(rightLsn);
            }
            rightRefetch.unpin(false); // already dirty from split
        }

        // Determine which page to insert into
        int targetPageId;
        BTreeLeafPage targetPage;
        IndexFile.PinnedPage targetPinned;

        if (key < splitResult.separatorKey()) {
            targetPageId = leaf.pageId();
            targetPage = leaf;
            targetPinned = leafPinned;
        } else {
            targetPageId = splitResult.newRightPageId();
            targetPinned = indexFile.readPage(targetPageId);
            Page rightPageData = targetPinned.page();
            targetPage = BTreeLeafPage.of(IndexPageLayout.of(rightPageData));
            leafPinned.unpin(true);
        }

        // WAL-before-data: emit BTREE_INSERT for the target page before inserting
        int slotNo = targetPage.layout().findInsertionPoint(key);
        if (xlogWriter != null && targetPinned.frame() != null) {
            byte[] insertPayload = buildBtreeInsertPayload(slotNo, key, rid);
            long insertLsn = xlogWriter.append(
                XLogRecord.RMGR_BTREE,
                BTreeResourceManager.BTREE_INSERT,
                xid,
                indexFile.oid(),
                targetPageId,
                insertPayload
            );
            targetPinned.frame().setPdLsn(insertLsn);
        }
        InsertResult insertResult = targetPage.insert(key, rid);
        if (insertResult != InsertResult.OK) {
            targetPinned.unpin(true);
            throw new IllegalStateException("Failed to insert into split page: " + insertResult);
        }
        targetPinned.unpin(true);

        // Propagate the split upward
        propagateSplit(
            splitResult.separatorKey(),
            splitResult.newRightPageId(),
            leaf.pageId()
        );
    }

    /**
     * Propagates a split up the tree, inserting the separator into parent pages.
     *
     * @param separatorKey the key that separates left and right children
     * @param rightPageId the new right child page ID
     * @param leftPageId the left child page ID (for root split detection)
     * @throws IOException if file operations fail
     */
    private void propagateSplit(long separatorKey, int rightPageId, int leftPageId) throws IOException {
        while (pathDepth > 0) {
            pathDepth--;
            int parentPageId = pathStack[pathDepth];

            IndexFile.PinnedPage parentPinned = indexFile.readPage(parentPageId);
            Page parentPageData = parentPinned.page();
            BTreeInternalPage parent = BTreeInternalPage.of(IndexPageLayout.of(parentPageData));

            // 12 bytes internal entry + 4 bytes ItemId
            if (parent.freeBytes() >= 16) {
                // Parent has room: WAL-before-data, then insert
                int slotNo = parent.layout().findInternalInsertionPoint(separatorKey);
                if (xlogWriter != null && parentPinned.frame() != null) {
                    byte[] walPayload = buildBtreeInternalInsertPayload(slotNo, separatorKey, rightPageId);
                    long lsn = xlogWriter.append(
                        XLogRecord.RMGR_BTREE,
                        BTreeResourceManager.BTREE_INTERNAL_INSERT,
                        xid,
                        indexFile.oid(),
                        parentPageId,
                        walPayload
                    );
                    parentPinned.frame().setPdLsn(lsn);
                }
                InsertResult result = parent.insertSeparator(separatorKey, rightPageId);
                if (result == InsertResult.DUPLICATE_KEY) {
                    parentPinned.unpin(false);
                    throw new IllegalStateException("Duplicate separator key during split propagation: " + separatorKey);
                }
                if (result != InsertResult.OK) {
                    parentPinned.unpin(false);
                    throw new IllegalStateException("Unexpected insert result after pre-check: " + result);
                }
                parentPinned.unpin(true);
                return;
            }

            // Parent is full, need to split it
            BTreeInternalPage.InternalSplitResult internalSplit = parent.split(indexFile);

            // WAL-before-data for the left (original) parent page
            if (xlogWriter != null && parentPinned.frame() != null) {
                byte[] leftPayload = buildBtreeInternalSplitPayload(internalSplit.rightPageId(), internalSplit.promotedKey());
                long leftLsn = xlogWriter.append(
                    XLogRecord.RMGR_BTREE,
                    BTreeResourceManager.BTREE_INTERNAL_SPLIT,
                    xid,
                    indexFile.oid(),
                    parentPageId,
                    leftPayload
                );
                parentPinned.frame().setPdLsn(leftLsn);
            }

            // WAL-before-data for the right (new) internal page
            if (xlogWriter != null) {
                IndexFile.PinnedPage rightRefetch = indexFile.readPage(internalSplit.rightPageId());
                if (rightRefetch.frame() != null) {
                    byte[] rightPayload = buildBtreeInternalSplitPayload(parentPageId, internalSplit.promotedKey());
                    long rightLsn = xlogWriter.append(
                        XLogRecord.RMGR_BTREE,
                        BTreeResourceManager.BTREE_INTERNAL_SPLIT,
                        xid,
                        indexFile.oid(),
                        internalSplit.rightPageId(),
                        rightPayload
                    );
                    rightRefetch.frame().setPdLsn(rightLsn);
                }
                rightRefetch.unpin(false); // already dirty from split
            }

            long newSeparator = internalSplit.promotedKey();
            int newRightPageId = internalSplit.rightPageId();

            if (separatorKey < newSeparator) {
                // Insert into left page (original parent): WAL-before-data
                int slotNo = parent.layout().findInternalInsertionPoint(separatorKey);
                if (xlogWriter != null && parentPinned.frame() != null) {
                    byte[] walPayload = buildBtreeInternalInsertPayload(slotNo, separatorKey, rightPageId);
                    long lsn = xlogWriter.append(
                        XLogRecord.RMGR_BTREE,
                        BTreeResourceManager.BTREE_INTERNAL_INSERT,
                        xid,
                        indexFile.oid(),
                        parentPageId,
                        walPayload
                    );
                    parentPinned.frame().setPdLsn(lsn);
                }
                InsertResult insertResult = parent.insertSeparator(separatorKey, rightPageId);
                if (insertResult != InsertResult.OK) {
                    parentPinned.unpin(true);
                    throw new IllegalStateException("Failed to insert into split internal page: " + insertResult);
                }
                parentPinned.unpin(true);
            } else {
                // Insert into right page (new): WAL-before-data
                IndexFile.PinnedPage rightPinned = indexFile.readPage(newRightPageId);
                Page rightPageData = rightPinned.page();
                BTreeInternalPage rightPage = BTreeInternalPage.of(IndexPageLayout.of(rightPageData));
                int slotNo = rightPage.layout().findInternalInsertionPoint(separatorKey);
                if (xlogWriter != null && rightPinned.frame() != null) {
                    byte[] walPayload = buildBtreeInternalInsertPayload(slotNo, separatorKey, rightPageId);
                    long lsn = xlogWriter.append(
                        XLogRecord.RMGR_BTREE,
                        BTreeResourceManager.BTREE_INTERNAL_INSERT,
                        xid,
                        indexFile.oid(),
                        newRightPageId,
                        walPayload
                    );
                    rightPinned.frame().setPdLsn(lsn);
                }
                InsertResult insertResult = rightPage.insertSeparator(separatorKey, rightPageId);
                if (insertResult != InsertResult.OK) {
                    rightPinned.unpin(true);
                    parentPinned.unpin(true);
                    throw new IllegalStateException("Failed to insert into new internal page: " + insertResult);
                }
                rightPinned.unpin(true);
                parentPinned.unpin(true);
            }

            separatorKey = newSeparator;
            rightPageId = newRightPageId;
            leftPageId = internalSplit.leftPageId();
        }

        createNewRoot(separatorKey, leftPageId, rightPageId);
    }

    /**
     * Creates a new root page when the old root splits.
     *
     * @param separatorKey the separator key between the two children
     * @param leftChild the left child page ID (old root)
     * @param rightChild the right child page ID (new sibling from split)
     * @throws IOException if file operations fail
     */
    private void createNewRoot(long separatorKey, int leftChild, int rightChild) throws IOException {
        int newLevel = treeHeight() + 1;
        IndexFile.PinnedPage newRootPinned = indexFile.allocateNewPage(PageType.INDEX_INTERNAL);
        Page newRootPage = newRootPinned.page();
        BTreeInternalPage newRoot = BTreeInternalPage.of(IndexPageLayout.of(newRootPage));
        newRoot.setBtpoLevel(newLevel);

        // WAL-before-data: emit record before populating the new root
        if (xlogWriter != null && newRootPinned.frame() != null) {
            byte[] walPayload = buildBtreeInternalInsertPayload(0, separatorKey, rightChild);
            long lsn = xlogWriter.append(
                XLogRecord.RMGR_BTREE,
                BTreeResourceManager.BTREE_INTERNAL_INSERT,
                xid,
                indexFile.oid(),
                newRoot.pageId(),
                walPayload
            );
            newRootPinned.frame().setPdLsn(lsn);
        }

        newRoot.initializeWithChildren(leftChild, separatorKey, rightChild);

        newRootPinned.unpin(true);

        indexFile.setRootPageId(newRoot.pageId());

        log.debug("Created new root page {} at level {}, tree height now {}",
                newRoot.pageId(), newLevel, treeHeight());
    }

    /**
     * Descends from root to the appropriate leaf page for the given key.
     *
     * @param key the search key
     * @return the leaf page ID where the key belongs
     * @throws IOException if page read fails
     */
    private int descendToLeaf(long key) throws IOException {
        int currentPageId = rootPageId();
        int currentHeight = treeHeight();

        // Descend through internal levels
        for (int level = currentHeight; level > 0; level--) {
            IndexFile.PinnedPage pinned = indexFile.readPage(currentPageId);
            Page page = pinned.page();
            BTreeInternalPage internal = BTreeInternalPage.of(IndexPageLayout.of(page));
            currentPageId = internal.routeChildFor(key);
            pinned.unpin(false); // Internal pages are read-only during descent
        }

        return currentPageId;
    }

    /**
     * Descends from root to leaf, tracking the path on the stack for split propagation.
     *
     * @param key the search key
     * @return the leaf page ID where the key belongs
     * @throws IOException if page read fails
     */
    private int descendToLeafWithPath(long key) throws IOException {
        pathDepth = 0;
        int currentPageId = rootPageId();
        int currentHeight = treeHeight();

        // Descend through internal levels, tracking the path
        for (int level = currentHeight; level > 0; level--) {
            // Push current page onto path stack
            if (pathDepth >= MAX_HEIGHT) {
                throw new IllegalStateException("Tree height exceeds maximum: " + MAX_HEIGHT);
            }
            pathStack[pathDepth++] = currentPageId;

            IndexFile.PinnedPage pinned = indexFile.readPage(currentPageId);
            Page page = pinned.page();
            BTreeInternalPage internal = BTreeInternalPage.of(IndexPageLayout.of(page));
            currentPageId = internal.routeChildFor(key);
            pinned.unpin(false); // Internal pages are read-only during descent
        }

        return currentPageId;
    }
}
