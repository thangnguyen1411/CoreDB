package com.coredb.index;

import com.coredb.heap.RecordId;
import com.coredb.page.Page;
import com.coredb.page.PageType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    // Path tracking stack for descent and split propagation
    // pathStack[i] = pageId at level i during descent
    // Level 0 = leaf, Level height-1 = root
    private final int[] pathStack;
    private int pathDepth;

    private BTree(IndexFile indexFile) {
        this.indexFile = indexFile;
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
        return new BTree(indexFile);
    }

    /**
     * Opens an existing B+ tree from an index file.
     *
     * @param indexFile the index file containing the tree
     * @return a BTree instance for the existing tree
     */
    public static BTree open(IndexFile indexFile) {
        return new BTree(indexFile);
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

        // Try to insert into the leaf
        IndexFile.PinnedPage pinned = indexFile.readPage(leafPageId);
        Page page = pinned.page();
        BTreeLeafPage leaf = BTreeLeafPage.of(IndexPageLayout.of(page));

        InsertResult result = leaf.insert(key, rid);

        if (result == InsertResult.OK) {
            // Simple case: insert succeeded, unpin dirty
            pinned.unpin(true);
            return;
        }

        if (result == InsertResult.DUPLICATE_KEY) {
            pinned.unpin(false);
            throw new IllegalStateException("Duplicate key: " + key);
        }

        // result == InsertResult.FULL: need to split
        // Note: handleLeafSplit will unpin the page
        handleLeafSplit(leaf, pinned, key, rid);
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
        // Split the leaf page
        SplitResult splitResult = leaf.split(indexFile);

        // Determine which page to insert into
        int targetPageId;
        BTreeLeafPage targetPage;
        IndexFile.PinnedPage targetPinned = null;

        if (key < splitResult.separatorKey()) {
            // Insert into left (original) page
            targetPageId = leaf.pageId();
            targetPage = leaf;
            targetPinned = leafPinned; // Reuse the same pinned page
        } else {
            // Insert into right (new) page
            targetPageId = splitResult.newRightPageId();
            targetPinned = indexFile.readPage(targetPageId);
            Page rightPageData = targetPinned.page();
            targetPage = BTreeLeafPage.of(IndexPageLayout.of(rightPageData));
            // Unpin the original leaf since we're not using it
            leafPinned.unpin(true);
        }

        // Insert the key into the appropriate page
        InsertResult insertResult = targetPage.insert(key, rid);
        if (insertResult != InsertResult.OK) {
            // This should not happen - new page should have room
            targetPinned.unpin(true);
            throw new IllegalStateException("Failed to insert into split page: " + insertResult);
        }

        // Unpin the target page (dirty)
        targetPinned.unpin(true);

        // Propagate the split upward
        propagateSplit(
            splitResult.separatorKey(),
            splitResult.newRightPageId(),
            leaf.pageId()  // left page
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
        // Walk up the path stack, inserting separators into parents
        while (pathDepth > 0) {
            pathDepth--;
            int parentPageId = pathStack[pathDepth];

            IndexFile.PinnedPage parentPinned = indexFile.readPage(parentPageId);
            Page parentPageData = parentPinned.page();
            BTreeInternalPage parent = BTreeInternalPage.of(IndexPageLayout.of(parentPageData));

            // Try to insert the separator into the parent
            InsertResult result = parent.insertSeparator(separatorKey, rightPageId);

            if (result == InsertResult.OK) {
                // Parent had room, unpin dirty and we're done
                parentPinned.unpin(true);
                return;
            }

            if (result == InsertResult.DUPLICATE_KEY) {
                // This shouldn't happen during normal split propagation
                parentPinned.unpin(false);
                throw new IllegalStateException("Duplicate separator key during split propagation: " + separatorKey);
            }

            // Parent is full, need to split it
            BTreeInternalPage.InternalSplitResult internalSplit = parent.split(indexFile);

            // Determine which child gets the new separator
            // The separator key tells us: keys < separator go left, keys >= go right
            long newSeparator = internalSplit.promotedKey();
            int newRightPageId = internalSplit.rightPageId();

            // The new separator we were trying to insert
            // Compare with promoted key to decide which page gets it
            if (separatorKey < newSeparator) {
                // Insert into left page (original)
                BTreeInternalPage leftPage = parent;
                InsertResult insertResult = leftPage.insertSeparator(separatorKey, rightPageId);
                if (insertResult != InsertResult.OK) {
                    parentPinned.unpin(true);
                    throw new IllegalStateException("Failed to insert into split internal page: " + insertResult);
                }
                // Unpin left page (original parent, modified)
                parentPinned.unpin(true);
            } else {
                // Insert into right page (new)
                IndexFile.PinnedPage rightPinned = indexFile.readPage(newRightPageId);
                Page rightPageData = rightPinned.page();
                BTreeInternalPage rightPage = BTreeInternalPage.of(IndexPageLayout.of(rightPageData));
                InsertResult insertResult = rightPage.insertSeparator(separatorKey, rightPageId);
                if (insertResult != InsertResult.OK) {
                    rightPinned.unpin(true);
                    parentPinned.unpin(true);
                    throw new IllegalStateException("Failed to insert into new internal page: " + insertResult);
                }
                // Unpin both pages
                rightPinned.unpin(true);
                parentPinned.unpin(true);
            }

            // Continue propagating with the promoted key
            separatorKey = newSeparator;
            rightPageId = newRightPageId;
            leftPageId = internalSplit.leftPageId();
            // Loop continues to next parent level
        }

        // If we exit the loop, we've propagated all the way to above the root
        // This means the root split and we need a new root
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
        // Allocate a new internal page at the new level
        int newLevel = treeHeight() + 1;
        IndexFile.PinnedPage newRootPinned = indexFile.allocateNewPage(PageType.INDEX_INTERNAL);
        Page newRootPage = newRootPinned.page();
        BTreeInternalPage newRoot = BTreeInternalPage.of(IndexPageLayout.of(newRootPage));
        newRoot.setBtpoLevel(newLevel);

        // Initialize with the two children
        newRoot.initializeWithChildren(leftChild, separatorKey, rightChild);

        // Unpin the new root (dirty) - modifications are in the buffer pool frame
        newRootPinned.unpin(true);

        // Update the index file metadata
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
