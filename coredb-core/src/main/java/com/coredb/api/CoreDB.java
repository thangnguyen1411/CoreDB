package com.coredb.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Entry point for opening and closing a CoreDB database instance.
 * Methods beyond open/close throw UnsupportedOperationException until
 * the relevant phases are implemented.
 */
public final class CoreDB implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(CoreDB.class);

    private final Path dataPath;
    private final CoreDBConfig config;
    private volatile boolean closed = false;

    private CoreDB(Path dataPath, CoreDBConfig config) {
        this.dataPath = dataPath;
        this.config = config;
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
        return new CoreDB(dataPath, config);
    }

    public Path dataPath() {
        return dataPath;
    }

    public CoreDBConfig config() {
        return config;
    }

    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() throws IOException {
        if (!closed) {
            closed = true;
            log.debug("CoreDB closed: path={}", dataPath);
        }
    }
}
