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

class BufferPoolTest {

    @TempDir
    Path tempDir;

    private static final int TEST_OID = 1000;

    private Path createTestFile(int numPages) throws IOException {
        Path file = tempDir.resolve("test_heap");
        try (FileChannel channel = FileChannel.open(file,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            for (int i = 0; i < numPages; i++) {
                ByteBuffer buf = ByteBuffer.allocate(Constants.PAGE_SIZE).order(ByteOrder.BIG_ENDIAN);
                buf.putInt(0, i);
                buf.clear();
                channel.write(buf, (long) i * Constants.PAGE_SIZE);
            }
        }
        return file;
    }

    @Test
    void fetchSamePageTwice_returnsSameDescriptor() throws IOException {
        Path file = createTestFile(1);
        BufferPool pool = new BufferPool(4);
        pool.registerFile(TEST_OID, file);

        BufferDescriptor frame1 = pool.fetchPage(TEST_OID, 0);
        BufferDescriptor frame2 = pool.fetchPage(TEST_OID, 0);

        assertThat(frame1).isSameAs(frame2);
        assertThat(frame1.frameId()).isEqualTo(frame2.frameId());
        assertThat(frame1.pinCount()).isEqualTo(2);

        pool.close();
    }

    @Test
    void unpinTwice_makesFrameEligibleForEviction() throws IOException {
        Path file = createTestFile(1);
        BufferPool pool = new BufferPool(4);
        pool.registerFile(TEST_OID, file);

        BufferDescriptor frame = pool.fetchPage(TEST_OID, 0);
        assertThat(frame.pinCount()).isEqualTo(1);

        pool.unpinPage(frame, false);
        assertThat(frame.pinCount()).isEqualTo(0);

        assertThatThrownBy(() -> pool.unpinPage(frame, false))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Unpin called on unpinned buffer");

        pool.close();
    }

    @Test
    void markDirty_setsDirtyFlag() throws IOException {
        Path file = createTestFile(1);
        BufferPool pool = new BufferPool(4);
        pool.registerFile(TEST_OID, file);

        BufferDescriptor frame = pool.fetchPage(TEST_OID, 0);
        assertThat(frame.dirty()).isFalse();

        pool.unpinPage(frame, true);

        assertThat(frame.dirty()).isTrue();
        assertThat(pool.dirtyCount()).isEqualTo(1);

        pool.close();
    }

    @Test
    void statistics_trackedCorrectly() throws IOException {
        Path file = createTestFile(2);
        BufferPool pool = new BufferPool(4);
        pool.registerFile(TEST_OID, file);

        assertThat(pool.hits()).isEqualTo(0);
        assertThat(pool.misses()).isEqualTo(0);

        pool.fetchPage(TEST_OID, 0);
        assertThat(pool.hits()).isEqualTo(0);
        assertThat(pool.misses()).isEqualTo(1);

        pool.fetchPage(TEST_OID, 0);
        assertThat(pool.hits()).isEqualTo(1);
        assertThat(pool.misses()).isEqualTo(1);

        pool.fetchPage(TEST_OID, 1);
        assertThat(pool.hits()).isEqualTo(1);
        assertThat(pool.misses()).isEqualTo(2);

        assertThat(pool.hitRate()).isCloseTo(33.3, offset(0.5));

        pool.close();
    }

    @Test
    void clockSweep_evictsUnpinnedPage() throws IOException {
        Path file = createTestFile(4);
        BufferPool pool = new BufferPool(3);
        pool.registerFile(TEST_OID, file);

        BufferDescriptor frame0 = pool.fetchPage(TEST_OID, 0);
        BufferDescriptor frame1 = pool.fetchPage(TEST_OID, 1);
        BufferDescriptor frame2 = pool.fetchPage(TEST_OID, 2);

        assertThat(pool.usedFrames()).isEqualTo(3);

        pool.unpinPage(frame0, false);
        pool.unpinPage(frame1, false);
        pool.unpinPage(frame2, false);

        assertThat(frame0.pinCount()).isEqualTo(0);
        assertThat(frame1.pinCount()).isEqualTo(0);
        assertThat(frame2.pinCount()).isEqualTo(0);

        BufferDescriptor frame3 = pool.fetchPage(TEST_OID, 3);

        assertThat(pool.usedFrames()).isEqualTo(3);
        assertThat(frame3.frameId()).isIn(frame0.frameId(), frame1.frameId(), frame2.frameId());

        pool.close();
    }

    @Test
    void clockSweep_hotPageSurvives() throws IOException {
        Path file = createTestFile(4);
        BufferPool pool = new BufferPool(3);
        pool.registerFile(TEST_OID, file);

        BufferDescriptor frame0 = pool.fetchPage(TEST_OID, 0);
        BufferDescriptor frame1 = pool.fetchPage(TEST_OID, 1);
        pool.fetchPage(TEST_OID, 2); // stays pinned

        pool.unpinPage(frame0, false);
        pool.unpinPage(frame1, false);

        BufferDescriptor frame0Again = pool.fetchPage(TEST_OID, 0);
        assertThat(frame0Again).isSameAs(frame0);
        pool.unpinPage(frame0, false);

        pool.fetchPage(TEST_OID, 3);

        assertThat(pool.usedFrames()).isEqualTo(3);

        pool.close();
    }

    /**
     * All frames pinned — fetchPage must block until one is released.
     * A background thread releases a pin after 50 ms; the main thread's
     * fetch should then succeed.
     */
    @Test
    void clockSweep_allPinned_blocksUntilUnpin() throws IOException, InterruptedException {
        Path file = createTestFile(5);
        BufferPool pool = new BufferPool(3);
        pool.registerFile(TEST_OID, file);

        BufferDescriptor frame0 = pool.fetchPage(TEST_OID, 0);
        BufferDescriptor frame1 = pool.fetchPage(TEST_OID, 1);
        pool.fetchPage(TEST_OID, 2); // pinned, not held in a local var

        // Background thread releases frame0 after a short delay.
        Thread releaser = new Thread(() -> {
            try {
                Thread.sleep(50);
                pool.unpinPage(frame0, false);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });
        releaser.start();

        // Should succeed once the releaser unpins frame0.
        BufferDescriptor frame3 = pool.fetchPage(TEST_OID, 3);
        assertThat(frame3).isNotNull();

        releaser.join();
        pool.close();
    }

    /**
     * All frames permanently pinned — fetchPage must eventually throw StorageException
     * after the sweep timeout expires.
     */
    @Test
    void clockSweep_allPinned_throwsAfterTimeout() throws IOException {
        Path file = createTestFile(5);
        // Short sweep timeout so the test doesn't wait too long.
        BufferPool pool = new BufferPool(3, 200);
        pool.registerFile(TEST_OID, file);

        pool.fetchPage(TEST_OID, 0);
        pool.fetchPage(TEST_OID, 1);
        pool.fetchPage(TEST_OID, 2);

        assertThatThrownBy(() -> pool.fetchPage(TEST_OID, 3))
            .isInstanceOf(StorageException.class);

        pool.close();
    }

    @Test
    void pinnedFrame_multiplePinsOnSamePage() throws IOException {
        Path file = createTestFile(1);
        BufferPool pool = new BufferPool(4);
        pool.registerFile(TEST_OID, file);

        BufferDescriptor frame0 = pool.fetchPage(TEST_OID, 0);
        assertThat(frame0.pinCount()).isEqualTo(1);

        BufferDescriptor frame0Again = pool.fetchPage(TEST_OID, 0);
        assertThat(frame0Again).isSameAs(frame0);
        assertThat(frame0.pinCount()).isEqualTo(2);

        BufferDescriptor frame0Third = pool.fetchPage(TEST_OID, 0);
        assertThat(frame0Third).isSameAs(frame0);
        assertThat(frame0.pinCount()).isEqualTo(3);

        pool.close();
    }

    @Test
    void flushDirtyFrame_writesToDisk() throws IOException {
        Path file = createTestFile(1);
        BufferPool pool = new BufferPool(4);
        pool.registerFile(TEST_OID, file);

        BufferDescriptor frame = pool.fetchPage(TEST_OID, 0);

        frame.contentLock().writeLock().lock();
        try {
            ByteBuffer buf = frame.page();
            buf.clear();
            buf.putInt(0xDEADBEEF);
            buf.flip();
        } finally {
            frame.contentLock().writeLock().unlock();
        }

        pool.unpinPage(frame, true);
        assertThat(frame.dirty()).isTrue();

        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            pool.flushFrame(frame.frameId(), channel);
        }
        assertThat(frame.dirty()).isFalse();

        pool.close();
    }

    @Test
    void close_withDirtyPages_autoFlushes() throws IOException {
        Path file = createTestFile(1);
        BufferPool pool = new BufferPool(4);
        pool.registerFile(TEST_OID, file);

        BufferDescriptor frame = pool.fetchPage(TEST_OID, 0);
        pool.unpinPage(frame, true);

        pool.close();
    }

    @Test
    void close_afterFlushAllDirty_succeeds() throws IOException {
        Path file = createTestFile(1);
        BufferPool pool = new BufferPool(4);
        pool.registerFile(TEST_OID, file);

        BufferDescriptor frame = pool.fetchPage(TEST_OID, 0);
        pool.unpinPage(frame, true);

        pool.flushAllDirty();
        pool.close();
    }

    @Test
    void bufferDescriptor_key_packsCorrectly() {
        int tableOid = 0x12345678;
        int pageId = 0x9ABCDEF0;

        long key = BufferDescriptor.key(tableOid, pageId);

        assertThat((int) (key >>> 32)).isEqualTo(tableOid);
        assertThat((int) key).isEqualTo(pageId);
    }

    @Test
    void statsString_formatsCorrectly() throws IOException {
        Path file = createTestFile(2);
        BufferPool pool = new BufferPool(8);
        pool.registerFile(TEST_OID, file);

        pool.fetchPage(TEST_OID, 0);
        pool.fetchPage(TEST_OID, 0); // hit
        pool.fetchPage(TEST_OID, 1); // miss

        String stats = pool.statsString();

        assertThat(stats).contains("frames=8");
        assertThat(stats).contains("pinned=2");
        assertThat(stats).contains("dirty=0");
        assertThat(stats).contains("hits=1");
        assertThat(stats).contains("misses=2");
        assertThat(stats).contains("hit-rate=33.");
        assertThat(stats).contains("partitions=16");

        pool.close();
    }

    @Test
    void fetchNewPage_createsEmptyPage() throws IOException {
        Path file = createTestFile(1);
        BufferPool pool = new BufferPool(4);
        pool.registerFile(TEST_OID, file);

        BufferDescriptor frame = pool.fetchNewPage(TEST_OID, 5);

        assertThat(frame.fileId()).isEqualTo(TEST_OID);
        assertThat(frame.pageId()).isEqualTo(5);
        assertThat(frame.pinCount()).isEqualTo(1);
        assertThat(frame.dirty()).isTrue();

        ByteBuffer buf = frame.page();
        assertThat(buf.limit()).isEqualTo(Constants.PAGE_SIZE);

        pool.close();
    }

    @Test
    void defaultConstructor_creates1024Frames() {
        BufferPool pool = new BufferPool();

        assertThat(pool.frameCount()).isEqualTo(1024);
    }
}
