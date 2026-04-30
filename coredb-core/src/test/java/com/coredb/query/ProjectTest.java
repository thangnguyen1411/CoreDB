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

import static com.coredb.query.Predicate.Op.GT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProjectTest {

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
        db.transactionManager().commit(writeTx);
    }

    @AfterEach
    void tearDown() throws IOException {
        db.close();
    }

    @Test
    void project_specificColumns_returnsOnlyThoseColumns() throws IOException {
        Transaction tx = db.transactionManager().beginTransaction();
        Project project = new Project(List.of("id", "name"), schema, new SeqScan(engine, tx));
        project.open();

        List<Row> rows = drainAll(project);
        project.close();
        db.transactionManager().commit(tx);

        assertThat(rows).hasSize(2);
        for (Row row : rows) {
            assertThat(row.size()).isEqualTo(2);
        }
    }

    @Test
    void project_passThrough_returnsAllColumns() throws IOException {
        Transaction tx = db.transactionManager().beginTransaction();
        Project project = new Project(List.of(), schema, new SeqScan(engine, tx));
        project.open();

        List<Row> rows = drainAll(project);
        project.close();
        db.transactionManager().commit(tx);

        assertThat(rows).hasSize(2);
        for (Row row : rows) {
            assertThat(row.size()).isEqualTo(3);
        }
    }

    @Test
    void project_nullColumns_passThroughAllColumns() throws IOException {
        Transaction tx = db.transactionManager().beginTransaction();
        Project project = new Project(null, schema, new SeqScan(engine, tx));
        project.open();

        List<Row> rows = drainAll(project);
        project.close();
        db.transactionManager().commit(tx);

        assertThat(rows).hasSize(2);
        for (Row row : rows) {
            assertThat(row.size()).isEqualTo(3);
        }
    }

    @Test
    void project_singleColumn_returnsOnlyThatColumn() throws IOException {
        Transaction tx = db.transactionManager().beginTransaction();
        Project project = new Project(List.of("name"), schema, new SeqScan(engine, tx));
        project.open();

        List<Row> rows = drainAll(project);
        project.close();
        db.transactionManager().commit(tx);

        assertThat(rows).hasSize(2);
        assertThat(rows.stream().map(r -> r.getString(0))).contains("Alice", "Bob");
    }

    @Test
    void project_composedWithFilter_worksEndToEnd() throws IOException {
        Transaction tx = db.transactionManager().beginTransaction();
        Predicate pred = new Predicate("age", GT, 25, schema);
        Project project = new Project(
            List.of("name"),
            schema,
            new Filter(pred, new SeqScan(engine, tx))
        );
        project.open();

        List<Row> rows = drainAll(project);
        project.close();
        db.transactionManager().commit(tx);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getString(0)).isEqualTo("Alice");
    }

    @Test
    void project_unknownColumn_throwsAtConstruction() throws IOException {
        assertThatThrownBy(() -> new Project(List.of("nonexistent"), schema, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("nonexistent");
    }

    @Test
    void project_closePropagatedToChild() throws IOException {
        Transaction tx = db.transactionManager().beginTransaction();
        Project project = new Project(List.of("name"), schema, new SeqScan(engine, tx));
        project.open();
        project.close();
        db.transactionManager().commit(tx);
    }

    private List<Row> drainAll(Project project) {
        List<Row> rows = new ArrayList<>();
        Optional<Row> r;
        while ((r = project.next()).isPresent()) {
            rows.add(r.get());
        }
        return rows;
    }
}
