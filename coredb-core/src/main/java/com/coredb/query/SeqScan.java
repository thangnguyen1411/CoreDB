package com.coredb.query;

import com.coredb.api.Row;
import com.coredb.engine.StorageEngine;
import com.coredb.txn.Transaction;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;

public final class SeqScan implements Operator {

    private final StorageEngine engine;
    private final Transaction tx;
    private Iterator<Map.Entry<Long, Row>> rows;

    public SeqScan(StorageEngine engine, Transaction tx) {
        this.engine = engine;
        this.tx = tx;
    }

    @Override
    public void open() {
        if (tx.currentStatementSnapshot() == null) {
            throw new IllegalStateException("snapshot not set; statement boundary missing");
        }
        try {
            rows = engine.fullScan();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Optional<Row> next() {
        return rows.hasNext() ? Optional.of(rows.next().getValue()) : Optional.empty();
    }

    @Override
    public void close() {}
}
