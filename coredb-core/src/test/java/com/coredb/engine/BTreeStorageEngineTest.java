package com.coredb.engine;

import com.coredb.api.CoreDBConfig;
import com.coredb.catalog.TableMeta;
import com.coredb.config.EngineType;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contract tests for {@link BTreeStorageEngine}.
 *
 * <p>Tests insert/get/delete without UPDATE path. The upsert test
 * is expected to fail with "UPDATE path not yet implemented." exception.</p>
 */
class BTreeStorageEngineTest extends StorageEngineContractTest {

    @Override
    protected StorageEngine createEngine(Path dataDir, TableMeta meta) throws IOException {
        CoreDBConfig config = CoreDBConfig.defaults();
        StorageEngine engine = StorageEngineFactory.create(EngineType.BTREE, config);
        engine.open(dataDir, meta);
        return engine;
    }

    /**
     * PUT of existing key should throw because UPDATE path is not yet implemented.
     * This test verifies the expected behavior.
     */
    @Test
    void putOfExistingKey_throwsUpdatePathNotImplemented() throws IOException {
        TableMeta meta = createTestTableMeta();
        Path dataDir = tempDir.resolve("data");
        java.nio.file.Files.createDirectories(dataDir);

        // First put
        try (StorageEngine engine = createEngine(dataDir, meta)) {
            engine.open(dataDir, meta);
            com.coredb.api.Row original = com.coredb.api.Row.of(1L, "Alice", 30);
            engine.put(1L, original);
        }

        // Second put should throw
        try (StorageEngine engine = createEngine(dataDir, meta)) {
            engine.open(dataDir, meta);
            com.coredb.api.Row updated = com.coredb.api.Row.of(1L, "Bob", 31);

            assertThatThrownBy(() -> engine.put(1L, updated))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("UPDATE path not yet implemented.");
        }
    }

    private TableMeta createTestTableMeta() {
        com.coredb.api.Schema schema = com.coredb.api.Schema.of(
            com.coredb.api.Column.longCol("id").withNullable(false),
            com.coredb.api.Column.stringCol("name"),
            com.coredb.api.Column.intCol("age")
        );
        return new TableMeta(1002, "test_table", schema, "id", EngineType.BTREE);
    }
}
