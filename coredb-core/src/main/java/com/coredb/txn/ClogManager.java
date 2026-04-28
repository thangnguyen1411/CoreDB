package com.coredb.txn;

import java.io.IOException;
import java.nio.file.Path;
import com.coredb.util.CorruptionException;

/**
 * Commit Log Manager interface (pg_xact equivalent).
 *
 * <p>Stores transaction status (in-progress, committed, aborted) using 2 bits per XID.
 * Implementations provide file-backed or in-memory storage of transaction statuses.</p>
 */
public interface ClogManager extends AutoCloseable {

    /**
     * Creates a fresh pg_xact file.
     *
     * @param dataDir the data directory
     * @return a new ClogManager instance
     * @throws IOException if file creation fails
     */
    static ClogManager create(Path dataDir) throws IOException {
        return FileClogManager.create(dataDir);
    }

    /**
     * Opens an existing pg_xact file.
     *
     * @param dataDir the data directory
     * @return a ClogManager instance with state loaded from disk
     * @throws IOException if file cannot be read
     * @throws CorruptionException if magic or version check fails
     */
    static ClogManager open(Path dataDir) throws IOException {
        return FileClogManager.open(dataDir);
    }

    /**
     * Returns the status of a transaction.
     *
     * @param xid the transaction ID
     * @return the status (IN_PROGRESS if never set)
     * @throws IllegalArgumentException if xid is negative or 0 (INVALID_XID)
     */
    Status getStatus(int xid);

    /**
     * Marks a transaction as committed.
     *
     * @param xid the transaction ID
     * @throws IllegalArgumentException if xid is negative, 0, or 1
     */
    void setCommitted(int xid);

    /**
     * Marks a transaction as aborted.
     *
     * @param xid the transaction ID
     * @throws IllegalArgumentException if xid is negative, 0, or 1
     */
    void setAborted(int xid);

    /**
     * Flushes all dirty status changes to disk.
     *
     * @throws IOException if write fails
     */
    void flush() throws IOException;

    @Override
    void close() throws IOException;

    /**
     * Returns the number of XID entries currently tracked.
     */
    int entryCount();

    /**
     * Returns statistics about clog contents.
     */
    Stats getStats();

    /**
     * Statistics about clog contents.
     */
    record Stats(int entries, int inProgress, int committed, int aborted, int cacheBytes) {
        @Override
        public String toString() {
            return String.format(
                "entries=%d  in-progress=%d  committed=%d  aborted=%d  cache-bytes=%d",
                entries, inProgress, committed, aborted, cacheBytes);
        }
    }

    /**
     * Transaction status values.
     */
    enum Status {
        IN_PROGRESS,
        COMMITTED,
        ABORTED
    }
}
