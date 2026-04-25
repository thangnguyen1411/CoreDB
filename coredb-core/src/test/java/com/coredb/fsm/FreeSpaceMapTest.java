package com.coredb.fsm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for FreeSpaceMap — the in-memory free space tracking data structure.
 *
 * <p>These tests verify the core FSM semantics: category encoding,
 * requestPage linear search, and updatePage behaviour.</p>
 */
class FreeSpaceMapTest {

    // ========== Construction ==========

    @Test
    @DisplayName("Construct with 0 pages → empty FSM")
    void constructZeroPages() {
        FreeSpaceMap fsm = new FreeSpaceMap(0);

        assertThat(fsm.trackedDataPageCount()).isEqualTo(0);
        assertThat(fsm.requestPage(1)).isEqualTo(-1);
        assertThat(fsm.requestPage(0)).isEqualTo(-1);
    }

    @Test
    @DisplayName("Construct with negative page count throws")
    void constructNegativePagesThrows() {
        assertThatThrownBy(() -> new FreeSpaceMap(-1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("pageCount");
    }

    @Test
    @DisplayName("Construct with 10 pages → all categories are 0 (full)")
    void constructTenPagesAllZero() {
        FreeSpaceMap fsm = new FreeSpaceMap(10);

        assertThat(fsm.trackedDataPageCount()).isEqualTo(10);
        for (int pageId = 1; pageId <= 10; pageId++) {
            assertThat(fsm.getCategory(pageId)).isEqualTo(0);
        }
    }

    // ========== requestPage (all categories zero) ==========

    @Test
    @DisplayName("requestPage with 100 bytes needed returns -1 when all categories are 0")
    void requestPageAllZeroReturnsMinusOne() {
        FreeSpaceMap fsm = new FreeSpaceMap(10);

        assertThat(fsm.requestPage(100)).isEqualTo(-1);
    }

    @Test
    @DisplayName("requestPage with 0 bytes needed still returns -1 when all categories are 0")
    void requestPageZeroBytesAllZeroReturnsMinusOne() {
        FreeSpaceMap fsm = new FreeSpaceMap(10);

        // Category 0 means "treat as full" — even 0 bytes won't match
        assertThat(fsm.requestPage(0)).isEqualTo(-1);
    }

    // ========== updatePage and categoryFor ==========

    @Test
    @DisplayName("updatePage(3, 500) → getCategory(3) returns 15 (500/32)")
    void updatePageSetsCategory() {
        FreeSpaceMap fsm = new FreeSpaceMap(10);

        fsm.updatePage(3, 500);

        assertThat(fsm.getCategory(3)).isEqualTo(15); // 500 / 32 = 15
    }

    @Test
    @DisplayName("categoryFor computes correct values")
    void categoryForComputesCorrectly() {
        assertThat(FreeSpaceMap.categoryFor(0)).isEqualTo(0);
        assertThat(FreeSpaceMap.categoryFor(31)).isEqualTo(0);   // 31/32 = 0
        assertThat(FreeSpaceMap.categoryFor(32)).isEqualTo(1);  // 32/32 = 1
        assertThat(FreeSpaceMap.categoryFor(64)).isEqualTo(2);  // 64/32 = 2
        assertThat(FreeSpaceMap.categoryFor(500)).isEqualTo(15); // 500/32 = 15
        assertThat(FreeSpaceMap.categoryFor(8160)).isEqualTo(255); // 8160/32 = 255
    }

    @Test
    @DisplayName("categoryFor clamps at 255 for large values")
    void categoryForClampsAt255() {
        assertThat(FreeSpaceMap.categoryFor(1_000_000)).isEqualTo(255);
        assertThat(FreeSpaceMap.categoryFor(Integer.MAX_VALUE)).isEqualTo(255);
    }

    @Test
    @DisplayName("categoryFor returns 0 for negative values")
    void categoryForNegativeReturnsZero() {
        assertThat(FreeSpaceMap.categoryFor(-1)).isEqualTo(0);
        assertThat(FreeSpaceMap.categoryFor(-100)).isEqualTo(0);
    }

    // ========== requestPage (with updated categories) ==========

    @Test
    @DisplayName("After updatePage(3, 500), requestPage(100) returns 3")
    void requestPageReturnsUpdatedPage() {
        FreeSpaceMap fsm = new FreeSpaceMap(10);
        fsm.updatePage(3, 500); // category = 15

        int pageId = fsm.requestPage(100); // 15 * 32 = 480 >= 100

        assertThat(pageId).isEqualTo(3);
    }

    @Test
    @DisplayName("After updatePage(3, 500), requestPage(0) returns 3")
    void requestPageZeroBytesReturnsNonFullPage() {
        FreeSpaceMap fsm = new FreeSpaceMap(10);
        fsm.updatePage(3, 500); // category = 15

        // Any non-zero category satisfies a request for 0 bytes
        int pageId = fsm.requestPage(0);

        assertThat(pageId).isEqualTo(3);
    }

    @Test
    @DisplayName("After updatePage(3, 0), requestPage(100) returns -1 again")
    void updatePageToZeroMakesPageUnavailable() {
        FreeSpaceMap fsm = new FreeSpaceMap(10);
        fsm.updatePage(3, 500); // category = 15
        assertThat(fsm.requestPage(100)).isEqualTo(3);

        fsm.updatePage(3, 0); // category = 0

        assertThat(fsm.requestPage(100)).isEqualTo(-1);
    }

    @Test
    @DisplayName("requestPage returns first page that satisfies the request")
    void requestPageReturnsFirstFit() {
        FreeSpaceMap fsm = new FreeSpaceMap(10);
        fsm.updatePage(5, 200);  // category = 6
        fsm.updatePage(3, 500);  // category = 15
        fsm.updatePage(7, 1000); // category = 31

        // Should return page 3 (first page with category*32 >= 100)
        assertThat(fsm.requestPage(100)).isEqualTo(3);
    }

    @Test
    @DisplayName("requestPage skips pages with insufficient space")
    void requestPageSkipsInsufficientSpace() {
        FreeSpaceMap fsm = new FreeSpaceMap(10);
        fsm.updatePage(1, 60);   // category = 1 (32 bytes)
        fsm.updatePage(2, 100);  // category = 3 (96 bytes)
        fsm.updatePage(3, 30);   // category = 0 (treated as full)

        // Need 80 bytes: page 1 has 32 (no), page 2 has 96 (yes)
        assertThat(fsm.requestPage(80)).isEqualTo(2);
    }

    @Test
    @DisplayName("requestPage with 8192 bytes never succeeds (max free is ~8176)")
    void requestPageFullPageSizeNeverSucceeds() {
        FreeSpaceMap fsm = new FreeSpaceMap(5);
        // Even with max category (255), 255 * 32 = 8160 < 8192
        fsm.updatePage(1, 10000); // clamps to category 255

        assertThat(fsm.requestPage(8192)).isEqualTo(-1);
    }

    // ========== Edge Cases ==========

    @Test
    @DisplayName("updatePage with out-of-bounds pageId throws IndexOutOfBoundsException")
    void updatePageOutOfBoundsThrows() {
        FreeSpaceMap fsm = new FreeSpaceMap(5);

        assertThatThrownBy(() -> fsm.updatePage(0, 100))
            .isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> fsm.updatePage(6, 100))
            .isInstanceOf(IndexOutOfBoundsException.class);
    }

    @Test
    @DisplayName("getCategory with out-of-bounds pageId throws IndexOutOfBoundsException")
    void getCategoryOutOfBoundsThrows() {
        FreeSpaceMap fsm = new FreeSpaceMap(5);

        assertThatThrownBy(() -> fsm.getCategory(0))
            .isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> fsm.getCategory(6))
            .isInstanceOf(IndexOutOfBoundsException.class);
    }

    @Test
    @DisplayName("updatePage at exact boundary (pageCount) is valid")
    void updatePageAtBoundaryValid() {
        FreeSpaceMap fsm = new FreeSpaceMap(5);

        fsm.updatePage(5, 100); // page 5 is the last valid page

        assertThat(fsm.getCategory(5)).isEqualTo(3);
    }

    // ========== grow ==========

    @Test
    @DisplayName("grow extends the array with zero-filled entries")
    void growExtendsArray() {
        FreeSpaceMap fsm = new FreeSpaceMap(5);
        fsm.updatePage(3, 100);

        fsm.grow(10);

        assertThat(fsm.trackedDataPageCount()).isEqualTo(10);
        // Existing entries preserved
        assertThat(fsm.getCategory(3)).isEqualTo(3);
        // New entries are zero
        assertThat(fsm.getCategory(6)).isEqualTo(0);
        assertThat(fsm.getCategory(10)).isEqualTo(0);
    }

    @Test
    @DisplayName("grow with same size is a no-op")
    void growSameSizeNoOp() {
        FreeSpaceMap fsm = new FreeSpaceMap(5);
        fsm.updatePage(3, 100);

        fsm.grow(5);

        assertThat(fsm.trackedDataPageCount()).isEqualTo(5);
        assertThat(fsm.getCategory(3)).isEqualTo(3);
    }

    @Test
    @DisplayName("grow with smaller size throws")
    void growSmallerThrows() {
        FreeSpaceMap fsm = new FreeSpaceMap(10);

        assertThatThrownBy(() -> fsm.grow(5))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("newPageCount");
    }

    // ========== Realistic Scenarios ==========

    @Test
    @DisplayName("Fill page scenario: categories decrease as page fills")
    void fillPageScenario() {
        FreeSpaceMap fsm = new FreeSpaceMap(3);

        // Simulate a page gradually filling up
        fsm.updatePage(1, 8000);  // Nearly empty
        assertThat(fsm.getCategory(1)).isEqualTo(250);

        fsm.updatePage(1, 4000);  // Half full
        assertThat(fsm.getCategory(1)).isEqualTo(125);

        fsm.updatePage(1, 100);   // Nearly full
        assertThat(fsm.getCategory(1)).isEqualTo(3);

        fsm.updatePage(1, 0);     // Full
        assertThat(fsm.getCategory(1)).isEqualTo(0);
    }

    @Test
    @DisplayName("Multi-page search: finds page with enough space")
    void multiPageSearch() {
        FreeSpaceMap fsm = new FreeSpaceMap(5);
        // Page 1: nearly full (small space)
        fsm.updatePage(1, 64);   // category = 2 → 64 bytes
        // Page 2: full
        fsm.updatePage(2, 0);    // category = 0
        // Page 3: has room
        fsm.updatePage(3, 1000); // category = 31 → 992 bytes
        // Page 4: has lots of room
        fsm.updatePage(4, 5000); // category = 156 → 4992 bytes

        // Need 500 bytes: only pages 3 and 4 have enough
        // Should return page 3 (first fit)
        assertThat(fsm.requestPage(500)).isEqualTo(3);

        // Need 2000 bytes: only page 4 has enough
        assertThat(fsm.requestPage(2000)).isEqualTo(4);

        // Need 100 bytes: page 1 is first with enough (64 >= 100? No!)
        // Actually 64 < 100, so page 1 doesn't qualify
        // Page 3 is the first with 992 >= 100
        assertThat(fsm.requestPage(100)).isEqualTo(3);

        // Need 50 bytes: page 1 has 64 >= 50, so it's the first fit
        assertThat(fsm.requestPage(50)).isEqualTo(1);
    }
}
