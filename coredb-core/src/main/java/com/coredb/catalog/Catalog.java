package com.coredb.catalog;

import com.coredb.api.Column;
import com.coredb.api.ColumnType;
import com.coredb.api.Row;
import com.coredb.api.Schema;
import com.coredb.config.EngineType;
import com.coredb.heap.HeapFile;
import com.coredb.heap.HeapPage;
import com.coredb.heap.RecordId;
import com.coredb.page.Page;
import com.coredb.page.PageType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

/**
 * High-level catalog API for managing tables.
 *
 * <p>The catalog stores metadata in two system heap files:
 * <ul>
 *   <li>core_class (OID 1000): one row per table (tableId, tableName, pkColumn, engineType, rootPageId)</li>
 *   <li>core_attribute (OID 1001): one row per column (tableId, attnum, attname, atttype, attnull)</li>
 * </ul>
 *
 * <p>This class provides operations to create, lookup, list, and drop tables.
 * It is used after BootstrapCatalog.initialize() has created the system catalogs.
 *
 * @see BootstrapCatalog
 */
public final class Catalog implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(Catalog.class);

    // Type codes for core_attribute (must match BootstrapCatalog)
    private static final int TYPE_INT = 1;
    private static final int TYPE_LONG = 2;
    private static final int TYPE_STRING = 3;
    private static final int TYPE_BOOL = 4;

    private final Path dataDir;
    private final ControlFile controlFile;
    private final HeapFile coreClassFile;
    private final HeapFile coreAttributeFile;

    /**
     * Opens the catalog using the system catalog heap files.
     *
     * @param dataDir the data directory path
     * @param controlFile the control file for OID allocation
     * @throws IOException if the catalog files cannot be opened
     */
    public Catalog(Path dataDir, ControlFile controlFile) throws IOException {
        this.dataDir = dataDir;
        this.controlFile = controlFile;

        Path baseDir = dataDir.resolve("base").resolve("1");
        Path coreClassPath = baseDir.resolve(String.valueOf(BootstrapCatalog.CORE_CLASS_OID));
        Path coreAttributePath = baseDir.resolve(String.valueOf(BootstrapCatalog.CORE_ATTRIBUTE_OID));

        this.coreClassFile = HeapFile.open(coreClassPath, BootstrapCatalog.CORE_CLASS_OID, BootstrapCatalog.CORE_CLASS_SCHEMA);
        this.coreAttributeFile = HeapFile.open(coreAttributePath, BootstrapCatalog.CORE_ATTRIBUTE_OID, BootstrapCatalog.CORE_ATTRIBUTE_SCHEMA);

        log.debug("Catalog opened: core_class={}, core_attribute={}", coreClassPath, coreAttributePath);
    }

    /**
     * Creates a new table with the given name, schema, and optional primary key column.
     *
     * <p>Steps:
     * <ol>
     *   <li>Allocate a new OID from the control file</li>
     *   <li>Create the heap file for the table</li>
     *   <li>Insert a row into core_class describing the table</li>
     *   <li>Insert rows into core_attribute describing each column</li>
     * </ol>
     *
     * @param name the table name
     * @param schema the table schema
     * @param pkColumn the name of the primary key column, or null if no PK
     * @throws IllegalArgumentException if a table with this name already exists, or if pkColumn is provided but not found
     * @throws IOException if file operations fail
     */
    public void createTable(String name, Schema schema, String pkColumn) throws IOException {
        // Validate PK column exists (only if provided)
        if (pkColumn != null && schema.column(pkColumn) == null) {
            throw new IllegalArgumentException("PK column '" + pkColumn + "' not found in schema");
        }

        // Check for duplicate name
        if (openTable(name).isPresent()) {
            throw new IllegalArgumentException("Table already exists: " + name);
        }

        // Allocate OID and create heap file
        int oid = controlFile.allocateOid();
        Path tablePath = dataDir.resolve("base").resolve("1").resolve(String.valueOf(oid));

        // Create the heap file (side effect only, close immediately)
        HeapFile.create(tablePath, oid, schema).close();
        log.debug("Created heap file for table {}: oid={}", name, oid);

        // Insert into core_class: (tableId, tableName, pkColumn, engineType, rootPageId)
        Row classRow = Row.of((long) oid, name, pkColumn, EngineType.BTREE.ordinal(), 0L);
        RecordId classRid = coreClassFile.insert(classRow);
        log.debug("Inserted into core_class: {} -> {}", name, classRid);

        // Insert into core_attribute: (tableId, attnum, attname, atttype, attnull)
        for (int i = 0; i < schema.columnCount(); i++) {
            Column col = schema.column(i);
            int typeCode = columnTypeToCode(col.type());
            Row attrRow = Row.of((long) oid, i + 1, col.name(), typeCode, col.nullable());
            coreAttributeFile.insert(attrRow);
        }
        log.debug("Inserted {} columns into core_attribute for table {}", schema.columnCount(), name);

        // Note: Table heap file is created and closed above (fsynced via close).
        // Catalog files (core_class, core_attribute) remain open for the lifetime
        // of Catalog instance and are synced on Catalog.close().
        // TODO: No immediate fsync of catalog files after insert. If process crashes
        // before Catalog.close(), the catalog entries may not be persisted.
    }

    /**
     * Looks up a table by name and returns its metadata.
     *
     * @param name the table name
     * @return Optional containing TableMeta if found and not deleted
     * @throws IOException if scan fails
     */
    public Optional<TableMeta> openTable(String name) throws IOException {
        Iterator<Row> it = coreClassFile.scan();
        while (it.hasNext()) {
            Row row = it.next();
            String tableName = row.getString(1);
            if (tableName.equals(name)) {
                // Check if deleted (xmax != 0) - for now we use stub visibility
                // Stub visibility: xmax != 0 means deleted
                // Actually, the HeapFile.scan() already filters out deleted rows
                return Optional.of(buildTableMeta(row));
            }
        }
        return Optional.empty();
    }

    /**
     * Lists all non-deleted user tables in the catalog.
     * System catalogs (oid <= CORE_ATTRIBUTE_OID) are filtered out.
     *
     * @return list of TableMeta for all live user tables
     * @throws IOException if scan fails
     */
    public List<TableMeta> listTables() throws IOException {
        List<TableMeta> tables = new ArrayList<>();
        Iterator<Row> it = coreClassFile.scan();
        while (it.hasNext()) {
            Row row = it.next();
            int oid = row.getLong(0).intValue();
            // Filter out system catalogs (oid 1000, 1001)
            if (oid <= BootstrapCatalog.CORE_ATTRIBUTE_OID) {
                continue;
            }
            tables.add(buildTableMeta(row));
        }
        return tables;
    }

    /**
     * Soft-deletes a table by setting t_xmax on its core_class row.
     * The heap file on disk is not removed (v1 does not reclaim storage).
     *
     * @param name the table name
     * @throws IllegalArgumentException if table not found
     * @throws IOException if delete fails
     */
    public void dropTable(String name) throws IOException {
        RecordId targetRid = findTableRecordId(name);
        if (targetRid == null) {
            throw new IllegalArgumentException("Table not found: " + name);
        }

        coreClassFile.delete(targetRid);
        log.info("Dropped table {} (soft delete, rid={})", name, targetRid);
    }

    /**
     * Returns the path to a table's heap file.
     *
     * @param oid the table OID
     * @return the Path to the table file
     */
    public Path tablePath(int oid) {
        return dataDir.resolve("base").resolve("1").resolve(String.valueOf(oid));
    }

    @Override
    public void close() throws IOException {
        coreClassFile.close();
        coreAttributeFile.close();
        log.debug("Catalog closed");
    }

    // ==================== Private Helper Methods ====================

    /**
     * Finds the RecordId for a table by name.
     *
     * <p>Note: This uses HeapPage.scan() + HeapFile.get() rather than HeapFile.scan()
     * because we need the RecordId for deletion. HeapFile.scan() returns an iterator
     * over rows but doesn't expose the RecordIds. Deleted rows are correctly filtered
     * out since HeapFile.get() returns Optional.empty() for deleted slots.
     */
    private RecordId findTableRecordId(String name) throws IOException {
        for (int pageId = 1; pageId < coreClassFile.pageCount(); pageId++) {
            Page page = coreClassFile.readPage(pageId);
            if (page.pageType() != PageType.HEAP) {
                continue;
            }
            HeapPage hp = new HeapPage(page);
            for (RecordId rid : hp.scan()) {
                Optional<Row> row = coreClassFile.get(rid);
                if (row.isPresent() && name.equals(row.get().getString(1))) {
                    return rid;
                }
            }
        }
        return null;
    }

    private TableMeta buildTableMeta(Row classRow) throws IOException {
        int oid = classRow.getLong(0).intValue();
        String name = classRow.getString(1);
        String pkColumn = classRow.getString(2);
        int engineTypeCode = classRow.getInt(3);
        EngineType engineType = EngineType.values()[engineTypeCode];

        // Look up columns from core_attribute - collect in a map first, then sort by attnum
        // TODO: This is O(n) over all attribute rows per table lookup. For v1 this is fine,
        // but we should add an index (e.g., btree on tableId) or cache for production use.
        record ColInfo(String name, ColumnType type, boolean nullable, int attnum) {}
        List<ColInfo> colInfos = new ArrayList<>();

        Iterator<Row> attrIt = coreAttributeFile.scan();
        while (attrIt.hasNext()) {
            Row attrRow = attrIt.next();
            long tableId = attrRow.getLong(0);
            if (tableId == oid) {
                int attnum = attrRow.getInt(1);
                String colName = attrRow.getString(2);
                int typeCode = attrRow.getInt(3);
                boolean nullable = attrRow.getBoolean(4);
                ColumnType type = columnCodeToType(typeCode);
                colInfos.add(new ColInfo(colName, type, nullable, attnum));
            }
        }

        // Sort by attnum and create columns
        colInfos.sort((a, b) -> Integer.compare(a.attnum(), b.attnum()));
        List<Column> columns = colInfos.stream()
            .map(ci -> new Column(ci.name(), ci.type(), ci.nullable()))
            .toList();

        Schema schema = Schema.of(columns);
        return new TableMeta(oid, name, schema, pkColumn, engineType);
    }

    private int columnTypeToCode(ColumnType type) {
        return switch (type) {
            case INT -> TYPE_INT;
            case LONG -> TYPE_LONG;
            case STRING -> TYPE_STRING;
            case BOOL -> TYPE_BOOL;
        };
    }

    private ColumnType columnCodeToType(int code) {
        return switch (code) {
            case TYPE_INT -> ColumnType.INT;
            case TYPE_LONG -> ColumnType.LONG;
            case TYPE_STRING -> ColumnType.STRING;
            case TYPE_BOOL -> ColumnType.BOOL;
            default -> throw new IllegalArgumentException("Unknown type code: " + code);
        };
    }
}
