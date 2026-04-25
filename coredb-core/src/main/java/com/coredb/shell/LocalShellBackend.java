package com.coredb.shell;

import com.coredb.api.Column;
import com.coredb.api.CoreDB;
import com.coredb.api.Row;
import com.coredb.api.Schema;
import com.coredb.catalog.BootstrapCatalog;
import com.coredb.catalog.Catalog;
import com.coredb.catalog.ColumnDefParser;
import com.coredb.catalog.ControlFile;
import com.coredb.catalog.TableMeta;
import com.coredb.heap.HeapFile;
import com.coredb.heap.RecordId;
import com.coredb.util.Constants;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

public final class LocalShellBackend implements ShellBackend, AutoCloseable {

    // Hard-coded schema for raw demo: id (LONG), name (STRING), age (INT)
    private static final Schema RAW_SCHEMA = Schema.of(
        Column.longCol("id"),
        Column.stringCol("name"),
        Column.intCol("age")
    );

    private final CoreDB db;

    public LocalShellBackend(CoreDB db) {
        this.db = db;
    }

    private Catalog getCatalog() {
        return db.catalog();
    }

    @Override
    public void close() throws IOException {
        // Catalog is owned by CoreDB; CoreDB.close() handles cleanup
    }

    @Override
    public String execute(String input) {
        String trimmed = input.trim();
        if (trimmed.isEmpty()) {
            return "";
        }

        String[] parts = trimmed.split("\\s+", 2);
        String command = parts[0].toLowerCase();
        String args = parts.length > 1 ? parts[1] : "";

        return switch (command) {
            case "version"        -> formatVersion();
            case "status"         -> formatStatus();
            case "insert-raw"     -> handleInsertRaw(args);
            case "get-raw"        -> handleGetRaw(args);
            case "scan-raw"       -> handleScanRaw(args);
            case "delete-raw"     -> handleDeleteRaw(args);
            case "heap-stats"     -> handleHeapStats(args);
            case "schema-parse"      -> handleSchemaParse(args);
            case "control-info"      -> handleControlInfo();
            case "control-alloc-oid" -> handleControlAllocOid();
            case "heap-meta"         -> handleHeapMeta(args);
            case "bootstrap"         -> handleBootstrap();
            case "create-table"      -> handleCreateTable(args);
            case "list-tables"       -> handleListTables();
            case "describe"          -> handleDescribe(args);
            case "drop-table"        -> handleDropTable(args);
            case "help"              -> formatHelp();
            default                  -> "unknown command: " + command + "  (type 'help' for available commands)";
        };
    }

    private String formatVersion() {
        return String.format("CoreDB 0.1 | engine=%s | page-size=%d",
                db.config().engineType(), db.config().pageSize());
    }

    private String formatStatus() {
        String path = db.dataPath().toAbsolutePath().toString();
        boolean exists = Files.exists(db.dataPath());
        return String.format("file=%s  exists=%b", path, exists);
    }

    private String formatHelp() {
        return """
            Cluster commands:
              control-info               show pg_control contents
              control-alloc-oid          allocate next OID and persist
              bootstrap                  show bootstrap status

            Catalog commands:
              create-table <name> cols... pk:<col>   create a new table
              list-tables                              list all tables
              describe <name>                          show table schema
              drop-table <name>                        drop a table (soft delete)
              schema-parse <def>                       parse column definition

            Diagnostics:
              version                    print version and config
              status                     show DB file path and whether it exists
              help                       list available commands
              quit                       exit

            Debug: (raw heap commands bypass Catalog and access heap files directly)
              insert-raw table=<name>|oid=<N> id=N name=XXX age=N   insert a row
              get-raw table=<name>|oid=<N> rid=page:slot            get a row by RecordId
              scan-raw table=<name>|oid=<N>                         scan all rows
              delete-raw table=<name>|oid=<N> rid=page:slot         delete a row by RecordId
              heap-stats table=<name>|oid=<N>                       show file stats
              heap-meta table=<name>|oid=<N>                        show meta page of per-table heap file
            """;
    }

    private String handleInsertRaw(String args) {
        OidResolution resolution = resolveOidFull(args);
        if (!resolution.isSuccess()) {
            return resolution.errorMessage();
        }
        int oid = resolution.oid();

        Long id = null;
        String name = null;
        Integer age = null;

        for (String part : args.split("\\s+")) {
            try {
                if (part.startsWith("id=")) {
                    id = Long.parseLong(part.substring(3));
                } else if (part.startsWith("name=")) {
                    name = part.substring(5);
                } else if (part.startsWith("age=")) {
                    age = Integer.parseInt(part.substring(4));
                }
            } catch (NumberFormatException e) {
                return "usage: insert-raw table=<name>|oid=<N> id=N name=XXX age=N (invalid number: " + part + ")";
            }
        }

        if (id == null || name == null || age == null) {
            return "usage: insert-raw table=<name>|oid=<N> id=N name=XXX age=N";
        }

        // Build path: dataDir/base/1/<oid>
        Path tablePath = db.dataPath().resolve("base").resolve("1").resolve(String.valueOf(oid));

        if (!Files.exists(tablePath)) {
            return "error: heap file not found: " + db.dataPath().relativize(tablePath);
        }

        try (HeapFile hf = HeapFile.open(tablePath, oid, schemaForOid(oid))) {
            Row row = Row.of(id, name, age);
            RecordId rid = hf.insert(row);
            return String.format("rid=%s  (xmin=%d xmax=%d)", rid, Constants.BOOTSTRAP_XID, Constants.INVALID_XID);
        } catch (Exception e) {
            return "error: " + e.getMessage();
        }
    }

    private String handleGetRaw(String args) {
        OidResolution resolution = resolveOidFull(args);
        if (!resolution.isSuccess()) {
            return resolution.errorMessage();
        }
        int oid = resolution.oid();

        RecordId rid = null;
        for (String part : args.split("\\s+")) {
            if (part.startsWith("rid=")) {
                try {
                    rid = RecordId.parse(part.substring(4));
                } catch (IllegalArgumentException e) {
                    return "invalid rid format: " + part.substring(4);
                }
            }
        }

        if (rid == null) {
            return "usage: get-raw table=<name>|oid=<N> rid=page:slot";
        }

        // Build path: dataDir/base/1/<oid>
        Path tablePath = db.dataPath().resolve("base").resolve("1").resolve(String.valueOf(oid));

        if (!Files.exists(tablePath)) {
            return "error: heap file not found: " + db.dataPath().relativize(tablePath);
        }

        try (HeapFile hf = HeapFile.open(tablePath, oid, schemaForOid(oid))) {
            Optional<Row> row = hf.get(rid);
            if (row.isPresent()) {
                return row.get().values().toString();
            } else {
                return "(not found or not visible)";
            }
        } catch (Exception e) {
            return "error: " + e.getMessage();
        }
    }

    private String handleScanRaw(String args) {
        OidResolution resolution = resolveOidFull(args);
        if (!resolution.isSuccess()) {
            return resolution.errorMessage();
        }
        int oid = resolution.oid();

        // Build path: dataDir/base/1/<oid>
        Path tablePath = db.dataPath().resolve("base").resolve("1").resolve(String.valueOf(oid));

        if (!Files.exists(tablePath)) {
            return "error: heap file not found: " + db.dataPath().relativize(tablePath);
        }

        try (HeapFile hf = HeapFile.open(tablePath, oid, schemaForOid(oid))) {
            StringBuilder sb = new StringBuilder();
            int count = 0;

            Iterator<Row> it = hf.scan();
            while (it.hasNext()) {
                Row row = it.next();
                sb.append(row.values().toString()).append("\n");
                count++;
            }

            if (count == 0) {
                return "(no live tuples)";
            }
            return String.format("(%d rows)%n", count) + sb.toString().stripTrailing();
        } catch (Exception e) {
            return "error: " + e.getMessage();
        }
    }

    private String handleDeleteRaw(String args) {
        OidResolution resolution = resolveOidFull(args);
        if (!resolution.isSuccess()) {
            return resolution.errorMessage();
        }
        int oid = resolution.oid();

        RecordId rid = null;
        for (String part : args.split("\\s+")) {
            if (part.startsWith("rid=")) {
                try {
                    rid = RecordId.parse(part.substring(4));
                } catch (IllegalArgumentException e) {
                    return "invalid rid format: " + part.substring(4);
                }
            }
        }

        if (rid == null) {
            return "usage: delete-raw table=<name>|oid=<N> rid=page:slot";
        }

        // Build path: dataDir/base/1/<oid>
        Path tablePath = db.dataPath().resolve("base").resolve("1").resolve(String.valueOf(oid));

        if (!Files.exists(tablePath)) {
            return "error: heap file not found: " + db.dataPath().relativize(tablePath);
        }

        try (HeapFile hf = HeapFile.open(tablePath, oid, schemaForOid(oid))) {
            hf.delete(rid);
            return "ok (t_xmax set)";
        } catch (Exception e) {
            return "error: " + e.getMessage();
        }
    }

    private String handleHeapStats(String args) {
        OidResolution resolution = resolveOidFull(args);
        if (!resolution.isSuccess()) {
            return resolution.errorMessage();
        }
        int oid = resolution.oid();

        // Build path: dataDir/base/1/<oid>
        Path tablePath = db.dataPath().resolve("base").resolve("1").resolve(String.valueOf(oid));

        if (!Files.exists(tablePath)) {
            return "error: heap file not found: " + db.dataPath().relativize(tablePath);
        }

        try (HeapFile hf = HeapFile.open(tablePath, oid, schemaForOid(oid))) {
            long fileSize = hf.fileSize();
            int nextPageId = hf.nextPageId();
            int dataPages = nextPageId - 1; // Page 0 is meta

            // Count live rows by scanning
            int liveRows = 0;
            Iterator<Row> it = hf.scan();
            while (it.hasNext()) {
                it.next();
                liveRows++;
            }

            StringBuilder sb = new StringBuilder();
            sb.append("path=").append(db.dataPath().relativize(tablePath)).append("\n");
            sb.append(String.format("fileSize=%d bytes (%d pages)%n", fileSize, fileSize / Constants.PAGE_SIZE));
            sb.append("nextPageId=").append(nextPageId).append("\n");
            sb.append("livePages=").append(dataPages).append("  liveRows=").append(liveRows);

            return sb.toString();
        } catch (Exception e) {
            return "error: " + e.getMessage();
        }
    }

    private String handleSchemaParse(String args) {
        if (args.isBlank()) {
            return "usage: schema-parse id:long name:string age:int pk:id";
        }

        try {
            ColumnDefParser.ParsedSchema parsed = ColumnDefParser.parse(args);
            return ColumnDefParser.formatSchema(parsed.schema(), parsed.pkColumn());
        } catch (IllegalArgumentException e) {
            return "error: " + e.getMessage();
        }
    }

    private String handleControlInfo() {
        ControlFile cf = db.controlFile();
        return cf.formatInfo();
    }

    private String handleControlAllocOid() {
        try {
            ControlFile cf = db.controlFile();
            int oid = cf.allocateOid();
            return String.format("%d  (nextOid now %d)", oid, cf.nextOid());
        } catch (IOException e) {
            return "error: " + e.getMessage();
        }
    }

    private String handleHeapMeta(String args) {
        OidResolution resolution = resolveOidFull(args);
        if (!resolution.isSuccess()) {
            return resolution.errorMessage();
        }
        int oid = resolution.oid();

        // Build path: dataDir/base/1/<oid>
        Path tablePath = db.dataPath().resolve("base").resolve("1").resolve(String.valueOf(oid));

        if (!Files.exists(tablePath)) {
            return "error: heap file not found: " + db.dataPath().relativize(tablePath);
        }

        try {
            // We need a schema to open, but for meta page inspection we just need to validate
            // Use a dummy schema - the meta page doesn't depend on it
            Schema dummySchema = Schema.of(Column.longCol("dummy"));
            try (HeapFile hf = HeapFile.open(tablePath, oid, dummySchema)) {
                String path = db.dataPath().relativize(hf.tablePath()).toString();
                StringBuilder sb = new StringBuilder();
                sb.append("path=").append(path).append("\n");
                sb.append(String.format("magic=0x%08X (\"HEAP\")%n", Constants.HEAP_FILE_MAGIC));
                sb.append("formatVersion=1\n");
                sb.append("oid=").append(oid).append("\n");
                sb.append("nextPageId=").append(hf.nextPageId());
                return sb.toString();
            }
        } catch (IOException e) {
            return "error: " + e.getMessage();
        }
    }

    private static Schema schemaForOid(int oid) {
        return switch (oid) {
            case BootstrapCatalog.CORE_CLASS_OID      -> BootstrapCatalog.CORE_CLASS_SCHEMA;
            case BootstrapCatalog.CORE_ATTRIBUTE_OID  -> BootstrapCatalog.CORE_ATTRIBUTE_SCHEMA;
            default                                   -> RAW_SCHEMA;
        };
    }

    /**
     * Result of OID resolution: either success with OID, or failure with error message.
     */
    private record OidResolution(int oid, String errorMessage, boolean isSuccess) {
        static OidResolution success(int oid) {
            return new OidResolution(oid, null, true);
        }
        static OidResolution failure(String errorMessage) {
            return new OidResolution(-1, errorMessage, false);
        }
    }

    /**
     * Resolves either "oid=N" or "table=<name>" to an OID.
     * Returns an OidResolution with success status and appropriate error message on failure.
     */
    private OidResolution resolveOidFull(String args) {
        String[] parts = args.split("\\s+");
        for (String part : parts) {
            if (part.startsWith("oid=")) {
                try {
                    return OidResolution.success(Integer.parseInt(part.substring(4)));
                } catch (NumberFormatException e) {
                    return OidResolution.failure("usage: specify oid=<N> or table=<name>");
                }
            } else if (part.startsWith("table=")) {
                String tableName = part.substring(6);
                try {
                    Catalog cat = getCatalog();
                    Optional<TableMeta> meta = cat.openTable(tableName);
                    if (meta.isEmpty()) {
                        return OidResolution.failure("error: unknown table: " + tableName);
                    }
                    return OidResolution.success(meta.get().oid());
                } catch (IOException e) {
                    return OidResolution.failure("error: " + e.getMessage());
                }
            }
        }
        return OidResolution.failure("usage: specify oid=<N> or table=<name>");
    }

    private String handleCreateTable(String args) {
        if (args.isBlank()) {
            return "usage: create-table <name> col:type ... pk:<col>\n" +
                   "  e.g., create-table users id:long name:string age:int pk:id";
        }

        String[] parts = args.trim().split("\\s+", 2);
        String tableName = parts[0];
        String colDefs = parts.length > 1 ? parts[1] : "";

        if (colDefs.isBlank()) {
            return "usage: create-table <name> col:type ... pk:<col> (at least one column required)";
        }

        try {
            ColumnDefParser.ParsedSchema parsed = ColumnDefParser.parse(colDefs);
            Catalog cat = getCatalog();
            cat.createTable(tableName, parsed.schema(), parsed.pkColumn());
            return "ok";
        } catch (IllegalArgumentException e) {
            return "error: " + e.getMessage();
        } catch (IOException e) {
            return "error: " + e.getMessage();
        }
    }

    private String handleListTables() {
        try {
            Catalog cat = getCatalog();
            List<TableMeta> tables = cat.listTables();
            if (tables.isEmpty()) {
                return "(no tables)";
            }
            StringBuilder sb = new StringBuilder();
            for (TableMeta meta : tables) {
                String pk = meta.pkColumn() != null ? meta.pkColumn() : "(none)";
                sb.append(String.format("%-20s oid=%d  pk=%s  engine=%s%n",
                    meta.name(), meta.oid(), pk, meta.engineType()));
            }
            return sb.toString().stripTrailing();
        } catch (IOException e) {
            return "error: " + e.getMessage();
        }
    }

    private String handleDescribe(String args) {
        if (args.isBlank()) {
            return "usage: describe <table-name>";
        }
        String tableName = args.trim().split("\\s+")[0];

        try {
            Catalog cat = getCatalog();
            Optional<TableMeta> metaOpt = cat.openTable(tableName);
            if (metaOpt.isEmpty()) {
                return "error: unknown table: " + tableName;
            }
            TableMeta meta = metaOpt.get();
            StringBuilder sb = new StringBuilder();
            String pk = meta.pkColumn() != null ? meta.pkColumn() : "(none)";
            sb.append(String.format("table=%s  oid=%d  pk=%s  engine=%s%n",
                meta.name(), meta.oid(), pk, meta.engineType()));
            sb.append(String.format("%-5s %-7s %s%n", "col", "type", "nullable"));
            for (int i = 0; i < meta.schema().columnCount(); i++) {
                Column col = meta.schema().column(i);
                sb.append(String.format("%-5s %-7s %s%n",
                    col.name(), col.type(), col.nullable() ? "true" : "false"));
            }
            return sb.toString().stripTrailing();
        } catch (IOException e) {
            return "error: " + e.getMessage();
        }
    }

    private String handleDropTable(String args) {
        if (args.isBlank()) {
            return "usage: drop-table <table-name>";
        }
        String tableName = args.trim().split("\\s+")[0];

        try {
            Catalog cat = getCatalog();
            cat.dropTable(tableName);
            return "ok (soft delete)";
        } catch (IllegalArgumentException e) {
            return "error: " + e.getMessage();
        } catch (IOException e) {
            return "error: " + e.getMessage();
        }
    }

    private String handleBootstrap() {
        // CoreDB.open() already bootstraps on first run.
        // This command is now a status probe only.
        Path controlPath = db.dataPath().resolve("global/pg_control");
        if (Files.exists(controlPath)) {
            return "already initialized (pg_control exists)";
        }
        throw new IllegalStateException("pg_control missing — CoreDB.open() should have initialized");
    }
}
