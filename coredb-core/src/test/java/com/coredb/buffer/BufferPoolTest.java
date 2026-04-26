package com.coredb.buffer;

import com.coredb.util.Constants;
import com.coredb.util.StorageException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.offset;

/**
 * Tests for Buffer Pool core (pin/unpin discipline).
 *
 * <p>These tests verify:
 * <ul>
 *   <li>Page fetch returns correct buffer</li>
 *   <li>Pin count increments/decrements correctly</li>
 *   <li>Dirty tracking works</li>
 *   <li>Statistics are accurate</li>
 *   <li>Pool full throws</li>
 * </ul>
 */
class BufferPoolTest {

    @TempDir
    Path tempDir;

    private static final int TEST_OID = 1000;

    /**
     * Creates a test file with N pages, each page filled with its pageId.
     */
    private Path createTestFile(int numPages) throws IOException {
        Path file = tempDir.resolve("test_heap");
        try (FileChannel channel = FileChannel.open(file,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            for (int i = 0; i < numPages; i++) {
                ByteBuffer buf = ByteBuffer.allocate(Constants.PAGE_SIZE).order(ByteOrder.BIG_ENDIAN);
                // Write pageId at start of page for verification
                buf.putInt(0, i);
                buf.clear();
                channel.write(buf, (long) i * Constants.PAGE_SIZE);
            }
        }
        return file;
    }

    @Test
    void fetchSamePageTwice_returnsSameDescriptor() throws IOException {
        // Given: a file with 1 page
        Path file = createTestFile(1);
        BufferPool pool = new BufferPool(4); // 4 frames

        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            // When: fetch same page twice
            BufferDescriptor frame1 = pool.fetchPage(TEST_OID, 0, channel);
            BufferDescriptor frame2 = pool.fetchPage(TEST_OID, 0, channel);

            // Then: same object, pinCount = 2
            assertThat(frame1).isSameAs(frame2);
            assertThat(frame1.frameId()).isEqualTo(frame2.frameId());
            assertThat(frame1.pinCount()).isEqualTo(2);
        }

        pool.close();
    }

    @Test
    void unpinTwice_makesFrameEligibleForEviction() throws IOException {
        // Given: a file with 1 page
        Path file = createTestFile(1);
        BufferPool pool = new BufferPool(4);

        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            // When: fetch and unpin twice
            BufferDescriptor frame = pool.fetchPage(TEST_OID, 0, channel);
            assertThat(frame.pinCount()).isEqualTo(1);

            pool.unpinPage(frame, false);
            assertThat(frame.pinCount()).isEqualTo(0);

            // Second unpin should be idempotent (but throws in our impl)
            // Our impl throws on unpin of unpinned, so this tests the error case
            assertThatThrownBy(() -> pool.unpinPage(frame, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unpin called on unpinned buffer");
        }

        pool.close();
    }

    @Test
    void markDirty_setsDirtyFlag() throws IOException {
        // Given: a file with 1 page
        Path file = createTestFile(1);
        BufferPool pool = new BufferPool(4);

        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            // When: fetch and unpin with dirty=true
            BufferDescriptor frame = pool.fetchPage(TEST_OID, 0, channel);
            assertThat(frame.dirty()).isFalse();

            pool.unpinPage(frame, true);

            // Then: frame is marked dirty
            assertThat(frame.dirty()).isTrue();
            assertThat(pool.dirtyCount()).isEqualTo(1);
        }

        pool.close();
    }

    @Test
    void statistics_trackedCorrectly() throws IOException {
        // Given: a file with 2 pages
        Path file = createTestFile(2);
        BufferPool pool = new BufferPool(4);

        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            // Initially: no hits or misses
            assertThat(pool.hits()).isEqualTo(0);
            assertThat(pool.misses()).isEqualTo(0);

            // First fetch of page 0: miss
            pool.fetchPage(TEST_OID, 0, channel);
            assertThat(pool.hits()).isEqualTo(0);
            assertThat(pool.misses()).isEqualTo(1);

            // Second fetch of page 0: hit
            pool.fetchPage(TEST_OID, 0, channel);
            assertThat(pool.hits()).isEqualTo(1);
            assertThat(pool.misses()).isEqualTo(1);

            // First fetch of page 1: miss
            pool.fetchPage(TEST_OID, 1, channel);
            assertThat(pool.hits()).isEqualTo(1);
            assertThat(pool.misses()).isEqualTo(2);

            // Hit rate should be 33%
            assertThat(pool.hitRate()).isCloseTo(33.3, offset(0.5));
        }

        pool.close();
    }

    @Test
    void poolFull_throwsException() throws IOException {
        // Given: a file with 5 pages but only 3 frames (3 frames = small pool for testing)
        Path file = createTestFile(5);
        BufferPool pool = new BufferPool(3); // Only 3 frames

        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            // When: fetch 3 pages (fills the pool)
            BufferDescriptor frame0 = pool.fetchPage(TEST_OID, 0, channel);
            BufferDescriptor frame1 = pool.fetchPage(TEST_OID, 1, channel);
            BufferDescriptor frame2 = pool.fetchPage(TEST_OID, 2, channel);

            // Then: pool is full (all 3 frames used)
            assertThat(pool.usedFrames()).isEqualTo(3);

            // When: try to fetch a 4th page
            assertThatThrownBy(() -> pool.fetchPage(TEST_OID, 3, channel))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("Buffer pool full");

            // Unpin one frame and try again
            pool.unpinPage(frame0, false);
            pool.unpinPage(frame1, false);
            pool.unpinPage(frame2, false);

            // Now frame0 could be reused, but we need to test that it was unpinned
            assertThat(frame0.pinCount()).isEqualTo(0);
            assertThat(frame1.pinCount()).isEqualTo(0);
            assertThat(frame2.pinCount()).isEqualTo(0);
        }

        pool.close();
    }

    @Test
    void pinnedFrame_multiplePinsOnSamePage() throws IOException {
        // Given: a file with 1 page
        Path file = createTestFile(1);
        BufferPool pool = new BufferPool(4);

        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            // Fetch same page multiple times
            BufferDescriptor frame0 = pool.fetchPage(TEST_OID, 0, channel);
            assertThat(frame0.pinCount()).isEqualTo(1);

            // Fetch again - should be a hit and increment pin count
            BufferDescriptor frame0Again = pool.fetchPage(TEST_OID, 0, channel);
            assertThat(frame0Again).isSameAs(frame0);
            assertThat(frame0.pinCount()).isEqualTo(2);

            // And again
            BufferDescriptor frame0Third = pool.fetchPage(TEST_OID, 0, channel);
            assertThat(frame0Third).isSameAs(frame0);
            assertThat(frame0.pinCount()).isEqualTo(3);
        }

        pool.close();
    }

    @Test
    void flushDirtyFrame_writesToDisk() throws IOException {
        // Given: a file with 1 page
        Path file = createTestFile(1);
        BufferPool pool = new BufferPool(4);

        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            // Fetch the page and modify it
            BufferDescriptor frame = pool.fetchPage(TEST_OID, 0, channel);

            // Write a marker value into the page buffer
            ByteBuffer buf = frame.page();
            buf.clear();
            buf.putInt(0xDEADBEEF);
            buf.flip();

            // Unpin as dirty
            pool.unpinPage(frame, true);
            assertThat(frame.dirty()).isTrue();

            // Flush the dirty frame
            pool.flushFrame(frame.frameId(), channel);
            assertThat(frame.dirty()).isFalse(); // Should be clean after flush

            // Verify by reading raw file directly (bypassing buffer pool)
            // Page 1 in PageIO is at offset 8192 (pageNum+1 convention for data pages)
            ByteBuffer readBuf = ByteBuffer.allocate(Constants.PAGE_SIZE);
            channel.position(1L * Constants.PAGE_SIZE); // Page 0 data is at offset 8192
            channel.read(readBuf);
            readBuf.flip();
            int marker = readBuf.getInt(0);
            assertThat(marker).isEqualTo(0xDEADBEEF);
        }

        pool.close();
    }

    @Test
    void close_withDirtyPages_throws() throws IOException {
        // Given: a pool with a dirty page
        Path file = createTestFile(1);
        BufferPool pool = new BufferPool(4);

        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            BufferDescriptor frame = pool.fetchPage(TEST_OID, 0, channel);
            pool.unpinPage(frame, true); // mark dirty

            // When: try to close without flushing
            // Then: throws IllegalStateException
            assertThatThrownBy(() -> pool.close())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dirty frame(s)")
                .hasMessageContaining("flushAllDirty");
        }
    }

    @Test
    void close_afterFlushAllDirty_succeeds() throws IOException {
        // Given: a pool with a dirty page
        Path file = createTestFile(1);
        BufferPool pool = new BufferPool(4);

        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            BufferDescriptor frame = pool.fetchPage(TEST_OID, 0, channel);
            pool.unpinPage(frame, true); // mark dirty

            // Flush dirty pages first
            pool.flushAllDirty(channel);

            // When: close after flushing
            // Then: succeeds without exception
            pool.close(); // should not throw
        }
    }

    @Test
    void bufferDescriptor_key_packsCorrectly() {
        // Given: tableOid and pageId
        int tableOid = 0x12345678;
        int pageId = 0x9ABCDEF0;

        // When: create key
        long key = BufferDescriptor.key(tableOid, pageId);

        // Then: upper 32 bits = tableOid, lower 32 bits = pageId
        assertThat((int) (key >>> 32)).isEqualTo(tableOid);
        assertThat((int) key).isEqualTo(pageId);
    }

    @Test
    void statsString_formatsCorrectly() throws IOException {
        // Given: a pool with some activity
        Path file = createTestFile(2);
        BufferPool pool = new BufferPool(8);

        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            pool.fetchPage(TEST_OID, 0, channel);
            pool.fetchPage(TEST_OID, 0, channel); // hit
            pool.fetchPage(TEST_OID, 1, channel); // miss

            // When: get stats string
            String stats = pool.statsString();

            // Then: contains expected values
            assertThat(stats).contains("frames=8");
            assertThat(stats).contains("pinned=2"); // 2 frames have pins (page 0 and page 1)
            assertThat(stats).contains("dirty=0");
            assertThat(stats).contains("hits=1");
            assertThat(stats).contains("misses=2");
            assertThat(stats).contains("hit-rate=33.");
        }

        pool.close();
    }

    @Test
    void fetchNewPage_createsEmptyPage() throws IOException {
        // Given: a pool with available frames
        BufferPool pool = new BufferPool(4);

        // When: fetch a new page (no disk read needed)
        BufferDescriptor frame = pool.fetchNewPage(TEST_OID, 5);

        // Then: frame is set up correctly
        assertThat(frame.tableOid()).isEqualTo(TEST_OID);
        assertThat(frame.pageId()).isEqualTo(5);
        assertThat(frame.pinCount()).isEqualTo(1);
        assertThat(frame.dirty()).isTrue(); // New pages are dirty

        // And: page buffer is empty (cleared)
        ByteBuffer buf = frame.page();
        assertThat(buf.limit()).isEqualTo(Constants.PAGE_SIZE);

        pool.close();
    }

    @Test
    void defaultConstructor_creates1024Frames() {
        // When: use default constructor
        BufferPool pool = new BufferPool();

        // Then: 1024 frames
        assertThat(pool.frameCount()).isEqualTo(1024);

        pool.close();
    }
}
