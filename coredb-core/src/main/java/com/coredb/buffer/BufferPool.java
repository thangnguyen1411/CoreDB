package com.coredb.buffer;

import com.coredb.page.Page;
import com.coredb.page.PageIO;
import com.coredb.util.Constants;
import com.coredb.util.StorageException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
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
 * - File registry: manages FileChannels for multiple table files
 *
 * PostgreSQL parallel: bufmgr.c, freelist.c (StrategyGetBuffer)
 */
public final class BufferPool implements AutoCloseable {

    private final int frameCount;
    private final BufferDescriptor[] descriptors;
    private final Map<Long, Integer> pageTable; // (fileId, pageId) -> frameId
    private final Map<Integer, FileChannel> channels; // fileId -> FileChannel

    // Statistics
    private long hits;
    private long misses;

    // Clock-sweep hand for eviction
    private int sweepHand;

    /**
     * Creates a buffer pool with the specified number of frames.
     *
     * @param frameCount number of 8KB frames (default: 1024 for 8MB pool)
     */
    public BufferPool(int frameCount) {
        this.frameCount = frameCount;
        this.descriptors = new BufferDescriptor[frameCount];
        this.pageTable = new HashMap<>(frameCount * 2);
        this.channels = new HashMap<>();
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
     * Registers a file with the buffer pool.
     * Opens a FileChannel that will be used for I/O operations.
     *
     * @param fileId   the unique file identifier (may be table OID or derived from it)
     * @param filePath the path to the file
     * @throws IOException if the file cannot be opened
     */
    public void registerFile(int fileId, Path filePath) throws IOException {
        if (channels.containsKey(fileId)) {
            return; // Already registered
        }
        FileChannel channel = FileChannel.open(filePath,
                StandardOpenOption.READ, StandardOpenOption.WRITE);
        channels.put(fileId, channel);
    }

    /**
     * Unregisters a file and closes its channel.
     * Flushes any dirty pages for this file before closing.
     *
     * @param fileId the unique file identifier
     * @throws IOException if closing fails
     */
    public void unregisterFile(int fileId) throws IOException {
        flushAllForFile(fileId);
        FileChannel channel = channels.remove(fileId);
        if (channel != null) {
            channel.close();
        }
        // Remove any pages for this file from the page table
        pageTable.entrySet().removeIf(entry -> {
            int id = (int) (entry.getKey() >> 32);
            return id == fileId;
        });
    }

    /**
     * Fetches a page from the buffer pool.
     *
     * Hit: returns existing pinned buffer.
     * Miss: allocates frame, reads from disk via registered FileChannel, pins it.
     *
     * @param fileId the unique file identifier
     * @param pageId the page ID within the file
     * @return BufferDescriptor for the page (already pinned)
     * @throws IOException if disk read fails
     * @throws StorageException if pool is full, all frames pinned, or file not registered
     */
    public BufferDescriptor fetchPage(int fileId, int pageId) throws IOException {
        FileChannel channel = channels.get(fileId);
        if (channel == null) {
            throw new StorageException("File not registered for fileId=" + fileId);
        }

        long key = BufferDescriptor.key(fileId, pageId);

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

        // Find a free frame (uses clock-sweep eviction if needed)
        BufferDescriptor victim = findFreeFrame(channel);
        if (victim == null) {
            throw new StorageException("Buffer pool full (" + frameCount + " frames). ");
        }

        // Read page from disk into the frame
        Page page = PageIO.readPage(channel, pageId);
        victim.page().clear();
        victim.page().put(page.buffer().duplicate().clear());
        victim.page().flip();

        // Set up the frame — usageCount = 1 on first load
        victim.bind(fileId, pageId);
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
     * @param fileId the unique file identifier
     * @param pageId the new page ID
     * @return BufferDescriptor for the new page (already pinned, dirty)
     * @throws StorageException if pool is full or file not registered
     * @throws IOException if flushing a dirty victim fails
     */
    public BufferDescriptor fetchNewPage(int fileId, int pageId) throws IOException {
        FileChannel channel = channels.get(fileId);
        if (channel == null) {
            throw new StorageException("File not registered for fileId=" + fileId);
        }

        long key = BufferDescriptor.key(fileId, pageId);

        Integer frameId = pageTable.get(key);
        if (frameId != null) {
            // Shouldn't happen for new pages, but handle it
            BufferDescriptor frame = descriptors[frameId];
            frame.pin();
            hits++;
            return frame;
        }

        misses++;

        BufferDescriptor victim = findFreeFrame(channel);
        if (victim == null) {
            throw new StorageException("Buffer pool full (" + frameCount + " frames).");
        }

        // Clear the frame for the new page
        victim.page().clear();
        victim.page().limit(Constants.PAGE_SIZE);

        victim.bind(fileId, pageId);
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
     * Flushes all dirty frames for a specific file to disk.
     *
     * @param fileId the file identifier whose dirty pages should be flushed
     * @throws IOException if any write fails
     */
    public void flushAllForFile(int fileId) throws IOException {
        FileChannel channel = channels.get(fileId);
        if (channel == null) {
            return; // No file registered for this id
        }
        for (int i = 0; i < frameCount; i++) {
            BufferDescriptor frame = descriptors[i];
            if (frame.dirty() && frame.fileId() == fileId) {
                flushFrame(i, channel);
            }
        }
    }

    /**
     * Flushes all dirty frames to disk across all registered files.
     *
     * @throws IOException if any write fails
     */
    public void flushAllDirty() throws IOException {
        for (int i = 0; i < frameCount; i++) {
            BufferDescriptor frame = descriptors[i];
            if (frame.dirty() && frame.fileId() != 0) {
                FileChannel channel = channels.get(frame.fileId());
                if (channel != null) {
                    flushFrame(i, channel);
                }
            }
        }
    }

    /**
     * Closes the buffer pool and all registered file channels.
     * Flushes all dirty pages before closing.
     */
    @Override
    public void close() throws IOException {
        // Flush all dirty pages first
        flushAllDirty();

        // Close all file channels
        for (FileChannel channel : channels.values()) {
            try {
                channel.close();
            } catch (IOException e) {
                // Continue closing others
            }
        }
        channels.clear();
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
            if (frame.fileId() != 0) {
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
     * If no free frames, uses clock-sweep eviction to select a victim.
     * 
     * @param channel FileChannel for flushing dirty victims
     * @return a free BufferDescriptor (possibly after eviction)
     * @throws IOException if flushing a dirty victim fails
     */
    private BufferDescriptor findFreeFrame(FileChannel channel) throws IOException {
        // First: look for completely free (unbound) frames
        for (BufferDescriptor frame : descriptors) {
            if (frame.fileId() == 0 && frame.pinCount() == 0) {
                return frame;
            }
        }
        
        // No free frames - use clock-sweep eviction
        return selectVictim(channel);
    }

    /**
     * Clock-sweep eviction algorithm (PostgreSQL: StrategyGetBuffer/freelist.c).
     *
     * Sweeps through frames looking for an unpinned victim:
     * - If usageCount == 0: select as victim (flush if dirty)
     * - Otherwise: decrement usageCount and continue
     *
     * After frameCount * 2 attempts, throws if no evictable frame found.
     *
     * @param newFileChannel FileChannel for the new file being loaded (fallback if victim's channel unknown)
     * @return evicted frame ready for reuse
     * @throws IOException if flushing fails
     * @throws StorageException if all frames are pinned
     */
    private BufferDescriptor selectVictim(FileChannel newFileChannel) throws IOException {
        for (int attempts = 0; attempts < frameCount * 2; attempts++) {
            BufferDescriptor frame = descriptors[sweepHand];

            if (frame.pinCount() == 0) {
                if (frame.usageCount() == 0) {
                    // Found victim - evict it
                    if (frame.dirty()) {
                        // Use the correct channel for this frame's file
                        FileChannel flushChannel = channels.get(frame.fileId());
                        if (flushChannel == null) {
                            flushChannel = newFileChannel; // Fallback
                        }
                        flushFrame(frame.frameId(), flushChannel);
                    }
                    // Remove from page table
                    removeFromPageTable(frame.fileId(), frame.pageId());
                    // Reset for reuse
                    frame.reset();
                    return frame;
                }
                // Decrement usage count (gives hot pages more chances)
                frame.decrementUsage();
            }

            sweepHand = (sweepHand + 1) % frameCount;
        }

        throw new StorageException(
            "No evictable frame found - all " + frameCount + " frames pinned?"
        );
    }

    /**
     * Removes a frame from the page table (called during eviction in 7B).
     */
    void removeFromPageTable(int fileId, int pageId) {
        pageTable.remove(BufferDescriptor.key(fileId, pageId));
    }

    /**
     * Gets the frame descriptor by ID.
     */
    BufferDescriptor descriptor(int frameId) {
        return descriptors[frameId];
    }
}
