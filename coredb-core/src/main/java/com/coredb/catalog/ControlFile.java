package com.coredb.catalog;

import com.coredb.api.CoreDBConfig;
import com.coredb.config.EngineType;
import com.coredb.util.BinaryUtil;
import com.coredb.util.Constants;
import com.coredb.util.CorruptionException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * Cluster-wide control file (pg_control equivalent).
 *
 * Fixed 512-byte format:
 *   Offset  Size  Field
 *   0       4     magic (0x434F5245 = "CORE")
 *   4       4     formatVersion
 *   8       4     engineType (0=BTREE, 1=LSM)
 *   12      4     nextOid
 *   16      4     nextXid
 *   20      8     checkpointLsn
 *   28      4     crc (CRC32C of bytes 0-27)
 *   32      480   reserved (zero-filled)
 *
 * All multi-byte fields are big-endian.
 *
 * The file is rewritten atomically (write-to-temp + rename + fsync) on every
 * OID/XID allocation and checkpoint.
 */
public final class ControlFile implements AutoCloseable {

    // Layout constants
    public static final int FILE_SIZE = 512;
    public static final int MAGIC = 0x434F5245; // "CORE"
    public static final int FORMAT_VERSION = 1;

    // Field offsets
    private static final int OFFSET_MAGIC = 0;
    private static final int OFFSET_FORMAT_VERSION = 4;
    private static final int OFFSET_ENGINE_TYPE = 8;
    private static final int OFFSET_NEXT_OID = 12;
    private static final int OFFSET_NEXT_XID = 16;
    private static final int OFFSET_CHECKPOINT_LSN = 20;
    private static final int OFFSET_CRC = 28;

    // Default bootstrap values
    private static final int DEFAULT_NEXT_OID = 1002;
    private static final int DEFAULT_NEXT_XID = Constants.FIRST_NORMAL_XID;
    private static final long DEFAULT_CHECKPOINT_LSN = 0L;

    private final Path dataDir;
    private final Path controlFilePath;

    // Mutable state (protected by synchronized)
    private EngineType engineType;
    private int nextOid;
    private int nextXid;
    private long checkpointLsn;

    private ControlFile(Path dataDir, EngineType engineType, int nextOid, int nextXid, long checkpointLsn) {
        this.dataDir = dataDir;
        this.controlFilePath = dataDir.resolve("global/pg_control");
        this.engineType = engineType;
        this.nextOid = nextOid;
        this.nextXid = nextXid;
        this.checkpointLsn = checkpointLsn;
    }

    /**
     * Creates a fresh control file in the given data directory.
     * Also creates the global/ subdirectory if needed.
     *
     * @param dataDir the data directory path
     * @param config  the database configuration
     * @return a new ControlFile instance
     * @throws IOException if file creation fails
     */
    public static ControlFile create(Path dataDir, CoreDBConfig config) throws IOException {
        Path globalDir = dataDir.resolve("global");
        Files.createDirectories(globalDir);

        ControlFile cf = new ControlFile(dataDir, config.engineType(), DEFAULT_NEXT_OID, DEFAULT_NEXT_XID, DEFAULT_CHECKPOINT_LSN);
        cf.write();
        return cf;
    }

    /**
     * Loads an existing control file from the data directory.
     *
     * @param dataDir the data directory path
     * @return a ControlFile instance with state loaded from disk
     * @throws IOException            if file cannot be read
     * @throws CorruptionException    if magic, version, or CRC check fails
     */
    public static ControlFile load(Path dataDir) throws IOException {
        Path controlPath = dataDir.resolve("global/pg_control");

        if (!Files.exists(controlPath)) {
            throw new IOException("Control file not found: " + controlPath);
        }

        byte[] data = Files.readAllBytes(controlPath);
        if (data.length != FILE_SIZE) {
            throw new CorruptionException("Control file size mismatch: expected " + FILE_SIZE + ", got " + data.length);
        }

        ByteBuffer buf = ByteBuffer.wrap(data);

        int magic = BinaryUtil.readU32(buf, OFFSET_MAGIC);
        if (magic != MAGIC) {
            throw new CorruptionException(String.format("Control file magic mismatch: expected 0x%08X, got 0x%08X", MAGIC, magic));
        }

        int formatVersion = BinaryUtil.readU32(buf, OFFSET_FORMAT_VERSION);
        if (formatVersion != FORMAT_VERSION) {
            throw new CorruptionException(String.format("Control file version mismatch: expected %d, got %d", FORMAT_VERSION, formatVersion));
        }

        // Verify CRC of bytes 0-27
        long storedCrc = Integer.toUnsignedLong(BinaryUtil.readU32(buf, OFFSET_CRC));
        long computedCrc = BinaryUtil.crc32c(data, 0, OFFSET_CRC);
        if (storedCrc != computedCrc) {
            throw new CorruptionException(String.format("Control file CRC mismatch: stored=0x%08X, computed=0x%08X", storedCrc, computedCrc));
        }

        int engineTypeCode = BinaryUtil.readU32(buf, OFFSET_ENGINE_TYPE);
        EngineType engineType = switch (engineTypeCode) {
            case 0 -> EngineType.BTREE;
            case 1 -> EngineType.LSM;
            default -> throw new CorruptionException("Unknown engine type: " + engineTypeCode);
        };

        int nextOid = BinaryUtil.readU32(buf, OFFSET_NEXT_OID);
        int nextXid = BinaryUtil.readU32(buf, OFFSET_NEXT_XID);
        long checkpointLsn = BinaryUtil.readU64(buf, OFFSET_CHECKPOINT_LSN);

        return new ControlFile(dataDir, engineType, nextOid, nextXid, checkpointLsn);
    }

    /**
     * Allocates and returns the next OID. Atomically increments and persists.
     *
     * @return the allocated OID
     * @throws IOException if write fails
     */
    public synchronized int allocateOid() throws IOException {
        int oid = nextOid;
        nextOid = (int) (Integer.toUnsignedLong(nextOid) + 1);
        write();
        return oid;
    }

    /**
     * Allocates and returns the next XID. Atomically increments and persists.
     *
     * @return the allocated XID
     * @throws IOException if write fails
     */
    public synchronized int allocateXid() throws IOException {
        int xid = nextXid;
        nextXid = (int) (Integer.toUnsignedLong(nextXid) + 1);
        write();
        return xid;
    }

    /**
     * Updates the checkpoint LSN and persists.
     *
     * @param lsn the new checkpoint LSN
     * @throws IOException if write fails
     */
    public synchronized void updateCheckpointLsn(long lsn) throws IOException {
        this.checkpointLsn = lsn;
        write();
    }

    // Accessors

    public Path dataDir() {
        return dataDir;
    }

    public EngineType engineType() {
        return engineType;
    }

    public synchronized int nextOid() {
        return nextOid;
    }

    public synchronized int nextXid() {
        return nextXid;
    }

    public synchronized long checkpointLsn() {
        return checkpointLsn;
    }

    /**
     * Formats the control file contents for display.
     */
    public String formatInfo() {
        synchronized (this) {
            int crc = computeCrc();
            return String.format("""
                    magic=0x%08X ("%s")
                    formatVersion=%d
                    engineType=%s
                    nextOid=%d
                    nextXid=%d
                    checkpointLsn=%d
                    crc=0x%08X (valid)""",
                MAGIC, magicToString(),
                FORMAT_VERSION,
                engineType,
                nextOid,
                nextXid,
                checkpointLsn,
                crc);
        }
    }

    private String magicToString() {
        return new String(new byte[]{
            (byte) (MAGIC >>> 24),
            (byte) (MAGIC >>> 16),
            (byte) (MAGIC >>> 8),
            (byte) MAGIC
        });
    }

    /**
     * Serializes all fields (except CRC) into the provided buffer.
     * Buffer must have at least OFFSET_CRC bytes remaining.
     */
    private void serializeFields(ByteBuffer buf) {
        BinaryUtil.writeU32(buf, OFFSET_MAGIC, MAGIC);
        BinaryUtil.writeU32(buf, OFFSET_FORMAT_VERSION, FORMAT_VERSION);
        BinaryUtil.writeU32(buf, OFFSET_ENGINE_TYPE, engineType.ordinal());
        BinaryUtil.writeU32(buf, OFFSET_NEXT_OID, nextOid);
        BinaryUtil.writeU32(buf, OFFSET_NEXT_XID, nextXid);
        BinaryUtil.writeU64(buf, OFFSET_CHECKPOINT_LSN, checkpointLsn);
    }

    /**
     * Atomically writes the control file to disk.
     * Uses write-to-temp + rename + fsync pattern for durability.
     */
    private synchronized void write() throws IOException {
        byte[] data = new byte[FILE_SIZE];
        ByteBuffer buf = ByteBuffer.wrap(data);

        // Serialize fields 0-27, compute CRC from same buffer
        serializeFields(buf);
        int crc = (int) BinaryUtil.crc32c(data, 0, OFFSET_CRC);
        BinaryUtil.writeU32(buf, OFFSET_CRC, crc);

        // Zero-fill the rest (already zero by default)

        // Atomic write: temp file -> rename -> fsync parent
        Path tempPath = controlFilePath.resolveSibling(controlFilePath.getFileName() + ".tmp");
        try {
            Files.write(tempPath, data, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.SYNC);
            Files.move(tempPath, controlFilePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

            // Fsync parent directory for durability
            try (FileChannel dirChannel = FileChannel.open(dataDir.resolve("global"), StandardOpenOption.READ)) {
                dirChannel.force(true);
            }
        } catch (IOException e) {
            // Clean up temp file on failure
            try {
                Files.deleteIfExists(tempPath);
            } catch (IOException ignored) {
            }
            throw e;
        }
    }

    private int computeCrc() {
        // Compute CRC of fields 0-27 by serializing to a temporary buffer
        byte[] data = new byte[OFFSET_CRC];
        ByteBuffer buf = ByteBuffer.wrap(data);
        serializeFields(buf);
        return (int) BinaryUtil.crc32c(data);
    }

    @Override
    public void close() {
        // Nothing to close for this file-based resource
    }
}
