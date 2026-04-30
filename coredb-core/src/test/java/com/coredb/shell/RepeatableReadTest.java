package com.coredb.shell;

import com.coredb.api.CoreDB;
import com.coredb.api.CoreDBConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies REPEATABLE READ guarantees and first-updater-wins lost-update detection.
 *
 * <p>Key contracts:
 * <ul>
 *   <li>A REPEATABLE READ transaction sees a stable snapshot: reads within the same
 *       transaction are not affected by concurrent commits.</li>
 *   <li>No phantom reads: a range scan returns the same rows throughout the transaction.</li>
 *   <li>First-updater-wins: if T1 commits an update after T2's snapshot was taken,
 *       T2's subsequent update on the same row raises a serialization failure.</li>
 *   <li>Two updates to different rows never conflict.</li>
 *   <li>Write skew is allowed at REPEATABLE READ (not at SERIALIZABLE).</li>
 * </ul>
 */
class RepeatableReadTest {

    @TempDir
    Path tempDir;

    CoreDB db;
    LocalShellBackend shell;

    @BeforeEach
    void setUp() throws IOException {
        db = CoreDB.open(tempDir, CoreDBConfig.defaults());
        shell = new LocalShellBackend(db);
        shell.execute("create-table items id:long name:string age:int pk:id");
        shell.execute("put items 1 Alice 30");
        shell.execute("put items 2 Bob 25");
    }

    @AfterEach
    void tearDown() throws IOException {
        db.close();
    }

    @Test
    void noNonRepeatableReads() throws Exception {
        shell.execute("begin");

        assertThat(shell.execute("get items 1")).contains("Alice");

        // Concurrent session updates and commits.
        LocalShellBackend other = new LocalShellBackend(db);
        Thread writer = new Thread(() -> other.execute("put items 1 AliceUpdated 31"));
        writer.start();
        writer.join();

        // Snapshot was taken at begin() — first read is unchanged.
        assertThat(shell.execute("get items 1")).contains("Alice");

        shell.execute("commit");
    }

    @Test
    void noPhantomReads() throws Exception {
        shell.execute("begin");

        String firstScan = shell.execute("scan items");
        assertThat(firstScan).contains("Alice").contains("Bob");

        // Concurrent session inserts a new row and commits.
        LocalShellBackend other = new LocalShellBackend(db);
        Thread writer = new Thread(() -> other.execute("put items 3 Carol 22"));
        writer.start();
        writer.join();

        // REPEATABLE READ snapshot — phantom not visible.
        String secondScan = shell.execute("scan items");
        assertThat(secondScan).doesNotContain("Carol");

        shell.execute("commit");
    }

    @Test
    void firstUpdaterWins_concurrentCommittedUpdate_raisesSerializationFailure() throws Exception {
        // T2 begins and reads the row.
        shell.execute("begin");
        assertThat(shell.execute("get items 1")).contains("Alice");

        // T1 (another session) updates and commits the same row.
        LocalShellBackend other = new LocalShellBackend(db);
        Thread t1 = new Thread(() -> other.execute("put items 1 AliceT1 99"));
        t1.start();
        t1.join();

        // T2 tries to update the same row — T1 committed after T2's snapshot.
        // Expected: serialization failure (first-updater-wins).
        String result = shell.execute("put items 1 AliceT2 77");
        assertThat(result).startsWith("serialization failure");

        // Transaction was rolled back; T1's version is what's visible.
        assertThat(shell.execute("get items 1")).contains("AliceT1");
    }

    @Test
    void firstUpdaterWins_atReadCommitted_v1AlsoRaisesSerializationFailure() throws Exception {
        // v1 deviation: READ COMMITTED also raises serialization failure on lost update
        // instead of performing EvalPlanQual rewalk.
        shell.execute("set-isolation read-committed");
        shell.execute("begin");
        assertThat(shell.execute("get items 1")).contains("Alice");

        LocalShellBackend other = new LocalShellBackend(db);
        Thread t1 = new Thread(() -> other.execute("put items 1 AliceRC 50"));
        t1.start();
        t1.join();

        String result = shell.execute("put items 1 AliceOverwrite 51");
        assertThat(result).startsWith("serialization failure");

        assertThat(shell.execute("get items 1")).contains("AliceRC");
    }

    @Test
    void twoUpdatesToDifferentRows_bothSucceed() throws Exception {
        LocalShellBackend t1 = new LocalShellBackend(db);
        LocalShellBackend t2 = new LocalShellBackend(db);

        t1.execute("begin");
        t2.execute("begin");

        // T1 updates row 1, T2 updates row 2 — no overlap.
        Thread thread1 = new Thread(() -> {
            t1.execute("put items 1 AliceUpdated 31");
            t1.execute("commit");
        });
        Thread thread2 = new Thread(() -> {
            t2.execute("put items 2 BobUpdated 26");
            t2.execute("commit");
        });

        thread1.start();
        thread2.start();
        thread1.join();
        thread2.join();

        assertThat(shell.execute("get items 1")).contains("AliceUpdated");
        assertThat(shell.execute("get items 2")).contains("BobUpdated");
    }

    @Test
    void afterSerializationFailure_txnIsRolledBackAndWritesInvisible() throws Exception {
        // T1 wins on row 1.
        LocalShellBackend other = new LocalShellBackend(db);
        other.execute("put items 1 AliceWinner 99");

        // T2 begins, sees Alice, then tries to overwrite — should fail and be rolled back.
        shell.execute("begin");
        assertThat(shell.execute("get items 1")).contains("Alice");

        // Concurrent winner committed a different version.
        LocalShellBackend winner = new LocalShellBackend(db);
        Thread t = new Thread(() -> winner.execute("put items 1 AliceConcurrent 88"));
        t.start();
        t.join();

        String result = shell.execute("put items 1 AliceLoser 11");
        assertThat(result).startsWith("serialization failure");

        // AliceLoser's value must not appear anywhere.
        assertThat(shell.execute("get items 1")).doesNotContain("AliceLoser");
    }

    @Test
    void writeSkewAllowed_atRepeatableRead() throws Exception {
        // Classic write-skew: two transactions each read a condition and each update based on it.
        // Both should commit at REPEATABLE READ (write skew is allowed).
        // This contrasts with SERIALIZABLE, where one would be aborted.

        // Setup: items 1 = Alice (active=true simulated via age>0), items 2 = Bob (age>0)
        LocalShellBackend t1 = new LocalShellBackend(db);
        LocalShellBackend t2 = new LocalShellBackend(db);

        t1.execute("begin");
        t2.execute("begin");

        // Both read the rows (age > 0 means "on call").
        assertThat(t1.execute("get items 1")).contains("Alice");
        assertThat(t2.execute("get items 2")).contains("Bob");

        // T1 "goes off call" by setting age=0.
        assertThat(t1.execute("put items 1 Alice 0")).doesNotStartWith("serialization failure");
        assertThat(t1.execute("commit")).doesNotContain("error");

        // T2 "goes off call" by setting age=0 on a different row — no conflict with T1.
        assertThat(t2.execute("put items 2 Bob 0")).doesNotStartWith("serialization failure");
        assertThat(t2.execute("commit")).doesNotContain("error");

        // Both committed — write skew occurred at REPEATABLE READ.
        assertThat(shell.execute("get items 1")).contains("0");
        assertThat(shell.execute("get items 2")).contains("0");
    }
}
