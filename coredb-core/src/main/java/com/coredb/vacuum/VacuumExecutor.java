package com.coredb.vacuum;

import com.coredb.heap.HeapFile;
import com.coredb.heap.RecordId;
import com.coredb.index.BTree;
import com.coredb.page.PageHeader;
import com.coredb.page.PageType;
import com.coredb.txn.ClogManager;
import com.coredb.util.BinaryUtil;
import com.coredb.util.Constants;
import com.coredb.wal.XLogWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Optional;

/**
 * Removes dead tuples from a heap file and cleans matching index entries.
 *
 * <p>For each heap page the executor:
 * <ol>
 *   <li>Computes the compaction result (no I/O beyond clog).</li>
 *   <li>Removes the dead heap slot's corresponding entry from every index
 *       (<em>before</em> rewriting the heap page).</li>
 *   <li>Flushes WAL for index changes so they are durable before the heap
 *       is rewritten.</li>
 *   <li>Emits a {@code HEAP_VACUUM} WAL record and writes the compacted page
 *       bytes back through the buffer pool.</li>
 * </ol>
 *
 * <p>Index-before-heap ordering means a crash after index cleanup but before
 * the heap rewrite leaves the index short an entry for a dead heap slot —
 * which is correct. A crash in the reverse order would leave the index
 * pointing at an LP_UNUSED slot.</p>
 *
 * <p>PostgreSQL equivalent: {@code lazy_vacuum_heap} + {@code lazy_vacuum_index}</p>
 */
public final class VacuumExecutor {

    private static final Logger log = LoggerFactory.getLogger(VacuumExecutor.class);

    private final HeapFile heap;
    private final List<BTree> indexes;
    private final XLogWriter xlogWriter;
    private final ClogManager clog;

    public VacuumExecutor(HeapFile heap, List<BTree> indexes,
                          XLogWriter xlogWriter, ClogManager clog) {
        this.heap = heap;
        this.indexes = indexes;
        this.xlogWriter = xlogWriter;
        this.clog = clog;
    }

    /**
     * Runs a full-table VACUUM pass.
     *
     * @param oldestXmin horizon from {@code SnapshotManager.oldestActiveXmin()} — tuples
     *                   deleted by a committed XID below this value are dead to every
     *                   possible future snapshot and may be reclaimed
     * @return aggregate statistics for the run
     * @throws IOException if any page or WAL I/O fails
     */
    public VacuumStats vacuum(int oldestXmin) throws IOException {
        int pagesScanned = 0;
        int deadTuples = 0;
        int indexEntriesRemoved = 0;
        long bytesReclaimed = 0;
        int pagesEmptied = 0;

        // Page 0 is the meta page; data pages start at 1.
        for (int pageId = 1; pageId < heap.pageCount(); pageId++) {
            HeapFile.PinnedPage pinned = heap.readPage(pageId);
            PageType type = pinned.page().pageType();
            byte[] pageBytes = pinned.page().buffer().array().clone();
            pinned.unpin(false);

            if (type != PageType.HEAP) {
                continue;
            }

            pagesScanned++;

            CompactionResult result = PageCompactor.compact(pageBytes, oldestXmin, clog);
            if (!result.hasDeadTuples()) {
                continue;
            }

            deadTuples += result.deadSlots().size();
            bytesReclaimed += result.reclaimedBytes();

            // Step 1: clean index entries for every dead slot BEFORE rewriting the heap page.
            for (int deadSlot : result.deadSlots()) {
                RecordId rid = new RecordId(pageId, deadSlot);
                for (BTree index : indexes) {
                    Optional<Long> removed = index.removeEntriesPointingAt(rid);
                    if (removed.isPresent()) {
                        indexEntriesRemoved++;
                    }
                }
            }

            // Step 2: flush WAL so index changes are durable before the heap rewrite.
            if (xlogWriter != null && !result.deadSlots().isEmpty()) {
                xlogWriter.flushUpTo(xlogWriter.currentLsn());
            }

            // Step 3: emit HEAP_VACUUM WAL and write the compacted page back.
            int[] deadSlotsArr = result.deadSlots().stream().mapToInt(Integer::intValue).toArray();
            heap.vacuumPage(pageId, result.newPageBytes(), deadSlotsArr, Constants.BOOTSTRAP_XID);

            if (isPageEmpty(result.newPageBytes())) {
                pagesEmptied++;
            }

            log.debug("vacuumed page={} dead={} reclaimed={}B", pageId,
                    result.deadSlots().size(), result.reclaimedBytes());
        }

        heap.flush();

        log.info("VACUUM complete: pages={} dead={} indexRemoved={} reclaimed={}B",
                pagesScanned, deadTuples, indexEntriesRemoved, bytesReclaimed);

        return new VacuumStats(pagesScanned, deadTuples, indexEntriesRemoved, bytesReclaimed, pagesEmptied);
    }

    private boolean isPageEmpty(byte[] pageBytes) {
        ByteBuffer buf = ByteBuffer.wrap(pageBytes).order(ByteOrder.BIG_ENDIAN);
        int pdLower = Short.toUnsignedInt(BinaryUtil.readU16(buf, PageHeader.OFFSET_PD_LOWER));
        return pdLower == PageHeader.SIZE;
    }
}
