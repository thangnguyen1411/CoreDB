package com.coredb.engine;

import com.coredb.api.Row;
import com.coredb.api.Schema;
import com.coredb.buffer.BufferPool;
import com.coredb.catalog.TableMeta;
import com.coredb.config.EngineType;
import com.coredb.txn.ClogManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Abstract contract test suite for {@link StorageEngine} implementations.
 *
 * <p>Any concrete storage engine must satisfy these semantics. Subclasses
 * provide a factory method to create the engine under test.</p>
 *
 * <p>This test suite defines the expected behavior for:
 * <ul>
 *   <li>Primary key operations: put, get, delete</li>
 *   <li>Upsert semantics: replacing visible rows while leaving dead versions</li>
 *   <li>Scan operations: rangeScan and fullScan</li>
 *   <li>Persistence: close and reopen preserves data</li>
 * </ul>
 */
public abstract class StorageEngineContractTest {

    @TempDir
    protected Path tempDir;

    private ClogManager clog;

    @BeforeEach
    void setUp() throws Exception {
        clog = ClogManager.create(tempDir);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (clog != null) {
            clog.close();
        }
    }

    /**
     * Factory method for creating the storage engine under test.
     *
     * @param dataDir the data directory
     * @param meta    table metadata
     * @return a new storage engine instance
     */
    protected abstract StorageEngine createEngine(Path dataDir, TableMeta meta) throws IOException;

    /**
     * Abstract hook for concrete subclasses to verify that a dead tuple version exists.
     * Every storage engine implementation MUST override this to prove MVCC semantics
     * by scanning the raw heap and asserting two physical tuples exist after upsert.
     *
     * @param dataDir the data directory
     * @param meta    table metadata
     * @param pk      the primary key that was updated
     */
    protected abstract void assertDeadVersionExists(Path dataDir, TableMeta meta, long pk);

    private TableMeta createTestTableMeta() {
        Schema schema = Schema.of(
            com.coredb.api.Column.longCol("id").withNullable(false),
            com.coredb.api.Column.stringCol("name"),
            com.coredb.api.Column.intCol("age")
        );
        return new TableMeta(1002, "test_table", schema, "id", EngineType.BTREE);
    }

    @Test
    void putOfNewKey_thenGet_returnsRow() throws IOException {
        TableMeta meta = createTestTableMeta();
        try (BufferPool pool = new BufferPool();
             StorageEngine engine = createEngine(tempDir, meta)) {
            engine.open(tempDir, meta, pool, null, clog);

            Row row = Row.of(1L, "Alice", 30);
            engine.put(1L, row);

            Optional<Row> result = engine.get(1L);
            assertThat(result).isPresent();
            assertThat(result.get()).isEqualTo(row);
        }
    }

    @Test
    void putOfExistingKey_replacesVisibleRow_andLeavesDeadOldVersion() throws IOException {
        TableMeta meta = createTestTableMeta();
        Path dataDir = tempDir.resolve("data");
        java.nio.file.Files.createDirectories(dataDir);

        // First put
        try (BufferPool pool = new BufferPool();
             StorageEngine engine = createEngine(dataDir, meta)) {
            engine.open(dataDir, meta, pool, null, clog);
            Row original = Row.of(1L, "Alice", 30);
            engine.put(1L, original);
        }

        // Second put (upsert)
        try (BufferPool pool = new BufferPool();
             StorageEngine engine = createEngine(dataDir, meta)) {
            engine.open(dataDir, meta, pool, null, clog);
            Row updated = Row.of(1L, "Bob", 31);
            engine.put(1L, updated);

            // get returns the new visible row
            Optional<Row> result = engine.get(1L);
            assertThat(result).isPresent();
            assertThat(result.get()).isEqualTo(updated);
        }

        // Verify that a dead version exists (MVCC upsert, not in-place overwrite)
        assertDeadVersionExists(dataDir, meta, 1L);
    }

    @Test
    void delete_thenGet_returnsEmpty() throws IOException {
        TableMeta meta = createTestTableMeta();
        try (BufferPool pool = new BufferPool();
             StorageEngine engine = createEngine(tempDir, meta)) {
            engine.open(tempDir, meta, pool, null, clog);

            Row row = Row.of(1L, "Alice", 30);
            engine.put(1L, row);
            assertThat(engine.get(1L)).isPresent();

            engine.delete(1L);

            assertThat(engine.get(1L)).isEmpty();
        }
    }

    @Test
    void delete_nonExistentKey_isNoOp() throws IOException {
        TableMeta meta = createTestTableMeta();
        try (BufferPool pool = new BufferPool();
             StorageEngine engine = createEngine(tempDir, meta)) {
            engine.open(tempDir, meta, pool, null, clog);

            // Should not throw
            engine.delete(999L);

            assertThat(engine.get(999L)).isEmpty();
        }
    }

    @Test
    void rangeScan_returnsSortedAndInclusiveAtBothEnds() throws IOException {
        TableMeta meta = createTestTableMeta();
        try (BufferPool pool = new BufferPool();
             StorageEngine engine = createEngine(tempDir, meta)) {
            engine.open(tempDir, meta, pool, null, clog);

            // Insert in scrambled order
            engine.put(5L, Row.of(5L, "Eve", 25));
            engine.put(1L, Row.of(1L, "Alice", 30));
            engine.put(3L, Row.of(3L, "Charlie", 35));
            engine.put(2L, Row.of(2L, "Bob", 25));
            engine.put(4L, Row.of(4L, "Dana", 28));

            Iterator<Map.Entry<Long, Row>> it = engine.rangeScan(2L, 4L);

            // Should return keys 2, 3, 4 in order
            assertThat(it.hasNext()).isTrue();
            Map.Entry<Long, Row> first = it.next();
            assertThat(first.getKey()).isEqualTo(2L);

            assertThat(it.hasNext()).isTrue();
            Map.Entry<Long, Row> second = it.next();
            assertThat(second.getKey()).isEqualTo(3L);

            assertThat(it.hasNext()).isTrue();
            Map.Entry<Long, Row> third = it.next();
            assertThat(third.getKey()).isEqualTo(4L);

            assertThat(it.hasNext()).isFalse();
        }
    }

    @Test
    void rangeScan_emptyRange_returnsEmptyIterator() throws IOException {
        TableMeta meta = createTestTableMeta();
        try (BufferPool pool = new BufferPool();
             StorageEngine engine = createEngine(tempDir, meta)) {
            engine.open(tempDir, meta, pool, null, clog);

            engine.put(1L, Row.of(1L, "Alice", 30));
            engine.put(5L, Row.of(5L, "Eve", 25));

            // Range with from > to should return empty
            Iterator<Map.Entry<Long, Row>> it = engine.rangeScan(10L, 1L);
            assertThat(it.hasNext()).isFalse();
        }
    }

    @Test
    void rangeScan_fromGreaterThanMaxKey_returnsEmpty() throws IOException {
        TableMeta meta = createTestTableMeta();
        try (BufferPool pool = new BufferPool();
             StorageEngine engine = createEngine(tempDir, meta)) {
            engine.open(tempDir, meta, pool, null, clog);

            engine.put(1L, Row.of(1L, "Alice", 30));

            Iterator<Map.Entry<Long, Row>> it = engine.rangeScan(100L, 200L);
            assertThat(it.hasNext()).isFalse();
        }
    }

    @Test
    void fullScan_returnsAllLiveRowsExactlyOnce() throws IOException {
        TableMeta meta = createTestTableMeta();
        try (BufferPool pool = new BufferPool();
             StorageEngine engine = createEngine(tempDir, meta)) {
            engine.open(tempDir, meta, pool, null, clog);

            engine.put(1L, Row.of(1L, "Alice", 30));
            engine.put(2L, Row.of(2L, "Bob", 25));
            engine.put(3L, Row.of(3L, "Charlie", 35));

            long count = 0;
            for (Iterator<Map.Entry<Long, Row>> it = engine.fullScan(); it.hasNext(); ) {
                Map.Entry<Long, Row> entry = it.next();
                assertThat(entry.getKey()).isIn(1L, 2L, 3L);
                count++;
            }

            assertThat(count).isEqualTo(3);
        }
    }

    @Test
    void fullScan_afterDelete_excludesDeletedRows() throws IOException {
        TableMeta meta = createTestTableMeta();
        try (BufferPool pool = new BufferPool();
             StorageEngine engine = createEngine(tempDir, meta)) {
            engine.open(tempDir, meta, pool, null, clog);

            engine.put(1L, Row.of(1L, "Alice", 30));
            engine.put(2L, Row.of(2L, "Bob", 25));
            engine.put(3L, Row.of(3L, "Charlie", 35));

            engine.delete(2L);

            long count = 0;
            for (Iterator<Map.Entry<Long, Row>> it = engine.fullScan(); it.hasNext(); ) {
                Map.Entry<Long, Row> entry = it.next();
                assertThat(entry.getKey()).isIn(1L, 3L);
                count++;
            }

            assertThat(count).isEqualTo(2);
        }
    }

    @Test
    void closeThenReopen_preservesAllVisibleRows() throws IOException {
        TableMeta meta = createTestTableMeta();
        Path dataDir = tempDir.resolve("persistent");
        java.nio.file.Files.createDirectories(dataDir);

        // First session: insert rows
        try (BufferPool pool = new BufferPool();
             StorageEngine engine = createEngine(dataDir, meta)) {
            engine.open(dataDir, meta, pool, null, clog);
            engine.put(1L, Row.of(1L, "Alice", 30));
            engine.put(2L, Row.of(2L, "Bob", 25));
            engine.flush();
        }

        // Second session: verify persistence
        try (BufferPool pool = new BufferPool();
             StorageEngine engine = createEngine(dataDir, meta)) {
            engine.open(dataDir, meta, pool, null, clog);

            Optional<Row> alice = engine.get(1L);
            assertThat(alice).isPresent();
            assertThat(alice.get()).isEqualTo(Row.of(1L, "Alice", 30));

            Optional<Row> bob = engine.get(2L);
            assertThat(bob).isPresent();
            assertThat(bob.get()).isEqualTo(Row.of(2L, "Bob", 25));
        }
    }

    @Test
    void putAfterDelete_reusesKey() throws IOException {
        TableMeta meta = createTestTableMeta();
        try (BufferPool pool = new BufferPool();
             StorageEngine engine = createEngine(tempDir, meta)) {
            engine.open(tempDir, meta, pool, null, clog);

            // Insert, delete, re-insert with same PK
            Row original = Row.of(1L, "Alice", 30);
            engine.put(1L, original);
            engine.delete(1L);
            assertThat(engine.get(1L)).isEmpty();

            Row replacement = Row.of(1L, "Bob", 31);
            engine.put(1L, replacement);

            Optional<Row> result = engine.get(1L);
            assertThat(result).isPresent();
            assertThat(result.get()).isEqualTo(replacement);
        }
    }
}
