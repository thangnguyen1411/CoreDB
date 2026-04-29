package com.coredb.buffer;

import com.coredb.util.StorageException;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Clock-sweep eviction algorithm for the buffer pool.
 *
 * Serialises hand advancement under its own mutex. This mutex is disjoint from
 * the BufferTable partition locks — never hold both simultaneously.
 *
 * When all frames are pinned, selectVictim() blocks on unpinCondition until a
 * frame is unpinned (via notifyUnpin()) or the timeout expires.
 */
final class ClockSweep {

    private final ReentrantLock sweepMutex = new ReentrantLock();
    private final Condition unpinCondition = sweepMutex.newCondition();
    private int hand;
    private final int frameCount;
    private final long timeoutMs;

    ClockSweep(int frameCount, long timeoutMs) {
        this.frameCount = frameCount;
        this.timeoutMs = timeoutMs;
    }

    /**
     * Selects an unpinned victim frame using clock sweep.
     *
     * Returns the victim's frameId with pinCount already incremented to 1
     * (claimed). Caller owns the claim and must either use it or release it
     * via the victim's headerMutex.
     *
     * Blocks if all frames are pinned, up to timeoutMs, then throws.
     *
     * The sweepMutex is acquired and released entirely inside this method.
     * Caller must NOT hold any partition lock when calling.
     */
    int selectVictim(BufferDescriptor[] descriptors) {
        sweepMutex.lock();
        try {
            long deadline = System.currentTimeMillis() + timeoutMs;
            int attempts = 0;

            while (true) {
                BufferDescriptor frame = descriptors[hand];
                hand = (hand + 1) % frameCount;
                attempts++;

                // Check and potentially claim the frame under its header mutex.
                // sweepMutex → headerMutex ordering is safe: sweepMutex is disjoint
                // from partition locks, and headerMutex is always acquired last.
                frame.headerMutex().lock();
                try {
                    if (frame.pinCount() == 0) {
                        if (frame.usageCount() == 0) {
                            frame.pinForEviction();
                            return frame.frameId();
                        }
                        frame.decrementUsage();
                    }
                } finally {
                    frame.headerMutex().unlock();
                }

                if (attempts >= frameCount * 2) {
                    long remaining = deadline - System.currentTimeMillis();
                    if (remaining <= 0) {
                        throw new StorageException(
                            "No evictable frame found — all " + frameCount + " frames pinned after " + timeoutMs + "ms");
                    }
                    try {
                        unpinCondition.await(remaining, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new StorageException("Interrupted while waiting for evictable frame");
                    }
                    attempts = 0;
                }
            }
        } finally {
            sweepMutex.unlock();
        }
    }

    /**
     * Signals that a frame has been unpinned.
     * Wakes any thread blocked in selectVictim() waiting for an evictable frame.
     */
    void notifyUnpin() {
        sweepMutex.lock();
        try {
            unpinCondition.signalAll();
        } finally {
            sweepMutex.unlock();
        }
    }
}
