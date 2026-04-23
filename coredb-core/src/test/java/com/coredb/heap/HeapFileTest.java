package com.coredb.heap;

import com.coredb.api.Column;
import com.coredb.api.CoreDBConfig;
import com.coredb.api.Row;
import com.coredb.api.Schema;
import com.coredb.config.EngineType;
import com.coredb.storage.DiskManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HeapFileTest {

    @TempDir
    Path tempDir;

    private DiskManager diskManager;
    private HeapFile heapFile;
    private Schema schema;

    @BeforeEach
    void setUp() throws IOException {
        Path dbPath = tempDir.resolve("test.db");
        CoreDBConfig config = CoreDBConfig.builder()
                .engineType(EngineType.BTREE)
                .pageSize(8192)
                .build();
        diskManager = DiskManager.open(dbPath, config);

        schema = Schema.of(
                Column.longCol("id"),
                Column.stringCol("name"),
                Column.intCol("age")
        );
        heapFile = new HeapFile(diskManager, schema);
    }

    @AfterEach
    void tearDown() throws IOException {
        if (diskManager != null) {
            diskManager.close();
        }
    }

    @Test
    void insert_singleRow_returnsRecordId() throws IOException {
        Row row = Row.of(1L, "Alice", 30);

        RecordId rid = heapFile.insert(row);

        assertThat(rid.pageId()).isGreaterThanOrEqualTo(1);
        assertThat(rid.slotNo()).isZero();
    }

    @Test
    void get_existingRow_returnsRow() throws IOException {
        Row original = Row.of(1L, "Alice", 30);
        RecordId rid = heapFile.insert(original);

        Optional<Row> fetched = heapFile.get(rid);

        assertThat(fetched).isPresent();
        assertThat(fetched.get()).isEqualTo(original);
    }

    @Test
    void get_nonExistentSlot_returnsEmpty() throws IOException {
        RecordId rid = new RecordId(1, 99);

        Optional<Row> fetched = heapFile.get(rid);

        assertThat(fetched).isEmpty();
    }

    @Test
    void insert_multipleRows_eachRetrievable() throws IOException {
        List<RecordId> rids = new ArrayList<>();
        int count = 100;

        for (int i = 0; i < count; i++) {
            Row row = Row.of((long) i, "User" + i, i % 100);
            rids.add(heapFile.insert(row));
        }

        for (int i = 0; i < count; i++) {
            Optional<Row> fetched = heapFile.get(rids.get(i));
            assertThat(fetched).isPresent();
            assertThat(fetched.get().getLong(0)).isEqualTo((long) i);
            assertThat(fetched.get().getString(1)).isEqualTo("User" + i);
            assertThat(fetched.get().getInt(2)).isEqualTo(i % 100);
        }
    }

    @Test
    void insert_manyRows_allocatesMultiplePages() throws IOException {
        int count = 200;

        for (int i = 0; i < count; i++) {
            Row row = Row.of((long) i, "User" + i, i);
            heapFile.insert(row);
        }

        assertThat(heapFile.pageCount()).isGreaterThan(2);
    }

    @Test
    void delete_existingRow_getReturnsEmpty() throws IOException {
        Row row = Row.of(1L, "Alice", 30);
        RecordId rid = heapFile.insert(row);

        heapFile.delete(rid);

        Optional<Row> fetched = heapFile.get(rid);
        assertThat(fetched).isEmpty();
    }

    @Test
    void scan_emptyFile_returnsEmpty() throws IOException {
        List<Row> rows = new ArrayList<>();
        heapFile.scan().forEachRemaining(rows::add);

        assertThat(rows).isEmpty();
    }

    @Test
    void scan_afterInserts_returnsAllRows() throws IOException {
        int count = 50;
        for (int i = 0; i < count; i++) {
            heapFile.insert(Row.of((long) i, "User" + i, i));
        }

        List<Row> rows = new ArrayList<>();
        heapFile.scan().forEachRemaining(rows::add);

        assertThat(rows).hasSize(count);
    }

    @Test
    void scan_afterSomeDeletes_returnsOnlyLiveRows() throws IOException {
        List<RecordId> rids = new ArrayList<>();
        int count = 20;

        for (int i = 0; i < count; i++) {
            rids.add(heapFile.insert(Row.of((long) i, "User" + i, i)));
        }

        for (int i = 0; i < count; i += 2) {
            heapFile.delete(rids.get(i));
        }

        List<Row> rows = new ArrayList<>();
        heapFile.scan().forEachRemaining(rows::add);

        assertThat(rows).hasSize(count / 2);
    }

    @Test
    void insert_withNullValues_roundTripsCorrectly() throws IOException {
        Row row = Row.of(1L, null, 30);
        RecordId rid = heapFile.insert(row);

        Optional<Row> fetched = heapFile.get(rid);

        assertThat(fetched).isPresent();
        assertThat(fetched.get().getLong(0)).isEqualTo(1L);
        assertThat(fetched.get().getString(1)).isNull();
        assertThat(fetched.get().getInt(2)).isEqualTo(30);
    }

    @Test
    void delete_nonExistentPage_throwsStorageException() {
        RecordId rid = new RecordId(99, 0);

        assertThatThrownBy(() -> heapFile.delete(rid))
                .isInstanceOf(com.coredb.util.StorageException.class)
                .hasMessageContaining("page 99 does not exist");
    }

    @Test
    void persistence_closeAndReopen_dataSurvives() throws IOException {
        List<RecordId> rids = new ArrayList<>();
        int count = 50;

        for (int i = 0; i < count; i++) {
            rids.add(heapFile.insert(Row.of((long) i, "User" + i, i)));
        }

        diskManager.close();

        CoreDBConfig config = CoreDBConfig.builder()
                .engineType(EngineType.BTREE)
                .pageSize(8192)
                .build();
        diskManager = DiskManager.open(tempDir.resolve("test.db"), config);
        heapFile = new HeapFile(diskManager, schema);

        for (int i = 0; i < count; i++) {
            Optional<Row> fetched = heapFile.get(rids.get(i));
            assertThat(fetched).isPresent();
            assertThat(fetched.get().getLong(0)).isEqualTo((long) i);
        }
    }
}
