package com.coredb.txn;

import com.coredb.catalog.ControlFile;
import com.coredb.mvcc.Snapshot;
import com.coredb.mvcc.SnapshotManager;
import com.coredb.util.TxnException;

import java.io.IOException;

/**
 * Manages transaction lifecycle: begin, commit, rollback.
 *
 * <p>Single-threaded for now. {@link #beginTransaction()} allocates an XID from
 * the control file (persistent), registers it in the active set, and takes an
 * immutable snapshot for REPEATABLE READ isolation.</p>
 *
 * <p>The commit/rollback path in this phase updates clog immediately
 * (correctness) but does NOT emit WAL records or enforce flush ordering.
 * That durability layer is added in the next phase.</p>
 *
 * <p>PostgreSQL equivalent: {@code StartTransaction} / {@code CommitTransaction}
 * in {@code src/backend/access/transam/xact.c}</p>
 */
public final class TransactionManager {

    private final ControlFile controlFile;
    private final SnapshotManager snapshotManager;
    private final ClogManager clog;

    private Transaction currentTx;

    public TransactionManager(ControlFile controlFile, SnapshotManager snapshotManager, ClogManager clog) {
        this.controlFile = controlFile;
        this.snapshotManager = snapshotManager;
        this.clog = clog;
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
     * Commits the given transaction.
     *
     * <p>Updates clog so future visibility checks treat this XID's tuples as
     * committed. Removes the XID from the active set so subsequent snapshots no
     * longer include it. WAL + flush ordering for durability is added in the
     * next phase.</p>
     */
    public void commit(Transaction tx) {
        validateActive(tx);
        try {
            clog.setCommitted(tx.xid());
        } finally {
            finishTransaction(tx, Transaction.State.COMMITTED);
        }
    }

    /**
     * Rolls back the given transaction.
     *
     * <p>Marks the XID as aborted in clog. Tuples inserted by this transaction
     * become invisible via the MVCC visibility check — no page restoration
     * needed. WAL record for durability is added in the next phase.</p>
     */
    public void rollback(Transaction tx) {
        validateActive(tx);
        try {
            clog.setAborted(tx.xid());
        } finally {
            finishTransaction(tx, Transaction.State.ABORTED);
        }
    }

    /**
     * Returns the currently active transaction, or {@code null} if none.
     */
    public Transaction currentTransaction() {
        return currentTx;
    }

    private void validateActive(Transaction tx) {
        if (tx == null) {
            throw new TxnException("null transaction");
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
