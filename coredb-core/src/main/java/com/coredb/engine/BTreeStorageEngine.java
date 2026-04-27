package com.coredb.engine;

import com.coredb.api.CoreDBConfig;
import com.coredb.api.Row;
import com.coredb.api.Schema;
import com.coredb.buffer.BufferPool;
import com.coredb.catalog.TableMeta;
import com.coredb.heap.HeapFile;
import com.coredb.heap.RecordId;
import com.coredb.index.BTree;
import com.coredb.index.IndexFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.AbstractMap;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * B+ tree storage engine implementation.
 *
 * <p>Combines a heap file for tuple storage with a B+ tree index on the primary key.
 *
 * <p>File layout per table:
 * <ul>
 *   <li>{@code base/1/<oid>} - heap file with actual row data</li>
 *   <li>{@code base/1/<oid>_pk} - B+ tree index mapping PK to RecordId</li>
 * </ul>
 */
public class BTreeStorageEngine implements StorageEngine {

    private static final Logger log = LoggerFactory.getLogger(BTreeStorageEngine.class);

    // Index files use heap OID + this offset so their pages don't collide in the buffer pool.
    // The offset is persisted in the index file's meta page and recovered on open.
    private static final int INDEX_OID_OFFSET = 0x00100000;

    private final CoreDBConfig config;

    // Engine state (valid after open())
    private BufferPool bufferPool;
    private HeapFile heap;
    private IndexFile indexFile;
    private BTree pkIndex;
    private int pkColumnIndex;

    BTreeStorageEngine(CoreDBConfig config) {
        this.config = config;
    }

    @Override
    public void open(Path dataDir, TableMeta meta, BufferPool bufferPool) throws IOException {
        this.bufferPool = bufferPool;
        Schema schema = meta.schema();
        this.pkColumnIndex = schema.indexOf(meta.pkColumn());
        if (pkColumnIndex < 0) {
            throw new IllegalArgumentException("PK column '" + meta.pkColumn() + "' not found in schema");
        }

        if (schema.column(pkColumnIndex).type() != com.coredb.api.ColumnType.LONG) {
            throw new IllegalArgumentException("BTreeStorageEngine only supports LONG primary keys.");
        }

        // Open or create heap file: base/1/<oid>
        Path heapPath = dataDir.resolve("base/1/" + meta.oid());
        if (Files.exists(heapPath)) {
            this.heap = HeapFile.open(heapPath, meta.oid(), schema, bufferPool);
        } else {
            this.heap = HeapFile.create(heapPath, meta.oid(), schema, bufferPool);
        }

        // Open or create index file: base/1/<oid>_pk
        Path indexPath = dataDir.resolve("base/1/" + meta.oid() + "_pk");
        if (Files.exists(indexPath)) {
            // Recover the file ID from the index file's meta page
            this.indexFile = IndexFile.open(indexPath, bufferPool);
            this.pkIndex = BTree.open(indexFile);
        } else {
            // Allocate a new file ID for the index file
            int indexFileId = meta.oid() + INDEX_OID_OFFSET;
            this.indexFile = IndexFile.create(indexPath, indexFileId, bufferPool);
            this.pkIndex = BTree.create(indexFile);
        }

        log.debug("Opened BTreeStorageEngine for table {} (oid={})", meta.name(), meta.oid());
    }

    @Override
    public void close() throws IOException {
        if (indexFile != null) {
            indexFile.close();
            indexFile = null;
        }
        if (heap != null) {
            heap.close();
            heap = null;
        }
        pkIndex = null;
    }

    @Override
    public void put(long pk, Row row) throws IOException {
        Optional<RecordId> existing = pkIndex.search(pk);
        if (existing.isPresent()) {
            RecordId oldRid = existing.get();

            RecordId newRid = heap.insert(row);
            pkIndex.delete(pk);
            pkIndex.insert(pk, newRid);
            heap.delete(oldRid);

            log.debug("Updated row with pk={}: oldRid={} -> newRid={}", pk, oldRid, newRid);
        } else {
            RecordId rid = heap.insert(row);
            pkIndex.insert(pk, rid);
            log.debug("Inserted row with pk={} at rid={}", pk, rid);
        }
    }

    @Override
    public Optional<Row> get(long pk) throws IOException {
        Optional<RecordId> ridOpt = pkIndex.search(pk);
        if (ridOpt.isEmpty()) {
            return Optional.empty();
        }

        return heap.get(ridOpt.get());
    }

    @Override
    public void delete(long pk) throws IOException {
        Optional<RecordId> ridOpt = pkIndex.search(pk);
        if (ridOpt.isEmpty()) {
            return;
        }

        RecordId rid = ridOpt.get();

        heap.delete(rid);
        pkIndex.delete(pk);

        log.debug("Deleted row with pk={} at rid={}", pk, rid);
    }

    @Override
    public Iterator<Map.Entry<Long, Row>> rangeScan(long fromPk, long toPk) throws IOException {
        Iterator<Map.Entry<Long, RecordId>> indexIterator = pkIndex.rangeScan(fromPk, toPk);

        return new Iterator<>() {
            @Override
            public boolean hasNext() {
                return indexIterator.hasNext();
            }

            @Override
            public Map.Entry<Long, Row> next() {
                Map.Entry<Long, RecordId> entry = indexIterator.next();
                try {
                    Optional<Row> rowOpt = heap.get(entry.getValue());
                    if (rowOpt.isEmpty()) {
                        throw new IllegalStateException("Index points to missing row: " + entry.getValue());
                    }
                    return new AbstractMap.SimpleEntry<>(entry.getKey(), rowOpt.get());
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }
        };
    }

    @Override
    public Iterator<Map.Entry<Long, Row>> fullScan() throws IOException {
        Iterator<Row> heapIterator = heap.scan();

        return new Iterator<>() {
            private Map.Entry<Long, Row> nextEntry = null;

            {
                advance();
            }

            private void advance() {
                while (heapIterator.hasNext()) {
                    Row row = heapIterator.next();
                    Long pk = extractPk(row);
                    if (pk != null) {
                        nextEntry = new AbstractMap.SimpleEntry<>(pk, row);
                        return;
                    }
                }
                nextEntry = null;
            }

            @Override
            public boolean hasNext() {
                return nextEntry != null;
            }

            @Override
            public Map.Entry<Long, Row> next() {
                if (nextEntry == null) {
                    throw new NoSuchElementException();
                }
                Map.Entry<Long, Row> result = nextEntry;
                advance();
                return result;
            }
        };
    }

    @Override
    public void createIndex(String indexName, String columnName) throws IOException {
        throw new UnsupportedOperationException("Secondary indexes not yet implemented");
    }

    @Override
    public void dropIndex(String indexName) throws IOException {
        throw new UnsupportedOperationException("Secondary indexes not yet implemented");
    }

    @Override
    public Iterator<Map.Entry<Long, Row>> indexLookup(String indexName, Object value) throws IOException {
        throw new UnsupportedOperationException("Secondary indexes not yet implemented");
    }

    @Override
    public Iterator<Map.Entry<Long, Row>> indexRangeScan(String indexName, Object from, Object to) throws IOException {
        throw new UnsupportedOperationException("Secondary indexes not yet implemented");
    }

    @Override
    public void flush() throws IOException {
        if (heap != null) {
            heap.flush();
        }
        if (indexFile != null) {
            indexFile.flush();
        }
    }

    private Long extractPk(Row row) {
        if (pkColumnIndex >= row.size()) {
            return null;
        }
        Object pkValue = row.get(pkColumnIndex);
        if (pkValue instanceof Long) {
            return (Long) pkValue;
        }
        if (pkValue instanceof Integer) {
            return ((Integer) pkValue).longValue();
        }
        return null;
    }
}
