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
 * <p>Single-threaded. {@link #beginTransaction()} allocates an XID from
 * the control file (persistent), registers it in the active set, and takes an
 * immutable snapshot for REPEATABLE READ isolation.</p>
 *
 * <p>Commit/rollback update clog immediately for visibility correctness.
 * WAL flush ordering for durability is handled at a higher layer.</p>
 */
public final class TransactionManager {

    private final ControlFile controlFile;
    private final SnapshotManager snapshotManager;
    private final ClogManager clog;
    private final XLogWriter xlogWriter;

    private Transaction currentTx;

    public TransactionManager(ControlFile controlFile, SnapshotManager snapshotManager, ClogManager clog) {
        this(controlFile, snapshotManager, clog, null);
    }

    public TransactionManager(ControlFile controlFile, SnapshotManager snapshotManager,
                               ClogManager clog, XLogWriter xlogWriter) {
        this.controlFile = controlFile;
        this.snapshotManager = snapshotManager;
        this.clog = clog;
        this.xlogWriter = xlogWriter;
    }

    /**
     * Begins a new transaction.
     *
     * <p>Order matters for REPEATABLE READ:
     * <ol>
     *   <li>Allocate XID from control file (durable)</li>
     *   <li>Register XID as active — BEFORE taking the snapshot</li>
     *   <li>Take snapshot, which captures the just-registered XID in activeXids</li>
     * </ol>
     * If 2 and 3 were swapped, another transaction's snapshot taken between 1 and 3
     * would not see this XID as active, violating isolation.</p>
     */
    public Transaction beginTransaction() throws IOException {
        if (currentTx != null && currentTx.state() == Transaction.State.ACTIVE) {
            throw new TxnException("A transaction is already active (nested transactions not supported)");
        }
        int xid = controlFile.allocateXid();
        snapshotManager.registerActiveXid(xid);
        Snapshot snap = snapshotManager.takeSnapshot();
        currentTx = new Transaction(xid, snap);
        return currentTx;
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
        return currentTx;
    }

    /**
     * Clears the active transaction after an unrecoverable I/O failure in commit or rollback.
     *
     * <p>Does NOT update clog. The XID remains IN_PROGRESS in pg_xact; recovery will
     * resolve it to COMMITTED (if XACT_COMMIT reached the WAL) or ABORTED (sweep) on
     * the next startup. Callers must treat the outcome as uncertain.</p>
     */
    public void clearCurrentTransaction() {
        if (currentTx != null) {
            finishTransaction(currentTx, Transaction.State.ABORTED);
        }
    }

    private void validateActive(Transaction tx) {
        if (tx == null) {
            throw new TxnException("null transaction");
        }
        if (tx != currentTx) {
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
        if (currentTx == tx) {
            currentTx = null;
        }
    }
}
