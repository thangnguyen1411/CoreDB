package com.coredb.index;

import com.coredb.heap.RecordId;
import com.coredb.page.Page;
import com.coredb.page.PageType;
import com.coredb.util.Constants;
import com.coredb.wal.BTreeResourceManager;
import com.coredb.wal.XLogRecord;
import com.coredb.wal.XLogWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.AbstractMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Collections;
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

    private BTree(IndexFile indexFile, XLogWriter xlogWriter, int xid) {
        this.indexFile = indexFile;
        this.xlogWriter = xlogWriter;
        this.xid = xid;
    }

    /**
     * Per-call descent path. Holds page IDs from root (index 0) down to the level
     * just above the leaf (index pathDepth-1). Held on the stack so concurrent
     * insert calls do not collide on a single shared array.
     */
    private static final class PathStack {
        final int[] stack = new int[MAX_HEIGHT];
        int depth;
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
        // Descend with shared latches; the returned leaf is pinned and read-locked.
        IndexFile.PinnedPage pinned = descendToLeafShared(key);
        try {
            // Lehman-Yao right-link follow: a concurrent split may have moved our key
            // to a sibling not yet linked from the parent. Walk the right-link chain
            // until the page actually owns this key range.
            while (true) {
                BTreeLeafPage leaf = BTreeLeafPage.of(IndexPageLayout.of(pinned.page()));
                if (leaf.keyBelongsHere(key)) {
                    return leaf.search(key);
                }
                int nextId = leaf.btpoNext();
                pinned = handOverShared(pinned, nextId);
            }
        } finally {
            unlockShared(pinned);
            pinned.unpin(false);
        }
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
            return Collections.emptyIterator();
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

            // Descend with shared latches; the leaf is pinned and shared-locked.
            // If anything below throws, release the latch+pin we are holding —
            // the caller has no reference to call close() on a half-built iterator.
            try {
                currentPinned = descendToLeafShared(from);
                while (true) {
                    BTreeLeafPage leaf = BTreeLeafPage.of(IndexPageLayout.of(currentPinned.page()));
                    if (leaf.keyBelongsHere(from)) {
                        this.currentLeaf = leaf;
                        break;
                    }
                    currentPinned = handOverShared(currentPinned, leaf.btpoNext());
                }
                this.currentSlot = currentLeaf.findFirstSlotGe(from);
                advance();
            } catch (RuntimeException | IOException e) {
                releaseCurrent();
                throw e;
            }
        }

        private void advance() throws IOException {
            while (true) {
                if (currentSlot < currentLeaf.entryCount()) {
                    long key = currentLeaf.keyAt(currentSlot);
                    if (key > to) {
                        releaseCurrent();
                        hasNext = false;
                        nextEntry = null;
                        return;
                    }
                    RecordId rid = currentLeaf.ridAt(currentSlot);
                    nextEntry = new AbstractMap.SimpleEntry<>(key, rid);
                    hasNext = true;
                    currentSlot++;
                    return;
                }

                int nextPageId = currentLeaf.btpoNext();
                if (nextPageId == 0) {
                    releaseCurrent();
                    hasNext = false;
                    nextEntry = null;
                    return;
                }

                currentPinned = handOverShared(currentPinned, nextPageId);
                currentLeaf = BTreeLeafPage.of(IndexPageLayout.of(currentPinned.page()));
                currentSlot = 0;
            }
        }

        private void releaseCurrent() {
            if (currentPinned != null) {
                unlockShared(currentPinned);
                currentPinned.unpin(false);
                currentPinned = null;
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
                releaseCurrent();
                throw new java.io.UncheckedIOException(e);
            } catch (RuntimeException e) {
                releaseCurrent();
                throw e;
            }
            return result;
        }

        @Override
        public void close() {
            if (!closed) {
                releaseCurrent();
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
        IndexFile.PinnedPage pinned = descendToLeafShared(key);
        unlockShared(pinned);
        lockExclusive(pinned);
        try {
            while (true) {
                BTreeLeafPage leaf = BTreeLeafPage.of(IndexPageLayout.of(pinned.page()));
                if (!leaf.keyBelongsHere(key)) {
                    int nextId = leaf.btpoNext();
                    IndexFile.PinnedPage next = indexFile.readPage(nextId);
                    lockExclusive(next);
                    unlockExclusive(pinned);
                    pinned.unpin(false);
                    pinned = next;
                    continue;
                }

                boolean deleted = leaf.delete(key);
                if (deleted) {
                    if (xlogWriter != null && pinned.frame() != null) {
                        byte[] payload = buildBtreeDeletePayload(key);
                        appendWalWithFPW(pinned.frame(), pinned.page().buffer(),
                                XLogRecord.RMGR_BTREE, BTreeResourceManager.BTREE_DELETE,
                                leaf.pageId(), payload);
                    }
                }
                unlockExclusive(pinned);
                pinned.unpin(deleted);
                pinned = null;
                return deleted;
            }
        } finally {
            if (pinned != null) {
                unlockExclusive(pinned);
                pinned.unpin(false);
            }
        }
    }

    /**
     * Scans all leaf pages and removes the entry whose heap RecordId matches {@code rid}.
     *
     * <p>This is O(total index entries) because the index is keyed on the PK, not on
     * RecordId. Used by VACUUM to clean up index entries for dead heap slots before
     * rewriting the page.</p>
     *
     * <p>Stops after the first match. This is correct for a unique PK index where each
     * RecordId appears at most once. Do not use for non-unique indexes.</p>
     *
     * @param rid the heap RecordId to remove
     * @return the PK key of the removed entry, or empty if not found
     * @throws IOException if a page operation fails
     */
    public Optional<Long> removeEntriesPointingAt(RecordId rid) throws IOException {
        IndexFile.PinnedPage pinned = descendToLeafShared(Long.MIN_VALUE);
        unlockShared(pinned);
        lockExclusive(pinned);

        try {
            while (true) {
                BTreeLeafPage leaf = BTreeLeafPage.of(IndexPageLayout.of(pinned.page()));
                int leafPageId = leaf.pageId();
                Optional<Long> deletedKey = leaf.deleteByRid(rid);

                if (deletedKey.isPresent()) {
                    if (xlogWriter != null && pinned.frame() != null) {
                        byte[] payload = buildBtreeDeletePayload(deletedKey.get());
                        appendWalWithFPW(pinned.frame(), pinned.page().buffer(),
                                XLogRecord.RMGR_BTREE, BTreeResourceManager.BTREE_DELETE,
                                leafPageId, payload);
                    }
                    unlockExclusive(pinned);
                    pinned.unpin(true);
                    pinned = null;
                    return deletedKey;
                }

                int nextLeaf = leaf.btpoNext();
                if (nextLeaf == 0) {
                    unlockExclusive(pinned);
                    pinned.unpin(false);
                    pinned = null;
                    return Optional.empty();
                }
                IndexFile.PinnedPage next = indexFile.readPage(nextLeaf);
                lockExclusive(next);
                unlockExclusive(pinned);
                pinned.unpin(false);
                pinned = next;
            }
        } finally {
            if (pinned != null) {
                unlockExclusive(pinned);
                pinned.unpin(false);
            }
        }
    }

    private byte[] buildBtreeDeletePayload(long key) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        dos.writeLong(key);
        dos.flush();
        return baos.toByteArray();
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
        PathStack path = new PathStack();

        // Descend with shared latches, recording the internal path for split propagation.
        IndexFile.PinnedPage pinned = descendToLeafShared(key, path);
        // Drop the shared leaf latch and re-acquire as exclusive. We do not upgrade
        // in place because rwlock upgrade is a deadlock hazard (two readers each
        // wanting to upgrade will block each other forever). After re-acquiring we
        // re-validate the right-link in case a concurrent split moved the key.
        unlockShared(pinned);
        lockExclusive(pinned);

        try {
            while (true) {
                BTreeLeafPage leaf = BTreeLeafPage.of(IndexPageLayout.of(pinned.page()));
                if (!leaf.keyBelongsHere(key)) {
                    int nextId = leaf.btpoNext();
                    IndexFile.PinnedPage next = indexFile.readPage(nextId);
                    lockExclusive(next);
                    unlockExclusive(pinned);
                    pinned.unpin(false);
                    pinned = next;
                    continue;
                }

                if (leaf.search(key).isPresent()) {
                    throw new IllegalStateException("Duplicate key: " + key);
                }

                if (leaf.freeBytes() < 18) {
                    // handleLeafSplit consumes pinned (unpins on its own paths) and
                    // is responsible for releasing the exclusive latch.
                    handleLeafSplit(leaf, pinned, key, rid, path);
                    pinned = null;
                    return;
                }

                int slotNo = leaf.layout().findInsertionPoint(key);
                if (xlogWriter != null && pinned.frame() != null) {
                    byte[] walPayload = buildBtreeInsertPayload(slotNo, key, rid);
                    appendWalWithFPW(
                        pinned.frame(),
                        pinned.page().buffer(),
                        XLogRecord.RMGR_BTREE,
                        BTreeResourceManager.BTREE_INSERT,
                        leaf.pageId(),
                        walPayload
                    );
                }
                InsertResult result = leaf.insert(key, rid);
                if (result != InsertResult.OK) {
                    throw new IllegalStateException("Unexpected insert result after pre-check: " + result);
                }
                unlockExclusive(pinned);
                pinned.unpin(true);
                pinned = null;
                return;
            }
        } finally {
            if (pinned != null) {
                unlockExclusive(pinned);
                pinned.unpin(false);
            }
        }
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
     * Builds a full-page write WAL payload.
     * Format: (byte[Constants.PAGE_SIZE] pageImage, byte[] originalPayload)
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
            indexFile.oid(),
            pageId,
            walPayload
        );

        frame.setPdLsn(lsn);
        return lsn;
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
    private void handleLeafSplit(BTreeLeafPage leaf, IndexFile.PinnedPage leafPinned, long key, RecordId rid, PathStack path) throws IOException {
        // Caller holds the exclusive content latch on leafPinned.
        int oldRightSibling = leaf.btpoNext();

        // The new right page is fresh: nobody can reach it until leaf.btpoNext is
        // updated (which happens inside leaf.split). The mutation runs under our
        // exclusive latch on the left, so the order is safe.
        SplitResult splitResult = leaf.split(indexFile);

        if (xlogWriter != null && leafPinned.frame() != null) {
            byte[] leftPayload = buildBtreeSplitPayload(splitResult.newRightPageId(), oldRightSibling, splitResult.separatorKey());
            appendWalWithFPW(
                leafPinned.frame(),
                leafPinned.page().buffer(),
                XLogRecord.RMGR_BTREE,
                BTreeResourceManager.BTREE_SPLIT,
                leaf.pageId(),
                leftPayload
            );
        }

        // Pin and exclusively latch the new right page for the rest of this routine.
        // We will need it for WAL pdLsn and possibly as the insertion target.
        IndexFile.PinnedPage rightPinned = indexFile.readPage(splitResult.newRightPageId());
        lockExclusive(rightPinned);
        boolean leafReleased = false;
        boolean rightReleased = false;
        try {
            if (xlogWriter != null && rightPinned.frame() != null) {
                byte[] rightPayload = buildBtreeSplitRightPayload(
                    BTreeLeafPage.of(IndexPageLayout.of(rightPinned.page())),
                    leaf.pageId(),
                    splitResult.separatorKey()
                );
                appendWalWithFPW(
                    rightPinned.frame(),
                    rightPinned.page().buffer(),
                    XLogRecord.RMGR_BTREE,
                    BTreeResourceManager.BTREE_SPLIT,
                    splitResult.newRightPageId(),
                    rightPayload
                );
            }

            int targetPageId;
            BTreeLeafPage targetPage;
            IndexFile.PinnedPage targetPinned;

            if (key < splitResult.separatorKey()) {
                targetPageId = leaf.pageId();
                targetPage = leaf;
                targetPinned = leafPinned;
            } else {
                targetPageId = splitResult.newRightPageId();
                targetPage = BTreeLeafPage.of(IndexPageLayout.of(rightPinned.page()));
                targetPinned = rightPinned;
            }

            int slotNo = targetPage.layout().findInsertionPoint(key);
            if (xlogWriter != null && targetPinned.frame() != null) {
                byte[] insertPayload = buildBtreeInsertPayload(slotNo, key, rid);
                appendWalWithFPW(
                    targetPinned.frame(),
                    targetPinned.page().buffer(),
                    XLogRecord.RMGR_BTREE,
                    BTreeResourceManager.BTREE_INSERT,
                    targetPageId,
                    insertPayload
                );
            }
            InsertResult insertResult = targetPage.insert(key, rid);
            if (insertResult != InsertResult.OK) {
                throw new IllegalStateException("Failed to insert into split page: " + insertResult);
            }

            // The right-link from leaf to right is now installed and the new
            // entry is in place. Release the leaf's exclusive latch before
            // walking up the tree — Lehman-Yao only requires that descendants
            // are linked before the parent is told about them.
            unlockExclusive(leafPinned);
            leafPinned.unpin(true);
            leafReleased = true;

            unlockExclusive(rightPinned);
            rightPinned.unpin(true);
            rightReleased = true;
        } finally {
            if (!rightReleased) {
                unlockExclusive(rightPinned);
                rightPinned.unpin(true);
            }
            if (!leafReleased) {
                unlockExclusive(leafPinned);
                leafPinned.unpin(true);
            }
        }
        // After this point neither leaf latch nor right latch is held.
        propagateSplit(splitResult.separatorKey(), splitResult.newRightPageId(), leaf.pageId(), path);
    }

    /**
     * Propagates a split up the tree, inserting the separator into parent pages.
     *
     * @param separatorKey the key that separates left and right children
     * @param rightPageId the new right child page ID
     * @param leftPageId the left child page ID (for root split detection)
     * @throws IOException if file operations fail
     */
    private void propagateSplit(long separatorKey, int rightPageId, int leftPageId, PathStack path) throws IOException {
        while (path.depth > 0) {
            path.depth--;
            int parentPageId = path.stack[path.depth];

            IndexFile.PinnedPage parentPinned = indexFile.readPage(parentPageId);
            lockExclusive(parentPinned);
            boolean parentReleased = false;
            try {
                Page parentPageData = parentPinned.page();
                BTreeInternalPage parent = BTreeInternalPage.of(IndexPageLayout.of(parentPageData));

                // 12 bytes internal entry + 4 bytes ItemId
                if (parent.freeBytes() >= 16) {
                    int slotNo = parent.layout().findInternalInsertionPoint(separatorKey);
                    if (xlogWriter != null && parentPinned.frame() != null) {
                        byte[] walPayload = buildBtreeInternalInsertPayload(slotNo, separatorKey, rightPageId);
                        appendWalWithFPW(
                            parentPinned.frame(),
                            parentPageData.buffer(),
                            XLogRecord.RMGR_BTREE,
                            BTreeResourceManager.BTREE_INTERNAL_INSERT,
                            parentPageId,
                            walPayload
                        );
                    }
                    InsertResult result = parent.insertSeparator(separatorKey, rightPageId);
                    if (result == InsertResult.DUPLICATE_KEY) {
                        throw new IllegalStateException("Duplicate separator key during split propagation: " + separatorKey);
                    }
                    if (result != InsertResult.OK) {
                        throw new IllegalStateException("Unexpected insert result after pre-check: " + result);
                    }
                    return;
                }

                // Parent is full, need to split it
                BTreeInternalPage.InternalSplitResult internalSplit = parent.split(indexFile);

                if (xlogWriter != null && parentPinned.frame() != null) {
                    byte[] leftPayload = buildBtreeInternalSplitPayload(internalSplit.rightPageId(), internalSplit.promotedKey());
                    appendWalWithFPW(
                        parentPinned.frame(),
                        parentPageData.buffer(),
                        XLogRecord.RMGR_BTREE,
                        BTreeResourceManager.BTREE_INTERNAL_SPLIT,
                        parentPageId,
                        leftPayload
                    );
                }

                long newSeparator = internalSplit.promotedKey();
                int newRightPageId = internalSplit.rightPageId();

                IndexFile.PinnedPage internalRightPinned = indexFile.readPage(newRightPageId);
                lockExclusive(internalRightPinned);
                try {
                    if (xlogWriter != null && internalRightPinned.frame() != null) {
                        byte[] rightPayload = buildBtreeInternalSplitPayload(parentPageId, internalSplit.promotedKey());
                        appendWalWithFPW(
                            internalRightPinned.frame(),
                            internalRightPinned.page().buffer(),
                            XLogRecord.RMGR_BTREE,
                            BTreeResourceManager.BTREE_INTERNAL_SPLIT,
                            newRightPageId,
                            rightPayload
                        );
                    }

                    if (separatorKey < newSeparator) {
                        int slotNo = parent.layout().findInternalInsertionPoint(separatorKey);
                        if (xlogWriter != null && parentPinned.frame() != null) {
                            byte[] walPayload = buildBtreeInternalInsertPayload(slotNo, separatorKey, rightPageId);
                            appendWalWithFPW(
                                parentPinned.frame(),
                                parentPageData.buffer(),
                                XLogRecord.RMGR_BTREE,
                                BTreeResourceManager.BTREE_INTERNAL_INSERT,
                                parentPageId,
                                walPayload
                            );
                        }
                        InsertResult insertResult = parent.insertSeparator(separatorKey, rightPageId);
                        if (insertResult != InsertResult.OK) {
                            throw new IllegalStateException("Failed to insert into split internal page: " + insertResult);
                        }
                    } else {
                        BTreeInternalPage rightPage = BTreeInternalPage.of(IndexPageLayout.of(internalRightPinned.page()));
                        int slotNo = rightPage.layout().findInternalInsertionPoint(separatorKey);
                        if (xlogWriter != null && internalRightPinned.frame() != null) {
                            byte[] walPayload = buildBtreeInternalInsertPayload(slotNo, separatorKey, rightPageId);
                            appendWalWithFPW(
                                internalRightPinned.frame(),
                                internalRightPinned.page().buffer(),
                                XLogRecord.RMGR_BTREE,
                                BTreeResourceManager.BTREE_INTERNAL_INSERT,
                                newRightPageId,
                                walPayload
                            );
                        }
                        InsertResult insertResult = rightPage.insertSeparator(separatorKey, rightPageId);
                        if (insertResult != InsertResult.OK) {
                            throw new IllegalStateException("Failed to insert into new internal page: " + insertResult);
                        }
                    }
                } finally {
                    unlockExclusive(internalRightPinned);
                    internalRightPinned.unpin(true);
                }

                // Release this parent and ascend, carrying the promoted key.
                unlockExclusive(parentPinned);
                parentPinned.unpin(true);
                parentReleased = true;

                separatorKey = newSeparator;
                rightPageId = newRightPageId;
                leftPageId = internalSplit.leftPageId();
            } finally {
                if (!parentReleased) {
                    unlockExclusive(parentPinned);
                    parentPinned.unpin(true);
                }
            }
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
            appendWalWithFPW(
                newRootPinned.frame(),
                newRootPage.buffer(),
                XLogRecord.RMGR_BTREE,
                BTreeResourceManager.BTREE_INTERNAL_INSERT,
                newRoot.pageId(),
                walPayload
            );
        }

        newRoot.initializeWithChildren(leftChild, separatorKey, rightChild);

        newRootPinned.unpin(true);

        indexFile.setRootPageId(newRoot.pageId());

        log.debug("Created new root page {} at level {}, tree height now {}",
                newRoot.pageId(), newLevel, treeHeight());
    }

    /**
     * Descends from root to the leaf for {@code key}, holding shared content latches
     * with crabbing: the parent latch is dropped only after the child is latched.
     * The returned PinnedPage is pinned and read-locked; caller must unlockShared
     * and unpin.
     */
    private IndexFile.PinnedPage descendToLeafShared(long key) throws IOException {
        IndexFile.RootSnapshot snap = indexFile.rootSnapshot();
        int currentPageId = snap.rootPageId();
        int height = snap.treeHeight();
        IndexFile.PinnedPage pinned = indexFile.readPage(currentPageId);
        lockShared(pinned);

        for (int level = height; level > 0; level--) {
            BTreeInternalPage internal =
                BTreeInternalPage.of(IndexPageLayout.of(pinned.page()));
            int childId = internal.routeChildFor(key);
            IndexFile.PinnedPage child = indexFile.readPage(childId);
            lockShared(child);
            unlockShared(pinned);
            pinned.unpin(false);
            pinned = child;
        }
        return pinned;
    }

    /**
     * Descent variant that records the internal path in {@code path} for split
     * propagation. Internal pages are visited under shared latches and dropped
     * before descending further (crab); the leaf is returned pinned and shared-locked.
     */
    private IndexFile.PinnedPage descendToLeafShared(long key, PathStack path) throws IOException {
        path.depth = 0;
        IndexFile.RootSnapshot snap = indexFile.rootSnapshot();
        int currentPageId = snap.rootPageId();
        int height = snap.treeHeight();
        IndexFile.PinnedPage pinned = indexFile.readPage(currentPageId);
        lockShared(pinned);

        for (int level = height; level > 0; level--) {
            if (path.depth >= MAX_HEIGHT) {
                unlockShared(pinned);
                pinned.unpin(false);
                throw new IllegalStateException("Tree height exceeds maximum: " + MAX_HEIGHT);
            }
            path.stack[path.depth++] = currentPageId;
            BTreeInternalPage internal =
                BTreeInternalPage.of(IndexPageLayout.of(pinned.page()));
            int childId = internal.routeChildFor(key);
            IndexFile.PinnedPage child = indexFile.readPage(childId);
            lockShared(child);
            unlockShared(pinned);
            pinned.unpin(false);
            pinned = child;
            currentPageId = childId;
        }
        return pinned;
    }

    // === Latch helpers ===
    // Bootstrap mode (frame == null) is single-threaded by construction, so we
    // skip latch operations there rather than allocating a no-op lock.

    private static void lockShared(IndexFile.PinnedPage p) {
        if (p.frame() != null) p.frame().lockShared();
    }

    private static void unlockShared(IndexFile.PinnedPage p) {
        if (p.frame() != null) p.frame().unlockShared();
    }

    private static void lockExclusive(IndexFile.PinnedPage p) {
        if (p.frame() != null) p.frame().lockExclusive();
    }

    private static void unlockExclusive(IndexFile.PinnedPage p) {
        if (p.frame() != null) p.frame().unlockExclusive();
    }

    /**
     * Releases the shared latch and pin on {@code current} and returns the next page
     * pinned and shared-locked. Used to walk the right-link chain.
     */
    private IndexFile.PinnedPage handOverShared(IndexFile.PinnedPage current, int nextPageId) throws IOException {
        IndexFile.PinnedPage next = indexFile.readPage(nextPageId);
        lockShared(next);
        unlockShared(current);
        current.unpin(false);
        return next;
    }
}
