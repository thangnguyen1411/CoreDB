package com.coredb.wal;

import com.coredb.util.Constants;
import com.coredb.util.CorruptionException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;

/**
 * Sequential WAL reader.
 *
 * <p>Reads XLogRecords from a WAL file, starting from a specified LSN.
 * Handles EOF gracefully and validates record integrity via CRC.
 */
public final class XLogReader implements AutoCloseable {

    private static final int WAL_HEADER_SIZE = 16;
    private static final int WAL_FORMAT_VERSION = 1;

    private final FileChannel channel;
    private final Path walPath;
    private long currentOffset;
    private long lastReadLsn = XLogWriter.INVALID_LSN;

    /**
     * Opens the WAL file for reading.
     *
     * @param walPath path to the WAL file
     * @return a new XLogReader positioned after the header
     * @throws IOException if file cannot be opened or has invalid format
     */
    public static XLogReader open(Path walPath) throws IOException {
        if (!Files.exists(walPath)) {
            throw new IOException("WAL file does not exist: " + walPath);
        }

        FileChannel channel = FileChannel.open(
            walPath,
            StandardOpenOption.READ
        );

        long fileSize = channel.size();
        if (fileSize < WAL_HEADER_SIZE) {
            throw new IOException("WAL file too small: " + fileSize);
        }

        // Read and validate header
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

        return new XLogReader(channel, walPath, WAL_HEADER_SIZE);
    }

    private XLogReader(FileChannel channel, Path walPath, long initialOffset) {
        this.channel = channel;
        this.walPath = walPath;
        this.currentOffset = initialOffset;
    }

    /**
     * Seeks to the specified LSN position.
     *
     * @param lsn the LSN to seek to (must be >= FIRST_LSN)
     * @throws IOException if seek fails or LSN is invalid
     */
    public void seek(long lsn) throws IOException {
        if (lsn < XLogWriter.FIRST_LSN) {
            throw new IOException("Invalid LSN: " + lsn);
        }
        if (lsn > channel.size()) {
            throw new IOException("LSN beyond end of file: " + lsn);
        }
        currentOffset = lsn;
        lastReadLsn = XLogWriter.INVALID_LSN;
    }

    /**
     * Reads the next record from the WAL.
     *
     * @return Optional containing the next record, or empty if at EOF
     * @throws IOException if read fails
     * @throws CorruptionException if record is corrupted
     */
    public Optional<XLogRecord> readNext() throws IOException, CorruptionException {
        long fileSize = channel.size();
        if (currentOffset >= fileSize) {
            return Optional.empty();
        }

        // Read header first to determine record size
        ByteBuffer headerBuf = ByteBuffer.allocate(12); // lsn + totLen + xid prefix
        headerBuf.order(ByteOrder.BIG_ENDIAN);

        channel.position(currentOffset);
        int headerRead = channel.read(headerBuf);
        if (headerRead < 12) {
            // EOF reached mid-record
            return Optional.empty();
        }
        headerBuf.flip();

        headerBuf.getLong(); // skip lsn (we'll validate from the full record)
        int totLen = headerBuf.getInt();

        if (totLen < 40) {
            throw new CorruptionException("Invalid record length: " + totLen);
        }

        if (currentOffset + totLen > fileSize) {
            // Incomplete record at end of file - treat as EOF
            return Optional.empty();
        }

        // Read the complete record
        ByteBuffer recordBuf = ByteBuffer.allocate(totLen);
        recordBuf.order(ByteOrder.BIG_ENDIAN);

        // Re-position and read the full record
        channel.position(currentOffset);
        int totalRead = channel.read(recordBuf);
        if (totalRead < totLen) {
            // Incomplete read
            return Optional.empty();
        }
        recordBuf.flip();

        XLogRecord record = XLogRecord.readFrom(recordBuf, 0);
        if (record == null) {
            return Optional.empty();
        }

        // Verify LSN matches position (sanity check)
        if (record.lsn() != currentOffset) {
            throw new CorruptionException(
                String.format("LSN mismatch: expected %d, got %d", currentOffset, record.lsn()));
        }

        currentOffset += totLen;
        lastReadLsn = record.lsn();
        return Optional.of(record);
    }

    /**
     * Returns the LSN of the most recently read record.
     */
    public long lastReadLsn() {
        return lastReadLsn;
    }

    /**
     * Returns the current read position in the WAL file.
     */
    public long currentOffset() {
        return currentOffset;
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }

    /**
     * Returns the path to the WAL file.
     */
    public Path walPath() {
        return walPath;
    }
}
