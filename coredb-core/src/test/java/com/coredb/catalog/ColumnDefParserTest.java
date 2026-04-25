package com.coredb.catalog;

import com.coredb.api.Column;
import com.coredb.api.ColumnType;
import com.coredb.api.Schema;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ColumnDefParserTest {

    @Test
    void parse_validInput_returnsSchemaAndPk() {
        ColumnDefParser.ParsedSchema parsed = ColumnDefParser.parse("id:long name:string age:int pk:id");

        assertThat(parsed.pkColumn()).isEqualTo("id");
        assertThat(parsed.schema().columnCount()).isEqualTo(3);

        // PK column should be NOT NULL, others nullable
        assertThat(parsed.schema().column(0)).isEqualTo(new Column("id", ColumnType.LONG, false));
        assertThat(parsed.schema().column(1)).isEqualTo(new Column("name", ColumnType.STRING, true));
        assertThat(parsed.schema().column(2)).isEqualTo(new Column("age", ColumnType.INT, true));
    }

    @Test
    void parse_allTypes_supported() {
        ColumnDefParser.ParsedSchema parsed = ColumnDefParser.parse(
            "i:int l:long s:string b:bool pk:i"
        );

        assertThat(parsed.schema().column(0).type()).isEqualTo(ColumnType.INT);
        assertThat(parsed.schema().column(1).type()).isEqualTo(ColumnType.LONG);
        assertThat(parsed.schema().column(2).type()).isEqualTo(ColumnType.STRING);
        assertThat(parsed.schema().column(3).type()).isEqualTo(ColumnType.BOOL);
    }

    @Test
    void parse_missingPkDeclaration_returnsNullPk() {
        ColumnDefParser.ParsedSchema parsed = ColumnDefParser.parse("id:long name:string");

        assertThat(parsed.pkColumn()).isNull();
        assertThat(parsed.schema().columnCount()).isEqualTo(2);
        // All columns remain nullable when no PK specified
        assertThat(parsed.schema().column(0).nullable()).isTrue();
        assertThat(parsed.schema().column(1).nullable()).isTrue();
    }

    @Test
    void parse_pkColumnNotFound_throws() {
        assertThatThrownBy(() -> ColumnDefParser.parse("id:long name:string pk:missing"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("PK column 'missing' not found");
    }

    @Test
    void parse_duplicateColumnNames_throws() {
        assertThatThrownBy(() -> ColumnDefParser.parse("id:long id:int pk:id"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Duplicate column name");
    }

    @Test
    void parse_unknownType_throws() {
        assertThatThrownBy(() -> ColumnDefParser.parse("id:unknown pk:id"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unknown column type");
    }

    @Test
    void parse_invalidColumnDefinition_throws() {
        assertThatThrownBy(() -> ColumnDefParser.parse("id pk:id"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("expected name:type");
    }

    @Test
    void formatSchema_outputsExpectedFormat() {
        Schema schema = Schema.of(
            Column.longCol("id"),
            Column.stringCol("name"),
            Column.intCol("age")
        );

        String formatted = ColumnDefParser.formatSchema(schema, "id");

        assertThat(formatted).contains("pk=id");
        assertThat(formatted).contains("id");
        assertThat(formatted).contains("LONG");
        assertThat(formatted).contains("not null");
        assertThat(formatted).contains("name");
        assertThat(formatted).contains("STRING");
        assertThat(formatted).contains("nullable");
    }

    @Test
    void formatSchema_nullPk_outputsNone() {
        Schema schema = Schema.of(
            Column.longCol("id"),
            Column.stringCol("name")
        );

        String formatted = ColumnDefParser.formatSchema(schema, null);

        assertThat(formatted).contains("pk=(none)");
    }
}
