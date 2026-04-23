package com.coredb.util;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.zip.CRC32C;

/**
 * Low-level binary read/write helpers. All operations use big-endian byte order.
 */
public final class BinaryUtil {

    private BinaryUtil() {}

    // --- u16 (2 bytes, unsigned, stored as Java short) ---

    public static short readU16(ByteBuffer buf, int offset) {
        return buf.order(ByteOrder.BIG_ENDIAN).getShort(offset);
    }

    public static void writeU16(ByteBuffer buf, int offset, short value) {
        buf.order(ByteOrder.BIG_ENDIAN).putShort(offset, value);
    }

    // --- u32 (4 bytes, unsigned, stored as Java int) ---

    public static int readU32(ByteBuffer buf, int offset) {
        return buf.order(ByteOrder.BIG_ENDIAN).getInt(offset);
    }

    public static void writeU32(ByteBuffer buf, int offset, int value) {
        buf.order(ByteOrder.BIG_ENDIAN).putInt(offset, value);
    }

    // --- u64 (8 bytes, stored as Java long) ---

    public static long readU64(ByteBuffer buf, int offset) {
        return buf.order(ByteOrder.BIG_ENDIAN).getLong(offset);
    }

    public static void writeU64(ByteBuffer buf, int offset, long value) {
        buf.order(ByteOrder.BIG_ENDIAN).putLong(offset, value);
    }

    // --- CRC32C checksum ---

    public static long crc32c(byte[] data) {
        CRC32C crc = new CRC32C();
        crc.update(data);
        return crc.getValue();
    }

    public static long crc32c(byte[] data, int offset, int length) {
        CRC32C crc = new CRC32C();
        crc.update(data, offset, length);
        return crc.getValue();
    }
}
