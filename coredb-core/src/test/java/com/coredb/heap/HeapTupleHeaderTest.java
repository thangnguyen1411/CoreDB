package com.coredb.heap;

import com.coredb.util.Constants;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.assertj.core.api.Assertions.assertThat;

class HeapTupleHeaderTest {

    private static ByteBuffer freshBuffer(int capacity) {
        return ByteBuffer.allocate(capacity).order(ByteOrder.BIG_ENDIAN);
    }

    @Test
    void newHeader_hasBootstrapXminAndZeroXmax() {
        var rid = new RecordId(3, 7);
        var h = new HeapTupleHeader(rid, (short) 4);

        assertThat(h.xmin()).isEqualTo(Constants.BOOTSTRAP_XID);
        assertThat(h.xmax()).isEqualTo(Constants.INVALID_XID);
        assertThat(h.cid()).isEqualTo(0);
        assertThat(h.ctid()).isEqualTo(rid);
        assertThat(h.infomask()).isEqualTo((short) 0);
        assertThat(h.natts()).isEqualTo((short) 4);
        assertThat(h.headerSize()).isEqualTo(HeapTupleHeader.computeHeaderSize(4));
    }

    @Test
    void computeHeaderSize_correctForVariousNatts() {
        assertThat(HeapTupleHeader.computeHeaderSize(0)).isEqualTo(23);  // 0 bitmap bytes
        assertThat(HeapTupleHeader.computeHeaderSize(1)).isEqualTo(24);  // 1 bitmap byte
        assertThat(HeapTupleHeader.computeHeaderSize(8)).isEqualTo(24);  // 1 bitmap byte
        assertThat(HeapTupleHeader.computeHeaderSize(9)).isEqualTo(25);  // 2 bitmap bytes
        assertThat(HeapTupleHeader.computeHeaderSize(16)).isEqualTo(25); // 2 bitmap bytes
        assertThat(HeapTupleHeader.computeHeaderSize(17)).isEqualTo(26); // 3 bitmap bytes
    }

    @Test
    void hoff_pointsToDataStart() {
        var h4 = new HeapTupleHeader(new RecordId(0, 0), (short) 4);
        assertThat(h4.hoff()).isEqualTo(24); // 23 + ceil(4/8)=1

        var h9 = new HeapTupleHeader(new RecordId(0, 0), (short) 9);
        assertThat(h9.hoff()).isEqualTo(25); // 23 + ceil(9/8)=2
    }

    @Test
    void writeThenRead_roundTripsAllFields() {
        var rid = new RecordId(5, 2);
        byte[] bitmap = {(byte) 0b00000010}; // column 1 is null
        var original = new HeapTupleHeader(rid, (short) 7, bitmap);
        original.setXmax(42);
        original.setInfomaskFlag(HeapTupleHeader.XMIN_COMMITTED);

        ByteBuffer buf = freshBuffer(64);
        original.writeTo(buf, 0);

        var decoded = HeapTupleHeader.readFrom(buf, 0);

        assertThat(decoded.xmin()).isEqualTo(Constants.BOOTSTRAP_XID);
        assertThat(decoded.xmax()).isEqualTo(42);
        assertThat(decoded.cid()).isEqualTo(0);
        assertThat(decoded.ctid()).isEqualTo(rid);
        assertThat(decoded.natts()).isEqualTo((short) 7);
        assertThat(decoded.hoff()).isEqualTo(HeapTupleHeader.computeHeaderSize(7));
        assertThat(decoded.hasInfomaskFlag(HeapTupleHeader.XMIN_COMMITTED)).isTrue();
        assertThat(decoded.hasInfomaskFlag(HeapTupleHeader.XMAX_COMMITTED)).isFalse();
    }

    @Test
    void writeThenRead_atNonZeroOffset_roundTrips() {
        int offset = 12;
        var rid = new RecordId(1, 0);
        var original = new HeapTupleHeader(rid, (short) 3);

        ByteBuffer buf = freshBuffer(128);
        original.writeTo(buf, offset);

        var decoded = HeapTupleHeader.readFrom(buf, offset);

        assertThat(decoded.xmin()).isEqualTo(Constants.BOOTSTRAP_XID);
        assertThat(decoded.xmax()).isEqualTo(Constants.INVALID_XID);
        assertThat(decoded.ctid()).isEqualTo(rid);
        assertThat(decoded.natts()).isEqualTo((short) 3);
    }

    @Test
    void nullBitmap_roundTrip_singleByte() {
        // columns 0 and 2 are null (bits 0 and 2 set)
        byte[] bitmap = {(byte) 0b00000101};
        var h = new HeapTupleHeader(new RecordId(0, 0), (short) 4, bitmap);

        ByteBuffer buf = freshBuffer(64);
        h.writeTo(buf, 0);
        var decoded = HeapTupleHeader.readFrom(buf, 0);

        assertThat(decoded.isNull(0)).isTrue();
        assertThat(decoded.isNull(1)).isFalse();
        assertThat(decoded.isNull(2)).isTrue();
        assertThat(decoded.isNull(3)).isFalse();
    }

    @Test
    void nullBitmap_roundTrip_multipleBytes() {
        // 9 columns — 2 bitmap bytes; columns 0 and 8 are null
        byte[] bitmap = {(byte) 0b00000001, (byte) 0b00000001};
        var h = new HeapTupleHeader(new RecordId(0, 0), (short) 9, bitmap);

        ByteBuffer buf = freshBuffer(64);
        h.writeTo(buf, 0);
        var decoded = HeapTupleHeader.readFrom(buf, 0);

        assertThat(decoded.isNull(0)).isTrue();
        assertThat(decoded.isNull(1)).isFalse();
        assertThat(decoded.isNull(7)).isFalse();
        assertThat(decoded.isNull(8)).isTrue();
    }

    @Test
    void nullBitmap_zeroColumns_emptyBitmap() {
        var h = new HeapTupleHeader(new RecordId(0, 0), (short) 0);
        assertThat(h.headerSize()).isEqualTo(23);

        ByteBuffer buf = freshBuffer(32);
        h.writeTo(buf, 0);
        var decoded = HeapTupleHeader.readFrom(buf, 0);
        assertThat(decoded.natts()).isEqualTo((short) 0);
        assertThat(decoded.headerSize()).isEqualTo(23);
    }

    @Test
    void infomask2_nattsPackedInLower11Bits() {
        // 1000 cols needs ceil(1000/8)=125 bitmap bytes → hoff=148, fits in uint8
        var h = new HeapTupleHeader(new RecordId(0, 0), (short) 1000);

        ByteBuffer buf = freshBuffer(200);
        h.writeTo(buf, 0);
        var decoded = HeapTupleHeader.readFrom(buf, 0);

        assertThat(decoded.natts()).isEqualTo((short) 1000);
        assertThat(decoded.headerSize()).isEqualTo(23 + 125);
    }

    @Test
    void natts_roundTrip_typicalValues() {
        for (short n : new short[]{1, 5, 8, 9, 100, 1024}) {
            var h = new HeapTupleHeader(new RecordId(0, 0), n);
            ByteBuffer buf = freshBuffer(HeapTupleHeader.computeHeaderSize(n) + 8);
            h.writeTo(buf, 0);
            assertThat(HeapTupleHeader.readFrom(buf, 0).natts()).isEqualTo(n);
        }
    }

    @Test
    void infomask_setAndClearFlags() {
        var h = new HeapTupleHeader(new RecordId(0, 0), (short) 0);

        h.setInfomaskFlag(HeapTupleHeader.XMIN_COMMITTED);
        h.setInfomaskFlag(HeapTupleHeader.XMAX_INVALID);

        assertThat(h.hasInfomaskFlag(HeapTupleHeader.XMIN_COMMITTED)).isTrue();
        assertThat(h.hasInfomaskFlag(HeapTupleHeader.XMAX_INVALID)).isTrue();
        assertThat(h.hasInfomaskFlag(HeapTupleHeader.XMIN_INVALID)).isFalse();

        h.clearInfomaskFlag(HeapTupleHeader.XMIN_COMMITTED);

        assertThat(h.hasInfomaskFlag(HeapTupleHeader.XMIN_COMMITTED)).isFalse();
        assertThat(h.hasInfomaskFlag(HeapTupleHeader.XMAX_INVALID)).isTrue();
    }

    @Test
    void setXmax_updatesField() {
        var h = new HeapTupleHeader(new RecordId(0, 0), (short) 0);
        assertThat(h.xmax()).isEqualTo(Constants.INVALID_XID);

        h.setXmax(99);
        assertThat(h.xmax()).isEqualTo(99);
    }

    @Test
    void setCtid_updatesVersionPointer() {
        var h = new HeapTupleHeader(new RecordId(1, 0), (short) 0);
        var newRid = new RecordId(2, 5);

        h.setCtid(newRid);

        assertThat(h.ctid()).isEqualTo(newRid);
    }
}
