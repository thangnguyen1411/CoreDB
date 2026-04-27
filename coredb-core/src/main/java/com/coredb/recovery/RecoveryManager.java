package com.coredb.recovery;

import com.coredb.catalog.ControlFile;
import com.coredb.page.PageHeader;
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
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Arrays;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * RecoveryManager handles redo-only crash recovery.
 *
 * <p>Replays WAL records on startup to recover from crashes.
 * Uses pd_lsn checks for idempotency and handles full-page writes.
 * Matches PostgreSQL's startup/redo.c design.</p>
 *
 * <p>Recovery procedure:
 * <ol>
 *   <li>Load ControlFile to get checkpoint LSN</li>
 *   <li>Seek WAL reader to checkpoint LSN (or FIRST_LSN if no checkpoint)</li>
 *   <li>Loop reading WAL records:</li>
 *   <li>Skip CHECKPOINT records (metadata only)</li>
 *   <li>For FPW records: restore full page image</li>
 *   <li>Otherwise: apply redo if pd_lsn < record LSN</li>
 *   <li>Return RecoveryStats with counts</li>
 * </ol>
 */
public final class RecoveryManager {

    private static final Logger log = LoggerFactory.getLogger(RecoveryManager.class);

    // Resource manager IDs for dispatch
    public static final byte RMGR_HEAP = XLogRecord.RMGR_HEAP;
    public static final byte RMGR_BTREE = XLogRecord.RMGR_BTREE;
    public static final byte RMGR_XLOG = XLogRecord.RMGR_XLOG;

    // First LSN in a WAL file (after 16-byte header)
    public static final long FIRST_LSN = 16;

    // WAL file header size
    public static final int WAL_HEADER_SIZE = 16;

    private RecoveryManager() {}

    /**
     * Performs redo-only recovery from the WAL.
     *
     * @param dataDir the database data directory
     * @return RecoveryStats with counts of records processed
     * @throws IOException if recovery fails
     */
    public static RecoveryStats recover(Path dataDir) throws IOException {
        Instant startTime = Instant.now();

        Path controlPath = dataDir.resolve("global/pg_control");
        if (!controlPath.toFile().exists()) {
            // Fresh database - no recovery needed
            return RecoveryStats.noRecoveryNeeded("fresh database");
        }

        // Load ControlFile
        ControlFile controlFile = ControlFile.load(dataDir);

        // Get start LSN for recovery
        long startLsn = controlFile.checkpointLsn();
        if (startLsn == 0) {
            // No checkpoint yet - start from FIRST_LSN
            startLsn = FIRST_LSN;
        }

        Path walPath = dataDir.resolve("global/pg_wal");
        if (!walPath.toFile().exists()) {
            // WAL file doesn't exist yet - no recovery needed
            controlFile.close();
            return RecoveryStats.noRecoveryNeeded("WAL file not found");
        }

        // Create resource manager registry for dispatch
        ResourceManagerRegistry rmgrRegistry = ResourceManagerRegistry.createStandard();

        // Open WAL reader and seek to start LSN
        XLogReader reader = XLogReader.open(walPath);
        reader.seek(startLsn);

        int redone = 0;
        int fpwRestored = 0;
        int skippedByPdLsn = 0;
        long lastLsn = startLsn;

        try {
            log.info("Starting recovery from LSN={}", startLsn);

            java.util.Optional<XLogRecord> optRecord;
            while ((optRecord = reader.readNext()).isPresent()) {
                XLogRecord record = optRecord.get();
                lastLsn = record.lsn();

                // Skip CHECKPOINT records - they are metadata only
                if (record.resourceManager() == RMGR_XLOG &&
                    (record.info() & 0x7F) == XLogResourceManager.CHECKPOINT) {
                    log.debug("Skipping CHECKPOINT record at LSN={}", record.lsn());
                    continue;
                }

                // Handle full-page write records
                if (record.isFullPageWrite()) {
                    restoreFullPage(dataDir, record, rmgrRegistry);
                    fpwRestored++;
                    continue;
                }

                // Apply regular redo (with idempotency check)
                boolean applied = applyRedo(dataDir, record, rmgrRegistry);
                if (applied) {
                    redone++;
                } else {
                    skippedByPdLsn++;
                }
            }

        } finally {
            reader.close();
            controlFile.close();
        }

        long elapsedMillis = java.time.Duration.between(startTime, Instant.now()).toMillis();

        log.info("Recovery complete: redone={}, fpwRestored={}, skipped={}, elapsed={}ms",
                 redone, fpwRestored, skippedByPdLsn, elapsedMillis);

        return new RecoveryStats(startLsn, lastLsn, redone, fpwRestored, skippedByPdLsn,
                                 Instant.now(), elapsedMillis);
    }

    /**
     * Restores a full page image from a full-page write record.
     *
     * <p>Extracts the full page image from the record and writes it to
     * the correct file offset. This short-circuits the need for redo.</p>
     *
     * @param dataDir the database data directory
     * @param record the full-page write WAL record
     * @throws IOException if page restoration fails
     */
    private static void restoreFullPage(Path dataDir, XLogRecord record,
                                         ResourceManagerRegistry rmgrRegistry) throws IOException {
        byte[] data = record.data();

        // FPW payload: [PAGE_SIZE bytes pre-mutation image][mutation payload bytes]
        byte[] pageImage = new byte[Constants.PAGE_SIZE];
        ByteBuffer.wrap(data).get(pageImage);

        ByteBuffer pageBuffer = ByteBuffer.wrap(Arrays.copyOf(pageImage, pageImage.length))
                                          .order(ByteOrder.BIG_ENDIAN);

        // Apply the embedded mutation on top of the restored image
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

        log.debug("Restored full page: tableOid={}, pageId={}",
                  record.tableOid(), record.pageId());
    }

    /**
     * Applies a redo operation to a page.
     *
     * <p>Implements idempotency check: skips if page's pd_lsn >= record LSN.
     * Reads page, dispatches to resource manager, updates pd_lsn, writes back.</p>
     *
     * @param dataDir the database data directory
     * @param record the WAL record to redo
     * @param rmgrRegistry the resource manager registry
     * @return true if the redo was applied, false if skipped due to pd_lsn
     * @throws IOException if redo fails
     */
    private static boolean applyRedo(Path dataDir, XLogRecord record,
                                       ResourceManagerRegistry rmgrRegistry) throws IOException {
        // Determine file path from tableOid
        Path filePath = resolveFilePath(dataDir, record.tableOid());

        // Create page buffer for the target page
        ByteBuffer pageBuffer = ByteBuffer.allocate(Constants.PAGE_SIZE).order(ByteOrder.BIG_ENDIAN);

        // Read the page from disk
        long fileOffset = (long) record.pageId() * Constants.PAGE_SIZE;

        try (FileChannel channel = FileChannel.open(filePath,
                StandardOpenOption.READ, StandardOpenOption.WRITE, StandardOpenOption.CREATE)) {

            // Ensure file is large enough
            long currentSize = channel.size();
            if (fileOffset + Constants.PAGE_SIZE > currentSize) {
                // Page doesn't exist yet - extend file and initialize with zeros
                channel.position(fileOffset + Constants.PAGE_SIZE - 1);
                channel.write(ByteBuffer.wrap(new byte[1]));
            }

            channel.position(fileOffset);
            int bytesRead = channel.read(pageBuffer);

            if (bytesRead < Constants.PAGE_SIZE) {
                // Initialize remaining bytes to zero
                pageBuffer.position(bytesRead);
                while (pageBuffer.hasRemaining()) {
                    pageBuffer.put((byte) 0);
                }
            }

            pageBuffer.flip();

            // Check pd_lsn for idempotency
            long pdLsn = BinaryUtil.readU64(pageBuffer, PageHeader.OFFSET_LSN);
            if (pdLsn >= record.lsn()) {
                // Already applied - skip
                log.debug("Skipping redo: pd_lsn={} >= record_lsn={}", pdLsn, record.lsn());
                return false;
            }

            // Initialize new page: pd_lower=0 on a zero page means header was never written
            if (pdLsn == 0 && BinaryUtil.readU16(pageBuffer, PageHeader.OFFSET_PD_LOWER) == 0) {
                boolean isIndex = record.tableOid() >= INDEX_OID_OFFSET;
                short flags = isIndex ? (short) 0x0300 : (short) 0x0100;
                BinaryUtil.writeU16(pageBuffer, PageHeader.OFFSET_PD_LOWER, (short) PageHeader.SIZE);
                BinaryUtil.writeU16(pageBuffer, PageHeader.OFFSET_PD_UPPER, (short) Constants.PAGE_SIZE);
                BinaryUtil.writeU16(pageBuffer, PageHeader.OFFSET_PD_SPECIAL, (short) Constants.PAGE_SIZE);
                BinaryUtil.writeU16(pageBuffer, PageHeader.OFFSET_PD_FLAGS, flags);
                log.debug("Initialized fresh page for redo: tableOid={}, pageId={}",
                          record.tableOid(), record.pageId());
            }

            // Dispatch to resource manager
            rmgrRegistry.dispatch(record, pageBuffer);

            // Update page's pd_lsn
            BinaryUtil.writeU64(pageBuffer, PageHeader.OFFSET_LSN, record.lsn());

            // Write page back to disk
            pageBuffer.rewind();
            channel.position(fileOffset);
            channel.write(pageBuffer);
            channel.force(true); // fsync
        }

        log.debug("Applied redo: tableOid={}, pageId={}, lsn={}",
                  record.tableOid(), record.pageId(), record.lsn());
        return true;
    }

    // Index files use heap OID + this offset (must match BTreeStorageEngine)
    public static final int INDEX_OID_OFFSET = 0x00100000;

    /**
     * Resolves a table OID to a file path.
     *
     * <p>All heap table files are in base/1/&lt;oid&gt;.
     * Index files (OID >= INDEX_OID_OFFSET) are in base/1/&lt;baseOid&gt;_pk.</p>
     *
     * @param dataDir the database data directory
     * @param tableOid the table OID
     * @return the path to the file
     */
    private static Path resolveFilePath(Path dataDir, int tableOid) {
        // Check if this is an index OID (has the offset applied)
        if (tableOid >= INDEX_OID_OFFSET) {
            int baseOid = tableOid - INDEX_OID_OFFSET;
            return dataDir.resolve("base/1/" + baseOid + "_pk");
        }
        // Regular heap table file
        return dataDir.resolve("base/1/" + tableOid);
    }
}
