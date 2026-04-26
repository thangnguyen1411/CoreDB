package com.coredb.index;

import com.coredb.heap.RecordId;
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
}
