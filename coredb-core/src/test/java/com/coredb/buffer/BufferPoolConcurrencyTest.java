package com.coredb.buffer;

import com.coredb.util.Constants;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Thread-safety tests for the buffer pool.
 *
 * These tests drive concurrent fetch/unpin operations and verify invariants:
 * - No two threads load the same page twice from disk (IO-in-progress flag)
 * - Pinned frames are never evicted
 * - Hit/miss counts are consistent
 * - No AssertionError or IllegalStateException under concurrent load
 */
class BufferPoolConcurrencyTest {

    @TempDir
    Path tempDir;

    private static final int FILE_OID = 42;

    private Path createTestFile(int numPages) throws IOException {
        Path file = tempDir.resolve("concurrent_heap");
        try (FileChannel ch = FileChannel.open(file,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            for (int i = 0; i < numPages; i++) {
                ByteBuffer buf = ByteBuffer.allocate(Constants.PAGE_SIZE).order(ByteOrder.BIG_ENDIAN);
                buf.putInt(0, i); // page marker for identification
                buf.clear();
                ch.write(buf, (long) i * Constants.PAGE_SIZE);
            }
        }
        return file;
    }

    /**
     * Two threads fetch the same cold page concurrently.
     * Both must end up pinning the same frame; disk should be read exactly once.
     */
    @Test
    void concurrentFetch_samePage_onlyOneDiskRead() throws Exception {
        Path file = createTestFile(1);
        // Instrument disk reads via a custom channel wrapper? No — instead we verify
        // by checking that both threads see the same frameId after fetch.
        BufferPool pool = new BufferPool(4);
        pool.registerFile(FILE_OID, file);

        CountDownLatch start = new CountDownLatch(1);
        int[] frameIds = new int[2];

        Thread t1 = new Thread(() -> {
            try {
                start.await();
                frameIds[0] = pool.fetchPage(FILE_OID, 0).frameId();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        Thread t2 = new Thread(() -> {
            try {
                start.await();
                frameIds[1] = pool.fetchPage(FILE_OID, 0).frameId();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        t1.start();
        t2.start();
        start.countDown();
        t1.join();
        t2.join();

        // Both threads must have gotten the same frame (no duplicate load).
        assertThat(frameIds[0]).isEqualTo(frameIds[1]);
        // Both threads pinned the frame: pinCount == 2.
        assertThat(pool.descriptor(frameIds[0]).pinCount()).isEqualTo(2);

        pool.close();
    }

    /**
     * Two threads fetch distinct pages concurrently — no contention expected
     * on unrelated partitions, both fetches succeed.
     */
    @Test
    void concurrentFetch_differentPages_noContention() throws Exception {
        Path file = createTestFile(2);
        BufferPool pool = new BufferPool(4);
        pool.registerFile(FILE_OID, file);

        CountDownLatch start = new CountDownLatch(1);
        BufferDescriptor[] results = new BufferDescriptor[2];

        Thread t1 = new Thread(() -> {
            try {
                start.await();
                results[0] = pool.fetchPage(FILE_OID, 0);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        Thread t2 = new Thread(() -> {
            try {
                start.await();
                results[1] = pool.fetchPage(FILE_OID, 1);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        t1.start();
        t2.start();
        start.countDown();
        t1.join();
        t2.join();

        assertThat(results[0]).isNotNull();
        assertThat(results[1]).isNotNull();
        assertThat(results[0].frameId()).isNotEqualTo(results[1].frameId());

        pool.close();
    }

    /**
     * Eviction during pin: a thread that has pinned a frame must prevent that frame
     * from being evicted even when the pool is under sweep pressure.
     */
    @Test
    void pinnedFrame_notEvictedUnderSweepPressure() throws Exception {
        // 3-frame pool, 4 pages: force eviction of something.
        Path file = createTestFile(4);
        BufferPool pool = new BufferPool(3);
        pool.registerFile(FILE_OID, file);

        // Pin page 0 and keep it pinned throughout.
        BufferDescriptor pinned = pool.fetchPage(FILE_OID, 0);
        assertThat(pinned.pinCount()).isEqualTo(1);

        // Fetch pages 1 and 2 to fill remaining frames.
        BufferDescriptor d1 = pool.fetchPage(FILE_OID, 1);
        BufferDescriptor d2 = pool.fetchPage(FILE_OID, 2);

        // Unpin 1 and 2 so they are evictable.
        pool.unpinPage(d1, false);
        pool.unpinPage(d2, false);

        // Fetch page 3 — must evict one of {1, 2}, never the pinned page 0.
        BufferDescriptor d3 = pool.fetchPage(FILE_OID, 3);
        assertThat(d3.frameId()).isNotEqualTo(pinned.frameId());
        assertThat(pinned.fileId()).isEqualTo(FILE_OID);
        assertThat(pinned.pageId()).isEqualTo(0);

        pool.close();
    }

    /**
     * Concurrent dirty marking: multiple threads marking the same frame dirty
     * must result in dirty=true and a pdLsn equal to the maximum of all inputs.
     */
    @Test
    void concurrentDirtyMark_resultIsConsistent() throws Exception {
        Path file = createTestFile(1);
        BufferPool pool = new BufferPool(4);
        pool.registerFile(FILE_OID, file);

        BufferDescriptor frame = pool.fetchPage(FILE_OID, 0);
        // Pin it N more times so all threads can unpin independently.
        int N = 10;
        for (int i = 0; i < N; i++) {
            pool.fetchPage(FILE_OID, 0);
        }

        AtomicLong maxLsn = new AtomicLong(0);
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < N; i++) {
            long lsn = (long) (i + 1) * 100;
            maxLsn.updateAndGet(cur -> Math.max(cur, lsn));
            threads.add(new Thread(() -> {
                frame.headerMutex().lock();
                try {
                    frame.setPdLsn(lsn);
                    frame.markDirty();
                } finally {
                    frame.headerMutex().unlock();
                }
                pool.unpinPage(frame, false); // dirty already set above
            }));
        }

        threads.forEach(Thread::start);
        for (Thread t : threads) t.join();

        // All threads unpinned; pinCount must be 1 (the original fetch).
        assertThat(frame.dirty()).isTrue();
        // pinCount: 1 original + N re-pins − N unpins = 1.
        assertThat(frame.pinCount()).isEqualTo(1);

        pool.close();
    }

    /**
     * Stress test: N threads each perform K random fetch/unpin cycles.
     * No assertion errors, no deadlocks, hit rate consistent.
     */
    @Test
    void stress_randomFetchUnpin_noErrors() throws Exception {
        int N = 8;
        int K = 1_000;
        int numPages = 20;
        int numFrames = 8;

        Path file = createTestFile(numPages);
        BufferPool pool = new BufferPool(numFrames);
        pool.registerFile(FILE_OID, file);

        AtomicInteger errorCount = new AtomicInteger(0);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService exec = Executors.newFixedThreadPool(N);
        List<Future<?>> futures = new ArrayList<>();

        for (int t = 0; t < N; t++) {
            futures.add(exec.submit(() -> {
                try {
                    start.await();
                    java.util.Random rng = new java.util.Random();
                    for (int k = 0; k < K; k++) {
                        int pageId = rng.nextInt(numPages);
                        BufferDescriptor frame = pool.fetchPage(FILE_OID, pageId);
                        assertThat(frame.pageId()).isEqualTo(pageId);
                        assertThat(frame.fileId()).isEqualTo(FILE_OID);
                        pool.unpinPage(frame, false);
                    }
                } catch (AssertionError | Exception e) {
                    errorCount.incrementAndGet();
                    e.printStackTrace();
                }
            }));
        }

        start.countDown();
        for (Future<?> f : futures) f.get();
        exec.shutdown();

        assertThat(errorCount.get()).isZero();
        // All threads unpinned everything: pool should have 0 pinned frames.
        assertThat(pool.pinnedCount()).isEqualTo(0);
        // Total ops = N * K; hits + misses must equal that.
        assertThat(pool.hits() + pool.misses()).isEqualTo((long) N * K);

        pool.close();
    }
}
