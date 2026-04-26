package com.coredb.engine;

import com.coredb.api.CoreDBConfig;
import com.coredb.api.Row;
import com.coredb.api.Schema;
import com.coredb.catalog.TableMeta;
import com.coredb.heap.HeapFile;
import com.coredb.heap.RecordId;
import com.coredb.index.BTree;
import com.coredb.index.IndexFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
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

    private final CoreDBConfig config;

    // Engine state (valid after open())
    private HeapFile heap;
    private IndexFile indexFile;
    private BTree pkIndex;
    private int pkColumnIndex;

    BTreeStorageEngine(CoreDBConfig config) {
        this.config = config;
    }

    @Override
    public void open(Path dataDir, TableMeta meta) throws IOException {
        // Resolve PK column index for extracting PK values from rows
        Schema schema = meta.schema();
        this.pkColumnIndex = schema.indexOf(meta.pkColumn());
        if (pkColumnIndex < 0) {
            throw new IllegalArgumentException("PK column '" + meta.pkColumn() + "' not found in schema");
        }

        // Validate PK column is LONG type (our B+ tree only supports long keys for now)
        if (schema.column(pkColumnIndex).type() != com.coredb.api.ColumnType.LONG) {
            throw new IllegalArgumentException("BTreeStorageEngine only supports LONG primary keys.");
        }

        // Open or create heap file: base/1/<oid>
        Path heapPath = dataDir.resolve("base/1/" + meta.oid());
        if (java.nio.file.Files.exists(heapPath)) {
            this.heap = HeapFile.open(heapPath, meta.oid(), schema);
        } else {
            this.heap = HeapFile.create(heapPath, meta.oid(), schema);
        }

        // Open or create index file: base/1/<oid>_pk
        Path indexPath = dataDir.resolve("base/1/" + meta.oid() + "_pk");
        if (java.nio.file.Files.exists(indexPath)) {
            this.indexFile = IndexFile.open(indexPath, meta.oid());
            this.pkIndex = BTree.open(indexFile);
        } else {
            this.indexFile = IndexFile.create(indexPath, meta.oid());
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
            heap.delete(oldRid);
            pkIndex.delete(pk);
            pkIndex.insert(pk, newRid);

            log.debug("Updated row with pk={}: oldRid={} -> newRid={}", pk, oldRid, newRid);
        } else {
            // INSERT path
            RecordId rid = heap.insert(row);
            pkIndex.insert(pk, rid);
            log.debug("Inserted row with pk={} at rid={}", pk, rid);
        }
    }

    @Override
    public Optional<Row> get(long pk) throws IOException {
        // Search index for RecordId
        Optional<RecordId> ridOpt = pkIndex.search(pk);
        if (ridOpt.isEmpty()) {
            return Optional.empty();
        }

        // Fetch row from heap
        return heap.get(ridOpt.get());
    }

    @Override
    public void delete(long pk) throws IOException {
        // Search index for RecordId
        Optional<RecordId> ridOpt = pkIndex.search(pk);
        if (ridOpt.isEmpty()) {
            // PK not found, nothing to delete
            return;
        }

        RecordId rid = ridOpt.get();

        // Delete from heap (sets t_xmax)
        heap.delete(rid);

        // Delete from index
        pkIndex.delete(pk);

        log.debug("Deleted row with pk={} at rid={}", pk, rid);
    }

    @Override
    public Iterator<Map.Entry<Long, Row>> rangeScan(long fromPk, long toPk) throws IOException {
        // Use B+ tree range scan to get (pk, RecordId) pairs
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
                        // This shouldn't happen if the engine is consistent
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
        // Scan heap and extract PK from each row
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

    /**
     * Extracts the primary key value from a row.
     *
     * @param row the row to extract from
     * @return the PK value as Long, or null if extraction fails
     */
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
