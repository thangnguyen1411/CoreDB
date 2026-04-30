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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SeqScanTest {

    @TempDir
    Path tempDir;

    CoreDB db;
    Schema schema;
    TableMeta meta;
    StorageEngine engine;

    @BeforeEach
    void setUp() throws IOException {
        db = CoreDB.open(tempDir);
        schema = Schema.of(
            Column.longCol("id"),
            Column.stringCol("name"),
            Column.intCol("age")
        );
        db.catalog().createTable("users", schema, "id");
        meta = db.catalog().openTable("users").orElseThrow();
        engine = db.getEngineForTable(meta);
    }

    @AfterEach
    void tearDown() throws IOException {
        db.close();
    }

    @Test
    void scan_emptyTable_returnsEmpty() throws IOException {
        Transaction tx = db.transactionManager().beginTransaction();
        SeqScan scan = new SeqScan(engine, "users", tx);
        scan.open();
        assertThat(scan.next()).isEmpty();
        scan.close();
        db.transactionManager().commit(tx);
    }

    @Test
    void scan_hundredRows_returnsAllRows() throws IOException {
        Transaction writeTx = db.transactionManager().beginTransaction();
        for (long i = 1; i <= 100; i++) {
            engine.put(i, Row.of(i, "name" + i, (int) i));
        }
        db.transactionManager().commit(writeTx);

        Transaction readTx = db.transactionManager().beginTransaction();
        SeqScan scan = new SeqScan(engine, "users", readTx);
        scan.open();

        int count = 0;
        while (scan.next().isPresent()) {
            count++;
        }
        scan.close();
        db.transactionManager().commit(readTx);

        assertThat(count).isEqualTo(100);
    }

    @Test
    void scan_afterDelete_excludesDeletedRow() throws IOException {
        Transaction writeTx = db.transactionManager().beginTransaction();
        engine.put(1L, Row.of(1L, "Alice", 30));
        engine.put(2L, Row.of(2L, "Bob", 25));
        db.transactionManager().commit(writeTx);

        Transaction deleteTx = db.transactionManager().beginTransaction();
        engine.delete(2L);
        db.transactionManager().commit(deleteTx);

        Transaction readTx = db.transactionManager().beginTransaction();
        SeqScan scan = new SeqScan(engine, "users", readTx);
        scan.open();

        List<Row> rows = drainAll(scan);
        scan.close();
        db.transactionManager().commit(readTx);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getString(1)).isEqualTo("Alice");
    }

    @Test
    void scan_abortedInsert_notVisible() throws IOException {
        Transaction writerTx = db.transactionManager().beginTransaction();
        engine.put(1L, Row.of(1L, "Alice", 30));
        db.transactionManager().rollback(writerTx);

        Transaction readTx = db.transactionManager().beginTransaction();
        SeqScan scan = new SeqScan(engine, "users", readTx);
        scan.open();
        List<Row> rows = drainAll(scan);
        scan.close();
        db.transactionManager().commit(readTx);

        assertThat(rows).isEmpty();
    }

    @Test
    void scan_withoutStatementSnapshot_throwsIllegalStateException() throws IOException {
        Transaction tx = db.transactionManager().beginTransaction();
        tx.setCurrentStatementSnapshot(null);
        SeqScan scan = new SeqScan(engine, "users", tx);
        assertThatThrownBy(scan::open)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("snapshot not set");
        scan.close();
        db.transactionManager().rollback(tx);
    }

    @Test
    void scan_closedProperly_noException() throws IOException {
        Transaction tx = db.transactionManager().beginTransaction();
        SeqScan scan = new SeqScan(engine, "users", tx);
        scan.open();
        scan.close();
        db.transactionManager().commit(tx);
    }

    private List<Row> drainAll(SeqScan scan) {
        List<Row> rows = new ArrayList<>();
        Optional<Row> r;
        while ((r = scan.next()).isPresent()) {
            rows.add(r.get());
        }
        return rows;
    }
}
