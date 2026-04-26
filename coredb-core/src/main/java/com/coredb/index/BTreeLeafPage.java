package com.coredb.index;

import com.coredb.heap.RecordId;
import com.coredb.page.Page;
import com.coredb.page.PageType;

import java.util.Optional;

/**
 * B-tree leaf page operations.
 *
 * <p>Wraps an {@link IndexPageLayout} to provide high-level leaf page operations:
 * <ul>
 *   <li>Binary search for keys</li>
 *   <li>Sorted insertion (not appending - inserts at correct position)</li>
 *   <li>Entry accessors by slot position</li>
 * </ul>
 *
 * <p>This class does NOT handle splits - it returns {@link InsertResult#FULL}
 * when there is no room, leaving split handling to the caller.</p>
 *
 * <p>Each leaf entry stores a (key, RecordId) pair in 14 bytes:
 * <ul>
 *   <li>key: 8 bytes (long)</li>
 *   <li>RecordId: 6 bytes (pageId: 4, slotNo: 2)</li>
 * </ul>
 */
public final class BTreeLeafPage {

    private final IndexPageLayout layout;

    private BTreeLeafPage(IndexPageLayout layout) {
        this.layout = layout;
    }

    /**
     * Creates a new empty leaf page.
     *
     * @param pageId the page ID
     * @return a new empty BTreeLeafPage
     */
    public static BTreeLeafPage createEmpty(int pageId) {
        IndexPageLayout layout = IndexPageLayout.createEmpty(pageId, PageType.INDEX_LEAF);
        return new BTreeLeafPage(layout);
    }

    /**
     * Wraps an existing IndexPageLayout as a leaf page.
     *
     * @param layout the existing layout
     * @return a BTreeLeafPage wrapping the layout
     */
    public static BTreeLeafPage of(IndexPageLayout layout) {
        return new BTreeLeafPage(layout);
    }

    /**
     * Returns the underlying IndexPageLayout.
     */
    public IndexPageLayout layout() {
        return layout;
    }

    /**
     * Returns the page ID.
     */
    public int pageId() {
        return layout.pageId();
    }

    /**
     * Returns the number of entries on this page.
     */
    public int entryCount() {
        return layout.entryCount();
    }

    /**
     * Returns the key at the given slot.
     *
     * @param slot the slot index (0-based)
     * @return the key at that slot
     * @throws IndexOutOfBoundsException if slot is out of range
     */
    public long keyAt(int slot) {
        return layout.readLeafEntry(slot).key();
    }

    /**
     * Returns the RecordId at the given slot.
     *
     * @param slot the slot index (0-based)
     * @return the RecordId at that slot
     * @throws IndexOutOfBoundsException if slot is out of range
     */
    public RecordId ridAt(int slot) {
        return layout.readLeafEntry(slot).rid();
    }

    /**
     * Searches for a key in the leaf page.
     *
     * @param key the key to search for
     * @return Optional containing the RecordId if found, empty otherwise
     */
    public Optional<RecordId> search(long key) {
        int slot = layout.searchKey(key);
        if (slot >= 0) {
            return Optional.of(layout.readLeafEntry(slot).rid());
        }
        return Optional.empty();
    }

    /**
     * Finds the first slot where key >= searchKey.
     * This is the starting point for range scans.
     *
     * @param searchKey the key to search for
     * @return slot index (0..entryCount) where all entries before have keys < searchKey
     *         and entries at or after have keys >= searchKey. Returns entryCount if
     *         all keys are less than searchKey.
     */
    public int findFirstSlotGe(long searchKey) {
        return layout.findInsertionPoint(searchKey);
    }

    /**
     * Inserts a (key, RecordId) pair into the leaf page at the correct sorted position.
     *
     * <p>If the key already exists, returns {@link InsertResult#DUPLICATE_KEY}.
     * If there is not enough space for the insertion, returns {@link InsertResult#FULL}.
     * Otherwise inserts at the correct sorted position and returns {@link InsertResult#OK}.</p>
     *
     * <p>When inserting, all entries after the insertion point are shifted right.
     * This maintains the sorted order of the ItemId array.</p>
     *
     * @param key the key to insert
     * @param rid the RecordId associated with the key
     * @return OK if inserted, FULL if no room, DUPLICATE_KEY if key exists
     */
    public InsertResult insert(long key, RecordId rid) {
        // Check if key already exists
        int existingSlot = layout.searchKey(key);
        if (existingSlot >= 0) {
            return InsertResult.DUPLICATE_KEY;
        }

        // Calculate space needed: 14 bytes for tuple + 4 bytes for ItemId
        int spaceNeeded = 14 + 4;
        if (layout.freeBytes() < spaceNeeded) {
            return InsertResult.FULL;
        }

        // Find insertion point
        int insertionPoint = layout.findInsertionPoint(key);

        // Perform sorted insertion by shifting ItemIds
        insertAt(insertionPoint, key, rid);

        return InsertResult.OK;
    }

    /**
     * Performs the actual insertion at a specific slot, shifting existing entries.
     *
     * @param slot the slot to insert at
     * @param key the key to insert
     * @param rid the RecordId to insert
     */
    private void insertAt(int slot, long key, RecordId rid) {
        int count = layout.entryCount();

        // First, shift all ItemIds from slot to count-1 right by one position
        // We do this in reverse order to avoid overwriting
        for (int i = count - 1; i >= slot; i--) {
            int itemId = layout.readItemId(i);
            layout.writeItemId(i + 1, itemId);
        }

        // Update pd_lower to reflect the new ItemId slot
        short newLower = (short) (layout.pdLower() + 4); // 4 bytes per ItemId
        layout.setPdLower(newLower);

        // Now write the actual tuple data at pd_upper - tupleSize
        // and create the ItemId at the correct slot
        byte[] tuple = new byte[14];
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(tuple);
        buf.putLong(0, key);
        buf.putInt(8, rid.pageId());
        buf.putShort(12, (short) rid.slotNo());

        // Calculate position for tuple data
        short newUpper = (short) (layout.pdUpper() - 14);

        // Write tuple data
        java.nio.ByteBuffer pageBuf = layout.page().buffer();
        pageBuf.position(newUpper);
        pageBuf.put(tuple);

        // Create and write ItemId
        int itemId = com.coredb.page.ItemId.pack(newUpper, com.coredb.page.ItemId.FLAGS_NORMAL, 14);
        layout.writeItemId(slot, itemId);

        // Update pd_upper
        layout.setPdUpper(newUpper);
    }

    /**
     * Returns the free bytes available on this page.
     */
    public int freeBytes() {
        return layout.freeBytes();
    }

    /**
     * Returns true if this page is a leaf (btpo_level == 0).
     */
    public boolean isLeaf() {
        return layout.isLeaf();
    }

    /**
     * Returns the left sibling page ID (btpo_prev).
     */
    public int btpoPrev() {
        return layout.btpoPrev();
    }

    /**
     * Returns the right sibling page ID (btpo_next).
     */
    public int btpoNext() {
        return layout.btpoNext();
    }

    /**
     * Sets the left sibling page ID.
     */
    public void setBtpoPrev(int prev) {
        layout.setBtpoPrev(prev);
    }

    /**
     * Sets the right sibling page ID.
     */
    public void setBtpoNext(int next) {
        layout.setBtpoNext(next);
    }

    /**
     * Splits this leaf page into two, allocating a new right sibling.
     *
     * <p>This method handles the mechanics of splitting a full leaf page:
     * <ul>
     *   <li>Allocates a new right-sibling page via {@link IndexFile#allocateNewPage}</li>
     *   <li>Moves the upper half of entries to the new page</li>
     *   <li>Maintains the doubly-linked sibling chain correctly</li>
     *   <li>Returns the separator key for parent insertion</li>
     * </ul>
     *
     * <p>Sibling pointer maintenance follows PostgreSQL's pattern:
     * <pre>
     * Before:  ... <-> L (full) <-> R_old <-> ...
     * After:   ... <-> L         <-> R_new <-> R_old <-> ...
     * </pre>
     *
     * <p>fsync ordering: The new page is written first and fsync'd before
     * updating L's btpo_next and R_old's btpo_prev. This ensures that a
     * crash between writes leaves the new page orphaned but not visible,
     * which is recoverable by later vacuum.</p>
     *
     * @param indexFile the index file for allocating the new page
     * @return SplitResult containing the separator key and new right page ID
     * @throws java.io.IOException if file operations fail
     */
    public SplitResult split(IndexFile indexFile) throws java.io.IOException {
        // Collect all entries from this page
        int count = entryCount();
        if (count < 2) {
            throw new IllegalStateException("Cannot split page with fewer than 2 entries");
        }

        // Read all entries before we modify the page
        Entry[] entries = new Entry[count];
        for (int i = 0; i < count; i++) {
            entries[i] = new Entry(keyAt(i), ridAt(i));
        }

        // Split point: upper half goes to new page
        // For even counts: split exactly in half
        // For odd counts: left page gets one more (ceiling division)
        int splitPoint = (count + 1) / 2;

        // Allocate new right sibling page
        Page newPage = indexFile.allocateNewPage(PageType.INDEX_LEAF);
        BTreeLeafPage rightPage = BTreeLeafPage.of(IndexPageLayout.of(newPage));

        // Insert upper half entries into the new right page
        for (int i = splitPoint; i < count; i++) {
            InsertResult result = rightPage.insert(entries[i].key, entries[i].rid);
            if (result != InsertResult.OK) {
                throw new IllegalStateException("New page should have room: " + result);
            }
        }

        // The separator key is the smallest key now on the right page
        long separatorKey = entries[splitPoint].key;

        // Update sibling chain pointers
        int oldRightSibling = this.btpoNext();
        int newRightPageId = rightPage.pageId();
        int leftPageId = this.pageId();

        // Point new page at this page (left) and old right sibling
        rightPage.setBtpoPrev(leftPageId);
        rightPage.setBtpoNext(oldRightSibling);

        // Save left sibling pointer before reinitializing
        int leftSibling = this.btpoPrev();

        // Reset this page and re-insert lower half entries
        // We do this by creating a fresh empty layout at the same pageId
        this.layout.initializeAsLeaf();

        // Restore sibling pointers (initializeAsLeaf resets them)
        this.setBtpoPrev(leftSibling);
        this.setBtpoNext(newRightPageId);

        for (int i = 0; i < splitPoint; i++) {
            InsertResult result = this.insert(entries[i].key, entries[i].rid);
            if (result != InsertResult.OK) {
                throw new IllegalStateException("Left page should have room after split: " + result);
            }
        }

        // Write pages with proper fsync ordering per PostgreSQL:
        // 1. Write and fsync the new right page first
        indexFile.writePage(rightPage.layout.page());

        // 2. Write and fsync this (left) page with updated btpo_next
        indexFile.writePage(this.layout.page());

        // 3. If there was a right sibling, update its btpo_prev
        if (oldRightSibling != 0) {
            com.coredb.page.Page oldRightPageData = indexFile.readPage(oldRightSibling);
            BTreeLeafPage oldRightPage = BTreeLeafPage.of(IndexPageLayout.of(oldRightPageData));
            oldRightPage.setBtpoPrev(newRightPageId);
            indexFile.writePage(oldRightPageData);
        }

        return new SplitResult(separatorKey, newRightPageId);
    }

    /**
     * Simple holder for key/rid pairs during split.
     */
    private record Entry(long key, RecordId rid) {
    }
}
