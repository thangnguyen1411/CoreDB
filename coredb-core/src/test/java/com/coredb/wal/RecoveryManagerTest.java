package com.coredb.wal;

import com.coredb.api.CoreDB;
import com.coredb.api.CoreDBConfig;
import com.coredb.recovery.RecoveryManager;
import com.coredb.recovery.RecoveryStats;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for RecoveryManager redo-only recovery.
 */
class RecoveryManagerTest {

    @Test
    void recover_freshDatabase_returnsNoRecoveryNeeded(@TempDir Path tempDir) throws IOException {
        // Create a fresh database
        CoreDB db = CoreDB.open(tempDir, CoreDBConfig.defaults());

        // Fresh database should have no recovery needed
        RecoveryStats stats = db.lastRecoveryStats();
        assertThat(stats).isNotNull();
        assertThat(stats.isNoRecovery()).isTrue();
        assertThat(stats.noRecoveryReason()).contains("fresh database");

        db.close();
    }

    @Test
    void recover_existingDatabaseWithoutWal_returnsNoRecoveryNeeded(@TempDir Path tempDir) throws IOException {
        // First, create a database
        CoreDB db = CoreDB.open(tempDir, CoreDBConfig.defaults());
        db.close();

        // Re-open the existing database
        CoreDB db2 = CoreDB.open(tempDir, CoreDBConfig.defaults());

        // Should run recovery but find nothing to replay
        RecoveryStats stats = db2.lastRecoveryStats();
        assertThat(stats).isNotNull();
        // Recovery ran but there's no WAL data to replay
        assertThat(stats.totalRecords()).isEqualTo(0);

        db2.close();
    }

    @Test
    void recover_statsAreRecorded(@TempDir Path tempDir) throws IOException {
        // Create and close a database
        CoreDB db = CoreDB.open(tempDir, CoreDBConfig.defaults());
        db.close();

        // Re-open - should record recovery stats
        CoreDB db2 = CoreDB.open(tempDir, CoreDBConfig.defaults());
        RecoveryStats stats = db2.lastRecoveryStats();

        // Verify stats structure
        assertThat(stats.startLsn()).isGreaterThanOrEqualTo(0);
        assertThat(stats.endLsn()).isGreaterThanOrEqualTo(stats.startLsn());
        assertThat(stats.ranAt()).isNotNull();
        assertThat(stats.elapsedMillis()).isGreaterThanOrEqualTo(0);

        db2.close();
    }

    @Test
    void recoveryStats_noRecoveryNeeded_format(@TempDir Path tempDir) throws IOException {
        CoreDB db = CoreDB.open(tempDir, CoreDBConfig.defaults());
        RecoveryStats stats = db.lastRecoveryStats();

        // Format should indicate no recovery
        String formatted = stats.format();
        assertThat(formatted).contains("no recovery");
        assertThat(formatted).contains("fresh database");

        db.close();
    }

    @Test
    void recoveryStats_withRecovery_format(@TempDir Path tempDir) throws IOException {
        // Create and close
        CoreDB db = CoreDB.open(tempDir, CoreDBConfig.defaults());
        db.close();

        // Re-open to get recovery stats
        CoreDB db2 = CoreDB.open(tempDir, CoreDBConfig.defaults());
        RecoveryStats stats = db2.lastRecoveryStats();

        // Format should contain recovery details
        String formatted = stats.format();
        assertThat(formatted).contains("last-recovery");
        assertThat(formatted).contains("redone");
        assertThat(formatted).contains("fpw-restored");
        assertThat(formatted).contains("skipped-by-pd_lsn");

        db2.close();
    }

    @Test
    void recover_directCallWithFreshDb(@TempDir Path tempDir) throws IOException {
        // Call RecoveryManager directly before any database exists
        RecoveryStats stats = RecoveryManager.recover(tempDir);

        // Should indicate no recovery needed (fresh database)
        assertThat(stats.isNoRecovery()).isTrue();
        assertThat(stats.noRecoveryReason()).contains("fresh database");
    }

    @Test
    void recover_directCallWithExistingDb(@TempDir Path tempDir) throws IOException {
        // First create a database
        CoreDB db = CoreDB.open(tempDir, CoreDBConfig.defaults());
        db.close();

        // Call RecoveryManager directly
        RecoveryStats stats = RecoveryManager.recover(tempDir);

        // Should run and complete
        assertThat(stats).isNotNull();
        assertThat(stats.elapsedMillis()).isGreaterThanOrEqualTo(0);
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
