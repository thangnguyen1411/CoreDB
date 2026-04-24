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
        int pageId = -1;
        Long id = null;
        String name = null;
        Integer age = null;

        for (String part : args.split("\\s+")) {
            try {
                if (part.startsWith("page=")) {
                    pageId = Integer.parseInt(part.substring(5));
                } else if (part.startsWith("id=")) {
                    id = Long.parseLong(part.substring(3));
                } else if (part.startsWith("name=")) {
                    name = part.substring(5);
                } else if (part.startsWith("age=")) {
                    age = Integer.parseInt(part.substring(4));
                }
            } catch (NumberFormatException e) {
                return "usage: insert-raw page=N id=N name=XXX age=N (invalid number: " + part + ")";
            }
        }

        if (pageId < 1 || id == null || name == null || age == null) {
            return "usage: insert-raw page=N id=N name=XXX age=N";
        }

        try {
            DiskManager dm = db.diskManager();

            if (pageId >= dm.pageCount()) {
                return "error: page " + pageId + " does not exist";
            }

            Page page = dm.readPage(pageId);
            if (page.pageType() != PageType.HEAP) {
                return "error: page " + pageId + " is not a heap page";
            }

            Row row = Row.of(id, name, age);
            SerializedRow serialized = RowSerializer.serialize(row, RAW_SCHEMA);
            byte[] dataBytes = serialized.data();
            byte[] nullBitmap = serialized.nullBitmap();
            short natts = (short) RAW_SCHEMA.columnCount();

            int tupleHeaderSize = HeapTupleHeader.computeHeaderSize(natts);
            int tupleSize = tupleHeaderSize + dataBytes.length;

            HeapPage heapPage = new HeapPage(page);
            if (heapPage.freeBytes() < tupleSize + ItemId.SIZE) {
                return "error: page " + pageId + " has insufficient space";
            }

            RecordId rid = heapPage.insert(dataBytes, natts, nullBitmap);
            dm.writePage(page);

            return String.format("rid=%s  (xmin=%d xmax=%d)", rid, Constants.BOOTSTRAP_XID, Constants.INVALID_XID);
        } catch (Exception e) {
            return "error: " + e.getMessage();
        }
    }

    private String handleGetRaw(String args) {
        if (!args.startsWith("rid=")) {
            return "usage: get-raw rid=page:slot";
        }

        try {
            RecordId rid = RecordId.parse(args.substring(4));
            DiskManager dm = db.diskManager();

            if (rid.pageId() < 1 || rid.pageId() >= dm.pageCount()) {
                return "error: page " + rid.pageId() + " does not exist";
            }

            Page page = dm.readPage(rid.pageId());
            if (page.pageType() != PageType.HEAP) {
                return "error: page " + rid.pageId() + " is not a heap page";
            }

            HeapPage heapPage = new HeapPage(page);
            byte[] raw = heapPage.get(rid.slotNo());

            HeapTupleHeader header = HeapTupleHeader.readFrom(
                ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN), 0);

            // Defensive: heapPage.get() already validated FLAGS_NORMAL, but double-check visibility
            if (header.xmin() != Constants.BOOTSTRAP_XID) {
                return "error: tuple not visible (xmin != BOOTSTRAP_XID)";
            }

            int hoff = header.hoff();
            byte[] data = new byte[raw.length - hoff];
            System.arraycopy(raw, hoff, data, 0, data.length);

            Row row = RowSerializer.deserialize(data, RAW_SCHEMA, header);
            return row.values().toString();
        } catch (Exception e) {
            return "error: " + e.getMessage();
        }
    }

    private String handleScanRaw(String args) {
        if (!args.startsWith("page=")) {
            return "usage: scan-raw page=N";
        }

        try {
            int pageId = Integer.parseInt(args.substring(5));
            DiskManager dm = db.diskManager();

            if (pageId < 1 || pageId >= dm.pageCount()) {
                return "error: page " + pageId + " does not exist";
            }

            Page page = dm.readPage(pageId);
            if (page.pageType() != PageType.HEAP) {
                return "error: page " + pageId + " is not a heap page";
            }

            HeapPage heapPage = new HeapPage(page);
            StringBuilder sb = new StringBuilder();

            for (RecordId rid : heapPage.scan()) {
                try {
                    byte[] raw = heapPage.get(rid.slotNo());
                    HeapTupleHeader header = HeapTupleHeader.readFrom(
                        ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN), 0);

                    int hoff = header.hoff();
                    byte[] data = new byte[raw.length - hoff];
                    System.arraycopy(raw, hoff, data, 0, data.length);

                    Row row = RowSerializer.deserialize(data, RAW_SCHEMA, header);
                    sb.append(String.format("rid=%s  %s%n", rid, row.values()));
                } catch (Exception e) {
                    sb.append(String.format("rid=%s  error: %s%n", rid, e.getMessage()));
                }
            }

            return sb.length() > 0 ? sb.toString().stripTrailing() : "(no live tuples)";
        } catch (Exception e) {
            return "error: " + e.getMessage();
        }
    }

    private String handleDeleteRaw(String args) {
        if (!args.startsWith("rid=")) {
            return "usage: delete-raw rid=page:slot";
        }

        try {
            RecordId rid = RecordId.parse(args.substring(4));
            DiskManager dm = db.diskManager();

            if (rid.pageId() < 1 || rid.pageId() >= dm.pageCount()) {
                return "error: page " + rid.pageId() + " does not exist";
            }

            Page page = dm.readPage(rid.pageId());
            if (page.pageType() != PageType.HEAP) {
                return "error: page " + rid.pageId() + " is not a heap page";
            }

            HeapPage heapPage = new HeapPage(page);
            heapPage.delete(rid.slotNo());
            dm.writePage(page);

            return "ok (t_xmax set)";
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
            sb.append(String.format("magic=0x%08X (\"HEAP\")%n", 0x48454150));
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
