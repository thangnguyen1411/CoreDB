package com.coredb.heap;

import com.coredb.util.BinaryUtil;
import com.coredb.util.Constants;

import java.nio.ByteBuffer;

// Always allocates ceil(natts/8) bitmap bytes even when no columns are NULL;
// PostgreSQL omits t_bits[] when HEAP_HASNULL is clear.
public final class HeapTupleHeader {

    private static final int OFF_XMIN       = 0;
    private static final int OFF_XMAX       = 4;
    private static final int OFF_CID        = 8;
    private static final int OFF_CTID_PAGE  = 12;
    private static final int OFF_CTID_SLOT  = 16;
    private static final int OFF_INFOMASK2  = 18;
    private static final int OFF_INFOMASK   = 20;
    private static final int OFF_HOFF       = 22;
    private static final int OFF_BITS       = 23;

    private static final short HEAP_NATTS_MASK = 0x07FF;

    public static final short HEAP_HASNULL      = 0x0001;
    public static final short HEAP_HASVARWIDTH  = 0x0002;
    public static final short HEAP_HASEXTERNAL  = 0x0004;
    public static final short XMIN_COMMITTED    = (short) 0x0100;
    public static final short XMIN_INVALID      = (short) 0x0200;
    public static final short XMAX_COMMITTED    = (short) 0x0400;
    public static final short XMAX_INVALID      = (short) 0x0800;

    private int xmin;
    private int xmax;
    private int cid;
    private RecordId ctid;
    private short infomask2;
    private short infomask;
    private final byte hoff;
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
