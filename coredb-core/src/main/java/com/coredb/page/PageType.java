package com.coredb.page;

public enum PageType {
    META, HEAP, INDEX_INTERNAL, INDEX_LEAF;

    public byte code() {
        return (byte) ordinal();
    }

    public static PageType fromCode(byte code) {
        return values()[Byte.toUnsignedInt(code)];
    }
}
