package com.coredb.query;

import com.coredb.api.Row;
import com.coredb.api.Schema;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class Project implements Operator {

    private final List<String> columns;
    private final int[] columnIndices;
    private final Operator child;

    public Project(List<String> columns, Schema schema, Operator child) {
        if (columns == null || columns.isEmpty()) {
            this.columns = List.of();
            this.columnIndices = new int[0];
        } else {
            int[] indices = new int[columns.size()];
            for (int i = 0; i < columns.size(); i++) {
                int idx = schema.indexOf(columns.get(i));
                if (idx < 0) {
                    throw new IllegalArgumentException("Column not found in schema: " + columns.get(i));
                }
                indices[i] = idx;
            }
            this.columns = List.copyOf(columns);
            this.columnIndices = indices;
        }
        this.child = child;
    }

    @Override
    public void open() {
        child.open();
    }

    @Override
    public Optional<Row> next() {
        Optional<Row> r = child.next();
        if (r.isEmpty()) return Optional.empty();
        if (columnIndices.length == 0) return r;

        Row row = r.get();
        List<Object> projected = new ArrayList<>(columnIndices.length);
        for (int idx : columnIndices) {
            projected.add(row.get(idx));
        }
        return Optional.of(Row.of(projected));
    }

    @Override
    public void close() {
        child.close();
    }
}
