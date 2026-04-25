package com.coredb.heap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.coredb.api.Column;
import com.coredb.api.Row;
import com.coredb.api.Schema;
import com.coredb.util.Constants;
import com.coredb.util.CorruptionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Per-table HeapFile with meta page.
 */
class HeapFilePerTableTest {

    @TempDir
    Path tempDir;

    private Schema schema;
    private Path tablePath;

    @BeforeEach
    void setUp() {
        schema = Schema.of(
            Column.longCol("id"),
            Column.stringCol("name"),
            Column.intCol("age")
        );
        tablePath = tempDir.resolve("base").resolve("1").resolve("1000");
    }

    @Test
    void create_writesMetaPageWithCorrectFormat() throws IOException {
        // When
        HeapFile hf = HeapFile.create(tablePath, 1000, schema);
        hf.close();

        // Then: file exists
        assertThat(tablePath).exists();
        assertThat(Files.size(tablePath)).isEqualTo(Constants.PAGE_SIZE); // Just meta page

        // Then: meta page has correct magic, version, OID, nextPageId
        try (FileChannel channel = FileChannel.open(tablePath, StandardOpenOption.READ)) {
            ByteBuffer buf = ByteBuffer.allocate(Constants.PAGE_SIZE).order(ByteOrder.BIG_ENDIAN);
            channel.read(buf);
            buf.flip();

            int magic = buf.getInt(0);
            int version = buf.getInt(4);
            int oid = buf.getInt(8);
            int nextPageId = buf.getInt(12);

            assertThat(magic).isEqualTo(Constants.HEAP_FILE_MAGIC); // "HEAP"
            assertThat(version).isEqualTo(1);
            assertThat(oid).isEqualTo(1000);
            assertThat(nextPageId).isEqualTo(1); // Data pages start at 1
        }
    }

    @Test
    void open_existingFile_roundTripsMetaPage() throws IOException {
        // Given: create a file
        HeapFile created = HeapFile.create(tablePath, 1000, schema);
        created.close();

        // When: open it
        HeapFile opened = HeapFile.open(tablePath, 1000, schema);

        // Then
        assertThat(opened.oid()).isEqualTo(1000);
        assertThat(opened.tablePath()).isEqualTo(tablePath);

        opened.close();
    }

    @Test
    void open_wrongMagic_throwsCorruptionException() throws IOException {
        // Given: create a file with wrong magic in meta page
        Files.createDirectories(tablePath.getParent());
        try (FileChannel channel = FileChannel.open(tablePath,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            ByteBuffer buf = ByteBuffer.allocate(Constants.PAGE_SIZE).order(ByteOrder.BIG_ENDIAN);
            buf.putInt(0, 0xDEADBEEF); // Wrong magic
            buf.putInt(4, 1); // version
            buf.putInt(8, 1000); // oid
            buf.rewind();
            channel.write(buf);
        }

        // When/Then
        assertThatThrownBy(() -> HeapFile.open(tablePath, 1000, schema))
            .isInstanceOf(CorruptionException.class)
            .hasMessageContaining("magic mismatch");
    }

    @Test
    void open_wrongVersion_throwsCorruptionException() throws IOException {
        // Given: create a file with wrong version in meta page
        Files.createDirectories(tablePath.getParent());
        try (FileChannel channel = FileChannel.open(tablePath,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            ByteBuffer buf = ByteBuffer.allocate(Constants.PAGE_SIZE).order(ByteOrder.BIG_ENDIAN);
            buf.putInt(0, Constants.HEAP_FILE_MAGIC); // correct magic
            buf.putInt(4, 999); // wrong version
            buf.putInt(8, 1000); // oid
            buf.rewind();
            channel.write(buf);
        }

        // When/Then
        assertThatThrownBy(() -> HeapFile.open(tablePath, 1000, schema))
            .isInstanceOf(CorruptionException.class)
            .hasMessageContaining("version mismatch");
    }

    @Test
    void open_oidMismatch_throwsCorruptionException() throws IOException {
        // Given: create a file with oid=1001 but try to open as oid=1000
        HeapFile created = HeapFile.create(tablePath, 1001, schema);
        created.close();

        // When/Then
        assertThatThrownBy(() -> HeapFile.open(tablePath, 1000, schema))
            .isInstanceOf(CorruptionException.class)
            .hasMessageContaining("OID mismatch");
    }

    @Test
    void create_multipleFiles_doNotInterfere() throws IOException {
        // Given
        Path path1000 = tempDir.resolve("base").resolve("1").resolve("1000");
        Path path1001 = tempDir.resolve("base").resolve("1").resolve("1001");

        // When
        HeapFile hf1000 = HeapFile.create(path1000, 1000, schema);
        HeapFile hf1001 = HeapFile.create(path1001, 1001, schema);

        hf1000.close();
        hf1001.close();

        // Then: both files exist independently
        assertThat(path1000).exists();
        assertThat(path1001).exists();

        // Then: each can be opened with its correct OID
        HeapFile reopened1000 = HeapFile.open(path1000, 1000, schema);
        HeapFile reopened1001 = HeapFile.open(path1001, 1001, schema);

        assertThat(reopened1000.oid()).isEqualTo(1000);
        assertThat(reopened1001.oid()).isEqualTo(1001);

        reopened1000.close();
        reopened1001.close();
    }

    @Test
    void tablePath_perTableMode_returnsPath() throws IOException {
        HeapFile hf = HeapFile.create(tablePath, 1000, schema);
        assertThat(hf.tablePath()).isEqualTo(tablePath);
        hf.close();
    }

    @Test
    void oid_perTableMode_returnsOid() throws IOException {
        HeapFile hf = HeapFile.create(tablePath, 1000, schema);
        assertThat(hf.oid()).isEqualTo(1000);
        hf.close();
    }

    @Test
    void nextPageId_perTableMode_returnsNextPageId() throws IOException {
        HeapFile hf = HeapFile.create(tablePath, 1000, schema);
        assertThat(hf.nextPageId()).isEqualTo(1);
        hf.close();
    }

    @Test
    void insert_singleRow_returnsRecordId() throws IOException {
        HeapFile hf = HeapFile.create(tablePath, 1000, schema);

        Row row = Row.of(1L, "Alice", 30);
        RecordId rid = hf.insert(row);

        assertThat(rid.pageId()).isGreaterThanOrEqualTo(1); // Page 0 is meta
        assertThat(rid.slotNo()).isZero();

        hf.close();
    }

    @Test
    void insert_singleRow_fileSizeIsExactlyTwoPages() throws IOException {
        HeapFile hf = HeapFile.create(tablePath, 1000, schema);

        Row row = Row.of(1L, "Alice", 30);
        hf.insert(row);
        hf.close();

        // File should be: 1 meta page + 1 data page = 2 pages
        long expectedSize = 2L * Constants.PAGE_SIZE;
        assertThat(Files.size(tablePath)).isEqualTo(expectedSize);
    }

    @Test
    void insert_100Rows_fileSizeIsCorrect() throws IOException {
        HeapFile hf = HeapFile.create(tablePath, 1000, schema);

        int count = 100;
        for (int i = 0; i < count; i++) {
            hf.insert(Row.of((long) i, "User" + i, i));
        }

        // Get page count before closing
        int pageCount = hf.pageCount();
        hf.close();

        // Verify exact file size: pageCount * PAGE_SIZE (includes meta page + all data pages)
        long expectedSize = (long) pageCount * Constants.PAGE_SIZE;
        assertThat(Files.size(tablePath)).isEqualTo(expectedSize);
    }

    @Test
    void get_existingRow_returnsRow() throws IOException {
        HeapFile hf = HeapFile.create(tablePath, 1000, schema);

        Row original = Row.of(1L, "Alice", 30);
        RecordId rid = hf.insert(original);

        java.util.Optional<Row> fetched = hf.get(rid);

        assertThat(fetched).isPresent();
        assertThat(fetched.get()).isEqualTo(original);

        hf.close();
    }

    @Test
    void get_nonExistentSlot_returnsEmpty() throws IOException {
        HeapFile hf = HeapFile.create(tablePath, 1000, schema);

        RecordId rid = new RecordId(1, 99);
        java.util.Optional<Row> fetched = hf.get(rid);

        assertThat(fetched).isEmpty();

        hf.close();
    }

    @Test
    void insert_multipleRows_eachRetrievable() throws IOException {
        HeapFile hf = HeapFile.create(tablePath, 1000, schema);

        java.util.List<RecordId> rids = new java.util.ArrayList<>();
        int count = 50;

        for (int i = 0; i < count; i++) {
            Row row = Row.of((long) i, "User" + i, i % 100);
            rids.add(hf.insert(row));
        }

        for (int i = 0; i < count; i++) {
            java.util.Optional<Row> fetched = hf.get(rids.get(i));
            assertThat(fetched).isPresent();
            assertThat(fetched.get().getLong(0)).isEqualTo((long) i);
            assertThat(fetched.get().getString(1)).isEqualTo("User" + i);
            assertThat(fetched.get().getInt(2)).isEqualTo(i % 100);
        }

        hf.close();
    }

    @Test
    void insert_incrementsNextPageId() throws IOException {
        HeapFile hf = HeapFile.create(tablePath, 1000, schema);

        assertThat(hf.nextPageId()).isEqualTo(1); // Just meta page

        // First insert allocates page 1
        hf.insert(Row.of(1L, "Alice", 30));
        assertThat(hf.nextPageId()).isEqualTo(2);

        // Many more inserts to force another page allocation
        for (int i = 0; i < 1000; i++) {
            hf.insert(Row.of((long) i, "User" + i, i));
        }
        assertThat(hf.nextPageId()).isGreaterThan(2);

        hf.close();
    }

    @Test
    void insert_persistsNextPageIdToMetaPage() throws IOException {
        HeapFile hf = HeapFile.create(tablePath, 1000, schema);

        // Insert enough rows to allocate multiple pages
        for (int i = 0; i < 1000; i++) {
            hf.insert(Row.of((long) i, "User" + i, i));
        }

        int pageCountBeforeClose = hf.nextPageId();
        long fileSizeBeforeClose = hf.fileSize();
        hf.close();

        // Reopen and verify nextPageId and file size persisted
        HeapFile reopened = HeapFile.open(tablePath, 1000, schema);
        assertThat(reopened.nextPageId()).isEqualTo(pageCountBeforeClose);
        assertThat(reopened.fileSize()).isEqualTo(fileSizeBeforeClose);
        reopened.close();
    }

    @Test
    void delete_existingRow_getReturnsEmpty() throws IOException {
        HeapFile hf = HeapFile.create(tablePath, 1000, schema);

        Row row = Row.of(1L, "Alice", 30);
        RecordId rid = hf.insert(row);

        hf.delete(rid);

        java.util.Optional<Row> fetched = hf.get(rid);
        assertThat(fetched).isEmpty();

        hf.close();
    }

    @Test
    void scan_emptyFile_returnsEmpty() throws IOException {
        HeapFile hf = HeapFile.create(tablePath, 1000, schema);

        java.util.List<Row> rows = new java.util.ArrayList<>();
        hf.scan().forEachRemaining(rows::add);

        assertThat(rows).isEmpty();

        hf.close();
    }

    @Test
    void scan_afterInserts_returnsAllRows() throws IOException {
        HeapFile hf = HeapFile.create(tablePath, 1000, schema);

        int count = 50;
        for (int i = 0; i < count; i++) {
            hf.insert(Row.of((long) i, "User" + i, i));
        }

        java.util.List<Row> rows = new java.util.ArrayList<>();
        hf.scan().forEachRemaining(rows::add);

        assertThat(rows).hasSize(count);

        hf.close();
    }

    @Test
    void scan_afterSomeDeletes_returnsOnlyLiveRows() throws IOException {
        HeapFile hf = HeapFile.create(tablePath, 1000, schema);

        java.util.List<RecordId> rids = new java.util.ArrayList<>();
        int count = 20;

        for (int i = 0; i < count; i++) {
            rids.add(hf.insert(Row.of((long) i, "User" + i, i)));
        }

        for (int i = 0; i < count; i += 2) {
            hf.delete(rids.get(i));
        }

        java.util.List<Row> rows = new java.util.ArrayList<>();
        hf.scan().forEachRemaining(rows::add);

        assertThat(rows).hasSize(count / 2);

        hf.close();
    }

    @Test
    void persistence_closeAndReopen_dataSurvives() throws IOException {
        HeapFile hf = HeapFile.create(tablePath, 1000, schema);

        java.util.List<RecordId> rids = new java.util.ArrayList<>();
        int count = 50;

        for (int i = 0; i < count; i++) {
            rids.add(hf.insert(Row.of((long) i, "User" + i, i)));
        }
        hf.close();

        // Reopen and verify data
        HeapFile reopened = HeapFile.open(tablePath, 1000, schema);
        for (int i = 0; i < count; i++) {
            java.util.Optional<Row> fetched = reopened.get(rids.get(i));
            assertThat(fetched).isPresent();
            assertThat(fetched.get().getLong(0)).isEqualTo((long) i);
        }
        reopened.close();
    }

    @Test
    void persistence_scanAfterReopen_returnsAllRows() throws IOException {
        HeapFile hf = HeapFile.create(tablePath, 1000, schema);

        int count = 50;
        for (int i = 0; i < count; i++) {
            hf.insert(Row.of((long) i, "User" + i, i));
        }
        hf.close();

        HeapFile reopened = HeapFile.open(tablePath, 1000, schema);
        java.util.List<Row> rows = new java.util.ArrayList<>();
        reopened.scan().forEachRemaining(rows::add);

        assertThat(rows).hasSize(count);
        reopened.close();
    }

    @Test
    void twoSeparateFiles_allocateIndependently() throws IOException {
        Path path1000 = tempDir.resolve("base").resolve("1").resolve("1000");
        Path path1001 = tempDir.resolve("base").resolve("1").resolve("1001");

        HeapFile hf1000 = HeapFile.create(path1000, 1000, schema);
        HeapFile hf1001 = HeapFile.create(path1001, 1001, schema);

        // Insert different amounts of data into each
        hf1000.insert(Row.of(1L, "A", 1));

        // Insert many rows into second file to force page allocation
        // Need enough rows to span multiple pages
        for (int i = 0; i < 1000; i++) {
            hf1001.insert(Row.of((long) i, "User" + i, i));
        }

        int pages1000 = hf1000.nextPageId();
        int pages1001 = hf1001.nextPageId();

        hf1000.close();
        hf1001.close();

        // Verify files have independent page counts (1001 should have more pages)
        assertThat(pages1001).isGreaterThan(pages1000);

        // Verify each file can be reopened independently with correct OID
        HeapFile reopened1000 = HeapFile.open(path1000, 1000, schema);
        HeapFile reopened1001 = HeapFile.open(path1001, 1001, schema);

        assertThat(reopened1000.oid()).isEqualTo(1000);
        assertThat(reopened1001.oid()).isEqualTo(1001);
        assertThat(reopened1000.nextPageId()).isEqualTo(pages1000);
        assertThat(reopened1001.nextPageId()).isEqualTo(pages1001);

        reopened1000.close();
        reopened1001.close();
    }

    @Test
    void pageCount_returnsNextPageId() throws IOException {
        HeapFile hf = HeapFile.create(tablePath, 1000, schema);

        assertThat(hf.pageCount()).isEqualTo(1); // Just meta page

        hf.insert(Row.of(1L, "Alice", 30));
        assertThat(hf.pageCount()).isGreaterThanOrEqualTo(2); // Meta + at least 1 data

        hf.close();
    }

    @Test
    void fileSize_returnsActualFileSize() throws IOException {
        HeapFile hf = HeapFile.create(tablePath, 1000, schema);

        long size1 = hf.fileSize();
        assertThat(size1).isEqualTo(Constants.PAGE_SIZE); // Just meta page

        hf.insert(Row.of(1L, "Alice", 30));

        long size2 = hf.fileSize();
        assertThat(size2).isGreaterThan(size1);
        assertThat(size2 % Constants.PAGE_SIZE).isZero();

        hf.close();
    }
}
