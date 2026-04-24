package com.coredb.heap;

/**
 * Result of serializing a row: the data bytes and the null bitmap.
 */
public record SerializedRow(byte[] data, byte[] nullBitmap) {}
