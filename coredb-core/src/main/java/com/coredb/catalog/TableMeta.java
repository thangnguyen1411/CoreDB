package com.coredb.catalog;

import com.coredb.api.Schema;
import com.coredb.config.EngineType;

public record TableMeta(
    int oid,
    String name,
    Schema schema,
    String pkColumn,
    EngineType engineType
) {}
