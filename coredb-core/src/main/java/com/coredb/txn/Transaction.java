package com.coredb.txn;

import com.coredb.mvcc.Snapshot;

/**
 * Represents a single database transaction.
 *
 * <p>A transaction holds an immutable XID and snapshot (taken once at
 * {@code beginTransaction()} for REPEATABLE READ semantics) plus a mutable
 * lifecycle state.</p>
 *
 * <p>PostgreSQL equivalent: {@code TransactionStateData} + {@code PGPROC}</p>
 */
public final class Transaction {

    public enum State { ACTIVE, COMMITTED, ABORTED }

    private final int xid;
    private final Snapshot snapshot;
    private State state;

    Transaction(int xid, Snapshot snapshot) {
        this.xid = xid;
        this.snapshot = snapshot;
        this.state = State.ACTIVE;
    }

    public int xid() { return xid; }

    public Snapshot snapshot() { return snapshot; }

    public State state() { return state; }

    void setState(State state) { this.state = state; }

    @Override
    public String toString() {
        return "Transaction{xid=" + xid + ", state=" + state + "}";
    }
}
