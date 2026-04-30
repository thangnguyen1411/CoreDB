package com.coredb.shell;

import com.coredb.api.CoreDB;
import com.coredb.api.CoreDBConfig;
import com.coredb.util.SerializationFailureException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies every non-trivial cell in the isolation level anomaly matrix.
 *
 * <p>Matrix summary — what each level prevents:
 * <pre>
 * Phenomenon          | READ UNCOMMITTED | READ COMMITTED | REPEATABLE READ | SERIALIZABLE
 * --------------------|------------------|----------------|-----------------|-------------
 * Dirty read          | Blocked (MVCC)   | Blocked        | Blocked         | Blocked
 * Non-repeatable read | Allowed          | Allowed        | Blocked         | Blocked
 * Phantom read        | Allowed          | Allowed        | Blocked         | Blocked
 * Lost update         | v1: Blocked      | v1: Blocked    | Blocked         | Blocked
 * Write skew          | Allowed          | Allowed        | Allowed         | Blocked
 * Read-only anomaly   | Allowed          | Allowed        | Allowed         | Blocked (simple)
 * </pre>
 *
 * <p>V1 deviations documented per test:
 * <ul>
 *   <li>Lost update at READ COMMITTED raises {@link SerializationFailureException} instead
 *       of performing EvalPlanQual rewalk (PostgreSQL default). Strictly more conservative.</li>
 *   <li>Classic three-transaction read-only anomaly is not detected because SIREAD locks
 *       are released at commit (summarize-out limitation). See
 *       {@link #readOnlyAnomaly_classicVariant_v1KnownLimitation}.</li>
 * </ul>
 */
class AnomalyMatrixTest {

    @TempDir
    Path tempDir;

    CoreDB db;
    LocalShellBackend shell;

    @BeforeEach
    void setUp() throws IOException {
        db = CoreDB.open(tempDir, CoreDBConfig.defaults());
        shell = new LocalShellBackend(db);
        shell.execute("create-table rows id:long val:string oncall:int pk:id");
        shell.execute("put rows 1 Alice 1");
        shell.execute("put rows 2 Bob 1");
    }

    @AfterEach
    void tearDown() throws IOException {
        db.close();
    }

    // -------------------------------------------------------------------------
    // Dirty read: blocked at all four levels by MVCC
    // -------------------------------------------------------------------------

    @Test
    void dirtyRead_blockedAtAllLevels() throws Exception {
        String[] levels = {"read-uncommitted", "read-committed", "repeatable-read", "serializable"};
        for (String level : levels) {
            dirtyReadBlockedAt(level);
        }
    }

    private void dirtyReadBlockedAt(String level) throws Exception {
        LocalShellBackend writer = new LocalShellBackend(db);
        LocalShellBackend reader = new LocalShellBackend(db);

        CountDownLatch rowInserted = new CountDownLatch(1);
        CountDownLatch allowRollback = new CountDownLatch(1);

        Thread writerThread = new Thread(() -> {
            writer.execute("begin");
            writer.execute("put rows 99 Dirty 0");
            rowInserted.countDown();
            try { allowRollback.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            writer.execute("rollback");
        });
        writerThread.start();
        rowInserted.await();

        reader.execute("set-isolation " + level);
        String result = reader.execute("get rows 99");
        assertThat(result)
            .as("dirty read should be blocked at level=%s", level)
            .isEqualTo("(not found)");

        allowRollback.countDown();
        writerThread.join(3000);
    }

    // -------------------------------------------------------------------------
    // Non-repeatable read: allowed at READ COMMITTED, blocked at REPEATABLE READ
    // -------------------------------------------------------------------------

    @Test
    void nonRepeatableRead_allowedAtReadCommitted() throws Exception {
        LocalShellBackend t1 = new LocalShellBackend(db);
        t1.execute("set-isolation read-committed");
        t1.execute("begin");

        assertThat(t1.execute("get rows 1")).contains("Alice");

        LocalShellBackend t2 = new LocalShellBackend(db);
        Thread updater = new Thread(() -> t2.execute("put rows 1 AliceUpdated 2"));
        updater.start();
        updater.join(3000);

        // READ COMMITTED refreshes snapshot between statements — sees the new value.
        assertThat(t1.execute("get rows 1"))
            .as("READ COMMITTED must see the committed update")
            .contains("AliceUpdated");

        t1.execute("commit");
    }

    @Test
    void nonRepeatableRead_blockedAtRepeatableRead() throws Exception {
        LocalShellBackend t1 = new LocalShellBackend(db);
        t1.execute("set-isolation repeatable-read");
        t1.execute("begin");

        assertThat(t1.execute("get rows 1")).contains("Alice");

        LocalShellBackend t2 = new LocalShellBackend(db);
        Thread updater = new Thread(() -> t2.execute("put rows 1 AliceUpdated 2"));
        updater.start();
        updater.join(3000);

        // Snapshot fixed at begin() — original value still visible.
        assertThat(t1.execute("get rows 1"))
            .as("REPEATABLE READ must not see the concurrent committed update")
            .contains("Alice")
            .doesNotContain("AliceUpdated");

        t1.execute("commit");
    }

    // -------------------------------------------------------------------------
    // Phantom read: allowed at READ COMMITTED, blocked at REPEATABLE READ
    // -------------------------------------------------------------------------

    @Test
    void phantomRead_allowedAtReadCommitted() throws Exception {
        LocalShellBackend t1 = new LocalShellBackend(db);
        t1.execute("set-isolation read-committed");
        t1.execute("begin");

        String firstScan = t1.execute("scan rows");
        assertThat(firstScan).contains("Alice").contains("Bob").doesNotContain("Carol");

        LocalShellBackend t2 = new LocalShellBackend(db);
        Thread inserter = new Thread(() -> t2.execute("put rows 3 Carol 1"));
        inserter.start();
        inserter.join(3000);

        // READ COMMITTED takes a fresh snapshot — phantom row appears.
        String secondScan = t1.execute("scan rows");
        assertThat(secondScan)
            .as("READ COMMITTED must see the phantom row committed between statements")
            .contains("Carol");

        t1.execute("commit");
    }

    @Test
    void phantomRead_blockedAtRepeatableRead() throws Exception {
        LocalShellBackend t1 = new LocalShellBackend(db);
        t1.execute("set-isolation repeatable-read");
        t1.execute("begin");

        String firstScan = t1.execute("scan rows");
        assertThat(firstScan).contains("Alice").contains("Bob").doesNotContain("Carol");

        LocalShellBackend t2 = new LocalShellBackend(db);
        Thread inserter = new Thread(() -> t2.execute("put rows 3 Carol 1"));
        inserter.start();
        inserter.join(3000);

        // Snapshot fixed at begin() — phantom invisible.
        String secondScan = t1.execute("scan rows");
        assertThat(secondScan)
            .as("REPEATABLE READ must not see the phantom row")
            .doesNotContain("Carol");

        t1.execute("commit");
    }

    // -------------------------------------------------------------------------
    // Lost update: blocked at REPEATABLE READ (first-updater-wins) and READ COMMITTED
    // -------------------------------------------------------------------------

    @Test
    void lostUpdate_blockedAtRepeatableRead() throws Exception {
        LocalShellBackend t2 = new LocalShellBackend(db);
        t2.execute("set-isolation repeatable-read");
        t2.execute("begin");
        assertThat(t2.execute("get rows 1")).contains("Alice");

        // T1 updates and commits the same row after T2's snapshot.
        LocalShellBackend t1 = new LocalShellBackend(db);
        Thread t1Thread = new Thread(() -> t1.execute("put rows 1 AliceT1 99"));
        t1Thread.start();
        t1Thread.join(3000);

        // T2's update would silently overwrite T1's — first-updater-wins prevents it.
        String result = t2.execute("put rows 1 AliceT2 77");
        assertThat(result)
            .as("REPEATABLE READ must raise serialization failure on lost update")
            .startsWith("serialization failure");

        // T1's value is preserved.
        assertThat(shell.execute("get rows 1")).contains("AliceT1");
    }

    @Test
    void lostUpdate_v1AlsoBlockedAtReadCommitted() throws Exception {
        // V1 deviation: READ COMMITTED raises SerializationFailureException instead of
        // EvalPlanQual rewalk. Strictly more conservative than PostgreSQL's default.
        LocalShellBackend t2 = new LocalShellBackend(db);
        t2.execute("set-isolation read-committed");
        t2.execute("begin");
        assertThat(t2.execute("get rows 1")).contains("Alice");

        LocalShellBackend t1 = new LocalShellBackend(db);
        Thread t1Thread = new Thread(() -> t1.execute("put rows 1 AliceRC 50"));
        t1Thread.start();
        t1Thread.join(3000);

        String result = t2.execute("put rows 1 AliceOverwrite 51");
        assertThat(result)
            .as("v1 READ COMMITTED must also raise serialization failure on lost update")
            .startsWith("serialization failure");

        assertThat(shell.execute("get rows 1")).contains("AliceRC");
    }

    // -------------------------------------------------------------------------
    // Write skew: allowed at REPEATABLE READ, blocked at SERIALIZABLE
    // -------------------------------------------------------------------------

    @Test
    void writeSkew_allowedAtRepeatableRead() throws Exception {
        // Doctors-on-call pattern: both T1 and T2 read oncall=1 for both rows,
        // conclude someone else is still on call, and each set their own row to oncall=0.
        // Combined effect: 0 on-call. REPEATABLE READ allows this.
        LocalShellBackend t1 = new LocalShellBackend(db);
        LocalShellBackend t2 = new LocalShellBackend(db);

        t1.execute("set-isolation repeatable-read");
        t2.execute("set-isolation repeatable-read");
        t1.execute("begin");
        t2.execute("begin");

        // Both see two on-call rows.
        assertThat(t1.execute("scan rows")).contains("Alice").contains("Bob");
        assertThat(t2.execute("scan rows")).contains("Alice").contains("Bob");

        // Each updates a different row — no first-updater-wins conflict.
        assertThat(t1.execute("put rows 1 Alice 0")).doesNotStartWith("serialization failure");
        assertThat(t1.execute("commit")).doesNotContain("error");

        assertThat(t2.execute("put rows 2 Bob 0")).doesNotStartWith("serialization failure");
        assertThat(t2.execute("commit")).doesNotContain("error");

        // Invariant broken — write skew allowed at REPEATABLE READ.
        assertThat(shell.execute("get rows 1")).contains("0");
        assertThat(shell.execute("get rows 2")).contains("0");
    }

    @Test
    void writeSkew_blockedAtSerializable() throws Exception {
        // Same doctors scenario. SSI detects the rw-antidependency cycle and aborts exactly
        // one of the two committers. Both writes must occur before either commits so that
        // SIREAD locks are still held when each write is detected.
        CountDownLatch t1Scanned  = new CountDownLatch(1);
        CountDownLatch t2Scanned  = new CountDownLatch(1);
        CountDownLatch t1Wrote    = new CountDownLatch(1);
        CountDownLatch t2Wrote    = new CountDownLatch(1);

        AtomicReference<Throwable> t1Error = new AtomicReference<>();
        AtomicReference<Throwable> t2Error = new AtomicReference<>();

        Thread t1 = new Thread(() -> {
            try {
                LocalShellBackend s1 = new LocalShellBackend(db);
                s1.execute("set-isolation serializable");
                s1.execute("begin");
                s1.execute("scan rows");
                t1Scanned.countDown();
                t2Scanned.await();
                s1.execute("put rows 1 Alice 0");
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
                s2.execute("set-isolation serializable");
                s2.execute("begin");
                s2.execute("scan rows");
                t2Scanned.countDown();
                t1Scanned.await();
                s2.execute("put rows 2 Bob 0");
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

        // Exactly one committer must have been aborted by the SSI pivot detection.
        boolean t1Failed = t1Error.get() instanceof SerializationFailureException;
        boolean t2Failed = t2Error.get() instanceof SerializationFailureException;
        assertThat(t1Failed ^ t2Failed)
            .as("exactly one of T1/T2 must fail; T1=%s, T2=%s", t1Error.get(), t2Error.get())
            .isTrue();
    }

    // -------------------------------------------------------------------------
    // Read-only anomaly: simple variant blocked at SERIALIZABLE
    // -------------------------------------------------------------------------

    @Test
    void readOnlyAnomaly_simpleVariant_blockedAtSerializable() throws Exception {
        // Three-transaction scenario:
        //   T1 (read-only SERIALIZABLE): scans both rows — acquires SIREADs on the heap page.
        //   T2 (SERIALIZABLE): scans both rows (SIREAD), then writes row 1.
        //     → addEdge(T1, T2): T1 read a page T2 wrote.
        //   T3 (SERIALIZABLE): scans both rows (SIREAD), then writes row 2.
        //     → addEdge(T1, T3): T1 read a page T3 wrote.
        //     → addEdge(T2, T3): T2 read a page T3 wrote.
        //   T1 commits first (read-only, no pivot: in-edges empty).
        //   T3 commits next: in={T1,T2}, out={}; no pivot (out-edges empty).
        //   T2 commits: in={T1}, out={T3}. T1 is committed → PIVOT → T2 aborts.
        //
        // Invariant: T1's read-only view is inconsistent with any serial order.

        CountDownLatch allScanned    = new CountDownLatch(3);
        CountDownLatch t2Wrote       = new CountDownLatch(1);
        CountDownLatch t3Wrote       = new CountDownLatch(1);
        CountDownLatch t1Committed   = new CountDownLatch(1);
        CountDownLatch t3Committed   = new CountDownLatch(1);

        AtomicReference<Throwable> t2Error = new AtomicReference<>();

        // T1: read-only, commits after both writers have written.
        Thread t1 = new Thread(() -> {
            try {
                LocalShellBackend s1 = new LocalShellBackend(db);
                s1.execute("set-isolation serializable");
                s1.execute("begin");
                s1.execute("scan rows");
                allScanned.countDown();
                // Wait for both writes to occur before committing, so SIREAD edges
                // are recorded before T1 releases its locks.
                t2Wrote.await();
                t3Wrote.await();
                s1.execute("commit");
                t1Committed.countDown();
            } catch (Throwable e) {
                t1Committed.countDown();
            }
        });

        // T2: scans, writes row 1, then waits for T1 and T3 to commit before committing.
        Thread t2 = new Thread(() -> {
            try {
                LocalShellBackend s2 = new LocalShellBackend(db);
                s2.execute("set-isolation serializable");
                s2.execute("begin");
                s2.execute("scan rows");
                allScanned.countDown();
                allScanned.await();
                s2.execute("put rows 1 Alice 0");
                t2Wrote.countDown();
                // Commit after T1 and T3 have committed so T1 is in commitOrder
                // and T3's edges to T2 are already recorded.
                t1Committed.await();
                t3Committed.await();
                s2.execute("commit");
            } catch (Throwable e) {
                t2Error.set(e);
                t2Wrote.countDown();
            }
        });

        // T3: scans, writes row 2, commits before T2.
        Thread t3 = new Thread(() -> {
            try {
                LocalShellBackend s3 = new LocalShellBackend(db);
                s3.execute("set-isolation serializable");
                s3.execute("begin");
                s3.execute("scan rows");
                allScanned.countDown();
                allScanned.await();
                s3.execute("put rows 2 Bob 0");
                t3Wrote.countDown();
                t1Committed.await();
                s3.execute("commit");
                t3Committed.countDown();
            } catch (Throwable e) {
                t3Committed.countDown();
                t3Wrote.countDown();
            }
        });

        t1.start();
        t2.start();
        t3.start();
        t1.join(5000);
        t2.join(5000);
        t3.join(5000);

        // T2 must be aborted: it is the pivot (in={T1}, out={T3}) and T1 committed.
        assertThat(t2Error.get())
            .as("T2 must be aborted as the dangerous pivot in the read-only anomaly scenario")
            .isInstanceOf(SerializationFailureException.class);
    }

    // -------------------------------------------------------------------------
    // Read-only anomaly: classic three-transaction variant — v1 known limitation
    // -------------------------------------------------------------------------

    @Disabled("v1 known limitation: SIREAD locks released at commit; the classic read-only "
        + "anomaly where the read-only transaction commits before the pivot forms is not detected. "
        + "Lifting this requires keeping SIREAD locks past commit (summarize-out). "
        + "The simple variant (readOnlyAnomaly_simpleVariant_blockedAtSerializable) is detected "
        + "because T1 is still active when the writes occur.")
    @Test
    void readOnlyAnomaly_classicVariant_v1KnownLimitation() {
        // T1 (read-only): reads X and Y, then commits.
        // T2: reads Y, writes X — must not start until AFTER T1 commits.
        // T3: reads X (T2's version), writes Y.
        //
        // In this ordering T1's SIREAD locks are released at commit.
        // When T2 writes X, T1 is no longer in readersOf(X) — the edge T1→T2
        // is never added. Without that edge, T2 has no in-edges and cannot be
        // a pivot. The anomaly goes undetected.
    }
}
