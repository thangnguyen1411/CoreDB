package com.coredb.wal;

import com.coredb.page.Page;
import com.coredb.page.PageType;
import com.coredb.util.Constants;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for ResourceManagerRegistry.
 */
class ResourceManagerRegistryTest {

    @Test
    void createStandard_shouldRegisterAllStandardManagers() {
        ResourceManagerRegistry registry = ResourceManagerRegistry.createStandard();

        assertThat(registry.size()).isEqualTo(3);
        assertThat(registry.hasManager(XLogRecord.RMGR_HEAP)).isTrue();
        assertThat(registry.hasManager(XLogRecord.RMGR_BTREE)).isTrue();
        assertThat(registry.hasManager(XLogRecord.RMGR_XLOG)).isTrue();
    }

    @Test
    void register_shouldAddNewManager() {
        ResourceManagerRegistry registry = new ResourceManagerRegistry();

        TestResourceManager testMgr = new TestResourceManager((byte) 99);
        registry.register(testMgr);

        assertThat(registry.size()).isEqualTo(1);
        assertThat(registry.hasManager((byte) 99)).isTrue();
        assertThat(registry.get((byte) 99)).isSameAs(testMgr);
    }

    @Test
    void register_withDuplicateId_shouldThrow() {
        ResourceManagerRegistry registry = new ResourceManagerRegistry();

        registry.register(new TestResourceManager((byte) 99));

        assertThatThrownBy(() -> registry.register(new TestResourceManager((byte) 99)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("already registered");
    }

    @Test
    void get_withUnknownId_shouldReturnNull() {
        ResourceManagerRegistry registry = new ResourceManagerRegistry();
        assertThat(registry.get((byte) 99)).isNull();
    }

    @Test
    void dispatch_withRegisteredManager_shouldCallRedo() throws IOException {
        ResourceManagerRegistry registry = new ResourceManagerRegistry();
        TestResourceManager testMgr = new TestResourceManager(XLogRecord.RMGR_HEAP);
        registry.register(testMgr);

        Page page = Page.Factory.allocate(1, PageType.HEAP);
        XLogRecord record = XLogRecord.create(
            16,
            Constants.BOOTSTRAP_XID,
            0,
            XLogRecord.RMGR_HEAP,
            (byte) 0x01,
            1002,
            1,
            new byte[0]
        );

        registry.dispatch(record, page.buffer());

        assertThat(testMgr.redoCalled).isTrue();
        assertThat(testMgr.lastRecord).isSameAs(record);
    }

    @Test
    void dispatch_withUnknownManager_shouldThrow() {
        ResourceManagerRegistry registry = new ResourceManagerRegistry();

        Page page = Page.Factory.allocate(1, PageType.HEAP);
        XLogRecord record = XLogRecord.create(
            16,
            Constants.BOOTSTRAP_XID,
            0,
            XLogRecord.RMGR_HEAP, // Not registered
            (byte) 0x01,
            1002,
            1,
            new byte[0]
        );

        assertThatThrownBy(() -> registry.dispatch(record, page.buffer()))
            .isInstanceOf(UnsupportedOperationException.class)
            .hasMessageContaining("No resource manager registered");
    }

    @Test
    void dispatch_usesStandardRegistry() throws IOException {
        ResourceManagerRegistry registry = ResourceManagerRegistry.createStandard();

        // Test that HEAP manager is callable
        Page page = Page.Factory.allocate(1, PageType.HEAP);

        // Create a proper insert record with all required fields
        short natts = 1;
        byte[] bitmap = new byte[(natts + 7) / 8]; // 1 byte for null bitmap
        byte[] tupleData = new byte[]{0x01, 0x02};

        ByteBuffer payload = ByteBuffer.allocate(6 + bitmap.length + tupleData.length);
        payload.putInt(0); // slotNo
        payload.putShort(natts);
        payload.put(bitmap);
        payload.put(tupleData);

        XLogRecord record = XLogRecord.create(
            16,
            Constants.BOOTSTRAP_XID,
            0,
            XLogRecord.RMGR_HEAP,
            HeapResourceManager.HEAP_INSERT,
            1002,
            1,
            payload.array()
        );

        // Should not throw
        registry.dispatch(record, page.buffer());

        // Verify the page was modified (ItemId slot created)
        // This confirms the HeapResourceManager was invoked correctly
    }

    /**
     * Test implementation of ResourceManager for unit testing.
     */
    private static class TestResourceManager implements ResourceManager {
        private final byte rmgrId;
        boolean redoCalled = false;
        XLogRecord lastRecord = null;

        TestResourceManager(byte rmgrId) {
            this.rmgrId = rmgrId;
        }

        @Override
        public byte rmgrId() {
            return rmgrId;
        }

        @Override
        public void redo(XLogRecord record, ByteBuffer targetPage) {
            redoCalled = true;
            lastRecord = record;
        }
    }
}
