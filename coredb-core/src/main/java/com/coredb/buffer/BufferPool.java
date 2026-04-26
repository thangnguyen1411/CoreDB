package com.coredb.buffer;

import com.coredb.page.Page;
import com.coredb.page.PageIO;
import com.coredb.util.Constants;
import com.coredb.util.StorageException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.util.HashMap;
import java.util.Map;

/**
 * BufferPool provides in-memory caching of database pages.
 * 
 * Core concepts:
 * - Frames: fixed number of 8KB buffers (default 1024 = 8MB)
 * - Pins: prevent eviction while a page is in use
 * - Dirty tracking: modified pages must be flushed to disk
 * - Usage count: for clock-sweep eviction
 * 
 * PostgreSQL parallel: bufmgr.c, freelist.c (StrategyGetBuffer)
 */
public final class BufferPool implements AutoCloseable {

    private final int frameCount;
    private final BufferDescriptor[] descriptors;
    private final Map<Long, Integer> pageTable; // (tableOid, pageId) -> frameId
    
    // Statistics
    private long hits;
    private long misses;

    /**
     * Creates a buffer pool with the specified number of frames.
     * 
     * @param frameCount number of 8KB frames (default: 1024 for 8MB pool)
     */
    public BufferPool(int frameCount) {
        this.frameCount = frameCount;
        this.descriptors = new BufferDescriptor[frameCount];
        this.pageTable = new HashMap<>(frameCount * 2);
        this.hits = 0;
        this.misses = 0;

        // Allocate direct ByteBuffer and slice into frames
        // Direct buffers avoid JVM heap copying during I/O
        // 1024 frames * 8192 bytes = 8MB, well within int range
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
        poolBuffer.clear(); // Reset position/limit for safety
    }

    /**
     * Default constructor: 1024 frames = 8MB pool.
     */
    public BufferPool() {
        this(1024);
    }

    /**
     * Fetches a page from the buffer pool.
     * 
     * Hit: returns existing pinned buffer.
     * Miss: allocates frame, reads from disk via FileChannel, pins it.
     * 
     * @param tableOid the table OID (identifies which file)
     * @param pageId the page ID within the table
     * @param channel FileChannel to read from on miss
     * @return BufferDescriptor for the page (already pinned)
     * @throws IOException if disk read fails
     * @throws StorageException if pool is full or all frames pinned
     */
    public BufferDescriptor fetchPage(int tableOid, int pageId, FileChannel channel) throws IOException {
        long key = BufferDescriptor.key(tableOid, pageId);
        
        Integer frameId = pageTable.get(key);
        if (frameId != null) {
            // Hit: page already in pool — bump usage count (hot pages survive longer)
            BufferDescriptor frame = descriptors[frameId];
            frame.pin();
            frame.bumpUsageCount();
            hits++;
            return frame;
        }

        // Miss: need to load from disk
        misses++;
        
        // Find a free frame (no eviction yet)
        BufferDescriptor victim = findFreeFrame();
        if (victim == null) {
            throw new StorageException("Buffer pool full (" + frameCount + " frames). ");
        }

        // Read page from disk into the frame
        Page page = PageIO.readPage(channel, pageId);
        victim.page().clear();
        victim.page().put(page.buffer().duplicate().clear());
        victim.page().flip();

        // Set up the frame — usageCount = 1 on first load
        victim.bind(tableOid, pageId);
        victim.initUsageCount();
        victim.pin();
        
        // Register in page table
        pageTable.put(key, victim.frameId());

        return victim;
    }

    /**
     * Fetches a page for a newly allocated page (no disk read needed).
     * Used when allocating a new page that doesn't exist on disk yet.
     * 
     * @param tableOid the table OID
     * @param pageId the new page ID
     * @return BufferDescriptor for the new page (already pinned, dirty)
     * @throws StorageException if pool is full
     */
    public BufferDescriptor fetchNewPage(int tableOid, int pageId) {
        long key = BufferDescriptor.key(tableOid, pageId);
        
        Integer frameId = pageTable.get(key);
        if (frameId != null) {
            // Shouldn't happen for new pages, but handle it
            BufferDescriptor frame = descriptors[frameId];
            frame.pin();
            hits++;
            return frame;
        }

        misses++;
        
        BufferDescriptor victim = findFreeFrame();
        if (victim == null) {
            throw new StorageException("Buffer pool full (" + frameCount + " frames).");
        }

        // Clear the frame for the new page
        victim.page().clear();
        victim.page().limit(Constants.PAGE_SIZE);
        
        victim.bind(tableOid, pageId);
        victim.pin();
        victim.markDirty(); // New pages are dirty by definition
        
        pageTable.put(key, victim.frameId());
        
        return victim;
    }

    /**
     * Unpins a buffer frame.
     * 
     * @param frame the buffer descriptor to unpin
     * @param dirty true if the page was modified (will be flushed later)
     */
    public void unpinPage(BufferDescriptor frame, boolean dirty) {
        if (dirty) {
            frame.markDirty();
        }
        frame.unpin();
    }

    /**
     * Flushes a specific dirty frame to disk.
     * 
     * @param frameId the frame to flush
     * @param channel the FileChannel to write to
     * @throws IOException if write fails
     */
    public void flushFrame(int frameId, FileChannel channel) throws IOException {
        BufferDescriptor frame = descriptors[frameId];
        if (!frame.dirty()) {
            return;
        }

        // Write the page to disk
        Page page = Page.Factory.wrap(frame.pageId(), frame.page().duplicate().clear());
        PageIO.writePage(channel, page);
        
        // Mark clean (just clear dirty flag, preserve binding for potential reuse)
        frame.markClean();
    }

    /**
     * Flushes all dirty frames to disk.
     * 
     * @param channel the FileChannel to write to
     * @throws IOException if any write fails
     */
    public void flushAllDirty(FileChannel channel) throws IOException {
        for (int i = 0; i < frameCount; i++) {
            BufferDescriptor frame = descriptors[i];
            if (frame.dirty() && frame.tableOid() != 0) {
                flushFrame(i, channel);
            }
        }
    }

    /**
     * Closes the buffer pool.
     * Note: caller is responsible for flushing dirty pages before close.
     * This pool is file-agnostic and cannot flush without a channel.
     */
    @Override
    public void close() {
        int dirty = dirtyCount();
        if (dirty > 0) {
            throw new IllegalStateException(
                "Buffer pool has " + dirty + " dirty frame(s). " +
                "Call flushAllDirty(channel) before close()."
            );
        }
        pageTable.clear();
    }

    // ==================== Statistics ====================

    public int frameCount() {
        return frameCount;
    }

    public long hits() {
        return hits;
    }

    public long misses() {
        return misses;
    }

    public double hitRate() {
        long total = hits + misses;
        return total == 0 ? 0.0 : (double) hits / total * 100.0;
    }

    public int pinnedCount() {
        int count = 0;
        for (BufferDescriptor frame : descriptors) {
            if (frame.pinCount() > 0) {
                count++;
            }
        }
        return count;
    }

    public int dirtyCount() {
        int count = 0;
        for (BufferDescriptor frame : descriptors) {
            if (frame.dirty()) {
                count++;
            }
        }
        return count;
    }

    public int usedFrames() {
        int count = 0;
        for (BufferDescriptor frame : descriptors) {
            if (frame.tableOid() != 0) {
                count++;
            }
        }
        return count;
    }

    /**
     * Returns statistics as a formatted string for the shell.
     */
    public String statsString() {
        return String.format(
            "frames=%d  pinned=%d  dirty=%d  hits=%d  misses=%d  hit-rate=%.1f%%",
            frameCount, pinnedCount(), dirtyCount(), hits, misses, hitRate()
        );
    }

    // ==================== Internal methods ====================

    /**
     * Finds a free (unbound) frame in the pool.
     * 
     * @return a free BufferDescriptor, or null if pool is full
     */
    private BufferDescriptor findFreeFrame() {
        for (BufferDescriptor frame : descriptors) {
            if (frame.tableOid() == 0 && frame.pinCount() == 0) {
                return frame;
            }
        }
        return null;
    }

    /**
     * Removes a frame from the page table (called during eviction in 7B).
     */
    void removeFromPageTable(int tableOid, int pageId) {
        pageTable.remove(BufferDescriptor.key(tableOid, pageId));
    }

    /**
     * Gets the frame descriptor by ID.
     */
    BufferDescriptor descriptor(int frameId) {
        return descriptors[frameId];
    }
}
