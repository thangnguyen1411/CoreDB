package com.coredb.api;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class Schema {

    private final List<Column> columns;

    private Schema(List<Column> columns) {
        this.columns = List.copyOf(columns);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Schema schema = (Schema) o;
        return Objects.equals(columns, schema.columns);
    }

    @Override
    public int hashCode() {
        return Objects.hash(columns);
    }

    @Override
    public String toString() {
        return "Schema{" + "columns=" + columns + '}';
    }

    public static Schema of(Column... columns) {
        return of(List.of(columns));
    }

    public static Schema of(List<Column> columns) {
        validateNoDuplicateNames(columns);
        return new Schema(columns);
    }

    private static void validateNoDuplicateNames(List<Column> columns) {
        Set<String> names = new HashSet<>();
        for (Column col : columns) {
            if (!names.add(col.name())) {
                throw new IllegalArgumentException("Duplicate column name: " + col.name());
            }
        }
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

    public Column column(String name) {
        for (Column col : columns) {
            if (col.name().equals(name)) {
                return col;
            }
        }
        return null;
    }

    public int indexOf(String name) {
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).name().equals(name)) {
                return i;
            }
        }
        return -1;
    }
}
