package com.coredb.wal;

import com.coredb.util.CorruptionException;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class XLogRecordTest {

    @Test
    void create_computesCorrectCRC() {
        byte[] data = new byte[]{0x01, 0x02, 0x03, 0x04};
        XLogRecord record = XLogRecord.create(100, 1, 50, XLogRecord.RMGR_HEAP, (byte) 0x01, 1000, 5, data);

        assertThat(record.lsn()).isEqualTo(100);
        assertThat(record.totLen()).isEqualTo(44); // 40 byte header + 4 bytes data
        assertThat(record.xid()).isEqualTo(1);
        assertThat(record.prevLsn()).isEqualTo(50);
        assertThat(record.rmgr()).isEqualTo(XLogRecord.RMGR_HEAP);
        assertThat(record.info()).isEqualTo((byte) 0x01);
        assertThat(record.tableOid()).isEqualTo(1000);
        assertThat(record.pageId()).isEqualTo(5);
        assertThat(record.data()).containsExactly(0x01, 0x02, 0x03, 0x04);
    }

    @Test
    void create_withEmptyData() {
        XLogRecord record = XLogRecord.create(100, 1, 0, XLogRecord.RMGR_HEAP, (byte) 0x01, 1000, 1, new byte[0]);

        assertThat(record.totLen()).isEqualTo(40); // Just header, no data
        assertThat(record.data()).isEmpty();
    }

    @Test
    void isFullPageWrite_highBitSet_returnsTrue() {
        XLogRecord record = XLogRecord.create(100, 1, 0, XLogRecord.RMGR_HEAP, (byte) (0x01 | XLogRecord.XLOG_FPW), 1000, 1, new byte[]{1});
        assertThat(record.isFullPageWrite()).isTrue();
    }

    @Test
    void isFullPageWrite_highBitClear_returnsFalse() {
        XLogRecord record = XLogRecord.create(100, 1, 0, XLogRecord.RMGR_HEAP, (byte) 0x01, 1000, 1, new byte[]{1});
        assertThat(record.isFullPageWrite()).isFalse();
    }

    @Test
    void rmgrName_returnsCorrectNames() {
        XLogRecord heapRecord = XLogRecord.create(100, 1, 0, XLogRecord.RMGR_HEAP, (byte) 0x01, 1000, 1, new byte[]{1});
        assertThat(heapRecord.rmgrName()).isEqualTo("HEAP");

        XLogRecord btreeRecord = XLogRecord.create(100, 1, 0, XLogRecord.RMGR_BTREE, (byte) 0x01, 1000, 1, new byte[]{1});
        assertThat(btreeRecord.rmgrName()).isEqualTo("BTREE");

        XLogRecord xlogRecord = XLogRecord.create(100, 1, 0, XLogRecord.RMGR_XLOG, (byte) 0x01, 1000, 1, new byte[]{1});
        assertThat(xlogRecord.rmgrName()).isEqualTo("XLOG");

        XLogRecord unknownRecord = XLogRecord.create(100, 1, 0, (byte) 99, (byte) 0x01, 1000, 1, new byte[]{1});
        assertThat(unknownRecord.rmgrName()).isEqualTo("UNKNOWN(99)");
    }

    @Test
    void toBytes_roundTripsCorrectly() {
        byte[] data = new byte[]{0x0A, 0x0B, 0x0C};
        XLogRecord original = XLogRecord.create(256, 42, 128, XLogRecord.RMGR_BTREE, (byte) 0x20, 2000, 50, data);

        byte[] bytes = original.toBytes();
        ByteBuffer buf = ByteBuffer.wrap(bytes);
        XLogRecord recovered = XLogRecord.readFrom(buf, 0);

        assertThat(recovered).isNotNull();
        assertThat(recovered.lsn()).isEqualTo(original.lsn());
        assertThat(recovered.totLen()).isEqualTo(original.totLen());
        assertThat(recovered.xid()).isEqualTo(original.xid());
        assertThat(recovered.prevLsn()).isEqualTo(original.prevLsn());
        assertThat(recovered.rmgr()).isEqualTo(original.rmgr());
        assertThat(recovered.info()).isEqualTo(original.info());
        assertThat(recovered.tableOid()).isEqualTo(original.tableOid());
        assertThat(recovered.pageId()).isEqualTo(original.pageId());
        assertThat(recovered.crc()).isEqualTo(original.crc());
        assertThat(recovered.data()).containsExactly(data);
    }

    @Test
    void readFrom_detectsCorruption() {
        XLogRecord original = XLogRecord.create(100, 1, 0, XLogRecord.RMGR_HEAP, (byte) 0x01, 1000, 1, new byte[]{0x01});
        byte[] bytes = original.toBytes();

        // Corrupt a byte in the data section
        bytes[bytes.length - 1] = (byte) (bytes[bytes.length - 1] ^ 0xFF);

        ByteBuffer buf = ByteBuffer.wrap(bytes);
        assertThatThrownBy(() -> XLogRecord.readFrom(buf, 0))
            .isInstanceOf(CorruptionException.class)
            .hasMessageContaining("CRC mismatch");
    }

    @Test
    void readFrom_returnsNullIfNotEnoughData() {
        ByteBuffer buf = ByteBuffer.allocate(10); // Too small for any record
        XLogRecord record = XLogRecord.readFrom(buf, 0);
        assertThat(record).isNull();
    }

    @Test
    void readFrom_returnsNullForIncompleteRecord() {
        // Create buffer that has header but not enough for full record
        ByteBuffer buf = ByteBuffer.allocate(45); // Header + partial data
        buf.order(ByteOrder.BIG_ENDIAN);
        buf.putLong(100); // lsn
        buf.putInt(100);  // totLen (claims to be much larger than buffer)
        buf.flip();

        XLogRecord record = XLogRecord.readFrom(buf, 0);
        assertThat(record).isNull();
    }

    @Test
    void readFrom_returnsNullIfBufferTooSmallForFullRecord() {
        ByteBuffer buf = ByteBuffer.allocate(40);
        buf.order(ByteOrder.BIG_ENDIAN);
        buf.putLong(100); // lsn
        buf.putInt(50);   // totLen (bigger than buffer)
        buf.flip();

        XLogRecord record = XLogRecord.readFrom(buf, 0);
        assertThat(record).isNull(); // Not enough data for claimed record size
    }

    @Test
    void data_returnsCopy() {
        byte[] originalData = new byte[]{0x01, 0x02};
        XLogRecord record = XLogRecord.create(100, 1, 0, XLogRecord.RMGR_HEAP, (byte) 0x01, 1000, 1, originalData);

        byte[] data1 = record.data();
        data1[0] = (byte) 0xFF; // Modify returned array

        byte[] data2 = record.data();
        assertThat(data2[0]).isEqualTo((byte) 0x01); // Original unchanged
    }

    @Test
    void toString_containsKeyInfo() {
        XLogRecord record = XLogRecord.create(100, 42, 50, XLogRecord.RMGR_HEAP, (byte) 0x01, 1000, 5, new byte[]{1, 2});
        String str = record.toString();

        assertThat(str).contains("lsn=100");
        assertThat(str).contains("xid=42");
        assertThat(str).contains("rmgr=HEAP");
        assertThat(str).contains("info=0x01");
        assertThat(str).contains("dataLen=2");
    }
}
