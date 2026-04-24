package com.coredb.api;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SchemaTest {

    @Test
    void columnByName_returnsCorrectColumn() {
        Schema schema = Schema.of(
            Column.longCol("id"),
            Column.stringCol("name"),
            Column.intCol("age")
        );

        assertThat(schema.column("id")).isEqualTo(Column.longCol("id"));
        assertThat(schema.column("name")).isEqualTo(Column.stringCol("name"));
        assertThat(schema.column("age")).isEqualTo(Column.intCol("age"));
    }

    @Test
    void columnByName_returnsNullForMissing() {
        Schema schema = Schema.of(Column.longCol("id"));

        assertThat(schema.column("missing")).isNull();
    }

    @Test
    void indexOf_returnsCorrectPosition() {
        Schema schema = Schema.of(
            Column.longCol("id"),
            Column.stringCol("name"),
            Column.intCol("age")
        );

        assertThat(schema.indexOf("id")).isEqualTo(0);
        assertThat(schema.indexOf("name")).isEqualTo(1);
        assertThat(schema.indexOf("age")).isEqualTo(2);
    }

    @Test
    void indexOf_returnsMinusOneForMissing() {
        Schema schema = Schema.of(Column.longCol("id"));

        assertThat(schema.indexOf("missing")).isEqualTo(-1);
    }

    @Test
    void of_withDuplicateNames_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> Schema.of(
            Column.longCol("id"),
            Column.intCol("id")
        )).isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Duplicate column name: id");
    }

    @Test
    void of_withDuplicateNamesInList_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> Schema.of(List.of(
            Column.longCol("id"),
            Column.stringCol("name"),
            Column.intCol("id")
        ))).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Duplicate column name: id");
    }
}
