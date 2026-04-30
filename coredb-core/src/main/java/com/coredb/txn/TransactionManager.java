package com.coredb.txn;

import com.coredb.catalog.ControlFile;
import com.coredb.mvcc.Snapshot;
import com.coredb.mvcc.SnapshotManager;
import com.coredb.util.SerializationFailureException;
import com.coredb.util.TxnException;
import com.coredb.wal.XLogRecord;
import com.coredb.wal.XLogResourceManager;
import com.coredb.wal.XLogWriter;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Manages transaction lifecycle: begin, commit, rollback.
 *
 * <p>{@link #beginTransaction()} allocates an XID from the control file
 * (persistent), registers it in the active set, and takes an immutable
 * snapshot for REPEATABLE READ isolation.</p>
 *
 * <p>Commit/rollback update clog immediately for visibility correctness.
 * WAL flush ordering for durability is handled at a higher layer.</p>
 *
 * <p>The active transaction is tracked per thread. Multiple threads may run
 * separate transactions concurrently against the same {@code TransactionManager}
 * instance; nested transactions on a single thread remain disallowed.</p>
 */
public final class TransactionManager {

    private final ControlFile controlFile;
    private final SnapshotManager snapshotManager;
    private final ClogManager clog;
    private final XLogWriter xlogWriter;
    private final LockManager lockManager;
    private final PredicateLockManager predicateLockManager;
    private final RWConflictGraph rwConflictGraph;

    private final ThreadLocal<Transaction> currentTx = new ThreadLocal<>();

    public TransactionManager(ControlFile controlFile, SnapshotManager snapshotManager, ClogManager clog) {
        this(controlFile, snapshotManager, clog, null, null, null, null);
    }

    public TransactionManager(ControlFile controlFile, SnapshotManager snapshotManager,
                               ClogManager clog, XLogWriter xlogWriter) {
        this(controlFile, snapshotManager, clog, xlogWriter, null, null, null);
    }

    public TransactionManager(ControlFile controlFile, SnapshotManager snapshotManager,
                               ClogManager clog, XLogWriter xlogWriter, LockManager lockManager) {
        this(controlFile, snapshotManager, clog, xlogWriter, lockManager, null, null);
    }

    public TransactionManager(ControlFile controlFile, SnapshotManager snapshotManager,
                               ClogManager clog, XLogWriter xlogWriter, LockManager lockManager,
                               PredicateLockManager predicateLockManager) {
        this(controlFile, snapshotManager, clog, xlogWriter, lockManager, predicateLockManager, null);
    }

    public TransactionManager(ControlFile controlFile, SnapshotManager snapshotManager,
                               ClogManager clog, XLogWriter xlogWriter, LockManager lockManager,
                               PredicateLockManager predicateLockManager,
                               RWConflictGraph rwConflictGraph) {
        this.controlFile = controlFile;
        this.snapshotManager = snapshotManager;
        this.clog = clog;
        this.xlogWriter = xlogWriter;
        this.lockManager = lockManager;
        this.predicateLockManager = predicateLockManager;
        this.rwConflictGraph = rwConflictGraph;
    }

    /**
     * Begins a new transaction at the default isolation level (REPEATABLE_READ).
     */
    public Transaction beginTransaction() throws IOException {
        return beginTransaction(IsolationLevel.REPEATABLE_READ);
    }

    /**
     * Begins a new transaction at the requested isolation level.
     *
     * <p>Order matters for snapshot isolation:
     * <ol>
     *   <li>Allocate XID from control file (durable)</li>
     *   <li>Register XID as active — BEFORE taking the snapshot</li>
     *   <li>Take snapshot, which captures the just-registered XID in activeXids</li>
     * </ol>
     * If 2 and 3 were swapped, another transaction's snapshot taken between 1 and 3
     * would not see this XID as active, violating isolation.</p>
     *
     * <p>READ_UNCOMMITTED is aliased to READ_COMMITTED — MVCC cannot expose uncommitted
     * versions, so the two levels are equivalent in this implementation.</p>
     */
    public Transaction beginTransaction(IsolationLevel level) throws IOException {
        Transaction active = currentTx.get();
        if (active != null && active.state() == Transaction.State.ACTIVE) {
            throw new TxnException("A transaction is already active (nested transactions not supported)");
        }
        IsolationLevel canonical = level.canonical();
        int xid = controlFile.allocateXid();
        snapshotManager.registerActiveXid(xid);
        Snapshot snap = snapshotManager.takeSnapshot();
        Transaction tx = new Transaction(xid, snap, canonical);
        currentTx.set(tx);
        // Hold an EXCLUSIVE TXN_COMMIT lock for the lifetime of this transaction.
        // Other transactions waiting for this one call waitForXid(), which acquires
        // a SHARE lock on the same tag — blocking until we releaseAll() at commit/rollback.
        if (lockManager != null) {
            lockManager.acquire(xid, new LockTag(xid, LockTag.LockType.TXN_COMMIT),
                                LockMode.EXCLUSIVE, 30_000L);
        }
        return tx;
    }

    /**
     * Commits a transaction with strict WAL ordering.
     *
     * <p>For SERIALIZABLE transactions, a commit-time pivot check is performed after
     * marking the transaction committed in the conflict graph but before releasing
     * predicate locks. If a dangerous structure is detected, the commit fails with
     * {@link SerializationFailureException} and the transaction is rolled back.</p>
     *
     * <p>Order matters for durability and crash recovery:
     * <ol>
     *   <li>Append XACT_COMMIT WAL record</li>
     *   <li>Flush WAL (durability point)</li>
     *   <li>Mark committed in clog</li>
     *   <li>Flush clog</li>
     *   <li>SSI: mark committed in conflict graph + recheck pivot</li>
     *   <li>Release all locks (mutex + predicate) and graph state</li>
     *   <li>Remove from active set</li>
     *   <li>Set transaction state to COMMITTED</li>
     * </ol>
     */
    public void commit(Transaction tx) throws IOException {
        validateActive(tx);

        if (xlogWriter != null) {
            byte[] payload = encodeCommitPayload(tx.xid(), System.currentTimeMillis());
            long commitLsn = xlogWriter.append(
                XLogRecord.RMGR_XLOG,
                XLogResourceManager.XACT_COMMIT,
                tx.xid(),
                0,
                0,
                payload
            );
            xlogWriter.flushUpTo(commitLsn);
        }

        clog.setCommitted(tx.xid());
        clog.flush();

        // SSI commit-time pivot recheck: marking the transaction committed may complete a
        // dangerous structure that was not detectable at write time (the "at least one
        // committed" condition now holds for edges involving this xid).
        if (rwConflictGraph != null && tx.level() == IsolationLevel.SERIALIZABLE) {
            rwConflictGraph.markCommitted(tx.xid());
            if (rwConflictGraph.isDangerousPivot(tx.xid())) {
                // Abort rather than commit: roll back visible state and clean up.
                clog.setAborted(tx.xid());
                clog.flush();
                releaseAllResources(tx);
                finishTransaction(tx, Transaction.State.ABORTED);
                throw new SerializationFailureException(
                    "SSI: dangerous structure detected at commit; xid=" + tx.xid() + " aborted");
            }
        }

        releaseAllResources(tx);
        finishTransaction(tx, Transaction.State.COMMITTED);
    }

    /**
     * Rolls back a transaction with WAL ordering.
     *
     * <p>Order:
     * <ol>
     *   <li>Append XACT_ABORT WAL record</li>
     *   <li>Flush WAL (optional but recommended)</li>
     *   <li>Mark aborted in clog</li>
     *   <li>Flush clog</li>
     *   <li>Release all locks and graph state</li>
     *   <li>Remove from active set</li>
     *   <li>Set transaction state to ABORTED</li>
     * </ol>
     */
    public void rollback(Transaction tx) throws IOException {
        validateActive(tx);

        if (xlogWriter != null) {
            byte[] payload = encodeAbortPayload(tx.xid());
            long abortLsn = xlogWriter.append(
                XLogRecord.RMGR_XLOG,
                XLogResourceManager.XACT_ABORT,
                tx.xid(),
                0,
                0,
                payload
            );
            xlogWriter.flushUpTo(abortLsn);
        }

        clog.setAborted(tx.xid());
        clog.flush();
        releaseAllResources(tx);
        finishTransaction(tx, Transaction.State.ABORTED);
    }

    private void releaseAllResources(Transaction tx) {
        if (lockManager != null) {
            lockManager.releaseAll(tx.xid());
        }
        if (predicateLockManager != null) {
            predicateLockManager.releaseAll(tx.xid());
        }
        if (rwConflictGraph != null) {
            rwConflictGraph.releaseAll(tx.xid());
        }
    }

    private byte[] encodeCommitPayload(int xid, long timestamp) {
        ByteBuffer buf = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN);
        buf.putInt(xid);
        buf.putLong(timestamp);
        return buf.array();
    }

    private byte[] encodeAbortPayload(int xid) {
        ByteBuffer buf = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN);
        buf.putInt(xid);
        return buf.array();
    }

    public Transaction currentTransaction() {
        return currentTx.get();
    }

    /**
     * Refreshes the statement-level snapshot for READ COMMITTED transactions.
     *
     * <p>Called by the shell layer at the start of each statement. For READ COMMITTED,
     * this takes a fresh snapshot so the statement sees all transactions that committed
     * since the current transaction began. For other isolation levels this is a no-op —
     * those levels use the transaction-level snapshot taken at {@code beginTransaction()}.
     */
    public void refreshStatementSnapshot(Transaction tx) {
        if (tx.level() == IsolationLevel.READ_COMMITTED) {
            tx.setCurrentStatementSnapshot(snapshotManager.takeSnapshot());
        }
    }

    /**
     * Blocks until the target transaction commits or rolls back.
     *
     * <p>Acquires a SHARE lock on the target's TXN_COMMIT slot, which is held exclusively
     * by the target from its {@code beginTransaction()} until its {@code commit()} or
     * {@code rollback()}. Returns immediately if the target has already finished.</p>
     *
     * <p>Used by first-updater-wins to stall the current writer until an in-progress
     * concurrent writer on the same row has resolved.</p>
     */
    public void waitForXid(int targetXid) {
        if (lockManager == null) return;
        Transaction tx = currentTx.get();
        if (tx == null) return;
        LockTag tag = new LockTag(targetXid, LockTag.LockType.TXN_COMMIT);
        lockManager.acquire(tx.xid(), tag, LockMode.SHARE, 30_000L);
        lockManager.release(tx.xid(), tag);
    }

    /**
     * Clears the active transaction after an unrecoverable I/O failure in commit or rollback.
     *
     * <p>Does NOT update clog. The XID remains IN_PROGRESS in pg_xact; recovery will
     * resolve it to COMMITTED (if XACT_COMMIT reached the WAL) or ABORTED (sweep) on
     * the next startup. Callers must treat the outcome as uncertain.</p>
     */
    public void clearCurrentTransaction() {
        Transaction tx = currentTx.get();
        if (tx != null) {
            releaseAllResources(tx);
            finishTransaction(tx, Transaction.State.ABORTED);
        }
    }

    /**
     * Returns the predicate lock manager used to track SIREAD locks for SSI, or null
     * if this instance was constructed without one.
     */
    public PredicateLockManager predicateLockManager() {
        return predicateLockManager;
    }

    /**
     * Returns the rw-conflict graph used for SSI pivot detection, or null if this
     * instance was constructed without one.
     */
    public RWConflictGraph rwConflictGraph() {
        return rwConflictGraph;
    }

    private void validateActive(Transaction tx) {
        if (tx == null) {
            throw new TxnException("null transaction");
        }
        if (tx != currentTx.get()) {
            throw new TxnException(
                "Transaction xid=" + tx.xid() + " does not belong to this TransactionManager");
        }
        if (tx.state() != Transaction.State.ACTIVE) {
            throw new TxnException(
                "Transaction xid=" + tx.xid() + " is not active (state=" + tx.state() + ")");
        }
    }

    private void finishTransaction(Transaction tx, Transaction.State newState) {
        snapshotManager.unregisterActiveXid(tx.xid());
        tx.setState(newState);
        if (currentTx.get() == tx) {
            currentTx.remove();
        }
    }
}
