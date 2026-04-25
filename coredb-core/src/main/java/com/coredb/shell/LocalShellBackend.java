package com.coredb.shell;

import com.coredb.api.Column;
import com.coredb.api.CoreDB;
import com.coredb.api.Row;
import com.coredb.api.Schema;
import com.coredb.catalog.ColumnDefParser;
import com.coredb.catalog.ControlFile;
import com.coredb.heap.HeapFile;
import com.coredb.heap.HeapPage;
import com.coredb.heap.HeapTupleHeader;
import com.coredb.heap.RecordId;
import com.coredb.heap.RowSerializer;
import com.coredb.heap.SerializedRow;
import com.coredb.page.ItemId;
import com.coredb.page.Page;
import com.coredb.page.PageType;
import com.coredb.storage.DiskManager;
import com.coredb.util.Constants;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Optional;

public final class LocalShellBackend implements ShellBackend {

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
            case "page-stats"     -> formatPageStats();
            case "page-dump"      -> formatPageDump(args);
            case "allocate-page"  -> handleAllocatePage();
            case "insert-raw"     -> handleInsertRaw(args);
            case "get-raw"        -> handleGetRaw(args);
            case "scan-raw"       -> handleScanRaw(args);
            case "delete-raw"     -> handleDeleteRaw(args);
            case "heap-stats"     -> handleHeapStats(args);
            case "schema-parse"      -> handleSchemaParse(args);
            case "control-info"      -> handleControlInfo();
            case "control-alloc-oid" -> handleControlAllocOid();
            case "heap-create"       -> handleHeapCreate(args);
            case "heap-meta"         -> handleHeapMeta(args);
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

    private String formatPageStats() {
        DiskManager dm = db.diskManager();
        try {
            return String.format("file=%s  pages=%d  size=%d bytes",
                    dm.path().toAbsolutePath(), dm.pageCount(), dm.fileSize());
        } catch (IOException e) {
            return "error: " + e.getMessage();
        }
    }

    private String formatPageDump(String args) {
        if (args.isBlank()) {
            return "usage: page-dump <pageId>";
        }
        int pageId;
        try {
            pageId = Integer.parseInt(args.trim());
        } catch (NumberFormatException e) {
            return "invalid page id: " + args.trim();
        }

        try {
            Page page = db.diskManager().readPage(pageId);
            return renderPageDump(page);
        } catch (IOException e) {
            return "error: " + e.getMessage();
        }
    }

    private static String renderPageDump(Page page) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("page=%-4d type=%-14s pd_lower=%-5d pd_upper=%-5d pd_special=%d%n",
                page.pageId(), page.pageType(),
                Short.toUnsignedInt(page.pdLower()),
                Short.toUnsignedInt(page.pdUpper()),
                Short.toUnsignedInt(page.pdSpecial())));

        byte[] bytes = page.buffer().array();
        int displayBytes = Math.min(bytes.length, 128);

        for (int row = 0; row < displayBytes; row += 16) {
            sb.append(String.format("%04x: ", row));
            for (int col = 0; col < 16; col++) {
                int idx = row + col;
                if (idx < displayBytes) {
                    sb.append(String.format("%02x ", bytes[idx]));
                } else {
                    sb.append("   ");
                }
                if (col == 7) sb.append(' ');
            }
            sb.append(' ');
            for (int col = 0; col < 16 && (row + col) < displayBytes; col++) {
                char c = (char) (bytes[row + col] & 0xFF);
                sb.append(c >= 32 && c < 127 ? c : '.');
            }
            sb.append('\n');
        }

        if (bytes.length > 128) {
            sb.append("  ...");
        }
        return sb.toString().stripTrailing();
    }

    private String formatHelp() {
        return """
            version      print version and config
            status       show DB file path and whether it exists
            page-stats   show page count and file size
            page-dump N  hex dump of page N
            allocate-page   create a new heap page for raw insert
            insert-raw page=N id=N name=XXX age=N  insert a row on specific page
            get-raw rid=page:slot                   get a row by RecordId
            scan-raw page=N                         scan all rows on a page
            delete-raw rid=page:slot                delete a row by RecordId
            schema-parse <def>                      parse column definition (id:long name:string pk:id)
            control-info                            show pg_control contents
            control-alloc-oid                       allocate next OID and persist
            heap-create oid=N cols...               create per-table heap file (oid, columns)
            heap-meta oid=N                         show meta page of per-table heap file
            heap-stats oid=N                        show file stats for per-table heap file
            insert-raw oid=N id=N name=XXX ...      insert a row into per-table file
            get-raw oid=N rid=page:slot             get a row by RecordId from per-table file
            scan-raw oid=N                          scan all rows in per-table file
            delete-raw oid=N rid=page:slot          delete a row by RecordId from per-table file
            help         list available commands
            quit         exit
            """;
    }

    private String handleAllocatePage() {
        try {
            DiskManager dm = db.diskManager();
            Page newPage = dm.allocatePage(PageType.HEAP);
            return "allocated page " + newPage.pageId() + " (type=HEAP)";
        } catch (Exception e) {
            return "error: " + e.getMessage();
        }
    }

    private String handleInsertRaw(String args) {
        int oid = -1;
        Long id = null;
        String name = null;
        Integer age = null;

        for (String part : args.split("\\s+")) {
            try {
                if (part.startsWith("oid=")) {
                    oid = Integer.parseInt(part.substring(4));
                } else if (part.startsWith("id=")) {
                    id = Long.parseLong(part.substring(3));
                } else if (part.startsWith("name=")) {
                    name = part.substring(5);
                } else if (part.startsWith("age=")) {
                    age = Integer.parseInt(part.substring(4));
                }
            } catch (NumberFormatException e) {
                return "usage: insert-raw oid=N id=N name=XXX age=N (invalid number: " + part + ")";
            }
        }

        if (oid < 0 || id == null || name == null || age == null) {
            return "usage: insert-raw oid=N id=N name=XXX age=N";
        }

        // Build path: dataDir/base/1/<oid>
        Path tablePath = db.dataPath().resolve("base").resolve("1").resolve(String.valueOf(oid));

        if (!Files.exists(tablePath)) {
            return "error: heap file not found: " + db.dataPath().relativize(tablePath);
        }

        try (HeapFile hf = HeapFile.open(tablePath, oid, RAW_SCHEMA)) {
            Row row = Row.of(id, name, age);
            RecordId rid = hf.insert(row);
            return String.format("rid=%s  (xmin=%d xmax=%d)", rid, Constants.BOOTSTRAP_XID, Constants.INVALID_XID);
        } catch (Exception e) {
            return "error: " + e.getMessage();
        }
    }

    private String handleGetRaw(String args) {
        int oid = -1;
        RecordId rid = null;

        for (String part : args.split("\\s+")) {
            if (part.startsWith("oid=")) {
                try {
                    oid = Integer.parseInt(part.substring(4));
                } catch (NumberFormatException e) {
                    return "invalid OID: " + part.substring(4);
                }
            } else if (part.startsWith("rid=")) {
                try {
                    rid = RecordId.parse(part.substring(4));
                } catch (IllegalArgumentException e) {
                    return "invalid rid format: " + part.substring(4);
                }
            }
        }

        if (oid < 0 || rid == null) {
            return "usage: get-raw oid=N rid=page:slot";
        }

        // Build path: dataDir/base/1/<oid>
        Path tablePath = db.dataPath().resolve("base").resolve("1").resolve(String.valueOf(oid));

        if (!Files.exists(tablePath)) {
            return "error: heap file not found: " + db.dataPath().relativize(tablePath);
        }

        try (HeapFile hf = HeapFile.open(tablePath, oid, RAW_SCHEMA)) {
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
        if (!args.startsWith("oid=")) {
            return "usage: scan-raw oid=N";
        }

        int oid;
        try {
            oid = Integer.parseInt(args.substring(4));
        } catch (NumberFormatException e) {
            return "invalid OID: " + args.substring(4);
        }

        // Build path: dataDir/base/1/<oid>
        Path tablePath = db.dataPath().resolve("base").resolve("1").resolve(String.valueOf(oid));

        if (!Files.exists(tablePath)) {
            return "error: heap file not found: " + db.dataPath().relativize(tablePath);
        }

        try (HeapFile hf = HeapFile.open(tablePath, oid, RAW_SCHEMA)) {
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
        int oid = -1;
        RecordId rid = null;

        for (String part : args.split("\\s+")) {
            if (part.startsWith("oid=")) {
                try {
                    oid = Integer.parseInt(part.substring(4));
                } catch (NumberFormatException e) {
                    return "invalid OID: " + part.substring(4);
                }
            } else if (part.startsWith("rid=")) {
                try {
                    rid = RecordId.parse(part.substring(4));
                } catch (IllegalArgumentException e) {
                    return "invalid rid format: " + part.substring(4);
                }
            }
        }

        if (oid < 0 || rid == null) {
            return "usage: delete-raw oid=N rid=page:slot";
        }

        // Build path: dataDir/base/1/<oid>
        Path tablePath = db.dataPath().resolve("base").resolve("1").resolve(String.valueOf(oid));

        if (!Files.exists(tablePath)) {
            return "error: heap file not found: " + db.dataPath().relativize(tablePath);
        }

        try (HeapFile hf = HeapFile.open(tablePath, oid, RAW_SCHEMA)) {
            hf.delete(rid);
            return "ok (t_xmax set)";
        } catch (Exception e) {
            return "error: " + e.getMessage();
        }
    }

    private String handleHeapStats(String args) {
        if (!args.startsWith("oid=")) {
            return "usage: heap-stats oid=N";
        }

        int oid;
        try {
            oid = Integer.parseInt(args.substring(4));
        } catch (NumberFormatException e) {
            return "invalid OID: " + args.substring(4);
        }

        // Build path: dataDir/base/1/<oid>
        Path tablePath = db.dataPath().resolve("base").resolve("1").resolve(String.valueOf(oid));

        if (!Files.exists(tablePath)) {
            return "error: heap file not found: " + db.dataPath().relativize(tablePath);
        }

        try (HeapFile hf = HeapFile.open(tablePath, oid, RAW_SCHEMA)) {
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

    private String handleHeapCreate(String args) {
        // Parse: oid=1000 id:long name:string
        if (args.isBlank() || !args.contains("oid=")) {
            return "usage: heap-create oid=N col:type ... (e.g., heap-create oid=1000 id:long name:string)";
        }

        int oid = -1;
        String colDefs = "";

        String[] parts = args.split("\\s+");
        for (String part : parts) {
            if (part.startsWith("oid=")) {
                try {
                    oid = Integer.parseInt(part.substring(4));
                } catch (NumberFormatException e) {
                    return "invalid OID: " + part.substring(4);
                }
            } else if (!part.isBlank() && colDefs.isEmpty()) {
                // First non-oid arg starts the column definitions
                colDefs = part;
            } else if (!part.isBlank()) {
                colDefs += " " + part;
            }
        }

        if (oid < 0) {
            return "usage: heap-create oid=N col:type ...";
        }
        if (colDefs.isBlank()) {
            return "usage: heap-create oid=N col:type ... (at least one column required)";
        }

        try {
            // Parse column definitions - synthesize PK from first column if user didn't provide one.
            // This is temporary scaffolding; heap-create will removed later.
            String colDefsToParse = colDefs;
            if (!colDefs.contains("pk:")) {
                String firstColName = colDefs.split("\\s+")[0].split(":")[0];
                colDefsToParse = colDefs + " pk:" + firstColName;
            }
            ColumnDefParser.ParsedSchema parsed = ColumnDefParser.parse(colDefsToParse);
            Schema schema = parsed.schema();

            // Build path: dataDir/base/1/<oid>
            Path tablePath = db.dataPath().resolve("base").resolve("1").resolve(String.valueOf(oid));

            HeapFile hf = HeapFile.create(tablePath, oid, schema);
            hf.close();

            return String.format("created %s (meta page written, nextPageId=1)",
                db.dataPath().relativize(tablePath));
        } catch (IllegalArgumentException e) {
            return "error: " + e.getMessage();
        } catch (IOException e) {
            return "error: " + e.getMessage();
        }
    }

    private String handleHeapMeta(String args) {
        if (!args.startsWith("oid=")) {
            return "usage: heap-meta oid=N";
        }

        int oid;
        try {
            oid = Integer.parseInt(args.substring(4));
        } catch (NumberFormatException e) {
            return "invalid OID: " + args.substring(4);
        }

        // Build path: dataDir/base/1/<oid>
        Path tablePath = db.dataPath().resolve("base").resolve("1").resolve(String.valueOf(oid));

        if (!Files.exists(tablePath)) {
            return "error: heap file not found: " + db.dataPath().relativize(tablePath);
        }

        try {
            // We need a schema to open, but for meta page inspection we just need to validate
            // Use a dummy schema - the meta page doesn't depend on it
            Schema dummySchema = Schema.of(Column.longCol("dummy"));
            HeapFile hf = HeapFile.open(tablePath, oid, dummySchema);

            String path = db.dataPath().relativize(hf.tablePath()).toString();
            StringBuilder sb = new StringBuilder();
            sb.append("path=").append(path).append("\n");
            sb.append(String.format("magic=0x%08X (\"HEAP\")%n", Constants.HEAP_FILE_MAGIC));
            sb.append("formatVersion=1\n");
            sb.append("oid=").append(oid).append("\n");
            sb.append("nextPageId=").append(hf.nextPageId());

            hf.close();
            return sb.toString();
        } catch (IOException e) {
            return "error: " + e.getMessage();
        }
    }
}
