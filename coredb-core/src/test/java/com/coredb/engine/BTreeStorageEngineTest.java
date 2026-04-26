package com.coredb.engine;

import com.coredb.api.CoreDBConfig;
import com.coredb.catalog.TableMeta;
import com.coredb.config.EngineType;
import com.coredb.heap.HeapFile;

import java.io.IOException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract tests for {@link BTreeStorageEngine}.
 *
 * <p>Full MVCC upsert support with dead version verification.</p>
 */
class BTreeStorageEngineTest extends StorageEngineContractTest {

    @Override
    protected StorageEngine createEngine(Path dataDir, TableMeta meta) throws IOException {
        CoreDBConfig config = CoreDBConfig.defaults();
        StorageEngine engine = StorageEngineFactory.create(EngineType.BTREE, config);
        engine.open(dataDir, meta);
        return engine;
    }

    /**
     * Verifies that after an upsert, a dead tuple version exists in the heap.
     * This is the MVCC semantics check - old version is marked deleted but
     * remains physically present until VACUUM.
     */
    @Override
    protected void assertDeadVersionExists(Path dataDir, TableMeta meta, long pk) {
        // Open heap file directly to count physical tuples
        Path heapPath = dataDir.resolve("base/1/" + meta.oid());

        try (HeapFile hf = HeapFile.open(heapPath, meta.oid(), meta.schema())) {
            int liveCount = 0;
            int deadCount = 0;
            int totalCount = 0;

            // Scan all pages and count tuple versions
            for (int pageId = 1; pageId < hf.pageCount(); pageId++) {
                com.coredb.page.Page page = hf.readPage(pageId);
                if (page.pageType() != com.coredb.page.PageType.HEAP) {
                    continue;
                }
                com.coredb.heap.HeapPage heapPage = new com.coredb.heap.HeapPage(page);

                // Iterate through all line pointers (including dead ones)
                for (int slot = 1; slot < heapPage.linePointerCount(); slot++) {
                    try {
                        byte[] raw = heapPage.get(slot);
                        totalCount++;

                        // Parse header to check visibility
                        java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(raw).order(java.nio.ByteOrder.BIG_ENDIAN);
                        com.coredb.heap.HeapTupleHeader header = com.coredb.heap.HeapTupleHeader.readFrom(buf, 0);

                        // In our stub visibility model:
                        // - xmin = BOOTSTRAP_XID, xmax = INVALID_XID  => live
                        // - xmin = BOOTSTRAP_XID, xmax != INVALID_XID => dead (deleted)
                        if (header.xmin() == com.coredb.util.Constants.BOOTSTRAP_XID) {
                            if (header.xmax() == com.coredb.util.Constants.INVALID_XID) {
                                liveCount++;
                            } else {
                                deadCount++;
                            }
                        }
                    } catch (Exception e) {
                        // Skip invalid/deleted slots
                    }
                }
            }

            // After upsert: should have 1 live (new version) + 1 dead (old version) = 2 total
            assertThat(liveCount)
                .as("Expected 1 live tuple after upsert")
                .isEqualTo(1);
            assertThat(deadCount)
                .as("Expected 1 dead tuple version after upsert (MVCC semantics)")
                .isEqualTo(1);
            assertThat(totalCount)
                .as("Expected 2 physical tuples total (1 live + 1 dead)")
                .isEqualTo(2);

        } catch (IOException e) {
            throw new RuntimeException("Failed to verify dead version exists", e);
        }
    }

    private TableMeta createTestTableMeta() {
        com.coredb.api.Schema schema = com.coredb.api.Schema.of(
            com.coredb.api.Column.longCol("id").withNullable(false),
            com.coredb.api.Column.stringCol("name"),
            com.coredb.api.Column.intCol("age")
        );
        return new TableMeta(1002, "test_table", schema, "id", EngineType.BTREE);
    }
}
