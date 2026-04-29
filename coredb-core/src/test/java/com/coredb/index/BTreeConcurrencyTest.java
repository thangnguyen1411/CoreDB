package com.coredb.index;

import com.coredb.buffer.BufferPool;
import com.coredb.heap.RecordId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concurrency tests for BTree under the Lehman-Yao protocol with content latches.
 *
 * <p>These tests run with a real BufferPool so the per-frame content latch is
 * active. Bootstrap mode (no buffer pool) is single-threaded by construction
 * and is covered by {@link BTreeTest}.
 */
class BTreeConcurrencyTest {

    @TempDir
    Path tempDir;

    @Test
    void concurrentReadersOnStaticTree_findEveryKey() throws Exception {
        BufferPool pool = new BufferPool(64);
        Path indexPath = tempDir.resolve("readers.idx");
        IndexFile indexFile = IndexFile.create(indexPath, 1000, pool);
        BTree tree = BTree.create(indexFile);

        int totalKeys = 4000;
        for (int k = 0; k < totalKeys; k++) {
            tree.insert(k, new RecordId(k % 100 + 1, k % 32));
        }

        int threads = 8;
        int opsPerThread = 5000;
        ExecutorService pool2 = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> err = new AtomicReference<>();

        for (int t = 0; t < threads; t++) {
            final int seed = t;
            pool2.submit(() -> {
                try {
                    start.await();
                    Random rnd = new Random(seed);
                    for (int i = 0; i < opsPerThread; i++) {
                        int k = rnd.nextInt(totalKeys);
                        Optional<RecordId> r = tree.search(k);
                        if (r.isEmpty() || r.get().pageId() != (k % 100 + 1)) {
                            throw new AssertionError("missing or wrong rid for key=" + k + ": " + r);
                        }
                    }
                } catch (Throwable e) {
                    err.compareAndSet(null, e);
                }
            });
        }
        start.countDown();
        pool2.shutdown();
        assertThat(pool2.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        if (err.get() != null) throw new RuntimeException(err.get());

        indexFile.close();
    }

    @Test
    void concurrentInsertsDisjointRanges_allKeysFindable() throws Exception {
        BufferPool pool = new BufferPool(64);
        Path indexPath = tempDir.resolve("inserts.idx");
        IndexFile indexFile = IndexFile.create(indexPath, 1001, pool);
        BTree tree = BTree.create(indexFile);

        int threads = 8;
        int perThread = 1000;
        ExecutorService exec = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> err = new AtomicReference<>();

        for (int t = 0; t < threads; t++) {
            final int tid = t;
            exec.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        long key = (long) tid * perThread + i;
                        tree.insert(key, new RecordId(tid + 1, i % 32));
                    }
                } catch (Throwable e) {
                    err.compareAndSet(null, e);
                }
            });
        }
        start.countDown();
        exec.shutdown();
        assertThat(exec.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
        if (err.get() != null) throw new RuntimeException(err.get());

        // Every key must be findable.
        for (int t = 0; t < threads; t++) {
            for (int i = 0; i < perThread; i++) {
                long key = (long) t * perThread + i;
                Optional<RecordId> r = tree.search(key);
                assertThat(r).as("key=%d", key).isPresent();
                assertThat(r.get().pageId()).isEqualTo(t + 1);
            }
        }

        indexFile.close();
    }

    @Test
    void readersAndWritersOverlapping_readersSeeCommittedKeys() throws Exception {
        BufferPool pool = new BufferPool(64);
        Path indexPath = tempDir.resolve("rw.idx");
        IndexFile indexFile = IndexFile.create(indexPath, 1002, pool);
        BTree tree = BTree.create(indexFile);

        // Pre-populate with even keys so readers always have something to find.
        int prepop = 2000;
        for (int k = 0; k < prepop; k++) {
            tree.insert(k * 2L, new RecordId(7, k % 32));
        }

        int writerCount = 4;
        int readerCount = 4;
        int writesPerThread = 500;
        int readsPerThread = 5000;
        ExecutorService exec = Executors.newFixedThreadPool(writerCount + readerCount);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> err = new AtomicReference<>();
        Set<Long> insertedOdds = ConcurrentHashMap.newKeySet();

        for (int t = 0; t < writerCount; t++) {
            final int tid = t;
            exec.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < writesPerThread; i++) {
                        long key = (long) tid * writesPerThread * 2L + i * 2L + 1L; // odd keys, disjoint
                        tree.insert(key, new RecordId(tid + 100, i % 32));
                        insertedOdds.add(key);
                    }
                } catch (Throwable e) {
                    err.compareAndSet(null, e);
                }
            });
        }
        for (int t = 0; t < readerCount; t++) {
            final int seed = t + 1000;
            exec.submit(() -> {
                try {
                    start.await();
                    Random rnd = new Random(seed);
                    for (int i = 0; i < readsPerThread; i++) {
                        long evenKey = rnd.nextInt(prepop) * 2L;
                        Optional<RecordId> r = tree.search(evenKey);
                        if (r.isEmpty()) {
                            throw new AssertionError("pre-populated key disappeared: " + evenKey);
                        }
                    }
                } catch (Throwable e) {
                    err.compareAndSet(null, e);
                }
            });
        }
        start.countDown();
        exec.shutdown();
        assertThat(exec.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
        if (err.get() != null) throw new RuntimeException(err.get());

        // After the storm, every odd key writers reported is findable.
        for (long k : insertedOdds) {
            assertThat(tree.search(k)).as("odd key=%d", k).isPresent();
        }

        indexFile.close();
    }

    @Test
    void rightLinkChain_readerFollowsAfterSplit() throws Exception {
        // Single-threaded test that proves the Lehman-Yao right-link is followed.
        // Insert enough keys to force splits, then verify all keys are reachable
        // both via descend and via the leaf chain.
        BufferPool pool = new BufferPool(32);
        Path indexPath = tempDir.resolve("chain.idx");
        IndexFile indexFile = IndexFile.create(indexPath, 1003, pool);
        BTree tree = BTree.create(indexFile);

        int n = 3000;
        List<Long> keys = new ArrayList<>();
        for (int i = 0; i < n; i++) keys.add((long) i);
        java.util.Collections.shuffle(keys, new Random(42));
        for (long k : keys) tree.insert(k, new RecordId(1, (int) (k % 32)));

        // Direct search
        for (long k : keys) {
            assertThat(tree.search(k)).as("k=%d", k).isPresent();
        }
        // Range scan covers the same keys
        Set<Long> seen = new HashSet<>();
        var it = tree.rangeScan(0, n - 1);
        while (it.hasNext()) {
            seen.add(it.next().getKey());
        }
        assertThat(seen).hasSize(n);

        indexFile.close();
    }
}
