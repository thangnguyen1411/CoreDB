package com.coredb.wal;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Resource manager for XLOG operations.
 *
 * <p>Handles redo of CHECKPOINT and other control-file related operations.</p>
 *
 * <p>Note: Most XLOG operations affect the control file rather than page data.
 * During recovery, the RecoveryManager handles control file updates directly,
 * so this rmgr's redo() method is typically a no-op for control operations.</p>
 */
public final class XLogResourceManager implements ResourceManager {

    // Operation codes
    public static final byte CHECKPOINT = 0x20;

    @Override
    public byte rmgrId() {
        return XLogRecord.RMGR_XLOG;
    }

    @Override
    public void redo(XLogRecord record, ByteBuffer targetPage) throws IOException {
        // XLOG operations typically don't modify pages directly.
        // CHECKPOINT records affect the control file, which is handled
        // separately by RecoveryManager.
        //
        // If we were to support more complex XLOG operations (like timeline
        // switches), they would be implemented here.

        switch (record.info()) {
            case CHECKPOINT:
                // No-op - control file is updated by RecoveryManager separately
                break;
            default:
                throw new UnsupportedOperationException(
                    "Unknown xlog operation: 0x" + Integer.toHexString(record.info() & 0xFF));
        }
    }

    /**
     * Returns the human-readable name for an XLOG operation.
     *
     * @param info the operation code
     * @return the operation name
     */
    public static String operationName(byte info) {
        return switch (info) {
            case CHECKPOINT -> "CHECKPOINT";
            default -> "UNKNOWN(0x" + Integer.toHexString(info & 0xFF) + ")";
        };
    }
}
