package com.coredb.query;

import com.coredb.api.Row;
import java.util.Optional;

public sealed interface Operator extends AutoCloseable
        permits SeqScan, IndexScan, Filter, Project {

    void open();

    Optional<Row> next();

    @Override
    void close();
}
