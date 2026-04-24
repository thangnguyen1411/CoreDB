package com.coredb.catalog;

import com.coredb.api.Column;
import com.coredb.api.ColumnType;
import com.coredb.api.Schema;

import java.util.ArrayList;
import java.util.List;

public final class ColumnDefParser {

    public record ParsedSchema(Schema schema, String pkColumn) {}

    public static ParsedSchema parse(String input) {
        String[] parts = input.trim().split("\\s+");

        List<Column> columns = new ArrayList<>();
        String pkColumn = null;

        for (String part : parts) {
            if (part.startsWith("pk:")) {
                pkColumn = part.substring(3);
                continue;
            }

            Column column = parseColumn(part);
            columns.add(column);
        }

        if (pkColumn == null) {
            throw new IllegalArgumentException("Missing pk:<column> declaration");
        }

        Schema schema = Schema.of(columns);

        Column pkCol = schema.column(pkColumn);
        if (pkCol == null) {
            throw new IllegalArgumentException("PK column '" + pkColumn + "' not found in column list");
        }

        // Rebuild columns with PK having nullable=false
        List<Column> columnsWithPkNotNull = new ArrayList<>();
        for (Column col : columns) {
            if (col.name().equals(pkColumn)) {
                columnsWithPkNotNull.add(new Column(col.name(), col.type(), false));
            } else {
                columnsWithPkNotNull.add(col);
            }
        }
        Schema schemaWithPkNotNull = Schema.of(columnsWithPkNotNull);

        return new ParsedSchema(schemaWithPkNotNull, pkColumn);
    }

    private static Column parseColumn(String def) {
        int colonIdx = def.indexOf(':');
        if (colonIdx < 0) {
            throw new IllegalArgumentException("Invalid column definition: " + def + " (expected name:type)");
        }

        String name = def.substring(0, colonIdx);
        String typeStr = def.substring(colonIdx + 1).toLowerCase();

        ColumnType type = switch (typeStr) {
            case "int" -> ColumnType.INT;
            case "long" -> ColumnType.LONG;
            case "string" -> ColumnType.STRING;
            case "bool" -> ColumnType.BOOL;
            default -> throw new IllegalArgumentException("Unknown column type: " + typeStr);
        };

        return new Column(name, type, true);
    }

    public static String formatSchema(Schema schema, String pkColumn) {
        StringBuilder sb = new StringBuilder();
        sb.append("pk=").append(pkColumn).append("\n");

        for (int i = 0; i < schema.columnCount(); i++) {
            Column col = schema.column(i);
            boolean isPk = col.name().equals(pkColumn);
            sb.append(String.format("%-5s %-7s %s",
                col.name(),
                col.type(),
                isPk ? "not null" : (col.nullable() ? "nullable" : "not null")));
            if (i < schema.columnCount() - 1) {
                sb.append("\n");
            }
        }

        return sb.toString();
    }
}
