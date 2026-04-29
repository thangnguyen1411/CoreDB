package com.coredb.engine;

import com.coredb.api.CoreDBConfig;
import com.coredb.api.Column;
import com.coredb.api.Row;
import com.coredb.api.Schema;
import com.coredb.buffer.BufferPool;
import com.coredb.catalog.ControlFile;
import com.coredb.catalog.TableMeta;
import com.coredb.config.EngineType;
import com.coredb.mvcc.SnapshotManager;
import com.coredb.txn.ClogManager;
import com.coredb.txn.LockManager;
import com.coredb.txn.LockMode;
import com.coredb.txn.LockTag;
import com.coredb.txn.Transaction;
import com.coredb.txn.TransactionManager;
import com.coredb.util.Constants;
import com.coredb.util.LockTimeoutException;
import com.coredb.wal.XLogWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConcurrentStorageEngineTest {

    @TempDir
    Path tempDir;

    private ClogManager clog;
    private ControlFile controlFile;
    private SnapshotManager snapshotManager;
    private XLogWriter xlogWriter;
    private LockManager lockManager;
    private TransactionManager transactionManager;
    private BufferPool bufferPool;

    @BeforeEach
    void setUp() throws Exception {
        Files.createDirectories(tempDir.resolve("global"));
        Files.createDirectories(tempDir.resolve("base/1"));
        clog = ClogManager.create(tempDir);
        controlFile = ControlFile.create(tempDir, CoreDBConfig.defaults());
        snapshotManager = new SnapshotManager(Constants.FIRST_NORMAL_XID);
        xlogWriter = XLogWriter.open(tempDir.resolve("global/pg_wal"));
        lockManager = new LockManager();
        transactionManager = new TransactionManager(controlFile, snapshotManager, clog, xlogWriter, lockManager);
        bufferPool = new BufferPool();
        bufferPool.setXLogWriter(xlogWriter);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (bufferPool != null) bufferPool.close();
        if (xlogWriter != null) xlogWriter.close();
        if (clog != null) clog.close();
        if (controlFile != null) controlFile.close();
    }

    private TableMeta tableMeta(int oid, String name) {
        Schema schema = Schema.of(
            Column.longCol("id").withNullable(false),
            Column.stringCol("name"),
            Column.intCol("age")
        );
        return new TableMeta(oid, name, schema, "id", EngineType.BTREE);
    }

    private ConcurrentStorageEngine openEngine(TableMeta meta) throws IOException {
        StorageEngine inner = StorageEngineFactory.create(EngineType.BTREE, CoreDBConfig.defaults());
        ConcurrentStorageEngine wrapped = new ConcurrentStorageEngine(inner, lockManager);
        wrapped.open(tempDir, meta, bufferPool, xlogWriter, clog, transactionManager);
        return wrapped;
    }

    @Test
    void put_acquiresExclusiveTableLock() throws IOException {
        TableMeta meta = tableMeta(2001, "users_a");
        try (ConcurrentStorageEngine engine = openEngine(meta)) {
            Transaction tx = transactionManager.beginTransaction();
            engine.put(1L, Row.of(1L, "Alice", 30));

            LockTag tag = new LockTag(meta.oid(), LockTag.LockType.TABLE);
            assertThat(lockManager.holdersOf(tag)).containsExactly(tx.xid());
            assertThat(lockManager.locksHeldBy(tx.xid())).containsExactly(tag);

            transactionManager.rollback(tx);
        }
    }

    @Test
    void get_doesNotAcquireLock() throws IOException {
        TableMeta meta = tableMeta(2002, "users_b");
        try (ConcurrentStorageEngine engine = openEngine(meta)) {
            Transaction tx = transactionManager.beginTransaction();
            engine.get(1L);

            assertThat(lockManager.locksHeldBy(tx.xid())).isEmpty();
            transactionManager.rollback(tx);
        }
    }

    @Test
    void rangeScan_doesNotAcquireLock() throws IOException {
        TableMeta meta = tableMeta(2003, "users_c");
        try (ConcurrentStorageEngine engine = openEngine(meta)) {
            Transaction tx = transactionManager.beginTransaction();
            engine.rangeScan(0L, 100L);

            assertThat(lockManager.locksHeldBy(tx.xid())).isEmpty();
            transactionManager.rollback(tx);
        }
    }

    @Test
    void commit_releasesAllLocks() throws IOException {
        TableMeta meta = tableMeta(2004, "users_d");
        try (ConcurrentStorageEngine engine = openEngine(meta)) {
            Transaction tx = transactionManager.beginTransaction();
            int xid = tx.xid();
            engine.put(1L, Row.of(1L, "Alice", 30));
            assertThat(lockManager.locksHeldBy(xid)).hasSize(1);

            transactionManager.commit(tx);

            assertThat(lockManager.locksHeldBy(xid)).isEmpty();
            assertThat(lockManager.holdersOf(new LockTag(meta.oid(), LockTag.LockType.TABLE))).isEmpty();
        }
    }

    @Test
    void rollback_releasesAllLocks() throws IOException {
        TableMeta meta = tableMeta(2005, "users_e");
        try (ConcurrentStorageEngine engine = openEngine(meta)) {
            Transaction tx = transactionManager.beginTransaction();
            int xid = tx.xid();
            engine.put(1L, Row.of(1L, "Alice", 30));
            assertThat(lockManager.locksHeldBy(xid)).hasSize(1);

            transactionManager.rollback(tx);

            assertThat(lockManager.locksHeldBy(xid)).isEmpty();
            assertThat(lockManager.holdersOf(new LockTag(meta.oid(), LockTag.LockType.TABLE))).isEmpty();
        }
    }

    @Test
    void delete_acquiresExclusiveLock() throws IOException {
        TableMeta meta = tableMeta(2006, "users_f");
        try (ConcurrentStorageEngine engine = openEngine(meta)) {
            Transaction setup = transactionManager.beginTransaction();
            engine.put(1L, Row.of(1L, "Alice", 30));
            transactionManager.commit(setup);

            Transaction tx = transactionManager.beginTransaction();
            engine.delete(1L);

            LockTag tag = new LockTag(meta.oid(), LockTag.LockType.TABLE);
            assertThat(lockManager.holdersOf(tag)).containsExactly(tx.xid());
            transactionManager.commit(tx);
            assertThat(lockManager.holdersOf(tag)).isEmpty();
        }
    }

    @Test
    void put_blocksWhenAnotherXidHoldsExclusiveLock() throws IOException {
        TableMeta meta = tableMeta(2007, "users_g");
        try (ConcurrentStorageEngine engine = openEngine(meta)) {
            int otherXid = 9999;
            LockTag tag = new LockTag(meta.oid(), LockTag.LockType.TABLE);
            lockManager.acquire(otherXid, tag, LockMode.EXCLUSIVE, 100);

            Transaction tx = transactionManager.beginTransaction();
            assertThatThrownBy(() -> engine.put(1L, Row.of(1L, "Alice", 30)))
                .isInstanceOf(LockTimeoutException.class);

            lockManager.release(otherXid, tag);
            transactionManager.rollback(tx);
        }
    }

    @Test
    void writesToDifferentTables_doNotContend() throws IOException {
        TableMeta tableA = tableMeta(2008, "users_h1");
        TableMeta tableB = tableMeta(2009, "users_h2");
        try (ConcurrentStorageEngine engineA = openEngine(tableA);
             ConcurrentStorageEngine engineB = openEngine(tableB)) {
            int otherXid = 8888;
            LockTag tagA = new LockTag(tableA.oid(), LockTag.LockType.TABLE);
            lockManager.acquire(otherXid, tagA, LockMode.EXCLUSIVE, 100);

            Transaction tx = transactionManager.beginTransaction();
            engineB.put(1L, Row.of(1L, "Bob", 25));

            assertThat(lockManager.holdersOf(new LockTag(tableB.oid(), LockTag.LockType.TABLE)))
                .containsExactly(tx.xid());

            lockManager.release(otherXid, tagA);
            transactionManager.rollback(tx);
        }
    }
}
