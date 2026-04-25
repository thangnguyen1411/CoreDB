package com.coredb.catalog;

import com.coredb.api.CoreDBConfig;
import com.coredb.api.Row;
import com.coredb.config.EngineType;
import com.coredb.heap.HeapFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for the bootstrap procedure.
 *
 * <p>Bootstrap creates the system catalogs (core_class, core_attribute) without
 * using the Catalog API — solving the chicken-and-egg problem.
 */
class BootstrapCatalogTest {

    @TempDir
    Path tempDir;

    @Test
    void initializeCreatesDirectoriesAndControlFile() throws Exception {
        CoreDBConfig config = CoreDBConfig.builder()
            .engineType(EngineType.BTREE)
            .build();

        BootstrapCatalog.initialize(tempDir, config);

        // Verify directories exist
        assertThat(tempDir.resolve("global")).exists();
        assertThat(tempDir.resolve("base/1")).exists();

        // Verify pg_control exists
        assertThat(tempDir.resolve("global/pg_control")).exists();

        // Verify catalog files exist
        assertThat(tempDir.resolve("base/1/1000")).exists();
        assertThat(tempDir.resolve("base/1/1001")).exists();
    }

    @Test
    void initializeCreatesControlFileWithCorrectValues() throws Exception {
        CoreDBConfig config = CoreDBConfig.builder()
            .engineType(EngineType.BTREE)
            .build();

        BootstrapCatalog.initialize(tempDir, config);

        ControlFile cf = ControlFile.load(tempDir);
        assertThat(cf.nextOid()).isEqualTo(1002);
        assertThat(cf.nextXid()).isEqualTo(3);
        assertThat(cf.engineType()).isEqualTo(EngineType.BTREE);
        cf.close();
    }

    @Test
    void coreClassContainsTwoRows() throws Exception {
        CoreDBConfig config = CoreDBConfig.builder()
            .engineType(EngineType.BTREE)
            .build();

        BootstrapCatalog.initialize(tempDir, config);

        Path coreClassPath = tempDir.resolve("base/1/1000");
        try (HeapFile hf = HeapFile.open(coreClassPath, 1000, BootstrapCatalog.CORE_CLASS_SCHEMA)) {
            List<Row> rows = toList(hf.scan());
            assertThat(rows).hasSize(2);

            // First row describes core_class itself
            Row coreClassRow = rows.get(0);
            assertThat(coreClassRow.getLong(0)).isEqualTo(1000L); // tableId
            assertThat(coreClassRow.getString(1)).isEqualTo("core_class"); // tableName
            assertThat(coreClassRow.getString(2)).isEqualTo("tableId"); // pkColumn
            assertThat(coreClassRow.getInt(3)).isEqualTo(0); // engineType (BTREE)

            // Second row describes core_attribute
            Row coreAttributeRow = rows.get(1);
            assertThat(coreAttributeRow.getLong(0)).isEqualTo(1001L); // tableId
            assertThat(coreAttributeRow.getString(1)).isEqualTo("core_attribute"); // tableName
            assertThat(coreAttributeRow.getString(2)).isEqualTo("tableId"); // pkColumn
            assertThat(coreAttributeRow.getInt(3)).isEqualTo(0); // engineType (BTREE)
        }
    }

    @Test
    void coreAttributeContainsTenRows() throws Exception {
        CoreDBConfig config = CoreDBConfig.builder()
            .engineType(EngineType.BTREE)
            .build();

        BootstrapCatalog.initialize(tempDir, config);

        Path coreAttributePath = tempDir.resolve("base/1/1001");
        try (HeapFile hf = HeapFile.open(coreAttributePath, 1001, BootstrapCatalog.CORE_ATTRIBUTE_SCHEMA)) {
            List<Row> rows = toList(hf.scan());
            assertThat(rows).hasSize(10);

            // Verify 5 columns for core_class (OID 1000)
            List<Row> coreClassColumns = rows.stream()
                .filter(r -> r.getLong(0) == 1000L)
                .toList();
            assertThat(coreClassColumns).hasSize(5);

            // Verify column names for core_class
            assertThat(coreClassColumns.stream().map(r -> r.getString(2))).containsExactlyInAnyOrder(
                "tableId", "tableName", "pkColumn", "engineType", "rootPageId"
            );

            // Verify 5 columns for core_attribute (OID 1001)
            List<Row> coreAttributeColumns = rows.stream()
                .filter(r -> r.getLong(0) == 1001L)
                .toList();
            assertThat(coreAttributeColumns).hasSize(5);

            // Verify column names for core_attribute
            assertThat(coreAttributeColumns.stream().map(r -> r.getString(2))).containsExactlyInAnyOrder(
                "tableId", "attnum", "attname", "atttype", "attnull"
            );
        }
    }

    @Test
    void initializeThrowsIfAlreadyInitialized() throws Exception {
        CoreDBConfig config = CoreDBConfig.builder()
            .engineType(EngineType.BTREE)
            .build();

        BootstrapCatalog.initialize(tempDir, config);

        assertThatThrownBy(() -> BootstrapCatalog.initialize(tempDir, config))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("already initialized");
    }

    @Test
    void filesAreSynchedToDisk() throws Exception {
        CoreDBConfig config = CoreDBConfig.builder()
            .engineType(EngineType.BTREE)
            .build();

        BootstrapCatalog.initialize(tempDir, config);

        // All files should exist and have non-zero size
        assertThat(tempDir.resolve("global/pg_control").toFile().length()).isGreaterThan(0);
        assertThat(tempDir.resolve("base/1/1000").toFile().length()).isGreaterThan(0);
        assertThat(tempDir.resolve("base/1/1001").toFile().length()).isGreaterThan(0);
    }

    private static List<Row> toList(Iterator<Row> iterator) {
        List<Row> list = new ArrayList<>();
        iterator.forEachRemaining(list::add);
        return list;
    }
}
