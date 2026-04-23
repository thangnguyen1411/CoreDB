package com.coredb.shell;

import com.coredb.api.CoreDB;
import com.coredb.config.CoreDBConfigLoader;
import com.coredb.config.CoreDBConfigLoader.LoadedConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

/**
 * Interactive REPL shell for CoreDB.
 *
 * The loop reads stdin line-by-line and routes to dispatch(). New commands
 * are added by extending LocalShellBackend (or the relevant ShellBackend
 * implementation) — nothing in this class changes as phases progress.
 *
 * We will swap LocalShellBackend for RemoteShellBackend to turn
 * this into a network client without touching the loop.
 */
public final class CoreDBShell {

    private static final Logger log = LoggerFactory.getLogger(CoreDBShell.class);
    private static final String PROMPT = "coredb> ";

    private final ShellBackend backend;

    public CoreDBShell(ShellBackend backend) {
        this.backend = backend;
    }

    public void run() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
            printPrompt();
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    printPrompt();
                    continue;
                }
                if (isQuit(line)) {
                    System.out.println("bye");
                    break;
                }
                String result = dispatch(line);
                if (!result.isEmpty()) {
                    System.out.println(result);
                }
                printPrompt();
            }
        } catch (IOException e) {
            log.error("Shell I/O error", e);
            System.err.println("error: " + e.getMessage());
        }
    }

    private String dispatch(String line) {
        return backend.execute(line);
    }

    private static boolean isQuit(String line) {
        return line.equalsIgnoreCase("quit") || line.equalsIgnoreCase("exit");
    }

    private static void printPrompt() {
        System.out.print(PROMPT);
        System.out.flush();
    }

    // --- Entry point ---

    /**
     * Usage: coredb [config-file]
     *   config-file — path to a .properties file (default: coredb.properties)
     *
     * If the file does not exist, built-in defaults are used.
     */
    public static void main(String[] args) throws Exception {
        String configFile = args.length > 0 ? args[0] : CoreDBConfigLoader.DEFAULT_FILE;
        LoadedConfig loaded = CoreDBConfigLoader.load(configFile);

        try (CoreDB db = CoreDB.open(loaded.dataPath(), loaded.config())) {
            ShellBackend backend = new LocalShellBackend(db);
            new CoreDBShell(backend).run();
        }
    }
}
