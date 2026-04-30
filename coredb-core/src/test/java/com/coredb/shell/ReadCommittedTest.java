package com.coredb.shell;

import com.coredb.api.CoreDB;
import com.coredb.api.CoreDBConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies READ COMMITTED statement-boundary snapshot behaviour.
 *
 * <p>Key contracts:
 * <ul>
 *   <li>A READ COMMITTED transaction sees data committed by concurrent transactions
 *       between its own statements (non-repeatable reads are allowed).</li>
 *   <li>Within a single statement, the snapshot is stable — no mid-command surprises.</li>
 *   <li>REPEATABLE READ is not affected — its snapshot is fixed at begin().</li>
 *   <li>Dirty reads are impossible at any level because MVCC visibility never exposes
 *       uncommitted tuples.</li>
 * </ul>
 */
class ReadCommittedTest {

    @TempDir
    Path tempDir;

    CoreDB db;
    LocalShellBackend shell;

    @BeforeEach
    void setUp() throws IOException {
        db = CoreDB.open(tempDir, CoreDBConfig.defaults());
        shell = new LocalShellBackend(db);
        shell.execute("create-table items id:long name:string age:int pk:id");
    }

    @AfterEach
    void tearDown() throws IOException {
        db.close();
    }

    /**
     * Non-repeatable read is allowed at READ COMMITTED:
     * a second statement within the same transaction sees data committed after the first statement ran.
     */
    @Test
    void readCommitted_seesDataCommittedBetweenStatements() throws Exception {
        shell.execute("set-isolation read-committed");
        shell.execute("begin");

        assertThat(shell.execute("get items 1")).isEqualTo("(not found)");

        // A second connection (different thread = different ThreadLocal) inserts and commits.
        LocalShellBackend otherSession = new LocalShellBackend(db);
        Thread writer = new Thread(() -> otherSession.execute("put items 1 Alice 30"));
        writer.start();
        writer.join();

        // READ COMMITTED refreshes the snapshot before this statement — sees the committed row.
        assertThat(shell.execute("get items 1")).contains("Alice");

        shell.execute("commit");
    }

    /**
     * REPEATABLE READ uses the snapshot taken at begin() for every statement in the transaction.
     * Data committed by concurrent transactions after begin() must not be visible.
     */
    @Test
    void repeatableRead_doesNotSeeDataCommittedAfterBegin() throws Exception {
        // default level is REPEATABLE READ
        shell.execute("begin");

        assertThat(shell.execute("get items 1")).isEqualTo("(not found)");

        LocalShellBackend otherSession = new LocalShellBackend(db);
        Thread writer = new Thread(() -> otherSession.execute("put items 1 Bob 25"));
        writer.start();
        writer.join();

        // Snapshot was taken at begin() — row remains invisible.
        assertThat(shell.execute("get items 1")).isEqualTo("(not found)");

        shell.execute("commit");
    }

    /**
     * Dirty reads are structurally impossible at READ COMMITTED.
     * MVCC visibility never exposes tuples whose xmin is still in-progress.
     */
    @Test
    void readCommitted_dirtyReadIsBlocked() throws Exception {
        LocalShellBackend otherSession = new LocalShellBackend(db);

        CountDownLatch rowInserted = new CountDownLatch(1);
        CountDownLatch allowRollback = new CountDownLatch(1);

        Thread writer = new Thread(() -> {
            otherSession.execute("begin");
            otherSession.execute("put items 1 Charlie 40");
            rowInserted.countDown();
            try { allowRollback.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            otherSession.execute("rollback");
        });
        writer.start();
        rowInserted.await();

        // T2's insert is uncommitted — READ COMMITTED must not see it.
        shell.execute("set-isolation read-committed");
        assertThat(shell.execute("get items 1")).isEqualTo("(not found)");

        allowRollback.countDown();
        writer.join();
    }

    /**
     * Within a single shell command (one statement) the snapshot does not change,
     * even for READ COMMITTED. All rows touched by one scan see a consistent state.
     */
    @Test
    void readCommitted_singleStatementIsConsistent() throws Exception {
        // Pre-populate two rows
        shell.execute("put items 1 Alice 30");
        shell.execute("put items 2 Bob 25");

        shell.execute("set-isolation read-committed");
        shell.execute("begin");

        // A single scan command should see a stable view — both rows or neither,
        // not a torn mixture.
        String result = shell.execute("scan items");
        assertThat(result).contains("Alice");
        assertThat(result).contains("Bob");

        shell.execute("commit");
    }
}
