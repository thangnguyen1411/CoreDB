package com.coredb.page;

import com.coredb.util.BinaryUtil;
import com.coredb.util.Constants;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class Page {

    private final int pageId;
    private final ByteBuffer buffer;

    public Page(int pageId, PageType type) {
        this.pageId = pageId;
        this.buffer = ByteBuffer.allocate(Constants.PAGE_SIZE).order(ByteOrder.BIG_ENDIAN);
        BinaryUtil.writeU64(buffer, PageHeader.OFFSET_LSN, 0L);
        BinaryUtil.writeU16(buffer, PageHeader.OFFSET_PD_LOWER, (short) PageHeader.SIZE);
        BinaryUtil.writeU16(buffer, PageHeader.OFFSET_PD_UPPER, (short) Constants.PAGE_SIZE);
        BinaryUtil.writeU16(buffer, PageHeader.OFFSET_PD_SPECIAL, (short) Constants.PAGE_SIZE);
        BinaryUtil.writeU16(buffer, PageHeader.OFFSET_PD_FLAGS, (short) (type.code() << 8));
    }

    /**
     * Creates a page with an uninitialized buffer.
     * The caller is responsible for writing the page header or custom layout.
     */
    public Page(int pageId) {
        this.pageId = pageId;
        this.buffer = ByteBuffer.allocate(Constants.PAGE_SIZE).order(
            ByteOrder.BIG_ENDIAN
        );
    }

    public Page(int pageId, ByteBuffer buffer) {
        this.pageId = pageId;
        this.buffer = buffer.order(ByteOrder.BIG_ENDIAN);
    }

    public int pageId() {
        return pageId;
    }

    public PageType pageType() {
        short flags = BinaryUtil.readU16(buffer, PageHeader.OFFSET_PD_FLAGS);
        return PageType.fromCode((byte) ((flags >> 8) & 0xFF));
    }

    public short pdLower() {
        return BinaryUtil.readU16(buffer, PageHeader.OFFSET_PD_LOWER);
    }

    public short pdUpper() {
        return BinaryUtil.readU16(buffer, PageHeader.OFFSET_PD_UPPER);
    }

    public short pdSpecial() {
        return BinaryUtil.readU16(buffer, PageHeader.OFFSET_PD_SPECIAL);
    }

    public short pdFlags() {
        return BinaryUtil.readU16(buffer, PageHeader.OFFSET_PD_FLAGS);
    }

    public long lsn() {
        return BinaryUtil.readU64(buffer, PageHeader.OFFSET_LSN);
    }

    public void setPdLower(short lower) {
        BinaryUtil.writeU16(buffer, PageHeader.OFFSET_PD_LOWER, lower);
    }

    public void setPdUpper(short upper) {
        BinaryUtil.writeU16(buffer, PageHeader.OFFSET_PD_UPPER, upper);
    }

    public void setPdSpecial(short special) {
        BinaryUtil.writeU16(buffer, PageHeader.OFFSET_PD_SPECIAL, special);
    }

    public void setLsn(long lsn) {
        BinaryUtil.writeU64(buffer, PageHeader.OFFSET_LSN, lsn);
    }

    public int freeBytes() {
        return Short.toUnsignedInt(pdUpper()) - Short.toUnsignedInt(pdLower());
    }

    public int readItemId(int slot) {
        return BinaryUtil.readU32(buffer, PageHeader.SIZE + slot * 4);
    }

    public void writeItemId(int slot, int itemId) {
        BinaryUtil.writeU32(buffer, PageHeader.SIZE + slot * 4, itemId);
    }

    public ByteBuffer buffer() {
        return buffer;
    }
}
