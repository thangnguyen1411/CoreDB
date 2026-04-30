package com.coredb.query;

import com.coredb.api.Row;
import com.coredb.api.Schema;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class Project implements Operator {

    private final List<String> columns;
    private final Schema schema;
    private final Operator child;

    public Project(List<String> columns, Schema schema, Operator child) {
        if (columns != null) {
            for (String col : columns) {
                if (schema.indexOf(col) < 0) {
                    throw new IllegalArgumentException("Column not found in schema: " + col);
                }
            }
        }
        this.columns = (columns == null || columns.isEmpty()) ? List.of() : List.copyOf(columns);
        this.schema = schema;
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
        if (columns.isEmpty()) return r;

        Row row = r.get();
        List<Object> projected = new ArrayList<>(columns.size());
        for (String col : columns) {
            projected.add(row.get(schema.indexOf(col)));
        }
        return Optional.of(Row.of(projected));
    }

    @Override
    public void close() {
        child.close();
    }
}
