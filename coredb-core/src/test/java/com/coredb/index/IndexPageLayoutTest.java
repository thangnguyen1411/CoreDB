package com.coredb.index;

import com.coredb.heap.RecordId;
import com.coredb.page.Page;
import com.coredb.page.PageType;
import com.coredb.util.Constants;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IndexPageLayoutTest {

    @Test
    void createEmpty_initializesAsLeafWithCorrectHeaderValues() {
        IndexPageLayout layout = IndexPageLayout.createEmpty(1, PageType.INDEX_LEAF);

        assertThat(layout.pageId()).isEqualTo(1);
        assertThat(layout.pageType()).isEqualTo(PageType.INDEX_LEAF);
        assertThat(layout.pdSpecial()).isEqualTo((short) (Constants.PAGE_SIZE - BTPageOpaque.SIZE));
        assertThat(layout.pdLower()).isEqualTo((short) 16); // PageHeader.SIZE
        assertThat(layout.pdUpper()).isEqualTo((short) (Constants.PAGE_SIZE - BTPageOpaque.SIZE));
        assertThat(layout.isLeaf()).isTrue();
        assertThat(layout.btpoPrev()).isEqualTo(0);
        assertThat(layout.btpoNext()).isEqualTo(0);
        assertThat(layout.btpoLevel()).isEqualTo(0);
    }

    @Test
    void freeBytes_onEmptyPage() {
        IndexPageLayout layout = IndexPageLayout.createEmpty(1, PageType.INDEX_LEAF);

        // Free = pd_upper - pd_lower = (PAGE_SIZE - opaqueSize) - PageHeader.SIZE
        int expectedFree = (Constants.PAGE_SIZE - BTPageOpaque.SIZE) - 16;
        assertThat(layout.freeBytes()).isEqualTo(expectedFree);
    }

    @Test
    void entryCount_onEmptyPage() {
        IndexPageLayout layout = IndexPageLayout.createEmpty(1, PageType.INDEX_LEAF);
        assertThat(layout.entryCount()).isEqualTo(0);
    }

    @Test
    void writeAndReadLeafEntry() {
        IndexPageLayout layout = IndexPageLayout.createEmpty(1, PageType.INDEX_LEAF);

        RecordId rid = new RecordId(5, 3);
        int slot = layout.writeLeafEntry(42L, rid);

        assertThat(slot).isEqualTo(0);
        assertThat(layout.entryCount()).isEqualTo(1);

        // Read it back
        IndexPageLayout.LeafEntry entry = layout.readLeafEntry(0);
        assertThat(entry.key()).isEqualTo(42L);
        assertThat(entry.rid()).isEqualTo(rid);
    }

    @Test
    void writeAndReadInternalEntry() {
        IndexPageLayout layout = IndexPageLayout.createEmpty(1, PageType.INDEX_INTERNAL);

        int slot = layout.writeInternalEntry(100L, 7);

        assertThat(slot).isEqualTo(0);

        // Read it back
        IndexPageLayout.InternalEntry entry = layout.readInternalEntry(0);
        assertThat(entry.key()).isEqualTo(100L);
        assertThat(entry.childPageId()).isEqualTo(7);
    }

    @Test
    void multipleLeafEntries_maintainOrder() {
        IndexPageLayout layout = IndexPageLayout.createEmpty(1, PageType.INDEX_LEAF);

        layout.writeLeafEntry(10L, new RecordId(1, 0));
        layout.writeLeafEntry(20L, new RecordId(1, 1));
        layout.writeLeafEntry(30L, new RecordId(1, 2));

        assertThat(layout.entryCount()).isEqualTo(3);

        assertThat(layout.readLeafEntry(0).key()).isEqualTo(10L);
        assertThat(layout.readLeafEntry(1).key()).isEqualTo(20L);
        assertThat(layout.readLeafEntry(2).key()).isEqualTo(30L);
    }

    @Test
    void writeTuple_failsWhenNoSpace() {
        IndexPageLayout layout = IndexPageLayout.createEmpty(1, PageType.INDEX_LEAF);

        // Fill the page with many entries
        int count = 0;
        try {
            while (true) {
                layout.writeLeafEntry(count, new RecordId(count, 0));
                count++;
            }
        } catch (IllegalStateException e) {
            // Expected - page is full
        }

        // Should have written many entries before failing
        assertThat(count).isGreaterThan(100);
    }

    @Test
    void siblingPointers_roundTrip() {
        IndexPageLayout layout = IndexPageLayout.createEmpty(1, PageType.INDEX_LEAF);

        layout.setBtpoPrev(5);
        layout.setBtpoNext(10);
        layout.setBtpoLevel(0);

        assertThat(layout.btpoPrev()).isEqualTo(5);
        assertThat(layout.btpoNext()).isEqualTo(10);
        assertThat(layout.btpoLevel()).isEqualTo(0);
    }

    @Test
    void setLevel_makesNonLeaf() {
        IndexPageLayout layout = IndexPageLayout.createEmpty(1, PageType.INDEX_INTERNAL);

        assertThat(layout.isLeaf()).isTrue(); // Initially level 0

        layout.setBtpoLevel(1);
        assertThat(layout.isLeaf()).isFalse();
        assertThat(layout.btpoLevel()).isEqualTo(1);
    }

    @Test
    void findInsertionPoint_emptyPage_returnsZero() {
        IndexPageLayout layout = IndexPageLayout.createEmpty(1, PageType.INDEX_LEAF);

        assertThat(layout.findInsertionPoint(50L)).isEqualTo(0);
    }

    @Test
    void findInsertionPoint_singleEntry() {
        IndexPageLayout layout = IndexPageLayout.createEmpty(1, PageType.INDEX_LEAF);
        layout.writeLeafEntry(100L, new RecordId(1, 0));

        // Insert before
        assertThat(layout.findInsertionPoint(50L)).isEqualTo(0);

        // Insert after
        assertThat(layout.findInsertionPoint(150L)).isEqualTo(1);

        // Equal key - returns that position
        assertThat(layout.findInsertionPoint(100L)).isEqualTo(0);
    }

    @Test
    void findInsertionPoint_multipleEntries() {
        IndexPageLayout layout = IndexPageLayout.createEmpty(1, PageType.INDEX_LEAF);
        layout.writeLeafEntry(10L, new RecordId(1, 0));
        layout.writeLeafEntry(30L, new RecordId(1, 1));
        layout.writeLeafEntry(50L, new RecordId(1, 2));

        assertThat(layout.findInsertionPoint(5L)).isEqualTo(0);   // Before all
        assertThat(layout.findInsertionPoint(20L)).isEqualTo(1);  // Between 10 and 30
        assertThat(layout.findInsertionPoint(40L)).isEqualTo(2);  // Between 30 and 50
        assertThat(layout.findInsertionPoint(60L)).isEqualTo(3);    // After all
        assertThat(layout.findInsertionPoint(30L)).isEqualTo(1);    // Equal to existing
    }

    @Test
    void searchKey_existingKey() {
        IndexPageLayout layout = IndexPageLayout.createEmpty(1, PageType.INDEX_LEAF);
        layout.writeLeafEntry(10L, new RecordId(1, 0));
        layout.writeLeafEntry(30L, new RecordId(1, 1));
        layout.writeLeafEntry(50L, new RecordId(1, 2));

        assertThat(layout.searchKey(10L)).isEqualTo(0);
        assertThat(layout.searchKey(30L)).isEqualTo(1);
        assertThat(layout.searchKey(50L)).isEqualTo(2);
    }

    @Test
    void searchKey_missingKey_returnsMinusOne() {
        IndexPageLayout layout = IndexPageLayout.createEmpty(1, PageType.INDEX_LEAF);
        layout.writeLeafEntry(10L, new RecordId(1, 0));
        layout.writeLeafEntry(30L, new RecordId(1, 1));

        assertThat(layout.searchKey(5L)).isEqualTo(-1);
        assertThat(layout.searchKey(20L)).isEqualTo(-1);
        assertThat(layout.searchKey(40L)).isEqualTo(-1);
    }

    @Test
    void freeBytes_decreasesAfterInsert() {
        IndexPageLayout layout = IndexPageLayout.createEmpty(1, PageType.INDEX_LEAF);
        int initialFree = layout.freeBytes();

        layout.writeLeafEntry(42L, new RecordId(1, 0));

        int afterInsert = layout.freeBytes();
        assertThat(afterInsert).isLessThan(initialFree);

        // Each leaf entry is 14 bytes + 4 bytes ItemId = 18 bytes
        assertThat(initialFree - afterInsert).isEqualTo(18);
    }

    @Test
    void of_existingPage_wrapsCorrectly() {
        Page page = IndexPageLayout.createEmpty(5, PageType.INDEX_LEAF).page();

        IndexPageLayout layout = IndexPageLayout.of(page);

        assertThat(layout.pageId()).isEqualTo(5);
        assertThat(layout.pageType()).isEqualTo(PageType.INDEX_LEAF);
    }
}
