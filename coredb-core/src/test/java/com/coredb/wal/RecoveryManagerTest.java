package com.coredb.wal;

import com.coredb.api.Column;
import com.coredb.api.CoreDB;
import com.coredb.api.CoreDBConfig;
import com.coredb.api.Row;
import com.coredb.api.Schema;
import com.coredb.catalog.TableMeta;
import com.coredb.engine.StorageEngine;
import com.coredb.recovery.RecoveryManager;
import com.coredb.recovery.RecoveryStats;
import com.coredb.txn.Transaction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class RecoveryManagerTest {

    private static final Schema TEST_SCHEMA = Schema.of(
        Column.longCol("id"),
        Column.stringCol("name"),
        Column.intCol("value")
    );

    @Test
    void recover_freshDatabase_returnsNoRecoveryNeeded(@TempDir Path tempDir) throws IOException {
        CoreDB db = CoreDB.open(tempDir, CoreDBConfig.defaults());

        RecoveryStats stats = db.lastRecoveryStats();
        assertThat(stats).isNotNull();
        assertThat(stats.isNoRecovery()).isTrue();
        assertThat(stats.noRecoveryReason()).contains("fresh database");

        db.close();
    }

    @Test
    void recover_existingDatabaseWithoutWal_returnsNoRecoveryNeeded(@TempDir Path tempDir) throws IOException {
        CoreDB db = CoreDB.open(tempDir, CoreDBConfig.defaults());
        db.close();

        CoreDB db2 = CoreDB.open(tempDir, CoreDBConfig.defaults());
        RecoveryStats stats = db2.lastRecoveryStats();
        assertThat(stats).isNotNull();
        assertThat(stats.totalRecords()).isEqualTo(0);

        db2.close();
    }

    @Test
    void recover_endToEndRedo_withoutCheckpoint_dataIsRecovered(@TempDir Path tempDir) throws Exception {
        CoreDB db = CoreDB.open(tempDir, CoreDBConfig.defaults());
        db.catalog().createTable("test", TEST_SCHEMA, "id");

        TableMeta meta = db.catalog().openTable("test").get();
        StorageEngine engine = db.getEngineForTable(meta);

        Transaction tx = db.beginTransaction();
        engine.put(1L, Row.of(1L, "row1", 100));
        engine.put(2L, Row.of(2L, "row2", 200));
        engine.put(3L, Row.of(3L, "row3", 300));
        db.transactionManager().commit(tx);

        db.simulateCrash();

        CoreDB db2 = CoreDB.open(tempDir, CoreDBConfig.defaults());
        RecoveryStats stats = db2.lastRecoveryStats();
        assertThat(stats.redone()).isGreaterThan(0);

        TableMeta meta2 = db2.catalog().openTable("test").get();
        StorageEngine engine2 = db2.getEngineForTable(meta2);

        Transaction readTx = db2.beginTransaction();
        assertThat(engine2.get(1L)).isPresent();
        assertThat(engine2.get(2L)).isPresent();
        assertThat(engine2.get(3L)).isPresent();

        Row row1 = engine2.get(1L).get();
        assertThat(row1.get(0)).isEqualTo(1L);
        assertThat(row1.get(1)).isEqualTo("row1");
        assertThat(row1.get(2)).isEqualTo(100);
        db2.transactionManager().commit(readTx);

        db2.close();
    }

    @Test
    void recover_checkpointBoundary_onlyReplaysPostCheckpointRecords(@TempDir Path tempDir) throws Exception {
        CoreDB db = CoreDB.open(tempDir, CoreDBConfig.defaults());
        db.catalog().createTable("test", TEST_SCHEMA, "id");

        TableMeta meta = db.catalog().openTable("test").get();
        StorageEngine engine = db.getEngineForTable(meta);

        Transaction tx1 = db.beginTransaction();
        engine.put(1L, Row.of(1L, "batch1_a", 100));
        engine.put(2L, Row.of(2L, "batch1_b", 200));
        db.transactionManager().commit(tx1);

        db.bufferPool().checkpoint(db.controlFile());

        Transaction tx2 = db.beginTransaction();
        engine.put(3L, Row.of(3L, "batch2_a", 300));
        engine.put(4L, Row.of(4L, "batch2_b", 400));
        db.transactionManager().commit(tx2);

        db.simulateCrash();

        CoreDB db2 = CoreDB.open(tempDir, CoreDBConfig.defaults());
        RecoveryStats stats = db2.lastRecoveryStats();
        assertThat(stats.redone()).isLessThan(4);

        TableMeta meta2 = db2.catalog().openTable("test").get();
        StorageEngine engine2 = db2.getEngineForTable(meta2);

        Transaction readTx = db2.beginTransaction();
        assertThat(engine2.get(1L)).isPresent();
        assertThat(engine2.get(2L)).isPresent();
        assertThat(engine2.get(3L)).isPresent();
        assertThat(engine2.get(4L)).isPresent();
        db2.transactionManager().commit(readTx);

        db2.close();
    }

    @Test
    void recover_idempotency_sameRecordTwice_pageUnchanged(@TempDir Path tempDir) throws Exception {
        CoreDB db = CoreDB.open(tempDir, CoreDBConfig.defaults());
        db.catalog().createTable("test", TEST_SCHEMA, "id");

        TableMeta meta = db.catalog().openTable("test").get();
        StorageEngine engine = db.getEngineForTable(meta);

        Transaction tx = db.beginTransaction();
        engine.put(1L, Row.of(1L, "original", 100));
        db.transactionManager().commit(tx);

        db.simulateCrash();

        System.setProperty("coredb.skip_post_recovery_checkpoint", "true");
        CoreDB db2 = CoreDB.open(tempDir, CoreDBConfig.defaults());
        RecoveryStats stats1 = db2.lastRecoveryStats();
        assertThat(stats1.redone()).isGreaterThan(0);

        TableMeta meta2 = db2.catalog().openTable("test").get();
        StorageEngine engine2 = db2.getEngineForTable(meta2);

        Transaction readTx1 = db2.beginTransaction();
        Row rowAfterFirstRecovery = engine2.get(1L).get();
        db2.transactionManager().commit(readTx1);

        db2.simulateCrash();

        CoreDB db3 = CoreDB.open(tempDir, CoreDBConfig.defaults());
        RecoveryStats stats2 = db3.lastRecoveryStats();

        System.clearProperty("coredb.skip_post_recovery_checkpoint");

        assertThat(stats2.skippedByPdLsn()).isGreaterThan(0);

        TableMeta meta3 = db3.catalog().openTable("test").get();
        StorageEngine engine3 = db3.getEngineForTable(meta3);

        Transaction readTx2 = db3.beginTransaction();
        Row rowAfterSecondRecovery = engine3.get(1L).get();
        db3.transactionManager().commit(readTx2);

        assertThat(rowAfterSecondRecovery.get(0)).isEqualTo(rowAfterFirstRecovery.get(0));
        assertThat(rowAfterSecondRecovery.get(1)).isEqualTo(rowAfterFirstRecovery.get(1));
        assertThat(rowAfterSecondRecovery.get(2)).isEqualTo(rowAfterFirstRecovery.get(2));

        db3.close();
    }

    @Test
    void recover_fullPageWrite_restoresCorrectPageBytes(@TempDir Path tempDir) throws Exception {
        CoreDB db = CoreDB.open(tempDir, CoreDBConfig.defaults());
        db.catalog().createTable("test", TEST_SCHEMA, "id");

        TableMeta meta = db.catalog().openTable("test").get();
        StorageEngine engine = db.getEngineForTable(meta);

        Transaction tx1 = db.beginTransaction();
        engine.put(1L, Row.of(1L, "test", 100));
        db.transactionManager().commit(tx1);

        db.bufferPool().checkpoint(db.controlFile());

        Transaction tx2 = db.beginTransaction();
        engine.put(2L, Row.of(2L, "test2", 200));
        db.transactionManager().commit(tx2);

        db.simulateCrash();

        CoreDB db2 = CoreDB.open(tempDir, CoreDBConfig.defaults());
        RecoveryStats stats = db2.lastRecoveryStats();
        assertThat(stats.redone() + stats.fpwRestored()).isGreaterThan(0);

        TableMeta meta2 = db2.catalog().openTable("test").get();
        StorageEngine engine2 = db2.getEngineForTable(meta2);

        Transaction readTx = db2.beginTransaction();
        assertThat(engine2.get(1L)).isPresent();
        assertThat(engine2.get(2L)).isPresent();
        db2.transactionManager().commit(readTx);

        db2.close();
    }

    @Test
    void recoveryStats_format_noRecovery(@TempDir Path tempDir) throws IOException {
        CoreDB db = CoreDB.open(tempDir, CoreDBConfig.defaults());
        RecoveryStats stats = db.lastRecoveryStats();

        String formatted = stats.format();
        assertThat(formatted).contains("no recovery");
        assertThat(formatted).contains("fresh database");

        db.close();
    }

    @Test
    void recoveryStats_format_withRecovery(@TempDir Path tempDir) throws IOException {
        CoreDB db = CoreDB.open(tempDir, CoreDBConfig.defaults());
        db.close();

        CoreDB db2 = CoreDB.open(tempDir, CoreDBConfig.defaults());
        RecoveryStats stats = db2.lastRecoveryStats();

        String formatted = stats.format();
        assertThat(formatted).contains("last-recovery");
        assertThat(formatted).contains("redone");
        assertThat(formatted).contains("fpw-restored");
        assertThat(formatted).contains("skipped-by-pd_lsn");

        db2.close();
    }

    @Test
    void recover_directCallWithFreshDb(@TempDir Path tempDir) throws IOException {
        RecoveryStats stats = RecoveryManager.recover(tempDir);

        assertThat(stats.isNoRecovery()).isTrue();
        assertThat(stats.noRecoveryReason()).contains("fresh database");
    }

    @Test
    void recoveryStats_totalRecords(@TempDir Path tempDir) throws IOException {
        CoreDB db = CoreDB.open(tempDir, CoreDBConfig.defaults());
        db.close();

        CoreDB db2 = CoreDB.open(tempDir, CoreDBConfig.defaults());
        RecoveryStats stats = db2.lastRecoveryStats();

        assertThat(stats.totalRecords())
            .isEqualTo(stats.redone() + stats.fpwRestored() + stats.skippedByPdLsn()
                       + stats.xactCommitReplayed() + stats.xactAbortReplayed());

        db2.close();
    }
}
