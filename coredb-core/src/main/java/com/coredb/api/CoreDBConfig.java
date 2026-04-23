package com.coredb.api;

import com.coredb.config.EngineType;
import com.coredb.util.Constants;

public final class CoreDBConfig {

    private final EngineType engineType;
    private final int pageSize;

    private CoreDBConfig(Builder builder) {
        this.engineType = builder.engineType;
        this.pageSize = builder.pageSize;
    }

    public EngineType engineType() {
        return engineType;
    }

    public int pageSize() {
        return pageSize;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static CoreDBConfig defaults() {
        return builder().build();
    }

    public static final class Builder {

        private EngineType engineType = EngineType.BTREE;
        private int pageSize = Constants.PAGE_SIZE;

        public Builder engineType(EngineType engineType) {
            this.engineType = engineType;
            return this;
        }

        public Builder pageSize(int pageSize) {
            this.pageSize = pageSize;
            return this;
        }

        public CoreDBConfig build() {
            return new CoreDBConfig(this);
        }
    }
}
