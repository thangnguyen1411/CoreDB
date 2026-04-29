package com.coredb.buffer;

import java.nio.ByteBuffer;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * A single frame in the buffer pool.
 *
 * Concurrency model — four primitives, acquired in this order:
 *   1. BufferTable partition lock  (caller's responsibility)
 *   2. headerMutex                 (protects all header fields below)
 *   3. contentLock (read/write)    (protects page bytes)
 *
 * The ioCondition is signalled when ioInProgress flips false, so threads
 * waiting on a concurrent page load are woken immediately on completion.
 *
 * PostgreSQL parallel: BufferDesc (src/include/storage/buf_internals.h)
 */
public final class BufferDescriptor {

    // Immutable after construction
    private final int frameId;
    private final ByteBuffer page;

    // Concurrency primitives
    private final ReentrantLock headerMutex = new ReentrantLock();
    private final Condition ioCondition = headerMutex.newCondition();
    private final ReentrantReadWriteLock contentLock = new ReentrantReadWriteLock();

    // Header-mutex-protected fields
    private int fileId;
    private int pageId;
    private int pinCount;
    private int usageCount;
    private boolean dirty;
    private long pdLsn;
    private boolean needsFullPageWrite;
    private boolean ioInProgress;
    private boolean valid;

    BufferDescriptor(int frameId, ByteBuffer page) {
        this.frameId = frameId;
        this.page = page;
    }

    // ==================== Accessors (read under headerMutex where noted) ====================

    public int frameId() { return frameId; }
    public ByteBuffer page() { return page; }

    /** Acquire headerMutex before reading. */
    public int fileId() { return fileId; }
    public int pageId() { return pageId; }
    public int pinCount() { return pinCount; }
    public int usageCount() { return usageCount; }
    public boolean dirty() { return dirty; }
    public long pdLsn() { return pdLsn; }
    public boolean needsFullPageWrite() { return needsFullPageWrite; }
    public boolean ioInProgress() { return ioInProgress; }
    public boolean valid() { return valid; }

    // ==================== Concurrency primitive accessors ====================

    ReentrantLock headerMutex() { return headerMutex; }
    Condition ioCondition() { return ioCondition; }
    ReentrantReadWriteLock contentLock() { return contentLock; }

    // ==================== Header-mutex-protected mutations ====================

    /** Caller must hold headerMutex. */
    void pin() { pinCount++; }

    /**
     * Increments pinCount for a cache hit.
     * Caller must hold headerMutex.
     */
    void pinOnHit() {
        pinCount++;
        usageCount = Math.min(5, usageCount + 1);
    }

    /**
     * Called by ClockSweep when claiming this frame as an eviction victim.
     * Sets pinCount to 1 so no other sweep can claim it simultaneously.
     * Caller must hold headerMutex.
     */
    void pinForEviction() {
        pinCount = 1;
    }

    /**
     * Decrements pinCount. Returns true if pinCount dropped to zero.
     * Caller must hold headerMutex.
     */
    boolean unpin() {
        if (pinCount <= 0) {
            throw new IllegalStateException("Unpin called on unpinned buffer frame " + frameId);
        }
        return --pinCount == 0;
    }

    /** Caller must hold headerMutex. */
    void markDirty() {
        dirty = true;
        usageCount = Math.min(5, usageCount + 1);
    }

    /** Caller must hold headerMutex. */
    void markClean() { dirty = false; }

    /** Caller must hold headerMutex. */
    void bumpUsageCount() { usageCount = Math.min(5, usageCount + 1); }

    /** Caller must hold headerMutex. */
    void decrementUsage() { if (usageCount > 0) usageCount--; }

    /** Caller must hold headerMutex. */
    public void setPdLsn(long lsn) { pdLsn = lsn; }

    /** Caller must hold headerMutex. */
    public void setNeedsFullPageWrite(boolean v) { needsFullPageWrite = v; }

    /** Caller must hold headerMutex. */
    public void clearNeedsFullPageWrite() { needsFullPageWrite = false; }

    /**
     * Binds this frame to a new (fileId, pageId) for loading.
     * Sets ioInProgress=true, valid=false, dirty=false, pinCount=1, usageCount=1.
     * Caller must hold headerMutex.
     */
    void bindForLoad(int newFileId, int newPageId) {
        fileId = newFileId;
        pageId = newPageId;
        pinCount = 1;
        usageCount = 1;
        dirty = false;
        pdLsn = 0;
        needsFullPageWrite = false;
        valid = false;
        ioInProgress = true;
    }

    /**
     * Completes a successful IO: marks frame valid, clears ioInProgress, wakes waiters.
     * Caller must hold headerMutex.
     */
    void completeLoad(long pageLsn) {
        pdLsn = pageLsn;
        valid = true;
        ioInProgress = false;
        ioCondition.signalAll();
    }

    /**
     * Aborts a failed IO: clears binding and IO state, wakes waiters.
     * Does NOT reset pinCount — the evictor must call unpin() explicitly after this,
     * and waiting threads must call unpin() after detecting !valid().
     * Caller must hold headerMutex.
     */
    void abortLoad() {
        fileId = 0;
        pageId = 0;
        dirty = false;
        pdLsn = 0;
        valid = false;
        ioInProgress = false;
        ioCondition.signalAll();
    }

    /**
     * Releases a victim claim after deciding not to use this frame.
     * Resets it to empty. Caller must hold headerMutex.
     */
    void releaseVictimClaim() {
        fileId = 0;
        pageId = 0;
        pinCount = 0;
        dirty = false;
        pdLsn = 0;
        needsFullPageWrite = false;
        valid = false;
        ioInProgress = false;
        ioCondition.signalAll();
    }

    /**
     * Sets valid and clears ioInProgress after a new-page allocation (no disk read needed).
     * Caller must hold headerMutex.
     */
    void bindNewPage(int newFileId, int newPageId) {
        fileId = newFileId;
        pageId = newPageId;
        pinCount = 1;
        usageCount = 1;
        dirty = true;
        pdLsn = 0;
        needsFullPageWrite = false;
        valid = true;
        ioInProgress = false;
    }

    /** Returns a compact key for hash table lookups. */
    static long key(int fileId, int pageId) {
        return ((long) fileId << 32) | (pageId & 0xFFFFFFFFL);
    }

    @Override
    public String toString() {
        return String.format(
            "BufferDescriptor[frame=%d, fileId=%d, page=%d, pins=%d, usage=%d, dirty=%s, valid=%s, io=%s]",
            frameId, fileId, pageId, pinCount, usageCount, dirty, valid, ioInProgress
        );
    }
}
