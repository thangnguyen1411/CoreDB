package com.coredb.txn;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PredicateLockManagerTest {

    private PredicateLockManager mgr;

    @BeforeEach
    void setUp() {
        mgr = new PredicateLockManager();
    }

    @Test
    void acquireSiread_recordsXidForPage() {
        mgr.acquireSiread(10, 1002, 3);

        assertThat(mgr.readersOf(1002, 3)).containsExactly(10);
    }

    @Test
    void acquireSiread_twoXidsOnSamePage_bothRecorded() {
        mgr.acquireSiread(10, 1002, 3);
        mgr.acquireSiread(11, 1002, 3);

        assertThat(mgr.readersOf(1002, 3)).containsExactlyInAnyOrder(10, 11);
    }

    @Test
    void acquireSiread_idempotent_sameXidTwice() {
        mgr.acquireSiread(10, 1002, 3);
        mgr.acquireSiread(10, 1002, 3);

        assertThat(mgr.readersOf(1002, 3)).containsExactly(10);
    }

    @Test
    void readersOf_unknownPage_returnsEmpty() {
        assertThat(mgr.readersOf(1002, 99)).isEmpty();
    }

    @Test
    void locksHeldBy_returnsTagsForXid() {
        mgr.acquireSiread(10, 1002, 3);
        mgr.acquireSiread(10, 1002, 5);

        Set<PredicateLockTag> tags = mgr.locksHeldBy(10);
        assertThat(tags).containsExactlyInAnyOrder(
            new PredicateLockTag(1002, 3),
            new PredicateLockTag(1002, 5)
        );
    }

    @Test
    void locksHeldBy_unknownXid_returnsEmpty() {
        assertThat(mgr.locksHeldBy(999)).isEmpty();
    }

    @Test
    void releaseAll_removesXidFromAllPages() {
        mgr.acquireSiread(10, 1002, 3);
        mgr.acquireSiread(10, 1002, 5);
        mgr.acquireSiread(11, 1002, 3);

        mgr.releaseAll(10);

        assertThat(mgr.readersOf(1002, 3)).containsExactly(11);
        assertThat(mgr.readersOf(1002, 5)).isEmpty();
        assertThat(mgr.locksHeldBy(10)).isEmpty();
    }

    @Test
    void releaseAll_otherXidUnaffected() {
        mgr.acquireSiread(10, 1002, 3);
        mgr.acquireSiread(11, 1002, 3);

        mgr.releaseAll(10);

        assertThat(mgr.readersOf(1002, 3)).containsExactly(11);
        assertThat(mgr.locksHeldBy(11)).containsExactly(new PredicateLockTag(1002, 3));
    }

    @Test
    void releaseAll_unknownXid_noError() {
        mgr.releaseAll(999);
    }

    @Test
    void releaseAll_emptyTagSetCleanedUp() {
        mgr.acquireSiread(10, 1002, 3);
        mgr.releaseAll(10);

        assertThat(mgr.allLocks()).isEmpty();
    }

    @Test
    void allLocks_returnsSnapshot() {
        mgr.acquireSiread(10, 1002, 3);
        mgr.acquireSiread(11, 1002, 4);

        assertThat(mgr.allLocks()).hasSize(2);
        assertThat(mgr.allLocks()).containsKey(new PredicateLockTag(1002, 3));
        assertThat(mgr.allLocks()).containsKey(new PredicateLockTag(1002, 4));
    }
}
