package com.coredb.txn;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
        graph.addEdge(10, 20);
        assertThat(graph.isDangerousPivot(10)).isFalse();
    }

    @Test
    void onlyInEdge_notDangerousPivot() {
        graph.addEdge(10, 20);
        assertThat(graph.isDangerousPivot(20)).isFalse();
    }

    @Test
    void bothEdges_noCommittedNeighbour_notDangerousPivot() {
        // T0=3 → pivot=5 → T2=7, but neither has committed yet
        graph.addEdge(3, 5);
        graph.addEdge(5, 7);
        assertThat(graph.isDangerousPivot(5)).isFalse();
    }

    @Test
    void bothEdges_t0Committed_isDangerous() {
        graph.addEdge(3, 5);
        graph.addEdge(5, 7);
        graph.markCommitted(3);

        assertThat(graph.isDangerousPivot(5)).isTrue();
    }

    @Test
    void bothEdges_t2Committed_isDangerous() {
        graph.addEdge(3, 5);
        graph.addEdge(5, 7);
        graph.markCommitted(7);

        assertThat(graph.isDangerousPivot(5)).isTrue();
    }

    @Test
    void twoTransactionCycle_t0EqualT2_committedNeighbour_isDangerous() {
        // Write-skew with two transactions produces a 2-cycle: T3 →rw T5 →rw T3.
        // The pivot T5 has in={T3} and out={T3}. When T3 commits, T5 is dangerous.
        // This is the canonical write-skew dangerous structure.
        graph.addEdge(3, 5);   // T3 read page P that T5 wrote  → in-edge on T5
        graph.addEdge(5, 3);   // T5 read page Q that T3 wrote  → out-edge on T5
        graph.markCommitted(3);

        assertThat(graph.isDangerousPivot(5)).isTrue();
    }

    @Test
    void twoTransactionCycle_neitherCommitted_notDangerous() {
        // Same 2-cycle but neither participant has committed yet — not yet dangerous.
        graph.addEdge(3, 5);
        graph.addEdge(5, 3);

        assertThat(graph.isDangerousPivot(5)).isFalse();
        assertThat(graph.isDangerousPivot(3)).isFalse();
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
        Set<Integer> xid3Out = graph.outEdges().getOrDefault(3, Set.of());
        assertThat(xid3Out).doesNotContain(5);
        Set<Integer> xid7In = graph.inEdges().getOrDefault(7, Set.of());
        assertThat(xid7In).doesNotContain(5);
        assertThat(graph.isDangerousPivot(5)).isFalse();
    }

    @Test
    void releaseAll_nonExistentXid_noop() {
        graph.releaseAll(999);
    }

    @Test
    void markCommitted_thenRelease_cleanedUp() {
        graph.markCommitted(10);
        graph.releaseAll(10);
        // Commit-order entry removed — pivot on a neighbour should not fire.
        graph.addEdge(10, 20);
        graph.addEdge(20, 30);
        assertThat(graph.isDangerousPivot(20)).isFalse();
    }

    @Test
    void multipleEdges_onlyOneDangerousPath_isDangerous() {
        // Path T0=1 → pivot=5 → T2=9 is dangerous (T0=1 committed).
        // Path T0=2 → pivot=5 → T2=8 is not yet dangerous on its own.
        // The pivot fires because at least one path qualifies.
        graph.addEdge(1, 5);
        graph.addEdge(2, 5);
        graph.addEdge(5, 8);
        graph.addEdge(5, 9);
        graph.markCommitted(1);

        assertThat(graph.isDangerousPivot(5)).isTrue();
    }
}
