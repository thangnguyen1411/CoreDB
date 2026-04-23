package com.coredb.util;

public final class Constants {

    private Constants() {}

    public static final int PAGE_SIZE = 4096;

    // "COREDB" ASCII + u16 version 1 — written at byte 0 of the metadata page
    public static final long FILE_MAGIC = 0x434F5245_44420001L;

    // u16 at byte 0 of the metadata page after magic bytes
    public static final short FORMAT_VERSION = 1;
}
