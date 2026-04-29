package com.coredb.index;

import com.coredb.util.BinaryUtil;
import com.coredb.util.Constants;

import java.nio.ByteBuffer;

/**
 * B-tree page opaque data stored in the special area (pd_special) of index pages.
 *
 * <p>In PostgreSQL terminology, "opaque" means access-method-specific data that
 * the generic page layer doesn't understand — only the B-tree code interprets
 * these bytes. This is where B-tree stores sibling pointers (for leaf chains),
 * tree level information, and the Lehman-Yao high key.</p>
 *
 * <p>Layout (20 bytes total):</p>
 * <pre>
 * bytes 0-3   btpo_prev   left sibling page id (0 = none)
 * bytes 4-7   btpo_next   right sibling / right-link page id (0 = rightmost)
 * bytes 8-11  btpo_level  0 = leaf, 1+ = internal
 * bytes 12-19 high_key    smallest key on the right sibling; only meaningful when btpo_next != 0
 * </pre>
 *
 * <p>The high key is the Lehman-Yao boundary: a search for K that lands on a page
 * with btpo_next != 0 and K &gt;= high_key must follow the right-link to the next
 * page rather than concluding the key does not exist. This lets readers tolerate
 * splits that have not yet been reflected in the parent.</p>
 *
 * <p>For index pages: pd_special = PAGE_SIZE - 20.</p>
 */
public final class BTPageOpaque {

    public static final int SIZE = 20;

    private static final int OFFSET_PREV = 0;
    private static final int OFFSET_NEXT = 4;
    private static final int OFFSET_LEVEL = 8;
    private static final int OFFSET_HIGH_KEY = 12;

    private final ByteBuffer buffer;
    private final int specialOffset;

    private BTPageOpaque(ByteBuffer pageBuffer, int specialOffset) {
        this.buffer = pageBuffer;
        this.specialOffset = specialOffset;
    }

    public static BTPageOpaque of(ByteBuffer pageBuffer, int specialOffset) {
        return new BTPageOpaque(pageBuffer, specialOffset);
    }

    public static int specialOffsetForIndex() {
        return Constants.PAGE_SIZE - SIZE;
    }

    public int btpoPrev() {
        return BinaryUtil.readU32(buffer, specialOffset + OFFSET_PREV);
    }

    public void setBtpoPrev(int prev) {
        BinaryUtil.writeU32(buffer, specialOffset + OFFSET_PREV, prev);
    }

    public int btpoNext() {
        return BinaryUtil.readU32(buffer, specialOffset + OFFSET_NEXT);
    }

    public void setBtpoNext(int next) {
        BinaryUtil.writeU32(buffer, specialOffset + OFFSET_NEXT, next);
    }

    public int btpoLevel() {
        return BinaryUtil.readU32(buffer, specialOffset + OFFSET_LEVEL);
    }

    public void setBtpoLevel(int level) {
        BinaryUtil.writeU32(buffer, specialOffset + OFFSET_LEVEL, level);
    }

    /**
     * Returns the high key — the smallest key on the right sibling.
     * Only meaningful when {@link #btpoNext()} != 0.
     */
    public long highKey() {
        return buffer.getLong(specialOffset + OFFSET_HIGH_KEY);
    }

    public void setHighKey(long key) {
        buffer.putLong(specialOffset + OFFSET_HIGH_KEY, key);
    }

    /**
     * Initializes the opaque area for a new leaf page.
     * Sets prev/next to 0, level to 0, high key to 0.
     */
    public void initAsLeaf() {
        setBtpoPrev(0);
        setBtpoNext(0);
        setBtpoLevel(0);
        setHighKey(0L);
    }

    public boolean isLeaf() {
        return btpoLevel() == 0;
    }
}
