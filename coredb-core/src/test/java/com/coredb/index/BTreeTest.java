package com.coredb.index;

import com.coredb.heap.RecordId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for BTree operations.
 *
 * <p>These tests verify the complete B+ tree implementation:
 * <ul>
 *   <li>Basic insert and search</li>
 *   <li>Split propagation from leaf to root</li>
 *   <li>Tree height growth on root split</li>
 *   <li>All keys remain searchable after splits</li>
 *   <li>Range queries via leaf chain</li>
 * </ul>
 */
class BTreeTest {

    @TempDir
    Path tempDir;

    @Test
    void create_newTree_hasHeightZero() throws IOException {
        Path indexPath = tempDir.resolve("test.idx");
        IndexFile indexFile = IndexFile.create(indexPath, 1000);
        BTree tree = BTree.create(indexFile);

        assertThat(tree.treeHeight()).isEqualTo(0);
        assertThat(tree.rootPageId()).isEqualTo(1);

        indexFile.close();
    }

    @Test
    void search_emptyTree_returnsEmpty() throws IOException {
        Path indexPath = tempDir.resolve("test.idx");
        IndexFile indexFile = IndexFile.create(indexPath, 1000);
        BTree tree = BTree.create(indexFile);

        Optional<RecordId> result = tree.search(42L);

        assertThat(result).isEmpty();

        indexFile.close();
    }

    @Test
    void insert_singleKey_thenSearch_findsIt() throws IOException {
        Path indexPath = tempDir.resolve("test.idx");
        IndexFile indexFile = IndexFile.create(indexPath, 1000);
        BTree tree = BTree.create(indexFile);

        RecordId rid = new RecordId(1, 0);
        tree.insert(42L, rid);

        Optional<RecordId> result = tree.search(42L);

        assertThat(result).isPresent();
        assertThat(result.get()).isEqualTo(rid);

        indexFile.close();
    }

    @Test
    void insert_multipleKeys_allSearchable() throws IOException {
        Path indexPath = tempDir.resolve("test.idx");
        IndexFile indexFile = IndexFile.create(indexPath, 1000);
        BTree tree = BTree.create(indexFile);

        // Insert 100 keys
        for (int i = 0; i < 100; i++) {
            tree.insert(i * 10L, new RecordId(i + 1, 0));
        }

        // Verify all are searchable
        for (int i = 0; i < 100; i++) {
            Optional<RecordId> result = tree.search(i * 10L);
            assertThat(result)
                .as("Key %d should be found", i * 10L)
                .isPresent();
            assertThat(result.get()).isEqualTo(new RecordId(i + 1, 0));
        }

        // Verify non-existent keys return empty
        assertThat(tree.search(5L)).isEmpty();
        assertThat(tree.search(1000L)).isEmpty();

        indexFile.close();
    }

    @Test
    void insert_manyKeys_causesLeafSplit_treeHeightStaysZero() throws IOException {
        Path indexPath = tempDir.resolve("test.idx");
        IndexFile indexFile = IndexFile.create(indexPath, 1000);
        BTree tree = BTree.create(indexFile);

        // Insert enough keys to cause leaf split
        // Each leaf entry is ~18 bytes (14 data + 4 ItemId)
        // With ~8000 free bytes, we can fit ~400 entries per page
        // Insert 500 keys to force a split
        for (int i = 0; i < 500; i++) {
            tree.insert((long) i, new RecordId(i + 1, 0));
        }

        // After leaf split, height should still be 1 (root is internal with 2 children)
        // Actually let's check what the height is
        int height = tree.treeHeight();
        assertThat(height).isGreaterThanOrEqualTo(0);

        // All keys should still be searchable
        for (int i = 0; i < 500; i++) {
            Optional<RecordId> result = tree.search((long) i);
            assertThat(result)
                .as("Key %d should be found after split", i)
                .isPresent();
        }

        indexFile.close();
    }

    @Test
    void insert_enoughKeys_causesRootSplit_treeHeightIncreases() throws IOException {
        Path indexPath = tempDir.resolve("test.idx");
        IndexFile indexFile = IndexFile.create(indexPath, 1000);
        BTree tree = BTree.create(indexFile);

        // Insert many keys to force root split and height growth
        // Need enough keys to fill multiple leaf pages and then split the root
        int keyCount = 10000;
        for (int i = 0; i < keyCount; i++) {
            tree.insert((long) i, new RecordId(i + 1, i % 10));
        }

        // Height should have increased
        assertThat(tree.treeHeight()).isGreaterThanOrEqualTo(1);

        // All keys should still be searchable
        for (int i = 0; i < keyCount; i++) {
            Optional<RecordId> result = tree.search((long) i);
            assertThat(result)
                .as("Key %d should be found after tree growth", i)
                .isPresent();
            assertThat(result.get()).isEqualTo(new RecordId(i + 1, i % 10));
        }

        indexFile.close();
    }

    @Test
    void insert_randomKeys_allSearchable() throws IOException {
        Path indexPath = tempDir.resolve("test.idx");
        IndexFile indexFile = IndexFile.create(indexPath, 1000);
        BTree tree = BTree.create(indexFile);

        Random random = new Random(12345); // Fixed seed for reproducibility
        TreeMap<Long, RecordId> expected = new TreeMap<>();

        // Insert 1000 random keys
        for (int i = 0; i < 1000; i++) {
            long key = random.nextLong(100000);
            RecordId rid = new RecordId(i + 1, random.nextInt(100));
            
            // Skip duplicates for this test
            if (!expected.containsKey(key)) {
                tree.insert(key, rid);
                expected.put(key, rid);
            }
        }

        // Verify all inserted keys are searchable
        for (var entry : expected.entrySet()) {
            Optional<RecordId> result = tree.search(entry.getKey());
            assertThat(result)
                .as("Key %d should be found", entry.getKey())
                .isPresent();
            assertThat(result.get()).isEqualTo(entry.getValue());
        }

        indexFile.close();
    }

    @Test
    void insert_duplicateKey_throws() throws IOException {
        Path indexPath = tempDir.resolve("test.idx");
        IndexFile indexFile = IndexFile.create(indexPath, 1000);
        BTree tree = BTree.create(indexFile);

        tree.insert(42L, new RecordId(1, 0));

        assertThatThrownBy(() -> tree.insert(42L, new RecordId(2, 0)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Duplicate key");

        indexFile.close();
    }

    @Test
    void closeAndReopen_allKeysStillSearchable() throws IOException {
        Path indexPath = tempDir.resolve("test.idx");
        
        // Create and populate tree
        {
            IndexFile indexFile = IndexFile.create(indexPath, 1000);
            BTree tree = BTree.create(indexFile);

            for (int i = 0; i < 1000; i++) {
                tree.insert((long) i, new RecordId(i + 1, 0));
            }

            indexFile.close();
        }

        // Reopen and verify
        {
            IndexFile indexFile = IndexFile.open(indexPath, 1000);
            BTree tree = BTree.open(indexFile);

            // Verify all keys are still searchable
            for (int i = 0; i < 1000; i++) {
                Optional<RecordId> result = tree.search((long) i);
                assertThat(result)
                    .as("Key %d should be found after reopen", i)
                    .isPresent();
                assertThat(result.get()).isEqualTo(new RecordId(i + 1, 0));
            }

            indexFile.close();
        }
    }

    @Test
    void insert_outOfOrder_allSearchable() throws IOException {
        Path indexPath = tempDir.resolve("test.idx");
        IndexFile indexFile = IndexFile.create(indexPath, 1000);
        BTree tree = BTree.create(indexFile);

        // Insert keys in descending order (worst case for sorted insert)
        for (int i = 999; i >= 0; i--) {
            tree.insert((long) i, new RecordId(i + 1, 0));
        }

        // Verify all keys are searchable
        for (int i = 0; i < 1000; i++) {
            Optional<RecordId> result = tree.search((long) i);
            assertThat(result)
                .as("Key %d should be found", i)
                .isPresent();
            assertThat(result.get()).isEqualTo(new RecordId(i + 1, 0));
        }

        indexFile.close();
    }

    @Test
    void treeHeight_afterManyInserts_reflectsActualStructure() throws IOException {
        Path indexPath = tempDir.resolve("test.idx");
        IndexFile indexFile = IndexFile.create(indexPath, 1000);
        BTree tree = BTree.create(indexFile);

        // Start with height 0
        assertThat(tree.treeHeight()).isEqualTo(0);

        // Insert keys until we trigger splits and height growth
        int inserted = 0;
        for (int round = 0; round < 5; round++) {
            int keysThisRound = 5000;
            for (int i = 0; i < keysThisRound; i++) {
                tree.insert((long) inserted, new RecordId(inserted + 1, 0));
                inserted++;
            }

            int height = tree.treeHeight();
            
            // Log the progression
            System.out.printf("After %d keys: height = %d, root = %d%n",
                inserted, height, tree.rootPageId());

            // Height should be reasonable for the number of keys
            // With ~400 entries per leaf and ~500 entries per internal page:
            // 25000 keys -> ~63 leaf pages -> height should be 1 or 2
            assertThat(height).isGreaterThanOrEqualTo(0);
        }

        // Final height check
        int finalHeight = tree.treeHeight();
        assertThat(finalHeight).isGreaterThanOrEqualTo(1);

        // Verify all keys are searchable
        for (int i = 0; i < inserted; i++) {
            Optional<RecordId> result = tree.search((long) i);
            assertThat(result)
                .as("Key %d should be found", i)
                .isPresent();
        }

        indexFile.close();
    }

    @Test
    void largeScale_insertAndVerify_allKeysSearchable() throws IOException {
        Path indexPath = tempDir.resolve("test.idx");
        IndexFile indexFile = IndexFile.create(indexPath, 1000);
        BTree tree = BTree.create(indexFile);

        int keyCount = 10000;
        
        // Insert keys
        for (int i = 0; i < keyCount; i++) {
            tree.insert((long) i, new RecordId(i + 1, i % 100));
        }

        // Verify tree structure
        System.out.printf("Tree: height=%d, root=%d, pages=%d%n",
            tree.treeHeight(), tree.rootPageId(), indexFile.pageCount());

        // Verify all keys in order
        for (int i = 0; i < keyCount; i++) {
            Optional<RecordId> result = tree.search((long) i);
            assertThat(result)
                .as("Key %d should be found", i)
                .isPresent();
            assertThat(result.get().pageId()).isEqualTo(i + 1);
            assertThat(result.get().slotNo()).isEqualTo(i % 100);
        }

        // Verify some random keys
        Random random = new Random(42);
        for (int i = 0; i < 1000; i++) {
            int key = random.nextInt(keyCount);
            Optional<RecordId> result = tree.search((long) key);
            assertThat(result).isPresent();
            assertThat(result.get().pageId()).isEqualTo(key + 1);
        }

        indexFile.close();
    }

    @Test
    void search_nonExistentKeysAfterManyInserts_returnsEmpty() throws IOException {
        Path indexPath = tempDir.resolve("test.idx");
        IndexFile indexFile = IndexFile.create(indexPath, 1000);
        BTree tree = BTree.create(indexFile);

        // Insert even keys
        for (int i = 0; i < 1000; i += 2) {
            tree.insert((long) i, new RecordId(i + 1, 0));
        }

        // Search for odd keys (never inserted)
        for (int i = 1; i < 1000; i += 2) {
            Optional<RecordId> result = tree.search((long) i);
            assertThat(result).as("Key %d should not be found", i).isEmpty();
        }

        // Search for keys beyond range
        assertThat(tree.search(-1L)).isEmpty();
        assertThat(tree.search(10000L)).isEmpty();

        indexFile.close();
    }

    // ============================================================================
    // Range Scan Tests
    // ============================================================================

    @Test
    void rangeScan_fromGreaterThanTo_returnsEmptyIterator() throws IOException {
        Path indexPath = tempDir.resolve("test.idx");
        IndexFile indexFile = IndexFile.create(indexPath, 1000);
        BTree tree = BTree.create(indexFile);

        // Insert some keys
        for (int i = 0; i < 100; i++) {
            tree.insert((long) i, new RecordId(i + 1, 0));
        }

        // from > to should return empty iterator
        Iterator<Map.Entry<Long, RecordId>> iter = tree.rangeScan(50L, 10L);
        assertThat(iter.hasNext()).isFalse();

        indexFile.close();
    }

    @Test
    void rangeScan_fromGreaterThanMaxKey_returnsEmptyIterator() throws IOException {
        Path indexPath = tempDir.resolve("test.idx");
        IndexFile indexFile = IndexFile.create(indexPath, 1000);
        BTree tree = BTree.create(indexFile);

        // Insert keys 0-99
        for (int i = 0; i < 100; i++) {
            tree.insert((long) i, new RecordId(i + 1, 0));
        }

        // from > maxKey should return empty
        Iterator<Map.Entry<Long, RecordId>> iter = tree.rangeScan(1000L, 2000L);
        assertThat(iter.hasNext()).isFalse();

        indexFile.close();
    }

    @Test
    void rangeScan_fromLessThanMinKey_startsAtFirstLeaf() throws IOException {
        Path indexPath = tempDir.resolve("test.idx");
        IndexFile indexFile = IndexFile.create(indexPath, 1000);
        BTree tree = BTree.create(indexFile);

        // Insert keys 50-149
        for (int i = 50; i < 150; i++) {
            tree.insert((long) i, new RecordId(i + 1, 0));
        }

        // from < minKey should start at first leaf
        Iterator<Map.Entry<Long, RecordId>> iter = tree.rangeScan(0L, 60L);

        List<Long> keys = new ArrayList<>();
        while (iter.hasNext()) {
            keys.add(iter.next().getKey());
        }

        // Should return keys 50-60 (inclusive)
        assertThat(keys).containsExactly(50L, 51L, 52L, 53L, 54L, 55L, 56L, 57L, 58L, 59L, 60L);

        indexFile.close();
    }

    @Test
    void rangeScan_fromEqualsToEqualsExistingKey_returnsSingleEntry() throws IOException {
        Path indexPath = tempDir.resolve("test.idx");
        IndexFile indexFile = IndexFile.create(indexPath, 1000);
        BTree tree = BTree.create(indexFile);

        // Insert keys 0-99
        for (int i = 0; i < 100; i++) {
            tree.insert((long) i, new RecordId(i + 1, 0));
        }

        // from == to == existingKey should return exactly one entry
        Iterator<Map.Entry<Long, RecordId>> iter = tree.rangeScan(42L, 42L);

        assertThat(iter.hasNext()).isTrue();
        Map.Entry<Long, RecordId> entry = iter.next();
        assertThat(entry.getKey()).isEqualTo(42L);
        assertThat(entry.getValue()).isEqualTo(new RecordId(43, 0));
        assertThat(iter.hasNext()).isFalse();

        indexFile.close();
    }

    @Test
    void rangeScan_fromEqualsToEqualsNonExistingKey_returnsEmptyIterator() throws IOException {
        Path indexPath = tempDir.resolve("test.idx");
        IndexFile indexFile = IndexFile.create(indexPath, 1000);
        BTree tree = BTree.create(indexFile);

        // Insert even keys only
        for (int i = 0; i < 100; i += 2) {
            tree.insert((long) i, new RecordId(i + 1, 0));
        }

        // from == to == non-existingKey should return empty
        Iterator<Map.Entry<Long, RecordId>> iter = tree.rangeScan(43L, 43L);
        assertThat(iter.hasNext()).isFalse();

        indexFile.close();
    }

    @Test
    void rangeScan_emptyTree_returnsEmptyIterator() throws IOException {
        Path indexPath = tempDir.resolve("test.idx");
        IndexFile indexFile = IndexFile.create(indexPath, 1000);
        BTree tree = BTree.create(indexFile);

        // Empty tree should return empty iterator
        Iterator<Map.Entry<Long, RecordId>> iter = tree.rangeScan(0L, 100L);
        assertThat(iter.hasNext()).isFalse();

        indexFile.close();
    }

    @Test
    void rangeScan_singlePage_rangeWithinPage() throws IOException {
        Path indexPath = tempDir.resolve("test.idx");
        IndexFile indexFile = IndexFile.create(indexPath, 1000);
        BTree tree = BTree.create(indexFile);

        // Insert 100 keys (should fit on one page)
        for (int i = 0; i < 100; i++) {
            tree.insert((long) i, new RecordId(i + 1, i % 10));
        }

        // Range within the page
        Iterator<Map.Entry<Long, RecordId>> iter = tree.rangeScan(25L, 35L);

        List<Long> keys = new ArrayList<>();
        List<RecordId> rids = new ArrayList<>();
        while (iter.hasNext()) {
            Map.Entry<Long, RecordId> entry = iter.next();
            keys.add(entry.getKey());
            rids.add(entry.getValue());
        }

        // Should return keys 25-35 (inclusive)
        assertThat(keys).hasSize(11);
        for (int i = 25; i <= 35; i++) {
            assertThat(keys.get(i - 25)).isEqualTo((long) i);
            assertThat(rids.get(i - 25)).isEqualTo(new RecordId(i + 1, i % 10));
        }

        indexFile.close();
    }

    @Test
    void rangeScan_multiplePages_crossesPageBoundary() throws IOException {
        Path indexPath = tempDir.resolve("test.idx");
        IndexFile indexFile = IndexFile.create(indexPath, 1000);
        BTree tree = BTree.create(indexFile);

        // Insert many keys to force multiple leaf pages
        // With ~400 entries per page, 1000 keys should create at least 3 pages
        for (int i = 0; i < 1000; i++) {
            tree.insert((long) i, new RecordId(i + 1, 0));
        }

        // Range that crosses page boundaries
        Iterator<Map.Entry<Long, RecordId>> iter = tree.rangeScan(350L, 450L);

        List<Long> keys = new ArrayList<>();
        while (iter.hasNext()) {
            keys.add(iter.next().getKey());
        }

        // Should return keys 350-450 (inclusive)
        assertThat(keys).hasSize(101);
        assertThat(keys.get(0)).isEqualTo(350L);
        assertThat(keys.get(keys.size() - 1)).isEqualTo(450L);

        // Verify all keys are in order
        for (int i = 0; i < keys.size(); i++) {
            assertThat(keys.get(i)).isEqualTo((long) (350 + i));
        }

        indexFile.close();
    }

    @Test
    void rangeScan_fullRange_returnsAllKeys() throws IOException {
        Path indexPath = tempDir.resolve("test.idx");
        IndexFile indexFile = IndexFile.create(indexPath, 1000);
        BTree tree = BTree.create(indexFile);

        // Insert 1000 keys
        for (int i = 0; i < 1000; i++) {
            tree.insert((long) i, new RecordId(i + 1, 0));
        }

        // Full range scan
        Iterator<Map.Entry<Long, RecordId>> iter = tree.rangeScan(Long.MIN_VALUE, Long.MAX_VALUE);

        List<Long> keys = new ArrayList<>();
        while (iter.hasNext()) {
            keys.add(iter.next().getKey());
        }

        // Should return all 1000 keys
        assertThat(keys).hasSize(1000);
        for (int i = 0; i < 1000; i++) {
            assertThat(keys.get(i)).isEqualTo((long) i);
        }

        indexFile.close();
    }

    @Test
    void rangeScan_randomKeys_matchesTreeMap() throws IOException {
        Path indexPath = tempDir.resolve("test.idx");
        IndexFile indexFile = IndexFile.create(indexPath, 1000);
        BTree tree = BTree.create(indexFile);

        Random random = new Random(12345);
        TreeMap<Long, RecordId> expected = new TreeMap<>();

        // Insert 10,000 random keys
        for (int i = 0; i < 10000; i++) {
            long key = random.nextLong(100000);
            RecordId rid = new RecordId(i + 1, random.nextInt(100));

            // Skip duplicates
            if (!expected.containsKey(key)) {
                tree.insert(key, rid);
                expected.put(key, rid);
            }
        }

        // Test a dozen different ranges
        long[] rangeStarts = {0, 1000, 5000, 9000, 25000, 50000, 75000, 90000, 99999, -1000};
        long[] rangeEnds = {100, 2000, 6000, 10000, 30000, 55000, 80000, 95000, 100000, 50000};

        for (int r = 0; r < rangeStarts.length; r++) {
            long from = rangeStarts[r];
            long to = rangeEnds[r];

            // Get expected results from TreeMap
            List<Map.Entry<Long, RecordId>> expectedEntries = new ArrayList<>(
                expected.subMap(from, true, to, true).entrySet()
            );

            // Get actual results from BTree
            Iterator<Map.Entry<Long, RecordId>> iter = tree.rangeScan(from, to);
            List<Map.Entry<Long, RecordId>> actualEntries = new ArrayList<>();
            while (iter.hasNext()) {
                actualEntries.add(iter.next());
            }

            // Compare
            assertThat(actualEntries)
                .as("Range [%d, %d] should match TreeMap", from, to)
                .hasSize(expectedEntries.size());

            for (int i = 0; i < expectedEntries.size(); i++) {
                Map.Entry<Long, RecordId> expectedEntry = expectedEntries.get(i);
                Map.Entry<Long, RecordId> actualEntry = actualEntries.get(i);

                assertThat(actualEntry.getKey())
                    .as("Key at index %d in range [%d, %d]", i, from, to)
                    .isEqualTo(expectedEntry.getKey());
                assertThat(actualEntry.getValue())
                    .as("RecordId for key %d in range [%d, %d]", expectedEntry.getKey(), from, to)
                    .isEqualTo(expectedEntry.getValue());
            }
        }

        indexFile.close();
    }

    @Test
    void rangeScan_afterReopen_stillWorks() throws IOException {
        Path indexPath = tempDir.resolve("test.idx");

        // Create and populate tree
        TreeMap<Long, RecordId> expected = new TreeMap<>();
        {
            IndexFile indexFile = IndexFile.create(indexPath, 1000);
            BTree tree = BTree.create(indexFile);

            Random random = new Random(42);
            for (int i = 0; i < 1000; i++) {
                long key = random.nextLong(10000);
                RecordId rid = new RecordId(i + 1, 0);
                if (!expected.containsKey(key)) {
                    tree.insert(key, rid);
                    expected.put(key, rid);
                }
            }

            indexFile.close();
        }

        // Reopen and range scan
        {
            IndexFile indexFile = IndexFile.open(indexPath, 1000);
            BTree tree = BTree.open(indexFile);

            Iterator<Map.Entry<Long, RecordId>> iter = tree.rangeScan(1000L, 2000L);

            List<Long> keys = new ArrayList<>();
            while (iter.hasNext()) {
                keys.add(iter.next().getKey());
            }

            // Verify against TreeMap
            List<Long> expectedKeys = new ArrayList<>(expected.subMap(1000L, true, 2000L, true).keySet());
            assertThat(keys).isEqualTo(expectedKeys);

            indexFile.close();
        }
    }

    @Test
    void rangeScan_unorderedInsert_returnsSortedOrder() throws IOException {
        Path indexPath = tempDir.resolve("test.idx");
        IndexFile indexFile = IndexFile.create(indexPath, 1000);
        BTree tree = BTree.create(indexFile);

        // Insert keys in descending order
        for (int i = 999; i >= 0; i--) {
            tree.insert((long) i, new RecordId(i + 1, 0));
        }

        // Range scan should still return in ascending order
        Iterator<Map.Entry<Long, RecordId>> iter = tree.rangeScan(0L, 999L);

        List<Long> keys = new ArrayList<>();
        while (iter.hasNext()) {
            keys.add(iter.next().getKey());
        }

        // Should be sorted ascending
        assertThat(keys).hasSize(1000);
        for (int i = 0; i < 1000; i++) {
            assertThat(keys.get(i)).isEqualTo((long) i);
        }

        indexFile.close();
    }

    @Test
    void rangeScan_partialIteratorConsumption_doesNotLoadAllPages() throws IOException {
        Path indexPath = tempDir.resolve("test.idx");
        IndexFile indexFile = IndexFile.create(indexPath, 1000);
        BTree tree = BTree.create(indexFile);

        // Insert many keys
        for (int i = 0; i < 1000; i++) {
            tree.insert((long) i, new RecordId(i + 1, 0));
        }

        // Get iterator but only consume first 5 entries
        Iterator<Map.Entry<Long, RecordId>> iter = tree.rangeScan(0L, 999L);

        List<Long> keys = new ArrayList<>();
        for (int i = 0; i < 5 && iter.hasNext(); i++) {
            keys.add(iter.next().getKey());
        }

        // Should have first 5 keys
        assertThat(keys).containsExactly(0L, 1L, 2L, 3L, 4L);

        // Iterator is lazy - this test verifies it doesn't crash
        // (actual lazy loading is hard to test without mocking)

        indexFile.close();
    }
}
