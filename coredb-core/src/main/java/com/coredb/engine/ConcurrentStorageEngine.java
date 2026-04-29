package com.coredb.engine;

import com.coredb.api.Row;
import com.coredb.buffer.BufferPool;
import com.coredb.catalog.TableMeta;
import com.coredb.txn.ClogManager;
import com.coredb.txn.LockManager;
import com.coredb.txn.LockMode;
import com.coredb.txn.LockTag;
import com.coredb.txn.Transaction;
import com.coredb.txn.TransactionManager;
import com.coredb.wal.XLogWriter;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;

/**
 * Wraps a {@link StorageEngine} and serialises write operations on the underlying
 * table via {@link LockManager}. Reads bypass the lock manager — MVCC handles
 * read/write isolation; the lock manager exists to prevent write/write conflicts.
 *
 * <p>Locks are acquired in EXCLUSIVE mode on the table tag and held until the
 * active transaction ends. {@link TransactionManager#commit} and
 * {@link TransactionManager#rollback} release them via {@code releaseAll(xid)}.
 */
public final class ConcurrentStorageEngine implements StorageEngine {

    public static final long DEFAULT_LOCK_TIMEOUT_MS = 5_000L;

    private final StorageEngine delegate;
    private final LockManager lockManager;
    private final long lockTimeoutMs;

    private TransactionManager transactionManager;
    private LockTag tableTag;

    public ConcurrentStorageEngine(StorageEngine delegate, LockManager lockManager) {
        this(delegate, lockManager, DEFAULT_LOCK_TIMEOUT_MS);
    }

    public ConcurrentStorageEngine(StorageEngine delegate, LockManager lockManager, long lockTimeoutMs) {
        this.delegate = delegate;
        this.lockManager = lockManager;
        this.lockTimeoutMs = lockTimeoutMs;
    }

    @Override
    public void open(Path dataDir, TableMeta meta, BufferPool bufferPool, XLogWriter xlogWriter,
                     ClogManager clog, TransactionManager transactionManager) throws IOException {
        delegate.open(dataDir, meta, bufferPool, xlogWriter, clog, transactionManager);
        this.transactionManager = transactionManager;
        this.tableTag = new LockTag(meta.oid(), LockTag.LockType.TABLE);
    }

    @Override
    public void close() throws IOException {
        delegate.close();
    }

    @Override
    public void put(long pk, Row row) throws IOException {
        acquireExclusive();
        delegate.put(pk, row);
    }

    @Override
    public void delete(long pk) throws IOException {
        acquireExclusive();
        delegate.delete(pk);
    }

    @Override
    public Optional<Row> get(long pk) throws IOException {
        return delegate.get(pk);
    }

    @Override
    public Iterator<Map.Entry<Long, Row>> rangeScan(long fromPk, long toPk) throws IOException {
        return delegate.rangeScan(fromPk, toPk);
    }

    @Override
    public Iterator<Map.Entry<Long, Row>> fullScan() throws IOException {
        return delegate.fullScan();
    }

    @Override
    public void createIndex(String indexName, String columnName) throws IOException {
        delegate.createIndex(indexName, columnName);
    }

    @Override
    public void dropIndex(String indexName) throws IOException {
        delegate.dropIndex(indexName);
    }

    @Override
    public Iterator<Map.Entry<Long, Row>> indexLookup(String indexName, Object value) throws IOException {
        return delegate.indexLookup(indexName, value);
    }

    @Override
    public Iterator<Map.Entry<Long, Row>> indexRangeScan(String indexName, Object from, Object to) throws IOException {
        return delegate.indexRangeScan(indexName, from, to);
    }

    @Override
    public void flush() throws IOException {
        delegate.flush();
    }

    public StorageEngine delegate() {
        return delegate;
    }

    private void acquireExclusive() {
        Transaction tx = transactionManager.currentTransaction();
        if (tx == null) {
            throw new IllegalStateException("no active transaction");
        }
        lockManager.acquire(tx.xid(), tableTag, LockMode.EXCLUSIVE, lockTimeoutMs);
    }
}
