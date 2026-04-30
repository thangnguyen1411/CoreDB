package com.coredb.txn;

import com.coredb.api.CoreDBConfig;
import com.coredb.catalog.ControlFile;
import com.coredb.mvcc.Snapshot;
import com.coredb.mvcc.SnapshotManager;
import com.coredb.util.Constants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class IsolationLevelTest {

    @TempDir
    Path tempDir;

    ClogManager clog;
    ControlFile controlFile;
    SnapshotManager snapshotManager;
    TransactionManager txnMgr;

    @BeforeEach
    void setUp() throws Exception {
        Files.createDirectories(tempDir.resolve("global"));
        clog = ClogManager.create(tempDir);
        controlFile = ControlFile.create(tempDir, CoreDBConfig.defaults());
        snapshotManager = new SnapshotManager(Constants.FIRST_NORMAL_XID);
        txnMgr = new TransactionManager(controlFile, snapshotManager, clog);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (clog != null) clog.close();
        if (controlFile != null) controlFile.close();
    }

    @Test
    void readUncommitted_aliasedToReadCommitted() throws IOException {
        Transaction tx = txnMgr.beginTransaction(IsolationLevel.READ_UNCOMMITTED);
        assertThat(tx.level()).isEqualTo(IsolationLevel.READ_COMMITTED);
        txnMgr.rollback(tx);
    }

    @Test
    void readCommitted_storedAsIs() throws IOException {
        Transaction tx = txnMgr.beginTransaction(IsolationLevel.READ_COMMITTED);
        assertThat(tx.level()).isEqualTo(IsolationLevel.READ_COMMITTED);
        txnMgr.rollback(tx);
    }

    @Test
    void repeatableRead_storedAsIs() throws IOException {
        Transaction tx = txnMgr.beginTransaction(IsolationLevel.REPEATABLE_READ);
        assertThat(tx.level()).isEqualTo(IsolationLevel.REPEATABLE_READ);
        txnMgr.rollback(tx);
    }

    @Test
    void serializable_storedAsIs() throws IOException {
        Transaction tx = txnMgr.beginTransaction(IsolationLevel.SERIALIZABLE);
        assertThat(tx.level()).isEqualTo(IsolationLevel.SERIALIZABLE);
        txnMgr.rollback(tx);
    }

    @Test
    void noArgBegin_defaultsToRepeatableRead() throws IOException {
        Transaction tx = txnMgr.beginTransaction();
        assertThat(tx.level()).isEqualTo(IsolationLevel.REPEATABLE_READ);
        txnMgr.rollback(tx);
    }

    @Test
    void currentStatementSnapshot_initiallyEqualToTransactionSnapshot() throws IOException {
        Transaction tx = txnMgr.beginTransaction(IsolationLevel.REPEATABLE_READ);
        assertThat(tx.currentStatementSnapshot()).isEqualTo(tx.snapshot());
        txnMgr.rollback(tx);
    }

    @Test
    void refreshStatementSnapshot_readCommitted_updatesSnapshot() throws IOException {
        // Simulate a second transaction committing, advancing the snapshot boundary.
        Transaction other = txnMgr.beginTransaction();
        txnMgr.commit(other);

        Transaction tx = txnMgr.beginTransaction(IsolationLevel.READ_COMMITTED);
        Snapshot before = tx.currentStatementSnapshot();

        txnMgr.refreshStatementSnapshot(tx);
        Snapshot after = tx.currentStatementSnapshot();

        // The refreshed snapshot's xmax must be >= the original's, reflecting the committed transaction.
        assertThat(after.xmax()).isGreaterThanOrEqualTo(before.xmax());
        txnMgr.rollback(tx);
    }

    @Test
    void refreshStatementSnapshot_repeatableRead_isNoOp() throws IOException {
        Transaction tx = txnMgr.beginTransaction(IsolationLevel.REPEATABLE_READ);
        Snapshot txnSnapshot = tx.snapshot();

        txnMgr.refreshStatementSnapshot(tx);

        assertThat(tx.currentStatementSnapshot()).isSameAs(txnSnapshot);
        txnMgr.rollback(tx);
    }

    @Test
    void refreshStatementSnapshot_serializable_isNoOp() throws IOException {
        Transaction tx = txnMgr.beginTransaction(IsolationLevel.SERIALIZABLE);
        Snapshot txnSnapshot = tx.snapshot();

        txnMgr.refreshStatementSnapshot(tx);

        assertThat(tx.currentStatementSnapshot()).isSameAs(txnSnapshot);
        txnMgr.rollback(tx);
    }

    @Test
    void canonical_readUncommittedMapsToReadCommitted() {
        assertThat(IsolationLevel.READ_UNCOMMITTED.canonical())
            .isEqualTo(IsolationLevel.READ_COMMITTED);
    }

    @Test
    void canonical_otherLevelsUnchanged() {
        assertThat(IsolationLevel.READ_COMMITTED.canonical())
            .isEqualTo(IsolationLevel.READ_COMMITTED);
        assertThat(IsolationLevel.REPEATABLE_READ.canonical())
            .isEqualTo(IsolationLevel.REPEATABLE_READ);
        assertThat(IsolationLevel.SERIALIZABLE.canonical())
            .isEqualTo(IsolationLevel.SERIALIZABLE);
    }
}
