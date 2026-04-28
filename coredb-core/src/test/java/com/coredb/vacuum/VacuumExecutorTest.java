package com.coredb.vacuum;

import static org.assertj.core.api.Assertions.assertThat;

import com.coredb.api.Column;
import com.coredb.api.Row;
import com.coredb.api.Schema;
import com.coredb.catalog.TableMeta;
import com.coredb.engine.BTreeStorageEngine;
import com.coredb.heap.HeapFile;
import com.coredb.heap.RecordId;
import com.coredb.index.BTree;
import com.coredb.index.IndexFile;
import com.coredb.mvcc.Snapshot;
import com.coredb.txn.ClogManager;
import com.coredb.util.Constants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

class VacuumExecutorTest {

    @TempDir
    Path tempDir;

    private ClogManager clog;
    private HeapFile heap;
    private BTree pkIndex;

    private static final Schema SCHEMA = Schema.of(
            Column.longCol("id"),
            Column.stringCol("name")
    );

    @BeforeEach
    void setUp() throws Exception {
        clog = ClogManager.create(tempDir);

        Path heapPath = tempDir.resolve("heap");
        heap = HeapFile.create(heapPath, 100, SCHEMA);

        Path indexPath = tempDir.resolve("index_pk");
        IndexFile indexFile = IndexFile.create(indexPath, 200);
        pkIndex = BTree.create(indexFile);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (heap != null) heap.close();
        if (pkIndex != null) pkIndex.indexFile().close();
        if (clog != null) clog.close();
    }

    @Test
    void vacuum_noDeadTuples_returnsZeroCounts() throws Exception {
        insertRow(1L, "Alice", Constants.BOOTSTRAP_XID);
        insertRow(2L, "Bob", Constants.BOOTSTRAP_XID);

        VacuumStats stats = runVacuum(Integer.MAX_VALUE);

        assertThat(stats.deadTuples()).isZero();
        assertThat(stats.indexEntriesRemoved()).isZero();
        assertThat(stats.bytesReclaimed()).isZero();
        assertThat(stats.pagesScanned()).isGreaterThan(0);
    }

    @Test
    void vacuum_deadTuple_removedFromHeapAndIndex() throws Exception {
        int insertXid = 3;
        clog.setCommitted(insertXid);
        RecordId rid = insertRow(1L, "Alice", insertXid);
        pkIndex.insert(1L, rid);

        int deleteXid = 4;
        clog.setCommitted(deleteXid);
        heap.delete(rid, deleteXid);

        // oldestXmin > deleteXid means deletion is behind all readers
        VacuumStats stats = runVacuum(100);

        assertThat(stats.deadTuples()).isEqualTo(1);
        assertThat(stats.indexEntriesRemoved()).isEqualTo(1);
        assertThat(stats.bytesReclaimed()).isPositive();

        // Index must no longer contain the deleted key
        assertThat(pkIndex.search(1L)).isEmpty();
    }

    @Test
    void vacuum_abortedInsert_reclaimedWithoutIndexCleanup() throws Exception {
        int abortedXid = 5;
        clog.setAborted(abortedXid);
        insertRow(1L, "Ghost", abortedXid);
        // Deliberately do NOT insert into index (aborted inserts never get index entries)

        VacuumStats stats = runVacuum(Integer.MAX_VALUE);

        assertThat(stats.deadTuples()).isEqualTo(1);
        assertThat(stats.indexEntriesRemoved()).isZero();
    }

    @Test
    void vacuum_deletedButBelowHorizon_notReclaimed() throws Exception {
        int insertXid = 3;
        clog.setCommitted(insertXid);
        RecordId rid = insertRow(1L, "Alice", insertXid);
        pkIndex.insert(1L, rid);

        int deleteXid = 50;
        clog.setCommitted(deleteXid);
        heap.delete(rid, deleteXid);

        // oldestXmin == deleteXid — horizon exactly at the delete xid, not strictly less
        VacuumStats stats = runVacuum(50);

        assertThat(stats.deadTuples()).isZero();
        // Index entry must still be present
        assertThat(pkIndex.search(1L)).isPresent();
    }

    @Test
    void vacuum_multipleDeadTuples_allReclaimedAndIndexCleaned() throws Exception {
        int xid = 3;
        clog.setCommitted(xid);

        for (long pk = 1; pk <= 5; pk++) {
            RecordId rid = insertRow(pk, "Row" + pk, xid);
            pkIndex.insert(pk, rid);
        }

        int deleteXid = 4;
        clog.setCommitted(deleteXid);

        // Delete odd rows: 1, 3, 5
        for (long pk : new long[]{1L, 3L, 5L}) {
            Optional<RecordId> rid = pkIndex.search(pk);
            assertThat(rid).isPresent();
            heap.delete(rid.get(), deleteXid);
        }

        VacuumStats stats = runVacuum(100);

        assertThat(stats.deadTuples()).isEqualTo(3);
        assertThat(stats.indexEntriesRemoved()).isEqualTo(3);

        // Even rows survive
        assertThat(pkIndex.search(2L)).isPresent();
        assertThat(pkIndex.search(4L)).isPresent();

        // Odd rows are gone from the index
        assertThat(pkIndex.search(1L)).isEmpty();
        assertThat(pkIndex.search(3L)).isEmpty();
        assertThat(pkIndex.search(5L)).isEmpty();
    }

    @Test
    void vacuum_idempotent_secondRunFindsNoDeadTuples() throws Exception {
        int xid = 3;
        clog.setCommitted(xid);
        RecordId rid = insertRow(1L, "Alice", xid);
        pkIndex.insert(1L, rid);

        int deleteXid = 4;
        clog.setCommitted(deleteXid);
        heap.delete(rid, deleteXid);

        VacuumStats first = runVacuum(100);
        assertThat(first.deadTuples()).isEqualTo(1);

        VacuumStats second = runVacuum(100);
        assertThat(second.deadTuples()).isZero();
        assertThat(second.bytesReclaimed()).isZero();
    }

    @Test
    void vacuum_deletedRowNoLongerReturnedByHeapGet() throws Exception {
        int xid = 3;
        clog.setCommitted(xid);
        RecordId rid = insertRow(1L, "Alice", xid);
        pkIndex.insert(1L, rid);

        int deleteXid = 4;
        clog.setCommitted(deleteXid);
        heap.delete(rid, deleteXid);

        runVacuum(100);

        // After vacuum, the slot is LP_UNUSED; get() returns empty regardless of snapshot
        Optional<Row> row = heap.get(rid, Snapshot.BOOTSTRAP, clog);
        assertThat(row).isEmpty();
    }

    // ---- helpers ----

    private RecordId insertRow(long pk, String name, int xid) throws IOException {
        Row row = Row.of(pk, name);
        return heap.insert(row, xid);
    }

    private VacuumStats runVacuum(int oldestXmin) throws IOException {
        VacuumExecutor executor = new VacuumExecutor(heap, List.of(pkIndex), null, clog);
        return executor.vacuum(oldestXmin);
    }
}
