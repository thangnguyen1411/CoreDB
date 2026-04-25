package com.coredb.catalog;

import com.coredb.api.Column;
import com.coredb.api.CoreDBConfig;
import com.coredb.api.Row;
import com.coredb.api.Schema;
import com.coredb.config.EngineType;
import com.coredb.heap.HeapFile;
import com.coredb.heap.RecordId;
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

    // First user table OID (assigned by ControlFile, starts at 1002)
    public static final int FIRST_USER_OID = 1002;

    // Schema for core_class: (tableId LONG, tableName STRING, pkColumn STRING, engineType INT, rootPageId LONG)
    public static final Schema CORE_CLASS_SCHEMA = Schema.of(
        Column.longCol("tableId").withNullable(false),
        Column.stringCol("tableName").withNullable(false),
        Column.stringCol("pkColumn").withNullable(false),
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

        // Step 2: Create control file
        ControlFile controlFile = ControlFile.create(dataDir, config);
        log.debug("Created pg_control: nextOid={}, nextXid={}",
            controlFile.nextOid(), controlFile.nextXid());

        // Step 3: Create empty heap files for system catalogs
        Path coreClassPath = baseDir.resolve(String.valueOf(CORE_CLASS_OID));
        Path coreAttributePath = baseDir.resolve(String.valueOf(CORE_ATTRIBUTE_OID));

        HeapFile coreClassFile = HeapFile.create(coreClassPath, CORE_CLASS_OID, CORE_CLASS_SCHEMA);
        HeapFile coreAttributeFile = HeapFile.create(coreAttributePath, CORE_ATTRIBUTE_OID, CORE_ATTRIBUTE_SCHEMA);

        log.debug("Created heap files: {} (oid={}), {} (oid={})",
            coreClassPath, CORE_CLASS_OID, coreAttributePath, CORE_ATTRIBUTE_OID);

        // Step 4: Insert self-describing bootstrap rows
        // All rows use BOOTSTRAP_XID (1) as xmin, making them permanently visible
        int coreClassRows = insertCoreClassRows(coreClassFile);
        int coreAttributeRows = insertCoreAttributeRows(coreAttributeFile);

        log.info("Inserted bootstrap rows: {} rows into core_class, {} rows into core_attribute",
            coreClassRows, coreAttributeRows);

        // Step 5: Sync to disk
        coreClassFile.close();
        coreAttributeFile.close();

        log.info("Bootstrap complete: {} rows total", coreClassRows + coreAttributeRows);
    }

    /**
     * Inserts rows describing core_class and core_attribute into core_class.
     *
     * <p>Rows inserted:
     * <ul>
     *   <li>(1000, "core_class", "tableId", 0, 0)</li>
     *   <li>(1001, "core_attribute", "tableId", 0, 0)</li>
     * </ul>
     *
     * @return number of rows inserted (2)
     */
    private static int insertCoreClassRows(HeapFile coreClassFile) throws IOException {
        int count = 0;

        // Row for core_class itself
        Row coreClassRow = Row.of(
            (long) CORE_CLASS_OID,      // tableId
            "core_class",                // tableName
            "tableId",                   // pkColumn
            EngineType.BTREE.ordinal(),  // engineType
            0L                           // rootPageId (unused in heap files)
        );
        RecordId rid1 = coreClassFile.insert(coreClassRow);
        log.debug("Inserted core_class row: {} -> {}", rid1, coreClassRow);
        count++;

        // Row for core_attribute
        Row coreAttributeRow = Row.of(
            (long) CORE_ATTRIBUTE_OID,   // tableId
            "core_attribute",            // tableName
            "tableId",                   // pkColumn
            EngineType.BTREE.ordinal(),  // engineType
            0L                           // rootPageId
        );
        RecordId rid2 = coreClassFile.insert(coreAttributeRow);
        log.debug("Inserted core_attribute row: {} -> {}", rid2, coreAttributeRow);
        count++;

        return count;
    }

    /**
     * Inserts rows describing columns of both catalog tables into core_attribute.
     *
     * <p>Rows inserted (5 for core_class + 4 for core_attribute = 9 total):
     * <ul>
     *   <li>core_class columns: tableId, tableName, pkColumn, engineType, rootPageId</li>
     *   <li>core_attribute columns: tableId, attnum, attname, atttype, attnull</li>
     * </ul>
     *
     * <p>Column type encoding:
     * <ul>
     *   <li>INT = 1</li>
     *   <li>LONG = 2</li>
     *   <li>STRING = 3</li>
     *   <li>BOOL = 4</li>
     * </ul>
     *
     * @return number of rows inserted (9)
     */
    private static int insertCoreAttributeRows(HeapFile coreAttributeFile) throws IOException {
        int count = 0;

        // Column type ordinal values (must match ColumnType enum ordinals + 1)
        // ColumnType is: INT, LONG, STRING, BOOL (ordinals 0, 1, 2, 3)
        // We store as 1-based: 1=INT, 2=LONG, 3=STRING, 4=BOOL
        final int TYPE_LONG = 2;
        final int TYPE_STRING = 3;
        final int TYPE_INT = 1;
        final int TYPE_BOOL = 4;

        // Columns for core_class (OID 1000)
        // 1. tableId LONG not null
        coreAttributeFile.insert(Row.of((long) CORE_CLASS_OID, 1, "tableId", TYPE_LONG, false));
        count++;
        // 2. tableName STRING not null
        coreAttributeFile.insert(Row.of((long) CORE_CLASS_OID, 2, "tableName", TYPE_STRING, false));
        count++;
        // 3. pkColumn STRING not null
        coreAttributeFile.insert(Row.of((long) CORE_CLASS_OID, 3, "pkColumn", TYPE_STRING, false));
        count++;
        // 4. engineType INT not null
        coreAttributeFile.insert(Row.of((long) CORE_CLASS_OID, 4, "engineType", TYPE_INT, false));
        count++;
        // 5. rootPageId LONG not null
        coreAttributeFile.insert(Row.of((long) CORE_CLASS_OID, 5, "rootPageId", TYPE_LONG, false));
        count++;

        // Columns for core_attribute (OID 1001)
        // 1. tableId LONG not null
        coreAttributeFile.insert(Row.of((long) CORE_ATTRIBUTE_OID, 1, "tableId", TYPE_LONG, false));
        count++;
        // 2. attnum INT not null
        coreAttributeFile.insert(Row.of((long) CORE_ATTRIBUTE_OID, 2, "attnum", TYPE_INT, false));
        count++;
        // 3. attname STRING not null
        coreAttributeFile.insert(Row.of((long) CORE_ATTRIBUTE_OID, 3, "attname", TYPE_STRING, false));
        count++;
        // 4. atttype INT not null
        coreAttributeFile.insert(Row.of((long) CORE_ATTRIBUTE_OID, 4, "atttype", TYPE_INT, false));
        count++;
        // 5. attnull BOOL not null
        coreAttributeFile.insert(Row.of((long) CORE_ATTRIBUTE_OID, 5, "attnull", TYPE_BOOL, false));
        count++;

        log.debug("Inserted {} column rows into core_attribute", count);
        return count;
    }
}
