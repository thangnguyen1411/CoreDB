package com.coredb.buffer;

import com.coredb.catalog.ControlFile;
import com.coredb.page.Page;
import com.coredb.page.PageIO;
import com.coredb.util.Constants;
import com.coredb.util.StorageException;
import com.coredb.wal.XLogRecord;
import com.coredb.wal.XLogResourceManager;
import com.coredb.wal.XLogWriter;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Thread-safe in-memory page cache backed by clock-sweep eviction.
 *
 * Concurrency design (four primitives, held in this order):
 *   1. BufferTable partition lock  — protects hash table lookups and updates
 *   2. ClockSweep mutex            — disjoint from partition locks; serialises hand advancement
 *   3. BufferDescriptor.headerMutex — protects pinCount, usageCount, dirty, pdLsn, ioInProgress, valid
 *   4. BufferDescriptor.contentLock — rwlock protecting page bytes
 *
 * IO-in-progress flag: prevents duplicate disk reads when two threads miss
 * on the same (fileId, pageId) concurrently. The second thread waits on
 * ioCondition; the first signals when its read completes.
 *
 * PostgreSQL parallel: src/backend/storage/buffer/bufmgr.c
 */
public final class BufferPool implements AutoCloseable {

    /** Default sweep timeout: 5 seconds. Tests may pass a shorter value. */
    static final long DEFAULT_SWEEP_TIMEOUT_MS = 5_000;

    private final int frameCount;
    private final BufferDescriptor[] descriptors;
    private final BufferTable bufferTable;
    private final ClockSweep clockSweep;
    private final ConcurrentHashMap<Integer, FileChannel> channels = new ConcurrentHashMap<>();
    private volatile XLogWriter xlogWriter;

    // Statistics
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final AtomicLong ioWaits = new AtomicLong();
    private final AtomicLong evictions = new AtomicLong();

    public BufferPool(int frameCount) {
        this(frameCount, DEFAULT_SWEEP_TIMEOUT_MS);
    }

    public BufferPool(int frameCount, long sweepTimeoutMs) {
        this.frameCount = frameCount;
        this.descriptors = new BufferDescriptor[frameCount];
        this.bufferTable = new BufferTable();
        this.clockSweep = new ClockSweep(frameCount, sweepTimeoutMs);

        ByteBuffer poolBuffer = ByteBuffer.allocateDirect(
            Constants.PAGE_SIZE * frameCount
        ).order(ByteOrder.BIG_ENDIAN);

        for (int i = 0; i < frameCount; i++) {
            int offset = i * Constants.PAGE_SIZE;
            poolBuffer.position(offset);
            poolBuffer.limit(offset + Constants.PAGE_SIZE);
            ByteBuffer frameBuffer = poolBuffer.slice().order(ByteOrder.BIG_ENDIAN);
            descriptors[i] = new BufferDescriptor(i, frameBuffer);
        }
        poolBuffer.clear();
    }

    public BufferPool() {
        this(1024);
    }

    public void setXLogWriter(XLogWriter xlogWriter) {
        this.xlogWriter = xlogWriter;
    }

    // ==================== File registration ====================

    public void registerFile(int fileId, Path filePath) throws IOException {
        if (channels.containsKey(fileId)) {
            return;
        }
        FileChannel channel = FileChannel.open(filePath, StandardOpenOption.READ, StandardOpenOption.WRITE);
        channels.put(fileId, channel);
    }

    public void unregisterFile(int fileId) throws IOException {
        flushAllForFile(fileId);
        FileChannel channel = channels.remove(fileId);
        if (channel != null) {
            channel.close();
        }
        bufferTable.removeAllForFile(fileId);
    }

    // ==================== Core fetch operations ====================

    /**
     * Fetches a page from the buffer pool.
     *
     * Hit: pins the existing frame and returns it immediately (after waiting for
     * any in-flight IO on that frame to complete).
     *
     * Miss: selects a victim, evicts its old page if needed, loads the new page
     * from disk, and returns the frame. The IO-in-progress flag is set before
     * the disk read begins so that a concurrent miss for the same page waits
     * rather than issuing a duplicate read.
     */
    public BufferDescriptor fetchPage(int fileId, int pageId) throws IOException {
        FileChannel channel = channels.get(fileId);
        if (channel == null) {
            throw new StorageException("File not registered for fileId=" + fileId);
        }

        long key = BufferTable.key(fileId, pageId);

        // ── HIT path ──────────────────────────────────────────────────────────
        ReentrantLock partLock = bufferTable.lockFor(key);
        partLock.lock();
        try {
            Integer frameId = bufferTable.lookup(key);
            if (frameId != null) {
                BufferDescriptor d = descriptors[frameId];
                d.headerMutex().lock();
                try {
                    d.pinOnHit();
                } finally {
                    d.headerMutex().unlock();
                }
                hits.incrementAndGet();
                waitForIo(d);
                d.headerMutex().lock();
                try {
                    if (!d.valid()) {
                        boolean droppedToZero = d.unpin();
                        if (droppedToZero) clockSweep.notifyUnpin();
                        throw new StorageException(
                            "Page load failed for fileId=" + fileId + " pageId=" + pageId);
                    }
                } finally {
                    d.headerMutex().unlock();
                }
                return d;
            }
        } finally {
            partLock.unlock();
        }

        // ── MISS path ─────────────────────────────────────────────────────────
        // partLock was released above before the sweep call (disjoint mutexes).

        while (true) {
            // 1. Select victim under sweepMutex (blocks if all frames pinned).
            int victimFrameId = clockSweep.selectVictim(descriptors);
            BufferDescriptor victim = descriptors[victimFrameId];

            // 2. Evict victim's old page if it holds one.
            // Read `valid` under headerMutex — writes to valid happen under headerMutex
            // (in completeLoad / abortLoad), so a plain read without the lock can see a
            // stale value, leaving the old hash entry intact and creating two keys for the
            // same frame.
            boolean victimHasPage;
            victim.headerMutex().lock();
            try {
                victimHasPage = victim.valid();
            } finally {
                victim.headerMutex().unlock();
            }

            if (victimHasPage) {
                boolean evicted = evictFrame(victim);
                if (!evicted) {
                    // Victim was re-pinned by another thread between sweep and our eviction check.
                    // Release only our eviction claim (don't touch other fields — the frame is in
                    // active use by whoever pinned it). The old hash key was NOT removed by evictFrame.
                    victim.headerMutex().lock();
                    try {
                        victim.unpin();
                    } finally {
                        victim.headerMutex().unlock();
                    }
                    clockSweep.notifyUnpin();
                    continue;
                }
                evictions.incrementAndGet();
            }

            // 3. Bind victim to the new (fileId, pageId) and set ioInProgress.
            victim.headerMutex().lock();
            try {
                victim.bindForLoad(fileId, pageId);
            } finally {
                victim.headerMutex().unlock();
            }

            // 4. Insert into hash under partition lock, checking for a concurrent load.
            partLock.lock();
            Integer existing = bufferTable.lookup(key);
            if (existing != null) {
                // Another thread loaded this page while we were selecting a victim.
                // Discard our victim claim and pin the already-loaded frame.
                partLock.unlock();

                victim.headerMutex().lock();
                try {
                    victim.releaseVictimClaim();
                } finally {
                    victim.headerMutex().unlock();
                }
                clockSweep.notifyUnpin();

                // Pin the concurrent frame and wait for its IO.
                BufferDescriptor other = descriptors[existing];
                other.headerMutex().lock();
                try {
                    other.pinOnHit();
                } finally {
                    other.headerMutex().unlock();
                }
                // Count as a hit: we got the page without going to disk ourselves.
                hits.incrementAndGet();
                waitForIo(other);
                return other;
            }
            // Commit: we own this load. Count it as a miss now.
            misses.incrementAndGet();
            bufferTable.insert(key, victimFrameId);
            partLock.unlock();

            // 5. Perform disk read OUTSIDE all latches.
            try {
                Page page = PageIO.readPage(channel, pageId);
                victim.contentLock().writeLock().lock();
                try {
                    victim.page().clear();
                    victim.page().put(page.buffer().duplicate().clear());
                    victim.page().flip();
                } finally {
                    victim.contentLock().writeLock().unlock();
                }
                victim.headerMutex().lock();
                try {
                    victim.completeLoad(page.getPdLsn());
                } finally {
                    victim.headerMutex().unlock();
                }
            } catch (IOException e) {
                partLock.lock();
                bufferTable.remove(key);
                partLock.unlock();
                boolean droppedToZero;
                victim.headerMutex().lock();
                try {
                    victim.abortLoad();
                    droppedToZero = victim.unpin(); // release evictor's bindForLoad pin
                } finally {
                    victim.headerMutex().unlock();
                }
                if (droppedToZero) clockSweep.notifyUnpin();
                throw e;
            }

            return victim;
        }
    }

    /**
     * Fetches a frame for a newly allocated page (no disk read needed).
     */
    public BufferDescriptor fetchNewPage(int fileId, int pageId) throws IOException {
        if (!channels.containsKey(fileId)) {
            throw new StorageException("File not registered for fileId=" + fileId);
        }

        long key = BufferTable.key(fileId, pageId);

        // Check for existing entry (should not happen for genuinely new pages).
        ReentrantLock partLock = bufferTable.lockFor(key);
        partLock.lock();
        try {
            Integer frameId = bufferTable.lookup(key);
            if (frameId != null) {
                BufferDescriptor d = descriptors[frameId];
                d.headerMutex().lock();
                try {
                    d.pinOnHit();
                } finally {
                    d.headerMutex().unlock();
                }
                hits.incrementAndGet();
                return d;
            }
        } finally {
            partLock.unlock();
        }

        while (true) {
            int victimFrameId = clockSweep.selectVictim(descriptors);
            BufferDescriptor victim = descriptors[victimFrameId];

            boolean victimHasPage;
            victim.headerMutex().lock();
            try {
                victimHasPage = victim.valid();
            } finally {
                victim.headerMutex().unlock();
            }

            if (victimHasPage) {
                boolean evicted = evictFrame(victim);
                if (!evicted) {
                    victim.headerMutex().lock();
                    try {
                        victim.unpin();
                    } finally {
                        victim.headerMutex().unlock();
                    }
                    clockSweep.notifyUnpin();
                    continue;
                }
                evictions.incrementAndGet();
            }

            victim.headerMutex().lock();
            try {
                victim.page().clear();
                victim.page().limit(Constants.PAGE_SIZE);
                victim.bindNewPage(fileId, pageId);
            } finally {
                victim.headerMutex().unlock();
            }

            partLock.lock();
            Integer existing = bufferTable.lookup(key);
            if (existing != null) {
                partLock.unlock();
                victim.headerMutex().lock();
                try {
                    victim.releaseVictimClaim();
                } finally {
                    victim.headerMutex().unlock();
                }
                clockSweep.notifyUnpin();
                BufferDescriptor other = descriptors[existing];
                other.headerMutex().lock();
                try {
                    other.pinOnHit();
                } finally {
                    other.headerMutex().unlock();
                }
                hits.incrementAndGet();
                return other;
            }
            misses.incrementAndGet();
            bufferTable.insert(key, victimFrameId);
            partLock.unlock();

            return victim;
        }
    }

    /**
     * Unpins a buffer frame.
     *
     * If dirty is true, marks the page dirty (bumps usageCount so hot pages survive
     * longer). Signals the ClockSweep condition when pinCount drops to zero so
     * blocked victim selectors wake immediately.
     */
    public void unpinPage(BufferDescriptor frame, boolean dirty) {
        boolean droppedToZero;
        frame.headerMutex().lock();
        try {
            if (dirty) {
                frame.markDirty();
            }
            droppedToZero = frame.unpin();
        } finally {
            frame.headerMutex().unlock();
        }
        if (droppedToZero) {
            clockSweep.notifyUnpin();
        }
    }

    // ==================== Flush operations ====================

    /**
     * Flushes a specific frame to disk if dirty.
     * Enforces WAL-before-data: flushes WAL up to frame.pdLsn before writing.
     */
    public void flushFrame(int frameId, FileChannel channel) throws IOException {
        BufferDescriptor frame = descriptors[frameId];
        boolean isDirty;
        long lsn;
        frame.headerMutex().lock();
        try {
            isDirty = frame.dirty();
            lsn = frame.pdLsn();
        } finally {
            frame.headerMutex().unlock();
        }

        if (!isDirty) {
            return;
        }

        if (xlogWriter != null && lsn > 0) {
            xlogWriter.flushUpTo(lsn);
        }

        frame.contentLock().readLock().lock();
        try {
            Page page = Page.Factory.wrap(frame.pageId(), frame.page().duplicate().clear());
            page.setPdLsn(lsn);
            PageIO.writePage(channel, page);
        } finally {
            frame.contentLock().readLock().unlock();
        }

        frame.headerMutex().lock();
        try {
            frame.markClean();
        } finally {
            frame.headerMutex().unlock();
        }
    }

    public void flushAllForFile(int fileId) throws IOException {
        FileChannel channel = channels.get(fileId);
        if (channel == null) {
            return;
        }
        for (int i = 0; i < frameCount; i++) {
            BufferDescriptor frame = descriptors[i];
            boolean shouldFlush;
            frame.headerMutex().lock();
            try {
                shouldFlush = frame.dirty() && frame.fileId() == fileId;
            } finally {
                frame.headerMutex().unlock();
            }
            if (shouldFlush) {
                flushFrame(i, channel);
            }
        }
    }

    public void flushAllDirty() throws IOException {
        for (int i = 0; i < frameCount; i++) {
            BufferDescriptor frame = descriptors[i];
            int fid;
            boolean isDirty;
            frame.headerMutex().lock();
            try {
                fid = frame.fileId();
                isDirty = frame.dirty();
            } finally {
                frame.headerMutex().unlock();
            }
            if (isDirty && fid != 0) {
                FileChannel channel = channels.get(fid);
                if (channel != null) {
                    flushFrame(i, channel);
                }
            }
        }
    }

    // ==================== Checkpoint ====================

    public record CheckpointResult(long checkpointLsn, int flushedPages) {}

    public CheckpointResult checkpoint(ControlFile controlFile) throws IOException {
        if (xlogWriter == null) {
            throw new IllegalStateException("XLogWriter not set - cannot perform checkpoint");
        }

        int flushedPages = 0;
        for (int i = 0; i < frameCount; i++) {
            BufferDescriptor frame = descriptors[i];
            int fid;
            boolean isDirty;
            frame.headerMutex().lock();
            try {
                fid = frame.fileId();
                isDirty = frame.dirty();
            } finally {
                frame.headerMutex().unlock();
            }
            if (isDirty && fid != 0) {
                FileChannel channel = channels.get(fid);
                if (channel != null) {
                    flushFrame(i, channel);
                    flushedPages++;
                }
            }
        }

        long checkpointLsn = xlogWriter.currentLsn();
        byte[] payload = buildCheckpointPayload(checkpointLsn);
        long recordLsn = xlogWriter.append(
            XLogRecord.RMGR_XLOG,
            XLogResourceManager.CHECKPOINT,
            0, 0, 0,
            payload
        );

        xlogWriter.flushUpTo(recordLsn);
        controlFile.updateCheckpointLsn(checkpointLsn);

        for (BufferDescriptor frame : descriptors) {
            frame.headerMutex().lock();
            try {
                frame.setNeedsFullPageWrite(true);
            } finally {
                frame.headerMutex().unlock();
            }
        }

        return new CheckpointResult(checkpointLsn, flushedPages);
    }

    private byte[] buildCheckpointPayload(long redoLsn) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        dos.writeLong(redoLsn);
        dos.flush();
        return baos.toByteArray();
    }

    // ==================== Lifecycle ====================

    @Override
    public void close() throws IOException {
        flushAllDirty();
        for (FileChannel channel : channels.values()) {
            try { channel.close(); } catch (IOException ignored) {}
        }
        channels.clear();
    }

    public void closeWithoutFlush() throws IOException {
        for (FileChannel channel : channels.values()) {
            try { channel.close(); } catch (IOException ignored) {}
        }
        channels.clear();
    }

    // ==================== Statistics ====================

    public int frameCount() { return frameCount; }

    public long hits() { return hits.get(); }

    public long misses() { return misses.get(); }

    public double hitRate() {
        long total = hits.get() + misses.get();
        return total == 0 ? 0.0 : (double) hits.get() / total * 100.0;
    }

    public int pinnedCount() {
        int count = 0;
        for (BufferDescriptor frame : descriptors) {
            frame.headerMutex().lock();
            try {
                if (frame.pinCount() > 0) count++;
            } finally {
                frame.headerMutex().unlock();
            }
        }
        return count;
    }

    public int dirtyCount() {
        int count = 0;
        for (BufferDescriptor frame : descriptors) {
            frame.headerMutex().lock();
            try {
                if (frame.dirty()) count++;
            } finally {
                frame.headerMutex().unlock();
            }
        }
        return count;
    }

    public int usedFrames() {
        int count = 0;
        for (BufferDescriptor frame : descriptors) {
            frame.headerMutex().lock();
            try {
                if (frame.fileId() != 0) count++;
            } finally {
                frame.headerMutex().unlock();
            }
        }
        return count;
    }

    public long ioWaits() { return ioWaits.get(); }

    public long evictions() { return evictions.get(); }

    public String statsString() {
        return String.format(
            "frames=%d  pinned=%d  dirty=%d  hits=%d  misses=%d  hit-rate=%.1f%%  " +
            "partitions=%d  io-waits=%d  evictions=%d",
            frameCount, pinnedCount(), dirtyCount(), hits(), misses(), hitRate(),
            BufferTable.N_PARTITIONS, ioWaits(), evictions()
        );
    }

    // ==================== Internal helpers ====================

    /**
     * Evicts a victim frame that holds an old page.
     *
     * Protocol:
     *   1. Acquire old partition lock → acquire victim's headerMutex
     *   2. If pinCount > 1: another thread pinned it after we claimed it — back off
     *   3. Remove old key from hash
     *   4. Release both locks
     *   5. Flush dirty page if needed (outside all latches)
     *
     * Returns true if eviction succeeded; false if the victim was re-pinned.
     *
     * The victim must have pinCount=1 (claimed by selectVictim) before this call.
     * Partition lock > headerMutex ordering is respected throughout.
     */
    private boolean evictFrame(BufferDescriptor victim) throws IOException {
        int oldFileId;
        int oldPageId;

        victim.headerMutex().lock();
        try {
            oldFileId = victim.fileId();
            oldPageId = victim.pageId();
        } finally {
            victim.headerMutex().unlock();
        }

        long oldKey = BufferTable.key(oldFileId, oldPageId);
        ReentrantLock oldPartLock = bufferTable.lockFor(oldKey);

        oldPartLock.lock();
        try {
            victim.headerMutex().lock();
            try {
                if (victim.pinCount() > 1) {
                    // Another thread pinned the frame via the old key — we cannot evict.
                    return false;
                }
                // Only we hold a pin. Remove the old hash entry while holding both locks
                // so that no new thread can pin via the old key after we remove it.
                bufferTable.remove(oldKey);
            } finally {
                victim.headerMutex().unlock();
            }
        } finally {
            oldPartLock.unlock();
        }

        // Flush dirty page outside all latches (WAL-before-data rule applied inside flushFrame).
        boolean isDirty;
        victim.headerMutex().lock();
        try {
            isDirty = victim.dirty();
        } finally {
            victim.headerMutex().unlock();
        }

        if (isDirty) {
            FileChannel channel = channels.get(oldFileId);
            if (channel != null) {
                flushFrame(victim.frameId(), channel);
            }
        }

        return true;
    }

    /**
     * Waits for any in-flight IO on a frame to complete.
     * Called after pinning on a HIT where ioInProgress might be set.
     */
    private void waitForIo(BufferDescriptor d) {
        d.headerMutex().lock();
        try {
            if (d.ioInProgress()) {
                ioWaits.incrementAndGet();
                while (d.ioInProgress()) {
                    try {
                        d.ioCondition().await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new StorageException("Interrupted while waiting for page IO");
                    }
                }
            }
        } finally {
            d.headerMutex().unlock();
        }
    }

    /** Package-private: used by tests. */
    BufferDescriptor descriptor(int frameId) {
        return descriptors[frameId];
    }

    /** Package-private: kept for legacy callers that flush directly. */
    void removeFromPageTable(int fileId, int pageId) {
        long key = BufferTable.key(fileId, pageId);
        ReentrantLock partLock = bufferTable.lockFor(key);
        partLock.lock();
        try {
            bufferTable.remove(key);
        } finally {
            partLock.unlock();
        }
    }
}
