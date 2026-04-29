package com.coredb.buffer;

import java.util.HashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Sharded hash table mapping (fileId, pageId) → frameId.
 *
 * 16 partitions, each with its own lock, so threads fetching unrelated pages
 * don't contend on a single global mutex. PostgreSQL uses 128 partitions
 * (BufMappingLock); 16 is sufficient for v1.
 *
 * All lookup/insert/remove calls must be made while holding the partition lock
 * returned by lockFor(key). The caller is responsible for acquiring and releasing
 * the lock — this class does not acquire it internally.
 */
final class BufferTable {

    static final int N_PARTITIONS = 16;

    private final ReentrantLock[] partitionLocks;
    @SuppressWarnings("unchecked")
    private final HashMap<Long, Integer>[] partitions;

    @SuppressWarnings("unchecked")
    BufferTable() {
        partitionLocks = new ReentrantLock[N_PARTITIONS];
        partitions = new HashMap[N_PARTITIONS];
        for (int i = 0; i < N_PARTITIONS; i++) {
            partitionLocks[i] = new ReentrantLock();
            partitions[i] = new HashMap<>();
        }
    }

    static long key(int fileId, int pageId) {
        return ((long) fileId << 32) | (pageId & 0xFFFFFFFFL);
    }

    private int partitionOf(long key) {
        return (int) (key & 0xF);
    }

    ReentrantLock lockFor(long key) {
        return partitionLocks[partitionOf(key)];
    }

    /** Must be called under the partition lock for key. */
    Integer lookup(long key) {
        return partitions[partitionOf(key)].get(key);
    }

    /** Must be called under the partition lock for key. */
    void insert(long key, int frameId) {
        partitions[partitionOf(key)].put(key, frameId);
    }

    /** Must be called under the partition lock for key. */
    void remove(long key) {
        partitions[partitionOf(key)].remove(key);
    }

    /** Removes all entries for a given fileId. Acquires each partition lock in turn. */
    void removeAllForFile(int fileId) {
        for (int i = 0; i < N_PARTITIONS; i++) {
            partitionLocks[i].lock();
            try {
                partitions[i].entrySet().removeIf(e -> (int) (e.getKey() >>> 32) == fileId);
            } finally {
                partitionLocks[i].unlock();
            }
        }
    }
}
