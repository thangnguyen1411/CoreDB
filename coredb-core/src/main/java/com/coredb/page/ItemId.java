package com.coredb.page;

public final class ItemId {

    public static final int FLAGS_UNUSED   = 0;
    public static final int FLAGS_NORMAL   = 1;
    public static final int FLAGS_REDIRECT = 2;
    public static final int FLAGS_DEAD     = 3;

    private ItemId() {}

    public static int pack(int offset, int flags, int length) {
        return (offset << 17) | ((flags & 0x3) << 15) | (length & 0x7FFF);
    }

    public static int offset(int itemId) {
        return (itemId >>> 17) & 0x7FFF;
    }

    public static int flags(int itemId) {
        return (itemId >>> 15) & 0x3;
    }

    public static int length(int itemId) {
        return itemId & 0x7FFF;
    }
}
