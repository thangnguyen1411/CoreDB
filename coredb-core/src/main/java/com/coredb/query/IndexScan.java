package com.coredb.query;

import com.coredb.api.Row;
import com.coredb.engine.StorageEngine;
import com.coredb.txn.Transaction;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;

public final class IndexScan implements Operator {

    private final StorageEngine engine;
    private final String table;
    private final String indexName;
    private final Object key;
    private final Transaction tx;
    private Optional<Row> result;
    private boolean produced;

    public IndexScan(StorageEngine engine, String table, String indexName, Object key, Transaction tx) {
        this.engine = engine;
        this.table = table;
        this.indexName = indexName;
        this.key = key;
        this.tx = tx;
    }

    @Override
    public void open() {
        if (tx.currentStatementSnapshot() == null) {
            throw new IllegalStateException("snapshot not set; statement boundary missing");
        }
        try {
            Iterator<Map.Entry<Long, Row>> it = engine.indexLookup(indexName, key);
            result = it.hasNext() ? Optional.of(it.next().getValue()) : Optional.empty();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        produced = false;
    }

    @Override
    public Optional<Row> next() {
        if (produced) return Optional.empty();
        produced = true;
        return result;
    }

    @Override
    public void close() {}
}
