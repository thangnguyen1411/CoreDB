package com.coredb.index;

import com.coredb.page.Page;
import com.coredb.page.PageType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for BTreeInternalPage operations.
 *
 * <p>These tests verify that internal pages properly implement N keys with N+1 children:
 * <ul>
 *   <li>Leftmost child handles keys < first separator</li>
 *   <li>Entry[i] handles keys >= key[i] and < key[i+1] (or >= key[N] for last)</li>
 *   <li>Split promotes middle key and properly distributes children</li>
 * </ul>
 */
class BTreeInternalPageTest {

    @TempDir
    Path tempDir;

    @Test
    void createEmpty_initializesAsInternalWithNoEntries() {
        BTreeInternalPage page = BTreeInternalPage.createEmpty(1, 1);

        assertThat(page.pageId()).isEqualTo(1);
        assertThat(page.btpoLevel()).isEqualTo(1);
        assertThat(page.isInternal()).isTrue();
        assertThat(page.entryCount()).isEqualTo(0);
        assertThat(page.leftmostChild()).isEqualTo(0);
        assertThat(page.childCount()).isEqualTo(1); // Just leftmost (which is 0/invalid)
    }

    @Test
    void leftmostChild_setAndGet() {
        BTreeInternalPage page = BTreeInternalPage.createEmpty(1, 1);

        page.setLeftmostChild(999);

        assertThat(page.leftmostChild()).isEqualTo(999);
    }

    @Test
    void childCount_withEntries_isEntryCountPlusOne() {
        BTreeInternalPage page = BTreeInternalPage.createEmpty(1, 1);
        page.setLeftmostChild(100);

        assertThat(page.childCount()).isEqualTo(1);

        page.insertSeparator(50L, 200);
        assertThat(page.childCount()).isEqualTo(2);

        page.insertSeparator(100L, 300);
        assertThat(page.childCount()).isEqualTo(3);
    }

    @Test
    void initializeWithChildren_setsUpProperStructure() {
        BTreeInternalPage page = BTreeInternalPage.createEmpty(1, 1);

        // Initialize with left=100, separator=50, right=200
        page.initializeWithChildren(100, 50L, 200);

        assertThat(page.leftmostChild()).isEqualTo(100);
        assertThat(page.entryCount()).isEqualTo(1);
        assertThat(page.keyAt(0)).isEqualTo(50L);
        assertThat(page.childPageIdAt(0)).isEqualTo(200);
        assertThat(page.childCount()).isEqualTo(2);
    }

    @Test
    void insertSeparator_singleEntry_returnsOk() {
        BTreeInternalPage page = BTreeInternalPage.createEmpty(1, 1);

        InsertResult result = page.insertSeparator(100L, 10);

        assertThat(result).isEqualTo(InsertResult.OK);
        assertThat(page.entryCount()).isEqualTo(1);
    }

    @Test
    void insertSeparator_multipleEntriesInOrder_maintainsSortedOrder() {
        BTreeInternalPage page = BTreeInternalPage.createEmpty(1, 1);

        page.insertSeparator(10L, 100);
        page.insertSeparator(20L, 200);
        page.insertSeparator(30L, 300);

        assertThat(page.entryCount()).isEqualTo(3);
        assertThat(page.keyAt(0)).isEqualTo(10L);
        assertThat(page.keyAt(1)).isEqualTo(20L);
        assertThat(page.keyAt(2)).isEqualTo(30L);
        assertThat(page.childPageIdAt(0)).isEqualTo(100);
        assertThat(page.childPageIdAt(1)).isEqualTo(200);
        assertThat(page.childPageIdAt(2)).isEqualTo(300);
    }

    @Test
    void insertSeparator_outOfOrder_sortsCorrectly() {
        BTreeInternalPage page = BTreeInternalPage.createEmpty(1, 1);

        page.insertSeparator(50L, 500);
        page.insertSeparator(10L, 100);
        page.insertSeparator(30L, 300);
        page.insertSeparator(20L, 200);

        assertThat(page.entryCount()).isEqualTo(4);
        assertThat(page.keyAt(0)).isEqualTo(10L);
        assertThat(page.keyAt(1)).isEqualTo(20L);
        assertThat(page.keyAt(2)).isEqualTo(30L);
        assertThat(page.keyAt(3)).isEqualTo(50L);
    }

    @Test
    void insertSeparator_duplicateKey_returnsDuplicateKey() {
        BTreeInternalPage page = BTreeInternalPage.createEmpty(1, 1);

        page.insertSeparator(42L, 100);
        InsertResult result = page.insertSeparator(42L, 200);

        assertThat(result).isEqualTo(InsertResult.DUPLICATE_KEY);
        assertThat(page.entryCount()).isEqualTo(1);
    }

    @Test
    void insertSeparator_untilFull_returnsFull() {
        BTreeInternalPage page = BTreeInternalPage.createEmpty(1, 1);

        int count = 0;
        InsertResult result = InsertResult.OK;

        while (result == InsertResult.OK) {
            result = page.insertSeparator(count * 10L, count + 100);
            if (result == InsertResult.OK) {
                count++;
            }
        }

        assertThat(result).isEqualTo(InsertResult.FULL);
        assertThat(count).isGreaterThan(100);
    }

    @Test
    void routeChildFor_noChildren_throws() {
        BTreeInternalPage page = BTreeInternalPage.createEmpty(1, 1);

        assertThatThrownBy(() -> page.routeChildFor(42L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no children");
    }

    @Test
    void routeChildFor_onlyLeftmostChild_allKeysGoThere() {
        BTreeInternalPage page = BTreeInternalPage.createEmpty(1, 1);
        page.setLeftmostChild(999);

        // No entries, so all keys go to leftmost child
        assertThat(page.routeChildFor(0L)).isEqualTo(999);
        assertThat(page.routeChildFor(100L)).isEqualTo(999);
        assertThat(page.routeChildFor(1000L)).isEqualTo(999);
    }

    @Test
    void routeChildFor_withEntries_usesLeftmostForSmallKeys() {
        BTreeInternalPage page = BTreeInternalPage.createEmpty(1, 1);
        page.setLeftmostChild(100); // handles keys < 50
        page.insertSeparator(50L, 200); // handles keys >= 50

        // Keys < 50 go to leftmost child
        assertThat(page.routeChildFor(0L)).isEqualTo(100);
        assertThat(page.routeChildFor(49L)).isEqualTo(100);

        // Keys >= 50 go to first entry's child
        assertThat(page.routeChildFor(50L)).isEqualTo(200);
        assertThat(page.routeChildFor(100L)).isEqualTo(200);
    }

    @Test
    void routeChildFor_multipleEntries_routesCorrectly() {
        // Structure: leftmost=50, [(100, 200), (200, 300), (300, 400)]
        // Routing:
        //   keys < 100 → page 50 (leftmost)
        //   100 <= keys < 200 → page 200
        //   200 <= keys < 300 → page 300
        //   keys >= 300 → page 400
        BTreeInternalPage page = BTreeInternalPage.createEmpty(1, 1);
        page.setLeftmostChild(50);
        page.insertSeparator(100L, 200);
        page.insertSeparator(200L, 300);
        page.insertSeparator(300L, 400);

        // Test routing
        assertThat(page.routeChildFor(50L)).isEqualTo(50);   // < first key → leftmost
        assertThat(page.routeChildFor(99L)).isEqualTo(50);  // < first key → leftmost
        assertThat(page.routeChildFor(100L)).isEqualTo(200);  // >= 100, < 200 → child 1
        assertThat(page.routeChildFor(150L)).isEqualTo(200);  // >= 100, < 200 → child 1
        assertThat(page.routeChildFor(200L)).isEqualTo(300);  // >= 200, < 300 → child 2
        assertThat(page.routeChildFor(250L)).isEqualTo(300);  // >= 200, < 300 → child 2
        assertThat(page.routeChildFor(300L)).isEqualTo(400);  // >= 300 → child 3 (last)
        assertThat(page.routeChildFor(500L)).isEqualTo(400);  // >= 300 → child 3 (last)
    }

    @Test
    void split_evenCount_promotesMiddleKey() throws IOException {
        Path indexPath = tempDir.resolve("test.idx");
        IndexFile indexFile = IndexFile.create(indexPath, 1000);

        // Setup: leftmost=1, entries=[(10,2), (20,3), (30,4), (40,5)]
        // Split should promote key 20, child 3
        IndexFile.PinnedPage pinned = indexFile.readPage(1);
        Page rootPage = pinned.page();
        BTreeInternalPage page = BTreeInternalPage.of(IndexPageLayout.of(rootPage));
        page.setBtpoLevel(1);
        page.setLeftmostChild(1);
        page.insertSeparator(10L, 2);
        page.insertSeparator(20L, 3);
        page.insertSeparator(30L, 4);
        page.insertSeparator(40L, 5);
        pinned.unpin(true);

        // Split

        // Split
        BTreeInternalPage.InternalSplitResult result = page.split(indexFile);

        // With 4 entries, splitPoint = 2, promoted key = entries[2].key = 30
        assertThat(result.promotedKey()).isEqualTo(30L);
        assertThat(result.leftPageId()).isEqualTo(1);
        assertThat(result.rightPageId()).isEqualTo(2);

        // Left page: leftmost=1, entries=[(10,2), (20,3)]
        assertThat(page.leftmostChild()).isEqualTo(1);
        assertThat(page.entryCount()).isEqualTo(2);
        assertThat(page.keyAt(0)).isEqualTo(10L);
        assertThat(page.keyAt(1)).isEqualTo(20L);
        assertThat(page.childPageIdAt(0)).isEqualTo(2);
        assertThat(page.childPageIdAt(1)).isEqualTo(3);
        assertThat(page.btpoLevel()).isEqualTo(1);  // CRITICAL: level must survive split

        // Right page: leftmost=4 (promoted child), entry=[(40,5)]
        IndexFile.PinnedPage rightPinned = indexFile.readPage(result.rightPageId());
        Page rightPageData = rightPinned.page();
        BTreeInternalPage rightPage = BTreeInternalPage.of(IndexPageLayout.of(rightPageData));
        rightPinned.unpin(false);
        assertThat(rightPage.leftmostChild()).isEqualTo(4); // Child of promoted key 30
        assertThat(rightPage.entryCount()).isEqualTo(1);
        assertThat(rightPage.keyAt(0)).isEqualTo(40L);
        assertThat(rightPage.childPageIdAt(0)).isEqualTo(5);

        // Promoted key 30 is not on either page
        assertThat(result.promotedKey()).isEqualTo(30L);

        indexFile.close();
    }

    @Test
    void split_oddCount_promotesMiddleKey() throws IOException {
        Path indexPath = tempDir.resolve("test.idx");
        IndexFile indexFile = IndexFile.create(indexPath, 1000);

        IndexFile.PinnedPage pinned = indexFile.readPage(1);
        Page rootPage = pinned.page();
        BTreeInternalPage page = BTreeInternalPage.of(IndexPageLayout.of(rootPage));
        page.setBtpoLevel(1);
        page.setLeftmostChild(1);

        // Insert 5 entries (odd count): leftmost=1, [(10,2), (20,3), (30,4), (40,5), (50,6)]
        page.insertSeparator(10L, 2);
        page.insertSeparator(20L, 3);
        page.insertSeparator(30L, 4);
        page.insertSeparator(40L, 5);
        page.insertSeparator(50L, 6);
        pinned.unpin(true);

        // Split: splitPoint = 5/2 = 2, promoted key = entries[2].key = 30, child = 4
        BTreeInternalPage.InternalSplitResult result = page.split(indexFile);

        assertThat(result.promotedKey()).isEqualTo(30L);

        // Left page: leftmost=1, entries [(10,2), (20,3)]
        assertThat(page.btpoLevel()).isEqualTo(1);  // CRITICAL: level must survive split
        assertThat(page.leftmostChild()).isEqualTo(1);
        assertThat(page.entryCount()).isEqualTo(2);

        // Right page: leftmost=4 (promoted child), entries [(40,5), (50,6)]
        IndexFile.PinnedPage rightPinned = indexFile.readPage(result.rightPageId());
        Page rightPageData = rightPinned.page();
        BTreeInternalPage rightPage = BTreeInternalPage.of(IndexPageLayout.of(rightPageData));
        rightPinned.unpin(false);
        assertThat(rightPage.leftmostChild()).isEqualTo(4);
        assertThat(rightPage.entryCount()).isEqualTo(2);
        assertThat(rightPage.keyAt(0)).isEqualTo(40L);
        assertThat(rightPage.keyAt(1)).isEqualTo(50L);

        indexFile.close();
    }

    @Test
    void split_keyInvariant_leftKeysLessThanPromoted_rightKeysGreater() throws IOException {
        Path indexPath = tempDir.resolve("test.idx");
        IndexFile indexFile = IndexFile.create(indexPath, 1000);

        IndexFile.PinnedPage pinned = indexFile.readPage(1);
        Page rootPage = pinned.page();
        BTreeInternalPage page = BTreeInternalPage.of(IndexPageLayout.of(rootPage));
        page.setBtpoLevel(1);
        page.setLeftmostChild(1);

        // Insert entries: 10, 20, 30, 40, 50, 60
        for (int i = 1; i <= 6; i++) {
            page.insertSeparator((long) (i * 10), i + 1);
        }
        pinned.unpin(true);

        // Split
        BTreeInternalPage.InternalSplitResult result = page.split(indexFile);

        // All left keys must be < promoted key
        for (int i = 0; i < page.entryCount(); i++) {
            assertThat(page.keyAt(i)).isLessThan(result.promotedKey());
        }

        // All right keys must be > promoted key
        IndexFile.PinnedPage rightPinned = indexFile.readPage(result.rightPageId());
        Page rightPageData = rightPinned.page();
        BTreeInternalPage rightPage = BTreeInternalPage.of(IndexPageLayout.of(rightPageData));
        rightPinned.unpin(false);
        for (int i = 0; i < rightPage.entryCount(); i++) {
            assertThat(rightPage.keyAt(i)).isGreaterThan(result.promotedKey());
        }

        indexFile.close();
    }

    @Test
    void split_pageWithFewerThanTwoEntries_throws() throws IOException {
        Path indexPath = tempDir.resolve("test.idx");
        IndexFile indexFile = IndexFile.create(indexPath, 1000);

        IndexFile.PinnedPage pinned = indexFile.readPage(1);
        Page rootPage = pinned.page();
        BTreeInternalPage page = BTreeInternalPage.of(IndexPageLayout.of(rootPage));
        page.setBtpoLevel(1);

        page.insertSeparator(42L, 100);
        // leftmostChild is 1, only one entry
        pinned.unpin(true);

        assertThatThrownBy(() -> page.split(indexFile))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fewer than 2 entries");

        indexFile.close();
    }

    @Test
    void of_existingLayout_wrapsCorrectly() {
        IndexPageLayout layout = IndexPageLayout.createEmpty(5, PageType.INDEX_INTERNAL);
        layout.setBtpoLevel(2);
        layout.setBtpoPrev(999); // leftmost child
        layout.writeInternalEntry(42L, 100);

        BTreeInternalPage page = BTreeInternalPage.of(layout);

        assertThat(page.pageId()).isEqualTo(5);
        assertThat(page.btpoLevel()).isEqualTo(2);
        assertThat(page.leftmostChild()).isEqualTo(999);
        assertThat(page.entryCount()).isEqualTo(1);
        assertThat(page.keyAt(0)).isEqualTo(42L);
        assertThat(page.childPageIdAt(0)).isEqualTo(100);
    }

    @Test
    void split_childrenProperlyDistributed() throws IOException {
        Path indexPath = tempDir.resolve("test.idx");
        IndexFile indexFile = IndexFile.create(indexPath, 1000);

        // Setup: leftmost=10, entries=[(50,20), (100,30), (150,40), (200,50)]
        // 5 children total: 10, 20, 30, 40, 50
        IndexFile.PinnedPage pinned = indexFile.readPage(1);
        Page rootPage = pinned.page();
        BTreeInternalPage page = BTreeInternalPage.of(IndexPageLayout.of(rootPage));
        page.setBtpoLevel(1);
        page.setLeftmostChild(10);
        page.insertSeparator(50L, 20);
        page.insertSeparator(100L, 30);
        page.insertSeparator(150L, 40);
        page.insertSeparator(200L, 50);
        pinned.unpin(true);

        assertThat(page.childCount()).isEqualTo(5);

        // Split at position 2: with 4 entries, splitPoint = 4/2 = 2
        // promoted key = entries[2].key = 150, child = entries[2].childPageId = 40
        BTreeInternalPage.InternalSplitResult result = page.split(indexFile);

        assertThat(result.promotedKey()).isEqualTo(150L);

        // Left page: leftmost=10, entries [(50,20), (100,30)]
        // Children: 10 (keys < 50), 20 (keys >= 50 and < 100), 30 (keys >= 100 and < 150)
        assertThat(page.leftmostChild()).isEqualTo(10);
        assertThat(page.entryCount()).isEqualTo(2);
        assertThat(page.keyAt(0)).isEqualTo(50L);
        assertThat(page.keyAt(1)).isEqualTo(100L);
        assertThat(page.childPageIdAt(0)).isEqualTo(20);
        assertThat(page.childPageIdAt(1)).isEqualTo(30);
        assertThat(page.childCount()).isEqualTo(3);

        // Verify left page routing
        assertThat(page.routeChildFor(25L)).isEqualTo(10);   // < 50
        assertThat(page.routeChildFor(75L)).isEqualTo(20);  // >= 50, < 100
        assertThat(page.routeChildFor(125L)).isEqualTo(30);  // >= 100, < 150

        // Right page: leftmost=40 (promoted child), entry [(200,50)]
        // Children: 40 (keys >= 150 and < 200), 50 (keys >= 200)
        IndexFile.PinnedPage rightPinned = indexFile.readPage(result.rightPageId());
        Page rightPageData = rightPinned.page();
        BTreeInternalPage rightPage = BTreeInternalPage.of(IndexPageLayout.of(rightPageData));
        rightPinned.unpin(false);
        assertThat(rightPage.leftmostChild()).isEqualTo(40);  // child of promoted key 150
        assertThat(rightPage.entryCount()).isEqualTo(1);
        assertThat(rightPage.keyAt(0)).isEqualTo(200L);
        assertThat(rightPage.childPageIdAt(0)).isEqualTo(50);
        assertThat(rightPage.childCount()).isEqualTo(2);

        // Verify right page routing
        assertThat(rightPage.routeChildFor(175L)).isEqualTo(40); // >= 150, < 200
        assertThat(rightPage.routeChildFor(250L)).isEqualTo(50); // >= 200

        indexFile.close();
    }
}
