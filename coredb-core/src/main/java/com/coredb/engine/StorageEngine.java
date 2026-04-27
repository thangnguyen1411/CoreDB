package com.coredb.engine;

import com.coredb.api.Row;
import com.coredb.buffer.BufferPool;
import com.coredb.catalog.TableMeta;
import com.coredb.wal.XLogWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;

/**
 * The pluggable storage engine interface for CoreDB.
 *
 * <p>This interface defines the contract that all storage engines must satisfy,
 * providing a clean seam between the logical table layer and the physical storage
 * mechanism.</p>
 *
 * <p>Key design decisions:
 * <ul>
 *   <li>{@code put} is an <b>upsert</b> — it inserts new rows or updates existing
 *       ones by primary key, leaving old versions behind (MVCC semantics).</li>
 *   <li>Secondary indexes are declared but stubbed for later.</li>
 *   <li>All operations throw {@link IOException} for storage failures.</li>
 * </ul>
 */
public interface StorageEngine extends AutoCloseable {
    /**
     * Opens or initializes this storage engine for the given table.
     *
     * @param dataDir the data directory path
     * @param meta    table metadata including OID, schema, and engine type
     * @param bufferPool the buffer pool for caching pages
     * @throws IOException if opening fails
     */
    void open(Path dataDir, TableMeta meta, BufferPool bufferPool, XLogWriter xlogWriter)
        throws IOException;

    /**
     * Closes the storage engine, releasing all resources.
     *
     * @throws IOException if closing fails
     */
    @Override
    void close() throws IOException;

    /**
     * Stores a row by primary key. This is an <b>upsert</b> operation:
     * <ul>
     *   <li>If the PK does not exist, inserts a new tuple.</li>
     *   <li>If the PK exists, marks the old tuple as deleted (sets t_xmax)
     *       and inserts a new tuple, updating the index to point to the new RID.</li>
     * </ul>
     *
     * <p>The old tuple remains visible to existing snapshots until VACUUM
     * reclaims it.</p>
     *
     * @param pk  the primary key value
     * @param row the row data to store
     * @throws IOException if the operation fails
     */
    void put(long pk, Row row) throws IOException;

    /**
     * Retrieves a row by primary key.
     *
     * @param pk the primary key value
     * @return the row if found, or empty if not present
     * @throws IOException if the operation fails
     */
    Optional<Row> get(long pk) throws IOException;

    /**
     * Deletes a row by primary key.
     *
     * <p>Implementation should set t_xmax on the tuple to mark it deleted.
     * The index entry should also be removed. The tuple space is not reclaimed
     * until VACUUM.</p>
     *
     * @param pk the primary key value
     * @throws IOException if the operation fails
     */
    void delete(long pk) throws IOException;

    /**
     * Scans a range of primary keys, inclusive at both ends.
     *
     * <p>Returns entries in ascending PK order. The iterator is lazy —
     * it should not load all entries into memory.</p>
     *
     * @param fromPk the start key (inclusive)
     * @param toPk   the end key (inclusive)
     * @return an iterator over (PK, Row) entries in ascending order
     * @throws IOException if the operation fails
     */
    Iterator<Map.Entry<Long, Row>> rangeScan(long fromPk, long toPk)
        throws IOException;

    /**
     * Returns an iterator over all rows in the table.
     *
     * <p>The iteration order is engine-defined (heap order for B-tree engine).</p>
     *
     * @return an iterator over all (PK, Row) entries
     * @throws IOException if the operation fails
     */
    Iterator<Map.Entry<Long, Row>> fullScan() throws IOException;

    // Secondary index operations — declared but stubbed for now

    /**
     * Creates a secondary index on the specified column.
     *
     * @param indexName  the name of the index
     * @param columnName the column to index
     * @throws IOException                  if the operation fails
     * @throws UnsupportedOperationException if not yet implemented
     */
    void createIndex(String indexName, String columnName) throws IOException;

    /**
     * Drops a secondary index.
     *
     * @param indexName the name of the index
     * @throws IOException                  if the operation fails
     * @throws UnsupportedOperationException if not yet implemented
     */
    void dropIndex(String indexName) throws IOException;

    /**
     * Looks up rows by an indexed column value.
     *
     * <p>For non-unique indexes, uses composite key convention:
     * (secondary_value, pk) as the key.</p>
     *
     * @param indexName the name of the index
     * @param value     the value to look up
     * @return an iterator over (PK, Row) entries
     * @throws IOException                  if the operation fails
     * @throws UnsupportedOperationException if not yet implemented
     */
    Iterator<Map.Entry<Long, Row>> indexLookup(String indexName, Object value)
        throws IOException;

    /**
     * Scans a range of values in a secondary index.
     *
     * @param indexName the name of the index
     * @param from      the start value (inclusive)
     * @param to        the end value (inclusive)
     * @return an iterator over (PK, Row) entries in ascending (value, pk) order
     * @throws IOException                  if the operation fails
     * @throws UnsupportedOperationException if not yet implemented
     */
    Iterator<Map.Entry<Long, Row>> indexRangeScan(
        String indexName,
        Object from,
        Object to
    ) throws IOException;

    /**
     * Flushes all dirty data to disk.
     *
     * <p>This provides durability without WAL.
     * Callers should fsync both heap and index files.</p>
     *
     * @throws IOException if the operation fails
     */
    void flush() throws IOException;
}
