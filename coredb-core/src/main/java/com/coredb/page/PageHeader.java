package com.coredb.page;

public record PageHeader(long pdLsn, short pdLower, short pdUpper, short pdSpecial, short pdFlags) {

    public static final int SIZE = 16;

    public static final int OFFSET_LSN        = 0;
    public static final int OFFSET_PD_LOWER   = 8;
    public static final int OFFSET_PD_UPPER   = 10;
    public static final int OFFSET_PD_SPECIAL = 12;
    public static final int OFFSET_PD_FLAGS   = 14;
}
