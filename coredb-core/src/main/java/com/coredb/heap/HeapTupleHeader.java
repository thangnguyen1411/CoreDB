package com.coredb.heap;

import com.coredb.util.BinaryUtil;
import com.coredb.util.Constants;

import java.nio.ByteBuffer;

/**
 * Reads and writes the 20-byte tuple header that precedes every heap tuple.
 * Inspired by PostgreSQL's HeapTupleHeaderData.
 *
 * Binary layout (big-endian, 20 bytes total):
 *   offset  0  t_xmin      4 bytes  inserting transaction id
 *   offset  4  t_xmax      4 bytes  deleting transaction id (0 = live)
 *   offset  8  t_ctid page 4 bytes  pageId of current version
 *   offset 12  t_ctid slot 2 bytes  slotNo of current version
 *   offset 14  t_infomask  2 bytes  status hint flags
 *   offset 16  t_hoff      1 byte   byte offset to first data byte
 *   offset 17  t_natts     2 bytes  number of attributes when tuple was created
 *   offset 19  padding     1 byte   reserved (zero)
 *
 * t_natts enables schema evolution: reading code compares t_natts against the current
 * schema's column count to handle columns added after this tuple was written.
 */
public final class HeapTupleHeader {

    public static final int HEADER_SIZE = 20;

    public static final short XMIN_COMMITTED = 0x01;
    public static final short XMIN_INVALID   = 0x02;
    public static final short XMAX_COMMITTED = 0x04;
    public static final short XMAX_INVALID   = 0x08;

    private static final int OFF_XMIN      = 0;
    private static final int OFF_XMAX      = 4;
    private static final int OFF_CTID_PAGE = 8;
    private static final int OFF_CTID_SLOT = 12;
    private static final int OFF_INFOMASK  = 14;
    private static final int OFF_HOFF      = 16;
    private static final int OFF_NATTS     = 17;

    private int xmin;
    private int xmax;
    private RecordId ctid;
    private short infomask;
    private final byte hoff;
    private final short natts;

    public HeapTupleHeader(RecordId ctid, short natts) {
        this.xmin      = Constants.BOOTSTRAP_XID;
        this.xmax      = Constants.INVALID_XID;
        this.ctid      = ctid;
        this.infomask  = 0;
        this.hoff      = (byte) HEADER_SIZE;
        this.natts     = natts;
    }

    private HeapTupleHeader(int xmin, int xmax, RecordId ctid, short infomask, byte hoff, short natts) {
        this.xmin     = xmin;
        this.xmax     = xmax;
        this.ctid     = ctid;
        this.infomask = infomask;
        this.hoff     = hoff;
        this.natts    = natts;
    }

    public static HeapTupleHeader readFrom(ByteBuffer buf, int offset) {
        int   xmin     = BinaryUtil.readU32(buf, offset + OFF_XMIN);
        int   xmax     = BinaryUtil.readU32(buf, offset + OFF_XMAX);
        int   ctidPage = BinaryUtil.readU32(buf, offset + OFF_CTID_PAGE);
        int   ctidSlot = Short.toUnsignedInt(BinaryUtil.readU16(buf, offset + OFF_CTID_SLOT));
        short infomask = BinaryUtil.readU16(buf, offset + OFF_INFOMASK);
        byte  hoff     = buf.get(offset + OFF_HOFF);
        short natts    = BinaryUtil.readU16(buf, offset + OFF_NATTS);
        return new HeapTupleHeader(xmin, xmax, new RecordId(ctidPage, ctidSlot), infomask, hoff, natts);
    }

    public void writeTo(ByteBuffer buf, int offset) {
        BinaryUtil.writeU32(buf, offset + OFF_XMIN, xmin);
        BinaryUtil.writeU32(buf, offset + OFF_XMAX, xmax);
        BinaryUtil.writeU32(buf, offset + OFF_CTID_PAGE, ctid.pageId());
        BinaryUtil.writeU16(buf, offset + OFF_CTID_SLOT, (short) ctid.slotNo());
        BinaryUtil.writeU16(buf, offset + OFF_INFOMASK, infomask);
        buf.put(offset + OFF_HOFF, hoff);
        BinaryUtil.writeU16(buf, offset + OFF_NATTS, natts);
        buf.put(offset + 19, (byte) 0);
    }

    public int xmin()       { return xmin; }
    public int xmax()       { return xmax; }
    public RecordId ctid()  { return ctid; }
    public short infomask() { return infomask; }
    public int hoff()       { return Byte.toUnsignedInt(hoff); }
    public short natts()    { return natts; }

    public void setXmax(int xmax)        { this.xmax = xmax; }
    public void setCtid(RecordId ctid)   { this.ctid = ctid; }

    public void setInfomaskFlag(short flag)   { infomask |= flag; }
    public void clearInfomaskFlag(short flag) { infomask &= (short) ~flag; }
    public boolean hasInfomaskFlag(short flag){ return (infomask & flag) != 0; }
}
