package com.coredb.index;

import com.coredb.util.BinaryUtil;
import com.coredb.util.Constants;

import java.nio.ByteBuffer;

/**
 * B-tree page opaque data stored in the special area (pd_special) of index pages.
 *
 * <p>In PostgreSQL terminology, "opaque" means access-method-specific data that
 * the generic page layer doesn't understand — only the B-tree code interprets
 * these bytes. This is where B-tree stores sibling pointers (for leaf chains)
 * and tree level information.</p>
 *
 * <p>PostgreSQL's BTPageOpaqueData provides sibling pointers and level information
 * for B-tree pages. This is stored in the "special space" at the end of the page,
 * accessed via pd_special.</p>
 *
 * <p>Layout (12 bytes total):</p>
 * <pre>
 * bytes 0-3   btpo_prev   left sibling page id (0 = none)
 * bytes 4-7   btpo_next   right sibling page id (0 = none)
 * bytes 8-11  btpo_level  0 = leaf, 1+ = internal
 * </pre>
 *
 * <p>Note: 4 bytes for level is overkill (1 byte would suffice for practical trees),
 * but we match PostgreSQL's BTPageOpaqueData layout exactly for alignment.</p>
 *
 * <p>For index pages: pd_special = PAGE_SIZE - 12</p>
 */
public final class BTPageOpaque {

    public static final int SIZE = 12;

    private static final int OFFSET_PREV = 0;
    private static final int OFFSET_NEXT = 4;
    private static final int OFFSET_LEVEL = 8;

    private final ByteBuffer buffer;
    private final int specialOffset;

    private BTPageOpaque(ByteBuffer pageBuffer, int specialOffset) {
        this.buffer = pageBuffer;
        this.specialOffset = specialOffset;
    }

    /**
     * Creates a BTPageOpaque accessor for the given page buffer.
     *
     * @param pageBuffer the page's ByteBuffer
     * @param specialOffset the pd_special offset (typically PAGE_SIZE - 12)
     * @return a BTPageOpaque accessor
     */
    public static BTPageOpaque of(ByteBuffer pageBuffer, int specialOffset) {
        return new BTPageOpaque(pageBuffer, specialOffset);
    }

    /**
     * Returns the special offset for index pages (PAGE_SIZE - 12).
     */
    public static int specialOffsetForIndex() {
        return Constants.PAGE_SIZE - SIZE;
    }

    /**
     * Returns the left sibling page id (btpo_prev).
     * 0 means no left sibling.
     */
    public int btpoPrev() {
        return BinaryUtil.readU32(buffer, specialOffset + OFFSET_PREV);
    }

    /**
     * Sets the left sibling page id (btpo_prev).
     */
    public void setBtpoPrev(int prev) {
        BinaryUtil.writeU32(buffer, specialOffset + OFFSET_PREV, prev);
    }

    /**
     * Returns the right sibling page id (btpo_next).
     * 0 means no right sibling.
     */
    public int btpoNext() {
        return BinaryUtil.readU32(buffer, specialOffset + OFFSET_NEXT);
    }

    /**
     * Sets the right sibling page id (btpo_next).
     */
    public void setBtpoNext(int next) {
        BinaryUtil.writeU32(buffer, specialOffset + OFFSET_NEXT, next);
    }

    /**
     * Returns the page level (btpo_level).
     * 0 = leaf page, 1+ = internal page.
     */
    public int btpoLevel() {
        return BinaryUtil.readU32(buffer, specialOffset + OFFSET_LEVEL);
    }

    /**
     * Sets the page level (btpo_level).
     * 0 = leaf page, 1+ = internal page.
     */
    public void setBtpoLevel(int level) {
        BinaryUtil.writeU32(buffer, specialOffset + OFFSET_LEVEL, level);
    }

    /**
     * Initializes the opaque area for a new leaf page.
     * Sets prev/next to 0 and level to 0.
     */
    public void initAsLeaf() {
        setBtpoPrev(0);
        setBtpoNext(0);
        setBtpoLevel(0);
    }

    /**
     * Checks if this page is a leaf page (level == 0).
     */
    public boolean isLeaf() {
        return btpoLevel() == 0;
    }
}
