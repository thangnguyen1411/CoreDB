package com.coredb.shell;

import com.coredb.api.CoreDB;
import com.coredb.util.Constants;

import java.nio.file.Files;

/**
 * ShellBackend that calls CoreDB directly (embedded / in-process mode).
 * New commands are added here as phases progress by extending the switch
 * in execute() — the shell loop never changes.
 */
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

        return switch (command) {
            case "version" -> formatVersion();
            case "status"  -> formatStatus();
            case "help"    -> formatHelp();
            default        -> "unknown command: " + command + "  (type 'help' for available commands)";
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
                  version    print version and config
                  status     show DB file path and whether it exists
                  help       list available commands
                  quit       exit""";
    }
}
