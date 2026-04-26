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
    private int tableOid;
    private int pageId;
    private int pinCount;
    private int usageCount;
    private boolean dirty;
    private long pdLsn;
    private final ByteBuffer page;

    BufferDescriptor(int frameId, ByteBuffer page) {
        this.frameId = frameId;
        this.page = page;
        this.tableOid = 0;
        this.pageId = 0;
        this.pinCount = 0;
        this.usageCount = 0;
        this.dirty = false;
        this.pdLsn = 0;
    }

    public int frameId() {
        return frameId;
    }

    public int tableOid() {
        return tableOid;
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
     * Binds this frame to a specific page from a table.
     * Called when a page is loaded into this frame.
     */
    void bind(int tableOid, int pageId) {
        this.tableOid = tableOid;
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
            throw new IllegalStateException("Unpin called on unpinned buffer frame " + frameId);
        }
        pinCount--;
    }

    /**
     * Marks the buffer as dirty and bumps usage count.
     * Dirty pages must be flushed to disk before eviction.
     * PostgreSQL: BM_DIRTY flag + usage_count bump.
     */
    void markDirty() {
        dirty = true;
        usageCount = Math.min(5, usageCount + 1);
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
    void setPdLsn(long lsn) {
        pdLsn = lsn;
    }

    /**
     * Resets all mutable state for reuse when frame is evicted.
     */
    void reset() {
        tableOid = 0;
        pageId = 0;
        pinCount = 0;
        usageCount = 0;
        dirty = false;
        pdLsn = 0;
    }

    /**
     * Returns a compact key for hash table lookups.
     * Packs (tableOid, pageId) into a long for use as HashMap key.
     */
    static long key(int tableOid, int pageId) {
        return ((long) tableOid << 32) | (pageId & 0xFFFFFFFFL);
    }

    @Override
    public String toString() {
        return String.format("BufferDescriptor[frame=%d, oid=%d, page=%d, pins=%d, usage=%d, dirty=%s]",
            frameId, tableOid, pageId, pinCount, usageCount, dirty);
    }
}
