package com.coredb.api;

import com.coredb.config.EngineType;
import com.coredb.util.Constants;

public final class CoreDBConfig {

    private final EngineType engineType;
    private final int pageSize;
    private final int bufferPoolSize;

    private CoreDBConfig(Builder builder) {
        this.engineType = builder.engineType;
        this.pageSize = builder.pageSize;
        this.bufferPoolSize = builder.bufferPoolSize;
    }

    public EngineType engineType() {
        return engineType;
    }

    public int pageSize() {
        return pageSize;
    }

    public int bufferPoolSize() {
        return bufferPoolSize;
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
        private int bufferPoolSize = 1024; // Default 1024 frames = 8MB

        public Builder engineType(EngineType engineType) {
            this.engineType = engineType;
            return this;
        }

        public Builder pageSize(int pageSize) {
            this.pageSize = pageSize;
            return this;
        }

        public Builder bufferPoolSize(int bufferPoolSize) {
            this.bufferPoolSize = bufferPoolSize;
            return this;
        }

        public CoreDBConfig build() {
            return new CoreDBConfig(this);
        }
    }
}
