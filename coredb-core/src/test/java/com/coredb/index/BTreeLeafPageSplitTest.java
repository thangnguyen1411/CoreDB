package com.coredb.index;

import com.coredb.heap.RecordId;
import com.coredb.index.IndexFile.PinnedPage;
import com.coredb.page.Page;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for BTreeLeafPage split operations.
 *
 * <p>These tests verify that leaf page splits:
 * <ul>
 *   <li>Divide entries correctly between left and right pages</li>
 *   <li>Maintain sorted order within each page</li>
 *   <li>Maintain the doubly-linked sibling chain correctly</li>
 *   <li>Return the correct separator key</li>
 *   <li>Preserve searchability of all keys after split</li>
 * </ul>
 */
class BTreeLeafPageSplitTest {

    @TempDir
    Path tempDir;

    @Test
    void split_pageWithTwoEntries_dividesCorrectly() throws IOException {
        // Create an index file and a leaf page
        Path indexPath = tempDir.resolve("test.idx");
        IndexFile indexFile = IndexFile.create(indexPath, 1000);

        // Get the root page (page 1) as a leaf
        PinnedPage pinned = indexFile.readPage(1);
        Page rootPage = pinned.page();
        BTreeLeafPage leaf = BTreeLeafPage.of(IndexPageLayout.of(rootPage));

        // Insert exactly 2 entries (minimum for split)
        leaf.insert(10L, new RecordId(1, 0));
        leaf.insert(20L, new RecordId(1, 1));
        pinned.unpin(true);

        // Split the page
        SplitResult result = leaf.split(indexFile);

        // Verify split result
        assertThat(result.separatorKey()).isEqualTo(20L);
        assertThat(result.newRightPageId()).isEqualTo(2);

        // Verify left page (page 1) has first entry
        assertThat(leaf.entryCount()).isEqualTo(1);
        assertThat(leaf.keyAt(0)).isEqualTo(10L);

        // Verify right page (page 2) has second entry
        PinnedPage rightPinned = indexFile.readPage(2);
        Page rightPageData = rightPinned.page();
        BTreeLeafPage rightPage = BTreeLeafPage.of(IndexPageLayout.of(rightPageData));
        assertThat(rightPage.entryCount()).isEqualTo(1);
        assertThat(rightPage.keyAt(0)).isEqualTo(20L);
        rightPinned.unpin(false);

        indexFile.close();
    }

    @Test
    void split_multipleEntries_maintainsSortedOrder() throws IOException {
        Path indexPath = tempDir.resolve("test.idx");
        IndexFile indexFile = IndexFile.create(indexPath, 1000);

        PinnedPage pinned = indexFile.readPage(1);
        Page rootPage = pinned.page();
        BTreeLeafPage leaf = BTreeLeafPage.of(IndexPageLayout.of(rootPage));

        // Insert 10 entries
        for (int i = 0; i < 10; i++) {
            leaf.insert((long) (i * 10), new RecordId(1, i));
        }
        pinned.unpin(true);

        // Split
        SplitResult result = leaf.split(indexFile);

        // Verify separator is the first key of right page
        assertThat(result.separatorKey()).isEqualTo(50L);

        // Left page should have entries 0-40 (5 entries)
        assertThat(leaf.entryCount()).isEqualTo(5);
        for (int i = 0; i < 5; i++) {
            assertThat(leaf.keyAt(i)).isEqualTo(i * 10L);
        }

        // Right page should have entries 50-90 (5 entries)
        PinnedPage rightPinned = indexFile.readPage(result.newRightPageId());
        Page rightPageData = rightPinned.page();
        BTreeLeafPage rightPage = BTreeLeafPage.of(IndexPageLayout.of(rightPageData));
        assertThat(rightPage.entryCount()).isEqualTo(5);
        for (int i = 0; i < 5; i++) {
            assertThat(rightPage.keyAt(i)).isEqualTo((i + 5) * 10L);
        }
        rightPinned.unpin(false);

        indexFile.close();
    }

    @Test
    void split_oddCount_leftPageGetsOneMore() throws IOException {
        Path indexPath = tempDir.resolve("test.idx");
        IndexFile indexFile = IndexFile.create(indexPath, 1000);

        PinnedPage pinned = indexFile.readPage(1);
        Page rootPage = pinned.page();
        BTreeLeafPage leaf = BTreeLeafPage.of(IndexPageLayout.of(rootPage));

        // Insert 5 entries (odd count)
        for (int i = 0; i < 5; i++) {
            leaf.insert((long) (i * 10), new RecordId(1, i));
        }
        pinned.unpin(true);

        // Split: with 5 entries, splitPoint = 3, so left gets 3, right gets 2
        SplitResult result = leaf.split(indexFile);

        assertThat(leaf.entryCount()).isEqualTo(3);  // Left gets ceiling(5/2) = 3
        assertThat(result.separatorKey()).isEqualTo(30L);

        PinnedPage rightPinned = indexFile.readPage(result.newRightPageId());
        Page rightPageData = rightPinned.page();
        BTreeLeafPage rightPage = BTreeLeafPage.of(IndexPageLayout.of(rightPageData));
        rightPinned.unpin(false);
        assertThat(rightPage.entryCount()).isEqualTo(2);  // Right gets floor(5/2) = 2

        indexFile.close();
    }

    @Test
    void split_maintainsSiblingChain_singlePage() throws IOException {
        Path indexPath = tempDir.resolve("test.idx");
        IndexFile indexFile = IndexFile.create(indexPath, 1000);

        PinnedPage pinned = indexFile.readPage(1);
        Page rootPage = pinned.page();
        BTreeLeafPage leaf = BTreeLeafPage.of(IndexPageLayout.of(rootPage));

        // Insert entries
        for (int i = 0; i < 6; i++) {
            leaf.insert((long) i, new RecordId(1, i));
        }
        pinned.unpin(true);

        // Initially no siblings
        assertThat(leaf.btpoPrev()).isEqualTo(0);
        assertThat(leaf.btpoNext()).isEqualTo(0);

        // Split
        SplitResult result = leaf.split(indexFile);

        // Left page (page 1) should point to new right page
        assertThat(leaf.btpoNext()).isEqualTo(result.newRightPageId());
        assertThat(leaf.btpoPrev()).isEqualTo(0);  // Still no left sibling

        // Right page should point back to left and have no right sibling
        PinnedPage rightPinned = indexFile.readPage(result.newRightPageId());
        Page rightPageData = rightPinned.page();
        BTreeLeafPage rightPage = BTreeLeafPage.of(IndexPageLayout.of(rightPageData));
        assertThat(rightPage.btpoPrev()).isEqualTo(1);
        assertThat(rightPage.btpoNext()).isEqualTo(0);
        rightPinned.unpin(false);

        indexFile.close();
    }

    @Test
    void split_maintainsSiblingChain_withExistingRightSibling() throws IOException {
        Path indexPath = tempDir.resolve("test.idx");
        IndexFile indexFile = IndexFile.create(indexPath, 1000);

        // Manually set up a chain: Page 1 <-> Page 2
        PinnedPage pinned1 = indexFile.readPage(1);
        Page page1 = pinned1.page();
        BTreeLeafPage leaf1 = BTreeLeafPage.of(IndexPageLayout.of(page1));

        // Insert entries into page 1
        for (int i = 0; i < 6; i++) {
            leaf1.insert((long) i, new RecordId(1, i));
        }

        // Allocate page 2 as the right sibling of page 1
        PinnedPage pinned2 = indexFile.allocateNewPage(com.coredb.page.PageType.INDEX_LEAF);
        Page page2Data = pinned2.page();
        BTreeLeafPage leaf2 = BTreeLeafPage.of(IndexPageLayout.of(page2Data));

        // Set up sibling pointers: 1 <-> 2
        leaf1.setBtpoNext(2);
        leaf2.setBtpoPrev(1);

        // Add entries to page 2
        for (int i = 10; i < 16; i++) {
            leaf2.insert((long) i, new RecordId(1, i));
        }

        // Unpin both pages (modified)
        pinned1.unpin(true);
        pinned2.unpin(true);

        // Verify initial chain: 1 <-> 2
        assertThat(leaf1.btpoNext()).isEqualTo(2);
        assertThat(leaf2.btpoPrev()).isEqualTo(1);

        // Now split page 1
        SplitResult result = leaf1.split(indexFile);

        // After split, chain should be: 1 <-> 3 (new) <-> 2
        assertThat(leaf1.btpoNext()).isEqualTo(result.newRightPageId());

        PinnedPage newRightPinned = indexFile.readPage(result.newRightPageId());
        Page newRightData = newRightPinned.page();
        BTreeLeafPage newRight = BTreeLeafPage.of(IndexPageLayout.of(newRightData));

        // New page should point to page 1 and page 2
        assertThat(newRight.btpoPrev()).isEqualTo(1);
        assertThat(newRight.btpoNext()).isEqualTo(2);
        newRightPinned.unpin(false);

        // Page 2 should now point back to the new page, not page 1
        PinnedPage page2Pinned = indexFile.readPage(2);
        page2Data = page2Pinned.page();
        leaf2 = BTreeLeafPage.of(IndexPageLayout.of(page2Data));
        assertThat(leaf2.btpoPrev()).isEqualTo(result.newRightPageId());
        page2Pinned.unpin(false);

        indexFile.close();
    }

    @Test
    void split_allKeysRemainSearchable() throws IOException {
        Path indexPath = tempDir.resolve("test.idx");
        IndexFile indexFile = IndexFile.create(indexPath, 1000);

        PinnedPage pinned = indexFile.readPage(1);
        Page rootPage = pinned.page();
        BTreeLeafPage leaf = BTreeLeafPage.of(IndexPageLayout.of(rootPage));

        // Insert entries
        for (int i = 0; i < 8; i++) {
            leaf.insert((long) i, new RecordId(1, i));
        }
        pinned.unpin(true);

        // Split
        SplitResult result = leaf.split(indexFile);

        // All keys should still be searchable
        // Left page keys 0-3
        for (int i = 0; i < 4; i++) {
            assertThat(leaf.search((long) i)).hasValue(new RecordId(1, i));
        }

        // Right page keys 4-7
        PinnedPage rightPinned = indexFile.readPage(result.newRightPageId());
        Page rightPageData = rightPinned.page();
        BTreeLeafPage rightPage = BTreeLeafPage.of(IndexPageLayout.of(rightPageData));
        for (int i = 4; i < 8; i++) {
            assertThat(rightPage.search((long) i)).hasValue(new RecordId(1, i));
        }

        // Verify separator invariant: separator equals first key of right page
        assertThat(result.separatorKey()).isEqualTo(rightPage.keyAt(0));
        rightPinned.unpin(false);

        indexFile.close();
    }

    @Test
    void split_consecutiveSplits_threePageChain() throws IOException {
        Path indexPath = tempDir.resolve("test.idx");
        IndexFile indexFile = IndexFile.create(indexPath, 1000);

        PinnedPage pinned = indexFile.readPage(1);
        Page rootPage = pinned.page();
        BTreeLeafPage leaf = BTreeLeafPage.of(IndexPageLayout.of(rootPage));

        // Insert enough entries for two splits
        for (int i = 0; i < 20; i++) {
            leaf.insert((long) i, new RecordId(1, i));
        }
        pinned.unpin(true);

        // First split
        SplitResult split1 = leaf.split(indexFile);

        // Read the new right page and fill it up
        PinnedPage page2Pinned = indexFile.readPage(split1.newRightPageId());
        Page page2Data = page2Pinned.page();
        BTreeLeafPage page2 = BTreeLeafPage.of(IndexPageLayout.of(page2Data));

        // Add more entries to force another split
        for (int i = 20; i < 30; i++) {
            if (page2.insert((long) i, new RecordId(1, i)) == InsertResult.FULL) {
                break;
            }
        }
        page2Pinned.unpin(true);

        // Second split
        SplitResult split2 = page2.split(indexFile);

        // Verify three-page chain via btpo_next
        PinnedPage page1Pinned = indexFile.readPage(1);
        Page page1 = page1Pinned.page();
        BTreeLeafPage p1 = BTreeLeafPage.of(IndexPageLayout.of(page1));
        PinnedPage page2AgainPinned = indexFile.readPage(split1.newRightPageId());
        Page page2Again = page2AgainPinned.page();
        BTreeLeafPage p2 = BTreeLeafPage.of(IndexPageLayout.of(page2Again));
        PinnedPage page3Pinned = indexFile.readPage(split2.newRightPageId());
        Page page3 = page3Pinned.page();
        BTreeLeafPage p3 = BTreeLeafPage.of(IndexPageLayout.of(page3));

        // Chain should be: 1 -> 2 -> 3
        assertThat(p1.btpoNext()).isEqualTo(split1.newRightPageId());
        assertThat(p2.btpoNext()).isEqualTo(split2.newRightPageId());
        assertThat(p3.btpoNext()).isEqualTo(0);

        // Reverse chain: 1 <- 2 <- 3
        assertThat(p1.btpoPrev()).isEqualTo(0);
        assertThat(p2.btpoPrev()).isEqualTo(1);
        assertThat(p3.btpoPrev()).isEqualTo(split1.newRightPageId());

        // Verify total entries across all pages
        int totalEntries = p1.entryCount() + p2.entryCount() + p3.entryCount();
        assertThat(totalEntries).isEqualTo(30);

        // Unpin all pages
        page1Pinned.unpin(false);
        page2AgainPinned.unpin(false);
        page3Pinned.unpin(false);

        indexFile.close();
    }

    @Test
    void split_separatorKeyInvariant() throws IOException {
        Path indexPath = tempDir.resolve("test.idx");
        IndexFile indexFile = IndexFile.create(indexPath, 1000);

        PinnedPage pinned = indexFile.readPage(1);
        Page rootPage = pinned.page();
        BTreeLeafPage leaf = BTreeLeafPage.of(IndexPageLayout.of(rootPage));

        // Insert scrambled order to verify sorting
        int[] keys = {50, 10, 80, 20, 90, 30, 60, 40};
        for (int i = 0; i < keys.length; i++) {
            leaf.insert((long) keys[i], new RecordId(1, i));
        }
        pinned.unpin(true);

        // Split
        SplitResult result = leaf.split(indexFile);

        // Separator should be the first key of the right page
        // Sorted: 10, 20, 30, 40, 50, 60, 80, 90
        // Indices:  0,  1,  2,  3,  4,  5,  6,  7
        // Split point = (8+1)/2 = 4, left gets 0-3, right gets 4-7
        // separator = entries[4].key = 50 (first key of right page)
        assertThat(result.separatorKey()).isEqualTo(50L);

        // Verify right page's first key equals separator
        PinnedPage rightPinned = indexFile.readPage(result.newRightPageId());
        Page rightPageData = rightPinned.page();
        BTreeLeafPage rightPage = BTreeLeafPage.of(IndexPageLayout.of(rightPageData));
        assertThat(rightPage.keyAt(0)).isEqualTo(result.separatorKey());

        // Verify all left keys < separator <= all right keys
        for (int i = 0; i < leaf.entryCount(); i++) {
            assertThat(leaf.keyAt(i)).isLessThan(result.separatorKey());
        }
        for (int i = 0; i < rightPage.entryCount(); i++) {
            assertThat(rightPage.keyAt(i)).isGreaterThanOrEqualTo(result.separatorKey());
        }
        rightPinned.unpin(false);

        indexFile.close();
    }

    @Test
    void split_canTraverseLeafChainEndToEnd() throws IOException {
        Path indexPath = tempDir.resolve("test.idx");
        IndexFile indexFile = IndexFile.create(indexPath, 1000);

        PinnedPage pinned = indexFile.readPage(1);
        Page rootPage = pinned.page();
        BTreeLeafPage leaf = BTreeLeafPage.of(IndexPageLayout.of(rootPage));

        // Insert entries and split until we have 3 pages
        for (int i = 0; i < 30; i++) {
            if (leaf.insert((long) i, new RecordId(1, i)) == InsertResult.FULL) {
                pinned.unpin(true);
                leaf.split(indexFile);
                // Re-read page 1 after split
                pinned = indexFile.readPage(1);
                rootPage = pinned.page();
                leaf = BTreeLeafPage.of(IndexPageLayout.of(rootPage));
                // Try insert again
                leaf.insert((long) i, new RecordId(1, i));
            }
        }
        pinned.unpin(true);

        // Traverse the leaf chain via btpo_next and collect all keys
        int currentPageId = 1;
        int keyCount = 0;
        long lastKey = Long.MIN_VALUE;

        while (currentPageId != 0) {
            PinnedPage pagePinned = indexFile.readPage(currentPageId);
            Page pageData = pagePinned.page();
            BTreeLeafPage page = BTreeLeafPage.of(IndexPageLayout.of(pageData));

            for (int i = 0; i < page.entryCount(); i++) {
                long key = page.keyAt(i);
                // Verify keys are in ascending order across the chain
                assertThat(key).isGreaterThan(lastKey);
                lastKey = key;
                keyCount++;
            }

            currentPageId = page.btpoNext();
            pagePinned.unpin(false);
        }

        // Should have collected all 30 keys
        assertThat(keyCount).isEqualTo(30);

        indexFile.close();
    }

    @Test
    void split_pageWithFewerThanTwoEntries_throws() throws IOException {
        Path indexPath = tempDir.resolve("test.idx");
        IndexFile indexFile = IndexFile.create(indexPath, 1000);

        PinnedPage pinned = indexFile.readPage(1);
        Page rootPage = pinned.page();
        BTreeLeafPage leaf = BTreeLeafPage.of(IndexPageLayout.of(rootPage));

        // Insert only 1 entry
        leaf.insert(42L, new RecordId(1, 0));
        pinned.unpin(true);

        // Splitting a page with fewer than 2 entries should throw
        assertThatThrownBy(() -> leaf.split(indexFile))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Cannot split page with fewer than 2 entries");

        indexFile.close();
    }
}
