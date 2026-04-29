package com.coredb.recovery;

import com.coredb.catalog.ControlFile;
import com.coredb.page.PageHeader;
import com.coredb.txn.ClogManager;
import com.coredb.util.BinaryUtil;
import com.coredb.util.Constants;
import com.coredb.wal.ResourceManagerRegistry;
import com.coredb.wal.XLogReader;
import com.coredb.wal.XLogRecord;
import com.coredb.wal.XLogResourceManager;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Performs redo-only crash recovery from the WAL.
 *
 * <p>Recovery procedure:
 * <ol>
 *   <li>Load ControlFile to find checkpoint LSN and nextXid</li>
 *   <li>Open pg_xact for clog reconstruction</li>
 *   <li>Seek WAL reader to checkpoint LSN</li>
 *   <li>For each record: handle XACT records, FPW restores, or page redo</li>
 *   <li>Sweep any remaining IN_PROGRESS XIDs to ABORTED (they can't resume)</li>
 *   <li>Flush and close clog</li>
 * </ol>
 *
 * <p>No undo pass is required: uncommitted tuples have t_xmin set to an in-progress
 * XID that is absent from pg_xact after recovery, so the MVCC visibility check
 * treats them as aborted without any WAL undo.
 */
public final class RecoveryManager {

    private static final Logger log = LoggerFactory.getLogger(RecoveryManager.class);

    public static final byte RMGR_HEAP = XLogRecord.RMGR_HEAP;
    public static final byte RMGR_BTREE = XLogRecord.RMGR_BTREE;
    public static final byte RMGR_XLOG = XLogRecord.RMGR_XLOG;

    public static final long FIRST_LSN = 16;
    public static final int WAL_HEADER_SIZE = 16;

    // Index files use heap OID + this offset (must match BTreeStorageEngine)
    public static final int INDEX_OID_OFFSET = 0x00100000;

    private RecoveryManager() {}

    public static RecoveryStats recover(Path dataDir) throws IOException {
        Instant startTime = Instant.now();

        Path controlPath = dataDir.resolve("global/pg_control");
        if (!controlPath.toFile().exists()) {
            return RecoveryStats.noRecoveryNeeded("fresh database");
        }

        ControlFile controlFile = ControlFile.load(dataDir);
        long startLsn = controlFile.checkpointLsn();
        int nextXid = controlFile.nextXid();
        if (startLsn == 0) {
            startLsn = FIRST_LSN;
        }

        Path walPath = dataDir.resolve("global/pg_wal");
        if (!walPath.toFile().exists()) {
            controlFile.close();
            return RecoveryStats.noRecoveryNeeded("WAL file not found");
        }

        ResourceManagerRegistry rmgrRegistry = ResourceManagerRegistry.createStandard();

        // Open clog for XACT record replay and in-progress sweep.
        // pg_xact is always present on an existing database (created during bootstrap).
        ClogManager clog = null;
        Path pgXactPath = dataDir.resolve("global/pg_xact");
        if (Files.exists(pgXactPath)) {
            clog = ClogManager.open(dataDir);
        }

        XLogReader reader = XLogReader.open(walPath);
        reader.seek(startLsn);

        int redone = 0;
        int fpwRestored = 0;
        int skippedByPdLsn = 0;
        int xactCommitReplayed = 0;
        int xactAbortReplayed = 0;
        int xidsSweptToAborted = 0;
        long lastLsn = startLsn;

        try {
            log.info("Starting recovery from LSN={}", startLsn);

            Optional<XLogRecord> optRecord;
            while ((optRecord = reader.readNext()).isPresent()) {
                XLogRecord record = optRecord.get();
                lastLsn = record.lsn();

                // Skip CHECKPOINT — metadata only, no page mutations
                if (record.resourceManager() == RMGR_XLOG &&
                    (record.info() & 0x7F) == XLogResourceManager.CHECKPOINT) {
                    log.debug("Skipping CHECKPOINT record at LSN={}", record.lsn());
                    continue;
                }

                // Rebuild clog from XACT_COMMIT records.
                // Any commit whose WAL record survived a crash is durable.
                if (record.resourceManager() == RMGR_XLOG &&
                    (record.info() & 0x7F) == XLogResourceManager.XACT_COMMIT) {
                    if (clog != null) {
                        clog.setCommitted(record.xid());
                        xactCommitReplayed++;
                        log.debug("Replayed XACT_COMMIT xid={} at LSN={}", record.xid(), record.lsn());
                    }
                    continue;
                }

                // Rebuild clog from XACT_ABORT records.
                if (record.resourceManager() == RMGR_XLOG &&
                    (record.info() & 0x7F) == XLogResourceManager.XACT_ABORT) {
                    if (clog != null) {
                        clog.setAborted(record.xid());
                        xactAbortReplayed++;
                        log.debug("Replayed XACT_ABORT xid={} at LSN={}", record.xid(), record.lsn());
                    }
                    continue;
                }

                if (record.isFullPageWrite()) {
                    restoreFullPage(dataDir, record, rmgrRegistry);
                    fpwRestored++;
                    continue;
                }

                boolean applied = applyRedo(dataDir, record, rmgrRegistry);
                if (applied) {
                    redone++;
                } else {
                    skippedByPdLsn++;
                }
            }

            // Sweep: any XID from FIRST_NORMAL_XID up to nextXid that is still
            // IN_PROGRESS could not have committed (no commit record in WAL), so
            // it is effectively aborted. Mark it so future visibility checks agree.
            if (clog != null) {
                for (int xid = Constants.FIRST_NORMAL_XID; xid < nextXid; xid++) {
                    if (clog.getStatus(xid) == ClogManager.Status.IN_PROGRESS) {
                        clog.setAborted(xid);
                        xidsSweptToAborted++;
                    }
                }
                if (xidsSweptToAborted > 0) {
                    log.info("Swept {} in-progress XIDs to ABORTED", xidsSweptToAborted);
                }
                clog.flush();
            }

        } finally {
            try {
                reader.close();
            } finally {
                try {
                    if (clog != null) clog.close();
                } finally {
                    controlFile.close();
                }
            }
        }

        long elapsedMillis = java.time.Duration.between(startTime, Instant.now()).toMillis();

        log.info("Recovery complete: redone={}, fpwRestored={}, skipped={}, " +
                 "xactCommit={}, xactAbort={}, swept={}, elapsed={}ms",
                 redone, fpwRestored, skippedByPdLsn,
                 xactCommitReplayed, xactAbortReplayed, xidsSweptToAborted, elapsedMillis);

        return new RecoveryStats(startLsn, lastLsn, redone, fpwRestored, skippedByPdLsn,
                                 xactCommitReplayed, xactAbortReplayed, xidsSweptToAborted,
                                 Instant.now(), elapsedMillis);
    }

    private static void restoreFullPage(Path dataDir, XLogRecord record,
                                         ResourceManagerRegistry rmgrRegistry) throws IOException {
        byte[] data = record.data();

        byte[] pageImage = new byte[Constants.PAGE_SIZE];
        ByteBuffer.wrap(data).get(pageImage);

        ByteBuffer pageBuffer = ByteBuffer.wrap(Arrays.copyOf(pageImage, pageImage.length))
                                          .order(ByteOrder.BIG_ENDIAN);

        if (data.length > Constants.PAGE_SIZE) {
            byte[] mutationPayload = Arrays.copyOfRange(data, Constants.PAGE_SIZE, data.length);
            byte infoWithoutFpw = (byte) (record.info() & ~XLogRecord.XLOG_FPW);
            XLogRecord mutationRecord = XLogRecord.create(
                record.lsn(), record.xid(), record.prevLsn(),
                record.resourceManager(), infoWithoutFpw,
                record.tableOid(), record.pageId(), mutationPayload);
            rmgrRegistry.dispatch(mutationRecord, pageBuffer);
        }

        BinaryUtil.writeU64(pageBuffer, PageHeader.OFFSET_LSN, record.lsn());

        Path filePath = resolveFilePath(dataDir, record.tableOid());

        try (FileChannel channel = FileChannel.open(filePath,
                StandardOpenOption.READ, StandardOpenOption.WRITE, StandardOpenOption.CREATE)) {

            long fileOffset = (long) record.pageId() * Constants.PAGE_SIZE;

            long currentSize = channel.size();
            if (fileOffset + Constants.PAGE_SIZE > currentSize) {
                channel.position(fileOffset + Constants.PAGE_SIZE - 1);
                channel.write(ByteBuffer.wrap(new byte[1]));
            }

            channel.position(fileOffset);
            channel.write(pageBuffer);
            channel.force(true);
        }

        log.debug("Restored full page: tableOid={}, pageId={}", record.tableOid(), record.pageId());
    }

    private static boolean applyRedo(Path dataDir, XLogRecord record,
                                       ResourceManagerRegistry rmgrRegistry) throws IOException {
        Path filePath = resolveFilePath(dataDir, record.tableOid());

        ByteBuffer pageBuffer = ByteBuffer.allocate(Constants.PAGE_SIZE).order(ByteOrder.BIG_ENDIAN);

        long fileOffset = (long) record.pageId() * Constants.PAGE_SIZE;

        try (FileChannel channel = FileChannel.open(filePath,
                StandardOpenOption.READ, StandardOpenOption.WRITE, StandardOpenOption.CREATE)) {

            long currentSize = channel.size();
            if (fileOffset + Constants.PAGE_SIZE > currentSize) {
                channel.position(fileOffset + Constants.PAGE_SIZE - 1);
                channel.write(ByteBuffer.wrap(new byte[1]));
            }

            channel.position(fileOffset);
            int bytesRead = channel.read(pageBuffer);

            if (bytesRead < Constants.PAGE_SIZE) {
                pageBuffer.position(bytesRead);
                while (pageBuffer.hasRemaining()) {
                    pageBuffer.put((byte) 0);
                }
            }

            pageBuffer.flip();

            long pdLsn = BinaryUtil.readU64(pageBuffer, PageHeader.OFFSET_LSN);
            if (pdLsn >= record.lsn()) {
                log.debug("Skipping redo: pd_lsn={} >= record_lsn={}", pdLsn, record.lsn());
                return false;
            }

            if (pdLsn == 0 && BinaryUtil.readU16(pageBuffer, PageHeader.OFFSET_PD_LOWER) == 0) {
                boolean isIndex = record.tableOid() >= INDEX_OID_OFFSET;
                short flags = isIndex ? (short) 0x0300 : (short) 0x0100;
                BinaryUtil.writeU16(pageBuffer, PageHeader.OFFSET_PD_LOWER, (short) PageHeader.SIZE);
                BinaryUtil.writeU16(pageBuffer, PageHeader.OFFSET_PD_UPPER, (short) Constants.PAGE_SIZE);
                BinaryUtil.writeU16(pageBuffer, PageHeader.OFFSET_PD_SPECIAL, (short) Constants.PAGE_SIZE);
                BinaryUtil.writeU16(pageBuffer, PageHeader.OFFSET_PD_FLAGS, flags);
            }

            rmgrRegistry.dispatch(record, pageBuffer);

            BinaryUtil.writeU64(pageBuffer, PageHeader.OFFSET_LSN, record.lsn());

            pageBuffer.rewind();
            channel.position(fileOffset);
            channel.write(pageBuffer);
            channel.force(true);
        }

        log.debug("Applied redo: tableOid={}, pageId={}, lsn={}", record.tableOid(), record.pageId(), record.lsn());
        return true;
    }

    private static Path resolveFilePath(Path dataDir, int tableOid) {
        if (tableOid >= INDEX_OID_OFFSET) {
            int baseOid = tableOid - INDEX_OID_OFFSET;
            return dataDir.resolve("base/1/" + baseOid + "_pk");
        }
        return dataDir.resolve("base/1/" + tableOid);
    }
}
