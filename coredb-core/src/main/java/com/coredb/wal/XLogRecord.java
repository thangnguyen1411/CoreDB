package com.coredb.wal;

import com.coredb.util.CorruptionException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.zip.CRC32C;

/**
 * Immutable WAL record representing a single operation.
 *
 * <p>Header format (40 bytes, big-endian):
 * <pre>
 * Offset  Size  Field
 * 0       8     lsn            byte offset in WAL file
 * 8       4     totalLength    total record length on disk (header + data)
 * 12      4     xid            writing transaction ID
 * 16      8     prevLsn        previous record's LSN (for backward scan)
 * 24      1     resourceManager resource manager ID (HEAP=1, BTREE=2, XLOG=3)
 * 25      1     info           operation code within rmgr; high bit = FPW
 * 26      4     tableOid       file OID this record targets
 * 30      4     pageId         page within that file
 * 34      4     crc            CRC32C over header (excluding crc field) + data
 * 38      2     padding        reserved (zero)
 * </pre>
 *
 * <p>Followed by variable-length data payload.
 *
 * <p>Total header: 40 bytes
 */
public final class XLogRecord {

    // Resource manager IDs
    public static final byte RMGR_HEAP = 1;
    public static final byte RMGR_BTREE = 2;
    public static final byte RMGR_XLOG = 3;

    // Info flag for full-page write
    public static final byte XLOG_FPW = (byte) 0x80;

    // Header offsets
    private static final int OFFSET_LSN = 0;
    private static final int OFFSET_TOTAL_LENGTH = 8;
    private static final int OFFSET_XID = 12;
    private static final int OFFSET_PREV_LSN = 16;
    private static final int OFFSET_RESOURCE_MANAGER = 24;
    private static final int OFFSET_INFO = 25;
    private static final int OFFSET_TABLE_OID = 26;
    private static final int OFFSET_PAGE_ID = 30;
    private static final int OFFSET_CRC = 34;
    private static final int HEADER_SIZE = 40;

    private final long lsn;
    private final int totalLength;
    private final int xid;
    private final long prevLsn;
    private final byte resourceManager;
    private final byte info;
    private final int tableOid;
    private final int pageId;
    private final int crc;
    private final byte[] data;

    private XLogRecord(long lsn, int totalLength, int xid, long prevLsn, byte resourceManager,
                       byte info, int tableOid, int pageId, int crc, byte[] data) {
        this.lsn = lsn;
        this.totalLength = totalLength;
        this.xid = xid;
        this.prevLsn = prevLsn;
        this.resourceManager = resourceManager;
        this.info = info;
        this.tableOid = tableOid;
        this.pageId = pageId;
        this.crc = crc;
        this.data = data;
    }

    /**
     * Creates a new XLogRecord for writing.
     *
     * @param lsn the LSN where this record will be written
     * @param xid the writing transaction ID
     * @param prevLsn the previous record's LSN
     * @param resourceManager the resource manager ID
     * @param info the operation code
     * @param tableOid the target table OID
     * @param pageId the target page ID
     * @param data the payload data
     * @return a new XLogRecord with CRC computed
     */
    public static XLogRecord create(long lsn, int xid, long prevLsn, byte resourceManager,
                                      byte info, int tableOid, int pageId, byte[] data) {
        int totalLength = HEADER_SIZE + data.length;
        int crc = computeCrc(lsn, totalLength, xid, prevLsn, resourceManager, info, tableOid, pageId, data);
        return new XLogRecord(lsn, totalLength, xid, prevLsn, resourceManager, info, tableOid, pageId, crc, data);
    }

    /**
     * Reads an XLogRecord from a ByteBuffer at the given offset.
     *
     * @param buf the buffer to read from
     * @param offset the byte offset where the record starts
     * @return the parsed XLogRecord
     * @throws CorruptionException if the record is malformed or CRC check fails
     */
    public static XLogRecord readFrom(ByteBuffer buf, long offset) throws CorruptionException {
        if (offset > Integer.MAX_VALUE - HEADER_SIZE) {
            throw new CorruptionException("WAL offset too large: " + offset);
        }
        int off = (int) offset;

        if (off + HEADER_SIZE > buf.limit()) {
            return null; // Not enough data for header
        }

        buf.order(ByteOrder.BIG_ENDIAN);

        long lsn = buf.getLong(off + OFFSET_LSN);
        int totalLength = buf.getInt(off + OFFSET_TOTAL_LENGTH);
        int xid = buf.getInt(off + OFFSET_XID);
        long prevLsn = buf.getLong(off + OFFSET_PREV_LSN);
        byte resourceManager = buf.get(off + OFFSET_RESOURCE_MANAGER);
        byte info = buf.get(off + OFFSET_INFO);
        int tableOid = buf.getInt(off + OFFSET_TABLE_OID);
        int pageId = buf.getInt(off + OFFSET_PAGE_ID);
        int crc = buf.getInt(off + OFFSET_CRC);

        if (totalLength < HEADER_SIZE) {
            throw new CorruptionException("Invalid record length: " + totalLength);
        }

        if (off + totalLength > buf.limit()) {
            return null; // Not enough data for full record
        }

        byte[] data = new byte[totalLength - HEADER_SIZE];
        buf.position(off + HEADER_SIZE);
        buf.get(data);

        // Verify CRC (excluding the CRC field itself)
        int computedCrc = computeCrc(lsn, totalLength, xid, prevLsn, resourceManager, info, tableOid, pageId, data);
        if (computedCrc != crc) {
            throw new CorruptionException(
                String.format("CRC mismatch: expected 0x%08X, got 0x%08X", crc, computedCrc));
        }

        return new XLogRecord(lsn, totalLength, xid, prevLsn, resourceManager, info, tableOid, pageId, crc, data);
    }

    private static int computeCrc(long lsn, int totalLength, int xid, long prevLsn, byte resourceManager,
                                   byte info, int tableOid, int pageId, byte[] data) {
        CRC32C crc = new CRC32C();

        ByteBuffer header = ByteBuffer.allocate(HEADER_SIZE - 4); // Exclude CRC field
        header.order(ByteOrder.BIG_ENDIAN);
        header.putLong(lsn);
        header.putInt(totalLength);
        header.putInt(xid);
        header.putLong(prevLsn);
        header.put(resourceManager);
        header.put(info);
        header.putInt(tableOid);
        header.putInt(pageId);

        crc.update(header.array());
        crc.update(data);
        return (int) crc.getValue();
    }

    // Getters
    public long lsn() { return lsn; }
    public int totalLength() { return totalLength; }
    public int xid() { return xid; }
    public long prevLsn() { return prevLsn; }
    public byte resourceManager() { return resourceManager; }
    public byte info() { return info; }
    public int tableOid() { return tableOid; }
    public int pageId() { return pageId; }
    public int crc() { return crc; }
    public byte[] data() { return data.clone(); }

    /**
     * Returns true if this record contains a full-page write image.
     */
    public boolean isFullPageWrite() {
        return (info & XLOG_FPW) != 0;
    }

    /**
     * Returns the resource manager name for display purposes.
     */
    public String resourceManagerName() {
        return switch (resourceManager) {
            case RMGR_HEAP -> "HEAP";
            case RMGR_BTREE -> "BTREE";
            case RMGR_XLOG -> "XLOG";
            default -> "UNKNOWN(" + resourceManager + ")";
        };
    }

    /**
     * Serializes this record into a byte array suitable for writing to WAL.
     */
    public byte[] toBytes() {
        ByteBuffer buf = ByteBuffer.allocate(totalLength);
        buf.order(ByteOrder.BIG_ENDIAN);

        buf.putLong(lsn);
        buf.putInt(totalLength);
        buf.putInt(xid);
        buf.putLong(prevLsn);
        buf.put(resourceManager);
        buf.put(info);
        buf.putInt(tableOid);
        buf.putInt(pageId);
        buf.putInt(crc);
        buf.putShort((short) 0); // padding
        buf.put(data);

        return buf.array();
    }

    @Override
    public String toString() {
        return String.format(
            "XLogRecord{lsn=%d, totalLength=%d, xid=%d, prevLsn=%d, resourceManager=%s, info=0x%02X, " +
            "tableOid=%d, pageId=%d, crc=0x%08X, dataLen=%d}",
            lsn, totalLength, xid, prevLsn, resourceManagerName(), info, tableOid, pageId, crc, data.length);
    }
}
