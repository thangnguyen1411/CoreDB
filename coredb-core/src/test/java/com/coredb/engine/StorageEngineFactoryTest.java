package com.coredb.engine;

import com.coredb.api.CoreDBConfig;
import com.coredb.config.EngineType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link StorageEngineFactory}.
 *
 * <p>Verifies the factory returns correct engine types and
 * throws appropriate exceptions for unimplemented engines.</p>
 */
class StorageEngineFactoryTest {

    @Test
    void create_withBtreeEngine_returnsBTreeStorageEngine() {
        CoreDBConfig config = CoreDBConfig.builder()
            .engineType(EngineType.BTREE)
            .build();

        StorageEngine engine = StorageEngineFactory.create(EngineType.BTREE, config);

        assertThat(engine).isInstanceOf(BTreeStorageEngine.class);
    }

    @Test
    void create_withLsmEngine_throwsUnsupportedOperationException() {
        CoreDBConfig config = CoreDBConfig.builder()
            .engineType(EngineType.LSM)
            .build();

        assertThatThrownBy(() -> StorageEngineFactory.create(EngineType.LSM, config))
            .isInstanceOf(UnsupportedOperationException.class)
            .hasMessageContaining("LSM storage engine not yet implemented");
    }

    @Test
    void btreeEngine_implementsStorageEngineInterface() {
        CoreDBConfig config = CoreDBConfig.builder()
            .engineType(EngineType.BTREE)
            .build();

        StorageEngine engine = StorageEngineFactory.create(EngineType.BTREE, config);

        assertThat(engine).isInstanceOf(StorageEngine.class);
        assertThat(engine).isInstanceOf(AutoCloseable.class);
    }
}
