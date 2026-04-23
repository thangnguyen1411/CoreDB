package com.coredb.api;

import java.util.List;

public final class Schema {

    private final List<Column> columns;

    private Schema(List<Column> columns) {
        this.columns = List.copyOf(columns);
    }

    public static Schema of(Column... columns) {
        return new Schema(List.of(columns));
    }

    public static Schema of(List<Column> columns) {
        return new Schema(columns);
    }

    public List<Column> columns() {
        return columns;
    }

    public int columnCount() {
        return columns.size();
    }

    public Column column(int index) {
        return columns.get(index);
    }
}
