package com.coredb.catalog;

import com.coredb.api.Column;
import com.coredb.api.CoreDBConfig;
import com.coredb.api.Row;
import com.coredb.api.Schema;
import com.coredb.config.EngineType;
import com.coredb.heap.HeapFile;
import com.coredb.heap.RecordId;
import com.coredb.txn.ClogManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Bootstrap procedure for creating the system catalogs.
 *
 * <p>Solves the chicken-and-egg problem: PostgreSQL stores catalog metadata
 * in heap files (pg_class, pg_attribute), but to insert into those heap files
 * we need to know about them. Bootstrap mode bypasses the Catalog API and
 * writes directly to the heap files.
 *
 * <p>Bootstrap creates:
 * <ul>
 *   <li>$DATA_DIR/global/pg_control — cluster-wide state</li>
 *   <li>$DATA_DIR/base/1/1000 — core_class heap file (describes tables)</li>
 *   <li>$DATA_DIR/base/1/1001 — core_attribute heap file (describes columns)</li>
 * </ul>
 *
 * <p>All bootstrap rows use BOOTSTRAP_XID (1) as xmin, making them visible
 * to all snapshots forever. This matches PostgreSQL's initdb-time rows.
 *
 * @see <a href="https://github.com/postgres/postgres/blob/master/src/backend/catalog/bootstrap.c">PostgreSQL bootstrap.c</a>
 */
public final class BootstrapCatalog {

    private static final Logger log = LoggerFactory.getLogger(BootstrapCatalog.class);

    // System catalog OIDs (hardcoded, reserved range 1000-1001)
    public static final int CORE_CLASS_OID = 1000;
    public static final int CORE_ATTRIBUTE_OID = 1001;

    // Schema for core_class: (tableId LONG, tableName STRING, pkColumn STRING?, engineType INT, rootPageId LONG)
    public static final Schema CORE_CLASS_SCHEMA = Schema.of(
        Column.longCol("tableId").withNullable(false),
        Column.stringCol("tableName").withNullable(false),
        Column.stringCol("pkColumn").withNullable(true),  // nullable = no PK
        Column.intCol("engineType").withNullable(false),
        Column.longCol("rootPageId").withNullable(false)
    );

    // Schema for core_attribute: (tableId LONG, attnum INT, attname STRING, atttype INT, attnull BOOL)
    public static final Schema CORE_ATTRIBUTE_SCHEMA = Schema.of(
        Column.longCol("tableId").withNullable(false),
        Column.intCol("attnum").withNullable(false),
        Column.stringCol("attname").withNullable(false),
        Column.intCol("atttype").withNullable(false),
        Column.boolCol("attnull").withNullable(false)
    );

    private BootstrapCatalog() {
        // utility class
    }

    /**
     * Initializes a fresh data directory with system catalogs.
     *
     * <p>This method performs the bootstrap procedure:
     * <ol>
     *   <li>Create directories: global/, base/1/</li>
     *   <li>Create pg_control with nextOid=1002, nextXid=3</li>
     *   <li>Create empty heap files for core_class (1000) and core_attribute (1001)</li>
     *   <li>Insert self-describing rows using raw HeapFile.insert()</li>
     *   <li>Sync both catalog files to disk</li>
     * </ol>
     *
     * <p>All rows are inserted with t_xmin = BOOTSTRAP_XID (1) and t_xmax = 0,
     * making them permanently visible.
     *
     * @param dataDir the data directory path
     * @param config  the database configuration
     * @throws IOException              if file operations fail
     * @throws IllegalStateException    if the directory is already initialized
     */
    public static void initialize(Path dataDir, CoreDBConfig config) throws IOException {
        // Check if already initialized
        Path controlPath = dataDir.resolve("global/pg_control");
        if (Files.exists(controlPath)) {
            throw new IllegalStateException("Data directory already initialized: " + controlPath);
        }

        log.info("Bootstrapping CoreDB in {}", dataDir);

        // Step 1: Create directories
        Path globalDir = dataDir.resolve("global");
        Path baseDir = dataDir.resolve("base/1");
        Files.createDirectories(globalDir);
        Files.createDirectories(baseDir);
        log.debug("Created directories: {}/, {}/", globalDir, baseDir);

        // Step 2: Create pg_xact commit log (needed before any heap operations with visibility)
        ClogManager clog = ClogManager.create(dataDir);
        clog.close();
        log.debug("Created pg_xact commit log");

        // Step 3: Create control file (will be closed after creating heap files)
        try (ControlFile controlFile = ControlFile.create(dataDir, config)) {
            log.debug("Created pg_control: nextOid={}, nextXid={}",
                controlFile.nextOid(), controlFile.nextXid());

            // Step 3 & 4: Create heap files and insert bootstrap rows
            Path coreClassPath = baseDir.resolve(String.valueOf(CORE_CLASS_OID));
            Path coreAttributePath = baseDir.resolve(String.valueOf(CORE_ATTRIBUTE_OID));

            try (HeapFile coreClassFile = HeapFile.create(coreClassPath, CORE_CLASS_OID, CORE_CLASS_SCHEMA);
                 HeapFile coreAttributeFile = HeapFile.create(coreAttributePath, CORE_ATTRIBUTE_OID, CORE_ATTRIBUTE_SCHEMA)) {

                log.debug("Created heap files: {} (oid={}), {} (oid={})",
                    coreClassPath, CORE_CLASS_OID, coreAttributePath, CORE_ATTRIBUTE_OID);

                // Insert into core_class: rows describing core_class and core_attribute themselves
                RecordId rid1 = coreClassFile.insert(Row.of(
                    (long) CORE_CLASS_OID, "core_class", "tableId", EngineType.BTREE.ordinal(), 0L));
                log.info("Inserted core_class row: {}", rid1);

                RecordId rid2 = coreClassFile.insert(Row.of(
                    (long) CORE_ATTRIBUTE_OID, "core_attribute", "tableId", EngineType.BTREE.ordinal(), 0L));
                log.info("Inserted core_attribute row: {}", rid2);

                // Insert into core_attribute: 5 columns for core_class + 5 for core_attribute = 10 rows
                final int TYPE_INT = 1, TYPE_LONG = 2, TYPE_STRING = 3, TYPE_BOOL = 4;

                // Columns for core_class (OID 1000)
                coreAttributeFile.insert(Row.of((long) CORE_CLASS_OID, 1, "tableId", TYPE_LONG, false));
                coreAttributeFile.insert(Row.of((long) CORE_CLASS_OID, 2, "tableName", TYPE_STRING, false));
                coreAttributeFile.insert(Row.of((long) CORE_CLASS_OID, 3, "pkColumn", TYPE_STRING, true));
                coreAttributeFile.insert(Row.of((long) CORE_CLASS_OID, 4, "engineType", TYPE_INT, false));
                coreAttributeFile.insert(Row.of((long) CORE_CLASS_OID, 5, "rootPageId", TYPE_LONG, false));

                // Columns for core_attribute (OID 1001)
                coreAttributeFile.insert(Row.of((long) CORE_ATTRIBUTE_OID, 1, "tableId", TYPE_LONG, false));
                coreAttributeFile.insert(Row.of((long) CORE_ATTRIBUTE_OID, 2, "attnum", TYPE_INT, false));
                coreAttributeFile.insert(Row.of((long) CORE_ATTRIBUTE_OID, 3, "attname", TYPE_STRING, false));
                coreAttributeFile.insert(Row.of((long) CORE_ATTRIBUTE_OID, 4, "atttype", TYPE_INT, false));
                coreAttributeFile.insert(Row.of((long) CORE_ATTRIBUTE_OID, 5, "attnull", TYPE_BOOL, false));

                log.info("Inserted bootstrap rows: 2 rows into core_class, 10 rows into core_attribute");
            } // HeapFiles auto-close here (fsync on close)

            log.info("Bootstrap complete: 12 rows total");
        } // ControlFile auto-closes here
    }

}
