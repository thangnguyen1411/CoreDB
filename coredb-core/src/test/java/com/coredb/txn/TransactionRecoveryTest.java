package com.coredb.txn;

import com.coredb.api.Column;
import com.coredb.api.CoreDB;
import com.coredb.api.CoreDBConfig;
import com.coredb.api.Row;
import com.coredb.api.Schema;
import com.coredb.catalog.TableMeta;
import com.coredb.engine.StorageEngine;
import com.coredb.recovery.RecoveryStats;
import com.coredb.wal.XLogRecord;
import com.coredb.wal.XLogResourceManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Crash recovery tests for the transaction durability guarantee.
 *
 * <p>Covers the four ordering scenarios from the WAL → flush → clog protocol:
 * <ol>
 *   <li>Committed transaction survives crash (WAL + clog both durable)</li>
 *   <li>WAL durable but clog not yet updated → recovery rebuilds clog from WAL</li>
 *   <li>In-progress transaction (no commit) → recovery sweeps XID to ABORTED</li>
 *   <li>Rollback durable in WAL → recovery marks XID ABORTED, row invisible</li>
 * </ol>
 */
class TransactionRecoveryTest {

    private static final Schema SCHEMA = Schema.of(
        Column.longCol("id"),
        Column.stringCol("name"),
        Column.intCol("age")
    );

    private static TableMeta openOrCreateTable(CoreDB db, String name) throws IOException {
        if (db.catalog().openTable(name).isEmpty()) {
            db.catalog().createTable(name, SCHEMA, "id");
        }
        return db.catalog().openTable(name).get();
    }

    // -----------------------------------------------------------------------
    // Scenario 1: commit + crash → row visible after recovery
    // -----------------------------------------------------------------------

    @Test
    void commit_thenCrash_rowVisibleAfterRecovery(@TempDir Path tempDir) throws IOException {
        try (CoreDB db = CoreDB.open(tempDir, CoreDBConfig.defaults())) {
            TableMeta meta = openOrCreateTable(db, "users");
            StorageEngine engine = db.getEngineForTable(meta);

            Transaction tx = db.beginTransaction();
            engine.put(1L, Row.of(1L, "Alice", 30));
            db.transactionManager().commit(tx);

            db.simulateCrash();
        }

        CoreDB db2 = CoreDB.open(tempDir, CoreDBConfig.defaults());
        TableMeta meta2 = openOrCreateTable(db2, "users");
        StorageEngine engine2 = db2.getEngineForTable(meta2);

        Transaction readTx = db2.beginTransaction();
        assertThat(engine2.get(1L)).isPresent();
        Row row = engine2.get(1L).get();
        assertThat(row.get(1)).isEqualTo("Alice");
        db2.transactionManager().commit(readTx);

        RecoveryStats stats = db2.lastRecoveryStats();
        assertThat(stats.xactCommitReplayed()).isGreaterThan(0);

        db2.close();
    }

    // -----------------------------------------------------------------------
    // Scenario 2: WAL commit record durable, clog not yet updated
    //             Recovery must rebuild clog → row visible
    // -----------------------------------------------------------------------

    @Test
    void walCommitDurable_clogNotUpdated_rowVisibleAfterRecovery(@TempDir Path tempDir) throws IOException {
        int insertedXid;

        try (CoreDB db = CoreDB.open(tempDir, CoreDBConfig.defaults())) {
            TableMeta meta = openOrCreateTable(db, "users");
            StorageEngine engine = db.getEngineForTable(meta);

            // Begin a transaction and insert, but do not use TransactionManager.commit()
            // Instead, manually write XACT_COMMIT to WAL and flush it — this is exactly
            // what commit() does before calling clog.setCommitted(). We then crash
            // before clog.setCommitted() would have been called.
            Transaction tx = db.beginTransaction();
            insertedXid = tx.xid();
            engine.put(1L, Row.of(1L, "Bob", 25));

            // Write XACT_COMMIT to WAL and flush (durable). Do not call commit() on
            // the TransactionManager so clog.setCommitted() is never called.
            byte[] payload = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN)
                .putInt(insertedXid).putLong(System.currentTimeMillis()).array();
            long commitLsn = db.xlogWriter().append(
                XLogRecord.RMGR_XLOG, XLogResourceManager.XACT_COMMIT,
                insertedXid, 0, 0, payload);
            db.xlogWriter().flushUpTo(commitLsn);

            db.simulateCrash();
        }

        // Recovery should see XACT_COMMIT in WAL and call clog.setCommitted(insertedXid)
        CoreDB db2 = CoreDB.open(tempDir, CoreDBConfig.defaults());
        RecoveryStats stats = db2.lastRecoveryStats();
        assertThat(stats.xactCommitReplayed()).isGreaterThan(0);

        TableMeta meta2 = openOrCreateTable(db2, "users");
        StorageEngine engine2 = db2.getEngineForTable(meta2);

        Transaction readTx = db2.beginTransaction();
        assertThat(engine2.get(1L)).isPresent()
            .as("Row inserted by committed transaction must be visible after recovery");
        db2.transactionManager().commit(readTx);

        db2.close();
    }

    // -----------------------------------------------------------------------
    // Scenario 3: crash mid-transaction (no commit record in WAL)
    //             Recovery sweeps the XID to ABORTED → row invisible
    // -----------------------------------------------------------------------

    @Test
    void noCommit_thenCrash_rowInvisibleAfterRecovery(@TempDir Path tempDir) throws IOException {
        try (CoreDB db = CoreDB.open(tempDir, CoreDBConfig.defaults())) {
            TableMeta meta = openOrCreateTable(db, "users");
            StorageEngine engine = db.getEngineForTable(meta);

            Transaction tx = db.beginTransaction();
            engine.put(1L, Row.of(1L, "Carol", 28));
            // No commit — just crash
            db.simulateCrash();
        }

        // Recovery finds no XACT_COMMIT for the XID → in-progress sweep marks it ABORTED
        CoreDB db2 = CoreDB.open(tempDir, CoreDBConfig.defaults());
        RecoveryStats stats = db2.lastRecoveryStats();
        assertThat(stats.xidsSweptToAborted()).isGreaterThan(0);

        TableMeta meta2 = openOrCreateTable(db2, "users");
        StorageEngine engine2 = db2.getEngineForTable(meta2);

        Transaction readTx = db2.beginTransaction();
        assertThat(engine2.get(1L)).isEmpty()
            .as("Row from uncommitted transaction must be invisible after recovery");
        db2.transactionManager().commit(readTx);

        db2.close();
    }

    // -----------------------------------------------------------------------
    // Scenario 4: rollback durable in WAL, then crash
    //             Recovery replays XACT_ABORT → row invisible
    // -----------------------------------------------------------------------

    @Test
    void rollback_thenCrash_rowInvisibleAfterRecovery(@TempDir Path tempDir) throws IOException {
        try (CoreDB db = CoreDB.open(tempDir, CoreDBConfig.defaults())) {
            TableMeta meta = openOrCreateTable(db, "users");
            StorageEngine engine = db.getEngineForTable(meta);

            Transaction tx = db.beginTransaction();
            engine.put(1L, Row.of(1L, "Dave", 35));
            db.transactionManager().rollback(tx);

            db.simulateCrash();
        }

        CoreDB db2 = CoreDB.open(tempDir, CoreDBConfig.defaults());
        RecoveryStats stats = db2.lastRecoveryStats();
        // Either the XACT_ABORT was replayed from WAL, or the XID was swept to ABORTED
        assertThat(stats.xactAbortReplayed() + stats.xidsSweptToAborted()).isGreaterThan(0);

        TableMeta meta2 = openOrCreateTable(db2, "users");
        StorageEngine engine2 = db2.getEngineForTable(meta2);

        Transaction readTx = db2.beginTransaction();
        assertThat(engine2.get(1L)).isEmpty()
            .as("Rolled-back row must remain invisible after recovery");
        db2.transactionManager().commit(readTx);

        db2.close();
    }

    // -----------------------------------------------------------------------
    // Auto-commit: command without explicit begin is durable on crash
    // -----------------------------------------------------------------------

    @Test
    void autoCommit_thenCrash_rowVisibleAfterRecovery(@TempDir Path tempDir) throws IOException {
        // Use the shell's auto-commit by calling the engine within a manually managed
        // auto-begin/commit cycle (mirrors what LocalShellBackend does internally)
        try (CoreDB db = CoreDB.open(tempDir, CoreDBConfig.defaults())) {
            openOrCreateTable(db, "users");
            TableMeta meta = db.catalog().openTable("users").get();
            StorageEngine engine = db.getEngineForTable(meta);

            // Auto-commit: begin a transaction, do the work, commit
            Transaction tx = db.beginTransaction();
            engine.put(1L, Row.of(1L, "Eve", 22));
            db.transactionManager().commit(tx);

            db.simulateCrash();
        }

        CoreDB db2 = CoreDB.open(tempDir, CoreDBConfig.defaults());
        TableMeta meta2 = db2.catalog().openTable("users").get();
        StorageEngine engine2 = db2.getEngineForTable(meta2);

        Transaction readTx = db2.beginTransaction();
        assertThat(engine2.get(1L)).isPresent();
        db2.transactionManager().commit(readTx);

        db2.close();
    }

    // -----------------------------------------------------------------------
    // Commit visibility: rows committed by T1 are visible to T2 that begins after
    // -----------------------------------------------------------------------

    @Test
    void commitVisibility_subsequentTransactionSeesCommittedRows(@TempDir Path tempDir) throws IOException {
        CoreDB db = CoreDB.open(tempDir, CoreDBConfig.defaults());
        openOrCreateTable(db, "users");
        TableMeta meta = db.catalog().openTable("users").get();
        StorageEngine engine = db.getEngineForTable(meta);

        Transaction t1 = db.beginTransaction();
        engine.put(1L, Row.of(1L, "First", 1));
        db.transactionManager().commit(t1);

        Transaction t2 = db.beginTransaction();
        engine.put(2L, Row.of(2L, "Second", 2));
        db.transactionManager().commit(t2);

        // T3 starts after both commits and sees both rows
        Transaction t3 = db.beginTransaction();
        assertThat(engine.get(1L)).isPresent()
            .as("Row committed by T1 must be visible to T3");
        assertThat(engine.get(2L)).isPresent()
            .as("Row committed by T2 must be visible to T3");
        db.transactionManager().commit(t3);

        db.close();
    }

    // -----------------------------------------------------------------------
    // clog-from-WAL: committed row visible; aborted row invisible (no crash)
    // -----------------------------------------------------------------------

    @Test
    void committedRow_visibleToNewTransaction(@TempDir Path tempDir) throws IOException {
        CoreDB db = CoreDB.open(tempDir, CoreDBConfig.defaults());
        openOrCreateTable(db, "users");
        TableMeta meta = db.catalog().openTable("users").get();
        StorageEngine engine = db.getEngineForTable(meta);

        Transaction tx = db.beginTransaction();
        engine.put(1L, Row.of(1L, "Alice", 30));
        db.transactionManager().commit(tx);

        Transaction readTx = db.beginTransaction();
        assertThat(engine.get(1L)).isPresent();
        db.transactionManager().commit(readTx);

        db.close();
    }

    @Test
    void abortedRow_invisibleToNewTransaction(@TempDir Path tempDir) throws IOException {
        CoreDB db = CoreDB.open(tempDir, CoreDBConfig.defaults());
        openOrCreateTable(db, "users");
        TableMeta meta = db.catalog().openTable("users").get();
        StorageEngine engine = db.getEngineForTable(meta);

        Transaction tx = db.beginTransaction();
        engine.put(1L, Row.of(1L, "Alice", 30));
        db.transactionManager().rollback(tx);

        Transaction readTx = db.beginTransaction();
        assertThat(engine.get(1L)).isEmpty();
        db.transactionManager().commit(readTx);

        db.close();
    }
}
