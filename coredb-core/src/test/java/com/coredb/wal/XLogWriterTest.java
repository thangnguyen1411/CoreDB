package com.coredb.wal;

import com.coredb.util.Constants;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class XLogWriterTest {

    @TempDir
    Path tempDir;

    @Test
    void open_createsNewFileWithHeader() throws IOException {
        Path walPath = tempDir.resolve("pg_wal");

        try (XLogWriter writer = XLogWriter.open(walPath)) {
            assertThat(Files.exists(walPath)).isTrue();
            assertThat(writer.currentLsn()).isEqualTo(XLogWriter.FIRST_LSN);
            assertThat(writer.flushedLsn()).isEqualTo(XLogWriter.INVALID_LSN);
        }

        // Verify header was written
        byte[] header = Files.readAllBytes(walPath);
        assertThat(header.length).isEqualTo(16);

        // Verify magic
        int magic = ((header[0] & 0xFF) << 24) |
                   ((header[1] & 0xFF) << 16) |
                   ((header[2] & 0xFF) << 8) |
                   (header[3] & 0xFF);
        assertThat(magic).isEqualTo(Constants.WAL_FILE_MAGIC);

        // Verify version
        int version = ((header[4] & 0xFF) << 24) |
                     ((header[5] & 0xFF) << 16) |
                     ((header[6] & 0xFF) << 8) |
                     (header[7] & 0xFF);
        assertThat(version).isEqualTo(1);
    }

    @Test
    void open_existingFileValidatesHeader() throws IOException {
        Path walPath = tempDir.resolve("pg_wal");

        // Create file first
        try (XLogWriter writer = XLogWriter.open(walPath)) {
            writer.append(XLogRecord.RMGR_HEAP, (byte) 0x01, 1, 1000, 1, new byte[]{1, 2, 3});
        }

        // Reopen should succeed
        try (XLogWriter writer = XLogWriter.open(walPath)) {
            assertThat(writer.currentLsn()).isGreaterThan(XLogWriter.FIRST_LSN);
        }
    }

    @Test
    void append_returnsIncreasingLsn() throws IOException {
        Path walPath = tempDir.resolve("pg_wal");

        try (XLogWriter writer = XLogWriter.open(walPath)) {
            long lsn1 = writer.append(XLogRecord.RMGR_HEAP, (byte) 0x01, 1, 1000, 1, new byte[]{1});
            long lsn2 = writer.append(XLogRecord.RMGR_HEAP, (byte) 0x01, 1, 1000, 1, new byte[]{2});
            long lsn3 = writer.append(XLogRecord.RMGR_HEAP, (byte) 0x01, 1, 1000, 1, new byte[]{3});

            assertThat(lsn1).isEqualTo(XLogWriter.FIRST_LSN);
            assertThat(lsn2).isGreaterThan(lsn1);
            assertThat(lsn3).isGreaterThan(lsn2);
        }
    }

    @Test
    void append_writesRecordToFile() throws IOException {
        Path walPath = tempDir.resolve("pg_wal");

        byte[] data = new byte[]{0x01, 0x02, 0x03, 0x04, 0x05};

        try (XLogWriter writer = XLogWriter.open(walPath)) {
            long lsn = writer.append(XLogRecord.RMGR_HEAP, (byte) 0x01, 42, 1000, 5, data);
            writer.flushUpTo(lsn);
        }

        // Verify file size includes header + record
        long fileSize = Files.size(walPath);
        assertThat(fileSize).isGreaterThan(16); // Header is 16 bytes
    }

    @Test
    void flushUpTo_updatesFlushedLsn() throws IOException {
        Path walPath = tempDir.resolve("pg_wal");

        try (XLogWriter writer = XLogWriter.open(walPath)) {
            long lsn = writer.append(XLogRecord.RMGR_HEAP, (byte) 0x01, 1, 1000, 1, new byte[]{1});
            assertThat(writer.flushedLsn()).isEqualTo(XLogWriter.INVALID_LSN);

            writer.flushUpTo(lsn);
            assertThat(writer.flushedLsn()).isGreaterThanOrEqualTo(lsn);
        }
    }

    @Test
    void flushUpTo_doesNothingIfAlreadyFlushed() throws IOException {
        Path walPath = tempDir.resolve("pg_wal");

        try (XLogWriter writer = XLogWriter.open(walPath)) {
            long lsn = writer.append(XLogRecord.RMGR_HEAP, (byte) 0x01, 1, 1000, 1, new byte[]{1});
            writer.flushUpTo(lsn);
            long flushedLsn = writer.flushedLsn();

            // Second flush should be a no-op
            writer.flushUpTo(lsn);
            assertThat(writer.flushedLsn()).isEqualTo(flushedLsn);
        }
    }

    @Test
    void write100Records_fileSizeMatchesExpected() throws IOException {
        Path walPath = tempDir.resolve("pg_wal");

        int recordCount = 100;
        int dataSize = 50;
        int headerSize = 40; // XLogRecord header size

        try (XLogWriter writer = XLogWriter.open(walPath)) {
            for (int i = 0; i < recordCount; i++) {
                byte[] data = new byte[dataSize];
                data[0] = (byte) i;
                writer.append(XLogRecord.RMGR_HEAP, (byte) 0x01, i + 1, 1000, 1, data);
            }
            writer.flushUpTo(writer.currentLsn());
        }

        long expectedSize = 16 + (long) recordCount * (headerSize + dataSize);
        long actualSize = Files.size(walPath);
        assertThat(actualSize).isEqualTo(expectedSize);
    }
}
