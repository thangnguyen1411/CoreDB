package com.coredb.util;

public final class Constants {

    private Constants() {}

    public static final int PAGE_SIZE = 8192;

    public static final long FILE_MAGIC = 0x434F5245_44420001L;
    public static final short FORMAT_VERSION = 1;

    // Per-table heap file meta page magic ("HEAP" = 0x48454150)
    public static final int HEAP_FILE_MAGIC = 0x48454150;

    // FSM file magic ("FSM\0" = 0x46534D00)
    // NOTE: This is a CoreDB invention. PostgreSQL's FSM uses the standard page
    // header format without a dedicated file magic. CoreDB uses a simplified flat
    // byte array format, so we add this magic for version/corruption detection.
    public static final int FSM_FILE_MAGIC = 0x46534D00;

    public static final int INVALID_XID      = 0;
    public static final int BOOTSTRAP_XID    = 1;
    public static final int FROZEN_XID       = 2;
    public static final int FIRST_NORMAL_XID = 3;
}
