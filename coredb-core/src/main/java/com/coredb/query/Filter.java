package com.coredb.query;

import com.coredb.api.Row;
import java.util.Optional;

public final class Filter implements Operator {

    private final Predicate predicate;
    private final Operator child;

    public Filter(Predicate predicate, Operator child) {
        this.predicate = predicate;
        this.child = child;
    }

    @Override
    public void open() {
        child.open();
    }

    @Override
    public Optional<Row> next() {
        while (true) {
            Optional<Row> r = child.next();
            if (r.isEmpty()) return Optional.empty();
            if (predicate.test(r.get())) return r;
        }
    }

    @Override
    public void close() {
        child.close();
    }
}
