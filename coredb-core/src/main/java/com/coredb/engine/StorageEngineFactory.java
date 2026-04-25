package com.coredb.engine;

import com.coredb.api.CoreDBConfig;
import com.coredb.config.EngineType;

/**
 * Factory for creating storage engine instances.
 *
 * <p>Selects the appropriate engine implementation based on the
 * {@link EngineType} specified in the configuration.</p>
 */
public final class StorageEngineFactory {

    private StorageEngineFactory() {
        // Utility class
    }

    /**
     * Creates a new storage engine instance of the specified type.
     *
     * @param type   the engine type (BTREE or LSM)
     * @param config the CoreDB configuration
     * @return a new storage engine instance
     * @throws UnsupportedOperationException if the engine type is not yet implemented
     */
    public static StorageEngine create(EngineType type, CoreDBConfig config) {
        return switch (type) {
            case BTREE -> new BTreeStorageEngine(config);
            case LSM -> throw new UnsupportedOperationException("LSM storage engine not yet implemented");
        };
    }
}
