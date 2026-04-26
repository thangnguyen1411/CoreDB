package com.coredb.index;

import com.coredb.page.Page;
import com.coredb.page.PageType;

import java.io.IOException;

/**
 * B-tree internal page operations.
 *
 * <p>Internal pages store routing information to direct searches to child pages.
 * Unlike leaf pages which store (key, RecordId), internal pages store N separator
 * keys with N+1 child pointers.</p>
 *
 * <p>Structure: N keys with N+1 children:
 * <pre>
 *   leftmostChild, [(key1, child1), (key2, child2), ..., (keyN, childN)]
 *   
 *   Routing:
 *   - keys < key1 → leftmostChild
 *   - key1 <= keys < key2 → child1
 *   - key2 <= keys < key3 → child2
 *   - ...
 *   - keys >= keyN → childN
 * </pre>
 *
 * <p>The leftmost child is stored in btpo_prev (repurposed for internal pages).
 * Entry at slot i has key[i] and child[i] where child[i] handles keys >= key[i].</p>
 *
 * <p>Key differences from leaf pages:
 * <ul>
 *   <li>btpo_level >= 1 (0 is leaf only)</li>
 *   <li>Split promotes middle key to parent (doesn't keep it on right page)</li>
 *   <li>btpo_prev stores leftmost child for internal pages (not sibling pointer)</li>
 *   <li>N keys with N+1 children (leftmost child stored separately)</li>
 * </ul>
 */
public final class BTreeInternalPage {

    private final IndexPageLayout layout;

    private BTreeInternalPage(IndexPageLayout layout) {
        this.layout = layout;
    }

    /**
     * Creates a new empty internal page with the given level.
     *
     * @param pageId the page ID
     * @param level the tree level (1 = first internal level above leaves)
     * @return a new empty BTreeInternalPage
     */
    public static BTreeInternalPage createEmpty(int pageId, int level) {
        IndexPageLayout layout = IndexPageLayout.createEmpty(pageId, PageType.INDEX_INTERNAL);
        BTreeInternalPage page = new BTreeInternalPage(layout);
        page.setBtpoLevel(level);
        page.setLeftmostChild(0); // Initialize to invalid page
        return page;
    }

    /**
     * Returns the leftmost child page ID (stored in btpo_prev).
     * This child handles all keys less than the first separator key.
     */
    public int leftmostChild() {
        return layout.btpoPrev();
    }

    /**
     * Sets the leftmost child page ID (stored in btpo_prev).
     */
    public void setLeftmostChild(int childPageId) {
        layout.setBtpoPrev(childPageId);
    }

    /**
     * Returns the number of children (entries + 1 for leftmost).
     */
    public int childCount() {
        return entryCount() + 1;
    }

    /**
     * Wraps an existing IndexPageLayout as an internal page.
     *
     * @param layout the existing layout
     * @return a BTreeInternalPage wrapping the layout
     */
    public static BTreeInternalPage of(IndexPageLayout layout) {
        return new BTreeInternalPage(layout);
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
     * Returns the tree level of this page (btpo_level).
     * 0 = leaf, 1+ = internal.
     */
    public int btpoLevel() {
        return layout.btpoLevel();
    }

    /**
     * Sets the tree level of this page.
     */
    public void setBtpoLevel(int level) {
        layout.setBtpoLevel(level);
    }

    /**
     * Returns true if this is an internal page (level >= 1).
     */
    public boolean isInternal() {
        return btpoLevel() >= 1;
    }

    /**
     * Returns the key at the given slot.
     *
     * @param slot the slot index (0-based)
     * @return the key at that slot
     * @throws IndexOutOfBoundsException if slot is out of range
     */
    public long keyAt(int slot) {
        return layout.readInternalEntry(slot).key();
    }

    /**
     * Returns the child page ID at the given slot.
     *
     * @param slot the slot index (0-based)
     * @return the child page ID at that slot
     * @throws IndexOutOfBoundsException if slot is out of range
     */
    public int childPageIdAt(int slot) {
        return layout.readInternalEntry(slot).childPageId();
    }

    /**
     * Routes a search key to the appropriate child page.
     *
     * <p>Proper B-tree routing with N keys and N+1 children:
     * <ul>
     *   <li>keys < key[1] → leftmostChild</li>
     *   <li>key[i] <= keys < key[i+1] → child[i]</li>
     *   <li>keys >= key[N] → child[N]</li>
     * </ul>
     *
     * @param searchKey the key to route
     * @return the child page ID to descend to
     * @throws IllegalStateException if page has no children
     */
    public int routeChildFor(long searchKey) {
        int count = entryCount();
        if (count == 0) {
            // No separator keys, only leftmost child
            if (leftmostChild() == 0) {
                throw new IllegalStateException("Internal page has no children");
            }
            return leftmostChild();
        }

        // Check if searchKey < firstKey
        if (searchKey < keyAt(0)) {
            return leftmostChild();
        }

        // Find the rightmost key <= searchKey
        // That entry's child is the correct child to follow
        int slot = count - 1;
        for (int i = 0; i < count; i++) {
            if (keyAt(i) > searchKey) {
                slot = i - 1;
                break;
            }
        }

        return childPageIdAt(slot);
    }

    /**
     * Inserts a separator key and child page ID at the correct sorted position.
     *
     * <p>This is used when a child page splits and we need to add a new
     * routing entry to the parent internal page.</p>
     *
     * <p>The separator key divides the key space: the new child handles keys
     * >= separator, while the existing child (which may become leftmost or
     * shift position) handles keys < separator.</p>
     *
     * @param key the separator key (first key of the new right child)
     * @param childPageId the page ID of the new right child
     * @return OK if inserted, FULL if no room, DUPLICATE_KEY if key exists
     */
    public InsertResult insertSeparator(long key, int childPageId) {
        // Check if key already exists
        int existingSlot = findKeySlot(key);
        if (existingSlot >= 0) {
            return InsertResult.DUPLICATE_KEY;
        }

        // Calculate space needed: 12 bytes for tuple + 4 bytes for ItemId
        int spaceNeeded = 12 + 4;
        if (layout.freeBytes() < spaceNeeded) {
            return InsertResult.FULL;
        }

        // Find insertion point
        int insertionPoint = findInsertionPoint(key);

        // Perform sorted insertion
        insertAt(insertionPoint, key, childPageId);

        return InsertResult.OK;
    }

    /**
     * Initializes this internal page with a single separator and two children.
     * Used when the root is first converted from leaf to internal.
     *
     * @param leftChild the leftmost child page ID (keys < separator)
     * @param separator the separator key
     * @param rightChild the right child page ID (keys >= separator)
     */
    public void initializeWithChildren(int leftChild, long separator, int rightChild) {
        setLeftmostChild(leftChild);
        InsertResult result = insertSeparator(separator, rightChild);
        if (result != InsertResult.OK) {
            throw new IllegalStateException("Failed to initialize internal page: " + result);
        }
    }

    /**
     * Finds the slot where the given key exists, or -1 if not found.
     */
    private int findKeySlot(long searchKey) {
        int low = 0;
        int high = entryCount();

        while (low < high) {
            int mid = (low + high) >>> 1;
            long midKey = keyAt(mid);
            if (midKey < searchKey) {
                low = mid + 1;
            } else if (midKey > searchKey) {
                high = mid;
            } else {
                return mid; // Found
            }
        }
        return -1; // Not found
    }

    /**
     * Finds the insertion point for a key to maintain sorted order.
     * Returns the slot where key should be inserted (0..entryCount).
     */
    private int findInsertionPoint(long searchKey) {
        int low = 0;
        int high = entryCount();

        while (low < high) {
            int mid = (low + high) >>> 1;
            long midKey = keyAt(mid);
            if (midKey < searchKey) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        return low;
    }

    /**
     * Performs the actual insertion at a specific slot, shifting existing entries.
     */
    private void insertAt(int slot, long key, int childPageId) {
        int count = layout.entryCount();

        // Shift all ItemIds from slot to count-1 right by one position
        for (int i = count - 1; i >= slot; i--) {
            int itemId = layout.readItemId(i);
            layout.writeItemId(i + 1, itemId);
        }

        // Update pd_lower
        short newLower = (short) (layout.pdLower() + 4);
        layout.setPdLower(newLower);

        // Write tuple data
        byte[] tuple = new byte[12];
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(tuple);
        buf.putLong(0, key);
        buf.putInt(8, childPageId);

        // Calculate position for tuple data
        short newUpper = (short) (layout.pdUpper() - 12);

        // Write tuple data
        java.nio.ByteBuffer pageBuf = layout.page().buffer();
        pageBuf.position(newUpper);
        pageBuf.put(tuple);

        // Create and write ItemId
        int itemId = com.coredb.page.ItemId.pack(newUpper, com.coredb.page.ItemId.FLAGS_NORMAL, 12);
        layout.writeItemId(slot, itemId);

        // Update pd_upper
        layout.setPdUpper(newUpper);
    }

    /**
     * Splits this internal page into two.
     *
     * <p>Differs from leaf split in one critical way: the middle key is
     * <strong>promoted</strong> to the parent rather than staying on the
     * right page. The right page holds keys strictly greater than the separator.</p>
     *
     * <p>Leftmost child handling:
     * <ul>
     *   <li>Left page keeps its original leftmostChild</li>
     *   <li>The promoted key's child becomes the right page's leftmostChild</li>
     *   <li>Entries after promoted key go to right page with their children</li>
     * </ul></p>
     *
     * <p>Invariant: After split, the promoted separator is NOT on either child.
     * All keys in left page are < separator.
     * All keys in right page are > separator.</p>
     *
     * @param indexFile the index file for allocating the new page
     * @return InternalSplitResult with promoted key, left page ID, and right page ID
     * @throws IOException if file operations fail
     */
    public InternalSplitResult split(IndexFile indexFile) throws IOException {
        int count = entryCount();
        if (count < 2) {
            throw new IllegalStateException("Cannot split internal page with fewer than 2 entries");
        }

        // Save the original leftmost child of this page
        int originalLeftmostChild = leftmostChild();

        // Read all entries
        InternalEntry[] entries = new InternalEntry[count];
        for (int i = 0; i < count; i++) {
            entries[i] = new InternalEntry(keyAt(i), childPageIdAt(i));
        }

        // Split point: middle key is promoted
        int splitPoint = count / 2;

        // The promoted key and its child
        long promotedKey = entries[splitPoint].key;
        int promotedChild = entries[splitPoint].childPageId;

        // Allocate new right page at same level
        Page newPage = indexFile.allocateNewPage(PageType.INDEX_INTERNAL);
        BTreeInternalPage rightPage = BTreeInternalPage.of(IndexPageLayout.of(newPage));
        rightPage.setBtpoLevel(this.btpoLevel());

        // The promoted key's child becomes the right page's leftmost child
        rightPage.setLeftmostChild(promotedChild);

        // Right page gets entries AFTER the split point (strictly greater keys)
        for (int i = splitPoint + 1; i < count; i++) {
            InsertResult result = rightPage.insertSeparator(entries[i].key, entries[i].childPageId);
            if (result != InsertResult.OK) {
                throw new IllegalStateException("New internal page should have room: " + result);
            }
        }

        // Reset left page - save level BEFORE initializeAsLeaf wipes it
        int savedLevel = this.btpoLevel();
        this.layout.initializeAsLeaf(); // Reinitializes as leaf level 0
        this.setBtpoLevel(savedLevel); // Restore original level
        this.setLeftmostChild(originalLeftmostChild); // Restore leftmost child

        // Left page re-inserts entries BEFORE split point
        for (int i = 0; i < splitPoint; i++) {
            InsertResult result = this.insertSeparator(entries[i].key, entries[i].childPageId);
            if (result != InsertResult.OK) {
                throw new IllegalStateException("Left page should have room after split: " + result);
            }
        }

        // Write both pages
        indexFile.writePage(rightPage.layout().page());
        indexFile.writePage(this.layout().page());

        // Return the promoted key and both child page IDs
        return new InternalSplitResult(promotedKey, this.pageId(), rightPage.pageId());
    }

    /**
     * Returns the free bytes available on this page.
     */
    public int freeBytes() {
        return layout.freeBytes();
    }

    /**
     * Record for internal entries.
     */
    public record InternalEntry(long key, int childPageId) {
    }

    /**
     * Result of an internal page split.
     *
     * @param promotedKey the key promoted to parent (middle key)
     * @param leftPageId the page ID of left child (original page)
     * @param rightPageId the page ID of right child (new page)
     */
    public record InternalSplitResult(long promotedKey, int leftPageId, int rightPageId) {
    }
}
