package com.coredb.api;

import com.coredb.catalog.BootstrapCatalog;
import com.coredb.catalog.Catalog;
import com.coredb.catalog.ControlFile;
import com.coredb.catalog.TableMeta;
import com.coredb.engine.StorageEngine;
import com.coredb.engine.StorageEngineFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class CoreDB implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(CoreDB.class);

    private final Path dataPath;
    private final CoreDBConfig config;
    private final ControlFile controlFile;
    private final Catalog catalog;
    private final Map<Integer, StorageEngine> engineCache;
    private volatile boolean closed = false;

    private CoreDB(Path dataPath, CoreDBConfig config, ControlFile controlFile, Catalog catalog) {
        this.dataPath = dataPath;
        this.config = config;
        this.controlFile = controlFile;
        this.catalog = catalog;
        this.engineCache = new ConcurrentHashMap<>();
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

        // Create Catalog (opens core_class and core_attribute heap files)
        Catalog catalog = new Catalog(dataPath, controlFile);

        return new CoreDB(dataPath, config, controlFile, catalog);
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

    public Catalog catalog() {
        return catalog;
    }

    /**
     * Returns a StorageEngine for the given table, creating and caching it if necessary.
     * Engines are lazily constructed on first use and cached for the lifetime of the CoreDB instance.
     *
     * @param meta table metadata
     * @return the storage engine for this table
     * @throws IOException if the engine cannot be opened
     */
    public StorageEngine getEngineForTable(TableMeta meta) throws IOException {
        if (closed) {
            throw new IllegalStateException("CoreDB is closed");
        }
        return engineCache.computeIfAbsent(meta.oid(), oid -> {
            try {
                StorageEngine engine = StorageEngineFactory.create(config.engineType(), config);
                engine.open(dataPath, meta);
                log.debug("Opened StorageEngine for table {} (oid={})", meta.name(), oid);
                return engine;
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });
    }

    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() throws IOException {
        if (!closed) {
            closed = true;
            // Close engines in reverse order of creation (newest first)
            var engines = engineCache.values().stream().toList();
            java.util.Collections.reverse(engines);
            for (StorageEngine engine : engines) {
                try {
                    engine.close();
                } catch (IOException e) {
                    log.warn("Error closing storage engine: {}", e.getMessage());
                }
            }
            engineCache.clear();
            try {
                catalog.close();
            } finally {
                controlFile.close();
            }
            log.debug("CoreDB closed: path={}", dataPath);
        }
    }
}
