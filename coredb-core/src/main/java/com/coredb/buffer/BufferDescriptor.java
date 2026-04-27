package com.coredb.buffer;

import java.nio.ByteBuffer;

/**
 * BufferDescriptor represents a single frame in the buffer pool.
 *
 * One descriptor per frame. Mutable state (pinCount, usageCount, dirty, pdLsn)
 * tracks the page's lifecycle in the pool. When a frame is evicted, reset() clears
 * all mutable state for reuse.
 *
 * PostgreSQL parallel: BufferDesc (src/include/storage/buf.h)
 */
public final class BufferDescriptor {

    private final int frameId;
    private int fileId;
    private int pageId;
    private int pinCount;
    private int usageCount;
    private boolean dirty;
    private long pdLsn;
    private final ByteBuffer page;
    private boolean needsFullPageWrite;

    BufferDescriptor(int frameId, ByteBuffer page) {
        this.frameId = frameId;
        this.page = page;
        this.fileId = 0;
        this.pageId = 0;
        this.pinCount = 0;
        this.usageCount = 0;
        this.dirty = false;
        this.pdLsn = 0;
        this.needsFullPageWrite = false;
    }

    public int frameId() {
        return frameId;
    }

    public int fileId() {
        return fileId;
    }

    public int pageId() {
        return pageId;
    }

    public int pinCount() {
        return pinCount;
    }

    public int usageCount() {
        return usageCount;
    }

    public boolean dirty() {
        return dirty;
    }

    public long pdLsn() {
        return pdLsn;
    }

    public ByteBuffer page() {
        return page;
    }

    /**
     * Binds this frame to a specific page from a file.
     * Called when a page is loaded into this frame.
     */
    void bind(int fileId, int pageId) {
        this.fileId = fileId;
        this.pageId = pageId;
    }

    /**
     * Pins the buffer - prevents eviction while in use.
     * PostgreSQL: pinCount in BufferDesc.
     */
    void pin() {
        pinCount++;
    }

    /**
     * Unpins the buffer. Must have been pinned first.
     * PostgreSQL: pinCount decrement in UnpinBuffer.
     */
    void unpin() {
        if (pinCount <= 0) {
            throw new IllegalStateException(
                "Unpin called on unpinned buffer frame " + frameId
            );
        }
        pinCount--;
    }

    /**
     * Marks the buffer as dirty and bumps usage count.
     *
     * Dirty flag: set when the page's in-memory contents diverge from disk.
     * The eviction logic must flush dirty pages before reusing the frame,
     * otherwise modifications are lost.
     *
     * Usage count: bumped because modified pages are likely part of an active
     * workload and should be kept in memory longer. Capped at 5 to prevent
     * hot pages from dominating the pool indefinitely; the clock-sweep
     * eviction algorithm decrements this counter periodically, so a value
     * of 5 gives a page ~5 sweep cycles before it becomes evictable.
     * PostgreSQL uses the same cap via BM_MAX_USAGE_COUNT (src/backend/bufmgr/freelist.c).
     *
     * PostgreSQL: BM_DIRTY flag + usage_count bump in MarkBufferDirty().
     */
    void markDirty() {
        dirty = true;
        usageCount = Math.min(5, usageCount + 1);
    }

    /**
     * Initializes usageCount to 1 when a page is first loaded (cache miss).
     */
    void initUsageCount() {
        usageCount = 1;
    }

    /**
     * Bumps usageCount on cache hit, capped at 5 (hot pages survive longer).
     */
    void bumpUsageCount() {
        usageCount = Math.min(5, usageCount + 1);
    }

    /**
     * Decrements usageCount for clock-sweep eviction algorithm.
     * Usage count never goes below 0.
     * PostgreSQL: usage_count decrement in StrategyGetBuffer (freelist.c).
     */
    void decrementUsage() {
        if (usageCount > 0) {
            usageCount--;
        }
    }

    /**
     * Marks the buffer as clean (after successful flush to disk).
     * Preserves the binding for potential cache reuse.
     */
    void markClean() {
        dirty = false;
    }

    /**
     * Sets the pd_lsn - the LSN of the last WAL record for this page.
     * Used for WAL-before-data flush rule.
     */
    public void setPdLsn(long lsn) {
        pdLsn = lsn;
    }

    /**
     * Returns true if this frame needs a full-page write on next WAL record.
     */
    public boolean needsFullPageWrite() {
        return needsFullPageWrite;
    }

    /**
     * Sets the full-page write flag.
     * Called at checkpoint completion for all frames.
     */
    public void setNeedsFullPageWrite(boolean needsFullPageWrite) {
        this.needsFullPageWrite = needsFullPageWrite;
    }

    /**
     * Clears the full-page write flag after a WAL record has embedded the page image.
     */
    public void clearNeedsFullPageWrite() {
        this.needsFullPageWrite = false;
    }

    /**
     * Resets all mutable state for reuse when frame is evicted.
     */
    void reset() {
        fileId = 0;
        pageId = 0;
        pinCount = 0;
        usageCount = 0;
        dirty = false;
        pdLsn = 0;
        needsFullPageWrite = false;
    }

    /**
     * Returns a compact key for hash table lookups.
     * Packs 2 int values (fileId, pageId) into a long for use as HashMap key.
     */
    static long key(int fileId, int pageId) {
        return ((long) fileId << 32) | (pageId & 0xFFFFFFFFL);
    }

    @Override
    public String toString() {
        return String.format(
            "BufferDescriptor[frame=%d, fileId=%d, page=%d, pins=%d, usage=%d, dirty=%s]",
            frameId,
            fileId,
            pageId,
            pinCount,
            usageCount,
            dirty
        );
    }
}
