package com.coredb.shell;

import com.coredb.api.CoreDB;
import com.coredb.page.Page;
import com.coredb.storage.DiskManager;

import java.io.IOException;
import java.nio.file.Files;

public final class LocalShellBackend implements ShellBackend {

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
            case "version"    -> formatVersion();
            case "status"     -> formatStatus();
            case "page-stats" -> formatPageStats();
            case "page-dump"  -> formatPageDump(args);
            case "help"       -> formatHelp();
            default           -> "unknown command: " + command + "  (type 'help' for available commands)";
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
                  help         list available commands
                  quit         exit""";
    }
}
