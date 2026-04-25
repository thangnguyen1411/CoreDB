package com.coredb.engine;

import com.coredb.api.CoreDBConfig;
import com.coredb.api.Row;
import com.coredb.catalog.TableMeta;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;

/**
 * B+ tree storage engine implementation.
 *
 * <p>Combines a heap file for tuple storage with a B+ tree index on the primary key.</p>
 *
 * <p>Current state: stub implementation that throws UnsupportedOperationException
 * for all operations.</p>
 */
public class BTreeStorageEngine implements StorageEngine {

    private final CoreDBConfig config;

    BTreeStorageEngine(CoreDBConfig config) {
        this.config = config;
    }

    @Override
    public void open(Path dataDir, TableMeta meta) throws IOException {
        throw new UnsupportedOperationException("BTreeStorageEngine.open() not yet implemented");
    }

    @Override
    public void close() throws IOException {
        throw new UnsupportedOperationException("BTreeStorageEngine.close() not yet implemented");
    }

    @Override
    public void put(long pk, Row row) throws IOException {
        throw new UnsupportedOperationException("BTreeStorageEngine.put() not yet implemented");
    }

    @Override
    public Optional<Row> get(long pk) throws IOException {
        throw new UnsupportedOperationException("BTreeStorageEngine.get() not yet implemented");
    }

    @Override
    public void delete(long pk) throws IOException {
        throw new UnsupportedOperationException("BTreeStorageEngine.delete() not yet implemented");
    }

    @Override
    public Iterator<Map.Entry<Long, Row>> rangeScan(long fromPk, long toPk) throws IOException {
        throw new UnsupportedOperationException("BTreeStorageEngine.rangeScan() not yet implemented");
    }

    @Override
    public Iterator<Map.Entry<Long, Row>> fullScan() throws IOException {
        throw new UnsupportedOperationException("BTreeStorageEngine.fullScan() not yet implemented");
    }

    @Override
    public void createIndex(String indexName, String columnName) throws IOException {
        throw new UnsupportedOperationException("Secondary indexes not yet implemented");
    }

    @Override
    public void dropIndex(String indexName) throws IOException {
        throw new UnsupportedOperationException("Secondary indexes not yet implemented");
    }

    @Override
    public Iterator<Map.Entry<Long, Row>> indexLookup(String indexName, Object value) throws IOException {
        throw new UnsupportedOperationException("Secondary indexes not yet implemented");
    }

    @Override
    public Iterator<Map.Entry<Long, Row>> indexRangeScan(String indexName, Object from, Object to) throws IOException {
        throw new UnsupportedOperationException("Secondary indexes not yet implemented");
    }

    @Override
    public void flush() throws IOException {
        throw new UnsupportedOperationException("BTreeStorageEngine.flush() not yet implemented");
    }
}
