package com.coredb.fsm;

import com.coredb.util.BinaryUtil;
import com.coredb.util.Constants;
import com.coredb.util.CorruptionException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Free Space Map — tracks how much free space each heap page has.
 *
 * <p>This is a simplified in-memory version of PostgreSQL's FSM. It stores
 * one byte per heap page representing the free space category
 * ({@code freeBytes / 32}, clamped to 255). A linear scan is used to find
 * a page with enough room.</p>
 *
 * <p><b>Key semantic:</b> FSM is a <i>hint</i>, not an authority. It is allowed
 * to be stale or wrong. Correctness must never depend on FSM accuracy — only
 * insert <i>performance</i> does.</p>
 */
public final class FreeSpaceMap {

    // FSM file format constants
    private static final int FSM_HEADER_SIZE = 16;
    private static final int FSM_VERSION = 1;

    private byte[] categories;
    private FileChannel channel;
    private Path fsmPath;

    /**
     * Constructs an empty FSM for a heap file with {@code pageCount} data pages.
     *
     * <p>Page 0 is the meta page and is never tracked by FSM. Index 0 in the
     * array corresponds to heap page ID 1.</p>
     *
     * @param pageCount number of heap data pages to track (page 1 .. pageCount)
     */
    public FreeSpaceMap(int pageCount) {
        if (pageCount < 0) {
            throw new IllegalArgumentException("pageCount must be >= 0");
        }
        this.categories = new byte[pageCount];
    }

    /**
     * Returns the number of data pages this FSM is tracking.
     * Page 0 (meta page) is not tracked, so this equals the number of heap data pages.
     */
    public int trackedDataPageCount() {
        return categories.length;
    }

    /**
     * Searches for a heap page that has at least {@code neededBytes} of free
     * space. Performs a linear scan from page 1 upward.
     *
     * <p>A category of 0 means "treat as full" and is always skipped — even
     * when {@code neededBytes} is 0. This matches PostgreSQL's behaviour.</p>
     *
     * @param neededBytes minimum free bytes required
     * @return the 1-based page ID of the first matching page, or -1 if none
     */
    public int requestPage(int neededBytes) {
        for (int i = 0; i < categories.length; i++) {
            int cat = categories[i] & 0xFF; // unsigned
            if (cat > 0 && cat * 32 >= neededBytes) {
                return i + 1; // index 0 → pageId 1
            }
        }
        return -1;
    }

    /**
     * Updates the free-space category for a given page.
     *
     * @param pageId   the 1-based page ID
     * @param freeBytes the number of free bytes on that page
     * @throws IndexOutOfBoundsException if {@code pageId} is outside the
     *                                   tracked range (use {@link #grow(int)}
     *                                   to extend tracking)
     */
    public void updatePage(int pageId, int freeBytes) {
        if (pageId < 1 || pageId > categories.length) {
            throw new IndexOutOfBoundsException(
                "pageId " + pageId + " out of bounds [1.." + categories.length + "]");
        }
        categories[pageId - 1] = (byte) categoryFor(freeBytes);
    }

    /**
     * Returns the current category for a given page.
     *
     * @param pageId the 1-based page ID
     * @return the category byte as an unsigned int (0..255)
     * @throws IndexOutOfBoundsException if {@code pageId} is outside range
     */
    public int getCategory(int pageId) {
        if (pageId < 1 || pageId > categories.length) {
            throw new IndexOutOfBoundsException(
                "pageId " + pageId + " out of bounds [1.." + categories.length + "]");
        }
        return categories[pageId - 1] & 0xFF;
    }

    /**
     * Computes the free-space category for a given number of free bytes.
     *
     * <p><b>Why divide by 32?</b> One byte can only store 0-255. A page has ~8000
     * bytes of usable space (8192 minus 16-byte header), which doesn't fit. So we
     * use "buckets": divide free bytes by 32 to get a value 0-255. 255 × 32 = 8160,
     * which covers our usable page space (8176 bytes max on an empty page).</p>
     *
     * <p><b>Why clamp to 255?</b> For CoreDB's 8KB pages, an empty page has exactly
     * 8176 free bytes (after header), so 8176/32 = 255 — the clamp never triggers.
     * The clamp is defensive: it protects against future larger page sizes and
     * guards against bugs that might pass incorrect (negative or huge) values.</p>
     *
     * <p>Category = {@code freeBytes / 32}, clamped to 255 (0xFF).</p>
     *
     * <p>Examples: 0 bytes → 0, 500 bytes → 15, 8000 bytes → 250, 9000 bytes → 255</p>
     *
     * <p>Note: Even an empty 8KB page (8176 bytes free after 16-byte header) gets
     * category 255 because 8176/32 = 255 exactly.</p>
     *
     * @param freeBytes number of free bytes on a page
     * @return category value (0..255)
     */
    public static int categoryFor(int freeBytes) {
        if (freeBytes < 0) {
            return 0;
        }
        return Math.min(freeBytes / 32, 255);
    }

    /**
     * Extends the tracked page count to {@code newPageCount}.
     * New entries are zero-filled (category 0 = full).
     *
     * @param newPageCount the new total number of pages to track
     * @throws IllegalArgumentException if {@code newPageCount} is less than
     *                                  the current page count
     */
    public void grow(int newPageCount) {
        if (newPageCount < categories.length) {
            throw new IllegalArgumentException(
                "newPageCount " + newPageCount + " < current " + categories.length);
        }
        if (newPageCount == categories.length) {
            return;
        }
        byte[] newCategories = new byte[newPageCount];
        System.arraycopy(categories, 0, newCategories, 0, categories.length);
        // remaining bytes are already zero (category 0)
        categories = newCategories;

        // If we have a file channel, extend the file by writing a zero byte at new EOF.
        // FileChannel.truncate() only shrinks, it doesn't grow. We write at the new
        // end position to force file extension. The actual body bytes are written
        // by close().
        if (channel != null) {
            try {
                long newEof = FSM_HEADER_SIZE + newPageCount;
                ByteBuffer zero = ByteBuffer.allocate(1);
                channel.write(zero, newEof - 1); // Write zero at last byte of new size
            } catch (IOException e) {
                throw new RuntimeException("Failed to grow FSM file: " + fsmPath, e);
            }
        }
    }

    // ========== File Persistence ==========

    /**
     * Creates a new FSM file at the specified path.
     *
     * <p>File format:
     * <pre>
     * Header (16 bytes):
     *   bytes 0-3:   magic (0x46534D00 = "FSM\0")
     *   bytes 4-7:   format version (1)
     *   bytes 8-11:  page count (number of heap pages tracked)
     *   bytes 12-15: reserved (zero)
     * Body: raw byte array, one byte per heap page
     * </pre></p>
     *
     * @param fsmPath the path for the FSM file
     * @param initialPages initial number of heap pages to track
     * @return a new FreeSpaceMap instance backed by the file
     * @throws IOException if file creation fails
     */
    public static FreeSpaceMap create(Path fsmPath, int initialPages) throws IOException {
        // Ensure parent directory exists
        Files.createDirectories(fsmPath.getParent());

        // Create file with header + zero-filled body
        FileChannel ch = FileChannel.open(fsmPath,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE);

        try {
            ByteBuffer header = ByteBuffer.allocate(FSM_HEADER_SIZE);
            BinaryUtil.writeU32(header, 0, Constants.FSM_FILE_MAGIC);
            BinaryUtil.writeU32(header, 4, FSM_VERSION);
            BinaryUtil.writeU32(header, 8, initialPages);
            BinaryUtil.writeU32(header, 12, 0); // reserved
            // Absolute put() doesn't advance position, so set it manually
            header.position(FSM_HEADER_SIZE);
            header.flip();
            while (header.hasRemaining()) {
                ch.write(header);
            }

            // Write zero-filled body
            if (initialPages > 0) {
                ByteBuffer zeros = ByteBuffer.allocate(initialPages);
                while (zeros.hasRemaining()) {
                    ch.write(zeros);
                }
            }

            ch.force(true);

            // Return instance directly without closing/reopening
            byte[] categories = new byte[initialPages];
            // Already zero-filled by default
            FreeSpaceMap fsm = new FreeSpaceMap(categories, ch, fsmPath);
            return fsm;

        } catch (Exception e) {
            ch.close();
            throw e;
        }
    }

    /**
     * Opens an existing FSM file.
     *
     * @param fsmPath the path to the FSM file
     * @return a FreeSpaceMap instance loaded from the file
     * @throws IOException if file cannot be read
     * @throws CorruptionException if magic or version is invalid
     */
    public static FreeSpaceMap open(Path fsmPath) throws IOException {
        if (!Files.exists(fsmPath)) {
            throw new IOException("FSM file does not exist: " + fsmPath);
        }

        FileChannel ch = FileChannel.open(fsmPath, StandardOpenOption.READ,
                StandardOpenOption.WRITE);

        try {
            // Read and validate header
            ByteBuffer header = ByteBuffer.allocate(FSM_HEADER_SIZE);
            int headerRead = 0;
            while (header.hasRemaining()) {
                int r = ch.read(header, headerRead);
                if (r < 0) {
                    throw new CorruptionException("FSM file header incomplete: " + fsmPath);
                }
                headerRead += r;
            }
            header.flip();

            int magic = BinaryUtil.readU32(header, 0);
            if (magic != Constants.FSM_FILE_MAGIC) {
                throw new CorruptionException(
                    "FSM file magic mismatch: expected 0x" +
                    Integer.toHexString(Constants.FSM_FILE_MAGIC) +
                    " but found 0x" + Integer.toHexString(magic));
            }

            int version = BinaryUtil.readU32(header, 4);
            if (version != FSM_VERSION) {
                throw new CorruptionException(
                    "FSM file version mismatch: expected " + FSM_VERSION +
                    " but found " + version);
            }

            int pageCount = BinaryUtil.readU32(header, 8);

            // Read body into byte array
            byte[] categories = new byte[pageCount];
            if (pageCount > 0) {
                ByteBuffer body = ByteBuffer.wrap(categories);
                long bodyOffset = FSM_HEADER_SIZE;
                while (body.hasRemaining()) {
                    int r = ch.read(body, bodyOffset);
                    if (r < 0) {
                        throw new CorruptionException(
                            "FSM file body incomplete: expected " + pageCount +
                            " bytes but read " + (pageCount - body.remaining()));
                    }
                    bodyOffset += r;
                }
            }

            FreeSpaceMap fsm = new FreeSpaceMap(categories, ch, fsmPath);
            return fsm;

        } catch (Exception e) {
            ch.close();
            throw e;
        }
    }

    /**
     * Private constructor for file-backed FSM.
     */
    private FreeSpaceMap(byte[] categories, FileChannel channel, Path fsmPath) {
        this.categories = categories;
        this.channel = channel;
        this.fsmPath = fsmPath;
    }

    /**
     * Closes the FSM file, writing any pending changes to disk.
     *
     * @throws IOException if write fails
     */
    public void close() throws IOException {
        if (channel == null) {
            return; // In-memory only FSM
        }

        try {
            // Write current categories back to file
            if (categories.length > 0) {
                ByteBuffer body = ByteBuffer.wrap(categories);
                long written = 0;
                while (body.hasRemaining()) {
                    int w = channel.write(body, FSM_HEADER_SIZE + written);
                    if (w == 0) {
                        // FileChannel.write() on a local file never returns 0,
                        // but if it did, continuing would cause an infinite loop.
                        throw new IOException("FSM body write returned 0 bytes");
                    }
                    written += w;
                }
            }

            // Update page count in header
            ByteBuffer header = ByteBuffer.allocate(FSM_HEADER_SIZE);
            BinaryUtil.writeU32(header, 0, Constants.FSM_FILE_MAGIC);
            BinaryUtil.writeU32(header, 4, FSM_VERSION);
            BinaryUtil.writeU32(header, 8, categories.length);
            BinaryUtil.writeU32(header, 12, 0); // reserved
            // Absolute put() doesn't advance position, so set it manually
            header.position(FSM_HEADER_SIZE);
            header.flip();
            long headerWritten = 0;
            while (header.hasRemaining()) {
                int w = channel.write(header, headerWritten);
                if (w == 0) {
                    // FileChannel.write() on a local file never returns 0,
                    // but if it did, continuing would cause an infinite loop.
                    throw new IOException("FSM header write returned 0 bytes");
                }
                headerWritten += w;
            }

            channel.force(true);
        } finally {
            channel.close();
            channel = null;
            fsmPath = null;
        }
    }
}
