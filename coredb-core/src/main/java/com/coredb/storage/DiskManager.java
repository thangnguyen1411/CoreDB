package com.coredb.storage;

import com.coredb.api.CoreDBConfig;
import com.coredb.config.EngineType;
import com.coredb.page.PageHeader;
import com.coredb.page.Page;
import com.coredb.page.PageType;
import com.coredb.util.BinaryUtil;
import com.coredb.util.Constants;
import com.coredb.util.StorageException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class DiskManager implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DiskManager.class);

    // Offsets within page 0's payload (right after the 16-byte PageHeader)
    private static final int META_MAGIC        = PageHeader.SIZE;
    private static final int META_VERSION      = META_MAGIC + 8;
    private static final int META_CATALOG_ROOT = META_VERSION + 2;
    private static final int META_NEXT_PAGE    = META_CATALOG_ROOT + 4;
    private static final int META_ENGINE_TYPE  = META_NEXT_PAGE + 4;
    private static final int META_END          = META_ENGINE_TYPE + 1;

    private final Path path;
    private final FileChannel channel;
    private final Page metaPage;

    private DiskManager(Path path, FileChannel channel, Page metaPage) {
        this.path = path;
        this.channel = channel;
        this.metaPage = metaPage;
    }

    public static DiskManager open(Path path, CoreDBConfig config) throws IOException {
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        boolean isNew = !Files.exists(path);
        FileChannel channel = FileChannel.open(path,
                StandardOpenOption.READ, StandardOpenOption.WRITE, StandardOpenOption.CREATE);

        Page metaPage;
        if (isNew) {
            metaPage = buildMetaPage(config);
            writePageToChannel(channel, metaPage);
            log.info("Created database file: {}", path.toAbsolutePath());
        } else {
            metaPage = readPageFromChannel(channel, 0);
            verifyMagic(metaPage, path);
            log.info("Opened database file: {}  pages={}", path.toAbsolutePath(), readNextPageId(metaPage));
        }

        return new DiskManager(path, channel, metaPage);
    }

    public Page readPage(int pageId) throws IOException {
        int total = readNextPageId(metaPage);
        if (pageId < 0 || pageId >= total) {
            throw new StorageException("page " + pageId + " does not exist (allocated=" + total + ")");
        }
        if (pageId == 0) {
            return metaPage;
        }
        return readPageFromChannel(channel, pageId);
    }

    public void writePage(Page page) throws IOException {
        writePageToChannel(channel, page);
    }

    public Page allocatePage(PageType type) throws IOException {
        int newId = readNextPageId(metaPage);
        Page newPage = new Page(newId, type);
        writePageToChannel(channel, newPage);

        writeNextPageId(metaPage, newId + 1);
        writePageToChannel(channel, metaPage);

        log.debug("Allocated page id={} type={}", newId, type);
        return newPage;
    }

    public int pageCount() {
        return readNextPageId(metaPage);
    }

    public long fileSize() throws IOException {
        return channel.size();
    }

    public Path path() {
        return path;
    }

    @Override
    public void close() throws IOException {
        channel.force(true);
        channel.close();
        log.info("DiskManager closed: {}", path);
    }

    // --- Meta page construction and validation ---

    private static Page buildMetaPage(CoreDBConfig config) {
        Page p = new Page(0, PageType.META);
        ByteBuffer buf = p.buffer();
        BinaryUtil.writeU64(buf, META_MAGIC, Constants.FILE_MAGIC);
        BinaryUtil.writeU16(buf, META_VERSION, Constants.FORMAT_VERSION);
        BinaryUtil.writeU32(buf, META_CATALOG_ROOT, 0);
        BinaryUtil.writeU32(buf, META_NEXT_PAGE, 1);
        buf.put(META_ENGINE_TYPE, engineCode(config.engineType()));
        p.setPdLower((short) META_END);
        return p;
    }

    private static void verifyMagic(Page metaPage, Path path) throws IOException {
        long magic = BinaryUtil.readU64(metaPage.buffer(), META_MAGIC);
        if (magic != Constants.FILE_MAGIC) {
            throw new StorageException("not a CoreDB file or corrupted header: " + path);
        }
    }

    private static int readNextPageId(Page metaPage) {
        return BinaryUtil.readU32(metaPage.buffer(), META_NEXT_PAGE);
    }

    private static void writeNextPageId(Page metaPage, int id) {
        BinaryUtil.writeU32(metaPage.buffer(), META_NEXT_PAGE, id);
    }

    private static byte engineCode(EngineType type) {
        return switch (type) {
            case BTREE -> 0;
            case LSM   -> 1;
        };
    }

    // --- Low-level FileChannel I/O ---

    private static void writePageToChannel(FileChannel channel, Page page) throws IOException {
        ByteBuffer buf = page.buffer().duplicate();
        buf.clear();
        long pos = (long) page.pageId() * Constants.PAGE_SIZE;
        while (buf.hasRemaining()) {
            pos += channel.write(buf, pos);
        }
    }

    private static Page readPageFromChannel(FileChannel channel, int pageId) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(Constants.PAGE_SIZE).order(ByteOrder.BIG_ENDIAN);
        long pos = (long) pageId * Constants.PAGE_SIZE;
        while (buf.hasRemaining()) {
            int n = channel.read(buf, pos);
            if (n == -1) {
                throw new StorageException("unexpected EOF reading page " + pageId);
            }
            pos += n;
        }
        return new Page(pageId, buf);
    }
}
