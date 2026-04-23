package com.coredb.heap;

import com.coredb.api.Column;
import com.coredb.api.Row;
import com.coredb.api.Schema;
import com.coredb.util.StorageException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RowSerializerTest {

    private static final Schema SCHEMA = Schema.of(
            Column.intCol("age"),
            Column.longCol("id"),
            Column.stringCol("name"),
            Column.boolCol("active")
    );

    private static Row roundTrip(Row row, Schema schema) {
        byte[] bytes = RowSerializer.serialize(row, schema);
        return RowSerializer.deserialize(bytes, schema, (short) schema.columnCount());
    }

    @Test
    void roundTrip_allTypes_nonNull() {
        Row row = Row.of(30, 42L, "Alice", true);
        assertThat(roundTrip(row, SCHEMA)).isEqualTo(row);
    }

    @Test
    void roundTrip_intColumn() {
        Schema schema = Schema.of(Column.intCol("x"));
        assertThat(roundTrip(Row.of(Integer.MAX_VALUE), schema)).isEqualTo(Row.of(Integer.MAX_VALUE));
    }

    @Test
    void roundTrip_longColumn() {
        Schema schema = Schema.of(Column.longCol("x"));
        assertThat(roundTrip(Row.of(Long.MIN_VALUE), schema)).isEqualTo(Row.of(Long.MIN_VALUE));
    }

    @Test
    void roundTrip_boolColumn_false() {
        Schema schema = Schema.of(Column.boolCol("flag"));
        assertThat(roundTrip(Row.of(false), schema)).isEqualTo(Row.of(false));
    }

    @Test
    void roundTrip_stringColumn_empty() {
        Schema schema = Schema.of(Column.stringCol("s"));
        assertThat(roundTrip(Row.of(""), schema)).isEqualTo(Row.of(""));
    }

    @Test
    void roundTrip_stringColumn_unicode() {
        Schema schema = Schema.of(Column.stringCol("s"));
        assertThat(roundTrip(Row.of("こんにちは"), schema)).isEqualTo(Row.of("こんにちは"));
    }

    @Test
    void roundTrip_nullInt() {
        Row row = Row.of(null, 1L, "Bob", false);
        Row result = roundTrip(row, SCHEMA);

        assertThat(result.get(0)).isNull();
        assertThat(result.getLong(1)).isEqualTo(1L);
        assertThat(result.getString(2)).isEqualTo("Bob");
        assertThat(result.getBoolean(3)).isFalse();
    }

    @Test
    void roundTrip_nullString() {
        Row row = Row.of(5, 2L, null, true);
        Row result = roundTrip(row, SCHEMA);

        assertThat(result.getInt(0)).isEqualTo(5);
        assertThat(result.get(2)).isNull();
    }

    @Test
    void roundTrip_allNulls() {
        Row row = Row.of(null, null, null, null);
        Row result = roundTrip(row, SCHEMA);

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
        Row row = Row.of(null, 1, 2, 3, 4, 5, 6, 7, null);
        Row result = roundTrip(row, wide);

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

    // --- Schema evolution: backward compatibility (old tuple, new schema) ---

    @Test
    void backwardCompat_addedColumns_returnsNullForMissing() {
        Schema v1 = Schema.of(Column.intCol("id"), Column.stringCol("name"), Column.intCol("age"));
        Schema v2 = Schema.of(Column.intCol("id"), Column.stringCol("name"), Column.intCol("age"),
                Column.stringCol("email"), Column.boolCol("active"));

        byte[] bytes = RowSerializer.serialize(Row.of(1, "Alice", 30), v1);
        Row result = RowSerializer.deserialize(bytes, v2, (short) v1.columnCount());

        assertThat(result.getInt(0)).isEqualTo(1);
        assertThat(result.getString(1)).isEqualTo("Alice");
        assertThat(result.getInt(2)).isEqualTo(30);
        assertThat(result.get(3)).isNull();
        assertThat(result.get(4)).isNull();
    }

    @Test
    void backwardCompat_nullInMiddle_newSchemaAddsTrailingNulls() {
        Schema v1 = Schema.of(Column.intCol("id"), Column.stringCol("name"), Column.intCol("age"));
        Schema v2 = Schema.of(Column.intCol("id"), Column.stringCol("name"), Column.intCol("age"),
                Column.stringCol("email"));

        byte[] bytes = RowSerializer.serialize(Row.of(1, null, 30), v1);
        Row result = RowSerializer.deserialize(bytes, v2, (short) v1.columnCount());

        assertThat(result.getInt(0)).isEqualTo(1);
        assertThat(result.get(1)).isNull();
        assertThat(result.getInt(2)).isEqualTo(30);
        assertThat(result.get(3)).isNull();
    }

    @Test
    void backwardCompat_allNullsOldSchema_allNullsNewSchema() {
        Schema v1 = Schema.of(Column.intCol("a"), Column.intCol("b"), Column.intCol("c"));
        Schema v2 = Schema.of(Column.intCol("a"), Column.intCol("b"), Column.intCol("c"),
                Column.intCol("d"), Column.intCol("e"));

        byte[] bytes = RowSerializer.serialize(Row.of(null, null, null), v1);
        Row result = RowSerializer.deserialize(bytes, v2, (short) v1.columnCount());

        for (int i = 0; i < 5; i++) {
            assertThat(result.get(i)).isNull();
        }
    }

    @Test
    void backwardCompat_bitmapSpansMultipleBytes_withNewColumns() {
        Schema v1 = Schema.of(
                Column.intCol("c0"), Column.intCol("c1"), Column.intCol("c2"),
                Column.intCol("c3"), Column.intCol("c4"), Column.intCol("c5"),
                Column.intCol("c6"), Column.intCol("c7"), Column.intCol("c8")
        );
        Schema v2 = Schema.of(
                Column.intCol("c0"), Column.intCol("c1"), Column.intCol("c2"),
                Column.intCol("c3"), Column.intCol("c4"), Column.intCol("c5"),
                Column.intCol("c6"), Column.intCol("c7"), Column.intCol("c8"),
                Column.intCol("c9"), Column.intCol("c10"), Column.intCol("c11")
        );

        Row original = Row.of(null, 1, 2, 3, 4, 5, 6, 7, null);
        byte[] bytes = RowSerializer.serialize(original, v1);
        Row result = RowSerializer.deserialize(bytes, v2, (short) v1.columnCount());

        assertThat(result.get(0)).isNull();
        assertThat(result.getInt(8)).isNull();
        assertThat(result.get(9)).isNull();
        assertThat(result.get(10)).isNull();
        assertThat(result.get(11)).isNull();
    }

    // --- Schema evolution: forward compatibility (new tuple, old schema) ---

    @Test
    void forwardCompat_tooManyColumns_throwsStorageException() {
        Schema v1 = Schema.of(Column.intCol("id"), Column.stringCol("name"), Column.intCol("age"));
        Schema v2 = Schema.of(Column.intCol("id"), Column.stringCol("name"), Column.intCol("age"),
                Column.stringCol("email"), Column.boolCol("active"));

        byte[] bytes = RowSerializer.serialize(Row.of(1, "Alice", 30, "a@b.com", true), v2);

        assertThatThrownBy(() -> RowSerializer.deserialize(bytes, v1, (short) v2.columnCount()))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("5")
                .hasMessageContaining("3");
    }
}
