package com.coredb.heap;

import com.coredb.util.BinaryUtil;
import com.coredb.util.Constants;

import java.nio.ByteBuffer;

/**
 * Heap tuple header with PostgreSQL-compatible layout.
 *
 * Variable-length header: 23 fixed bytes + NULL bitmap (ceil(natts/8)).
 *
 * t_infomask2 vs t_infomask distinction:
 * - t_infomask2 (offset 18): Stores natts (column count) in lower 11 bits + tuple update-chain flags.
 *   Used when accessing tuple to determine how many columns to read.
 * - t_infomask (offset 20): General status flags (NULL presence, transaction visibility, locking).
 *   Used by MVCC visibility checks and vacuum.
 *
 * Note: Always allocates t_bits[] bitmap regardless of HEAP_HASNULL.
 * PostgreSQL omits bitmap when no NULLs; we simplify by always including it.
 */
public final class HeapTupleHeader {

    private static final int OFF_XMIN       = 0;
    private static final int OFF_XMAX       = 4;
    private static final int OFF_CID        = 8;
    private static final int OFF_CTID_PAGE  = 12;
    private static final int OFF_CTID_SLOT  = 16;
    private static final int OFF_INFOMASK2  = 18;
    private static final int OFF_INFOMASK   = 20;
    // t_hoff: byte offset from tuple start to first data byte.
    // Used to locate data when header has variable length (due to NULL bitmap).
    // Equals 23 + ceil(natts/8) — computed at construction, stored for fast access.
    private static final int OFF_HOFF       = 22;
    private static final int OFF_BITS       = 23;  // Start of NULL bitmap

    // t_infomask2: lower 11 bits store attribute count (natts)
    private static final short HEAP_NATTS_MASK = 0x07FF;

    // t_infomask flags: general tuple status (bits 0-2)
    public static final short HEAP_HASNULL      = 0x0001;  // tuple has at least one NULL column.
                                                           // Used to quickly check if NULL bitmap needs scanning.
    public static final short HEAP_HASVARWIDTH  = 0x0002;  // has variable-width column (e.g., STRING).
                                                           // Hints that tuple contains off-column data.
    public static final short HEAP_HASEXTERNAL  = 0x0004;  // has TOAST/external data stored outside heap.
                                                           // Requires following TOAST pointer to read full value.

    // t_infomask flags: transaction visibility - inserting transaction (bits 8-9)
    public static final short XMIN_COMMITTED    = (short) 0x0100;  // xmin transaction has committed.
                                                                   // Tuple is visible to transactions started after xmin.
    public static final short XMIN_INVALID      = (short) 0x0200;  // xmin transaction has aborted/rolled back.
                                                                   // Tuple should be treated as never inserted (dead).

    // t_infomask flags: transaction visibility - deleting transaction (bits 10-11)
    public static final short XMAX_COMMITTED    = (short) 0x0400;  // xmax transaction has committed.
                                                                   // Tuple has been deleted and is invisible to new transactions.
    public static final short XMAX_INVALID      = (short) 0x0800;  // xmax transaction has aborted.
                                                                   // Delete was rolled back; tuple is still live.

    // t_infomask flags: locking (bits 12-13)
    // HEAP_XMAX_EXCLUDED = 0x1000;  // xmax is a locking transaction (not delete)
    // HEAP_KEYS_UPDATED  = 0x2000;  // xmax modified key columns (for UNIQUE index checks)

    // Transaction ID that inserted this tuple.
    // Visibility: tuple is invisible to transactions with ID < xmin (until xmin commits).
    private int xmin;

    // Transaction ID that deleted this tuple (0 = not deleted, still live).
    // When set and that transaction commits, tuple becomes invisible to new snapshots.
    private int xmax;

    // Command ID within the inserting transaction.
    // Used for cursor visibility: cursors should not see changes from commands >= their cid.
    private int cid;

    // Current tuple location (pageId, slotNo).
    // Points to newer version if this tuple was updated (HOT chain).
    // For the latest version, ctid points to itself.
    private RecordId ctid;

    // Combined field: lower 11 bits = natts (column count), upper bits = update flags.
    // Used to determine how many columns to read and if tuple is in an update chain.
    private short infomask2;

    // Status flags: NULL presence, transaction visibility hints, locking info.
    // Used by MVCC to check tuple visibility without consulting pg_clog/pg_xact.
    private short infomask;

    // Header size in bytes. Stored on disk to avoid recomputing.
    // Data starts at offset hoff from tuple start.
    private final byte hoff;

    // NULL bitmap: one bit per column. Bit set = column is NULL.
    // Length = ceil(natts/8). Lives immediately after the 23-byte fixed header.
    private final byte[] tBits;

    public HeapTupleHeader(RecordId ctid, short natts, byte[] bitmap) {
        this.xmin      = Constants.BOOTSTRAP_XID;
        this.xmax      = Constants.INVALID_XID;
        this.cid       = 0;
        this.ctid      = ctid;
        this.infomask2 = (short) (natts & HEAP_NATTS_MASK);
        this.infomask  = 0;
        this.hoff      = (byte) computeHeaderSize(natts);
        this.tBits     = bitmap.clone();
    }

    public HeapTupleHeader(RecordId ctid, short natts) {
        this(ctid, natts, new byte[(natts + 7) / 8]);
    }

    private HeapTupleHeader(int xmin, int xmax, int cid, RecordId ctid,
                            short infomask2, short infomask, byte hoff, byte[] tBits) {
        this.xmin      = xmin;
        this.xmax      = xmax;
        this.cid       = cid;
        this.ctid      = ctid;
        this.infomask2 = infomask2;
        this.infomask  = infomask;
        this.hoff      = hoff;
        this.tBits     = tBits;
    }

    public static HeapTupleHeader readFrom(ByteBuffer buf, int offset) {
        int   xmin      = BinaryUtil.readU32(buf, offset + OFF_XMIN);
        int   xmax      = BinaryUtil.readU32(buf, offset + OFF_XMAX);
        int   cid       = BinaryUtil.readU32(buf, offset + OFF_CID);
        int   ctidPage  = BinaryUtil.readU32(buf, offset + OFF_CTID_PAGE);
        int   ctidSlot  = Short.toUnsignedInt(BinaryUtil.readU16(buf, offset + OFF_CTID_SLOT));
        short infomask2 = BinaryUtil.readU16(buf, offset + OFF_INFOMASK2);
        short infomask  = BinaryUtil.readU16(buf, offset + OFF_INFOMASK);
        byte  hoff      = buf.get(offset + OFF_HOFF);

        int bitmapLen = Math.max(0, Byte.toUnsignedInt(hoff) - OFF_BITS);
        byte[] tBits = new byte[bitmapLen];
        for (int i = 0; i < bitmapLen; i++) {
            tBits[i] = buf.get(offset + OFF_BITS + i);
        }

        return new HeapTupleHeader(xmin, xmax, cid, new RecordId(ctidPage, ctidSlot),
                infomask2, infomask, hoff, tBits);
    }

    public void writeTo(ByteBuffer buf, int offset) {
        BinaryUtil.writeU32(buf, offset + OFF_XMIN, xmin);
        BinaryUtil.writeU32(buf, offset + OFF_XMAX, xmax);
        BinaryUtil.writeU32(buf, offset + OFF_CID, cid);
        BinaryUtil.writeU32(buf, offset + OFF_CTID_PAGE, ctid.pageId());
        BinaryUtil.writeU16(buf, offset + OFF_CTID_SLOT, (short) ctid.slotNo());
        BinaryUtil.writeU16(buf, offset + OFF_INFOMASK2, infomask2);
        BinaryUtil.writeU16(buf, offset + OFF_INFOMASK, infomask);
        buf.put(offset + OFF_HOFF, hoff);
        for (int i = 0; i < tBits.length; i++) {
            buf.put(offset + OFF_BITS + i, tBits[i]);
        }
    }

    public static int computeHeaderSize(int natts) {
        return 23 + (natts + 7) / 8;
    }

    public int headerSize()     { return Byte.toUnsignedInt(hoff); }
    public int xmin()           { return xmin; }
    public int xmax()           { return xmax; }
    public int cid()            { return cid; }
    public RecordId ctid()      { return ctid; }
    public short infomask()     { return infomask; }
    public short infomask2()    { return infomask2; }
    public short natts()        { return (short) (infomask2 & HEAP_NATTS_MASK); }
    public int hoff()           { return Byte.toUnsignedInt(hoff); }

    public boolean isNull(int col) {
        return (tBits[col / 8] & (1 << (col % 8))) != 0;
    }

    public void setXmax(int xmax)       { this.xmax = xmax; }
    public void setCtid(RecordId ctid)  { this.ctid = ctid; }

    public void setInfomaskFlag(short flag)    { infomask |= flag; }
    public void clearInfomaskFlag(short flag)  { infomask &= (short) ~flag; }
    public boolean hasInfomaskFlag(short flag) { return (infomask & flag) != 0; }
}
