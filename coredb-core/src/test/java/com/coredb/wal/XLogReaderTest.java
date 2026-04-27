package com.coredb.wal;

import com.coredb.util.CorruptionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class XLogReaderTest {

    @TempDir
    Path tempDir;

    @Test
    void open_throwsIfFileDoesNotExist() {
        Path walPath = tempDir.resolve("pg_wal");

        assertThatThrownBy(() -> XLogReader.open(walPath))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("does not exist");
    }

    @Test
    void open_validatesHeader() throws IOException {
        Path walPath = tempDir.resolve("pg_wal");
        Files.write(walPath, new byte[]{1, 2, 3});

        assertThatThrownBy(() -> XLogReader.open(walPath))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("too small");
    }

    @Test
    void readNext_returnsEmptyForEmptyWAL() throws IOException {
        Path walPath = tempDir.resolve("pg_wal");

        // Create empty WAL
        try (XLogWriter writer = XLogWriter.open(walPath)) {
            // Just create and close
        }

        try (XLogReader reader = XLogReader.open(walPath)) {
            Optional<XLogRecord> record = reader.readNext();
            assertThat(record).isEmpty();
        }
    }

    @Test
    void readNext_returnsWrittenRecords() throws IOException {
        Path walPath = tempDir.resolve("pg_wal");

        byte[] data1 = new byte[]{0x01, 0x02};
        byte[] data2 = new byte[]{0x03, 0x04, 0x05};

        try (XLogWriter writer = XLogWriter.open(walPath)) {
            writer.append(XLogRecord.RMGR_HEAP, (byte) 0x01, 1, 1000, 5, data1);
            writer.append(XLogRecord.RMGR_BTREE, (byte) 0x10, 2, 1001, 10, data2);
            writer.flushUpTo(writer.currentLsn());
        }

        try (XLogReader reader = XLogReader.open(walPath)) {
            Optional<XLogRecord> rec1 = reader.readNext();
            assertThat(rec1).isPresent();
            assertThat(rec1.get().rmgr()).isEqualTo(XLogRecord.RMGR_HEAP);
            assertThat(rec1.get().info()).isEqualTo((byte) 0x01);
            assertThat(rec1.get().xid()).isEqualTo(1);
            assertThat(rec1.get().tableOid()).isEqualTo(1000);
            assertThat(rec1.get().pageId()).isEqualTo(5);
            assertThat(rec1.get().data()).containsExactly(0x01, 0x02);

            Optional<XLogRecord> rec2 = reader.readNext();
            assertThat(rec2).isPresent();
            assertThat(rec2.get().rmgr()).isEqualTo(XLogRecord.RMGR_BTREE);
            assertThat(rec2.get().info()).isEqualTo((byte) 0x10);
            assertThat(rec2.get().xid()).isEqualTo(2);
            assertThat(rec2.get().tableOid()).isEqualTo(1001);
            assertThat(rec2.get().pageId()).isEqualTo(10);
            assertThat(rec2.get().data()).containsExactly(0x03, 0x04, 0x05);

            Optional<XLogRecord> rec3 = reader.readNext();
            assertThat(rec3).isEmpty();
        }
    }

    @Test
    void readNext_validatesCRC() throws IOException {
        Path walPath = tempDir.resolve("pg_wal");

        try (XLogWriter writer = XLogWriter.open(walPath)) {
            writer.append(XLogRecord.RMGR_HEAP, (byte) 0x01, 1, 1000, 1, new byte[]{1});
            writer.flushUpTo(writer.currentLsn());
        }

        // Corrupt one byte in the file
        byte[] fileBytes = Files.readAllBytes(walPath);
        fileBytes[fileBytes.length - 1] = (byte) (fileBytes[fileBytes.length - 1] ^ 0xFF);
        Files.write(walPath, fileBytes);

        try (XLogReader reader = XLogReader.open(walPath)) {
            assertThatThrownBy(() -> reader.readNext())
                .isInstanceOf(CorruptionException.class)
                .hasMessageContaining("CRC mismatch");
        }
    }

    @Test
    void seek_positionsAtSpecificLsn() throws IOException {
        Path walPath = tempDir.resolve("pg_wal");

        long secondLsn;
        try (XLogWriter writer = XLogWriter.open(walPath)) {
            writer.append(XLogRecord.RMGR_HEAP, (byte) 0x01, 1, 1000, 1, new byte[]{1});
            secondLsn = writer.append(XLogRecord.RMGR_HEAP, (byte) 0x01, 2, 1000, 1, new byte[]{2});
            writer.append(XLogRecord.RMGR_HEAP, (byte) 0x01, 3, 1000, 1, new byte[]{3});
            writer.flushUpTo(writer.currentLsn());
        }

        try (XLogReader reader = XLogReader.open(walPath)) {
            reader.seek(secondLsn);

            Optional<XLogRecord> rec = reader.readNext();
            assertThat(rec).isPresent();
            assertThat(rec.get().xid()).isEqualTo(2);
        }
    }

    @Test
    void seek_throwsForInvalidLsn() throws IOException {
        Path walPath = tempDir.resolve("pg_wal");

        try (XLogWriter writer = XLogWriter.open(walPath)) {
            writer.flushUpTo(writer.currentLsn());
        }

        try (XLogReader reader = XLogReader.open(walPath)) {
            assertThatThrownBy(() -> reader.seek(5))  // Before FIRST_LSN
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Invalid LSN");
        }
    }

    @Test
    void read100Records_allFieldsRoundTrip() throws IOException {
        Path walPath = tempDir.resolve("pg_wal");

        int recordCount = 100;
        List<Long> expectedLsns = new ArrayList<>();
        List<byte[]> expectedData = new ArrayList<>();

        try (XLogWriter writer = XLogWriter.open(walPath)) {
            for (int i = 0; i < recordCount; i++) {
                byte[] data = new byte[]{(byte) i, (byte) (i + 1)};
                expectedData.add(data);
                long lsn = writer.append(
                    (i % 2 == 0) ? XLogRecord.RMGR_HEAP : XLogRecord.RMGR_BTREE,
                    (byte) (i % 256),
                    i + 1,
                    1000 + (i % 10),
                    (i % 5) + 1,
                    data
                );
                expectedLsns.add(lsn);
            }
            writer.flushUpTo(writer.currentLsn());
        }

        try (XLogReader reader = XLogReader.open(walPath)) {
            for (int i = 0; i < recordCount; i++) {
                Optional<XLogRecord> recordOpt = reader.readNext();
                assertThat(recordOpt).isPresent();

                XLogRecord rec = recordOpt.get();
                assertThat(rec.lsn()).isEqualTo(expectedLsns.get(i));
                assertThat(rec.xid()).isEqualTo(i + 1);
                assertThat(rec.tableOid()).isEqualTo(1000 + (i % 10));
                assertThat(rec.pageId()).isEqualTo((i % 5) + 1);
                assertThat(rec.data()).containsExactly(expectedData.get(i));
            }

            assertThat(reader.readNext()).isEmpty();
        }
    }

    @Test
    void lastReadLsn_returnsMostRecentRecordLsn() throws IOException {
        Path walPath = tempDir.resolve("pg_wal");

        try (XLogWriter writer = XLogWriter.open(walPath)) {
            long lsn = writer.append(XLogRecord.RMGR_HEAP, (byte) 0x01, 1, 1000, 1, new byte[]{1});
            writer.flushUpTo(lsn);
        }

        try (XLogReader reader = XLogReader.open(walPath)) {
            assertThat(reader.lastReadLsn()).isEqualTo(XLogWriter.INVALID_LSN);

            reader.readNext();
            assertThat(reader.lastReadLsn()).isGreaterThan(XLogWriter.INVALID_LSN);
        }
    }
}
