package com.coredb.txn;

import com.coredb.mvcc.Snapshot;

public final class Transaction {

    public enum State { ACTIVE, COMMITTED, ABORTED }

    private final int xid;
    private final Snapshot snapshot;
    private final IsolationLevel level;
    private State state;

    /**
     * Statement-level snapshot. Set at statement start; reused for the duration of one
     * shell command. At REPEATABLE_READ and SERIALIZABLE this is identical to {@link #snapshot}.
     * At READ_COMMITTED it is refreshed before each statement so the transaction sees
     * committed data from other transactions that committed since this transaction began.
     * Populated by the shell layer before dispatching any command.
     */
    Snapshot currentStatementSnapshot;

    Transaction(int xid, Snapshot snapshot, IsolationLevel level) {
        this.xid = xid;
        this.snapshot = snapshot;
        this.level = level;
        this.state = State.ACTIVE;
        this.currentStatementSnapshot = snapshot;
    }

    public int xid() { return xid; }

    public Snapshot snapshot() { return snapshot; }

    public IsolationLevel level() { return level; }

    public Snapshot currentStatementSnapshot() { return currentStatementSnapshot; }

    void setCurrentStatementSnapshot(Snapshot snap) { this.currentStatementSnapshot = snap; }

    public State state() { return state; }

    void setState(State state) { this.state = state; }

    @Override
    public String toString() {
        return "Transaction{xid=" + xid + ", level=" + level + ", state=" + state + "}";
    }
}
