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

    public static final byte CHECKPOINT = 0x20;
    public static final byte XACT_COMMIT = 0x30;
    public static final byte XACT_ABORT = 0x31;

    @Override
    public byte getResourceManagerId() {
        return XLogRecord.RMGR_XLOG;
    }

    @Override
    public void redo(XLogRecord record, ByteBuffer targetPage) throws IOException {
        switch (record.info()) {
            case CHECKPOINT:
            case XACT_COMMIT:
            case XACT_ABORT:
                break;
            default:
                throw new UnsupportedOperationException(
                    "Unknown xlog operation: 0x" + Integer.toHexString(record.info() & 0xFF));
        }
    }

    public static String operationName(byte info) {
        return switch (info) {
            case CHECKPOINT -> "CHECKPOINT";
            case XACT_COMMIT -> "XACT_COMMIT";
            case XACT_ABORT -> "XACT_ABORT";
            default -> "UNKNOWN(0x" + Integer.toHexString(info & 0xFF) + ")";
        };
    }
}
