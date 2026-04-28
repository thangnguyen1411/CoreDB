package com.coredb.txn;

import com.coredb.api.CoreDBConfig;
import com.coredb.catalog.ControlFile;
import com.coredb.mvcc.Snapshot;
import com.coredb.mvcc.SnapshotManager;
import com.coredb.util.Constants;
import com.coredb.util.TxnException;
import com.coredb.wal.XLogReader;
import com.coredb.wal.XLogRecord;
import com.coredb.wal.XLogResourceManager;
import com.coredb.wal.XLogWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransactionManagerTest {

    @TempDir
    Path tempDir;

    ClogManager clog;
    ControlFile controlFile;
    SnapshotManager snapshotManager;
    XLogWriter xlogWriter;
    TransactionManager transactionManager;

    @BeforeEach
    void setUp() throws Exception {
        Files.createDirectories(tempDir.resolve("global"));
        clog = ClogManager.create(tempDir);
        controlFile = ControlFile.create(tempDir, CoreDBConfig.defaults());
        snapshotManager = new SnapshotManager(Constants.FIRST_NORMAL_XID);
        xlogWriter = XLogWriter.open(tempDir.resolve("global/pg_wal"));
        transactionManager = new TransactionManager(controlFile, snapshotManager, clog, xlogWriter);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (xlogWriter != null) xlogWriter.close();
        if (clog != null) clog.close();
        if (controlFile != null) controlFile.close();
    }

    // Helper to read all WAL records
    private List<XLogRecord> readAllWalRecords() throws IOException {
        List<XLogRecord> records = new ArrayList<>();
        try (XLogReader reader = XLogReader.open(tempDir.resolve("global/pg_wal"))) {
            Optional<XLogRecord> optRecord;
            while ((optRecord = reader.readNext()).isPresent()) {
                records.add(optRecord.get());
            }
        }
        return records;
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
        TransactionManager transactionManager2 = new TransactionManager(reloaded, snapshotManager, clog, xlogWriter);

        Transaction t2 = transactionManager2.beginTransaction();
        assertThat(t2.xid()).isGreaterThan(firstXid);
        transactionManager2.commit(t2);
        reloaded.close();
    }

    @Test
    void commit_writesXactCommitWalRecord() throws IOException {
        transactionManager.beginTransaction();

        xlogWriter.close();
        xlogWriter = XLogWriter.open(tempDir.resolve("global/pg_wal"));
        transactionManager = new TransactionManager(controlFile, snapshotManager, clog, xlogWriter);

        Transaction tx2 = transactionManager.beginTransaction();
        transactionManager.commit(tx2);

        // Verify WAL contains XACT_COMMIT record
        List<XLogRecord> records = readAllWalRecords();
        assertThat(records).isNotEmpty();

        XLogRecord commitRecord = records.stream()
            .filter(r -> r.info() == XLogResourceManager.XACT_COMMIT)
            .findFirst()
            .orElse(null);

        assertThat(commitRecord).isNotNull();
        assertThat(commitRecord.xid()).isEqualTo(tx2.xid());
        assertThat(commitRecord.resourceManager()).isEqualTo(XLogRecord.RMGR_XLOG);
    }

    @Test
    void rollback_writesXactAbortWalRecord() throws IOException {
        xlogWriter.close();
        xlogWriter = XLogWriter.open(tempDir.resolve("global/pg_wal"));
        transactionManager = new TransactionManager(controlFile, snapshotManager, clog, xlogWriter);

        Transaction tx = transactionManager.beginTransaction();
        transactionManager.rollback(tx);

        // Verify WAL contains XACT_ABORT record
        List<XLogRecord> records = readAllWalRecords();
        assertThat(records).isNotEmpty();

        XLogRecord abortRecord = records.stream()
            .filter(r -> r.info() == XLogResourceManager.XACT_ABORT)
            .findFirst()
            .orElse(null);

        assertThat(abortRecord).isNotNull();
        assertThat(abortRecord.xid()).isEqualTo(tx.xid());
        assertThat(abortRecord.resourceManager()).isEqualTo(XLogRecord.RMGR_XLOG);
    }

    @Test
    void commitPayload_containsXidAndTimestamp() throws IOException {
        xlogWriter.close();
        xlogWriter = XLogWriter.open(tempDir.resolve("global/pg_wal"));
        transactionManager = new TransactionManager(controlFile, snapshotManager, clog, xlogWriter);

        long beforeCommit = System.currentTimeMillis();
        Transaction tx = transactionManager.beginTransaction();
        transactionManager.commit(tx);
        long afterCommit = System.currentTimeMillis();

        List<XLogRecord> records = readAllWalRecords();
        XLogRecord commitRecord = records.stream()
            .filter(r -> r.info() == XLogResourceManager.XACT_COMMIT)
            .findFirst()
            .orElseThrow();

        // Payload: (xid as int, timestamp as long) = 12 bytes
        byte[] data = commitRecord.data();
        assertThat(data).hasSize(12);

        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        int payloadXid = buf.getInt();
        long payloadTimestamp = buf.getLong();

        assertThat(payloadXid).isEqualTo(tx.xid());
        assertThat(payloadTimestamp).isBetween(beforeCommit, afterCommit);
    }

    @Test
    void abortPayload_containsXid() throws IOException {
        xlogWriter.close();
        xlogWriter = XLogWriter.open(tempDir.resolve("global/pg_wal"));
        transactionManager = new TransactionManager(controlFile, snapshotManager, clog, xlogWriter);

        Transaction tx = transactionManager.beginTransaction();
        transactionManager.rollback(tx);

        List<XLogRecord> records = readAllWalRecords();
        XLogRecord abortRecord = records.stream()
            .filter(r -> r.info() == XLogResourceManager.XACT_ABORT)
            .findFirst()
            .orElseThrow();

        // Payload: (xid as int) = 4 bytes
        byte[] data = abortRecord.data();
        assertThat(data).hasSize(4);

        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        int payloadXid = buf.getInt();

        assertThat(payloadXid).isEqualTo(tx.xid());
    }

    @Test
    void commit_multipleTransactionsWalRecordsInOrder() throws IOException {
        xlogWriter.close();
        xlogWriter = XLogWriter.open(tempDir.resolve("global/pg_wal"));
        transactionManager = new TransactionManager(controlFile, snapshotManager, clog, xlogWriter);

        // First transaction and commit
        Transaction t1 = transactionManager.beginTransaction();
        int xid1 = t1.xid();
        transactionManager.commit(t1);

        // Second transaction and commit (using same manager since first is done)
        Transaction t2 = transactionManager.beginTransaction();
        int xid2 = t2.xid();
        transactionManager.commit(t2);

        List<XLogRecord> records = readAllWalRecords();

        // Should have 2 XACT_COMMIT records
        List<XLogRecord> commitRecords = records.stream()
            .filter(r -> r.info() == XLogResourceManager.XACT_COMMIT)
            .toList();

        assertThat(commitRecords).hasSize(2);

        // Verify ordering: first commit record has lower LSN than second
        assertThat(commitRecords.get(0).lsn()).isLessThan(commitRecords.get(1).lsn());

        // Verify correct XIDs
        assertThat(commitRecords.get(0).xid()).isEqualTo(xid1);
        assertThat(commitRecords.get(1).xid()).isEqualTo(xid2);
    }

    @Test
    void commit_noWAL_whenXLogWriterIsNull() throws IOException {
        // Create manager without WAL writer
        TransactionManager noWalManager = new TransactionManager(controlFile, snapshotManager, clog, null);

        Transaction tx = noWalManager.beginTransaction();
        noWalManager.commit(tx);

        // Should complete without exception even though no WAL writer
        assertThat(tx.state()).isEqualTo(Transaction.State.COMMITTED);
        assertThat(clog.getStatus(tx.xid())).isEqualTo(ClogManager.Status.COMMITTED);
    }

    @Test
    void rollback_noWAL_whenXLogWriterIsNull() throws IOException {
        // Create manager without WAL writer
        TransactionManager noWalManager = new TransactionManager(controlFile, snapshotManager, clog, null);

        Transaction tx = noWalManager.beginTransaction();
        noWalManager.rollback(tx);

        // Should complete without exception even though no WAL writer
        assertThat(tx.state()).isEqualTo(Transaction.State.ABORTED);
        assertThat(clog.getStatus(tx.xid())).isEqualTo(ClogManager.Status.ABORTED);
    }
}
