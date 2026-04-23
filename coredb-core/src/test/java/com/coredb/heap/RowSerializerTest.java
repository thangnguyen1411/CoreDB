package com.coredb.heap;

import com.coredb.api.Column;
import com.coredb.api.Row;
import com.coredb.api.Schema;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RowSerializerTest {

    private static final Schema SCHEMA = Schema.of(
            Column.intCol("age"),
            Column.longCol("id"),
            Column.stringCol("name"),
            Column.boolCol("active")
    );

    @Test
    void roundTrip_allTypes_nonNull() {
        Row row = Row.of(30, 42L, "Alice", true);

        byte[] bytes = RowSerializer.serialize(row, SCHEMA);
        Row result = RowSerializer.deserialize(bytes, SCHEMA);

        assertThat(result).isEqualTo(row);
    }

    @Test
    void roundTrip_intColumn() {
        Schema schema = Schema.of(Column.intCol("x"));
        Row row = Row.of(Integer.MAX_VALUE);

        assertThat(RowSerializer.deserialize(RowSerializer.serialize(row, schema), schema)).isEqualTo(row);
    }

    @Test
    void roundTrip_longColumn() {
        Schema schema = Schema.of(Column.longCol("x"));
        Row row = Row.of(Long.MIN_VALUE);

        assertThat(RowSerializer.deserialize(RowSerializer.serialize(row, schema), schema)).isEqualTo(row);
    }

    @Test
    void roundTrip_boolColumn_false() {
        Schema schema = Schema.of(Column.boolCol("flag"));
        Row row = Row.of(false);

        assertThat(RowSerializer.deserialize(RowSerializer.serialize(row, schema), schema)).isEqualTo(row);
    }

    @Test
    void roundTrip_stringColumn_empty() {
        Schema schema = Schema.of(Column.stringCol("s"));
        Row row = Row.of("");

        assertThat(RowSerializer.deserialize(RowSerializer.serialize(row, schema), schema)).isEqualTo(row);
    }

    @Test
    void roundTrip_stringColumn_unicode() {
        Schema schema = Schema.of(Column.stringCol("s"));
        Row row = Row.of("こんにちは");

        assertThat(RowSerializer.deserialize(RowSerializer.serialize(row, schema), schema)).isEqualTo(row);
    }

    @Test
    void roundTrip_nullInt() {
        Row row = Row.of(null, 1L, "Bob", false);

        byte[] bytes = RowSerializer.serialize(row, SCHEMA);
        Row result = RowSerializer.deserialize(bytes, SCHEMA);

        assertThat(result.get(0)).isNull();
        assertThat(result.getLong(1)).isEqualTo(1L);
        assertThat(result.getString(2)).isEqualTo("Bob");
        assertThat(result.getBoolean(3)).isFalse();
    }

    @Test
    void roundTrip_nullString() {
        Row row = Row.of(5, 2L, null, true);

        byte[] bytes = RowSerializer.serialize(row, SCHEMA);
        Row result = RowSerializer.deserialize(bytes, SCHEMA);

        assertThat(result.getInt(0)).isEqualTo(5);
        assertThat(result.get(2)).isNull();
    }

    @Test
    void roundTrip_allNulls() {
        Row row = Row.of(null, null, null, null);

        byte[] bytes = RowSerializer.serialize(row, SCHEMA);
        Row result = RowSerializer.deserialize(bytes, SCHEMA);

        assertThat(result.get(0)).isNull();
        assertThat(result.get(1)).isNull();
        assertThat(result.get(2)).isNull();
        assertThat(result.get(3)).isNull();
    }

    @Test
    void roundTrip_manyColumns_nullBitmapSpansMultipleBytes() {
        Schema wide = Schema.of(
                Column.intCol("c0"), Column.intCol("c1"), Column.intCol("c2"),
                Column.intCol("c3"), Column.intCol("c4"), Column.intCol("c5"),
                Column.intCol("c6"), Column.intCol("c7"), Column.intCol("c8")
        );
        // 9 columns → bitmap needs 2 bytes; null at indices 0 and 8 (cross byte boundary)
        Row row = Row.of(null, 1, 2, 3, 4, 5, 6, 7, null);

        Row result = RowSerializer.deserialize(RowSerializer.serialize(row, wide), wide);

        assertThat(result.get(0)).isNull();
        assertThat(result.getInt(1)).isEqualTo(1);
        assertThat(result.getInt(7)).isEqualTo(7);
        assertThat(result.get(8)).isNull();
    }

    @Test
    void serializedSize_noNulls_isMinimal() {
        Schema schema = Schema.of(Column.intCol("a"), Column.longCol("b"));
        Row row = Row.of(1, 2L);
        // bitmap=1, INT=4, LONG=8 → 13 bytes
        assertThat(RowSerializer.serialize(row, schema)).hasSize(13);
    }
}
