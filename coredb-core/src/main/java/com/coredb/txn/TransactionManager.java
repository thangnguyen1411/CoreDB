package com.coredb.txn;

import com.coredb.catalog.ControlFile;
import com.coredb.mvcc.Snapshot;
import com.coredb.mvcc.SnapshotManager;
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

    private final ThreadLocal<Transaction> currentTx = new ThreadLocal<>();

    public TransactionManager(ControlFile controlFile, SnapshotManager snapshotManager, ClogManager clog) {
        this(controlFile, snapshotManager, clog, null, null);
    }

    public TransactionManager(ControlFile controlFile, SnapshotManager snapshotManager,
                               ClogManager clog, XLogWriter xlogWriter) {
        this(controlFile, snapshotManager, clog, xlogWriter, null);
    }

    public TransactionManager(ControlFile controlFile, SnapshotManager snapshotManager,
                               ClogManager clog, XLogWriter xlogWriter, LockManager lockManager) {
        this.controlFile = controlFile;
        this.snapshotManager = snapshotManager;
        this.clog = clog;
        this.xlogWriter = xlogWriter;
        this.lockManager = lockManager;
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
        return tx;
    }

    /**
     * Commits a transaction with strict WAL ordering.
     *
     * <p>Order matters for durability and crash recovery:
     * <ol>
     *   <li>Append XACT_COMMIT WAL record</li>
     *   <li>Flush WAL (durability point)</li>
     *   <li>Mark committed in clog</li>
     *   <li>Flush clog</li>
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
        if (lockManager != null) {
            lockManager.releaseAll(tx.xid());
        }
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
        if (lockManager != null) {
            lockManager.releaseAll(tx.xid());
        }
        finishTransaction(tx, Transaction.State.ABORTED);
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
     * Clears the active transaction after an unrecoverable I/O failure in commit or rollback.
     *
     * <p>Does NOT update clog. The XID remains IN_PROGRESS in pg_xact; recovery will
     * resolve it to COMMITTED (if XACT_COMMIT reached the WAL) or ABORTED (sweep) on
     * the next startup. Callers must treat the outcome as uncertain.</p>
     */
    public void clearCurrentTransaction() {
        Transaction tx = currentTx.get();
        if (tx != null) {
            if (lockManager != null) {
                lockManager.releaseAll(tx.xid());
            }
            finishTransaction(tx, Transaction.State.ABORTED);
        }
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
