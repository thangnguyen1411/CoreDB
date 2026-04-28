package com.coredb.txn;

import com.coredb.api.CoreDBConfig;
import com.coredb.catalog.ControlFile;
import com.coredb.heap.HeapPage;
import com.coredb.heap.HeapTupleHeader;
import com.coredb.heap.RecordId;
import com.coredb.mvcc.Snapshot;
import com.coredb.mvcc.SnapshotManager;
import com.coredb.mvcc.TupleVisibility;
import com.coredb.page.ItemId;
import com.coredb.page.Page;
import com.coredb.util.Constants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class RollbackVisibilityIntegrationTest {

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
    void rolledBackInsert_isInvisibleButPhysicallyPresent() throws IOException {
        Transaction tx = transactionManager.beginTransaction();
        int insertXid = tx.xid();

        Page page = Page.Factory.allocateHeapPage(0);
        HeapPage heapPage = new HeapPage(page);

        byte[] tupleData = createTestTupleData("Alice", 30);
        RecordId rid = heapPage.insert(tupleData, (short) 2, insertXid);
        int slotNo = rid.slotNo();
        assertThat(slotNo).isGreaterThanOrEqualTo(0);

        transactionManager.rollback(tx);

        assertThat(clog.getStatus(insertXid)).isEqualTo(ClogManager.Status.ABORTED);

        Snapshot newSnapshot = new Snapshot(insertXid + 1, insertXid + 2, java.util.Collections.emptySet());

        int itemId = page.readItemId(slotNo);
        int offset = ItemId.offset(itemId);
        HeapTupleHeader header = HeapTupleHeader.readFrom(page.buffer(), offset);

        assertThat(TupleVisibility.isVisible(header, newSnapshot, clog, Constants.INVALID_XID))
            .as("Rolled-back insert should be invisible to new transaction")
            .isFalse();

        assertThat(heapPage.slotCount()).isGreaterThan(0);

        byte[] physicalTupleData = heapPage.get(slotNo);
        assertThat(physicalTupleData).isNotNull();
        assertThat(physicalTupleData.length).isGreaterThan(0);

        HeapTupleHeader physicalHeader = HeapTupleHeader.readFrom(page.buffer(), offset);
        assertThat(physicalHeader.xmin()).isEqualTo(insertXid);

        assertThat(heapPage.scan(newSnapshot, clog, Constants.INVALID_XID))
            .as("No visible tuples should be found (the one tuple is invisible due to rollback)")
            .isEmpty();

        Transaction cleanupTx = transactionManager.beginTransaction();
        transactionManager.commit(cleanupTx);
    }

    @Test
    void committedInsert_isVisibleAndPhysicallyPresent() throws IOException {
        Transaction tx = transactionManager.beginTransaction();
        int insertXid = tx.xid();

        Page page = Page.Factory.allocateHeapPage(0);
        HeapPage heapPage = new HeapPage(page);

        byte[] tupleData = createTestTupleData("Bob", 25);
        RecordId rid = heapPage.insert(tupleData, (short) 2, insertXid);
        int slotNo = rid.slotNo();

        transactionManager.commit(tx);

        assertThat(clog.getStatus(insertXid)).isEqualTo(ClogManager.Status.COMMITTED);

        Snapshot newSnapshot = new Snapshot(insertXid + 1, insertXid + 2, java.util.Collections.emptySet());

        int itemId = page.readItemId(slotNo);
        int offset = ItemId.offset(itemId);
        HeapTupleHeader header = HeapTupleHeader.readFrom(page.buffer(), offset);

        assertThat(TupleVisibility.isVisible(header, newSnapshot, clog, Constants.INVALID_XID))
            .as("Committed insert should be visible to new transaction")
            .isTrue();

        assertThat(heapPage.scan(newSnapshot, clog, Constants.INVALID_XID))
            .hasSize(1);

        Transaction cleanupTx = transactionManager.beginTransaction();
        transactionManager.commit(cleanupTx);
    }

    @Test
    void rolledBackInsert_canBeReclaimedByVacuum() throws IOException {
        Transaction tx = transactionManager.beginTransaction();
        int insertXid = tx.xid();

        Page page = Page.Factory.allocateHeapPage(0);
        HeapPage heapPage = new HeapPage(page);

        byte[] tupleData = createTestTupleData("Charlie", 40);
        RecordId rid = heapPage.insert(tupleData, (short) 2, insertXid);
        int slotNo = rid.slotNo();

        transactionManager.rollback(tx);

        Transaction vacuumTx = transactionManager.beginTransaction();
        Snapshot vacuumSnapshot = vacuumTx.snapshot();

        int itemId = page.readItemId(slotNo);
        int offset = ItemId.offset(itemId);
        HeapTupleHeader header = HeapTupleHeader.readFrom(page.buffer(), offset);

        assertThat(TupleVisibility.isVisible(header, vacuumSnapshot, clog, vacuumTx.xid())).isFalse();
        assertThat(header.xmin()).isEqualTo(insertXid);
        assertThat(clog.getStatus(header.xmin())).isEqualTo(ClogManager.Status.ABORTED);

        transactionManager.commit(vacuumTx);
    }

    private byte[] createTestTupleData(String name, int age) {
        byte[] nameBytes = name.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.allocate(1 + nameBytes.length + 4).order(ByteOrder.BIG_ENDIAN);
        buf.put((byte) nameBytes.length);
        buf.put(nameBytes);
        buf.putInt(age);
        return buf.array();
    }
}
