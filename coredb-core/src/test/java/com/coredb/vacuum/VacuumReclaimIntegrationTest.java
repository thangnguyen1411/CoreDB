package com.coredb.vacuum;

import static org.assertj.core.api.Assertions.assertThat;

import com.coredb.api.Column;
import com.coredb.api.Row;
import com.coredb.api.Schema;
import com.coredb.fsm.FreeSpaceMap;
import com.coredb.heap.HeapFile;
import com.coredb.heap.RecordId;
import com.coredb.index.BTree;
import com.coredb.index.IndexFile;
import com.coredb.txn.ClogManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

class VacuumReclaimIntegrationTest {

    @TempDir
    Path tempDir;

    private ClogManager clog;
    private HeapFile heap;
    private BTree pkIndex;

    private static final Schema SCHEMA = Schema.of(
            Column.longCol("id"),
            Column.stringCol("name")
    );

    @BeforeEach
    void setUp() throws Exception {
        clog = ClogManager.create(tempDir);

        Path heapPath = tempDir.resolve("heap");
        heap = HeapFile.create(heapPath, 100, SCHEMA);

        Path indexPath = tempDir.resolve("index_pk");
        IndexFile indexFile = IndexFile.create(indexPath, 200);
        pkIndex = BTree.create(indexFile);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (heap != null) heap.close();
        if (pkIndex != null) pkIndex.indexFile().close();
        if (clog != null) clog.close();
    }

    /**
     * Insert 1000 rows, commit-delete 800, vacuum, then insert 500 more.
     * The file must not grow beyond its post-vacuum page count.
     */
    @Test
    void vacuum_reclaimedSpaceReusedForNewInserts() throws IOException {
        int insertCount = 1000;
        int deleteCount = 800;
        int reinsertCount = 500;

        int insertXid = 10;
        clog.setCommitted(insertXid);

        List<RecordId> rids = new ArrayList<>(insertCount);
        for (int i = 0; i < insertCount; i++) {
            Row row = Row.of((long) i, "name-" + i);
            RecordId rid = heap.insert(row, insertXid);
            pkIndex.insert(i, rid);
            rids.add(rid);
        }

        // Delete first deleteCount rows with a committed XID
        int deleteXid = insertXid + 1;
        clog.setCommitted(deleteXid);
        for (int i = 0; i < deleteCount; i++) {
            heap.delete(rids.get(i), deleteXid);
        }

        int pagesBeforeVacuum = heap.pageCount();

        // Run vacuum; oldestXmin is beyond both committed XIDs
        int oldestXmin = deleteXid + 1;
        VacuumExecutor vacuum = new VacuumExecutor(heap, List.of(pkIndex), null, clog);
        VacuumStats stats = vacuum.vacuum(oldestXmin);

        assertThat(stats.deadTuples()).isEqualTo(deleteCount);
        assertThat(stats.indexEntriesRemoved()).isEqualTo(deleteCount);
        assertThat(stats.bytesReclaimed()).isGreaterThan(0);

        // FSM should now show significant free space
        FreeSpaceMap fsm = heap.fsm();
        assertThat(fsm.totalFreeEstimate()).isGreaterThan(0);
        assertThat(fsm.pagesWithFreeSpace()).isGreaterThan(0);

        // Insert new rows — they must reuse reclaimed pages
        int newInsertXid = deleteXid + 1;
        clog.setCommitted(newInsertXid);
        for (int i = insertCount; i < insertCount + reinsertCount; i++) {
            Row row = Row.of((long) i, "new-" + i);
            RecordId rid = heap.insert(row, newInsertXid);
            pkIndex.insert(i, rid);
        }

        int pagesAfterReinsert = heap.pageCount();

        // The file must not have grown beyond the pre-vacuum page count.
        // Since we deleted 80% and only re-inserted 50%, there is definitely room
        // in the reclaimed pages.
        assertThat(pagesAfterReinsert).isLessThanOrEqualTo(pagesBeforeVacuum);
    }
}
