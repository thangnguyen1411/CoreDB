package com.coredb.fsm;

/**
 * Free Space Map — tracks how much free space each heap page has.
 *
 * <p>This is a simplified in-memory version of PostgreSQL's FSM. It stores
 * one byte per heap page representing the free space category
 * ({@code freeBytes / 32}, clamped to 255). A linear scan is used to find
 * a page with enough room.</p>
 *
 * <p><b>Key semantic:</b> FSM is a <i>hint</i>, not an authority. It is allowed
 * to be stale or wrong. Correctness must never depend on FSM accuracy — only
 * insert <i>performance</i> does.</p>
 */
public final class FreeSpaceMap {

    private byte[] categories;

    /**
     * Constructs an empty FSM for a heap file with {@code pageCount} data pages.
     *
     * <p>Page 0 is the meta page and is never tracked by FSM. Index 0 in the
     * array corresponds to heap page ID 1.</p>
     *
     * @param pageCount number of heap data pages to track (page 1 .. pageCount)
     */
    public FreeSpaceMap(int pageCount) {
        if (pageCount < 0) {
            throw new IllegalArgumentException("pageCount must be >= 0");
        }
        this.categories = new byte[pageCount];
    }

    /**
     * Returns the number of data pages this FSM is tracking.
     * Page 0 (meta page) is not tracked, so this equals the number of heap data pages.
     */
    public int trackedDataPageCount() {
        return categories.length;
    }

    /**
     * Searches for a heap page that has at least {@code neededBytes} of free
     * space. Performs a linear scan from page 1 upward.
     *
     * <p>A category of 0 means "treat as full" and is always skipped — even
     * when {@code neededBytes} is 0. This matches PostgreSQL's behaviour.</p>
     *
     * @param neededBytes minimum free bytes required
     * @return the 1-based page ID of the first matching page, or -1 if none
     */
    public int requestPage(int neededBytes) {
        for (int i = 0; i < categories.length; i++) {
            int cat = categories[i] & 0xFF; // unsigned
            if (cat > 0 && cat * 32 >= neededBytes) {
                return i + 1; // index 0 → pageId 1
            }
        }
        return -1;
    }

    /**
     * Updates the free-space category for a given page.
     *
     * @param pageId   the 1-based page ID
     * @param freeBytes the number of free bytes on that page
     * @throws IndexOutOfBoundsException if {@code pageId} is outside the
     *                                   tracked range (use {@link #grow(int)}
     *                                   to extend tracking)
     */
    public void updatePage(int pageId, int freeBytes) {
        if (pageId < 1 || pageId > categories.length) {
            throw new IndexOutOfBoundsException(
                "pageId " + pageId + " out of bounds [1.." + categories.length + "]");
        }
        categories[pageId - 1] = (byte) categoryFor(freeBytes);
    }

    /**
     * Returns the current category for a given page.
     *
     * @param pageId the 1-based page ID
     * @return the category byte as an unsigned int (0..255)
     * @throws IndexOutOfBoundsException if {@code pageId} is outside range
     */
    public int getCategory(int pageId) {
        if (pageId < 1 || pageId > categories.length) {
            throw new IndexOutOfBoundsException(
                "pageId " + pageId + " out of bounds [1.." + categories.length + "]");
        }
        return categories[pageId - 1] & 0xFF;
    }

    /**
     * Computes the free-space category for a given number of free bytes.
     *
     * <p><b>Why divide by 32?</b> One byte can only store 0-255. A page has ~8000
     * bytes of usable space (8192 minus 16-byte header), which doesn't fit. So we
     * use "buckets": divide free bytes by 32 to get a value 0-255. 255 × 32 = 8160,
     * which covers our usable page space (8176 bytes max on an empty page).</p>
     *
     * <p><b>Why clamp to 255?</b> If freeBytes is huge (e.g., 100000), division
     * gives 3125, which overflows a byte. We cap at 255 meaning "lots of space."</p>
     *
     * <p>Category = {@code freeBytes / 32}, clamped to 255 (0xFF).</p>
     *
     * <p>Examples: 0 bytes → 0, 500 bytes → 15, 8000 bytes → 250, 9000 bytes → 255</p>
     *
     * <p>Note: Even an empty 8KB page (8176 bytes free after 16-byte header) gets
     * category 255 because 8176/32 = 255 exactly.</p>
     *
     * @param freeBytes number of free bytes on a page
     * @return category value (0..255)
     */
    public static int categoryFor(int freeBytes) {
        if (freeBytes < 0) {
            return 0;
        }
        return Math.min(freeBytes / 32, 255);
    }

    /**
     * Extends the tracked page count to {@code newPageCount}.
     * New entries are zero-filled (category 0 = full).
     *
     * @param newPageCount the new total number of pages to track
     * @throws IllegalArgumentException if {@code newPageCount} is less than
     *                                  the current page count
     */
    public void grow(int newPageCount) {
        if (newPageCount < categories.length) {
            throw new IllegalArgumentException(
                "newPageCount " + newPageCount + " < current " + categories.length);
        }
        if (newPageCount == categories.length) {
            return;
        }
        byte[] newCategories = new byte[newPageCount];
        System.arraycopy(categories, 0, newCategories, 0, categories.length);
        // remaining bytes are already zero (category 0)
        categories = newCategories;
    }
}
