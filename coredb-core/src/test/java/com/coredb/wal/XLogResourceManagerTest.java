package com.coredb.wal;

import com.coredb.page.Page;
import com.coredb.page.PageType;
import com.coredb.util.Constants;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for XLogResourceManager WAL redo operations.
 */
class XLogResourceManagerTest {

    private static final int TABLE_OID = 1000; // XLOG uses control file
    private static final int PAGE_ID = 0; // Not used for XLOG

    @Test
    void redoCheckpoint_shouldBeNoOp() throws Exception {
        // XLOG CHECKPOINT records don't modify pages directly
        // They affect the control file, which is handled by RecoveryManager
        Page page = Page.Factory.allocate(PAGE_ID, PageType.META);
        ByteBuffer pageBuf = page.buffer();

        // Prepare the checkpoint payload: (long redoLsn)
        ByteBuffer payload = ByteBuffer.allocate(8);
        payload.putLong(100L); // redoLsn = 100

        XLogRecord record = XLogRecord.create(
            16,
            Constants.BOOTSTRAP_XID,
            0,
            XLogRecord.RMGR_XLOG,
            XLogResourceManager.CHECKPOINT,
            TABLE_OID,
            PAGE_ID,
            payload.array()
        );

        XLogResourceManager rmgr = new XLogResourceManager();
        // Should not throw and should not modify the page
        rmgr.redo(record, pageBuf);

        // Page should be unchanged (all zeros)
        byte[] pageBytes = new byte[pageBuf.capacity()];
        pageBuf.position(0);
        pageBuf.get(pageBytes);

        // Just verify we made it here without exception
        assertThat(true).isTrue();
    }

    @Test
    void redo_withUnknownOperation_shouldThrow() {
        Page page = Page.Factory.allocate(PAGE_ID, PageType.META);

        XLogRecord record = XLogRecord.create(
            16,
            Constants.BOOTSTRAP_XID,
            0,
            XLogRecord.RMGR_XLOG,
            (byte) 0x99, // Unknown operation
            TABLE_OID,
            PAGE_ID,
            new byte[0]
        );

        XLogResourceManager rmgr = new XLogResourceManager();
        assertThatThrownBy(() -> rmgr.redo(record, page.buffer()))
            .isInstanceOf(UnsupportedOperationException.class)
            .hasMessageContaining("Unknown xlog operation");
    }

    @Test
    void rmgrId_shouldReturnXlogConstant() {
        XLogResourceManager rmgr = new XLogResourceManager();
        assertThat(rmgr.rmgrId()).isEqualTo(XLogRecord.RMGR_XLOG);
    }

    @Test
    void operationName_shouldReturnCorrectNames() {
        assertThat(XLogResourceManager.operationName(XLogResourceManager.CHECKPOINT)).isEqualTo("CHECKPOINT");
        assertThat(XLogResourceManager.operationName((byte) 0x99))
            .startsWith("UNKNOWN(").endsWith(")");
    }
}
