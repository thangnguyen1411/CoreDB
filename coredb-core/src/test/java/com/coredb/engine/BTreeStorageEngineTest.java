package com.coredb.engine;

import com.coredb.api.CoreDBConfig;
import com.coredb.catalog.TableMeta;
import com.coredb.config.EngineType;
import com.coredb.heap.HeapFile;
import com.coredb.heap.HeapTupleHeader;

import java.io.IOException;
import java.nio.ByteBuffer;
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
        return StorageEngineFactory.create(EngineType.BTREE, config);
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
                HeapFile.PinnedPage pinned = hf.readPage(pageId);
                com.coredb.page.Page page = pinned.page();
                if (page.pageType() != com.coredb.page.PageType.HEAP) {
                    pinned.unpin(false);
                    continue;
                }
                com.coredb.heap.HeapPage heapPage = new com.coredb.heap.HeapPage(page);

                // Iterate through all line pointers (including dead ones)
                for (int slot = 0; slot < heapPage.slotCount(); slot++) {
                    try {
                        // Read ItemId directly from page to access both live and dead tuples
                        com.coredb.page.Page pageData = heapPage.page();
                        int itemId = pageData.readItemId(slot);

                        // Skip unused slots (offset = 0)
                        int offset = com.coredb.page.ItemId.offset(itemId);
                        if (offset == 0) continue;

                        totalCount++;

                        // Read tuple header directly from page buffer at the offset
                        ByteBuffer buf = pageData.buffer();
                        HeapTupleHeader header = HeapTupleHeader.readFrom(buf, offset);

                        if (header.xmax() == com.coredb.util.Constants.INVALID_XID) {
                            liveCount++;
                        } else {
                            deadCount++;
                        }
                    } catch (Exception e) {
                        // Skip invalid slots
                    }
                }
                pinned.unpin(false);
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
}
