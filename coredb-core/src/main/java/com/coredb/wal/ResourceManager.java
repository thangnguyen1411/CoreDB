package com.coredb.wal;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Resource manager interface for WAL redo operations.
 *
 * <p>Resource managers provide modular redo functionality. Each storage subsystem
 * (heap, btree, xlog) implements its own resource manager that knows how to replay
 * its specific operation types against a raw page buffer.</p>
 *
 * <p>The redo() method takes a ByteBuffer directly, not a BufferPool. During recovery,
 * pages may not be in the pool. The caller (RecoveryManager) is responsible for
 * resolving the record's target file and page, reading the page into a temporary
 * buffer, calling redo(), and writing the page back.</p>
 *
 * <p>This design matches PostgreSQL's RmgrData interface, where each rmgr is
 * responsible only for *what changes on a single page*.</p>
 */
public interface ResourceManager {

    /**
     * Returns the resource manager ID (rmgrId) for this manager.
     *
     * @return the rmgrId (HEAP=1, BTREE=2, XLOG=3)
     */
    byte rmgrId();

    /**
     * Replays a WAL record against a target page buffer.
     *
     * <p>The caller has already resolved the record's (tableOid, pageId) to a file,
     * read that page into targetPage, and will write it back after redo() returns.
     * The rmgr only needs to apply the specific changes encoded in the record.</p>
     *
     * @param record the WAL record to replay
     * @param targetPage the page buffer to modify (must be exactly PAGE_SIZE bytes)
     * @throws IOException if an I/O error occurs during redo
     */
    void redo(XLogRecord record, ByteBuffer targetPage) throws IOException;
}
