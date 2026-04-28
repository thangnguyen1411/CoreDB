package com.coredb.api;

import com.coredb.buffer.BufferPool;
import com.coredb.catalog.BootstrapCatalog;
import com.coredb.catalog.Catalog;
import com.coredb.catalog.ControlFile;
import com.coredb.catalog.TableMeta;
import com.coredb.engine.StorageEngine;
import com.coredb.engine.StorageEngineFactory;
import com.coredb.mvcc.SnapshotManager;
import com.coredb.recovery.RecoveryManager;
import com.coredb.recovery.RecoveryStats;
import com.coredb.txn.ClogManager;
import com.coredb.util.Constants;
import com.coredb.wal.XLogWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CoreDB implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(CoreDB.class);

    private final Path dataPath;
    private final CoreDBConfig config;
    private final ControlFile controlFile;
    private final BufferPool bufferPool;
    private final XLogWriter xlogWriter;
    private final ClogManager clog;
    private final Catalog catalog;
    private final SnapshotManager snapshotManager;
    private final Map<Integer, StorageEngine> engineCache;
    private final RecoveryStats lastRecoveryStats;
    private volatile boolean closed = false;

    private CoreDB(
        Path dataPath,
        CoreDBConfig config,
        ControlFile controlFile,
        BufferPool bufferPool,
        XLogWriter xlogWriter,
        ClogManager clog,
        Catalog catalog,
        SnapshotManager snapshotManager,
        RecoveryStats lastRecoveryStats
    ) {
        this.dataPath = dataPath;
        this.config = config;
        this.controlFile = controlFile;
        this.bufferPool = bufferPool;
        this.xlogWriter = xlogWriter;
        this.clog = clog;
        this.catalog = catalog;
        this.snapshotManager = snapshotManager;
        this.engineCache = new ConcurrentHashMap<>();
        this.lastRecoveryStats = lastRecoveryStats;
        log.debug(
            "CoreDB opened: path={} engine={} pageSize={}",
            dataPath,
            config.engineType(),
            config.pageSize()
        );
    }

    public static CoreDB open(String dataPath) throws IOException {
        return open(Path.of(dataPath));
    }

    public static CoreDB open(Path dataPath) throws IOException {
        return open(dataPath, CoreDBConfig.defaults());
    }

    public static CoreDB open(String dataPath, CoreDBConfig config)
        throws IOException {
        return open(Path.of(dataPath), config);
    }

    public static CoreDB open(Path dataPath, CoreDBConfig config)
        throws IOException {
        // Ensure data directory exists
        Files.createDirectories(dataPath);

        RecoveryStats recoveryStats;
        ControlFile controlFile;

        if (Files.exists(dataPath.resolve("global/pg_control"))) {
            // Existing database: run recovery then load control file
            log.info("Opened existing database: {}", dataPath);
            recoveryStats = RecoveryManager.recover(dataPath);
            controlFile = ControlFile.load(dataPath);
            if (!recoveryStats.isNoRecovery()) {
                log.info("Recovery complete: {} records redone, {} FPW restored, {} skipped",
                         recoveryStats.redone(), recoveryStats.fpwRestored(),
                         recoveryStats.skippedByPdLsn());
            }
        } else {
            // Fresh database: run bootstrap to create system catalogs
            log.info("Initializing new database: {}", dataPath);
            BootstrapCatalog.initialize(dataPath, config);
            controlFile = ControlFile.load(dataPath);
            recoveryStats = RecoveryStats.noRecoveryNeeded("fresh database");
            log.info("Database bootstrap complete");
        }

        // Create BufferPool first (before Catalog, which needs it)
        BufferPool bufferPool = new BufferPool(config.bufferPoolSize());

        // Create XLogWriter for WAL durability (after control file is loaded/created)
        Path walPath = dataPath.resolve("global").resolve("pg_wal");
        XLogWriter xlogWriter = XLogWriter.open(walPath);

        // Wire XLogWriter to BufferPool for WAL-before-data flush rule
        bufferPool.setXLogWriter(xlogWriter);

        // Post-recovery checkpoint for existing databases
        // This updates pg_control.checkpointLsn and resets needsFullPageWrite on frames
        // Can be disabled via system property for testing idempotency
        boolean skipPostRecoveryCheckpoint = Boolean.getBoolean("coredb.skip_post_recovery_checkpoint");
        if (!skipPostRecoveryCheckpoint &&
            Files.exists(dataPath.resolve("global/pg_control")) &&
            !recoveryStats.isNoRecovery()) {
            log.info("Performing post-recovery checkpoint...");
            bufferPool.checkpoint(controlFile);
        }

        // Open shared ClogManager (single instance per database)
        ClogManager clog = ClogManager.open(dataPath);

        // Create Catalog with WAL support (opens core_class and core_attribute heap files via buffer pool)
        Catalog catalog = new Catalog(dataPath, controlFile, bufferPool, xlogWriter, Constants.BOOTSTRAP_XID, clog);

        // SnapshotManager tracks active transactions for MVCC snapshot isolation.
        // Initialized from the persisted nextXid so that all pre-existing committed
        // transactions are behind the initial horizon.
        SnapshotManager snapshotManager = new SnapshotManager(controlFile.nextXid());

        return new CoreDB(dataPath, config, controlFile, bufferPool, xlogWriter, clog, catalog, snapshotManager, recoveryStats);
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
     * Returns the recovery statistics from the last database open.
     *
     * @return RecoveryStats with counts from recovery, or null if no recovery was needed
     */
    public RecoveryStats lastRecoveryStats() {
        return lastRecoveryStats;
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
                StorageEngine engine = StorageEngineFactory.create(
                    config.engineType(),
                    config
                );
                engine.open(dataPath, meta, bufferPool, xlogWriter, clog);
                log.debug(
                    "Opened StorageEngine for table {} (oid={})",
                    meta.name(),
                    oid
                );
                return engine;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    /**
     * Returns the buffer pool for this database instance.
     */
    public BufferPool bufferPool() {
        return bufferPool;
    }

    /**
     * Returns the WAL writer for this database instance.
     */
    public XLogWriter xlogWriter() {
        return xlogWriter;
    }

    /**
     * Returns the commit-log manager for this database instance.
     */
    public ClogManager clog() {
        return clog;
    }

    /**
     * Returns the snapshot manager for this database instance.
     */
    public SnapshotManager snapshotManager() {
        return snapshotManager;
    }

    public boolean isClosed() {
        return closed;
    }

    /**
     * Simulates a crash by flushing WAL but NOT dirty pages.
     * Used for testing crash recovery scenarios.
     *
     * <p>This ensures WAL records are on disk but data pages are not,
     * forcing recovery to replay WAL on next open.</p>
     *
     * @throws IOException if WAL flush fails
     */
    public void simulateCrash() throws IOException {
        if (!closed) {
            // Flush WAL to ensure records survive the "crash"
            xlogWriter.flushUpTo(xlogWriter.currentLsn());

            // Close without flushing dirty pages (simulates crash)
            closed = true;
            engineCache.clear();
            try {
                catalog.close();
            } finally {
                try {
                    // Close buffer pool WITHOUT flushing dirty pages
                    bufferPool.closeWithoutFlush();
                } finally {
                    try {
                        xlogWriter.close();
                    } finally {
                        controlFile.close();
                    }
                }
            }
            log.debug("CoreDB crashed (simulated): path={}", dataPath);
        }
    }

    @Override
    public void close() throws IOException {
        if (!closed) {
            closed = true;
            // Close engines in reverse order of creation (newest first)
            var engines = engineCache.values().stream().toList();
            Collections.reverse(engines);
            for (StorageEngine engine : engines) {
                try {
                    engine.close();
                } catch (IOException e) {
                    log.warn(
                        "Error closing storage engine: {}",
                        e.getMessage()
                    );
                }
            }
            engineCache.clear();
            try {
                catalog.close();
            } finally {
                try {
                    bufferPool.close();
                } finally {
                    try {
                        xlogWriter.close();
                    } finally {
                        try {
                            clog.close();
                        } finally {
                            controlFile.close();
                        }
                    }
                }
            }
            log.debug("CoreDB closed: path={}", dataPath);
        }
    }
}
