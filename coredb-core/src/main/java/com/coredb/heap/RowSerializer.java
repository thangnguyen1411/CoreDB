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

public final class RowSerializer {

    private RowSerializer() {}

    public static byte[] serialize(Row row, Schema schema) {
        int numCols = schema.columnCount();
        if (row.size() != numCols) {
            throw new StorageException("row has " + row.size() + " values but schema has " + numCols + " columns");
        }

        int dataSize = 0;
        for (int i = 0; i < numCols; i++) {
            if (row.get(i) == null) continue;
            dataSize += encodedSize(row.get(i), schema.column(i));
        }

        ByteBuffer buf = ByteBuffer.allocate(dataSize).order(ByteOrder.BIG_ENDIAN);

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

    public static byte[] nullBitmap(Row row, Schema schema) {
        int numCols = schema.columnCount();
        byte[] bitmap = new byte[(numCols + 7) / 8];
        for (int i = 0; i < numCols; i++) {
            if (row.get(i) == null) {
                bitmap[i / 8] |= (byte) (1 << (i % 8));
            }
        }
        return bitmap;
    }

    public static Row deserialize(byte[] data, Schema schema, HeapTupleHeader header) {
        int tnatts = Short.toUnsignedInt(header.natts());
        int schemaCols = schema.columnCount();
        if (tnatts > schemaCols) {
            throw new StorageException(
                "Tuple has " + tnatts + " attributes but schema expects " + schemaCols +
                " (forward compatibility not supported)");
        }

        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);

        List<Object> values = new ArrayList<>(schemaCols);
        for (int i = 0; i < tnatts; i++) {
            if (header.isNull(i)) {
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
        for (int i = tnatts; i < schemaCols; i++) {
            values.add(null);
        }

        return Row.of(values);
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
