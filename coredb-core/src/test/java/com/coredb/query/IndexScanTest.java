package com.coredb.query;

import com.coredb.api.Column;
import com.coredb.api.CoreDB;
import com.coredb.api.Row;
import com.coredb.api.Schema;
import com.coredb.catalog.TableMeta;
import com.coredb.engine.StorageEngine;
import com.coredb.txn.Transaction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IndexScanTest {

    @TempDir
    Path tempDir;

    CoreDB db;
    StorageEngine engine;

    @BeforeEach
    void setUp() throws IOException {
        db = CoreDB.open(tempDir);
        Schema schema = Schema.of(
            Column.longCol("id"),
            Column.stringCol("name"),
            Column.intCol("age")
        );
        db.catalog().createTable("users", schema, "id");
        TableMeta meta = db.catalog().openTable("users").orElseThrow();
        engine = db.getEngineForTable(meta);
    }

    @AfterEach
    void tearDown() throws IOException {
        db.close();
    }

    @Test
    void lookup_existingKey_returnsRow() throws IOException {
        Transaction writeTx = db.transactionManager().beginTransaction();
        engine.put(1L, Row.of(1L, "Alice", 30));
        db.transactionManager().commit(writeTx);

        Transaction readTx = db.transactionManager().beginTransaction();
        IndexScan scan = new IndexScan(engine, "users", "users_pk", 1L, readTx);
        scan.open();

        Optional<Row> result = scan.next();
        assertThat(result).isPresent();
        assertThat(result.get().get(0)).isEqualTo(1L);
        assertThat(result.get().get(1)).isEqualTo("Alice");

        assertThat(scan.next()).isEmpty();
        scan.close();
        db.transactionManager().commit(readTx);
    }

    @Test
    void lookup_nonExistentKey_returnsEmpty() throws IOException {
        Transaction tx = db.transactionManager().beginTransaction();
        IndexScan scan = new IndexScan(engine, "users", "users_pk", 99L, tx);
        scan.open();

        assertThat(scan.next()).isEmpty();
        scan.close();
        db.transactionManager().commit(tx);
    }

    @Test
    void lookup_afterDelete_returnsEmpty() throws IOException {
        Transaction writeTx = db.transactionManager().beginTransaction();
        engine.put(1L, Row.of(1L, "Alice", 30));
        db.transactionManager().commit(writeTx);

        Transaction deleteTx = db.transactionManager().beginTransaction();
        engine.delete(1L);
        db.transactionManager().commit(deleteTx);

        Transaction readTx = db.transactionManager().beginTransaction();
        IndexScan scan = new IndexScan(engine, "users", "users_pk", 1L, readTx);
        scan.open();

        assertThat(scan.next()).isEmpty();
        scan.close();
        db.transactionManager().commit(readTx);
    }

    @Test
    void lookup_rowDeletedByUncommittedTxn_stillVisible() throws IOException {
        Transaction writeTx = db.transactionManager().beginTransaction();
        engine.put(1L, Row.of(1L, "Alice", 30));
        db.transactionManager().commit(writeTx);

        Transaction deleteTx = db.transactionManager().beginTransaction();
        engine.delete(1L);
        // not committed yet

        Transaction readTx = db.transactionManager().beginTransaction();
        IndexScan scan = new IndexScan(engine, "users", "users_pk", 1L, readTx);
        scan.open();

        assertThat(scan.next()).isPresent();
        scan.close();

        db.transactionManager().rollback(deleteTx);
        db.transactionManager().commit(readTx);
    }

    @Test
    void lookup_snapshotPredatesDelete_rowStillVisible() throws IOException {
        Transaction writeTx = db.transactionManager().beginTransaction();
        engine.put(1L, Row.of(1L, "Alice", 30));
        db.transactionManager().commit(writeTx);

        Transaction readTx = db.transactionManager().beginTransaction();

        Transaction deleteTx = db.transactionManager().beginTransaction();
        engine.delete(1L);
        db.transactionManager().commit(deleteTx);

        IndexScan scan = new IndexScan(engine, "users", "users_pk", 1L, readTx);
        scan.open();

        assertThat(scan.next()).isPresent();
        scan.close();
        db.transactionManager().commit(readTx);
    }

    @Test
    void lookup_withoutStatementSnapshot_throwsIllegalStateException() throws IOException {
        Transaction tx = db.transactionManager().beginTransaction();
        tx.setCurrentStatementSnapshot(null);

        IndexScan scan = new IndexScan(engine, "users", "users_pk", 1L, tx);

        assertThatThrownBy(scan::open)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("snapshot not set");

        db.transactionManager().rollback(tx);
    }

    @Test
    void lookup_nextCalledTwice_secondCallReturnsEmpty() throws IOException {
        Transaction writeTx = db.transactionManager().beginTransaction();
        engine.put(1L, Row.of(1L, "Alice", 30));
        db.transactionManager().commit(writeTx);

        Transaction readTx = db.transactionManager().beginTransaction();
        IndexScan scan = new IndexScan(engine, "users", "users_pk", 1L, readTx);
        scan.open();

        assertThat(scan.next()).isPresent();
        assertThat(scan.next()).isEmpty();
        scan.close();
        db.transactionManager().commit(readTx);
    }

    @Test
    void lookup_unknownIndexName_throwsUnsupportedOperationException() throws IOException {
        Transaction tx = db.transactionManager().beginTransaction();
        IndexScan scan = new IndexScan(engine, "users", "users_age_idx", 30, tx);

        assertThatThrownBy(scan::open)
            .isInstanceOf(UnsupportedOperationException.class);

        db.transactionManager().rollback(tx);
    }
}
