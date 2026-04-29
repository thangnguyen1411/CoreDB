package com.coredb.wal;

import com.coredb.util.Constants;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Append-only WAL writer, safe for concurrent use by multiple threads.
 *
 * <p>Concurrency design:
 * <ul>
 *   <li>{@code insertLock} serialises LSN assignment and file writes. It is the leaf latch
 *       in the global latch order — never acquire another latch while holding it.</li>
 *   <li>{@code flushedLsn} is an {@link AtomicLong}. Readers check it without taking any
 *       lock (fast path). Writers update it only under {@code flushMutex}.</li>
 *   <li>{@code flushMutex} serialises {@code channel.force()} calls so that N concurrent
 *       callers of {@link #flushUpTo} share one fsync per "round" rather than each
 *       doing their own.</li>
 * </ul>
 *
 * <p>WAL file format:
 * <pre>
 * Header (16 bytes):
 *   bytes 0-3:   magic 0x57414C00 ("WAL\0")
 *   bytes 4-7:   format version (1)
 *   bytes 8-15:  reserved (zero)
 *
 * Records: appended sequentially starting at offset 16
 * </pre>
 */
public final class XLogWriter implements AutoCloseable {

    private static final int WAL_HEADER_SIZE = 16;
    private static final int WAL_FORMAT_VERSION = 1;

    public static final long INVALID_LSN = 0;
    public static final long FIRST_LSN = WAL_HEADER_SIZE;

    private final FileChannel channel;
    private final Path walPath;

    // Serialises LSN assignment + file writes; leaf latch in global order.
    private final ReentrantLock insertLock = new ReentrantLock();
    // Both fields below are only read/written under insertLock.
    private long currentLsn;
    private long prevLsn;

    // Read without any lock (fast path). Written only under flushMutex.
    private final AtomicLong flushedLsn = new AtomicLong(INVALID_LSN);

    // Serialises channel.force() so concurrent callers share one fsync per round.
    private final ReentrantLock flushMutex = new ReentrantLock();
    private final Condition flushCondition;

    // Package-private for tests to verify fsync count.
    final AtomicInteger fsyncCount = new AtomicInteger(0);

    /**
     * Opens or creates the WAL file at the given path.
     *
     * @param walPath path to the WAL file (typically $DATA_DIR/global/pg_wal)
     * @return a new XLogWriter
     * @throws IOException if the file cannot be opened or created
     */
    public static XLogWriter open(Path walPath) throws IOException {
        boolean fileExists = Files.exists(walPath);

        FileChannel channel = FileChannel.open(
            walPath,
            StandardOpenOption.READ,
            StandardOpenOption.WRITE,
            StandardOpenOption.CREATE
        );

        long fileSize = channel.size();

        if (fileExists && fileSize > 0) {
            if (fileSize < WAL_HEADER_SIZE) {
                throw new IOException("WAL file too small: " + fileSize);
            }
            ByteBuffer header = ByteBuffer.allocate(WAL_HEADER_SIZE);
            channel.position(0);
            int read = channel.read(header);
            if (read != WAL_HEADER_SIZE) {
                throw new IOException("Failed to read WAL header");
            }
            header.flip();
            header.order(ByteOrder.BIG_ENDIAN);

            int magic = header.getInt();
            if (magic != Constants.WAL_FILE_MAGIC) {
                throw new IOException(
                    String.format("Invalid WAL magic: expected 0x%08X, got 0x%08X",
                        Constants.WAL_FILE_MAGIC, magic));
            }

            int version = header.getInt();
            if (version != WAL_FORMAT_VERSION) {
                throw new IOException("Unsupported WAL version: " + version);
            }

            long prevLsn = scanForPrevLsn(channel, fileSize);
            return new XLogWriter(channel, walPath, fileSize, prevLsn);
        } else {
            ByteBuffer header = ByteBuffer.allocate(WAL_HEADER_SIZE);
            header.order(ByteOrder.BIG_ENDIAN);
            header.putInt(Constants.WAL_FILE_MAGIC);
            header.putInt(WAL_FORMAT_VERSION);
            header.putLong(0L);
            header.flip();

            channel.position(0);
            int written = channel.write(header);
            if (written != WAL_HEADER_SIZE) {
                throw new IOException("Failed to write WAL header");
            }
            channel.force(false);

            return new XLogWriter(channel, walPath, FIRST_LSN, INVALID_LSN);
        }
    }

    private XLogWriter(FileChannel channel, Path walPath, long currentLsn, long prevLsn) {
        this.channel = channel;
        this.walPath = walPath;
        this.currentLsn = currentLsn;
        this.prevLsn = prevLsn;
        this.flushCondition = flushMutex.newCondition();
    }

    /**
     * Appends a record to the WAL and returns the LSN where it was written.
     *
     * <p>The record is written to the OS buffer but not fsynced. Call
     * {@link #flushUpTo(long)} with the returned LSN before the page it covers
     * can be evicted to disk.
     *
     * @param resourceManager resource manager ID
     * @param info            operation code (high bit = full-page write flag)
     * @param xid             writing transaction ID
     * @param tableOid        target table OID
     * @param pageId          target page within that file
     * @param data            payload bytes
     * @return the LSN assigned to this record
     * @throws IOException if the write fails
     */
    public long append(byte resourceManager, byte info, int xid, int tableOid, int pageId,
                       byte[] data) throws IOException {
        insertLock.lock();
        try {
            long lsn = currentLsn;
            XLogRecord record = XLogRecord.create(lsn, xid, prevLsn, resourceManager, info, tableOid, pageId, data);
            byte[] recordBytes = record.toBytes();

            ByteBuffer buf = ByteBuffer.wrap(recordBytes);
            channel.position(lsn);
            int written = channel.write(buf);
            if (written != recordBytes.length) {
                throw new IOException("Partial WAL write: expected " + recordBytes.length + ", wrote " + written);
            }

            currentLsn = lsn + recordBytes.length;
            prevLsn = lsn;
            return lsn;
        } finally {
            insertLock.unlock();
        }
    }

    /**
     * Ensures all records up to {@code targetLsn} are durably written to disk.
     *
     * <p>Concurrent callers requesting the same or overlapping ranges share a
     * single {@code channel.force()} call: the thread that wins the
     * {@code flushMutex} does the fsync; the others see the updated
     * {@code flushedLsn} and return without a redundant fsync.
     *
     * @param targetLsn the LSN that must be on disk before this method returns
     * @throws IOException if the fsync fails
     */
    public void flushUpTo(long targetLsn) throws IOException {
        if (flushedLsn.get() >= targetLsn) {
            return;
        }
        flushMutex.lock();
        try {
            if (flushedLsn.get() >= targetLsn) {
                return;
            }
            long toFlush = snapshotCurrentLsn();
            channel.force(false);
            fsyncCount.incrementAndGet();
            flushedLsn.set(toFlush);
            flushCondition.signalAll();
        } finally {
            flushMutex.unlock();
        }
    }

    /**
     * Returns the next LSN that will be assigned to an appended record.
     */
    public long currentLsn() {
        insertLock.lock();
        try {
            return currentLsn;
        } finally {
            insertLock.unlock();
        }
    }

    /**
     * Returns the highest LSN that has been durably flushed to disk.
     *
     * <p>Safe to call without any lock.
     */
    public long flushedLsn() {
        return flushedLsn.get();
    }

    /**
     * Returns the path to the WAL file.
     */
    public Path walPath() {
        return walPath;
    }

    @Override
    public void close() throws IOException {
        long lsn = currentLsn();
        flushUpTo(lsn);
        channel.close();
    }

    // Called under flushMutex. Acquiring insertLock here is safe: the only
    // latch-order direction in this class is flushMutex → insertLock.
    // append() holds insertLock and never touches flushMutex, so no cycle.
    private long snapshotCurrentLsn() {
        insertLock.lock();
        try {
            return currentLsn;
        } finally {
            insertLock.unlock();
        }
    }

    private static long scanForPrevLsn(FileChannel channel, long fileSize) throws IOException {
        if (fileSize <= FIRST_LSN) {
            return INVALID_LSN;
        }

        long scanPos = FIRST_LSN;
        long lastLsn = INVALID_LSN;

        while (scanPos < fileSize) {
            ByteBuffer header = ByteBuffer.allocate(12);
            channel.position(scanPos);
            int read = channel.read(header);
            if (read < 12) {
                break;
            }
            header.flip();
            header.order(ByteOrder.BIG_ENDIAN);

            long recordLsn = header.getLong();
            int totalLength = header.getInt();

            if (totalLength < 40) {
                break;
            }

            lastLsn = recordLsn;
            scanPos += totalLength;

            if (scanPos > fileSize) {
                break;
            }
        }

        return lastLsn;
    }
}
