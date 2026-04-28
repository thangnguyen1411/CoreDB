package com.coredb.catalog;

import com.coredb.api.CoreDBConfig;
import com.coredb.buffer.BufferPool;
import com.coredb.txn.ClogManager;
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

        try (BufferPool pool = new BufferPool();
             ClogManager clog = ClogManager.open(tempDir);
             Catalog catalog = new Catalog(tempDir, cf, pool, clog)) {
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

        try (BufferPool pool = new BufferPool();
             ClogManager clog = ClogManager.open(tempDir);
             Catalog catalog = new Catalog(tempDir, cf, pool, clog)) {
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

        try (BufferPool pool = new BufferPool();
             ClogManager clog = ClogManager.open(tempDir);
             Catalog catalog = new Catalog(tempDir, cf, pool, clog)) {
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
        try (BufferPool pool = new BufferPool();
             ClogManager clog = ClogManager.open(tempDir);
             Catalog catalog = new Catalog(tempDir, cf, pool, clog)) {
            ColumnDefParser.ParsedSchema parsed = parse("id:long name:string pk:id");
            catalog.createTable("customers", parsed.schema(), parsed.pkColumn());
        }

        // Reopen and verify
        ControlFile cf2 = ControlFile.load(tempDir);
        try (BufferPool pool2 = new BufferPool();
             ClogManager clog2 = ClogManager.open(tempDir);
             Catalog catalog2 = new Catalog(tempDir, cf2, pool2, clog2)) {
            Optional<TableMeta> metaOpt = catalog2.openTable("customers");
            assertThat(metaOpt).isPresent();
            assertThat(metaOpt.get().name()).isEqualTo("customers");
            assertThat(metaOpt.get().schema().columnCount()).isEqualTo(2);
        }
    }

    @Test
    void dropTableHidesFromList() throws IOException {
        BootstrapCatalog.initialize(tempDir, CoreDBConfig.defaults());
        ControlFile cf = ControlFile.load(tempDir);

        try (BufferPool pool = new BufferPool();
             ClogManager clog = ClogManager.open(tempDir);
             Catalog catalog = new Catalog(tempDir, cf, pool, clog)) {
            // Create and drop a table
            ColumnDefParser.ParsedSchema parsed = parse("id:long name:string pk:id");
            catalog.createTable("temp", parsed.schema(), parsed.pkColumn());

            List<TableMeta> tables = catalog.listTables();
            assertThat(tables).hasSize(1);

            catalog.dropTable("temp");

            // Should be hidden from listTables
            tables = catalog.listTables();
            assertThat(tables).isEmpty();

            // openTable should also not find it
            Optional<TableMeta> metaOpt = catalog.openTable("temp");
            assertThat(metaOpt).isEmpty();
        }
    }

    @Test
    void recreateAfterDropGetsNewOid() throws IOException {
        BootstrapCatalog.initialize(tempDir, CoreDBConfig.defaults());
        ControlFile cf = ControlFile.load(tempDir);

        try (BufferPool pool = new BufferPool();
             ClogManager clog = ClogManager.open(tempDir);
             Catalog catalog = new Catalog(tempDir, cf, pool, clog)) {
            // Create a table
            ColumnDefParser.ParsedSchema parsed = parse("id:long name:string pk:id");
            catalog.createTable("reusable", parsed.schema(), parsed.pkColumn());

            int firstOid = catalog.openTable("reusable").get().oid();

            // Drop it
            catalog.dropTable("reusable");

            // Create a new table with the same name
            catalog.createTable("reusable", parsed.schema(), parsed.pkColumn());

            int secondOid = catalog.openTable("reusable").get().oid();

            // Should get a new OID, not reuse the old one
            assertThat(secondOid).isGreaterThan(firstOid);
        }
    }

    @Test
    void createTableWithoutPk() throws IOException {
        BootstrapCatalog.initialize(tempDir, CoreDBConfig.defaults());
        ControlFile cf = ControlFile.load(tempDir);

        try (BufferPool pool = new BufferPool();
             ClogManager clog = ClogManager.open(tempDir);
             Catalog catalog = new Catalog(tempDir, cf, pool, clog)) {
            // Create a table without PK
            ColumnDefParser.ParsedSchema parsed = parse("level:string message:string");
            catalog.createTable("logs", parsed.schema(), parsed.pkColumn());

            // Verify table exists with null PK
            var metaOpt = catalog.openTable("logs");
            assertThat(metaOpt).isPresent();
            assertThat(metaOpt.get().pkColumn()).isNull();
            assertThat(metaOpt.get().schema().columnCount()).isEqualTo(2);

            // Verify it appears in list
            var tables = catalog.listTables();
            assertThat(tables).hasSize(1);
            assertThat(tables.get(0).pkColumn()).isNull();
        }
    }

    @Test
    void dropNonExistentTableThrows() throws IOException {
        BootstrapCatalog.initialize(tempDir, CoreDBConfig.defaults());
        ControlFile cf = ControlFile.load(tempDir);

        try (BufferPool pool = new BufferPool();
             ClogManager clog = ClogManager.open(tempDir);
             Catalog catalog = new Catalog(tempDir, cf, pool, clog)) {
            assertThatThrownBy(() -> catalog.dropTable("nonexistent"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Table not found");
        }
    }
}
