package com.coredb.wal;

import com.coredb.util.Constants;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Append-only WAL writer.
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

    // LSN constants
    public static final long INVALID_LSN = 0;
    public static final long FIRST_LSN = WAL_HEADER_SIZE;  // First record starts after header

    private final FileChannel channel;
    private final Path walPath;
    private long currentLsn;
    private long flushedLsn;
    private long prevLsn;

    /**
     * Opens or creates the WAL file at the given path.
     *
     * @param walPath path to the WAL file (typically $DATA_DIR/global/pg_wal)
     * @return a new XLogWriter
     * @throws IOException if file cannot be opened/created
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
            // Existing file: validate header
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

            // Position at end for appending
            long currentLsn = fileSize;
            long prevLsn = scanForPrevLsn(channel, fileSize);
            return new XLogWriter(channel, walPath, currentLsn, currentLsn, prevLsn);
        } else {
            // New file: write header
            ByteBuffer header = ByteBuffer.allocate(WAL_HEADER_SIZE);
            header.order(ByteOrder.BIG_ENDIAN);
            header.putInt(Constants.WAL_FILE_MAGIC);
            header.putInt(WAL_FORMAT_VERSION);
            header.putLong(0L); // reserved
            header.flip();

            channel.position(0);
            int written = channel.write(header);
            if (written != WAL_HEADER_SIZE) {
                throw new IOException("Failed to write WAL header");
            }
            channel.force(false);

            return new XLogWriter(channel, walPath, FIRST_LSN, INVALID_LSN, INVALID_LSN);
        }
    }

    private XLogWriter(FileChannel channel, Path walPath, long currentLsn, long flushedLsn, long prevLsn) {
        this.channel = channel;
        this.walPath = walPath;
        this.currentLsn = currentLsn;
        this.flushedLsn = flushedLsn;
        this.prevLsn = prevLsn;
    }

    /**
     * Appends a record to the WAL.
     *
     * @param resourceManager resource manager ID
     * @param info operation code
     * @param xid transaction ID
     * @param tableOid target table OID
     * @param pageId target page ID
     * @param data payload data
     * @return the LSN where this record was written
     * @throws IOException if write fails
     */
    public synchronized long append(byte resourceManager, byte info, int xid, int tableOid, int pageId,
                                     byte[] data) throws IOException {
        long lsn = currentLsn;

        XLogRecord record = XLogRecord.create(lsn, xid, prevLsn, resourceManager, info, tableOid, pageId, data);
        byte[] recordBytes = record.toBytes();

        ByteBuffer buf = ByteBuffer.wrap(recordBytes);
        channel.position(lsn);
        int written = channel.write(buf);
        if (written != recordBytes.length) {
            throw new IOException("Partial write: expected " + recordBytes.length + ", wrote " + written);
        }

        currentLsn = lsn + recordBytes.length;
        prevLsn = lsn;  // This record becomes the prev for the next append
        return lsn;
    }

    /**
     * Flushes all records up to the given LSN to disk.
     *
     * @param lsn the LSN to flush up to (inclusive)
     * @throws IOException if flush fails
     */
    public synchronized void flushUpTo(long lsn) throws IOException {
        if (flushedLsn >= lsn) {
            return; // Already flushed
        }
        channel.force(false);
        flushedLsn = currentLsn;
    }

    /**
     * Returns the next LSN that will be assigned.
     */
    public synchronized long currentLsn() {
        return currentLsn;
    }

    /**
     * Returns the highest LSN that has been durably flushed to disk.
     */
    public synchronized long flushedLsn() {
        return flushedLsn;
    }

    @Override
    public synchronized void close() throws IOException {
        flushUpTo(currentLsn);
        channel.close();
    }

    /**
     * Returns the path to the WAL file.
     */
    public Path walPath() {
        return walPath;
    }

    /**
     * Scans the WAL file once at open() to find the prevLsn of the last record.
     * This is O(N) but only runs once at startup, not on every append.
     */
    private static long scanForPrevLsn(FileChannel channel, long fileSize) throws IOException {
        if (fileSize <= FIRST_LSN) {
            return INVALID_LSN;
        }

        long scanPos = FIRST_LSN;
        long lastLsn = INVALID_LSN;

        while (scanPos < fileSize) {
            ByteBuffer header = ByteBuffer.allocate(12); // lsn + totalLength
            channel.position(scanPos);
            int read = channel.read(header);
            if (read < 12) {
                break;
            }
            header.flip();
            header.order(ByteOrder.BIG_ENDIAN);

            long recordLsn = header.getLong();
            int totalLength = header.getInt();

            if (totalLength < 40) { // Minimum record size
                break;
            }

            lastLsn = recordLsn;
            scanPos += totalLength;

            if (scanPos > fileSize) {
                // Incomplete last record - treat last complete record as prev
                break;
            }
        }

        return lastLsn;
    }
}
