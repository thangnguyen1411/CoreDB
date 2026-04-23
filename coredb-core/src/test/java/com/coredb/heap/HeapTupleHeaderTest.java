package com.coredb.heap;

import com.coredb.util.Constants;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.assertj.core.api.Assertions.assertThat;

class HeapTupleHeaderTest {

    private static ByteBuffer freshBuffer() {
        return ByteBuffer.allocate(64).order(ByteOrder.BIG_ENDIAN);
    }

    @Test
    void newHeader_hasBootstrapXminAndZeroXmax() {
        var rid = new RecordId(3, 7);
        var h = new HeapTupleHeader(rid, (short) 4);

        assertThat(h.xmin()).isEqualTo(Constants.BOOTSTRAP_XID);
        assertThat(h.xmax()).isEqualTo(Constants.INVALID_XID);
        assertThat(h.ctid()).isEqualTo(rid);
        assertThat(h.infomask()).isEqualTo((short) 0);
        assertThat(h.hoff()).isEqualTo(HeapTupleHeader.HEADER_SIZE);
        assertThat(h.natts()).isEqualTo((short) 4);
    }

    @Test
    void writeThenRead_roundTripsAllFields() {
        var rid = new RecordId(5, 2);
        var original = new HeapTupleHeader(rid, (short) 7);
        original.setXmax(42);
        original.setInfomaskFlag(HeapTupleHeader.XMIN_COMMITTED);

        ByteBuffer buf = freshBuffer();
        original.writeTo(buf, 0);

        var decoded = HeapTupleHeader.readFrom(buf, 0);

        assertThat(decoded.xmin()).isEqualTo(Constants.BOOTSTRAP_XID);
        assertThat(decoded.xmax()).isEqualTo(42);
        assertThat(decoded.ctid()).isEqualTo(rid);
        assertThat(decoded.hoff()).isEqualTo(HeapTupleHeader.HEADER_SIZE);
        assertThat(decoded.hasInfomaskFlag(HeapTupleHeader.XMIN_COMMITTED)).isTrue();
        assertThat(decoded.hasInfomaskFlag(HeapTupleHeader.XMAX_COMMITTED)).isFalse();
        assertThat(decoded.natts()).isEqualTo((short) 7);
    }

    @Test
    void writeThenRead_atNonZeroOffset_roundTrips() {
        int offset = 12;
        var rid = new RecordId(1, 0);
        var original = new HeapTupleHeader(rid, (short) 3);

        ByteBuffer buf = freshBuffer();
        original.writeTo(buf, offset);

        var decoded = HeapTupleHeader.readFrom(buf, offset);

        assertThat(decoded.xmin()).isEqualTo(Constants.BOOTSTRAP_XID);
        assertThat(decoded.xmax()).isEqualTo(Constants.INVALID_XID);
        assertThat(decoded.ctid()).isEqualTo(rid);
        assertThat(decoded.natts()).isEqualTo((short) 3);
    }

    @Test
    void headerSize_is20Bytes() {
        assertThat(HeapTupleHeader.HEADER_SIZE).isEqualTo(20);
    }

    @Test
    void natts_roundTrip() {
        var h = new HeapTupleHeader(new RecordId(0, 0), (short) 5);
        ByteBuffer buf = freshBuffer();
        h.writeTo(buf, 0);
        assertThat(HeapTupleHeader.readFrom(buf, 0).natts()).isEqualTo((short) 5);
    }

    @Test
    void natts_maxValue_roundTrip() {
        // short -1 == 0xFFFF == 65535 unsigned — the maximum column count
        var h = new HeapTupleHeader(new RecordId(0, 0), (short) -1);
        ByteBuffer buf = freshBuffer();
        h.writeTo(buf, 0);
        assertThat(HeapTupleHeader.readFrom(buf, 0).natts()).isEqualTo((short) -1);
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
