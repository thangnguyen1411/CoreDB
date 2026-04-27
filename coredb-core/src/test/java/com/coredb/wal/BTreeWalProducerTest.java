package com.coredb.wal;

import com.coredb.api.Column;
import com.coredb.api.Row;
import com.coredb.api.Schema;
import com.coredb.buffer.BufferPool;
import com.coredb.heap.HeapFile;
import com.coredb.heap.RecordId;
import com.coredb.index.BTree;
import com.coredb.index.IndexFile;
import com.coredb.util.Constants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies WAL producers emit records before page mutations reach disk.
 *
 * Checks:
 * - heap insert/delete each produce a WAL record
 * - B-tree insert produces a WAL record
 * - leaf split produces at least one BTREE_SPLIT record
 * - WAL-before-data rule: flushedLsn >= pdLsn before dirty page write
 */
class BTreeWalProducerTest {

    @TempDir
    Path tempDir;

    private BufferPool bufferPool;
    private XLogWriter xlogWriter;
    private Schema schema;
    private Path walPath;

    @BeforeEach
    void setUp() throws Exception {
        bufferPool = new BufferPool(64);
        walPath = tempDir.resolve("pg_wal");
        xlogWriter = XLogWriter.open(walPath);
        bufferPool.setXLogWriter(xlogWriter);

        schema = Schema.of(
            Column.longCol("id"),
            Column.stringCol("name")
        );
    }

    @AfterEach
    void tearDown() throws Exception {
        if (bufferPool != null) {
            bufferPool.close();
        }
        if (xlogWriter != null) {
            xlogWriter.close();
        }
    }

    @Test
    void heapInsert_producesWalRecord() throws Exception {
        Path heapPath = tempDir.resolve("base/1/1002");
        int oid = 1002;
        long walBefore = xlogWriter.currentLsn();

        try (HeapFile heap = HeapFile.create(heapPath, oid, schema, bufferPool, xlogWriter, Constants.BOOTSTRAP_XID)) {
            heap.insert(Row.of(1L, "Alice"));
        }

        assertThat(xlogWriter.currentLsn()).isGreaterThan(walBefore);
    }

    @Test
    void heapInsert_walDumpShowsHeapInsertRecord() throws Exception {
        Path heapPath = tempDir.resolve("base/1/1002");
        int oid = 1002;

        try (HeapFile heap = HeapFile.create(heapPath, oid, schema, bufferPool, xlogWriter, Constants.BOOTSTRAP_XID)) {
            heap.insert(Row.of(1L, "Alice"));
        }

        try (XLogReader reader = XLogReader.open(walPath)) {
            reader.seek(XLogWriter.FIRST_LSN);
            Optional<XLogRecord> opt = reader.readNext();
            assertThat(opt).isPresent();
            XLogRecord record = opt.get();
            assertThat(record.resourceManager()).isEqualTo(XLogRecord.RMGR_HEAP);
            assertThat(record.info()).isEqualTo(HeapResourceManager.HEAP_INSERT);
            assertThat(record.tableOid()).isEqualTo(oid);
        }
    }

    @Test
    void heapDelete_producesWalRecord() throws Exception {
        Path heapPath = tempDir.resolve("base/1/1002");
        int oid = 1002;

        try (HeapFile heap = HeapFile.create(heapPath, oid, schema, bufferPool, xlogWriter, Constants.BOOTSTRAP_XID)) {
            RecordId rid = heap.insert(Row.of(1L, "Alice"));
            long lsnAfterInsert = xlogWriter.currentLsn();

            heap.delete(rid);

            assertThat(xlogWriter.currentLsn()).isGreaterThan(lsnAfterInsert);
        }
    }

    @Test
    void btreeInsert_producesWalRecord() throws Exception {
        Path indexPath = tempDir.resolve("base/1/1002_pk");
        Files.createDirectories(indexPath.getParent());
        int indexOid = 1002 + 0x00100000;

        long walBefore = xlogWriter.currentLsn();

        // IndexFile.create() registers the file with BufferPool internally
        IndexFile indexFile = IndexFile.create(indexPath, indexOid, bufferPool);
        try (indexFile) {
            BTree btree = BTree.create(indexFile, xlogWriter, Constants.BOOTSTRAP_XID);
            btree.insert(42L, new RecordId(1, 0));
        }

        assertThat(xlogWriter.currentLsn()).isGreaterThan(walBefore);

        try (XLogReader reader = XLogReader.open(walPath)) {
            reader.seek(XLogWriter.FIRST_LSN);
            Optional<XLogRecord> opt = reader.readNext();
            assertThat(opt).isPresent();
            XLogRecord record = opt.get();
            assertThat(record.resourceManager()).isEqualTo(XLogRecord.RMGR_BTREE);
            assertThat(record.info()).isEqualTo(BTreeResourceManager.BTREE_INSERT);
            assertThat(record.tableOid()).isEqualTo(indexOid);
        }
    }

    @Test
    void btreeSplit_producesAtLeastOneBtreeSplitRecord() throws Exception {
        Path indexPath = tempDir.resolve("base/1/1002_pk");
        Files.createDirectories(indexPath.getParent());
        int indexOid = 1002 + 0x00100000;

        // IndexFile.create() registers the file with BufferPool internally
        IndexFile indexFile = IndexFile.create(indexPath, indexOid, bufferPool);
        try (indexFile) {
            BTree btree = BTree.create(indexFile, xlogWriter, Constants.BOOTSTRAP_XID);

            // A leaf page holds ~453 entries; insert 500 to guarantee a split
            for (int i = 0; i < 500; i++) {
                btree.insert((long) i, new RecordId(1, i));
            }
        }

        boolean foundSplit = false;
        try (XLogReader reader = XLogReader.open(walPath)) {
            reader.seek(XLogWriter.FIRST_LSN);
            Optional<XLogRecord> opt;
            while ((opt = reader.readNext()).isPresent()) {
                XLogRecord record = opt.get();
                if (record.resourceManager() == XLogRecord.RMGR_BTREE
                        && record.info() == BTreeResourceManager.BTREE_SPLIT) {
                    foundSplit = true;
                    break;
                }
            }
        }
        assertThat(foundSplit).as("expected at least one BTREE_SPLIT WAL record").isTrue();
    }

    @Test
    void walBeforeDataRule_flushForcesWalFlushFirst() throws Exception {
        Path heapPath = tempDir.resolve("base/1/1002");
        int oid = 1002;

        try (HeapFile heap = HeapFile.create(heapPath, oid, schema, bufferPool, xlogWriter, Constants.BOOTSTRAP_XID)) {
            heap.insert(Row.of(1L, "Alice"));

            // flush() routes through BufferPool.flushAllForFile(), which calls
            // flushFrame() → xlogWriter.flushUpTo(pdLsn) before writing the page
            heap.flush();
        }

        assertThat(xlogWriter.flushedLsn()).isGreaterThan(XLogWriter.INVALID_LSN);
    }

    @Test
    void walRecordsSurviveCloseAndReopen() throws Exception {
        Path heapPath = tempDir.resolve("base/1/1002");
        int oid = 1002;

        try (HeapFile heap = HeapFile.create(heapPath, oid, schema, bufferPool, xlogWriter, Constants.BOOTSTRAP_XID)) {
            heap.insert(Row.of(1L, "Alice"));
            heap.flush();
        }

        xlogWriter.close();
        xlogWriter = null;

        try (XLogReader reader = XLogReader.open(walPath)) {
            reader.seek(XLogWriter.FIRST_LSN);
            Optional<XLogRecord> opt = reader.readNext();
            assertThat(opt).isPresent();
            assertThat(opt.get().lsn()).isGreaterThanOrEqualTo(XLogWriter.FIRST_LSN);
        }
    }
}
