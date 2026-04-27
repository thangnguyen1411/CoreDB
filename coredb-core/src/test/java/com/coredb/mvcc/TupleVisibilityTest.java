package com.coredb.mvcc;

import com.coredb.heap.HeapTupleHeader;
import com.coredb.heap.RecordId;
import com.coredb.txn.ClogManager;
import com.coredb.util.Constants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for TupleVisibility predicate against synthetic snapshots.
 *
 * <p>These tests verify the MVCC visibility rules using handcrafted snapshots
 * and clog states without involving HeapPage or storage layers.</p>
 */
class TupleVisibilityTest {

    @TempDir
    Path tempDir;

    private ClogManager clog;

    @BeforeEach
    void setUp() throws IOException {
        clog = ClogManager.create(tempDir);
    }

    /**
     * Helper to create a tuple header with specified xmin/xmax.
     */
    private HeapTupleHeader createHeader(int xmin, int xmax) {
        RecordId self = new RecordId(1, 1);
        HeapTupleHeader header = new HeapTupleHeader(self, (short) 1);
        
        // Use reflection to set private fields via the write/read cycle
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(64);
        header.writeTo(buf, 0);
        
        // Modify xmin and xmax in buffer
        buf.putInt(0, xmin);
        buf.putInt(4, xmax);
        
        return HeapTupleHeader.readFrom(buf, 0);
    }

    @Test
    void bootstrapXidIsVisibleToEverySnapshot() {
        HeapTupleHeader header = createHeader(Constants.BOOTSTRAP_XID, Constants.INVALID_XID);

        Snapshot anySnapshot = new Snapshot(100, 200, Set.of(150));

        assertThat(TupleVisibility.isVisible(header, Snapshot.BOOTSTRAP, clog)).isTrue();
        assertThat(TupleVisibility.isVisible(header, anySnapshot, clog)).isTrue();
    }

    @Test
    void xminLessThanXminAndCommittedIsVisible() {
        // xmin=5, snapshot sees [xmin=10, xmax=20), so 5 < 10 and "decided"
        HeapTupleHeader header = createHeader(5, Constants.INVALID_XID);
        clog.setCommitted(5);

        Snapshot snap = new Snapshot(10, 20, Collections.emptySet());

        assertThat(TupleVisibility.isVisible(header, snap, clog)).isTrue();
    }

    @Test
    void xminLessThanXminAndAbortedIsInvisible() {
        // xmin=5 committed, but then aborted
        HeapTupleHeader header = createHeader(5, Constants.INVALID_XID);
        clog.setAborted(5);

        Snapshot snap = new Snapshot(10, 20, Collections.emptySet());

        assertThat(TupleVisibility.isVisible(header, snap, clog)).isFalse();
    }

    @Test
    void xminInActiveXidsIsInvisible() {
        // xmin=15, which is in the active set at snapshot time
        HeapTupleHeader header = createHeader(15, Constants.INVALID_XID);
        clog.setCommitted(15);

        Snapshot snap = new Snapshot(10, 20, Set.of(15));

        assertThat(TupleVisibility.isVisible(header, snap, clog)).isFalse();
    }

    @Test
    void xminGreaterThanOrEqualToXmaxIsInvisible() {
        // xmin=25, snapshot xmax=20, so xmin is "in the future"
        HeapTupleHeader header = createHeader(25, Constants.INVALID_XID);
        clog.setCommitted(25);

        Snapshot snap = new Snapshot(10, 20, Collections.emptySet());

        assertThat(TupleVisibility.isVisible(header, snap, clog)).isFalse();
    }

    @Test
    void xmaxInvalidMeansNotDeleted() {
        // Tuple with xmax=0 (not deleted)
        HeapTupleHeader header = createHeader(5, Constants.INVALID_XID);
        clog.setCommitted(5);

        Snapshot snap = new Snapshot(10, 20, Collections.emptySet());

        assertThat(TupleVisibility.isVisible(header, snap, clog)).isTrue();
    }

    @Test
    void xmaxCommittedAndLessThanXmaxMakesInvisible() {
        // xmin=5 visible, xmax=15 committed delete, snapshot [10, 20)
        HeapTupleHeader header = createHeader(5, 15);
        clog.setCommitted(5);
        clog.setCommitted(15);

        Snapshot snap = new Snapshot(10, 20, Collections.emptySet());

        assertThat(TupleVisibility.isVisible(header, snap, clog)).isFalse();
    }

    @Test
    void xmaxAbortedMeansStillVisible() {
        // xmin=5 visible, xmax=15 aborted delete, so tuple still visible
        HeapTupleHeader header = createHeader(5, 15);
        clog.setCommitted(5);
        clog.setAborted(15);

        Snapshot snap = new Snapshot(10, 20, Collections.emptySet());

        assertThat(TupleVisibility.isVisible(header, snap, clog)).isTrue();
    }

    @Test
    void xmaxInActiveXidsMeansStillVisible() {
        // xmin=5 visible, xmax=15 delete in progress, not yet committed
        HeapTupleHeader header = createHeader(5, 15);
        clog.setCommitted(5);
        // xmax=15 stays IN_PROGRESS

        Snapshot snap = new Snapshot(10, 20, Set.of(15));

        assertThat(TupleVisibility.isVisible(header, snap, clog)).isTrue();
    }

    @Test
    void xmaxGreaterThanOrEqualToXmaxMeansStillVisible() {
        // xmin=5 visible, xmax=25 "future" delete, so not visible yet
        HeapTupleHeader header = createHeader(5, 25);
        clog.setCommitted(5);
        clog.setCommitted(25);

        Snapshot snap = new Snapshot(10, 20, Collections.emptySet());

        assertThat(TupleVisibility.isVisible(header, snap, clog)).isTrue();
    }

    @Test
    void complexScenarioWithMultipleTransactions() {
        // Scenario:
        // - Tuple inserted by txn 5 (committed)
        // - Deleted by txn 15 (committed)
        // - Snapshot at [xmin=20, xmax=30), active={25}
        // Should be INVISIBLE because delete (15) < snapshot.xmax (20) and committed

        HeapTupleHeader header = createHeader(5, 15);
        clog.setCommitted(5);
        clog.setCommitted(15);

        Snapshot snap = new Snapshot(20, 30, Set.of(25));

        assertThat(TupleVisibility.isVisible(header, snap, clog)).isFalse();
    }

    @Test
    void uncommittedInsertIsInvisible() {
        // xmin=15, still IN_PROGRESS
        HeapTupleHeader header = createHeader(15, Constants.INVALID_XID);
        // Don't commit or abort 15

        Snapshot snap = new Snapshot(10, 20, Collections.emptySet());

        assertThat(TupleVisibility.isVisible(header, snap, clog)).isFalse();
    }

    @Test
    void deleteBySelfTransactionNotVisibleIfInActiveSet() {
        // Txid 15 inserts and deletes, snapshot sees 15 as active
        HeapTupleHeader header = createHeader(15, 15);
        // In reality this wouldn't happen (same xid for both), but test the logic

        Snapshot snap = new Snapshot(10, 20, Set.of(15));

        assertThat(TupleVisibility.isVisible(header, snap, clog)).isFalse();
    }

    @Test
    void bootstrapXidWithXmaxStillVisible() {
        // Even with xmax set, BOOTSTRAP_XID tuples are always visible
        // (this shouldn't happen in practice, but test the invariant)
        HeapTupleHeader header = createHeader(Constants.BOOTSTRAP_XID, 100);

        Snapshot snap = new Snapshot(10, 20, Collections.emptySet());

        // xmin is BOOTSTRAP_XID, so xminVisible returns true
        // xmax is 100, which > 20, so xmaxCommittedAndVisible returns false
        assertThat(TupleVisibility.isVisible(header, snap, clog)).isTrue();
    }
}
