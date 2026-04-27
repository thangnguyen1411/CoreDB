package com.coredb.txn;

import com.coredb.util.Constants;
import com.coredb.util.CorruptionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.assertj.core.api.Assertions.*;

class ClogManagerTest {

    @TempDir
    Path tempDir;

    @Test
    void setCommittedThenGetStatusReturnsCommitted() throws Exception {
        ClogManager clog = ClogManager.create(tempDir);

        clog.setCommitted(3);
        assertThat(clog.getStatus(3)).isEqualTo(ClogManager.Status.COMMITTED);

        clog.close();
    }

    @Test
    void setAbortedThenGetStatusReturnsAborted() throws Exception {
        ClogManager clog = ClogManager.create(tempDir);

        clog.setAborted(4);
        assertThat(clog.getStatus(4)).isEqualTo(ClogManager.Status.ABORTED);

        clog.close();
    }

    @Test
    void unsetXidReturnsInProgress() throws Exception {
        ClogManager clog = ClogManager.create(tempDir);

        // XID 100 was never touched
        assertThat(clog.getStatus(100)).isEqualTo(ClogManager.Status.IN_PROGRESS);

        clog.close();
    }

    @Test
    void closeAndReopenPreservesStatuses() throws Exception {
        ClogManager clog = ClogManager.create(tempDir);

        clog.setCommitted(3);
        clog.setAborted(4);
        clog.setCommitted(5);
        clog.close();

        ClogManager reloaded = ClogManager.open(tempDir);
        assertThat(reloaded.getStatus(3)).isEqualTo(ClogManager.Status.COMMITTED);
        assertThat(reloaded.getStatus(4)).isEqualTo(ClogManager.Status.ABORTED);
        assertThat(reloaded.getStatus(5)).isEqualTo(ClogManager.Status.COMMITTED);
        assertThat(reloaded.getStatus(6)).isEqualTo(ClogManager.Status.IN_PROGRESS);

        reloaded.close();
    }

    @Test
    void bootstrapXidAlwaysCommitted() throws Exception {
        ClogManager clog = ClogManager.create(tempDir);

        // BOOTSTRAP_XID (1) is always committed
        assertThat(clog.getStatus(Constants.BOOTSTRAP_XID)).isEqualTo(ClogManager.Status.COMMITTED);

        clog.close();
    }

    @Test
    void invalidXidThrowsOnQuery() throws Exception {
        ClogManager clog = ClogManager.create(tempDir);

        assertThatThrownBy(() -> clog.getStatus(Constants.INVALID_XID))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("INVALID_XID");

        clog.close();
    }

    @Test
    void setStatusForInvalidXidThrows() throws Exception {
        ClogManager clog = ClogManager.create(tempDir);

        assertThatThrownBy(() -> clog.setCommitted(Constants.INVALID_XID))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("INVALID_XID");

        assertThatThrownBy(() -> clog.setCommitted(Constants.BOOTSTRAP_XID))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("BOOTSTRAP_XID");

        clog.close();
    }

    @Test
    void detectsMissingFile() {
        assertThatThrownBy(() -> ClogManager.open(tempDir))
            .isInstanceOf(java.io.IOException.class)
            .hasMessageContaining("pg_xact file not found");
    }

    @Test
    void detectsCorruptMagic() throws Exception {
        ClogManager clog = ClogManager.create(tempDir);
        clog.close();

        // Corrupt the magic bytes
        Path pgXactPath = tempDir.resolve("global/pg_xact");
        byte[] data = Files.readAllBytes(pgXactPath);
        data[0] = (byte) 0xDE;
        data[1] = (byte) 0xAD;
        data[2] = (byte) 0xBE;
        data[3] = (byte) 0xEF;
        Files.write(pgXactPath, data, StandardOpenOption.TRUNCATE_EXISTING);

        assertThatThrownBy(() -> ClogManager.open(tempDir))
            .isInstanceOf(CorruptionException.class)
            .hasMessageContaining("magic mismatch");
    }

    @Test
    void detectsCorruptVersion() throws Exception {
        ClogManager clog = ClogManager.create(tempDir);
        clog.close();

        // Corrupt the version bytes (offset 4-7)
        Path pgXactPath = tempDir.resolve("global/pg_xact");
        byte[] data = Files.readAllBytes(pgXactPath);
        data[4] = (byte) 0xFF;
        data[5] = (byte) 0xFF;
        data[6] = (byte) 0xFF;
        data[7] = (byte) 0xFF;
        Files.write(pgXactPath, data, StandardOpenOption.TRUNCATE_EXISTING);

        assertThatThrownBy(() -> ClogManager.open(tempDir))
            .isInstanceOf(CorruptionException.class)
            .hasMessageContaining("version mismatch");
    }

    @Test
    void statsReflectsCorrectCounts() throws Exception {
        ClogManager clog = ClogManager.create(tempDir);

        // Set some statuses
        clog.setCommitted(3);   // byte 0
        clog.setAborted(4);      // byte 1
        clog.setCommitted(5);    // byte 1
        clog.setAborted(6);     // byte 1
        clog.setCommitted(10);   // byte 2

        ClogManager.Stats stats = clog.getStats();

        // 11 entries tracked (0-10 inclusive)
        assertThat(stats.entries()).isGreaterThanOrEqualTo(11);
        assertThat(stats.committed()).isEqualTo(3);  // 3, 5, 10
        assertThat(stats.aborted()).isEqualTo(2);    // 4, 6
        // in-progress includes 0, 1, 2, 7, 8, 9, and anything >= 11

        clog.close();
    }

    @Test
    void bitPackingCorrectlyIsolatesXidsInSameByte() throws Exception {
        ClogManager clog = ClogManager.create(tempDir);

        // XIDs 2,3 are in byte index 0 (2/4=0, 3/4=0)
        // XIDs 4,5 are in byte index 1 (4/4=1, 5/4=1)
        clog.setCommitted(2);
        clog.setAborted(3);
        clog.setCommitted(4);
        clog.setAborted(5);

        // Verify each XID has its correct status
        assertThat(clog.getStatus(2)).isEqualTo(ClogManager.Status.COMMITTED);
        assertThat(clog.getStatus(3)).isEqualTo(ClogManager.Status.ABORTED);
        assertThat(clog.getStatus(4)).isEqualTo(ClogManager.Status.COMMITTED);
        assertThat(clog.getStatus(5)).isEqualTo(ClogManager.Status.ABORTED);

        clog.close();
    }

    @Test
    void statusesPersistAfterFlush() throws Exception {
        ClogManager clog = ClogManager.create(tempDir);

        clog.setCommitted(100);
        clog.setAborted(200);
        clog.flush();

        // Statuses should be visible even before close
        ClogManager reloaded = ClogManager.open(tempDir);
        assertThat(reloaded.getStatus(100)).isEqualTo(ClogManager.Status.COMMITTED);
        assertThat(reloaded.getStatus(200)).isEqualTo(ClogManager.Status.ABORTED);

        clog.close();
        reloaded.close();
    }

    @Test
    void entryCountGrowsWithMaxXid() throws Exception {
        ClogManager clog = ClogManager.create(tempDir);

        // Initially empty or minimal
        int initialCount = clog.entryCount();

        // Set a high XID
        clog.setCommitted(1000);

        // Entry count should now cover at least up to 1000
        assertThat(clog.entryCount()).isGreaterThanOrEqualTo(1001);

        clog.close();
    }
}
