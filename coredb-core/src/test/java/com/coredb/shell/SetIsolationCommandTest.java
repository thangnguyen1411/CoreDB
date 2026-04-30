package com.coredb.shell;

import com.coredb.api.CoreDB;
import com.coredb.api.CoreDBConfig;
import com.coredb.txn.IsolationLevel;
import com.coredb.txn.TransactionManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class SetIsolationCommandTest {

    @TempDir
    Path tempDir;

    CoreDB db;
    LocalShellBackend shell;

    @BeforeEach
    void setUp() throws IOException {
        db = CoreDB.open(tempDir, CoreDBConfig.defaults());
        shell = new LocalShellBackend(db);
    }

    @AfterEach
    void tearDown() throws IOException {
        db.close();
    }

    @Test
    void setIsolation_readCommitted_accepted() {
        String result = shell.execute("set-isolation read-committed");
        assertThat(result).contains("READ COMMITTED");
    }

    @Test
    void setIsolation_repeatableRead_accepted() {
        String result = shell.execute("set-isolation repeatable-read");
        assertThat(result).contains("REPEATABLE READ");
    }

    @Test
    void setIsolation_serializable_accepted() {
        String result = shell.execute("set-isolation serializable");
        assertThat(result).contains("SERIALIZABLE");
    }

    @Test
    void setIsolation_readUncommitted_aliasedToReadCommitted() {
        String result = shell.execute("set-isolation read-uncommitted");
        assertThat(result).contains("READ COMMITTED");
    }

    @Test
    void begin_showsIsolationLevel() {
        shell.execute("set-isolation read-committed");
        String result = shell.execute("begin");
        assertThat(result).contains("READ COMMITTED");
        shell.execute("rollback");
    }

    @Test
    void begin_defaultLevelIsRepeatableRead() {
        String result = shell.execute("begin");
        assertThat(result).contains("REPEATABLE READ");
        shell.execute("rollback");
    }

    @Test
    void setIsolation_rejectedInsideTransaction() {
        shell.execute("begin");
        String result = shell.execute("set-isolation read-committed");
        assertThat(result).startsWith("error:");
        assertThat(result).contains("inside a transaction");
        shell.execute("rollback");
    }

    @Test
    void setIsolation_allowedAfterTransactionEnds() {
        shell.execute("begin");
        shell.execute("rollback");
        String result = shell.execute("set-isolation serializable");
        assertThat(result).contains("SERIALIZABLE");
    }

    @Test
    void setIsolation_unknownLevel_returnsError() {
        String result = shell.execute("set-isolation banana");
        assertThat(result).startsWith("error:");
    }

    @Test
    void beginAfterSetIsolation_usesNewLevel() {
        shell.execute("set-isolation serializable");
        String beginResult = shell.execute("begin");
        assertThat(beginResult).contains("SERIALIZABLE");

        TransactionManager txnMgr = db.transactionManager();
        assertThat(txnMgr.currentTransaction()).isNotNull();
        assertThat(txnMgr.currentTransaction().level()).isEqualTo(IsolationLevel.SERIALIZABLE);
        shell.execute("rollback");
    }
}
