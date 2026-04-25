package com.coredb.api;

import com.coredb.catalog.BootstrapCatalog;
import com.coredb.catalog.ControlFile;
import com.coredb.storage.DiskManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class CoreDB implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(CoreDB.class);

    private final Path dataPath;
    private final CoreDBConfig config;
    private final ControlFile controlFile;
    private final DiskManager diskManager;
    private volatile boolean closed = false;

    private CoreDB(Path dataPath, CoreDBConfig config, ControlFile controlFile, DiskManager diskManager) {
        this.dataPath = dataPath;
        this.config = config;
        this.controlFile = controlFile;
        this.diskManager = diskManager;
        log.debug("CoreDB opened: path={} engine={} pageSize={}", dataPath, config.engineType(), config.pageSize());
    }

    public static CoreDB open(String dataPath) throws IOException {
        return open(Path.of(dataPath));
    }

    public static CoreDB open(Path dataPath) throws IOException {
        return open(dataPath, CoreDBConfig.defaults());
    }

    public static CoreDB open(String dataPath, CoreDBConfig config) throws IOException {
        return open(Path.of(dataPath), config);
    }

    public static CoreDB open(Path dataPath, CoreDBConfig config) throws IOException {
        // Ensure data directory exists
        Files.createDirectories(dataPath);

        ControlFile controlFile;
        if (Files.exists(dataPath.resolve("global/pg_control"))) {
            // Existing database: load control file and validate
            controlFile = ControlFile.load(dataPath);
            log.info("Opened existing database: {}", dataPath);
        } else {
            // Fresh database: run bootstrap to create system catalogs
            log.info("Initializing new database: {}", dataPath);
            BootstrapCatalog.initialize(dataPath, config);
            controlFile = ControlFile.load(dataPath);
            log.info("Database bootstrap complete");
        }
        DiskManager dm = DiskManager.open(dataPath, config);
        return new CoreDB(dataPath, config, controlFile, dm);
    }

    public Path dataPath() {
        return dataPath;
    }

    public CoreDBConfig config() {
        return config;
    }

    public ControlFile controlFile() {
        return controlFile;
    }

    public DiskManager diskManager() {
        return diskManager;
    }

    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() throws IOException {
        if (!closed) {
            closed = true;
            try {
                diskManager.close();
            } finally {
                controlFile.close();
            }
            log.debug("CoreDB closed: path={}", dataPath);
        }
    }
}
