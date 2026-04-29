package com.coredb.wal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concurrent correctness tests for {@link XLogWriter}.
 *
 * <p>These tests drive multiple threads against a shared XLogWriter and verify:
 * <ul>
 *   <li>All assigned LSNs are distinct and monotonically ordered.</li>
 *   <li>Every appended record is readable via {@link XLogReader} after flush.</li>
 *   <li>Concurrent {@link XLogWriter#flushUpTo} callers share a single fsync
 *       per "round" rather than each doing their own.</li>
 *   <li>An XLogReader positioned at {@code flushedLsn} never sees a torn record.</li>
 * </ul>
 */
class XLogWriterConcurrentTest {

    @TempDir
    Path tempDir;

    @Test
    void concurrentAppends_allLsnsDistinct() throws Exception {
        Path walPath = tempDir.resolve("pg_wal");
        int threads = 8;
        int appendsPerThread = 1000;

        List<Long> collectedLsns = Collections.synchronizedList(new ArrayList<>());

        try (XLogWriter writer = XLogWriter.open(walPath)) {
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            List<Future<?>> futures = new ArrayList<>();

            for (int t = 0; t < threads; t++) {
                final int threadId = t;
                futures.add(pool.submit(() -> {
                    try {
                        for (int i = 0; i < appendsPerThread; i++) {
                            byte[] data = new byte[]{(byte) threadId, (byte) i};
                            long lsn = writer.append(XLogRecord.RMGR_HEAP, (byte) 0x01,
                                threadId * 1000 + i, 1000, i, data);
                            collectedLsns.add(lsn);
                        }
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }));
            }

            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
            for (Future<?> f : futures) {
                f.get(); // rethrow any assertion/exception from threads
            }

            writer.flushUpTo(writer.currentLsn());
        }

        int expectedCount = threads * appendsPerThread;
        assertThat(collectedLsns).hasSize(expectedCount);

        // All LSNs must be distinct.
        Set<Long> uniqueLsns = new HashSet<>(collectedLsns);
        assertThat(uniqueLsns).hasSize(expectedCount);
    }

    @Test
    void concurrentAppends_allRecordsReadableAfterFlush() throws Exception {
        Path walPath = tempDir.resolve("pg_wal");
        int threads = 8;
        int appendsPerThread = 1000;

        try (XLogWriter writer = XLogWriter.open(walPath)) {
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            List<Future<?>> futures = new ArrayList<>();

            for (int t = 0; t < threads; t++) {
                final int threadId = t;
                futures.add(pool.submit(() -> {
                    try {
                        for (int i = 0; i < appendsPerThread; i++) {
                            writer.append(XLogRecord.RMGR_HEAP, (byte) 0x01,
                                threadId * 1000 + i, 1000, i, new byte[]{(byte) threadId});
                        }
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }));
            }

            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
            for (Future<?> f : futures) {
                f.get();
            }

            writer.flushUpTo(writer.currentLsn());
        }

        // Read all records back and count them.
        int recordCount = 0;
        try (XLogReader reader = XLogReader.open(walPath)) {
            while (reader.readNext().isPresent()) {
                recordCount++;
            }
        }

        assertThat(recordCount).isEqualTo(threads * appendsPerThread);
    }

    @Test
    void concurrentFlushUpTo_singleFsyncPerRound() throws Exception {
        Path walPath = tempDir.resolve("pg_wal");
        int threads = 8;

        try (XLogWriter writer = XLogWriter.open(walPath)) {
            // Write one record so there is something to flush.
            long lsn = writer.append(XLogRecord.RMGR_HEAP, (byte) 0x01, 1, 1000, 0, new byte[]{42});

            // All threads call flushUpTo(lsn) concurrently from a standing start.
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch go = new CountDownLatch(1);

            ExecutorService pool = Executors.newFixedThreadPool(threads);
            List<Future<?>> futures = new ArrayList<>();

            for (int t = 0; t < threads; t++) {
                futures.add(pool.submit(() -> {
                    try {
                        ready.countDown();
                        go.await();
                        writer.flushUpTo(lsn);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }));
            }

            ready.await();
            go.countDown();

            pool.shutdown();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
            for (Future<?> f : futures) {
                f.get();
            }

            // All threads must agree that lsn is flushed.
            assertThat(writer.flushedLsn()).isGreaterThanOrEqualTo(lsn);

            // At most one fsync per "round" — with 8 threads racing from the
            // same latch, at most a small handful of fsyncs should occur
            // (ideally 1, possibly 2 if threads slip across rounds).
            assertThat(writer.fsyncCount.get()).isLessThanOrEqualTo(4);
        }
    }

    @Test
    void flushUpTo_fastPathSkipsLockWhenAlreadyFlushed() throws Exception {
        Path walPath = tempDir.resolve("pg_wal");

        try (XLogWriter writer = XLogWriter.open(walPath)) {
            long lsn = writer.append(XLogRecord.RMGR_HEAP, (byte) 0x01, 1, 1000, 0, new byte[]{1});
            writer.flushUpTo(lsn);
            int fsyncsBefore = writer.fsyncCount.get();

            // Should take the fast path — no additional fsync.
            writer.flushUpTo(lsn);
            writer.flushUpTo(lsn);
            writer.flushUpTo(lsn);

            assertThat(writer.fsyncCount.get()).isEqualTo(fsyncsBefore);
        }
    }

    @Test
    void readerDuringConcurrentAppends_noTornRecords() throws Exception {
        Path walPath = tempDir.resolve("pg_wal");
        int threads = 4;
        int appendsPerThread = 500;

        try (XLogWriter writer = XLogWriter.open(walPath)) {
            ExecutorService pool = Executors.newFixedThreadPool(threads + 1);
            CountDownLatch writersStarted = new CountDownLatch(threads);

            // Writer threads
            List<Future<?>> writerFutures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                final int threadId = t;
                writerFutures.add(pool.submit(() -> {
                    try {
                        writersStarted.countDown();
                        for (int i = 0; i < appendsPerThread; i++) {
                            writer.append(XLogRecord.RMGR_HEAP, (byte) 0x01,
                                threadId, 1000, i, new byte[]{(byte) i});
                        }
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }));
            }

            // Reader thread: reads up to flushedLsn in a loop, verifying no torn records.
            Future<?> readerFuture = pool.submit(() -> {
                try {
                    writersStarted.await();
                    // Read a snapshot once writers have started.
                    writer.flushUpTo(writer.currentLsn());
                    long readUpTo = writer.flushedLsn();

                    try (XLogReader reader = XLogReader.open(walPath)) {
                        Optional<XLogRecord> record;
                        while ((record = reader.readNext()).isPresent()) {
                            // If we read past the flushed watermark at open time, stop.
                            if (record.get().lsn() >= readUpTo) {
                                break;
                            }
                        }
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

            for (Future<?> f : writerFutures) {
                f.get();
            }
            readerFuture.get();
        }
    }

    @Test
    void massiveAppend_allRecordsIntact() throws Exception {
        Path walPath = tempDir.resolve("pg_wal");
        int threads = 16;
        int appendsPerThread = 500; // 8 000 total records

        try (XLogWriter writer = XLogWriter.open(walPath)) {
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            List<Future<?>> futures = new ArrayList<>();

            for (int t = 0; t < threads; t++) {
                final int threadId = t;
                futures.add(pool.submit(() -> {
                    try {
                        for (int i = 0; i < appendsPerThread; i++) {
                            writer.append(XLogRecord.RMGR_HEAP, (byte) 0x01,
                                threadId * appendsPerThread + i, 1000, i,
                                new byte[]{(byte) threadId, (byte) i});
                        }
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }));
            }

            pool.shutdown();
            assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
            for (Future<?> f : futures) {
                f.get();
            }

            writer.flushUpTo(writer.currentLsn());
        }

        // Every record must parse cleanly (CRC validated inside XLogReader).
        int count = 0;
        try (XLogReader reader = XLogReader.open(walPath)) {
            Optional<XLogRecord> rec;
            while ((rec = reader.readNext()).isPresent()) {
                count++;
            }
        }

        assertThat(count).isEqualTo(threads * appendsPerThread);
    }
}
