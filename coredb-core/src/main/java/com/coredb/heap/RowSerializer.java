package com.coredb.heap;

import com.coredb.api.Column;
import com.coredb.api.Row;
import com.coredb.api.Schema;
import com.coredb.util.StorageException;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Serializes and deserializes a Row to/from a compact byte array given a Schema.
 *
 * Binary layout:
 *   [null bitmap: ceil(numCols/8) bytes]  — bit i (LSB-first) set = column i is null
 *   [col0 bytes if not null]
 *   [col1 bytes if not null]
 *   ...
 *
 * Per-type encoding (big-endian):
 *   INT    — 4 bytes
 *   LONG   — 8 bytes
 *   BOOL   — 1 byte (0=false, 1=true)
 *   STRING — 4-byte length prefix + UTF-8 bytes
 */
public final class RowSerializer {

    private RowSerializer() {}

    public static byte[] serialize(Row row, Schema schema) {
        int numCols = schema.columnCount();
        if (row.size() != numCols) {
            throw new StorageException("row has " + row.size() + " values but schema has " + numCols + " columns");
        }

        // Calculate bitmap size based on number of columns
        int bitmapBytes = bitmapSize(numCols);
        byte[] bitmap = new byte[bitmapBytes];

        for (int i = 0; i < numCols; i++) {
            if (row.get(i) == null) {
                bitmap[i / 8] |= (byte) (1 << (i % 8));
            }
        }

        int dataSize = 0;
        for (int i = 0; i < numCols; i++) {
            if (row.get(i) == null) continue;
            dataSize += encodedSize(row.get(i), schema.column(i));
        }

        ByteBuffer buf = ByteBuffer.allocate(bitmapBytes + dataSize).order(ByteOrder.BIG_ENDIAN);
        buf.put(bitmap);

        for (int i = 0; i < numCols; i++) {
            Object val = row.get(i);
            if (val == null) continue;
            Column col = schema.column(i);
            switch (col.type()) {
                case INT    -> buf.putInt((Integer) val);
                case LONG   -> buf.putLong((Long) val);
                case BOOL   -> buf.put((byte) ((Boolean) val ? 1 : 0));
                case STRING -> {
                    byte[] bytes = ((String) val).getBytes(StandardCharsets.UTF_8);
                    buf.putInt(bytes.length);
                    buf.put(bytes);
                }
            }
        }

        return buf.array();
    }

    public static Row deserialize(byte[] data, Schema schema) {
        int numCols = schema.columnCount();
        int bitmapBytes = bitmapSize(numCols);
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);

        byte[] bitmap = new byte[bitmapBytes];
        buf.get(bitmap);

        List<Object> values = new ArrayList<>(numCols);
        for (int i = 0; i < numCols; i++) {
            boolean isNull = (bitmap[i / 8] & (1 << (i % 8))) != 0;
            if (isNull) {
                values.add(null);
                continue;
            }
            Column col = schema.column(i);
            values.add(switch (col.type()) {
                case INT    -> buf.getInt();
                case LONG   -> buf.getLong();
                case BOOL   -> buf.get() != 0;
                case STRING -> {
                    int len = buf.getInt();
                    byte[] bytes = new byte[len];
                    buf.get(bytes);
                    yield new String(bytes, StandardCharsets.UTF_8);
                }
            });
        }

        return Row.of(values);
    }

    private static int bitmapSize(int numCols) {
        return (numCols + 7) / 8;
    }

    private static int encodedSize(Object val, Column col) {
        return switch (col.type()) {
            case INT  -> 4;
            case LONG -> 8;
            case BOOL -> 1;
            case STRING -> 4 + ((String) val).getBytes(StandardCharsets.UTF_8).length;
        };
    }
}
