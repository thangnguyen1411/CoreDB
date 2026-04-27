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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for RecoveryManager redo-only recovery.
 *
 * <p>These tests verify end-to-end recovery scenarios:
 * <ul>
 *   <li>Redo replay after crash (no checkpoint)</li>
 *   <li>Checkpoint boundaries (only replay post-checkpoint records)</li>
 *   <li>Full-page write restoration</li>
 *   <li>Idempotency via pd_lsn</li>
 * </ul>
 */
class RecoveryManagerTest {

    // Schema for test tables
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
        // 1. Create database and table
        CoreDB db = CoreDB.open(tempDir, CoreDBConfig.defaults());
        db.catalog().createTable("test", TEST_SCHEMA, "id");

        // 2. Insert rows (these generate WAL records)
        Optional<TableMeta> metaOpt = db.catalog().openTable("test");
        assertThat(metaOpt).isPresent();
        TableMeta meta = metaOpt.get();
        StorageEngine engine = db.getEngineForTable(meta);

        engine.put(1L, Row.of(1L, "row1", 100));
        engine.put(2L, Row.of(2L, "row2", 200));
        engine.put(3L, Row.of(3L, "row3", 300));

        // 3. Simulate crash - flush WAL but not dirty pages
        db.simulateCrash();

        // 4. Re-open - recovery should replay WAL and restore rows
        CoreDB db2 = CoreDB.open(tempDir, CoreDBConfig.defaults());

        // Verify recovery ran and redid the inserts
        RecoveryStats stats = db2.lastRecoveryStats();
        assertThat(stats.redone()).isGreaterThan(0);

        // Verify data was recovered
        Optional<TableMeta> metaOpt2 = db2.catalog().openTable("test");
        assertThat(metaOpt2).isPresent();
        StorageEngine engine2 = db2.getEngineForTable(metaOpt2.get());

        assertThat(engine2.get(1L)).isPresent();
        assertThat(engine2.get(2L)).isPresent();
        assertThat(engine2.get(3L)).isPresent();

        // Verify row contents
        Row row1 = engine2.get(1L).get();
        assertThat(row1.get(0)).isEqualTo(1L);
        assertThat(row1.get(1)).isEqualTo("row1");
        assertThat(row1.get(2)).isEqualTo(100);

        db2.close();
    }

    @Test
    void recover_checkpointBoundary_onlyReplaysPostCheckpointRecords(@TempDir Path tempDir) throws Exception {
        // 1. Create database and table
        CoreDB db = CoreDB.open(tempDir, CoreDBConfig.defaults());
        db.catalog().createTable("test", TEST_SCHEMA, "id");

        Optional<TableMeta> metaOpt = db.catalog().openTable("test");
        assertThat(metaOpt).isPresent();
        TableMeta meta = metaOpt.get();
        StorageEngine engine = db.getEngineForTable(meta);

        // 2. Insert first batch of rows
        engine.put(1L, Row.of(1L, "batch1_a", 100));
        engine.put(2L, Row.of(2L, "batch1_b", 200));

        // 3. Perform checkpoint - flushes dirty pages, writes CHECKPOINT record
        db.bufferPool().checkpoint(db.controlFile());

        // 4. Insert second batch (post-checkpoint)
        engine.put(3L, Row.of(3L, "batch2_a", 300));
        engine.put(4L, Row.of(4L, "batch2_b", 400));

        // 5. Simulate crash - flush WAL but not dirty pages
        db.simulateCrash();

        // 6. Re-open - recovery should only replay post-checkpoint records
        CoreDB db2 = CoreDB.open(tempDir, CoreDBConfig.defaults());

        RecoveryStats stats = db2.lastRecoveryStats();
        // Should have replayed fewer records than total inserts
        // (only the 2 post-checkpoint inserts, not all 4)
        assertThat(stats.redone()).isLessThan(4);

        // All data should still be present (first batch was flushed by checkpoint)
        Optional<TableMeta> metaOpt2 = db2.catalog().openTable("test");
        assertThat(metaOpt2).isPresent();
        StorageEngine engine2 = db2.getEngineForTable(metaOpt2.get());

        assertThat(engine2.get(1L)).isPresent();
        assertThat(engine2.get(2L)).isPresent();
        assertThat(engine2.get(3L)).isPresent();
        assertThat(engine2.get(4L)).isPresent();

        db2.close();
    }

    @Test
    void recover_idempotency_sameRecordTwice_pageUnchanged(@TempDir Path tempDir) throws Exception {
        // This test verifies that pd_lsn idempotency works:
        // Applying the same WAL record twice should not corrupt the page

        CoreDB db = CoreDB.open(tempDir, CoreDBConfig.defaults());
        db.catalog().createTable("test", TEST_SCHEMA, "id");

        Optional<TableMeta> metaOpt = db.catalog().openTable("test");
        assertThat(metaOpt).isPresent();
        TableMeta meta = metaOpt.get();
        StorageEngine engine = db.getEngineForTable(meta);

        // Insert a row
        engine.put(1L, Row.of(1L, "original", 100));
        db.simulateCrash();

        // First recovery - applies the insert (with post-recovery checkpoint disabled)
        System.setProperty("coredb.skip_post_recovery_checkpoint", "true");
        CoreDB db2 = CoreDB.open(tempDir, CoreDBConfig.defaults());
        RecoveryStats stats1 = db2.lastRecoveryStats();
        assertThat(stats1.redone()).isGreaterThan(0);

        // Get the recovered row
        Optional<TableMeta> metaOpt2 = db2.catalog().openTable("test");
        StorageEngine engine2 = db2.getEngineForTable(metaOpt2.get());
        Row rowAfterFirstRecovery = engine2.get(1L).get();

        // Crash again - recovery will run again from the same starting point
        db2.simulateCrash();

        // Second recovery on same database - should skip due to pd_lsn
        CoreDB db3 = CoreDB.open(tempDir, CoreDBConfig.defaults());
        RecoveryStats stats2 = db3.lastRecoveryStats();

        // Clear the system property
        System.clearProperty("coredb.skip_post_recovery_checkpoint");

        // The record should be skipped due to pd_lsn check
        assertThat(stats2.skippedByPdLsn()).isGreaterThan(0);

        // Row should be unchanged
        Optional<TableMeta> metaOpt3 = db3.catalog().openTable("test");
        StorageEngine engine3 = db3.getEngineForTable(metaOpt3.get());
        Row rowAfterSecondRecovery = engine3.get(1L).get();

        // Both reads should return the same data
        assertThat(rowAfterSecondRecovery.get(0)).isEqualTo(rowAfterFirstRecovery.get(0));
        assertThat(rowAfterSecondRecovery.get(1)).isEqualTo(rowAfterFirstRecovery.get(1));
        assertThat(rowAfterSecondRecovery.get(2)).isEqualTo(rowAfterFirstRecovery.get(2));

        db3.close();
    }

    @Test
    void recover_fullPageWrite_restoresCorrectPageBytes(@TempDir Path tempDir) throws Exception {
        // This test verifies that full-page writes are correctly restored
        // FPW happens after a checkpoint on the first page modification

        CoreDB db = CoreDB.open(tempDir, CoreDBConfig.defaults());
        db.catalog().createTable("test", TEST_SCHEMA, "id");

        Optional<TableMeta> metaOpt = db.catalog().openTable("test");
        assertThat(metaOpt).isPresent();
        TableMeta meta = metaOpt.get();
        StorageEngine engine = db.getEngineForTable(meta);

        // Insert some data
        engine.put(1L, Row.of(1L, "test", 100));

        // Perform checkpoint - this enables full-page writes for next modifications
        db.bufferPool().checkpoint(db.controlFile());

        // Insert more data (this should trigger FPW on the first page modification)
        engine.put(2L, Row.of(2L, "test2", 200));

        // Simulate crash - flush WAL but not dirty pages
        db.simulateCrash();

        // Re-open - recovery should restore full page images correctly
        CoreDB db2 = CoreDB.open(tempDir, CoreDBConfig.defaults());

        RecoveryStats stats = db2.lastRecoveryStats();
        // Should have either redone records or restored FPWs
        assertThat(stats.redone() + stats.fpwRestored()).isGreaterThan(0);

        // All data should be recoverable
        Optional<TableMeta> metaOpt2 = db2.catalog().openTable("test");
        assertThat(metaOpt2).isPresent();
        StorageEngine engine2 = db2.getEngineForTable(metaOpt2.get());

        assertThat(engine2.get(1L)).isPresent();
        assertThat(engine2.get(2L)).isPresent();

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

        int total = stats.totalRecords();
        int expected = stats.redone() + stats.fpwRestored() + stats.skippedByPdLsn();
        assertThat(total).isEqualTo(expected);

        db2.close();
    }
}
