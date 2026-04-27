package com.coredb.txn;

import com.coredb.util.Constants;
import com.coredb.util.CorruptionException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

/**
 * File-backed implementation of ClogManager (pg_xact equivalent).
 *
 * <p>Stores transaction status (in-progress, committed, aborted) using 2 bits per XID.
 * This compact representation fits 4 XIDs per byte, so 1 MB of clog covers 4 million XIDs.
 * Matches PostgreSQL's clog.c design.</p>
 *
 * <p>Status encoding:</p>
 * <ul>
 *   <li>00 = IN_PROGRESS</li>
 *   <li>01 = COMMITTED</li>
 *   <li>10 = ABORTED</li>
 *   <li>11 = reserved</li>
 * </ul>
 *
 * <p>Bit layout per byte: XID (n) uses bits (n % 4) * 2, XID (n+1) uses next 2 bits, etc.</p>
 */
final class FileClogManager implements ClogManager {

    // Status encoding (2 bits per XID)
    private static final int STATUS_IN_PROGRESS = 0;
    private static final int STATUS_COMMITTED   = 1;
    private static final int STATUS_ABORTED     = 2;

    // File format
    static final int FILE_MAGIC = 0x58414354; // "XACT"
    static final int FILE_VERSION = 1;
    static final int HEADER_SIZE = 16;

    // XID constants
    private static final int XID_INVALID = Constants.INVALID_XID;
    private static final int XID_BOOTSTRAP = Constants.BOOTSTRAP_XID;

    private final Path filePath;
    private final FileChannel channel;

    // In-memory cache: 2 bits per XID, packed 4 per byte
    private byte[] xactCache;

    // Dirty range tracking for efficient flush
    private int dirtyStart = Integer.MAX_VALUE;
    private int dirtyEnd = -1;

    private FileClogManager(Path filePath, FileChannel channel, byte[] xactCache) {
        this.filePath = filePath;
        this.channel = channel;
        this.xactCache = xactCache;
    }

    /**
     * Creates a fresh pg_xact file.
     *
     * @param dataDir the data directory
     * @return a new FileClogManager instance
     * @throws IOException if file creation fails
     */
    static FileClogManager create(Path dataDir) throws IOException {
        Path globalDir = dataDir.resolve("global");
        Files.createDirectories(globalDir);
        Path filePath = globalDir.resolve("pg_xact");

        // Start with empty cache - will grow as XIDs are allocated
        byte[] cache = new byte[0];

        FileChannel channel = FileChannel.open(filePath,
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.READ, StandardOpenOption.WRITE);

        // Write header
        ByteBuffer header = ByteBuffer.allocate(HEADER_SIZE);
        header.putInt(FILE_MAGIC);
        header.putInt(FILE_VERSION);
        header.putInt(0); // initial entry count (no XIDs tracked yet)
        header.putInt(0); // reserved
        header.flip();
        channel.write(header, 0);
        channel.force(true);

        return new FileClogManager(filePath, channel, cache);
    }

    /**
     * Opens an existing pg_xact file.
     *
     * @param dataDir the data directory
     * @return a FileClogManager instance with state loaded from disk
     * @throws IOException if file cannot be read
     * @throws CorruptionException if magic or version check fails
     */
    static FileClogManager open(Path dataDir) throws IOException {
        Path filePath = dataDir.resolve("global/pg_xact");

        if (!Files.exists(filePath)) {
            throw new IOException("pg_xact file not found: " + filePath);
        }

        FileChannel channel = FileChannel.open(filePath,
            StandardOpenOption.READ, StandardOpenOption.WRITE);

        // Read and verify header
        ByteBuffer header = ByteBuffer.allocate(HEADER_SIZE);
        int bytesRead = channel.read(header, 0);
        if (bytesRead < HEADER_SIZE) {
            channel.close();
            throw new CorruptionException("pg_xact file too small (header incomplete)");
        }
        header.flip();

        int magic = header.getInt();
        if (magic != FILE_MAGIC) {
            channel.close();
            throw new CorruptionException(String.format(
                "pg_xact magic mismatch: expected 0x%08X, got 0x%08X", FILE_MAGIC, magic));
        }

        int version = header.getInt();
        if (version != FILE_VERSION) {
            channel.close();
            throw new CorruptionException(String.format(
                "pg_xact version mismatch: expected %d, got %d", FILE_VERSION, version));
        }

        int entryCount = header.getInt();

        // Load cached bytes from file body
        byte[] cache;
        try {
            long fileSize = channel.size();
            int bodySize = (int) (fileSize - HEADER_SIZE);
            cache = new byte[bodySize];

            if (bodySize > 0) {
                ByteBuffer bodyBuffer = ByteBuffer.wrap(cache);
                while (bodyBuffer.hasRemaining()) {
                    long position = HEADER_SIZE + bodyBuffer.position();
                    int n = channel.read(bodyBuffer, position);
                    if (n <= 0) {
                        break;
                    }
                }
            }

            // Verify entry count matches body size
            int expectedBodySize = (entryCount + 3) / 4; // round up to whole bytes
            if (cache.length < expectedBodySize) {
                // Extend cache to match entry count
                cache = Arrays.copyOf(cache, expectedBodySize);
            }
        } catch (IOException e) {
            channel.close();
            throw e;
        }

        return new FileClogManager(filePath, channel, cache);
    }

    @Override
    public synchronized Status getStatus(int xid) {
        if (xid < 0) {
            throw new IllegalArgumentException("Cannot query status of negative XID: " + xid);
        }
        if (xid == XID_INVALID) {
            throw new IllegalArgumentException("Cannot query status of INVALID_XID (0)");
        }
        if (xid == XID_BOOTSTRAP || xid == Constants.FROZEN_XID) {
            return Status.COMMITTED;
        }

        int byteIndex = xid / 4;
        if (byteIndex >= xactCache.length) {
            return Status.IN_PROGRESS; // XID beyond tracked range
        }

        int bitShift = (xid % 4) * 2;
        int statusCode = (xactCache[byteIndex] >> bitShift) & 0x3;

        return switch (statusCode) {
            case STATUS_COMMITTED -> Status.COMMITTED;
            case STATUS_ABORTED -> Status.ABORTED;
            default -> Status.IN_PROGRESS;
        };
    }

    @Override
    public synchronized void setCommitted(int xid) {
        if (xid < 0 || xid == XID_INVALID || xid == XID_BOOTSTRAP) {
            throw new IllegalArgumentException("Cannot set status for INVALID_XID or BOOTSTRAP_XID");
        }
        setStatus(xid, STATUS_COMMITTED);
    }

    @Override
    public synchronized void setAborted(int xid) {
        if (xid < 0 || xid == XID_INVALID || xid == XID_BOOTSTRAP) {
            throw new IllegalArgumentException("Cannot set status for INVALID_XID or BOOTSTRAP_XID");
        }
        setStatus(xid, STATUS_ABORTED);
    }

    private synchronized void setStatus(int xid, int statusCode) {
        int byteIndex = xid / 4;

        // Grow cache if needed
        if (byteIndex >= xactCache.length) {
            int newSize = byteIndex + 1;
            xactCache = Arrays.copyOf(xactCache, newSize);
        }

        int bitShift = (xid % 4) * 2;
        int mask = 0x3 << bitShift;

        // Clear old status, set new status
        xactCache[byteIndex] = (byte) ((xactCache[byteIndex] & ~mask) | (statusCode << bitShift));

        // Track dirty range
        dirtyStart = Math.min(dirtyStart, byteIndex);
        dirtyEnd = Math.max(dirtyEnd, byteIndex);
    }

    @Override
    public synchronized void flush() throws IOException {
        if (dirtyStart > dirtyEnd) {
            return; // Nothing dirty
        }

        // Update entry count in header
        int entryCount = xactCache.length * 4;
        ByteBuffer header = ByteBuffer.allocate(HEADER_SIZE);
        header.putInt(FILE_MAGIC);
        header.putInt(FILE_VERSION);
        header.putInt(entryCount);
        header.putInt(0); // reserved
        header.flip();
        channel.write(header, 0);

        // Write dirty range of cache
        ByteBuffer dirtyBuffer = ByteBuffer.wrap(xactCache, dirtyStart, dirtyEnd - dirtyStart + 1);
        channel.write(dirtyBuffer, HEADER_SIZE + dirtyStart);
        channel.force(true);

        dirtyStart = Integer.MAX_VALUE;
        dirtyEnd = -1;
    }

    @Override
    public synchronized int entryCount() {
        return xactCache.length * 4;
    }

    @Override
    public synchronized ClogManager.Stats getStats() {
        int committed = 0;
        int aborted = 0;
        int inProgress = 0;

        for (int xid = Constants.FIRST_NORMAL_XID; xid < entryCount(); xid++) {
            Status status = getStatus(xid);
            switch (status) {
                case COMMITTED -> committed++;
                case ABORTED -> aborted++;
                case IN_PROGRESS -> inProgress++;
            }
        }

        return new ClogManager.Stats(entryCount(), inProgress, committed, aborted, xactCache.length);
    }

    @Override
    public synchronized void close() throws IOException {
        flush();
        channel.close();
    }
}
