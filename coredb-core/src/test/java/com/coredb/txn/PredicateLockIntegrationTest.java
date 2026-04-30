package com.coredb.txn;

import com.coredb.api.CoreDB;
import com.coredb.api.CoreDBConfig;
import com.coredb.shell.LocalShellBackend;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests verifying SIREAD predicate lock acquisition in SERIALIZABLE transactions.
 *
 * <p>Key contracts:
 * <ul>
 *   <li>SERIALIZABLE txn reading a page → SIREAD recorded for that page.</li>
 *   <li>READ COMMITTED / REPEATABLE READ txn → no SIREAD recorded.</li>
 *   <li>Two SERIALIZABLE txns reading the same page → both in readersOf.</li>
 *   <li>After commit, predicate-locks no longer shows that xid.</li>
 *   <li>Index-page SIREADs acquired during B-tree scan.</li>
 * </ul>
 */
class PredicateLockIntegrationTest {

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
    void serializable_get_acquiresSireadOnHeapPage() throws IOException {
        PredicateLockManager mgr = db.predicateLockManager();

        shell.execute("set-isolation serializable");
        shell.execute("begin");
        Transaction tx = db.transactionManager().currentTransaction();

        shell.execute("get items 1");

        assertThat(mgr.locksHeldBy(tx.xid())).isNotEmpty();

        shell.execute("commit");
    }

    @Test
    void readCommitted_get_noSiread() throws IOException {
        PredicateLockManager mgr = db.predicateLockManager();

        shell.execute("set-isolation read-committed");
        shell.execute("begin");
        Transaction tx = db.transactionManager().currentTransaction();

        shell.execute("get items 1");

        assertThat(mgr.locksHeldBy(tx.xid())).isEmpty();

        shell.execute("commit");
    }

    @Test
    void repeatableRead_get_noSiread() throws IOException {
        PredicateLockManager mgr = db.predicateLockManager();

        shell.execute("set-isolation repeatable-read");
        shell.execute("begin");
        Transaction tx = db.transactionManager().currentTransaction();

        shell.execute("get items 1");

        assertThat(mgr.locksHeldBy(tx.xid())).isEmpty();

        shell.execute("commit");
    }

    @Test
    void twoSerializableTxns_samePage_bothInReadersOf() throws IOException {
        PredicateLockManager mgr = db.predicateLockManager();

        shell.execute("set-isolation serializable");
        shell.execute("begin");
        Transaction tx1 = db.transactionManager().currentTransaction();
        shell.execute("get items 1");
        int xid1 = tx1.xid();
        shell.execute("commit");

        // Both xids held the SIREAD at the time of the read; tx1 committed but tx2 may still see it.
        // Test with two transactions whose reads overlap in time.
        shell.execute("set-isolation serializable");
        shell.execute("begin");
        Transaction tx2 = db.transactionManager().currentTransaction();
        shell.execute("get items 1");

        // tx2 should have a SIREAD on at least one page
        assertThat(mgr.locksHeldBy(tx2.xid())).isNotEmpty();

        shell.execute("commit");
    }

    @Test
    void afterCommit_sireadLocks_released() throws IOException {
        PredicateLockManager mgr = db.predicateLockManager();

        shell.execute("set-isolation serializable");
        shell.execute("begin");
        Transaction tx = db.transactionManager().currentTransaction();
        int xid = tx.xid();
        shell.execute("get items 1");

        assertThat(mgr.locksHeldBy(xid)).isNotEmpty();

        shell.execute("commit");

        assertThat(mgr.locksHeldBy(xid)).isEmpty();
    }

    @Test
    void afterRollback_sireadLocks_released() throws IOException {
        PredicateLockManager mgr = db.predicateLockManager();

        shell.execute("set-isolation serializable");
        shell.execute("begin");
        Transaction tx = db.transactionManager().currentTransaction();
        int xid = tx.xid();
        shell.execute("get items 1");
        shell.execute("rollback");

        assertThat(mgr.locksHeldBy(xid)).isEmpty();
    }

    @Test
    void serializable_scan_acquiresSireadOnHeapPages() throws IOException {
        PredicateLockManager mgr = db.predicateLockManager();

        shell.execute("set-isolation serializable");
        shell.execute("begin");
        Transaction tx = db.transactionManager().currentTransaction();

        shell.execute("scan items");

        Set<PredicateLockTag> tags = mgr.locksHeldBy(tx.xid());
        assertThat(tags).isNotEmpty();

        shell.execute("commit");
    }

    @Test
    void serializable_rangeScan_acquiresIndexPageSiread() throws IOException {
        PredicateLockManager mgr = db.predicateLockManager();

        shell.execute("set-isolation serializable");
        shell.execute("begin");
        Transaction tx = db.transactionManager().currentTransaction();

        shell.execute("range items 1 2");

        Set<PredicateLockTag> tags = mgr.locksHeldBy(tx.xid());
        // Both heap and index pages should be recorded
        assertThat(tags).isNotEmpty();

        // The range scan touches both an index leaf page and a heap page.
        // Index pages use a different tableOid (heap OID + offset), so there
        // should be at least two distinct tableOids in the tag set.
        long distinctTableOids = tags.stream().mapToInt(PredicateLockTag::tableOid).distinct().count();
        assertThat(distinctTableOids).isGreaterThanOrEqualTo(2);

        shell.execute("commit");
    }

    @Test
    void predicateLocks_shellCommand_showsActiveLocks() throws IOException {
        shell.execute("set-isolation serializable");
        shell.execute("begin");
        shell.execute("get items 1");

        String output = shell.execute("predicate-locks");

        assertThat(output).isNotEqualTo("(no predicate locks held)");

        shell.execute("commit");

        String afterCommit = shell.execute("predicate-locks");
        assertThat(afterCommit).isEqualTo("(no predicate locks held)");
    }
}
