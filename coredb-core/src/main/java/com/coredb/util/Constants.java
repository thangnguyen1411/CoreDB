package com.coredb.util;

public final class Constants {

    private Constants() {}

    public static final int PAGE_SIZE = 8192;

    public static final long FILE_MAGIC = 0x434F5245_44420001L;
    public static final short FORMAT_VERSION = 1;

    public static final int INVALID_XID      = 0;
    public static final int BOOTSTRAP_XID    = 1;
    public static final int FROZEN_XID       = 2;
    public static final int FIRST_NORMAL_XID = 3;
}
