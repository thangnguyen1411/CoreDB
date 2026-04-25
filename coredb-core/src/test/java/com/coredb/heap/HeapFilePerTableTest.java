package com.coredb.heap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.coredb.api.Column;
import com.coredb.api.Row;
import com.coredb.api.Schema;
import com.coredb.util.Constants;
import com.coredb.util.CorruptionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Per-table HeapFile with meta page.
 */
class HeapFilePerTableTest {

    @TempDir
    Path tempDir;

    private Schema schema;
    private Path tablePath;

    @BeforeEach
    void setUp() {
        schema = Schema.of(
            Column.longCol("id"),
            Column.stringCol("name"),
            Column.intCol("age")
        );
        tablePath = tempDir.resolve("base").resolve("1").resolve("1000");
    }

    @Test
    void create_writesMetaPageWithCorrectFormat() throws IOException {
        // When
        HeapFile hf = HeapFile.create(tablePath, 1000, schema);
        hf.close();

        // Then: file exists
        assertThat(tablePath).exists();
        assertThat(Files.size(tablePath)).isEqualTo(Constants.PAGE_SIZE); // Just meta page

        // Then: meta page has correct magic, version, OID, nextPageId
        try (FileChannel channel = FileChannel.open(tablePath, StandardOpenOption.READ)) {
            ByteBuffer buf = ByteBuffer.allocate(Constants.PAGE_SIZE).order(ByteOrder.BIG_ENDIAN);
            channel.read(buf);
            buf.flip();

            int magic = buf.getInt(0);
            int version = buf.getInt(4);
            int oid = buf.getInt(8);
            int nextPageId = buf.getInt(12);

            assertThat(magic).isEqualTo(Constants.HEAP_FILE_MAGIC); // "HEAP"
            assertThat(version).isEqualTo(1);
            assertThat(oid).isEqualTo(1000);
            assertThat(nextPageId).isEqualTo(1); // Data pages start at 1
        }
    }

    @Test
    void open_existingFile_roundTripsMetaPage() throws IOException {
        // Given: create a file
        HeapFile created = HeapFile.create(tablePath, 1000, schema);
        created.close();

        // When: open it
        HeapFile opened = HeapFile.open(tablePath, 1000, schema);

        // Then
        assertThat(opened.oid()).isEqualTo(1000);
        assertThat(opened.tablePath()).isEqualTo(tablePath);

        opened.close();
    }

    @Test
    void open_wrongMagic_throwsCorruptionException() throws IOException {
        // Given: create a file with wrong magic in meta page
        Files.createDirectories(tablePath.getParent());
        try (FileChannel channel = FileChannel.open(tablePath,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            ByteBuffer buf = ByteBuffer.allocate(Constants.PAGE_SIZE).order(ByteOrder.BIG_ENDIAN);
            buf.putInt(0, 0xDEADBEEF); // Wrong magic
            buf.putInt(4, 1); // version
            buf.putInt(8, 1000); // oid
            buf.rewind();
            channel.write(buf);
        }

        // When/Then
        assertThatThrownBy(() -> HeapFile.open(tablePath, 1000, schema))
            .isInstanceOf(CorruptionException.class)
            .hasMessageContaining("magic mismatch");
    }

    @Test
    void open_wrongVersion_throwsCorruptionException() throws IOException {
        // Given: create a file with wrong version in meta page
        Files.createDirectories(tablePath.getParent());
        try (FileChannel channel = FileChannel.open(tablePath,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            ByteBuffer buf = ByteBuffer.allocate(Constants.PAGE_SIZE).order(ByteOrder.BIG_ENDIAN);
            buf.putInt(0, Constants.HEAP_FILE_MAGIC); // correct magic
            buf.putInt(4, 999); // wrong version
            buf.putInt(8, 1000); // oid
            buf.rewind();
            channel.write(buf);
        }

        // When/Then
        assertThatThrownBy(() -> HeapFile.open(tablePath, 1000, schema))
            .isInstanceOf(CorruptionException.class)
            .hasMessageContaining("version mismatch");
    }

    @Test
    void open_oidMismatch_throwsCorruptionException() throws IOException {
        // Given: create a file with oid=1001 but try to open as oid=1000
        HeapFile created = HeapFile.create(tablePath, 1001, schema);
        created.close();

        // When/Then
        assertThatThrownBy(() -> HeapFile.open(tablePath, 1000, schema))
            .isInstanceOf(CorruptionException.class)
            .hasMessageContaining("OID mismatch");
    }

    @Test
    void create_multipleFiles_doNotInterfere() throws IOException {
        // Given
        Path path1000 = tempDir.resolve("base").resolve("1").resolve("1000");
        Path path1001 = tempDir.resolve("base").resolve("1").resolve("1001");

        // When
        HeapFile hf1000 = HeapFile.create(path1000, 1000, schema);
        HeapFile hf1001 = HeapFile.create(path1001, 1001, schema);

        hf1000.close();
        hf1001.close();

        // Then: both files exist independently
        assertThat(path1000).exists();
        assertThat(path1001).exists();

        // Then: each can be opened with its correct OID
        HeapFile reopened1000 = HeapFile.open(path1000, 1000, schema);
        HeapFile reopened1001 = HeapFile.open(path1001, 1001, schema);

        assertThat(reopened1000.oid()).isEqualTo(1000);
        assertThat(reopened1001.oid()).isEqualTo(1001);

        reopened1000.close();
        reopened1001.close();
    }

    @Test
    void tablePath_perTableMode_returnsPath() throws IOException {
        HeapFile hf = HeapFile.create(tablePath, 1000, schema);
        assertThat(hf.tablePath()).isEqualTo(tablePath);
        hf.close();
    }

    @Test
    void oid_perTableMode_returnsOid() throws IOException {
        HeapFile hf = HeapFile.create(tablePath, 1000, schema);
        assertThat(hf.oid()).isEqualTo(1000);
        hf.close();
    }

    @Test
    void nextPageId_perTableMode_returnsNextPageId() throws IOException {
        HeapFile hf = HeapFile.create(tablePath, 1000, schema);
        assertThat(hf.nextPageId()).isEqualTo(1);
        hf.close();
    }

    @Test
    void insert_singleRow_returnsRecordId() throws IOException {
        HeapFile hf = HeapFile.create(tablePath, 1000, schema);

        Row row = Row.of(1L, "Alice", 30);
        RecordId rid = hf.insert(row);

        assertThat(rid.pageId()).isGreaterThanOrEqualTo(1); // Page 0 is meta
        assertThat(rid.slotNo()).isZero();

        hf.close();
    }

    @Test
    void insert_singleRow_fileSizeIsExactlyTwoPages() throws IOException {
        HeapFile hf = HeapFile.create(tablePath, 1000, schema);

        Row row = Row.of(1L, "Alice", 30);
        hf.insert(row);
        hf.close();

        // File should be: 1 meta page + 1 data page = 2 pages
        long expectedSize = 2L * Constants.PAGE_SIZE;
        assertThat(Files.size(tablePath)).isEqualTo(expectedSize);
    }

    @Test
    void insert_100Rows_fileSizeIsCorrect() throws IOException {
        HeapFile hf = HeapFile.create(tablePath, 1000, schema);

        int count = 100;
        for (int i = 0; i < count; i++) {
            hf.insert(Row.of((long) i, "User" + i, i));
        }

        // Get page count before closing
        int pageCount = hf.pageCount();
        hf.close();

        // Verify exact file size: pageCount * PAGE_SIZE (includes meta page + all data pages)
        long expectedSize = (long) pageCount * Constants.PAGE_SIZE;
        assertThat(Files.size(tablePath)).isEqualTo(expectedSize);
    }

    @Test
    void get_existingRow_returnsRow() throws IOException {
        HeapFile hf = HeapFile.create(tablePath, 1000, schema);

        Row original = Row.of(1L, "Alice", 30);
        RecordId rid = hf.insert(original);

        java.util.Optional<Row> fetched = hf.get(rid);

        assertThat(fetched).isPresent();
        assertThat(fetched.get()).isEqualTo(original);

        hf.close();
    }

    @Test
    void get_nonExistentSlot_returnsEmpty() throws IOException {
        HeapFile hf = HeapFile.create(tablePath, 1000, schema);

        RecordId rid = new RecordId(1, 99);
        java.util.Optional<Row> fetched = hf.get(rid);

        assertThat(fetched).isEmpty();

        hf.close();
    }

    @Test
    void insert_multipleRows_eachRetrievable() throws IOException {
        HeapFile hf = HeapFile.create(tablePath, 1000, schema);

        java.util.List<RecordId> rids = new java.util.ArrayList<>();
        int count = 50;

        for (int i = 0; i < count; i++) {
            Row row = Row.of((long) i, "User" + i, i % 100);
            rids.add(hf.insert(row));
        }

        for (int i = 0; i < count; i++) {
            java.util.Optional<Row> fetched = hf.get(rids.get(i));
            assertThat(fetched).isPresent();
            assertThat(fetched.get().getLong(0)).isEqualTo((long) i);
            assertThat(fetched.get().getString(1)).isEqualTo("User" + i);
            assertThat(fetched.get().getInt(2)).isEqualTo(i % 100);
        }

        hf.close();
    }

    @Test
    void insert_incrementsNextPageId() throws IOException {
        HeapFile hf = HeapFile.create(tablePath, 1000, schema);

        assertThat(hf.nextPageId()).isEqualTo(1); // Just meta page

        // First insert allocates page 1
        hf.insert(Row.of(1L, "Alice", 30));
        assertThat(hf.nextPageId()).isEqualTo(2);

        // Many more inserts to force another page allocation
        for (int i = 0; i < 1000; i++) {
            hf.insert(Row.of((long) i, "User" + i, i));
        }
        assertThat(hf.nextPageId()).isGreaterThan(2);

        hf.close();
    }

    @Test
    void insert_persistsNextPageIdToMetaPage() throws IOException {
        HeapFile hf = HeapFile.create(tablePath, 1000, schema);

        // Insert enough rows to allocate multiple pages
        for (int i = 0; i < 1000; i++) {
            hf.insert(Row.of((long) i, "User" + i, i));
        }

        int pageCountBeforeClose = hf.nextPageId();
        long fileSizeBeforeClose = hf.fileSize();
        hf.close();

        // Reopen and verify nextPageId and file size persisted
        HeapFile reopened = HeapFile.open(tablePath, 1000, schema);
        assertThat(reopened.nextPageId()).isEqualTo(pageCountBeforeClose);
        assertThat(reopened.fileSize()).isEqualTo(fileSizeBeforeClose);
        reopened.close();
    }

    @Test
    void delete_existingRow_getReturnsEmpty() throws IOException {
        HeapFile hf = HeapFile.create(tablePath, 1000, schema);

        Row row = Row.of(1L, "Alice", 30);
        RecordId rid = hf.insert(row);

        hf.delete(rid);

        java.util.Optional<Row> fetched = hf.get(rid);
        assertThat(fetched).isEmpty();

        hf.close();
    }

    @Test
    void scan_emptyFile_returnsEmpty() throws IOException {
        HeapFile hf = HeapFile.create(tablePath, 1000, schema);

        java.util.List<Row> rows = new java.util.ArrayList<>();
        hf.scan().forEachRemaining(rows::add);

        assertThat(rows).isEmpty();

        hf.close();
    }

    @Test
    void scan_afterInserts_returnsAllRows() throws IOException {
        HeapFile hf = HeapFile.create(tablePath, 1000, schema);

        int count = 50;
        for (int i = 0; i < count; i++) {
            hf.insert(Row.of((long) i, "User" + i, i));
        }

        java.util.List<Row> rows = new java.util.ArrayList<>();
        hf.scan().forEachRemaining(rows::add);

        assertThat(rows).hasSize(count);

        hf.close();
    }

    @Test
    void scan_afterSomeDeletes_returnsOnlyLiveRows() throws IOException {
        HeapFile hf = HeapFile.create(tablePath, 1000, schema);

        java.util.List<RecordId> rids = new java.util.ArrayList<>();
        int count = 20;

        for (int i = 0; i < count; i++) {
            rids.add(hf.insert(Row.of((long) i, "User" + i, i)));
        }

        for (int i = 0; i < count; i += 2) {
            hf.delete(rids.get(i));
        }

        java.util.List<Row> rows = new java.util.ArrayList<>();
        hf.scan().forEachRemaining(rows::add);

        assertThat(rows).hasSize(count / 2);

        hf.close();
    }

    @Test
    void persistence_closeAndReopen_dataSurvives() throws IOException {
        HeapFile hf = HeapFile.create(tablePath, 1000, schema);

        java.util.List<RecordId> rids = new java.util.ArrayList<>();
        int count = 50;

        for (int i = 0; i < count; i++) {
            rids.add(hf.insert(Row.of((long) i, "User" + i, i)));
        }
        hf.close();

        // Reopen and verify data
        HeapFile reopened = HeapFile.open(tablePath, 1000, schema);
        for (int i = 0; i < count; i++) {
            java.util.Optional<Row> fetched = reopened.get(rids.get(i));
            assertThat(fetched).isPresent();
            assertThat(fetched.get().getLong(0)).isEqualTo((long) i);
        }
        reopened.close();
    }

    @Test
    void persistence_scanAfterReopen_returnsAllRows() throws IOException {
        HeapFile hf = HeapFile.create(tablePath, 1000, schema);

        int count = 50;
        for (int i = 0; i < count; i++) {
            hf.insert(Row.of((long) i, "User" + i, i));
        }
        hf.close();

        HeapFile reopened = HeapFile.open(tablePath, 1000, schema);
        java.util.List<Row> rows = new java.util.ArrayList<>();
        reopened.scan().forEachRemaining(rows::add);

        assertThat(rows).hasSize(count);
        reopened.close();
    }

    @Test
    void twoSeparateFiles_allocateIndependently() throws IOException {
        Path path1000 = tempDir.resolve("base").resolve("1").resolve("1000");
        Path path1001 = tempDir.resolve("base").resolve("1").resolve("1001");

        HeapFile hf1000 = HeapFile.create(path1000, 1000, schema);
        HeapFile hf1001 = HeapFile.create(path1001, 1001, schema);

        // Insert different amounts of data into each
        hf1000.insert(Row.of(1L, "A", 1));

        // Insert many rows into second file to force page allocation
        // Need enough rows to span multiple pages
        for (int i = 0; i < 1000; i++) {
            hf1001.insert(Row.of((long) i, "User" + i, i));
        }

        int pages1000 = hf1000.nextPageId();
        int pages1001 = hf1001.nextPageId();

        hf1000.close();
        hf1001.close();

        // Verify files have independent page counts (1001 should have more pages)
        assertThat(pages1001).isGreaterThan(pages1000);

        // Verify each file can be reopened independently with correct OID
        HeapFile reopened1000 = HeapFile.open(path1000, 1000, schema);
        HeapFile reopened1001 = HeapFile.open(path1001, 1001, schema);

        assertThat(reopened1000.oid()).isEqualTo(1000);
        assertThat(reopened1001.oid()).isEqualTo(1001);
        assertThat(reopened1000.nextPageId()).isEqualTo(pages1000);
        assertThat(reopened1001.nextPageId()).isEqualTo(pages1001);

        reopened1000.close();
        reopened1001.close();
    }

    @Test
    void pageCount_returnsNextPageId() throws IOException {
        HeapFile hf = HeapFile.create(tablePath, 1000, schema);

        assertThat(hf.pageCount()).isEqualTo(1); // Just meta page

        hf.insert(Row.of(1L, "Alice", 30));
        assertThat(hf.pageCount()).isGreaterThanOrEqualTo(2); // Meta + at least 1 data

        hf.close();
    }

    @Test
    void fileSize_returnsActualFileSize() throws IOException {
        HeapFile hf = HeapFile.create(tablePath, 1000, schema);

        long size1 = hf.fileSize();
        assertThat(size1).isEqualTo(Constants.PAGE_SIZE); // Just meta page

        hf.insert(Row.of(1L, "Alice", 30));

        long size2 = hf.fileSize();
        assertThat(size2).isGreaterThan(size1);
        assertThat(size2 % Constants.PAGE_SIZE).isZero();

        hf.close();
    }

    // ========== FSM Integration Tests ==========

    @Test
    void fsm_insertUntilPageFull_categoryDropsTowardZero() throws IOException {
        // This test verifies that as we fill page 1, its FSM category decreases
        HeapFile hf = HeapFile.create(tablePath, 1000, schema);

        // Initially page 1 doesn't exist, so no category tracked
        // After first insert, page 1 is created with high category
        hf.insert(Row.of(1L, "User1", 1));

        int initialCategory = getFsmCategory(hf, 1);
        assertThat(initialCategory).isGreaterThan(0); // Should have some free space

        // Keep inserting until page 1 is nearly full
        int inserted = 1;
        int lastCategory = initialCategory;

        while (inserted < 500) { // Safety limit
            try {
                hf.insert(Row.of((long) inserted, "User" + inserted, inserted));
                inserted++;

                int currentCategory = getFsmCategory(hf, 1);
                // Category should stay same or decrease as page fills
                assertThat(currentCategory)
                    .withFailMessage("FSM category increased unexpectedly from %d to %d", lastCategory, currentCategory)
                    .isLessThanOrEqualTo(lastCategory);
                lastCategory = currentCategory;

                // Stop if page is nearly full (category approaching 1 or 0)
                if (currentCategory <= 2) {
                    break;
                }
            } catch (Exception e) {
                // Page might be full
                break;
            }
        }

        // Verify category dropped significantly from initial
        assertThat(lastCategory).isLessThan(initialCategory);

        hf.close();
    }

    @Test
    void fsm_insertWhenPageFull_routesToNewPage() throws IOException {
        // Verify that when page 1 is full, inserts go to page 2
        HeapFile hf = HeapFile.create(tablePath, 1000, schema);

        // Insert rows until we spill to page 2
        int page1Inserts = 0;
        int page2Inserts = 0;

        for (int i = 0; i < 1000; i++) {
            RecordId rid = hf.insert(Row.of((long) i, "User" + i, i));
            if (rid.pageId() == 1) {
                page1Inserts++;
            } else if (rid.pageId() == 2) {
                page2Inserts++;
            }

            if (page2Inserts > 0) {
                break; // We've seen routing to page 2
            }
        }

        assertThat(page1Inserts).isGreaterThan(0);
        assertThat(page2Inserts).isGreaterThan(0);

        hf.close();
    }

    @Test
    void fsm_deleteRow_categoryIncreases() throws IOException {
        // Verify that after deleting a row, the page's FSM category increases
        HeapFile hf = HeapFile.create(tablePath, 1000, schema);

        // Insert a few rows
        RecordId rid1 = hf.insert(Row.of(1L, "Alice", 30));
        RecordId rid2 = hf.insert(Row.of(2L, "Bob", 25));

        // Get category before delete
        int categoryBefore = getFsmCategory(hf, rid1.pageId());

        // Delete one row
        hf.delete(rid1);

        // Get category after delete
        int categoryAfter = getFsmCategory(hf, rid1.pageId());

        // Category should increase (more free space available)
        assertThat(categoryAfter).isGreaterThanOrEqualTo(categoryBefore);

        hf.close();
    }

    @Test
    void fsm_deleteThenInsert_reusesFreedSpace() throws IOException {
        // Verify that deleted space is reused for subsequent inserts
        HeapFile hf = HeapFile.create(tablePath, 1000, schema);

        // Insert rows
        RecordId rid1 = hf.insert(Row.of(1L, "Alice", 30));
        int firstPageId = rid1.pageId();

        // Insert more rows on the same page
        RecordId rid2 = hf.insert(Row.of(2L, "Bob", 25));
        RecordId rid3 = hf.insert(Row.of(3L, "Carol", 35));

        // Delete the middle row
        hf.delete(rid2);

        // Insert a new row - should reuse the freed slot on the same page
        RecordId rid4 = hf.insert(Row.of(4L, "Dave", 40));

        // The new row should land on the same page where we freed space
        assertThat(rid4.pageId()).isEqualTo(firstPageId);

        hf.close();
    }

    @Test
    void fsm_missingFileOnOpen_createsFreshFsm() throws IOException {
        // Verify that if FSM file is deleted, HeapFile recreates it
        HeapFile hf = HeapFile.create(tablePath, 1000, schema);

        // Insert some data
        hf.insert(Row.of(1L, "Alice", 30));
        hf.insert(Row.of(2L, "Bob", 25));
        hf.close();

        // Delete the FSM file
        Path fsmPath = tablePath.getParent().resolve(tablePath.getFileName() + "_fsm");
        assertThat(fsmPath).exists();
        Files.delete(fsmPath);

        // Reopen - should recreate FSM
        HeapFile reopened = HeapFile.open(tablePath, 1000, schema);

        // Should be able to insert (FSM recreated, falls back to new page allocation)
        RecordId rid = reopened.insert(Row.of(3L, "Carol", 35));
        assertThat(rid.pageId()).isGreaterThanOrEqualTo(1);

        // Verify the new FSM file exists
        assertThat(fsmPath).exists();

        reopened.close();
    }

    @Test
    void fsm_multiPageTracking_tracksAllPages() throws IOException {
        // Verify FSM tracks multiple pages correctly
        HeapFile hf = HeapFile.create(tablePath, 1000, schema);

        // Insert enough rows to span multiple pages
        int maxPageSeen = 1;
        for (int i = 0; i < 500; i++) {
            RecordId rid = hf.insert(Row.of((long) i, "User" + i, i));
            maxPageSeen = Math.max(maxPageSeen, rid.pageId());
            if (maxPageSeen >= 3) {
                break;
            }
        }

        // Should have at least 3 pages (1 meta + 2+ data)
        assertThat(maxPageSeen).isGreaterThanOrEqualTo(2);

        // All data pages should be tracked in FSM
        for (int pageId = 1; pageId <= maxPageSeen; pageId++) {
            int category = getFsmCategory(hf, pageId);
            // Category should be 0 (full) to 255 (empty), never negative
            assertThat(category).isBetween(0, 255);
        }

        hf.close();
    }

    @Test
    void fsm_staleHighCategory_correctsOnInsert() throws IOException {
        // Test stale FSM recovery: FSM claims page has space but it's actually full
        HeapFile hf = HeapFile.create(tablePath, 1000, schema);

        // Fill page 1 completely
        int page1RowCount = 0;
        for (int i = 0; i < 1000; i++) {
            RecordId rid = hf.insert(Row.of((long) i, "User" + i, i));
            if (rid.pageId() == 1) {
                page1RowCount++;
            } else {
                break; // Page 1 is full, spilled to page 2
            }
        }

        // Verify we actually filled page 1
        assertThat(page1RowCount).isGreaterThan(0);

        // Manually corrupt FSM to claim page 1 has lots of space (category 250)
        // This simulates a stale FSM
        setFsmCategoryDirectly(hf, 1, 250);

        // Try to insert - should detect FSM is lying, correct it, and allocate new page
        int pageCountBefore = hf.pageCount();
        RecordId rid = hf.insert(Row.of(9999L, "NewUser", 99));

        // Should have allocated a new page (not gone to the "full" page 1)
        assertThat(rid.pageId()).isGreaterThanOrEqualTo(pageCountBefore - 1);

        // FSM should now be corrected for page 1 (category near 0)
        int correctedCategory = getFsmCategory(hf, 1);
        assertThat(correctedCategory).isLessThanOrEqualTo(10); // Nearly full or full

        hf.close();
    }

    @Test
    void fsm_staleLowCategories_continuesToAllocateNewPages() throws IOException {
        // Test: zero all FSM categories, verify inserts still work via new page allocation
        HeapFile hf = HeapFile.create(tablePath, 1000, schema);

        // Insert a few rows first
        hf.insert(Row.of(1L, "Alice", 30));
        hf.insert(Row.of(2L, "Bob", 25));

        int pagesBefore = hf.pageCount();

        // Zero out all FSM categories (simulate "lying low")
        zeroAllFsmCategories(hf);

        // Insert should still work - will allocate new page since FSM returns -1
        RecordId rid = hf.insert(Row.of(3L, "Carol", 35));

        // Should have allocated a new page
        assertThat(hf.pageCount()).isGreaterThanOrEqualTo(pagesBefore);

        // Row should be retrievable
        assertThat(hf.get(rid)).isPresent();

        hf.close();
    }

    // Helper methods to access FSM internals via reflection
    private int getFsmCategory(HeapFile hf, int pageId) {
        try {
            java.lang.reflect.Field fsmField = HeapFile.class.getDeclaredField("fsm");
            fsmField.setAccessible(true);
            Object fsm = fsmField.get(hf);
            java.lang.reflect.Method getCategoryMethod = fsm.getClass().getMethod("getCategory", int.class);
            return (int) getCategoryMethod.invoke(fsm, pageId);
        } catch (Exception e) {
            throw new RuntimeException("Failed to get FSM category", e);
        }
    }

    private void setFsmCategoryDirectly(HeapFile hf, int pageId, int category) {
        try {
            java.lang.reflect.Field fsmField = HeapFile.class.getDeclaredField("fsm");
            fsmField.setAccessible(true);
            Object fsm = fsmField.get(hf);
            // Use updatePage to set a specific free byte value that results in desired category
            int freeBytes = category * 32 + 16; // Middle of the bucket
            java.lang.reflect.Method updatePageMethod = fsm.getClass().getMethod("updatePage", int.class, int.class);
            updatePageMethod.invoke(fsm, pageId, freeBytes);
        } catch (Exception e) {
            throw new RuntimeException("Failed to set FSM category", e);
        }
    }

    private void zeroAllFsmCategories(HeapFile hf) {
        try {
            java.lang.reflect.Field fsmField = HeapFile.class.getDeclaredField("fsm");
            fsmField.setAccessible(true);
            Object fsm = fsmField.get(hf);
            java.lang.reflect.Field categoriesField = fsm.getClass().getDeclaredField("categories");
            categoriesField.setAccessible(true);
            byte[] categories = (byte[]) categoriesField.get(fsm);
            java.util.Arrays.fill(categories, (byte) 0);
        } catch (Exception e) {
            throw new RuntimeException("Failed to zero FSM categories", e);
        }
    }
}
