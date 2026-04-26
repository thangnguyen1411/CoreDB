package com.coredb.index;

import com.coredb.heap.RecordId;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for BTreeLeafPage in-page operations.
 *
 * <p>These tests verify that a single leaf page can:
 * <ul>
 *   <li>Perform binary search for keys</li>
 *   <li>Insert entries in sorted order</li>
 *   <li>Return correct results for search, entryCount, keyAt, ridAt</li>
 *   <li>Handle full pages and duplicate keys correctly</li>
 * </ul>
 */
class BTreeLeafPageTest {

    @Test
    void createEmpty_initializesAsLeafWithNoEntries() {
        BTreeLeafPage leaf = BTreeLeafPage.createEmpty(1);

        assertThat(leaf.pageId()).isEqualTo(1);
        assertThat(leaf.isLeaf()).isTrue();
        assertThat(leaf.entryCount()).isEqualTo(0);
        assertThat(leaf.btpoPrev()).isEqualTo(0);
        assertThat(leaf.btpoNext()).isEqualTo(0);
    }

    @Test
    void insert_singleEntry_returnsOk() {
        BTreeLeafPage leaf = BTreeLeafPage.createEmpty(1);

        InsertResult result = leaf.insert(42L, new RecordId(5, 3));

        assertThat(result).isEqualTo(InsertResult.OK);
        assertThat(leaf.entryCount()).isEqualTo(1);
    }

    @Test
    void insert_multipleEntriesInOrder_maintainsSortedOrder() {
        BTreeLeafPage leaf = BTreeLeafPage.createEmpty(1);

        leaf.insert(10L, new RecordId(1, 0));
        leaf.insert(20L, new RecordId(1, 1));
        leaf.insert(30L, new RecordId(1, 2));

        assertThat(leaf.entryCount()).isEqualTo(3);
        assertThat(leaf.keyAt(0)).isEqualTo(10L);
        assertThat(leaf.keyAt(1)).isEqualTo(20L);
        assertThat(leaf.keyAt(2)).isEqualTo(30L);
    }

    @Test
    void insert_multipleEntriesOutOfOrder_sortsCorrectly() {
        BTreeLeafPage leaf = BTreeLeafPage.createEmpty(1);

        // Insert in scrambled order
        leaf.insert(50L, new RecordId(1, 0));
        leaf.insert(10L, new RecordId(1, 1));
        leaf.insert(30L, new RecordId(1, 2));
        leaf.insert(20L, new RecordId(1, 3));
        leaf.insert(40L, new RecordId(1, 4));

        assertThat(leaf.entryCount()).isEqualTo(5);

        // Verify sorted order
        assertThat(leaf.keyAt(0)).isEqualTo(10L);
        assertThat(leaf.keyAt(1)).isEqualTo(20L);
        assertThat(leaf.keyAt(2)).isEqualTo(30L);
        assertThat(leaf.keyAt(3)).isEqualTo(40L);
        assertThat(leaf.keyAt(4)).isEqualTo(50L);
    }

    @Test
    void insert_duplicateKey_returnsDuplicateKey() {
        BTreeLeafPage leaf = BTreeLeafPage.createEmpty(1);

        leaf.insert(42L, new RecordId(5, 3));
        InsertResult result = leaf.insert(42L, new RecordId(6, 4));

        assertThat(result).isEqualTo(InsertResult.DUPLICATE_KEY);
        assertThat(leaf.entryCount()).isEqualTo(1); // No change
    }

    @Test
    void insert_untilFull_returnsFull() {
        BTreeLeafPage leaf = BTreeLeafPage.createEmpty(1);

        int count = 0;
        InsertResult result = InsertResult.OK;

        while (result == InsertResult.OK) {
            result = leaf.insert(count, new RecordId(count, 0));
            if (result == InsertResult.OK) {
                count++;
            }
        }

        assertThat(result).isEqualTo(InsertResult.FULL);
        assertThat(count).isGreaterThan(100); // Should fit many entries
    }

    @Test
    void search_existingKey_returnsRecordId() {
        BTreeLeafPage leaf = BTreeLeafPage.createEmpty(1);

        RecordId rid1 = new RecordId(1, 0);
        RecordId rid2 = new RecordId(1, 1);
        RecordId rid3 = new RecordId(2, 0);

        leaf.insert(10L, rid1);
        leaf.insert(20L, rid2);
        leaf.insert(30L, rid3);

        assertThat(leaf.search(10L)).hasValue(rid1);
        assertThat(leaf.search(20L)).hasValue(rid2);
        assertThat(leaf.search(30L)).hasValue(rid3);
    }

    @Test
    void search_missingKey_returnsEmpty() {
        BTreeLeafPage leaf = BTreeLeafPage.createEmpty(1);

        leaf.insert(10L, new RecordId(1, 0));
        leaf.insert(30L, new RecordId(1, 1));

        assertThat(leaf.search(5L)).isEmpty();
        assertThat(leaf.search(20L)).isEmpty();
        assertThat(leaf.search(40L)).isEmpty();
    }

    @Test
    void search_emptyPage_returnsEmpty() {
        BTreeLeafPage leaf = BTreeLeafPage.createEmpty(1);

        assertThat(leaf.search(42L)).isEmpty();
    }

    @Test
    void ridAt_returnsCorrectRecordId() {
        BTreeLeafPage leaf = BTreeLeafPage.createEmpty(1);

        RecordId rid1 = new RecordId(5, 3);
        RecordId rid2 = new RecordId(7, 2);

        leaf.insert(10L, rid1);
        leaf.insert(20L, rid2);

        assertThat(leaf.ridAt(0)).isEqualTo(rid1);
        assertThat(leaf.ridAt(1)).isEqualTo(rid2);
    }

    @Test
    void insertAfterSearch_maintainsCorrectness() {
        BTreeLeafPage leaf = BTreeLeafPage.createEmpty(1);

        // Insert some entries
        leaf.insert(10L, new RecordId(1, 0));
        leaf.insert(30L, new RecordId(1, 1));

        // Search for missing key (should insert between)
        assertThat(leaf.search(20L)).isEmpty();

        // Insert the missing key
        leaf.insert(20L, new RecordId(1, 2));

        // Verify all three are present and sorted
        assertThat(leaf.entryCount()).isEqualTo(3);
        assertThat(leaf.keyAt(0)).isEqualTo(10L);
        assertThat(leaf.keyAt(1)).isEqualTo(20L);
        assertThat(leaf.keyAt(2)).isEqualTo(30L);

        // Verify all are searchable
        assertThat(leaf.search(10L)).isPresent();
        assertThat(leaf.search(20L)).isPresent();
        assertThat(leaf.search(30L)).isPresent();
    }

    @Test
    void freeBytes_decreasesAfterInsert() {
        BTreeLeafPage leaf = BTreeLeafPage.createEmpty(1);

        int initialFree = leaf.freeBytes();

        leaf.insert(42L, new RecordId(1, 0));

        int afterInsert = leaf.freeBytes();

        // Each leaf entry: 14 bytes for tuple + 4 bytes for ItemId = 18 bytes
        assertThat(afterInsert).isEqualTo(initialFree - 18);
    }

    @Test
    void insert_manyEntries_allSearchable() {
        BTreeLeafPage leaf = BTreeLeafPage.createEmpty(1);

        // Insert many entries in random order
        int[] keys = {50, 10, 80, 20, 90, 30, 60, 40, 70, 100};
        for (int i = 0; i < keys.length; i++) {
            InsertResult result = leaf.insert(keys[i], new RecordId(1, i));
            assertThat(result).isEqualTo(InsertResult.OK);
        }

        assertThat(leaf.entryCount()).isEqualTo(10);

        // Verify sorted order
        long[] expectedKeys = {10, 20, 30, 40, 50, 60, 70, 80, 90, 100};
        for (int i = 0; i < expectedKeys.length; i++) {
            assertThat(leaf.keyAt(i)).isEqualTo(expectedKeys[i]);
        }

        // Verify all are searchable
        for (long key : expectedKeys) {
            Optional<RecordId> result = leaf.search(key);
            assertThat(result).isPresent();
        }
    }

    @Test
    void siblingPointers_canBeSetAndRetrieved() {
        BTreeLeafPage leaf = BTreeLeafPage.createEmpty(1);

        assertThat(leaf.btpoPrev()).isEqualTo(0);
        assertThat(leaf.btpoNext()).isEqualTo(0);

        leaf.setBtpoPrev(5);
        leaf.setBtpoNext(10);

        assertThat(leaf.btpoPrev()).isEqualTo(5);
        assertThat(leaf.btpoNext()).isEqualTo(10);
    }

    @Test
    void of_existingLayout_wrapsCorrectly() {
        IndexPageLayout layout = IndexPageLayout.createEmpty(5, com.coredb.page.PageType.INDEX_LEAF);
        layout.writeLeafEntry(42L, new RecordId(1, 0));

        BTreeLeafPage leaf = BTreeLeafPage.of(layout);

        assertThat(leaf.pageId()).isEqualTo(5);
        assertThat(leaf.entryCount()).isEqualTo(1);
        assertThat(leaf.keyAt(0)).isEqualTo(42L);
    }
}
