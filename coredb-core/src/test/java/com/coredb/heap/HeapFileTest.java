package com.coredb.heap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.coredb.api.Column;
import com.coredb.api.Row;
import com.coredb.api.Schema;
import com.coredb.mvcc.Snapshot;
import com.coredb.txn.ClogManager;
import com.coredb.util.Constants;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HeapFileTest {

    @TempDir
    Path tempDir;

    private HeapFile heapFile;
    private Schema schema;
    private Path tablePath;
    private ClogManager clog;

    @BeforeEach
    void setUp() throws Exception {
        schema = Schema.of(
            Column.longCol("id"),
            Column.stringCol("name"),
            Column.intCol("age")
        );
        tablePath = tempDir.resolve("base").resolve("1").resolve("1000");
        clog = ClogManager.create(tempDir);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (clog != null) {
            clog.close();
        }
    }

    @Test
    void insert_singleRow_returnsRecordId() throws IOException {
        heapFile = HeapFile.create(tablePath, 1000, schema);

        Row row = Row.of(1L, "Alice", 30);
        RecordId rid = heapFile.insert(row, Constants.BOOTSTRAP_XID);

        assertThat(rid.pageId()).isGreaterThanOrEqualTo(1);
        assertThat(rid.slotNo()).isZero();

        heapFile.close();
    }

    @Test
    void get_existingRow_returnsRow() throws IOException {
        heapFile = HeapFile.create(tablePath, 1000, schema);

        Row original = Row.of(1L, "Alice", 30);
        RecordId rid = heapFile.insert(original, Constants.BOOTSTRAP_XID);

        Optional<Row> fetched = heapFile.get(rid, Snapshot.BOOTSTRAP, clog);

        assertThat(fetched).isPresent();
        assertThat(fetched.get()).isEqualTo(original);

        heapFile.close();
    }

    @Test
    void get_nonExistentSlot_returnsEmpty() throws IOException {
        heapFile = HeapFile.create(tablePath, 1000, schema);

        RecordId rid = new RecordId(1, 99);
        Optional<Row> fetched = heapFile.get(rid, Snapshot.BOOTSTRAP, clog);

        assertThat(fetched).isEmpty();

        heapFile.close();
    }

    @Test
    void insert_multipleRows_eachRetrievable() throws IOException {
        heapFile = HeapFile.create(tablePath, 1000, schema);

        List<RecordId> rids = new ArrayList<>();
        int count = 100;

        for (int i = 0; i < count; i++) {
            Row row = Row.of((long) i, "User" + i, i % 100);
            rids.add(heapFile.insert(row, Constants.BOOTSTRAP_XID));
        }

        for (int i = 0; i < count; i++) {
            Optional<Row> fetched = heapFile.get(rids.get(i), Snapshot.BOOTSTRAP, clog);
            assertThat(fetched).isPresent();
            assertThat(fetched.get().getLong(0)).isEqualTo((long) i);
            assertThat(fetched.get().getString(1)).isEqualTo("User" + i);
            assertThat(fetched.get().getInt(2)).isEqualTo(i % 100);
        }

        heapFile.close();
    }

    @Test
    void insert_manyRows_allocatesMultiplePages() throws IOException {
        heapFile = HeapFile.create(tablePath, 1000, schema);

        int count = 200;
        for (int i = 0; i < count; i++) {
            Row row = Row.of((long) i, "User" + i, i);
            heapFile.insert(row, Constants.BOOTSTRAP_XID);
        }

        assertThat(heapFile.pageCount()).isGreaterThan(2);

        heapFile.close();
    }

    @Test
    void delete_existingRow_getReturnsEmpty() throws IOException {
        heapFile = HeapFile.create(tablePath, 1000, schema);

        Row row = Row.of(1L, "Alice", 30);
        RecordId rid = heapFile.insert(row, Constants.BOOTSTRAP_XID);

        heapFile.delete(rid, Constants.BOOTSTRAP_XID);

        Optional<Row> fetched = heapFile.get(rid, Snapshot.BOOTSTRAP, clog);
        assertThat(fetched).isEmpty();

        heapFile.close();
    }

    @Test
    void scan_emptyFile_returnsEmpty() throws IOException {
        heapFile = HeapFile.create(tablePath, 1000, schema);

        List<Row> rows = new ArrayList<>();
        heapFile.scan(Snapshot.BOOTSTRAP, clog).forEachRemaining(rows::add);

        assertThat(rows).isEmpty();

        heapFile.close();
    }

    @Test
    void scan_afterInserts_returnsAllRows() throws IOException {
        heapFile = HeapFile.create(tablePath, 1000, schema);

        int count = 50;
        for (int i = 0; i < count; i++) {
            heapFile.insert(Row.of((long) i, "User" + i, i), Constants.BOOTSTRAP_XID);
        }

        List<Row> rows = new ArrayList<>();
        heapFile.scan(Snapshot.BOOTSTRAP, clog).forEachRemaining(rows::add);

        assertThat(rows).hasSize(count);

        heapFile.close();
    }

    @Test
    void scan_afterSomeDeletes_returnsOnlyLiveRows() throws IOException {
        heapFile = HeapFile.create(tablePath, 1000, schema);

        List<RecordId> rids = new ArrayList<>();
        int count = 20;

        for (int i = 0; i < count; i++) {
            rids.add(heapFile.insert(Row.of((long) i, "User" + i, i), Constants.BOOTSTRAP_XID));
        }

        for (int i = 0; i < count; i += 2) {
            heapFile.delete(rids.get(i), Constants.BOOTSTRAP_XID);
        }

        List<Row> rows = new ArrayList<>();
        heapFile.scan(Snapshot.BOOTSTRAP, clog).forEachRemaining(rows::add);

        assertThat(rows).hasSize(count / 2);

        heapFile.close();
    }

    @Test
    void insert_withNullValues_roundTripsCorrectly() throws IOException {
        heapFile = HeapFile.create(tablePath, 1000, schema);

        Row row = Row.of(1L, null, 30);
        RecordId rid = heapFile.insert(row, Constants.BOOTSTRAP_XID);

        Optional<Row> fetched = heapFile.get(rid, Snapshot.BOOTSTRAP, clog);

        assertThat(fetched).isPresent();
        assertThat(fetched.get().getLong(0)).isEqualTo(1L);
        assertThat(fetched.get().getString(1)).isNull();
        assertThat(fetched.get().getInt(2)).isEqualTo(30);

        heapFile.close();
    }

    @Test
    void delete_nonExistentPage_throwsStorageException() throws IOException {
        heapFile = HeapFile.create(tablePath, 1000, schema);

        RecordId rid = new RecordId(99, 0);

        assertThatThrownBy(() -> heapFile.delete(rid, Constants.BOOTSTRAP_XID))
            .isInstanceOf(com.coredb.util.StorageException.class)
            .hasMessageContaining("page 99 does not exist");

        heapFile.close();
    }

    @Test
    void persistence_closeAndReopen_dataSurvives() throws IOException {
        heapFile = HeapFile.create(tablePath, 1000, schema);

        List<RecordId> rids = new ArrayList<>();
        int count = 50;

        for (int i = 0; i < count; i++) {
            rids.add(heapFile.insert(Row.of((long) i, "User" + i, i), Constants.BOOTSTRAP_XID));
        }

        heapFile.close();

        // Reopen and verify data
        heapFile = HeapFile.open(tablePath, 1000, schema);

        for (int i = 0; i < count; i++) {
            Optional<Row> fetched = heapFile.get(rids.get(i), Snapshot.BOOTSTRAP, clog);
            assertThat(fetched).isPresent();
            assertThat(fetched.get().getLong(0)).isEqualTo((long) i);
        }

        heapFile.close();
    }

    @Test
    void persistence_10kRows_withDeletes_scanReturnsOnlyLiveRows()
        throws IOException {
        heapFile = HeapFile.create(tablePath, 1000, schema);

        List<RecordId> rids = new ArrayList<>();
        int count = 10_000;

        for (int i = 0; i < count; i++) {
            rids.add(heapFile.insert(Row.of((long) i, "User" + i, i % 100), Constants.BOOTSTRAP_XID));
        }

        // Delete every 5th row (20% of total)
        for (int i = 0; i < count; i += 5) {
            heapFile.delete(rids.get(i), Constants.BOOTSTRAP_XID);
        }

        heapFile.close();

        // Reopen and verify
        heapFile = HeapFile.open(tablePath, 1000, schema);

        // Verify deleted rows are not accessible
        for (int i = 0; i < count; i += 5) {
            Optional<Row> fetched = heapFile.get(rids.get(i), Snapshot.BOOTSTRAP, clog);
            assertThat(fetched).isEmpty();
        }

        // Verify live rows are still accessible
        for (int i = 1; i < count; i += 5) {
            Optional<Row> fetched = heapFile.get(rids.get(i), Snapshot.BOOTSTRAP, clog);
            assertThat(fetched).isPresent();
            assertThat(fetched.get().getLong(0)).isEqualTo((long) i);
        }

        // Scan should return only live rows
        List<Row> scannedRows = new ArrayList<>();
        heapFile.scan(Snapshot.BOOTSTRAP, clog).forEachRemaining(scannedRows::add);

        int expectedLive = count - (count / 5); // 8000 live rows
        assertThat(scannedRows).hasSize(expectedLive);

        heapFile.close();
    }

    @Test
    void insert_100Rows_fileSizeIsCorrect() throws IOException {
        heapFile = HeapFile.create(tablePath, 1000, schema);

        int count = 100;
        for (int i = 0; i < count; i++) {
            heapFile.insert(Row.of((long) i, "User" + i, i), Constants.BOOTSTRAP_XID);
        }
        heapFile.close();

        // Verify exact file size: (1 + N) * PAGE_SIZE where N = number of data pages
        // We can calculate expected pages from pageCount: meta page + data pages
        int expectedPages = heapFile.pageCount();  // pageCount returns nextPageId which equals total pages
        long expectedSize = (long) expectedPages * Constants.PAGE_SIZE;
        assertThat(Files.size(tablePath)).isEqualTo(expectedSize);
    }
}
