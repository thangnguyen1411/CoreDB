package com.coredb.catalog;

import com.coredb.api.CoreDBConfig;
import com.coredb.config.EngineType;
import com.coredb.util.CorruptionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.assertj.core.api.Assertions.*;

class ControlFileTest {

    @TempDir
    Path tempDir;

    @Test
    void createAndLoadRoundTrip() throws Exception {
        CoreDBConfig config = CoreDBConfig.builder()
            .engineType(EngineType.BTREE)
            .build();

        ControlFile created = ControlFile.create(tempDir, config);
        assertThat(created.nextOid()).isEqualTo(1002);
        assertThat(created.nextXid()).isEqualTo(3);
        assertThat(created.engineType()).isEqualTo(EngineType.BTREE);
        assertThat(created.checkpointLsn()).isEqualTo(0);

        created.close();

        ControlFile loaded = ControlFile.load(tempDir);
        assertThat(loaded.nextOid()).isEqualTo(1002);
        assertThat(loaded.nextXid()).isEqualTo(3);
        assertThat(loaded.engineType()).isEqualTo(EngineType.BTREE);

        loaded.close();
    }

    @Test
    void allocateOidIncrementsAndPersists() throws Exception {
        CoreDBConfig config = CoreDBConfig.defaults();
        ControlFile cf = ControlFile.create(tempDir, config);

        int oid1 = cf.allocateOid();
        int oid2 = cf.allocateOid();
        int oid3 = cf.allocateOid();

        assertThat(oid1).isEqualTo(1002);
        assertThat(oid2).isEqualTo(1003);
        assertThat(oid3).isEqualTo(1004);
        assertThat(cf.nextOid()).isEqualTo(1005);

        cf.close();

        ControlFile reloaded = ControlFile.load(tempDir);
        assertThat(reloaded.nextOid()).isEqualTo(1005);
        reloaded.close();
    }

    @Test
    void allocateXidIncrementsAndPersists() throws Exception {
        CoreDBConfig config = CoreDBConfig.defaults();
        ControlFile cf = ControlFile.create(tempDir, config);

        int xid1 = cf.allocateXid();
        int xid2 = cf.allocateXid();

        assertThat(xid1).isEqualTo(3);
        assertThat(xid2).isEqualTo(4);
        assertThat(cf.nextXid()).isEqualTo(5);

        cf.close();

        ControlFile reloaded = ControlFile.load(tempDir);
        assertThat(reloaded.nextXid()).isEqualTo(5);
        reloaded.close();
    }

    @Test
    void detectsMissingControlFile() {
        assertThatThrownBy(() -> ControlFile.load(tempDir))
            .isInstanceOf(java.io.IOException.class)
            .hasMessageContaining("Control file not found");
    }

    @Test
    void detectsCorruptMagic() throws Exception {
        CoreDBConfig config = CoreDBConfig.defaults();
        ControlFile cf = ControlFile.create(tempDir, config);
        cf.close();

        // Corrupt the magic byte
        Path controlPath = tempDir.resolve("global/pg_control");
        byte[] data = Files.readAllBytes(controlPath);
        data[0] = (byte) 0xDE;
        data[1] = (byte) 0xAD;
        data[2] = (byte) 0xBE;
        data[3] = (byte) 0xEF;
        Files.write(controlPath, data, StandardOpenOption.TRUNCATE_EXISTING);

        assertThatThrownBy(() -> ControlFile.load(tempDir))
            .isInstanceOf(CorruptionException.class)
            .hasMessageContaining("magic mismatch");
    }

    @Test
    void detectsCorruptCrc() throws Exception {
        CoreDBConfig config = CoreDBConfig.defaults();
        ControlFile cf = ControlFile.create(tempDir, config);
        cf.close();

        // Corrupt the CRC field itself (byte 28) per phase-3.md spec
        Path controlPath = tempDir.resolve("global/pg_control");
        byte[] data = Files.readAllBytes(controlPath);
        data[28] = (byte) 0xFF; // CRC field starts at byte 28
        Files.write(controlPath, data, StandardOpenOption.TRUNCATE_EXISTING);

        assertThatThrownBy(() -> ControlFile.load(tempDir))
            .isInstanceOf(CorruptionException.class)
            .hasMessageContaining("CRC mismatch");
    }

    @Test
    void formatInfoContainsExpectedFields() throws Exception {
        CoreDBConfig config = CoreDBConfig.defaults();
        ControlFile cf = ControlFile.create(tempDir, config);

        String info = cf.formatInfo();
        assertThat(info).contains("magic=0x434F5245");
        assertThat(info).contains("formatVersion=1");
        assertThat(info).contains("engineType=BTREE");
        assertThat(info).contains("nextOid=1002");
        assertThat(info).contains("nextXid=3");
        assertThat(info).contains("checkpointLsn=0");

        cf.close();
    }

    @Test
    void updateCheckpointLsnPersists() throws Exception {
        CoreDBConfig config = CoreDBConfig.defaults();
        ControlFile cf = ControlFile.create(tempDir, config);

        cf.updateCheckpointLsn(12345L);
        assertThat(cf.checkpointLsn()).isEqualTo(12345L);

        cf.close();

        ControlFile reloaded = ControlFile.load(tempDir);
        assertThat(reloaded.checkpointLsn()).isEqualTo(12345L);
        reloaded.close();
    }

    @Test
    void allocateOneHundredOids() throws Exception {
        // Per phase-3.md spec: "100 allocateOid() calls → OIDs 1002, 1003, ..., 1101"
        CoreDBConfig config = CoreDBConfig.defaults();
        ControlFile cf = ControlFile.create(tempDir, config);

        // Allocate 100 OIDs
        for (int i = 0; i < 100; i++) {
            int expectedOid = 1002 + i;
            int actualOid = cf.allocateOid();
            assertThat(actualOid)
                .as("OID allocation %d should be %d", i, expectedOid)
                .isEqualTo(expectedOid);
        }

        assertThat(cf.nextOid()).isEqualTo(1102);
        cf.close();

        // Verify persistence after reopen
        ControlFile reloaded = ControlFile.load(tempDir);
        assertThat(reloaded.nextOid()).isEqualTo(1102);
        reloaded.close();
    }

    @Test
    void atomicWritePreservesOldFileOnCrash() throws Exception {
        // Simulates crash mid-write: temp file exists but rename didn't complete.
        // Old pg_control must still be readable.
        CoreDBConfig config = CoreDBConfig.defaults();
        ControlFile cf = ControlFile.create(tempDir, config);

        // Allocate one OID to change state from defaults
        int firstOid = cf.allocateOid();
        assertThat(firstOid).isEqualTo(1002);
        assertThat(cf.nextOid()).isEqualTo(1003);
        cf.close();

        // Read the original file content
        Path controlPath = tempDir.resolve("global/pg_control");
        byte[] originalContent = Files.readAllBytes(controlPath);
        assertThat(originalContent).hasSize(ControlFile.FILE_SIZE);

        // Simulate crash: write temp file but don't complete rename
        Path tempPath = tempDir.resolve("global/pg_control.tmp");
        byte[] corruptedData = new byte[ControlFile.FILE_SIZE];
        System.arraycopy(originalContent, 0, corruptedData, 0, originalContent.length);
        // Corrupt the data in the temp file
        corruptedData[20] = (byte) 0xFF;
        Files.write(tempPath, corruptedData, StandardOpenOption.CREATE);

        // Verify original file is still readable (atomic write preserved it)
        ControlFile loaded = ControlFile.load(tempDir);
        assertThat(loaded.nextOid()).isEqualTo(1003); // Unchanged from before "crash"
        loaded.close();

        // Cleanup temp file
        Files.deleteIfExists(tempPath);
    }
}
