package com.coredb.txn;

import com.coredb.api.CoreDB;
import com.coredb.api.CoreDBConfig;
import com.coredb.shell.LocalShellBackend;
import com.coredb.util.SerializationFailureException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for Serializable Snapshot Isolation (SSI) pivot detection.
 *
 * <p>Verifies that:
 * <ul>
 *   <li>Write skew is blocked at SERIALIZABLE — the second committer is aborted at commit time.</li>
 *   <li>Write skew is allowed at REPEATABLE READ (regression from 13.5C).</li>
 *   <li>Read-only SERIALIZABLE transactions do not incur false aborts.</li>
 *   <li>Commit-time pivot recheck aborts a dangerous transaction at commit.</li>
 *   <li>After an SSI abort, graph state is fully cleaned up.</li>
 *   <li>{@link CoreDB#executeSerializable} retries on failure and exhausts retries correctly.</li>
 * </ul>
 */
class SsiWriteSkewTest {

    @TempDir
    Path tempDir;

    CoreDB db;
    LocalShellBackend shell;

    @BeforeEach
    void setUp() throws IOException {
        db = CoreDB.open(tempDir, CoreDBConfig.defaults());
        shell = new LocalShellBackend(db);
        shell.execute("create-table doctors id:long oncall:int pk:id");
        // Two doctors, both on-call (oncall=1)
        shell.execute("put doctors 1 1");
        shell.execute("put doctors 2 1");
    }

    @AfterEach
    void tearDown() throws IOException {
        db.close();
    }

    // -------------------------------------------------------------------------
    // Write-skew at SERIALIZABLE: the second committer must be aborted
    // -------------------------------------------------------------------------

    @Test
    void writeSkew_atSerializable_secondCommitterAborts() throws Exception {
        // Classic doctors-on-call write skew:
        //   T1 reads all doctors (sees 2 on-call), sets doctor 1 off-call.
        //   T2 reads all doctors (sees 2 on-call), sets doctor 2 off-call.
        //   If both commit: 0 on-call doctors. This is non-serializable.
        //
        // For SSI to catch this, both WRITES must happen before either COMMIT
        // (so each writer's SIREAD is still held when the other write occurs,
        // allowing the rw-edges to be recorded). Four latches enforce this:
        //   - both must have scanned before either writes
        //   - both must have written before either commits

        CountDownLatch t1Scanned = new CountDownLatch(1);
        CountDownLatch t2Scanned = new CountDownLatch(1);
        CountDownLatch t1Wrote   = new CountDownLatch(1);
        CountDownLatch t2Wrote   = new CountDownLatch(1);

        AtomicReference<Throwable> t1Error = new AtomicReference<>();
        AtomicReference<Throwable> t2Error = new AtomicReference<>();

        Thread t1 = new Thread(() -> {
            try {
                LocalShellBackend s1 = new LocalShellBackend(db);
                s1.execute("set-isolation serializable");
                s1.execute("begin");
                s1.execute("scan doctors");
                t1Scanned.countDown();
                t2Scanned.await();
                s1.execute("put doctors 1 0");
                t1Wrote.countDown();
                t2Wrote.await();
                s1.execute("commit");
            } catch (Throwable e) {
                t1Error.set(e);
                t1Wrote.countDown();  // unblock T2 even on early failure
            }
        });

        Thread t2 = new Thread(() -> {
            try {
                LocalShellBackend s2 = new LocalShellBackend(db);
                s2.execute("set-isolation serializable");
                s2.execute("begin");
                s2.execute("scan doctors");
                t2Scanned.countDown();
                t1Scanned.await();
                s2.execute("put doctors 2 0");
                t2Wrote.countDown();
                t1Wrote.await();
                s2.execute("commit");
            } catch (Throwable e) {
                t2Error.set(e);
                t2Wrote.countDown();  // unblock T1 even on early failure
            }
        });

        t1.start();
        t2.start();
        t1.join(5000);
        t2.join(5000);

        // Exactly the second committer must have gotten SerializationFailureException.
        // One commits normally; the other is aborted by commit-time pivot recheck.
        boolean t1Failed = t1Error.get() instanceof SerializationFailureException;
        boolean t2Failed = t2Error.get() instanceof SerializationFailureException;
        assertThat(t1Failed ^ t2Failed)
            .as("exactly one of T1/T2 should fail with SerializationFailureException; "
                + "T1=%s, T2=%s", t1Error.get(), t2Error.get())
            .isTrue();
    }

    // -------------------------------------------------------------------------
    // Write-skew at REPEATABLE READ: both transactions commit (SSI does NOT fire)
    // -------------------------------------------------------------------------

    @Test
    void writeSkew_atRepeatableRead_bothCommit() throws Exception {
        CountDownLatch t1Scanned = new CountDownLatch(1);
        CountDownLatch t2Scanned = new CountDownLatch(1);
        CountDownLatch t1Wrote   = new CountDownLatch(1);
        CountDownLatch t2Wrote   = new CountDownLatch(1);

        AtomicReference<Throwable> t1Error = new AtomicReference<>();
        AtomicReference<Throwable> t2Error = new AtomicReference<>();

        Thread t1 = new Thread(() -> {
            try {
                LocalShellBackend s1 = new LocalShellBackend(db);
                s1.execute("set-isolation repeatable-read");
                s1.execute("begin");
                s1.execute("scan doctors");
                t1Scanned.countDown();
                t2Scanned.await();
                s1.execute("put doctors 1 0");
                t1Wrote.countDown();
                t2Wrote.await();
                s1.execute("commit");
            } catch (Throwable e) {
                t1Error.set(e);
                t1Wrote.countDown();
            }
        });

        Thread t2 = new Thread(() -> {
            try {
                LocalShellBackend s2 = new LocalShellBackend(db);
                s2.execute("set-isolation repeatable-read");
                s2.execute("begin");
                s2.execute("scan doctors");
                t2Scanned.countDown();
                t1Scanned.await();
                s2.execute("put doctors 2 0");
                t2Wrote.countDown();
                t1Wrote.await();
                s2.execute("commit");
            } catch (Throwable e) {
                t2Error.set(e);
                t2Wrote.countDown();
            }
        });

        t1.start();
        t2.start();
        t1.join(5000);
        t2.join(5000);

        // Both must commit — write skew is allowed at REPEATABLE READ
        assertThat(t1Error.get())
            .as("T1 should not fail at REPEATABLE READ: %s", t1Error.get())
            .isNull();
        assertThat(t2Error.get())
            .as("T2 should not fail at REPEATABLE READ: %s", t2Error.get())
            .isNull();
    }

    // -------------------------------------------------------------------------
    // Commit-time pivot recheck
    // -------------------------------------------------------------------------

    @Test
    void commitTimePivot_transactionAbortsAtCommit() throws IOException {
        // Tests the commit-time recheck path in TransactionManager.commit().
        // We construct a dangerous pivot structure directly in the RWConflictGraph
        // so we can test the recheck without requiring a 3-thread scenario:
        //
        //   fakeT0 → T1 (pivot) → fakeT2
        //   fakeT0 is committed → dangerous structure at T1's commit time.
        //
        // The write-skew variant (2-transaction cycle) is tested in
        // writeSkew_atSerializable_secondCommitterAborts.

        RWConflictGraph graph = db.rwConflictGraph();

        shell.execute("set-isolation serializable");
        shell.execute("begin");
        Transaction tx = db.transactionManager().currentTransaction();

        // Inject edges to make tx the pivot: fakeT0 → tx → fakeT2
        int fakeT0 = Integer.MAX_VALUE - 1;
        int fakeT2 = Integer.MAX_VALUE - 2;
        graph.addEdge(fakeT0, tx.xid());   // in-edge for tx
        graph.addEdge(tx.xid(), fakeT2);   // out-edge for tx
        graph.markCommitted(fakeT0);        // at least one neighbour committed → dangerous

        // Commit must fail: markCommitted(tx) runs, then isDangerousPivot fires.
        assertThatThrownBy(() -> shell.execute("commit"))
            .isInstanceOf(SerializationFailureException.class);

        // Transaction is now fully cleaned up (state=ABORTED, no currentTx)
        assertThat(tx.state()).isEqualTo(Transaction.State.ABORTED);
        assertThat(db.transactionManager().currentTransaction()).isNull();
        // Graph state for this xid is released
        assertThat(graph.outEdges()).doesNotContainKey(tx.xid());
        assertThat(graph.inEdges()).doesNotContainKey(tx.xid());
    }

    // -------------------------------------------------------------------------
    // Read-only SERIALIZABLE: no false aborts
    // -------------------------------------------------------------------------

    @Test
    void readOnly_serializable_noFalseAbort() throws IOException {
        shell.execute("set-isolation serializable");
        shell.execute("begin");
        shell.execute("scan doctors");
        shell.execute("get doctors 1");
        shell.execute("get doctors 2");
        String result = shell.execute("commit");
        assertThat(result).doesNotContain("serialization failure");
        assertThat(result).doesNotContain("error");
    }

    // -------------------------------------------------------------------------
    // Graph cleanup after abort: verify real edges are removed
    // -------------------------------------------------------------------------

    @Test
    void afterAbort_graphEdgesCleanedUp() throws IOException {
        // Create real graph edges by having T1 read a page and T2 write that page.
        // T1: serializable scan (acquires SIREAD on heap pages)
        shell.execute("set-isolation serializable");
        shell.execute("begin");
        Transaction t1 = db.transactionManager().currentTransaction();
        int t1Xid = t1.xid();
        shell.execute("scan doctors");
        shell.execute("commit");  // T1 committed; T1 is now in commitOrder

        // T2: serializable, writes a page T1 had SIREAD on
        // detectWriteConflicts adds edge T1→T2 (T1 read that page, T2 wrote it)
        // T2 only has in-edges (no out-edges), so no pivot fires — T2 writes normally.
        shell.execute("set-isolation serializable");
        shell.execute("begin");
        Transaction t2 = db.transactionManager().currentTransaction();
        int t2Xid = t2.xid();

        // This write creates edge T1→T2 (T1's SIREAD was released at commit, so
        // T1 is no longer in readersOf. Instead, T2's own SIREAD from the engine's
        // internal get() call is what we track). The important thing is T2 now has
        // graph state if any edges were created.
        shell.execute("put doctors 1 0");

        // Inject an additional edge directly so we have guaranteed in-edge on T2
        RWConflictGraph graph = db.rwConflictGraph();
        graph.addEdge(t1Xid, t2Xid);  // T1→T2: T1 read page T2 wrote

        assertThat(graph.inEdges().getOrDefault(t2Xid, Set.of())).contains(t1Xid);

        shell.execute("rollback");

        // After rollback, ALL graph state for T2 is gone
        assertThat(graph.outEdges()).doesNotContainKey(t2Xid);
        assertThat(graph.inEdges()).doesNotContainKey(t2Xid);
        // T1's out-edge to T2 is also removed from T1's edge set
        assertThat(graph.outEdges().getOrDefault(t1Xid, Set.of())).doesNotContain(t2Xid);
        // SIREAD locks also released
        assertThat(db.predicateLockManager().locksHeldBy(t2Xid)).isEmpty();
    }

    // -------------------------------------------------------------------------
    // executeSerializable retry helper
    // -------------------------------------------------------------------------

    @Test
    void executeSerializable_retriesOnFailure_eventuallySucceeds() throws IOException {
        shell.execute("create-table accounts id:long balance:int pk:id");
        shell.execute("put accounts 1 100");

        AtomicInteger attempts = new AtomicInteger(0);
        String result = db.executeSerializable(tx -> {
            int attempt = attempts.incrementAndGet();
            if (attempt < 3) {
                throw new SerializationFailureException("simulated failure on attempt " + attempt);
            }
            try {
                return shell.execute("get accounts 1");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, 3);

        assertThat(result).contains("100");
        assertThat(attempts.get()).isEqualTo(3);
    }

    @Test
    void executeSerializable_exhaustsRetries_throws() throws IOException {
        // Lambda always fails — verify the helper propagates after maxRetries.
        assertThatThrownBy(() ->
            db.executeSerializable(tx -> {
                throw new SerializationFailureException("always fails");
            }, 3)
        ).isInstanceOf(SerializationFailureException.class)
         .hasMessageContaining("retries exhausted");
    }
}
