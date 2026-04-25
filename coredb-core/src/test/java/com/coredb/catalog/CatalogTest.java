package com.coredb.catalog;

import com.coredb.api.CoreDBConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static com.coredb.catalog.ColumnDefParser.parse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CatalogTest {

    @TempDir
    Path tempDir;

    @Test
    void createAndListTable() throws IOException {
        // Bootstrap a fresh database
        BootstrapCatalog.initialize(tempDir, CoreDBConfig.defaults());
        ControlFile cf = ControlFile.load(tempDir);

        try (Catalog catalog = new Catalog(tempDir, cf)) {
            // Initially no user tables (only system catalogs which are filtered)
            List<TableMeta> tables = catalog.listTables();
            assertThat(tables).isEmpty();

            // Create a table
            ColumnDefParser.ParsedSchema parsed = parse("id:long name:string age:int pk:id");
            catalog.createTable("users", parsed.schema(), parsed.pkColumn());

            // Should now have one table
            tables = catalog.listTables();
            assertThat(tables).hasSize(1);

            TableMeta meta = tables.get(0);
            assertThat(meta.name()).isEqualTo("users");
            assertThat(meta.oid()).isGreaterThanOrEqualTo(1002); // First user OID
            assertThat(meta.pkColumn()).isEqualTo("id");
            assertThat(meta.schema().columnCount()).isEqualTo(3);
        }
    }

    @Test
    void openTableByName() throws IOException {
        BootstrapCatalog.initialize(tempDir, CoreDBConfig.defaults());
        ControlFile cf = ControlFile.load(tempDir);

        try (Catalog catalog = new Catalog(tempDir, cf)) {
            // Create a table
            ColumnDefParser.ParsedSchema parsed = parse("id:long name:string pk:id");
            catalog.createTable("products", parsed.schema(), parsed.pkColumn());

            // Open by name
            Optional<TableMeta> metaOpt = catalog.openTable("products");
            assertThat(metaOpt).isPresent();

            TableMeta meta = metaOpt.get();
            assertThat(meta.name()).isEqualTo("products");
            assertThat(meta.schema().columnCount()).isEqualTo(2);
            assertThat(meta.schema().column("id").type()).isEqualTo(com.coredb.api.ColumnType.LONG);
            assertThat(meta.schema().column("name").type()).isEqualTo(com.coredb.api.ColumnType.STRING);
        }
    }

    @Test
    void duplicateTableNameThrows() throws IOException {
        BootstrapCatalog.initialize(tempDir, CoreDBConfig.defaults());
        ControlFile cf = ControlFile.load(tempDir);

        try (Catalog catalog = new Catalog(tempDir, cf)) {
            ColumnDefParser.ParsedSchema parsed = parse("id:long pk:id");
            catalog.createTable("items", parsed.schema(), parsed.pkColumn());

            // Creating same name again should throw
            assertThatThrownBy(() -> catalog.createTable("items", parsed.schema(), parsed.pkColumn()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already exists");
        }
    }

    @Test
    void tableSurvivesReopen() throws IOException {
        BootstrapCatalog.initialize(tempDir, CoreDBConfig.defaults());
        ControlFile cf = ControlFile.load(tempDir);

        // Create table in first session
        try (Catalog catalog = new Catalog(tempDir, cf)) {
            ColumnDefParser.ParsedSchema parsed = parse("id:long name:string pk:id");
            catalog.createTable("customers", parsed.schema(), parsed.pkColumn());
        }

        // Reopen and verify
        ControlFile cf2 = ControlFile.load(tempDir);
        try (Catalog catalog2 = new Catalog(tempDir, cf2)) {
            Optional<TableMeta> metaOpt = catalog2.openTable("customers");
            assertThat(metaOpt).isPresent();
            assertThat(metaOpt.get().name()).isEqualTo("customers");
            assertThat(metaOpt.get().schema().columnCount()).isEqualTo(2);
        }
    }
}
