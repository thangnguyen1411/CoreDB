package com.coredb.txn;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RWConflictGraphTest {

    private RWConflictGraph graph;

    @BeforeEach
    void setUp() {
        graph = new RWConflictGraph();
    }

    @Test
    void noEdges_notDangerousPivot() {
        assertThat(graph.isDangerousPivot(1)).isFalse();
    }

    @Test
    void onlyOutEdge_notDangerousPivot() {
        graph.addEdge(10, 20);  // 10 reads page written by 20 — out-edge on 10
        // xid=10 has out-edge but no in-edge
        assertThat(graph.isDangerousPivot(10)).isFalse();
    }

    @Test
    void onlyInEdge_notDangerousPivot() {
        graph.addEdge(10, 20);  // in-edge on 20
        assertThat(graph.isDangerousPivot(20)).isFalse();
    }

    @Test
    void bothEdges_noCommittedNeighbour_notDangerousPivot() {
        // T0 → pivot(5) → T2, but neither T0 nor T2 has committed
        graph.addEdge(3, 5);   // in-edge on 5 from T0=3
        graph.addEdge(5, 7);   // out-edge on 5 to T2=7
        assertThat(graph.isDangerousPivot(5)).isFalse();
    }

    @Test
    void bothEdges_t0Committed_isDangerous() {
        graph.addEdge(3, 5);   // in-edge on 5 from T0=3
        graph.addEdge(5, 7);   // out-edge on 5 to T2=7
        graph.markCommitted(3);  // T0 committed

        assertThat(graph.isDangerousPivot(5)).isTrue();
    }

    @Test
    void bothEdges_t2Committed_isDangerous() {
        graph.addEdge(3, 5);
        graph.addEdge(5, 7);
        graph.markCommitted(7);  // T2 committed

        assertThat(graph.isDangerousPivot(5)).isTrue();
    }

    @Test
    void sameXidAsT0AndT2_notDangerousForThatPair() {
        // Edge where T0 == T2 is the same transaction — not a valid dangerous structure
        graph.addEdge(3, 5);   // 3 → 5
        graph.addEdge(5, 3);   // 5 → 3 (T2 == T0 == 3)
        graph.markCommitted(3);

        // The only pair is (T0=3, T2=3) which is the same xid, so not dangerous.
        assertThat(graph.isDangerousPivot(5)).isFalse();
    }

    @Test
    void addEdge_selfLoop_ignored() {
        graph.addEdge(5, 5);

        assertThat(graph.outEdges()).doesNotContainKey(5);
        assertThat(graph.inEdges()).doesNotContainKey(5);
    }

    @Test
    void releaseAll_removesEdgesAndCommitOrder() {
        graph.addEdge(3, 5);
        graph.addEdge(5, 7);
        graph.markCommitted(5);

        graph.releaseAll(5);

        assertThat(graph.outEdges()).doesNotContainKey(5);
        assertThat(graph.inEdges()).doesNotContainKey(5);
        // Neighbour edge sets no longer contain xid=5
        Set<Integer> xid3Out = graph.outEdges().getOrDefault(3, Set.of());
        assertThat(xid3Out).doesNotContain(5);
        Set<Integer> xid7In = graph.inEdges().getOrDefault(7, Set.of());
        assertThat(xid7In).doesNotContain(5);
        // After release, not a pivot (no edges)
        assertThat(graph.isDangerousPivot(5)).isFalse();
    }

    @Test
    void releaseAll_nonExistentXid_noop() {
        graph.releaseAll(999); // should not throw
    }

    @Test
    void markCommitted_thenRelease_cleanedUp() {
        graph.markCommitted(10);
        graph.releaseAll(10);
        // No commit-order entry remains — cannot observe directly, but isDangerousPivot
        // on a neighbour that had edges to 10 should not fire after release.
        graph.addEdge(10, 20);  // re-add after the release
        graph.addEdge(20, 30);
        // 10 was released, so its commit-order entry was removed; pivot check on 20 should be false.
        assertThat(graph.isDangerousPivot(20)).isFalse();
    }

    @Test
    void multipleEdges_onlyOneDangerousPath_isDangerous() {
        // T0=1 → pivot=5 → T2=9 (1 committed): dangerous
        // T0=2 → pivot=5 → T2=8 (neither committed): not dangerous on its own
        // Together the pivot still fires because at least one path has a committed neighbour.
        graph.addEdge(1, 5);
        graph.addEdge(2, 5);
        graph.addEdge(5, 8);
        graph.addEdge(5, 9);
        graph.markCommitted(1);

        assertThat(graph.isDangerousPivot(5)).isTrue();
    }
}
