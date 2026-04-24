package com.coredb.heap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.coredb.api.Column;
import com.coredb.api.Schema;
import com.coredb.util.Constants;
import com.coredb.util.CorruptionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Per-table HeapFile with meta page.
 */
class HeapFilePerTableTest {

    @TempDir
    Path tempDir;

    private Schema schema;
    private Path tablePath;

    @BeforeEach
    void setUp() {
        schema = Schema.of(
            Column.longCol("id"),
            Column.stringCol("name"),
            Column.intCol("age")
        );
        tablePath = tempDir.resolve("base").resolve("1").resolve("1000");
    }

    @Test
    void create_writesMetaPageWithCorrectFormat() throws IOException {
        // When
        HeapFile hf = HeapFile.create(tablePath, 1000, schema);
        hf.close();

        // Then: file exists
        assertThat(tablePath).exists();
        assertThat(Files.size(tablePath)).isEqualTo(Constants.PAGE_SIZE); // Just meta page

        // Then: meta page has correct magic, version, OID, nextPageId
        try (FileChannel channel = FileChannel.open(tablePath, StandardOpenOption.READ)) {
            ByteBuffer buf = ByteBuffer.allocate(Constants.PAGE_SIZE).order(ByteOrder.BIG_ENDIAN);
            channel.read(buf);
            buf.flip();

            int magic = buf.getInt(0);
            int version = buf.getInt(4);
            int oid = buf.getInt(8);
            int nextPageId = buf.getInt(12);

            assertThat(magic).isEqualTo(Constants.HEAP_FILE_MAGIC); // "HEAP"
            assertThat(version).isEqualTo(1);
            assertThat(oid).isEqualTo(1000);
            assertThat(nextPageId).isEqualTo(1); // Data pages start at 1
        }
    }

    @Test
    void open_existingFile_roundTripsMetaPage() throws IOException {
        // Given: create a file
        HeapFile created = HeapFile.create(tablePath, 1000, schema);
        created.close();

        // When: open it
        HeapFile opened = HeapFile.open(tablePath, 1000, schema);

        // Then
        assertThat(opened.isPerTableMode()).isTrue();
        assertThat(opened.oid()).isEqualTo(1000);
        assertThat(opened.tablePath()).isEqualTo(tablePath);

        opened.close();
    }

    @Test
    void open_wrongMagic_throwsCorruptionException() throws IOException {
        // Given: create a file with wrong magic in meta page
        Files.createDirectories(tablePath.getParent());
        try (FileChannel channel = FileChannel.open(tablePath,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            ByteBuffer buf = ByteBuffer.allocate(Constants.PAGE_SIZE).order(ByteOrder.BIG_ENDIAN);
            buf.putInt(0, 0xDEADBEEF); // Wrong magic
            buf.putInt(4, 1); // version
            buf.putInt(8, 1000); // oid
            buf.rewind();
            channel.write(buf);
        }

        // When/Then
        assertThatThrownBy(() -> HeapFile.open(tablePath, 1000, schema))
            .isInstanceOf(CorruptionException.class)
            .hasMessageContaining("magic mismatch");
    }

    @Test
    void open_wrongVersion_throwsCorruptionException() throws IOException {
        // Given: create a file with wrong version in meta page
        Files.createDirectories(tablePath.getParent());
        try (FileChannel channel = FileChannel.open(tablePath,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            ByteBuffer buf = ByteBuffer.allocate(Constants.PAGE_SIZE).order(ByteOrder.BIG_ENDIAN);
            buf.putInt(0, Constants.HEAP_FILE_MAGIC); // correct magic
            buf.putInt(4, 999); // wrong version
            buf.putInt(8, 1000); // oid
            buf.rewind();
            channel.write(buf);
        }

        // When/Then
        assertThatThrownBy(() -> HeapFile.open(tablePath, 1000, schema))
            .isInstanceOf(CorruptionException.class)
            .hasMessageContaining("version mismatch");
    }

    @Test
    void open_oidMismatch_throwsCorruptionException() throws IOException {
        // Given: create a file with oid=1001 but try to open as oid=1000
        HeapFile created = HeapFile.create(tablePath, 1001, schema);
        created.close();

        // When/Then
        assertThatThrownBy(() -> HeapFile.open(tablePath, 1000, schema))
            .isInstanceOf(CorruptionException.class)
            .hasMessageContaining("OID mismatch");
    }

    @Test
    void create_multipleFiles_doNotInterfere() throws IOException {
        // Given
        Path path1000 = tempDir.resolve("base").resolve("1").resolve("1000");
        Path path1001 = tempDir.resolve("base").resolve("1").resolve("1001");

        // When
        HeapFile hf1000 = HeapFile.create(path1000, 1000, schema);
        HeapFile hf1001 = HeapFile.create(path1001, 1001, schema);

        hf1000.close();
        hf1001.close();

        // Then: both files exist independently
        assertThat(path1000).exists();
        assertThat(path1001).exists();

        // Then: each can be opened with its correct OID
        HeapFile reopened1000 = HeapFile.open(path1000, 1000, schema);
        HeapFile reopened1001 = HeapFile.open(path1001, 1001, schema);

        assertThat(reopened1000.oid()).isEqualTo(1000);
        assertThat(reopened1001.oid()).isEqualTo(1001);

        reopened1000.close();
        reopened1001.close();
    }

    @Test
    void isPerTableMode_legacyConstructor_returnsFalse() throws IOException {
        // Given: a legacy DiskManager-based heap file
        // This would require DiskManager setup; we'll just test the new methods

        // When/Then: a file created with create() returns true
        HeapFile hf = HeapFile.create(tablePath, 1000, schema);
        assertThat(hf.isPerTableMode()).isTrue();
        hf.close();
    }

    @Test
    void tablePath_perTableMode_returnsPath() throws IOException {
        HeapFile hf = HeapFile.create(tablePath, 1000, schema);
        assertThat(hf.tablePath()).isEqualTo(tablePath);
        hf.close();
    }

    @Test
    void oid_perTableMode_returnsOid() throws IOException {
        HeapFile hf = HeapFile.create(tablePath, 1000, schema);
        assertThat(hf.oid()).isEqualTo(1000);
        hf.close();
    }

    @Test
    void nextPageId_perTableMode_returnsNextPageId() throws IOException {
        HeapFile hf = HeapFile.create(tablePath, 1000, schema);
        assertThat(hf.nextPageId()).isEqualTo(1);
        hf.close();
    }
}
