package com.coredb.engine;

import com.coredb.api.CoreDBConfig;
import com.coredb.api.Row;
import com.coredb.api.Schema;
import com.coredb.buffer.BufferPool;
import com.coredb.catalog.TableMeta;
import com.coredb.heap.HeapFile;
import com.coredb.heap.HeapTupleHeader;
import com.coredb.heap.RecordId;
import com.coredb.index.BTree;
import com.coredb.index.IndexFile;
import com.coredb.mvcc.Snapshot;
import com.coredb.txn.ClogManager;
import com.coredb.txn.IsolationLevel;
import com.coredb.txn.PredicateLockManager;
import com.coredb.txn.RWConflictGraph;
import com.coredb.txn.Transaction;
import com.coredb.txn.TransactionManager;
import com.coredb.util.StorageException;
import com.coredb.txn.ClogManager.Status;
import com.coredb.util.Constants;
import com.coredb.util.SerializationFailureException;
import com.coredb.wal.XLogWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.AbstractMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;

/**
 * B+ tree storage engine implementation.
 *
 * <p>Combines a heap file for tuple storage with a B+ tree index on the primary key.
 *
 * <p>File layout per table:
 * <ul>
 *   <li>{@code base/1/<oid>} - heap file with actual row data</li>
 *   <li>{@code base/1/<oid>_pk} - B+ tree index mapping PK to RecordId</li>
 * </ul>
 */
public class BTreeStorageEngine implements StorageEngine {

    private static final Logger log = LoggerFactory.getLogger(BTreeStorageEngine.class);

    // Index files use heap OID + this offset so their pages don't collide in the buffer pool.
    // The offset is persisted in the index file's meta page and recovered on open.
    private static final int INDEX_OID_OFFSET = 0x00100000;

    private final CoreDBConfig config;

    // Engine state (valid after open())
    private BufferPool bufferPool;
    private XLogWriter xlogWriter;
    private ClogManager clog;
    private TransactionManager transactionManager;
    private HeapFile heap;
    private IndexFile indexFile;
    private BTree pkIndex;
    private int pkColumnIndex;
    private int tableOid;
    private int indexFileId;
    private String pkIndexName;

    BTreeStorageEngine(CoreDBConfig config) {
        this.config = config;
    }

    @Override
    public void open(Path dataDir, TableMeta meta, BufferPool bufferPool, XLogWriter xlogWriter,
                     ClogManager clog, TransactionManager transactionManager) throws IOException {
        this.bufferPool = bufferPool;
        this.xlogWriter = xlogWriter;
        this.clog = clog;
        this.transactionManager = transactionManager;
        Schema schema = meta.schema();
        this.pkColumnIndex = schema.indexOf(meta.pkColumn());
        if (pkColumnIndex < 0) {
            throw new IllegalArgumentException("PK column '" + meta.pkColumn() + "' not found in schema");
        }

        if (schema.column(pkColumnIndex).type() != com.coredb.api.ColumnType.LONG) {
            throw new IllegalArgumentException("BTreeStorageEngine only supports LONG primary keys.");
        }

        // Open or create heap file: base/1/<oid>
        Path heapPath = dataDir.resolve("base/1/" + meta.oid());
        if (Files.exists(heapPath)) {
            this.heap = HeapFile.open(heapPath, meta.oid(), schema, bufferPool, xlogWriter, Constants.BOOTSTRAP_XID);
        } else {
            this.heap = HeapFile.create(heapPath, meta.oid(), schema, bufferPool, xlogWriter, Constants.BOOTSTRAP_XID);
        }

        // Open or create index file: base/1/<oid>_pk
        Path indexPath = dataDir.resolve("base/1/" + meta.oid() + "_pk");
        if (Files.exists(indexPath)) {
            // Recover the file ID from the index file's meta page
            this.indexFile = IndexFile.open(indexPath, bufferPool);
            this.pkIndex = BTree.open(indexFile, xlogWriter, Constants.BOOTSTRAP_XID);
        } else {
            // Allocate a new file ID for the index file
            this.indexFile = IndexFile.create(indexPath, meta.oid() + INDEX_OID_OFFSET, bufferPool);
            this.pkIndex = BTree.create(indexFile, xlogWriter, Constants.BOOTSTRAP_XID);
        }

        this.tableOid = meta.oid();
        this.indexFileId = meta.oid() + INDEX_OID_OFFSET;
        this.pkIndexName = meta.name() + "_pk";
        log.debug("Opened BTreeStorageEngine for table {} (oid={})", meta.name(), meta.oid());
    }

    @Override
    public void close() throws IOException {
        if (indexFile != null) {
            indexFile.close();
            indexFile = null;
        }
        if (heap != null) {
            heap.close();
            heap = null;
        }
        // clog and transactionManager are shared; do not close here
        clog = null;
        transactionManager = null;
        pkIndex = null;
    }

    public HeapFile heap() {
        return heap;
    }

    public BTree pkIndex() {
        return pkIndex;
    }

    @Override
    public void put(long pk, Row row) throws IOException {
        Transaction tx = requireActiveTransaction();
        int currentXid = tx.xid();
        boolean serializable = tx.level() == IsolationLevel.SERIALIZABLE;

        Optional<RecordId> existing = pkIndex.search(pk);
        if (existing.isPresent()) {
            RecordId oldRid = existing.get();
            firstUpdaterWins(pk, oldRid, tx);
            // Delete index entry first: if heap.update() fails, old tuple is still
            // live (xmax not set) so the row remains findable via scan.
            pkIndex.delete(pk);
            RecordId newRid;
            try {
                newRid = heap.update(oldRid, row, currentXid);
            } catch (StorageException e) {
                // xmax was set between our firstUpdaterWins check and the actual write —
                // a narrow race under high contention. Surface as serialization failure
                // so the client retries rather than seeing a generic storage error.
                pkIndex.insert(pk, oldRid); // restore index entry before throwing
                throw new SerializationFailureException(
                    "row for pk=" + pk + " modified concurrently; please retry");
            }
            pkIndex.insert(pk, newRid);
            if (serializable) {
                // The old heap page is where t_xmax was set; the new heap page is where
                // the new version was written. Both may have been read by concurrent readers.
                detectWriteConflicts(currentXid, tableOid, oldRid.pageId());
                if (newRid.pageId() != oldRid.pageId()) {
                    detectWriteConflicts(currentXid, tableOid, newRid.pageId());
                }
                // Find the index leaf page that now holds the updated key and check it.
                pkIndex.search(pk, indexPageId ->
                    detectWriteConflicts(currentXid, indexFileId, indexPageId));
            }
            log.debug("Updated row with pk={}: oldRid={} -> newRid={}", pk, oldRid, newRid);
        } else {
            RecordId rid = heap.insert(row, currentXid);
            pkIndex.insert(pk, rid);
            if (serializable) {
                detectWriteConflicts(currentXid, tableOid, rid.pageId());
                pkIndex.search(pk, indexPageId ->
                    detectWriteConflicts(currentXid, indexFileId, indexPageId));
            }
            log.debug("Inserted row with pk={} at rid={}", pk, rid);
        }
    }

    @Override
    public Optional<Row> get(long pk) throws IOException {
        Transaction tx = requireActiveTransaction();
        PredicateLockManager predLockMgr = predicateLockManager();
        boolean serializable = predLockMgr != null && tx.level() == IsolationLevel.SERIALIZABLE;

        Optional<RecordId> ridOpt;
        if (serializable) {
            ridOpt = pkIndex.search(pk,
                indexPageId -> predLockMgr.acquireSiread(tx.xid(), indexFileId, indexPageId));
        } else {
            ridOpt = pkIndex.search(pk);
        }

        if (ridOpt.isEmpty()) {
            return Optional.empty();
        }

        RecordId rid = ridOpt.get();
        if (serializable) {
            predLockMgr.acquireSiread(tx.xid(), tableOid, rid.pageId());
        }
        return heap.get(rid, tx.currentStatementSnapshot(), clog, tx.xid());
    }

    @Override
    public void delete(long pk) throws IOException {
        Transaction tx = requireActiveTransaction();
        int currentXid = tx.xid();
        boolean serializable = tx.level() == IsolationLevel.SERIALIZABLE;

        // Find the index leaf page before deletion so we can check SIREAD readers.
        int[] indexLeafPage = serializable ? new int[]{-1} : null;
        Optional<RecordId> ridOpt = serializable
            ? pkIndex.search(pk, pageId -> indexLeafPage[0] = pageId)
            : pkIndex.search(pk);

        if (ridOpt.isEmpty()) {
            return;
        }

        RecordId rid = ridOpt.get();
        heap.delete(rid, currentXid);
        pkIndex.delete(pk);

        if (serializable) {
            detectWriteConflicts(currentXid, tableOid, rid.pageId());
            if (indexLeafPage[0] >= 0) {
                detectWriteConflicts(currentXid, indexFileId, indexLeafPage[0]);
            }
        }

        log.debug("Deleted row with pk={} at rid={}", pk, rid);
    }

    @Override
    public Iterator<Map.Entry<Long, Row>> rangeScan(long fromPk, long toPk) throws IOException {
        Transaction tx = requireActiveTransaction();
        Snapshot snapshot = tx.currentStatementSnapshot();
        int currentXid = tx.xid();
        PredicateLockManager predLockMgr = predicateLockManager();
        boolean serializable = predLockMgr != null && tx.level() == IsolationLevel.SERIALIZABLE;

        // Track heap pages already locked to avoid redundant acquires for the same page.
        Set<Integer> lockedHeapPages = serializable ? new HashSet<>() : null;

        Iterator<Map.Entry<Long, RecordId>> indexIterator = serializable
            ? pkIndex.rangeScan(fromPk, toPk,
                indexPageId -> predLockMgr.acquireSiread(currentXid, indexFileId, indexPageId))
            : pkIndex.rangeScan(fromPk, toPk);

        return new Iterator<>() {
            private Map.Entry<Long, Row> nextEntry = computeNext();

            private Map.Entry<Long, Row> computeNext() {
                while (indexIterator.hasNext()) {
                    Map.Entry<Long, RecordId> entry = indexIterator.next();
                    RecordId rid = entry.getValue();
                    if (serializable && lockedHeapPages.add(rid.pageId())) {
                        predLockMgr.acquireSiread(currentXid, tableOid, rid.pageId());
                    }
                    try {
                        Optional<Row> rowOpt = heap.get(rid, snapshot, clog, currentXid);
                        if (rowOpt.isPresent()) {
                            return new AbstractMap.SimpleEntry<>(entry.getKey(), rowOpt.get());
                        }
                    } catch (IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                }
                return null;
            }

            @Override
            public boolean hasNext() {
                return nextEntry != null;
            }

            @Override
            public Map.Entry<Long, Row> next() {
                if (nextEntry == null) {
                    throw new NoSuchElementException();
                }
                Map.Entry<Long, Row> result = nextEntry;
                nextEntry = computeNext();
                return result;
            }
        };
    }

    @Override
    public Iterator<Map.Entry<Long, Row>> fullScan() throws IOException {
        Transaction tx = requireActiveTransaction();
        PredicateLockManager predLockMgr = predicateLockManager();
        if (predLockMgr != null && tx.level() == IsolationLevel.SERIALIZABLE) {
            // Acquire SIREAD on all currently allocated heap pages — a full scan touches
            // every page in the table (page 0 is the meta page; data pages start at 1).
            int pageCount = heap.pageCount();
            for (int p = 1; p < pageCount; p++) {
                predLockMgr.acquireSiread(tx.xid(), tableOid, p);
            }
        }
        Iterator<Row> heapIterator = heap.scan(tx.currentStatementSnapshot(), clog, tx.xid());

        return new Iterator<>() {
            private Map.Entry<Long, Row> nextEntry = computeNext();

            private Map.Entry<Long, Row> computeNext() {
                while (heapIterator.hasNext()) {
                    Row row = heapIterator.next();
                    Long pk = extractPk(row);
                    if (pk != null) {
                        return new AbstractMap.SimpleEntry<>(pk, row);
                    }
                }
                return null;
            }

            @Override
            public boolean hasNext() {
                return nextEntry != null;
            }

            @Override
            public Map.Entry<Long, Row> next() {
                if (nextEntry == null) {
                    throw new NoSuchElementException();
                }
                Map.Entry<Long, Row> result = nextEntry;
                nextEntry = computeNext();
                return result;
            }
        };
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
        if (indexName.equals(pkIndexName)) {
            long pk = ((Number) value).longValue();
            Optional<Row> row = get(pk);
            if (row.isEmpty()) {
                return java.util.Collections.emptyIterator();
            }
            return java.util.Collections.singletonList(
                new AbstractMap.SimpleEntry<>(pk, row.get())
            ).iterator();
        }
        throw new UnsupportedOperationException("Secondary indexes not yet implemented");
    }

    @Override
    public Iterator<Map.Entry<Long, Row>> indexRangeScan(String indexName, Object from, Object to) throws IOException {
        throw new UnsupportedOperationException("Secondary indexes not yet implemented");
    }

    @Override
    public void flush() throws IOException {
        if (heap != null) {
            heap.flush();
        }
        if (indexFile != null) {
            indexFile.flush();
        }
    }

    private Transaction requireActiveTransaction() {
        if (transactionManager == null) {
            throw new IllegalStateException("no transaction manager configured");
        }
        Transaction tx = transactionManager.currentTransaction();
        if (tx == null) {
            throw new IllegalStateException("no active transaction");
        }
        return tx;
    }

    private PredicateLockManager predicateLockManager() {
        return transactionManager != null ? transactionManager.predicateLockManager() : null;
    }

    private RWConflictGraph rwConflictGraph() {
        return transactionManager != null ? transactionManager.rwConflictGraph() : null;
    }

    /**
     * SSI write-conflict check: for a page this transaction is about to write, find every
     * other serializable transaction that holds a SIREAD on that page, record a rw-edge
     * (reader → this writer), and abort if a dangerous pivot is detected.
     *
     * <p>Called after each write operation completes so the RecordId / page ID is known.
     * {@link SerializationFailureException} is a RuntimeException and therefore safe to
     * throw from an {@code IntConsumer} callback.</p>
     */
    private void detectWriteConflicts(int writerXid, int pageOid, int pageId) {
        PredicateLockManager predLockMgr = predicateLockManager();
        RWConflictGraph graph = rwConflictGraph();
        if (predLockMgr == null || graph == null) {
            return;
        }
        Set<Integer> readers = predLockMgr.readersOf(pageOid, pageId);
        for (int readerXid : readers) {
            if (readerXid == writerXid) {
                continue;
            }
            graph.addEdge(readerXid, writerXid);
            if (graph.isDangerousPivot(readerXid) || graph.isDangerousPivot(writerXid)) {
                throw new SerializationFailureException(
                    "SSI: dangerous structure detected; xid=" + writerXid + " aborted");
            }
        }
    }

    /**
     * First-updater-wins: detects whether another committed (or in-progress) transaction
     * has already modified the row at {@code oldRid} since {@code tx}'s snapshot was taken.
     *
     * <p>If the indexed version is invisible to the current snapshot and its {@code xmin}
     * is a committed transaction, a concurrent writer won — raise
     * {@link SerializationFailureException} so the caller retries. If {@code xmin} is
     * in-progress, wait for it to commit or abort, then re-evaluate.</p>
     */
    private void firstUpdaterWins(long pk, RecordId oldRid, Transaction tx) throws IOException {
        Optional<Row> visible = heap.get(oldRid, tx.currentStatementSnapshot(), clog, tx.xid());
        if (visible.isPresent()) {
            return; // version is visible to our snapshot — no conflict
        }

        // Version invisible: find out why by reading the raw header.
        HeapTupleHeader h = heap.rawHeader(oldRid);
        if (h == null) {
            throw new SerializationFailureException(
                "row for pk=" + pk + " not found; concurrent modification likely");
        }

        Status xminStatus = clog.getStatus(h.xmin());
        switch (xminStatus) {
            case IN_PROGRESS -> {
                // Another writer is mid-transaction on this row. Wait for it to finish,
                // then re-evaluate: the outcome (commit or abort) determines whether
                // we have a real conflict.
                transactionManager.waitForXid(h.xmin());
                firstUpdaterWins(pk, oldRid, tx);
            }
            case COMMITTED -> throw new SerializationFailureException(
                // A concurrent transaction committed an update after our snapshot was taken.
                // Our update would overwrite data we never read — first-updater-wins.
                "row for pk=" + pk + " updated by concurrent transaction; please retry");
            case ABORTED -> {
                // The writer whose xmin we found aborted; its version is invisible by design.
                // The index entry is stale (points to the aborted writer's new version).
                // Returning here lets put() proceed: pkIndex.delete() + heap.update() will
                // overwrite the stale entry. VACUUM cleans up any remaining dead tuples.
            }
        }
    }

    private Long extractPk(Row row) {
        if (pkColumnIndex >= row.size()) {
            return null;
        }
        Object pkValue = row.get(pkColumnIndex);
        if (pkValue instanceof Long) {
            return (Long) pkValue;
        }
        if (pkValue instanceof Integer) {
            return ((Integer) pkValue).longValue();
        }
        return null;
    }
}
