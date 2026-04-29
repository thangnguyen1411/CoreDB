package com.coredb.index;

import com.coredb.heap.RecordId;
import com.coredb.page.ItemId;
import com.coredb.page.Page;
import com.coredb.page.PageHeader;
import com.coredb.page.PageType;
import com.coredb.util.BinaryUtil;
import com.coredb.util.Constants;

import java.nio.ByteBuffer;

/**
 * Layout operations for B-tree index pages.
 *
 * <p>Index pages use the same basic page structure as heap pages:
 * header, ItemId array, tuple data, and special space. The key differences:
 * <ul>
 *   <li>Special space holds BTPageOpaque (20 bytes) with sibling pointers and high key</li>
 *   <li>pd_special = PAGE_SIZE - 20 for index pages</li>
 *   <li>Tuples contain (key, RecordId) pairs for leaf pages</li>
 *   <li>Tuples contain (key, childPageId) pairs for internal pages</li>
 * </ul>
 */
public final class IndexPageLayout {

    private final Page page;
    private final ByteBuffer buffer;
    private final BTPageOpaque opaque;

    private IndexPageLayout(Page page) {
        this.page = page;
        this.buffer = page.buffer();
        this.opaque = BTPageOpaque.of(buffer, BTPageOpaque.specialOffsetForIndex());
    }

    /**
     * Wraps an existing page as an index page layout.
     */
    public static IndexPageLayout of(Page page) {
        return new IndexPageLayout(page);
    }

    /**
     * Creates a new empty index page with the given type.
     * Sets up pd_special and initializes BTPageOpaque as a leaf.
     */
    public static IndexPageLayout createEmpty(int pageId, PageType type) {
        Page page = Page.Factory.allocate(pageId, type);
        IndexPageLayout layout = new IndexPageLayout(page);
        layout.initializeAsLeaf();
        return layout;
    }

    /**
     * Initializes the page as an empty leaf:
     * - Sets pd_special = PAGE_SIZE - 20
     * - Initializes BTPageOpaque (prev=0, next=0, level=0, highKey=0)
     * - Sets pd_lower = header size, pd_upper = pd_special
     */
    public void initializeAsLeaf() {
        short specialOffset = (short) BTPageOpaque.specialOffsetForIndex();
        BinaryUtil.writeU16(buffer, PageHeader.OFFSET_PD_SPECIAL, specialOffset);
        opaque.initAsLeaf();
        // pd_lower starts after header, pd_upper starts at special area
        setPdLower((short) PageHeader.SIZE);
        setPdUpper(specialOffset);
    }

    // === Header accessors ===

    public int pageId() {
        return page.pageId();
    }

    public PageType pageType() {
        return page.pageType();
    }

    /**
     * Returns the underlying Page object.
     */
    public Page page() {
        return page;
    }

    public short pdLower() {
        return BinaryUtil.readU16(buffer, PageHeader.OFFSET_PD_LOWER);
    }

    public void setPdLower(short lower) {
        BinaryUtil.writeU16(buffer, PageHeader.OFFSET_PD_LOWER, lower);
    }

    public short pdUpper() {
        return BinaryUtil.readU16(buffer, PageHeader.OFFSET_PD_UPPER);
    }

    public void setPdUpper(short upper) {
        BinaryUtil.writeU16(buffer, PageHeader.OFFSET_PD_UPPER, upper);
    }

    public short pdSpecial() {
        return BinaryUtil.readU16(buffer, PageHeader.OFFSET_PD_SPECIAL);
    }

    // === BTPageOpaque accessors ===

    public BTPageOpaque opaque() {
        return opaque;
    }

    public int btpoPrev() {
        return opaque.btpoPrev();
    }

    public void setBtpoPrev(int prev) {
        opaque.setBtpoPrev(prev);
    }

    public int btpoNext() {
        return opaque.btpoNext();
    }

    public void setBtpoNext(int next) {
        opaque.setBtpoNext(next);
    }

    public int btpoLevel() {
        return opaque.btpoLevel();
    }

    public void setBtpoLevel(int level) {
        opaque.setBtpoLevel(level);
    }

    public boolean isLeaf() {
        return opaque.isLeaf();
    }

    public long highKey() {
        return opaque.highKey();
    }

    public void setHighKey(long key) {
        opaque.setHighKey(key);
    }

    // === Free space ===

    /**
     * Returns the amount of contiguous free space on the page.
     */
    public int freeBytes() {
        return Short.toUnsignedInt(pdUpper()) - Short.toUnsignedInt(pdLower());
    }

    /**
     * Returns the number of ItemId slots (entries) on this page.
     */
    public int entryCount() {
        return (pdLower() - PageHeader.SIZE) / ItemId.SIZE;
    }

    // === ItemId array operations ===

    /**
     * Reads an ItemId from the specified slot.
     */
    public int readItemId(int slot) {
        return BinaryUtil.readU32(buffer, PageHeader.SIZE + slot * ItemId.SIZE);
    }

    /**
     * Writes an ItemId to the specified slot.
     */
    public void writeItemId(int slot, int itemId) {
        BinaryUtil.writeU32(buffer, PageHeader.SIZE + slot * ItemId.SIZE, itemId);
    }

    // === Tuple operations ===

    /**
     * Returns the raw bytes of the tuple at the given slot.
     * Caller must interpret based on whether this is a leaf or internal page.
     */
    public byte[] readTuple(int slot) {
        int itemId = readItemId(slot);
        int offset = ItemId.offset(itemId);
        int length = ItemId.length(itemId);

        byte[] result = new byte[length];
        buffer.position(offset);
        buffer.get(result);
        return result;
    }

    /**
     * Writes a tuple and returns its slot number.
     * The tuple is written at pd_upper - length, and an ItemId is appended.
     *
     * @param tupleBytes the raw tuple bytes to write
     * @return the slot number assigned to this tuple
     * @throws IllegalStateException if there is not enough space
     */
    public int writeTuple(byte[] tupleBytes) {
        int spaceNeeded = tupleBytes.length + ItemId.SIZE;
        if (freeBytes() < spaceNeeded) {
            throw new IllegalStateException("not enough space on page: need " + spaceNeeded +
                    ", have " + freeBytes());
        }

        // Calculate new position for tuple data
        short newUpper = (short) (pdUpper() - tupleBytes.length);
        int slot = entryCount();
        short newLower = (short) (pdLower() + ItemId.SIZE);

        // Write tuple data
        buffer.position(newUpper);
        buffer.put(tupleBytes);

        // Create and write ItemId (NORMAL flag)
        int itemId = ItemId.pack(newUpper, ItemId.FLAGS_NORMAL, tupleBytes.length);
        writeItemId(slot, itemId);

        // Update header
        setPdUpper(newUpper);
        setPdLower(newLower);

        return slot;
    }

    // === Leaf page tuple helpers ===

    /**
     * Reads a leaf tuple at the given slot as (key, RecordId).
     * Layout: key (8 bytes) + RecordId (6 bytes) = 14 bytes
     */
    public LeafEntry readLeafEntry(int slot) {
        byte[] tuple = readTuple(slot);
        ByteBuffer buf = ByteBuffer.wrap(tuple);
        long key = buf.getLong(0);
        int pageId = buf.getInt(8);
        short slotNo = buf.getShort(12);
        return new LeafEntry(key, new RecordId(pageId, Short.toUnsignedInt(slotNo)));
    }

    /**
     * Writes a leaf entry (key, RecordId) to the page.
     */
    public int writeLeafEntry(long key, RecordId rid) {
        byte[] tuple = new byte[14];
        ByteBuffer buf = ByteBuffer.wrap(tuple);
        buf.putLong(0, key);
        buf.putInt(8, rid.pageId());
        buf.putShort(12, (short) rid.slotNo());
        return writeTuple(tuple);
    }

    // === Internal page tuple helpers ===

    /**
     * Reads an internal tuple at the given slot as (key, childPageId).
     * Layout: key (8 bytes) + childPageId (4 bytes) = 12 bytes
     */
    public InternalEntry readInternalEntry(int slot) {
        byte[] tuple = readTuple(slot);
        ByteBuffer buf = ByteBuffer.wrap(tuple);
        long key = buf.getLong(0);
        int childPageId = buf.getInt(8);
        return new InternalEntry(key, childPageId);
    }

    /**
     * Writes an internal entry (key, childPageId) to the page.
     */
    public int writeInternalEntry(long key, int childPageId) {
        byte[] tuple = new byte[12];
        ByteBuffer buf = ByteBuffer.wrap(tuple);
        buf.putLong(0, key);
        buf.putInt(8, childPageId);
        return writeTuple(tuple);
    }

    // === Binary search ===

    /**
     * Finds the slot where the given key should be inserted to maintain sorted order.
     * Returns the insertion point (0..entryCount) such that all entries before it
     * have keys < search key, and all at or after have keys >= search key.
     */
    public int findInsertionPoint(long searchKey) {
        int low = 0;
        int high = entryCount();

        while (low < high) {
            int mid = (low + high) >>> 1;
            LeafEntry entry = readLeafEntry(mid);
            if (entry.key() < searchKey) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        return low;
    }

    /**
     * Searches for a key in a leaf page. Returns the slot if found, or -1 if not found.
     */
    public int searchKey(long searchKey) {
        int slot = findInsertionPoint(searchKey);
        if (slot < entryCount()) {
            LeafEntry entry = readLeafEntry(slot);
            if (entry.key() == searchKey) {
                return slot;
            }
        }
        return -1;
    }

    /**
     * Finds the slot where the given key should be inserted in an internal page.
     * Uses readInternalEntry instead of readLeafEntry.
     * Returns insertion point (0..entryCount) such that all entries before have keys < search key.
     */
    public int findInternalInsertionPoint(long searchKey) {
        int low = 0;
        int high = entryCount();

        while (low < high) {
            int mid = (low + high) >>> 1;
            InternalEntry entry = readInternalEntry(mid);
            if (entry.key() < searchKey) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        return low;
    }

    // === Inner classes ===

    public record LeafEntry(long key, RecordId rid) {}
    public record InternalEntry(long key, int childPageId) {}
}
