package com.coredb.page;

import com.coredb.util.Constants;
import com.coredb.util.StorageException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;

public final class PageIO {

    private PageIO() {}

    public static void writePage(FileChannel channel, Page page) throws IOException {
        ByteBuffer buf = page.buffer().duplicate();
        buf.clear();
        long pos = (long) page.pageId() * Constants.PAGE_SIZE;
        while (buf.hasRemaining()) {
            pos += channel.write(buf, pos);
        }
    }

    public static Page readPage(FileChannel channel, int pageId) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(Constants.PAGE_SIZE).order(ByteOrder.BIG_ENDIAN);
        long pos = (long) pageId * Constants.PAGE_SIZE;
        while (buf.hasRemaining()) {
            int n = channel.read(buf, pos);
            if (n == -1) {
                throw new StorageException("Unexpected EOF reading page " + pageId);
            }
            pos += n;
        }
        return Page.Factory.wrap(pageId, buf);
    }
}
