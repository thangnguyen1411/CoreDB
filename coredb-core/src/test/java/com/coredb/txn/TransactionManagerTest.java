package com.coredb.txn;

import com.coredb.api.CoreDBConfig;
import com.coredb.catalog.ControlFile;
import com.coredb.mvcc.Snapshot;
import com.coredb.mvcc.SnapshotManager;
import com.coredb.util.Constants;
import com.coredb.util.TxnException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransactionManagerTest {

    @TempDir
    Path tempDir;

    ClogManager clog;
    ControlFile controlFile;
    SnapshotManager snapshotManager;
    TransactionManager transactionManager;

    @BeforeEach
    void setUp() throws Exception {
        Files.createDirectories(tempDir.resolve("global"));
        clog = ClogManager.create(tempDir);
        controlFile = ControlFile.create(tempDir, CoreDBConfig.defaults());
        snapshotManager = new SnapshotManager(Constants.FIRST_NORMAL_XID);
        transactionManager = new TransactionManager(controlFile, snapshotManager, clog);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (clog != null) clog.close();
        if (controlFile != null) controlFile.close();
    }

    @Test
    void beginTransaction_allocatesDistinctXids() throws IOException {
        Transaction t1 = transactionManager.beginTransaction();
        transactionManager.commit(t1);

        Transaction t2 = transactionManager.beginTransaction();
        transactionManager.commit(t2);

        assertThat(t1.xid()).isNotEqualTo(t2.xid());
    }

    @Test
    void beginTransaction_xidStartsAtFirstNormalXid() throws IOException {
        Transaction t1 = transactionManager.beginTransaction();
        assertThat(t1.xid()).isGreaterThanOrEqualTo(Constants.FIRST_NORMAL_XID);
        transactionManager.commit(t1);
    }

    @Test
    void beginTransaction_newXidVisibleInOwnSnapshot() throws IOException {
        Transaction tx = transactionManager.beginTransaction();
        assertThat(tx.snapshot().isActive(tx.xid())).isTrue();
        transactionManager.rollback(tx);
    }

    @Test
    void activeXidVisibleInSnapshotTakenWhileTransactionRunning() throws IOException {
        Transaction t1 = transactionManager.beginTransaction();
        int xid = t1.xid();

        // A snapshot taken while T1 is active must include T1's xid in the active set
        Snapshot snap = snapshotManager.takeSnapshot();
        assertThat(snap.isActive(xid)).isTrue();

        transactionManager.commit(t1);
    }

    @Test
    void beginWhileActiveTransaction_throwsTxnException() throws IOException {
        Transaction t1 = transactionManager.beginTransaction();

        assertThatThrownBy(() -> transactionManager.beginTransaction())
            .isInstanceOf(TxnException.class);

        transactionManager.rollback(t1);
    }

    @Test
    void commit_setsTransactionStateCommitted() throws IOException {
        Transaction tx = transactionManager.beginTransaction();
        assertThat(tx.state()).isEqualTo(Transaction.State.ACTIVE);

        transactionManager.commit(tx);

        assertThat(tx.state()).isEqualTo(Transaction.State.COMMITTED);
    }

    @Test
    void rollback_setsTransactionStateAborted() throws IOException {
        Transaction tx = transactionManager.beginTransaction();
        transactionManager.rollback(tx);

        assertThat(tx.state()).isEqualTo(Transaction.State.ABORTED);
    }

    @Test
    void commit_writesClogCommittedStatus() throws IOException {
        Transaction tx = transactionManager.beginTransaction();
        int xid = tx.xid();
        transactionManager.commit(tx);

        assertThat(clog.getStatus(xid)).isEqualTo(ClogManager.Status.COMMITTED);
    }

    @Test
    void rollback_writesClogAbortedStatus() throws IOException {
        Transaction tx = transactionManager.beginTransaction();
        int xid = tx.xid();
        transactionManager.rollback(tx);

        assertThat(clog.getStatus(xid)).isEqualTo(ClogManager.Status.ABORTED);
    }

    @Test
    void afterCommit_currentTransactionIsNull() throws IOException {
        Transaction tx = transactionManager.beginTransaction();
        transactionManager.commit(tx);

        assertThat(transactionManager.currentTransaction()).isNull();
    }

    @Test
    void afterCommit_xidRemovedFromActiveSet() throws IOException {
        Transaction tx = transactionManager.beginTransaction();
        int xid = tx.xid();
        transactionManager.commit(tx);

        Snapshot snapAfterCommit = snapshotManager.takeSnapshot();
        assertThat(snapAfterCommit.isActive(xid)).isFalse();
    }

    @Test
    void commit_onNonActiveTx_throwsTxnException() throws IOException {
        Transaction tx = transactionManager.beginTransaction();
        transactionManager.commit(tx);

        assertThatThrownBy(() -> transactionManager.commit(tx))
            .isInstanceOf(TxnException.class);
    }

    @Test
    void xidAllocation_surviveControlFileReload() throws IOException {
        Transaction t1 = transactionManager.beginTransaction();
        int firstXid = t1.xid();
        transactionManager.commit(t1);

        // Reload control file (simulates restart)
        controlFile.close();
        ControlFile reloaded = ControlFile.load(tempDir);
        TransactionManager transactionManager2 = new TransactionManager(reloaded, snapshotManager, clog);

        Transaction t2 = transactionManager2.beginTransaction();
        assertThat(t2.xid()).isGreaterThan(firstXid);
        transactionManager2.commit(t2);
        reloaded.close();
    }
}
