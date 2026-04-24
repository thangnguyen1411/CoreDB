package com.coredb.catalog;

import com.coredb.api.Column;
import com.coredb.api.Schema;
import com.coredb.config.EngineType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TableMetaTest {

    @Test
    void recordStoresAllFields() {
        Schema schema = Schema.of(
            Column.longCol("id"),
            Column.stringCol("name")
        );

        TableMeta meta = new TableMeta(1002, "users", schema, "id", EngineType.BTREE);

        assertThat(meta.oid()).isEqualTo(1002);
        assertThat(meta.name()).isEqualTo("users");
        assertThat(meta.schema()).isEqualTo(schema);
        assertThat(meta.pkColumn()).isEqualTo("id");
        assertThat(meta.engineType()).isEqualTo(EngineType.BTREE);
    }

    @Test
    void recordEqualityWorks() {
        Schema schema1 = Schema.of(Column.longCol("id"));
        Schema schema2 = Schema.of(Column.longCol("id"));

        TableMeta meta1 = new TableMeta(1002, "users", schema1, "id", EngineType.BTREE);
        TableMeta meta2 = new TableMeta(1002, "users", schema2, "id", EngineType.BTREE);
        TableMeta meta3 = new TableMeta(1003, "orders", schema1, "id", EngineType.BTREE);

        assertThat(meta1).isEqualTo(meta2);
        assertThat(meta1).isNotEqualTo(meta3);
    }
}
