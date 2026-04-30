package com.coredb.shell;

import com.coredb.api.Column;
import com.coredb.api.CoreDB;
import com.coredb.api.Row;
import com.coredb.api.Schema;
import com.coredb.buffer.BufferPool;
import com.coredb.catalog.BootstrapCatalog;
import com.coredb.catalog.Catalog;
import com.coredb.catalog.ColumnDefParser;
import com.coredb.catalog.ControlFile;
import com.coredb.catalog.TableMeta;
import com.coredb.engine.BTreeStorageEngine;
import com.coredb.engine.StorageEngine;
import com.coredb.vacuum.VacuumExecutor;
import com.coredb.vacuum.VacuumStats;
import com.coredb.heap.HeapFile;
import com.coredb.heap.RecordId;
import com.coredb.mvcc.Snapshot;
import com.coredb.index.BTreeLeafPage;
import com.coredb.index.IndexFile;
import com.coredb.index.IndexPageLayout;
import com.coredb.txn.IsolationLevel;
import com.coredb.txn.LockManager;
import com.coredb.txn.LockMode;
import com.coredb.util.Constants;
import com.coredb.wal.XLogReader;
import com.coredb.wal.XLogRecord;
import com.coredb.wal.XLogResourceManager;
import com.coredb.txn.ClogManager;
import com.coredb.txn.Transaction;
import com.coredb.txn.TransactionManager;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class LocalShellBackend implements ShellBackend, AutoCloseable {

    // Hard-coded schema for raw demo: id (LONG), name (STRING), age (INT)
    private static final Schema RAW_SCHEMA = Schema.of(
        Column.longCol("id"),
        Column.stringCol("name"),
        Column.intCol("age")
    );

    private final CoreDB db;
    private IsolationLevel currentDefaultLevel = IsolationLevel.REPEATABLE_READ;

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

        // Transaction control commands are never auto-committed
        return switch (command) {
            case "begin"          -> handleBegin();
            case "commit"         -> handleCommit();
            case "rollback"       -> handleRollback();
            case "set-isolation"  -> handleSetIsolation(args);
            default               -> executeWithAutoCommit(command, args);
        };
    }

    /**
     * Runs a command inside an auto-commit transaction when no transaction is active.
     *
     * <p>If the caller has already issued {@code begin}, the command runs inside that
     * transaction and the caller is responsible for {@code commit} or {@code rollback}.
     * Otherwise a transaction is begun, the command runs, and it is committed on success
     * or rolled back if an unchecked exception propagates.
     *
     * <p>The engine requires an active transaction for every mutation or read. This
     * wrapper satisfies that requirement transparently for single-statement shell usage.
     */
    private String executeWithAutoCommit(String command, String args) {
        TransactionManager txnMgr = db.transactionManager();
        boolean autoCommit = txnMgr.currentTransaction() == null;

        Transaction tx = null;
        if (autoCommit) {
            try {
                tx = txnMgr.beginTransaction();
            } catch (IOException e) {
                return "error: failed to begin transaction: " + e.getMessage();
            }
        }

        try {
            String result = dispatch(command, args);
            if (autoCommit) {
                if (result.startsWith("error:")) {
                    txnMgr.rollback(tx);
                } else {
                    txnMgr.commit(tx);
                }
            }
            return result;
        } catch (RuntimeException e) {
            if (autoCommit && tx != null) {
                try { txnMgr.rollback(tx); } catch (IOException ignored) {}
            }
            throw e;
        } catch (Exception e) {
            if (autoCommit && tx != null) {
                try { txnMgr.rollback(tx); } catch (IOException ignored) {}
            }
            return "error: " + e.getMessage();
        }
    }

    private String dispatch(String command, String args) {
        return switch (command) {
            case "version" -> formatVersion();
            case "status" -> formatStatus();
            case "insert-raw" -> handleInsertRaw(args);
            case "get-raw" -> handleGetRaw(args);
            case "scan-raw" -> handleScanRaw(args);
            case "delete-raw" -> handleDeleteRaw(args);
            case "heap-stats" -> handleHeapStats(args);
            case "buffer-stats" -> handleBufferStats();
            case "schema-parse" -> handleSchemaParse(args);
            case "control-info" -> handleControlInfo();
            case "control-alloc-oid" -> handleControlAllocOid();
            case "heap-meta" -> handleHeapMeta(args);
            case "bootstrap" -> handleBootstrap();
            case "create-table" -> handleCreateTable(args);
            case "list-tables" -> handleListTables();
            case "describe" -> handleDescribe(args);
            case "drop-table" -> handleDropTable(args);
            case "index-meta" -> handleIndexMeta(args);
            case "index-dump" -> handleIndexDump(args);
            case "put" -> handlePut(args);
            case "get" -> handleGet(args);
            case "delete" -> handleDelete(args);
            case "scan" -> handleScan(args);
            case "range" -> handleRange(args);
            case "wal-dump" -> handleWalDump();
            case "checkpoint" -> handleCheckpoint();
            case "recovery-status" -> handleRecoveryStatus();
            case "clog-status" -> handleClogStatus(args);
            case "vacuum" -> handleVacuum(args);
            case "vacuum-stats" -> handleVacuumStats(args);
            case "lock-table" -> handleLockTable();
            case "help" -> formatHelp();
            default -> "unknown command: " + command + "  (type 'help' for available commands)";
        };
    }

    private String handleBegin() {
        TransactionManager txnMgr = db.transactionManager();
        if (txnMgr.currentTransaction() != null) {
            return "error: transaction already active (xid=" + txnMgr.currentTransaction().xid() + ")";
        }
        try {
            Transaction tx = txnMgr.beginTransaction(currentDefaultLevel);
            return "xid=" + tx.xid() + " started (" + formatLevel(tx.level()) + ")";
        } catch (IOException e) {
            return "error: " + e.getMessage();
        }
    }

    private String handleSetIsolation(String args) {
        TransactionManager txnMgr = db.transactionManager();
        if (txnMgr.currentTransaction() != null) {
            return "error: cannot change isolation level inside a transaction";
        }
        IsolationLevel level = parseIsolationLevel(args.trim().toLowerCase());
        if (level == null) {
            return "error: unknown isolation level: " + args.trim() +
                   "\n  valid values: read-uncommitted, read-committed, repeatable-read, serializable";
        }
        currentDefaultLevel = level;
        return "default isolation level set to " + formatLevel(level.canonical());
    }

    private static IsolationLevel parseIsolationLevel(String s) {
        return switch (s) {
            case "read-uncommitted", "read_uncommitted"   -> IsolationLevel.READ_UNCOMMITTED;
            case "read-committed",   "read_committed"     -> IsolationLevel.READ_COMMITTED;
            case "repeatable-read",  "repeatable_read"    -> IsolationLevel.REPEATABLE_READ;
            case "serializable"                           -> IsolationLevel.SERIALIZABLE;
            default                                       -> null;
        };
    }

    private static String formatLevel(IsolationLevel level) {
        return switch (level) {
            case READ_UNCOMMITTED -> "READ UNCOMMITTED";
            case READ_COMMITTED   -> "READ COMMITTED";
            case REPEATABLE_READ  -> "REPEATABLE READ";
            case SERIALIZABLE     -> "SERIALIZABLE";
        };
    }

    private String handleCommit() {
        TransactionManager txnMgr = db.transactionManager();
        Transaction tx = txnMgr.currentTransaction();
        if (tx == null) {
            return "error: no active transaction";
        }
        int xid = tx.xid();
        try {
            txnMgr.commit(tx);
            return "xid=" + xid + " committed";
        } catch (IOException e) {
            txnMgr.clearCurrentTransaction();
            return "error: commit failed — transaction cleared, outcome uncertain: " + e.getMessage();
        }
    }

    private String handleRollback() {
        TransactionManager txnMgr = db.transactionManager();
        Transaction tx = txnMgr.currentTransaction();
        if (tx == null) {
            return "error: no active transaction";
        }
        int xid = tx.xid();
        try {
            txnMgr.rollback(tx);
            return "xid=" + xid + " aborted";
        } catch (IOException e) {
            txnMgr.clearCurrentTransaction();
            return "error: rollback failed — transaction cleared: " + e.getMessage();
        }
    }

    private String formatVersion() {
        return String.format(
            "CoreDB 0.1 | engine=%s | page-size=%d",
            db.config().engineType(),
            db.config().pageSize()
        );
    }

    private String formatStatus() {
        String path = db.dataPath().toAbsolutePath().toString();
        boolean exists = Files.exists(db.dataPath());
        return String.format("file=%s  exists=%b", path, exists);
    }

    private String handleBufferStats() {
        return db.bufferPool().statsString();
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

        Data commands (via StorageEngine - uses heap+index, MVCC semantics):
          put <table> <pk> <col1> <col2> ...       insert or update a row (upsert)
          get <table> <pk>                         retrieve a row by primary key
          delete <table> <pk>                      delete a row by primary key
          scan <table>                             full table scan (heap order)
          range <table> <from-pk> <to-pk>          range scan by primary key (sorted)

        Diagnostics:
          version                    print version and config
          status                     show DB file path and whether it exists
          buffer-stats               show buffer pool statistics
          help                       list available commands
          quit                       exit

        Debug: (raw heap commands - bypass StorageEngine, direct heap access)
          insert-raw table=<name>|oid=<N> id=N name=XXX age=N   insert a row
          get-raw table=<name>|oid=<N> rid=page:slot            get a row by RecordId
          scan-raw table=<name>|oid=<N>                         scan all rows
          delete-raw table=<name>|oid=<N> rid=page:slot         delete a row by RecordId
          heap-stats table=<name>|oid=<N>                       show file stats
          heap-meta table=<name>|oid=<N>                        show meta page of per-table heap file
          index-meta table=<name>|oid=<N>                       show meta page of per-table index file
          index-dump table=<name>|oid=<N> page=<P>              dump index page contents

        WAL commands:
          wal-dump                   dump WAL file contents
          checkpoint                 perform database checkpoint (flush dirty pages, write CHECKPOINT record)
          recovery-status            show last recovery statistics

        Transaction commands:
          begin                      start a new transaction
          commit                     commit the current transaction
          rollback                   abort the current transaction
          set-isolation <level>      set default isolation level (read-uncommitted | read-committed | repeatable-read | serializable)
          clog-status [xid]          show transaction status log summary or specific XID status
          lock-table                 show current lock table (holders and waiters)
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
                return (
                    "usage: insert-raw table=<name>|oid=<N> id=N name=XXX age=N (invalid number: " +
                    part +
                    ")"
                );
            }
        }

        if (id == null || name == null || age == null) {
            return "usage: insert-raw table=<name>|oid=<N> id=N name=XXX age=N";
        }

        // Build path: dataDir/base/1/<oid>
        Path tablePath = db
            .dataPath()
            .resolve("base")
            .resolve("1")
            .resolve(String.valueOf(oid));

        if (!Files.exists(tablePath)) {
            return (
                "error: heap file not found: " +
                db.dataPath().relativize(tablePath)
            );
        }

        try (HeapFile hf = HeapFile.open(tablePath, oid, schemaForOid(oid))) {
            Row row = Row.of(id, name, age);
            RecordId rid = hf.insert(row, Constants.BOOTSTRAP_XID);
            return String.format(
                "rid=%s  (xmin=%d xmax=%d)",
                rid,
                Constants.BOOTSTRAP_XID,
                Constants.INVALID_XID
            );
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
        Path tablePath = db
            .dataPath()
            .resolve("base")
            .resolve("1")
            .resolve(String.valueOf(oid));

        if (!Files.exists(tablePath)) {
            return (
                "error: heap file not found: " +
                db.dataPath().relativize(tablePath)
            );
        }

        try (HeapFile hf = HeapFile.open(tablePath, oid, schemaForOid(oid))) {
            Optional<Row> row = hf.get(rid, Snapshot.BOOTSTRAP, db.clog());
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
        Path tablePath = db
            .dataPath()
            .resolve("base")
            .resolve("1")
            .resolve(String.valueOf(oid));

        if (!Files.exists(tablePath)) {
            return (
                "error: heap file not found: " +
                db.dataPath().relativize(tablePath)
            );
        }

        try (HeapFile hf = HeapFile.open(tablePath, oid, schemaForOid(oid))) {
            StringBuilder sb = new StringBuilder();
            int count = 0;

            Iterator<Row> it = hf.scan(Snapshot.BOOTSTRAP, db.clog());
            while (it.hasNext()) {
                Row row = it.next();
                sb.append(row.values().toString()).append("\n");
                count++;
            }

            if (count == 0) {
                return "(no live tuples)";
            }
            return (
                String.format("(%d rows)%n", count) +
                sb.toString().stripTrailing()
            );
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
        Path tablePath = db
            .dataPath()
            .resolve("base")
            .resolve("1")
            .resolve(String.valueOf(oid));

        if (!Files.exists(tablePath)) {
            return (
                "error: heap file not found: " +
                db.dataPath().relativize(tablePath)
            );
        }

        try (HeapFile hf = HeapFile.open(tablePath, oid, schemaForOid(oid))) {
            hf.delete(rid, Constants.BOOTSTRAP_XID);
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
        Path tablePath = db
            .dataPath()
            .resolve("base")
            .resolve("1")
            .resolve(String.valueOf(oid));

        if (!Files.exists(tablePath)) {
            return (
                "error: heap file not found: " +
                db.dataPath().relativize(tablePath)
            );
        }

        try (HeapFile hf = HeapFile.open(tablePath, oid, schemaForOid(oid))) {
            long fileSize = hf.fileSize();
            int nextPageId = hf.nextPageId();
            int dataPages = nextPageId - 1; // Page 0 is meta

            // Count live rows by scanning
            int liveRows = 0;
            Iterator<Row> it = hf.scan(Snapshot.BOOTSTRAP, db.clog());
            while (it.hasNext()) {
                it.next();
                liveRows++;
            }

            StringBuilder sb = new StringBuilder();
            sb
                .append("path=")
                .append(db.dataPath().relativize(tablePath))
                .append("\n");
            sb.append(
                String.format(
                    "fileSize=%d bytes (%d pages)%n",
                    fileSize,
                    fileSize / Constants.PAGE_SIZE
                )
            );
            sb.append("nextPageId=").append(nextPageId).append("\n");
            sb
                .append("livePages=")
                .append(dataPages)
                .append("  liveRows=")
                .append(liveRows);

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
            return ColumnDefParser.formatSchema(
                parsed.schema(),
                parsed.pkColumn()
            );
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
        Path tablePath = db
            .dataPath()
            .resolve("base")
            .resolve("1")
            .resolve(String.valueOf(oid));

        if (!Files.exists(tablePath)) {
            return (
                "error: heap file not found: " +
                db.dataPath().relativize(tablePath)
            );
        }

        try {
            // We need a schema to open, but for meta page inspection we just need to validate
            // Use a dummy schema - the meta page doesn't depend on it
            Schema dummySchema = Schema.of(Column.longCol("dummy"));
            try (HeapFile hf = HeapFile.open(tablePath, oid, dummySchema)) {
                String path = db
                    .dataPath()
                    .relativize(hf.tablePath())
                    .toString();
                StringBuilder sb = new StringBuilder();
                sb.append("path=").append(path).append("\n");
                sb.append(
                    String.format(
                        "magic=0x%08X (\"HEAP\")%n",
                        Constants.HEAP_FILE_MAGIC
                    )
                );
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
            case BootstrapCatalog.CORE_CLASS_OID -> BootstrapCatalog.CORE_CLASS_SCHEMA;
            case BootstrapCatalog.CORE_ATTRIBUTE_OID -> BootstrapCatalog.CORE_ATTRIBUTE_SCHEMA;
            default -> RAW_SCHEMA;
        };
    }

    /**
     * Result of OID resolution: either success with OID, or failure with error message.
     */
    private record OidResolution(
        int oid,
        String errorMessage,
        boolean isSuccess
    ) {
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
                    return OidResolution.success(
                        Integer.parseInt(part.substring(4))
                    );
                } catch (NumberFormatException e) {
                    return OidResolution.failure(
                        "usage: specify oid=<N> or table=<name>"
                    );
                }
            } else if (part.startsWith("table=")) {
                String tableName = part.substring(6);
                try {
                    Catalog cat = getCatalog();
                    Optional<TableMeta> meta = cat.openTable(tableName);
                    if (meta.isEmpty()) {
                        return OidResolution.failure(
                            "error: unknown table: " + tableName
                        );
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
            return (
                "usage: create-table <name> col:type ... pk:<col>\n" +
                "  e.g., create-table users id:long name:string age:int pk:id"
            );
        }

        String[] parts = args.trim().split("\\s+", 2);
        String tableName = parts[0];
        String colDefs = parts.length > 1 ? parts[1] : "";

        if (colDefs.isBlank()) {
            return "usage: create-table <name> col:type ... pk:<col> (at least one column required)";
        }

        try {
            ColumnDefParser.ParsedSchema parsed = ColumnDefParser.parse(
                colDefs
            );
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
                String pk =
                    meta.pkColumn() != null ? meta.pkColumn() : "(none)";
                sb.append(
                    String.format(
                        "%-20s oid=%d  pk=%s  engine=%s%n",
                        meta.name(),
                        meta.oid(),
                        pk,
                        meta.engineType()
                    )
                );
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
            sb.append(
                String.format(
                    "table=%s  oid=%d  pk=%s  engine=%s%n",
                    meta.name(),
                    meta.oid(),
                    pk,
                    meta.engineType()
                )
            );
            sb.append(
                String.format("%-5s %-7s %s%n", "col", "type", "nullable")
            );
            for (int i = 0; i < meta.schema().columnCount(); i++) {
                Column col = meta.schema().column(i);
                sb.append(
                    String.format(
                        "%-5s %-7s %s%n",
                        col.name(),
                        col.type(),
                        col.nullable() ? "true" : "false"
                    )
                );
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
        throw new IllegalStateException(
            "pg_control missing — CoreDB.open() should have initialized"
        );
    }

    private String handleIndexMeta(String args) {
        OidResolution resolution = resolveOidFull(args);
        if (!resolution.isSuccess()) {
            return resolution.errorMessage();
        }
        int oid = resolution.oid();

        // Build path: dataDir/base/1/<oid>_pk
        Path indexPath = db
            .dataPath()
            .resolve("base")
            .resolve("1")
            .resolve(oid + "_pk");

        if (!Files.exists(indexPath)) {
            return (
                "error: index file not found: " +
                db.dataPath().relativize(indexPath)
            );
        }

        try {
            try (IndexFile idx = IndexFile.open(indexPath, oid)) {
                String path = db
                    .dataPath()
                    .relativize(idx.indexPath())
                    .toString();
                StringBuilder sb = new StringBuilder();
                sb.append("path=").append(path).append("\n");
                sb.append(
                    String.format(
                        "magic=0x%08X (\"IDXP\")%n",
                        Constants.INDEX_FILE_MAGIC
                    )
                );
                sb.append("formatVersion=1\n");
                sb.append("oid=").append(oid).append("\n");
                sb.append("root=").append(idx.rootPageId()).append("\n");
                sb.append("height=").append(idx.treeHeight()).append("\n");
                sb.append("nextPageId=").append(idx.nextPageId());
                return sb.toString();
            }
        } catch (IOException e) {
            return "error: " + e.getMessage();
        }
    }

    private String handleIndexDump(String args) {
        // Parse table=<name>|oid=<N> and page=<P>
        OidResolution resolution = resolveOidFull(args);
        if (!resolution.isSuccess()) {
            return resolution.errorMessage();
        }
        int oid = resolution.oid();

        Integer pageId = null;
        for (String part : args.split("\\s+")) {
            if (part.startsWith("page=")) {
                try {
                    pageId = Integer.parseInt(part.substring(5));
                } catch (NumberFormatException e) {
                    return "usage: index-dump table=<name>|oid=<N> page=<P> (invalid page number)";
                }
            }
        }

        if (pageId == null) {
            return "usage: index-dump table=<name>|oid=<N> page=<P>";
        }

        // Build path: dataDir/base/1/<oid>_pk
        Path indexPath = db
            .dataPath()
            .resolve("base")
            .resolve("1")
            .resolve(oid + "_pk");

        if (!Files.exists(indexPath)) {
            return (
                "error: index file not found: " +
                db.dataPath().relativize(indexPath)
            );
        }

        try {
            try (IndexFile idx = IndexFile.open(indexPath, oid)) {
                if (pageId < 1 || pageId >= idx.nextPageId()) {
                    return (
                        "error: page " +
                        pageId +
                        " does not exist (allocated=" +
                        idx.nextPageId() +
                        ")"
                    );
                }

                IndexFile.PinnedPage pinned = idx.readPage(pageId);
                com.coredb.page.Page page = pinned.page();
                IndexPageLayout layout = IndexPageLayout.of(page);
                BTreeLeafPage leaf = BTreeLeafPage.of(layout);

                StringBuilder sb = new StringBuilder();
                sb.append("page=").append(pageId);
                sb.append(" type=").append(leaf.isLeaf() ? "LEAF" : "INTERNAL");
                sb.append(" level=").append(layout.btpoLevel());
                sb.append(" prev=").append(layout.btpoPrev());
                sb.append(" next=").append(layout.btpoNext());
                sb.append(" entries=").append(leaf.entryCount());
                sb.append("\n");

                if (leaf.entryCount() > 0) {
                    sb.append("  slot  key   rid\n");
                    for (int i = 0; i < leaf.entryCount(); i++) {
                        long key = leaf.keyAt(i);
                        RecordId rid = leaf.ridAt(i);
                        sb.append(
                            String.format("  %-5d %-5d %s%n", i, key, rid)
                        );
                    }
                }

                return sb.toString();
            }
        } catch (IOException e) {
            return "error: " + e.getMessage();
        }
    }

    // ==================== StorageEngine Data Commands ====================

    /**
     * Handles: put <table> <pk> <col1> <col2> ...
     * Inserts or updates a row via the StorageEngine (MVCC upsert).
     */
    private String handlePut(String args) {
        if (args.isBlank()) {
            return "usage: put <table> <pk> <col1> <col2> ...";
        }

        String[] parts = args.trim().split("\\s+");
        if (parts.length < 2) {
            return "usage: put <table> <pk> <col1> <col2> ...";
        }

        String tableName = parts[0];
        long pk;
        try {
            pk = Long.parseLong(parts[1]);
        } catch (NumberFormatException e) {
            return "error: primary key must be a long integer";
        }

        try {
            Catalog cat = getCatalog();
            Optional<TableMeta> metaOpt = cat.openTable(tableName);
            if (metaOpt.isEmpty()) {
                return "error: unknown table: " + tableName;
            }
            TableMeta meta = metaOpt.get();

            // Build row from remaining arguments (pk + remaining columns)
            Object[] values = new Object[meta.schema().columnCount()];

            // First column is the PK
            int pkIndex = meta.schema().indexOf(meta.pkColumn());
            values[pkIndex] = pk;

            // Parse remaining arguments for other columns
            int argIdx = 2; // Start after table name and pk
            for (int i = 0; i < meta.schema().columnCount(); i++) {
                if (i == pkIndex) continue; // Skip PK, already set

                if (argIdx >= parts.length) {
                    return (
                        "error: not enough values provided (expected " +
                        (meta.schema().columnCount() - 1) +
                        " non-PK columns)"
                    );
                }

                values[i] = parseValue(
                    parts[argIdx],
                    meta.schema().column(i).type()
                );
                argIdx++;
            }

            Row row = Row.of(values);

            // Use cached engine from CoreDB
            StorageEngine engine = db.getEngineForTable(meta);
            boolean isUpdate = engine.get(pk).isPresent();
            engine.put(pk, row);
            return isUpdate ? "ok (updated)" : "ok (inserted)";
        } catch (IllegalStateException e) {
            return "error: " + e.getMessage();
        } catch (IOException | IllegalArgumentException e) {
            return "error: " + e.getMessage();
        }
    }

    /**
     * Handles: get <table> <pk>
     * Retrieves a row by primary key via the StorageEngine.
     */
    private String handleGet(String args) {
        if (args.isBlank()) {
            return "usage: get <table> <pk>";
        }

        String[] parts = args.trim().split("\\s+");
        if (parts.length != 2) {
            return "usage: get <table> <pk>";
        }

        String tableName = parts[0];
        long pk;
        try {
            pk = Long.parseLong(parts[1]);
        } catch (NumberFormatException e) {
            return "error: primary key must be a long integer";
        }

        try {
            Catalog cat = getCatalog();
            Optional<TableMeta> metaOpt = cat.openTable(tableName);
            if (metaOpt.isEmpty()) {
                return "error: unknown table: " + tableName;
            }
            TableMeta meta = metaOpt.get();

            StorageEngine engine = db.getEngineForTable(meta);
            Optional<Row> row = engine.get(pk);
            if (row.isPresent()) {
                return row.get().values().toString();
            } else {
                return "(not found)";
            }
        } catch (IOException e) {
            return "error: " + e.getMessage();
        }
    }

    /**
     * Handles: delete <table> <pk>
     * Deletes a row by primary key via the StorageEngine.
     */
    private String handleDelete(String args) {
        if (args.isBlank()) {
            return "usage: delete <table> <pk>";
        }

        String[] parts = args.trim().split("\\s+");
        if (parts.length != 2) {
            return "usage: delete <table> <pk>";
        }

        String tableName = parts[0];
        long pk;
        try {
            pk = Long.parseLong(parts[1]);
        } catch (NumberFormatException e) {
            return "error: primary key must be a long integer";
        }

        try {
            Catalog cat = getCatalog();
            Optional<TableMeta> metaOpt = cat.openTable(tableName);
            if (metaOpt.isEmpty()) {
                return "error: unknown table: " + tableName;
            }
            TableMeta meta = metaOpt.get();

            StorageEngine engine = db.getEngineForTable(meta);
            engine.delete(pk);
            return "ok";
        } catch (IOException e) {
            return "error: " + e.getMessage();
        }
    }

    /**
     * Handles: scan <table>
     * Full table scan via StorageEngine (returns rows in heap order).
     */
    private String handleScan(String args) {
        if (args.isBlank()) {
            return "usage: scan <table>";
        }

        String tableName = args.trim().split("\\s+")[0];

        try {
            Catalog cat = getCatalog();
            Optional<TableMeta> metaOpt = cat.openTable(tableName);
            if (metaOpt.isEmpty()) {
                return "error: unknown table: " + tableName;
            }
            TableMeta meta = metaOpt.get();

            StorageEngine engine = db.getEngineForTable(meta);
            StringBuilder sb = new StringBuilder();
            int count = 0;

            Iterator<Map.Entry<Long, Row>> it = engine.fullScan();
            while (it.hasNext()) {
                Map.Entry<Long, Row> entry = it.next();
                sb.append(entry.getKey()).append(": ")
                  .append(entry.getValue().values().toString()).append("\n");
                count++;
            }

            if (count == 0) {
                return "(no rows)";
            }
            return String.format("(%d rows)%n", count) + sb.toString().stripTrailing();
        } catch (IOException e) {
            return "error: " + e.getMessage();
        }
    }

    /**
     * Handles: range <table> <from-pk> <to-pk>
     * Range scan via StorageEngine (returns rows in PK order, inclusive).
     */
    private String handleRange(String args) {
        if (args.isBlank()) {
            return "usage: range <table> <from-pk> <to-pk>";
        }

        String[] parts = args.trim().split("\\s+");
        if (parts.length != 3) {
            return "usage: range <table> <from-pk> <to-pk>";
        }

        String tableName = parts[0];
        long fromPk, toPk;
        try {
            fromPk = Long.parseLong(parts[1]);
            toPk = Long.parseLong(parts[2]);
        } catch (NumberFormatException e) {
            return "error: primary key range bounds must be long integers";
        }

        try {
            Catalog cat = getCatalog();
            Optional<TableMeta> metaOpt = cat.openTable(tableName);
            if (metaOpt.isEmpty()) {
                return "error: unknown table: " + tableName;
            }
            TableMeta meta = metaOpt.get();

            StorageEngine engine = db.getEngineForTable(meta);
            StringBuilder sb = new StringBuilder();
            int count = 0;

            Iterator<Map.Entry<Long, Row>> it = engine.rangeScan(fromPk, toPk);
            while (it.hasNext()) {
                Map.Entry<Long, Row> entry = it.next();
                sb.append(entry.getKey()).append(": ")
                  .append(entry.getValue().values().toString()).append("\n");
                count++;
            }

            if (count == 0) {
                return "(no rows in range)";
            }
            return String.format("(%d rows)%n", count) + sb.toString().stripTrailing();
        } catch (IOException e) {
            return "error: " + e.getMessage();
        }
    }

    /**
     * Handles: wal-dump
     * Dumps WAL file contents for diagnostics.
     */
    private String handleWalDump() {
        Path walPath = db.dataPath().resolve("global/pg_wal");
        if (!Files.exists(walPath)) {
            return "(WAL file does not exist)";
        }

        StringBuilder sb = new StringBuilder();
        try (XLogReader reader = XLogReader.open(walPath)) {
            int count = 0;
            Optional<XLogRecord> recordOpt;
            while ((recordOpt = reader.readNext()).isPresent()) {
                XLogRecord rec = recordOpt.get();

                // Special formatting for CHECKPOINT records
                if (rec.resourceManager() == XLogRecord.RMGR_XLOG
                        && (rec.info() & 0x7F) == XLogResourceManager.CHECKPOINT) {
                    long redoLsn = 0;
                    byte[] data = rec.data();
                    if (data.length >= 8) {
                        ByteBuffer bb = ByteBuffer.wrap(data);
                        bb.order(java.nio.ByteOrder.BIG_ENDIAN);
                        redoLsn = bb.getLong();
                    }
                    sb.append(String.format(
                        "LSN=%d xid=%d rmgr=%s info=CHECKPOINT redoLsn=%d%n",
                        rec.lsn(),
                        rec.xid(),
                        rec.resourceManagerName(),
                        redoLsn
                    ));
                } else {
                    String infoStr = String.format("0x%02X", rec.info());
                    String fpwFlag = rec.isFullPageWrite() ? "yes" : "no";

                    sb.append(String.format(
                        "LSN=%d prev=%d xid=%d rmgr=%s info=%s tbl=%d pg=%d fpw=%s len=%d%n",
                        rec.lsn(),
                        rec.prevLsn(),
                        rec.xid(),
                        rec.resourceManagerName(),
                        infoStr,
                        rec.tableOid(),
                        rec.pageId(),
                        fpwFlag,
                        rec.totalLength()
                    ));
                }
                count++;
            }

            if (count == 0) {
                return "(WAL file is empty - only header present)";
            }
            sb.append(String.format("(%d records)%n", count));
            return sb.toString().stripTrailing();
        } catch (Exception e) {
            return "error: " + e.getMessage();
        }
    }

    /**
     * Handles: checkpoint
     * Performs a database checkpoint - flushes dirty pages and writes CHECKPOINT record.
     */
    private String handleRecoveryStatus() {
        if (db.lastRecoveryStats() == null) {
            return "no recovery stats available";
        }
        return db.lastRecoveryStats().format();
    }

    private String handleCheckpoint() {
        try {
            BufferPool.CheckpointResult result = db.bufferPool().checkpoint(db.controlFile());
            return String.format("flushed %d dirty pages  checkpoint-lsn=%d", result.flushedPages(), result.checkpointLsn());
        } catch (Exception e) {
            return "error: " + e.getMessage();
        }
    }

    /**
     * Handles: clog-status [xid]
     * Shows transaction status log summary or specific XID status.
     */
    private String handleVacuum(String args) {
        if (args.isBlank()) {
            return "usage: vacuum <table>";
        }
        String tableName = args.trim();
        try {
            Catalog cat = getCatalog();
            Optional<TableMeta> metaOpt = cat.openTable(tableName);
            if (metaOpt.isEmpty()) {
                return "error: unknown table: " + tableName;
            }
            TableMeta meta = metaOpt.get();
            StorageEngine engine = db.getEngineForTable(meta);
            if (!(engine instanceof BTreeStorageEngine btEngine)) {
                return "error: vacuum only supported for BTreeStorageEngine";
            }
            int oldestXmin = db.snapshotManager().oldestActiveXmin();
            VacuumExecutor vacuum = new VacuumExecutor(
                    btEngine.heap(),
                    List.of(btEngine.pkIndex()),
                    db.xlogWriter(),
                    db.clog());
            VacuumStats stats = vacuum.vacuum(oldestXmin);
            return String.format(
                    "scanned %d pages  dead-tuples=%d  index-entries-removed=%d  reclaimed=%d B",
                    stats.pagesScanned(), stats.deadTuples(),
                    stats.indexEntriesRemoved(), stats.bytesReclaimed());
        } catch (IOException e) {
            return "error: " + e.getMessage();
        }
    }

    private String handleVacuumStats(String args) {
        if (args.isBlank()) {
            return "usage: vacuum-stats <table>";
        }
        String tableName = args.trim();
        try {
            Catalog cat = getCatalog();
            Optional<TableMeta> metaOpt = cat.openTable(tableName);
            if (metaOpt.isEmpty()) {
                return "error: unknown table: " + tableName;
            }
            TableMeta meta = metaOpt.get();
            StorageEngine engine = db.getEngineForTable(meta);
            if (!(engine instanceof BTreeStorageEngine btEngine)) {
                return "error: vacuum-stats only supported for BTreeStorageEngine";
            }
            HeapFile heap = btEngine.heap();
            int pages = heap.pageCount() - 1;
            int oldestXmin = db.snapshotManager().oldestActiveXmin();
            long[] counts = heap.countTuples(oldestXmin, db.clog());
            return String.format("pages=%d  live=%d  dead=%d", pages, counts[0], counts[1]);
        } catch (IOException e) {
            return "error: " + e.getMessage();
        }
    }

    private String handleLockTable() {
        List<LockManager.LockSnapshot> locks = db.lockManager().allLocks();
        if (locks.isEmpty()) {
            return "(no locks held)";
        }

        Catalog cat = db.catalog();
        StringBuilder sb = new StringBuilder();
        for (LockManager.LockSnapshot snap : locks) {
            String resourceName = resolveTableName(cat, snap.tag().tableOid());
            StringBuilder holderPart = new StringBuilder();
            for (Map.Entry<Integer, LockMode> e : snap.holders().entrySet()) {
                if (!holderPart.isEmpty()) holderPart.append(", ");
                holderPart.append(e.getValue()).append(" held by xid=").append(e.getKey());
            }
            StringBuilder waiterPart = new StringBuilder();
            for (LockManager.WaiterInfo w : snap.waiters()) {
                if (!waiterPart.isEmpty()) waiterPart.append(", ");
                waiterPart.append("xid=").append(w.xid()).append(" (").append(w.mode()).append(")");
            }
            sb.append(resourceName)
              .append(" (").append(snap.tag().type()).append(")  ")
              .append(holderPart.isEmpty() ? "(no holders)" : holderPart)
              .append("  waiters: ").append(waiterPart.isEmpty() ? "none" : waiterPart)
              .append("\n");
        }
        return sb.toString().stripTrailing();
    }

    private String resolveTableName(Catalog cat, int oid) {
        try {
            for (TableMeta meta : cat.listTables()) {
                if (meta.oid() == oid) return meta.name();
            }
        } catch (IOException ignored) {}
        return "oid=" + oid;
    }

    private String handleClogStatus(String args) {
        Path pgXactPath = db.dataPath().resolve("global/pg_xact");
        if (!Files.exists(pgXactPath)) {
            return "pg_xact file does not exist (database may not be initialized)";
        }

        try (ClogManager clog = ClogManager.open(db.dataPath())) {
            String trimmed = args.trim();
            if (trimmed.isEmpty()) {
                // Summary mode
                return clog.getStats().toString();
            } else {
                // Specific XID mode
                try {
                    int xid = Integer.parseInt(trimmed);
                    ClogManager.Status status = clog.getStatus(xid);
                    return String.format("xid=%d status=%s", xid, status);
                } catch (NumberFormatException e) {
                    return "usage: clog-status [xid]  (xid must be a number)";
                }
            }
        } catch (Exception e) {
            return "error: " + e.getMessage();
        }
    }

    /**
     * Parses a value string into the appropriate type.
     */
    private Object parseValue(String value, com.coredb.api.ColumnType type) {
        return switch (type) {
            case LONG -> Long.parseLong(value);
            case INT -> Integer.parseInt(value);
            case STRING -> value;
            case BOOL -> Boolean.parseBoolean(value);
        };
    }
}
