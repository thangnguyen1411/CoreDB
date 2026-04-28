package com.coredb.engine;

import com.coredb.api.CoreDBConfig;
import com.coredb.api.Row;
import com.coredb.api.Schema;
import com.coredb.buffer.BufferPool;
import com.coredb.catalog.ControlFile;
import com.coredb.catalog.TableMeta;
import com.coredb.config.EngineType;
import com.coredb.mvcc.SnapshotManager;
import com.coredb.txn.ClogManager;
import com.coredb.txn.Transaction;
import com.coredb.txn.TransactionManager;
import com.coredb.util.Constants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    protected ClogManager clog;
    protected ControlFile controlFile;
    protected SnapshotManager snapshotManager;
    protected TransactionManager transactionManager;

    @BeforeEach
    void setUp() throws Exception {
        Files.createDirectories(tempDir.resolve("global"));
        clog = ClogManager.create(tempDir);
        controlFile = ControlFile.create(tempDir, CoreDBConfig.defaults());
        snapshotManager = new SnapshotManager(Constants.FIRST_NORMAL_XID);
        transactionManager = new TransactionManager(controlFile, snapshotManager, clog);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (clog != null) {
            clog.close();
        }
        if (controlFile != null) {
            controlFile.close();
        }
    }

    protected abstract StorageEngine createEngine(Path dataDir, TableMeta meta) throws IOException;

    /**
     * Abstract hook for concrete subclasses to verify that a dead tuple version exists.
     * Every storage engine implementation MUST override this to prove MVCC semantics
     * by scanning the raw heap and asserting two physical tuples exist after upsert.
     */
    protected abstract void assertDeadVersionExists(Path dataDir, TableMeta meta, long pk);

    protected TableMeta createTestTableMeta() {
        Schema schema = Schema.of(
            com.coredb.api.Column.longCol("id").withNullable(false),
            com.coredb.api.Column.stringCol("name"),
            com.coredb.api.Column.intCol("age")
        );
        return new TableMeta(1002, "test_table", schema, "id", EngineType.BTREE);
    }

    protected Transaction begin() throws IOException {
        return transactionManager.beginTransaction();
    }

    protected void commit(Transaction tx) throws IOException {
        transactionManager.commit(tx);
    }

    protected void rollback(Transaction tx) throws IOException {
        transactionManager.rollback(tx);
    }

    @Test
    void putOfNewKey_thenGet_returnsRow() throws IOException {
        TableMeta meta = createTestTableMeta();
        try (BufferPool pool = new BufferPool();
             StorageEngine engine = createEngine(tempDir, meta)) {
            engine.open(tempDir, meta, pool, null, clog, transactionManager);

            Transaction tx = begin();
            Row row = Row.of(1L, "Alice", 30);
            engine.put(1L, row);

            Optional<Row> result = engine.get(1L);
            assertThat(result).isPresent();
            assertThat(result.get()).isEqualTo(row);
            commit(tx);
        }
    }

    @Test
    void putOfExistingKey_replacesVisibleRow_andLeavesDeadOldVersion() throws IOException {
        TableMeta meta = createTestTableMeta();
        Path dataDir = tempDir.resolve("data");
        Files.createDirectories(dataDir);
        Files.createDirectories(dataDir.resolve("global"));

        try (BufferPool pool = new BufferPool();
             StorageEngine engine = createEngine(dataDir, meta)) {
            engine.open(dataDir, meta, pool, null, clog, transactionManager);
            Transaction tx = begin();
            Row original = Row.of(1L, "Alice", 30);
            engine.put(1L, original);
            commit(tx);
        }

        try (BufferPool pool = new BufferPool();
             StorageEngine engine = createEngine(dataDir, meta)) {
            engine.open(dataDir, meta, pool, null, clog, transactionManager);
            Transaction tx = begin();
            Row updated = Row.of(1L, "Bob", 31);
            engine.put(1L, updated);

            // get returns the new visible row (own write)
            Optional<Row> result = engine.get(1L);
            assertThat(result).isPresent();
            assertThat(result.get()).isEqualTo(updated);
            commit(tx);
        }

        // Verify that a dead version exists (MVCC upsert, not in-place overwrite)
        assertDeadVersionExists(dataDir, meta, 1L);
    }

    @Test
    void delete_thenGet_returnsEmpty() throws IOException {
        TableMeta meta = createTestTableMeta();
        try (BufferPool pool = new BufferPool();
             StorageEngine engine = createEngine(tempDir, meta)) {
            engine.open(tempDir, meta, pool, null, clog, transactionManager);

            Transaction tx = begin();
            Row row = Row.of(1L, "Alice", 30);
            engine.put(1L, row);
            assertThat(engine.get(1L)).isPresent();

            engine.delete(1L);

            assertThat(engine.get(1L)).isEmpty();
            commit(tx);
        }
    }

    @Test
    void delete_nonExistentKey_isNoOp() throws IOException {
        TableMeta meta = createTestTableMeta();
        try (BufferPool pool = new BufferPool();
             StorageEngine engine = createEngine(tempDir, meta)) {
            engine.open(tempDir, meta, pool, null, clog, transactionManager);

            Transaction tx = begin();
            engine.delete(999L);
            assertThat(engine.get(999L)).isEmpty();
            commit(tx);
        }
    }

    @Test
    void rangeScan_returnsSortedAndInclusiveAtBothEnds() throws IOException {
        TableMeta meta = createTestTableMeta();
        try (BufferPool pool = new BufferPool();
             StorageEngine engine = createEngine(tempDir, meta)) {
            engine.open(tempDir, meta, pool, null, clog, transactionManager);

            Transaction tx = begin();
            engine.put(5L, Row.of(5L, "Eve", 25));
            engine.put(1L, Row.of(1L, "Alice", 30));
            engine.put(3L, Row.of(3L, "Charlie", 35));
            engine.put(2L, Row.of(2L, "Bob", 25));
            engine.put(4L, Row.of(4L, "Dana", 28));

            Iterator<Map.Entry<Long, Row>> it = engine.rangeScan(2L, 4L);

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
            commit(tx);
        }
    }

    @Test
    void rangeScan_emptyRange_returnsEmptyIterator() throws IOException {
        TableMeta meta = createTestTableMeta();
        try (BufferPool pool = new BufferPool();
             StorageEngine engine = createEngine(tempDir, meta)) {
            engine.open(tempDir, meta, pool, null, clog, transactionManager);

            Transaction tx = begin();
            engine.put(1L, Row.of(1L, "Alice", 30));
            engine.put(5L, Row.of(5L, "Eve", 25));

            Iterator<Map.Entry<Long, Row>> it = engine.rangeScan(10L, 1L);
            assertThat(it.hasNext()).isFalse();
            commit(tx);
        }
    }

    @Test
    void rangeScan_fromGreaterThanMaxKey_returnsEmpty() throws IOException {
        TableMeta meta = createTestTableMeta();
        try (BufferPool pool = new BufferPool();
             StorageEngine engine = createEngine(tempDir, meta)) {
            engine.open(tempDir, meta, pool, null, clog, transactionManager);

            Transaction tx = begin();
            engine.put(1L, Row.of(1L, "Alice", 30));

            Iterator<Map.Entry<Long, Row>> it = engine.rangeScan(100L, 200L);
            assertThat(it.hasNext()).isFalse();
            commit(tx);
        }
    }

    @Test
    void fullScan_returnsAllLiveRowsExactlyOnce() throws IOException {
        TableMeta meta = createTestTableMeta();
        try (BufferPool pool = new BufferPool();
             StorageEngine engine = createEngine(tempDir, meta)) {
            engine.open(tempDir, meta, pool, null, clog, transactionManager);

            Transaction tx = begin();
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
            commit(tx);
        }
    }

    @Test
    void fullScan_afterDelete_excludesDeletedRows() throws IOException {
        TableMeta meta = createTestTableMeta();
        try (BufferPool pool = new BufferPool();
             StorageEngine engine = createEngine(tempDir, meta)) {
            engine.open(tempDir, meta, pool, null, clog, transactionManager);

            Transaction tx = begin();
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
            commit(tx);
        }
    }

    @Test
    void closeThenReopen_preservesAllVisibleRows() throws IOException {
        TableMeta meta = createTestTableMeta();
        Path dataDir = tempDir.resolve("persistent");
        Files.createDirectories(dataDir);
        Files.createDirectories(dataDir.resolve("global"));

        try (BufferPool pool = new BufferPool();
             StorageEngine engine = createEngine(dataDir, meta)) {
            engine.open(dataDir, meta, pool, null, clog, transactionManager);
            Transaction tx = begin();
            engine.put(1L, Row.of(1L, "Alice", 30));
            engine.put(2L, Row.of(2L, "Bob", 25));
            commit(tx);
            engine.flush();
        }

        // same transactionManager — shared ControlFile tracks nextXid across sessions
        try (BufferPool pool = new BufferPool();
             StorageEngine engine = createEngine(dataDir, meta)) {
            engine.open(dataDir, meta, pool, null, clog, transactionManager);
            Transaction tx = begin();

            Optional<Row> alice = engine.get(1L);
            assertThat(alice).isPresent();
            assertThat(alice.get()).isEqualTo(Row.of(1L, "Alice", 30));

            Optional<Row> bob = engine.get(2L);
            assertThat(bob).isPresent();
            assertThat(bob.get()).isEqualTo(Row.of(2L, "Bob", 25));
            commit(tx);
        }
    }

    @Test
    void putAfterDelete_reusesKey() throws IOException {
        TableMeta meta = createTestTableMeta();
        try (BufferPool pool = new BufferPool();
             StorageEngine engine = createEngine(tempDir, meta)) {
            engine.open(tempDir, meta, pool, null, clog, transactionManager);

            Transaction tx = begin();
            Row original = Row.of(1L, "Alice", 30);
            engine.put(1L, original);
            engine.delete(1L);
            assertThat(engine.get(1L)).isEmpty();

            Row replacement = Row.of(1L, "Bob", 31);
            engine.put(1L, replacement);

            Optional<Row> result = engine.get(1L);
            assertThat(result).isPresent();
            assertThat(result.get()).isEqualTo(replacement);
            commit(tx);
        }
    }

    @Test
    void rollback_thenGet_rowIsInvisibleToNewTransaction() throws IOException {
        TableMeta meta = createTestTableMeta();
        try (BufferPool pool = new BufferPool();
             StorageEngine engine = createEngine(tempDir, meta)) {
            engine.open(tempDir, meta, pool, null, clog, transactionManager);

            Transaction tx = begin();
            engine.put(1L, Row.of(1L, "Alice", 30));
            rollback(tx);

            Transaction tx2 = begin();
            assertThat(engine.get(1L)).isEmpty();
            commit(tx2);
        }
    }

    @Test
    void engineCallWithoutTransaction_throwsIllegalStateException() throws IOException {
        TableMeta meta = createTestTableMeta();
        try (BufferPool pool = new BufferPool();
             StorageEngine engine = createEngine(tempDir, meta)) {
            engine.open(tempDir, meta, pool, null, clog, transactionManager);

            assertThatThrownBy(() -> engine.put(1L, Row.of(1L, "Alice", 30)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no active transaction");
        }
    }
}
