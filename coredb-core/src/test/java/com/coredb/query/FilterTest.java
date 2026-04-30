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

import static com.coredb.query.Predicate.Op.EQ;
import static com.coredb.query.Predicate.Op.GT;
import static com.coredb.query.Predicate.Op.LT;
import static org.assertj.core.api.Assertions.assertThat;

class FilterTest {

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

        Transaction writeTx = db.transactionManager().beginTransaction();
        engine.put(1L, Row.of(1L, "Alice", 30));
        engine.put(2L, Row.of(2L, "Bob", 25));
        engine.put(3L, Row.of(3L, "Carol", 35));
        engine.put(4L, Row.of(4L, "Dave", 20));
        db.transactionManager().commit(writeTx);
    }

    @AfterEach
    void tearDown() throws IOException {
        db.close();
    }

    @Test
    void filter_ageGreaterThan_returnsMatchingRows() throws IOException {
        Transaction tx = db.transactionManager().beginTransaction();
        Predicate pred = new Predicate("age", GT, 25, schema);
        Filter filter = new Filter(pred, new SeqScan(engine, tx));
        filter.open();

        List<Row> rows = drainAll(filter);
        filter.close();
        db.transactionManager().commit(tx);

        assertThat(rows).hasSize(2);
        assertThat(rows).allMatch(r -> r.getInt(2) > 25);
    }

    @Test
    void filter_noMatch_returnsEmpty() throws IOException {
        Transaction tx = db.transactionManager().beginTransaction();
        Predicate pred = new Predicate("age", GT, 100, schema);
        Filter filter = new Filter(pred, new SeqScan(engine, tx));
        filter.open();

        List<Row> rows = drainAll(filter);
        filter.close();
        db.transactionManager().commit(tx);

        assertThat(rows).isEmpty();
    }

    @Test
    void filter_exactMatch_returnsSingleRow() throws IOException {
        Transaction tx = db.transactionManager().beginTransaction();
        Predicate pred = new Predicate("age", EQ, 30, schema);
        Filter filter = new Filter(pred, new SeqScan(engine, tx));
        filter.open();

        List<Row> rows = drainAll(filter);
        filter.close();
        db.transactionManager().commit(tx);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getString(1)).isEqualTo("Alice");
    }

    @Test
    void filter_ageLessThan_returnsMatchingRows() throws IOException {
        Transaction tx = db.transactionManager().beginTransaction();
        Predicate pred = new Predicate("age", LT, 25, schema);
        Filter filter = new Filter(pred, new SeqScan(engine, tx));
        filter.open();

        List<Row> rows = drainAll(filter);
        filter.close();
        db.transactionManager().commit(tx);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getString(1)).isEqualTo("Dave");
    }

    @Test
    void filter_closePropagatedToChild() throws IOException {
        Transaction tx = db.transactionManager().beginTransaction();
        Predicate pred = new Predicate("age", GT, 0, schema);
        Filter filter = new Filter(pred, new SeqScan(engine, tx));
        filter.open();
        filter.close();
        db.transactionManager().commit(tx);
    }

    private List<Row> drainAll(Filter filter) {
        List<Row> rows = new ArrayList<>();
        Optional<Row> r;
        while ((r = filter.next()).isPresent()) {
            rows.add(r.get());
        }
        return rows;
    }
}
