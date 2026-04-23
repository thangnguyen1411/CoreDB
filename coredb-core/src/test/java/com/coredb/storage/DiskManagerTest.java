package com.coredb.storage;

import com.coredb.api.CoreDBConfig;
import com.coredb.page.PageHeader;
import com.coredb.page.Page;
import com.coredb.page.PageType;
import com.coredb.util.Constants;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DiskManagerTest {

    @TempDir
    Path tempDir;

    private static CoreDBConfig defaultConfig() {
        return CoreDBConfig.defaults();
    }

    @Test
    void newFile_createsMetaPage() throws IOException {
        Path dbFile = tempDir.resolve("test.db");
        try (DiskManager dm = DiskManager.open(dbFile, defaultConfig())) {
            assertThat(dm.pageCount()).isEqualTo(1);
            assertThat(dm.fileSize()).isEqualTo(Constants.PAGE_SIZE);
        }
    }

    @Test
    void allocatePage_incrementsPageCount() throws IOException {
        Path dbFile = tempDir.resolve("test.db");
        try (DiskManager dm = DiskManager.open(dbFile, defaultConfig())) {
            dm.allocatePage(PageType.HEAP);
            dm.allocatePage(PageType.HEAP);
            assertThat(dm.pageCount()).isEqualTo(3);
        }
    }

    @Test
    void allocatePage_assignsSequentialIds() throws IOException {
        Path dbFile = tempDir.resolve("test.db");
        try (DiskManager dm = DiskManager.open(dbFile, defaultConfig())) {
            Page p1 = dm.allocatePage(PageType.HEAP);
            Page p2 = dm.allocatePage(PageType.HEAP);
            assertThat(p1.pageId()).isEqualTo(1);
            assertThat(p2.pageId()).isEqualTo(2);
        }
    }

    @Test
    void writeThenRead_returnsIdenticalBytes() throws IOException {
        Path dbFile = tempDir.resolve("test.db");
        try (DiskManager dm = DiskManager.open(dbFile, defaultConfig())) {
            Page written = dm.allocatePage(PageType.HEAP);
            byte[] payload = written.buffer().array();
            for (int i = PageHeader.SIZE; i < Constants.PAGE_SIZE; i++) {
                payload[i] = (byte) (i % 256);
            }
            dm.writePage(written);

            Page read = dm.readPage(written.pageId());
            assertThat(read.buffer().array()).isEqualTo(written.buffer().array());
        }
    }

    @Test
    void allocate100Pages_closeReopen_allReadBackIdentical() throws IOException {
        Path dbFile = tempDir.resolve("test.db");
        List<byte[]> writtenSnapshots = new ArrayList<>();

        try (DiskManager dm = DiskManager.open(dbFile, defaultConfig())) {
            for (int i = 0; i < 100; i++) {
                Page p = dm.allocatePage(PageType.HEAP);
                byte[] arr = p.buffer().array();
                for (int j = PageHeader.SIZE; j < arr.length; j++) {
                    arr[j] = (byte) ((i + j) % 256);
                }
                dm.writePage(p);
                writtenSnapshots.add(arr.clone());
            }
            assertThat(dm.pageCount()).isEqualTo(101);
        }

        try (DiskManager dm = DiskManager.open(dbFile, defaultConfig())) {
            assertThat(dm.pageCount()).isEqualTo(101);
            for (int i = 0; i < 100; i++) {
                Page p = dm.readPage(i + 1);
                assertThat(p.buffer().array())
                        .as("page %d bytes mismatch after reopen", i + 1)
                        .isEqualTo(writtenSnapshots.get(i));
            }
        }
    }

    @Test
    void metaPage_hasCorrectType() throws IOException {
        Path dbFile = tempDir.resolve("test.db");
        try (DiskManager dm = DiskManager.open(dbFile, defaultConfig())) {
            Page meta = dm.readPage(0);
            assertThat(meta.pageType()).isEqualTo(PageType.META);
        }
    }

    @Test
    void readPage_outOfBounds_throwsStorageException() throws IOException {
        Path dbFile = tempDir.resolve("test.db");
        try (DiskManager dm = DiskManager.open(dbFile, defaultConfig())) {
            assertThatThrownBy(() -> dm.readPage(99))
                    .isInstanceOf(com.coredb.util.StorageException.class);
        }
    }

    @Test
    void reopenExistingFile_preservesPageCount() throws IOException {
        Path dbFile = tempDir.resolve("test.db");
        try (DiskManager dm = DiskManager.open(dbFile, defaultConfig())) {
            dm.allocatePage(PageType.HEAP);
            dm.allocatePage(PageType.INDEX_LEAF);
        }
        try (DiskManager dm = DiskManager.open(dbFile, defaultConfig())) {
            assertThat(dm.pageCount()).isEqualTo(3);
        }
    }

    @Test
    void allocatedPage_hasCorrectType() throws IOException {
        Path dbFile = tempDir.resolve("test.db");
        try (DiskManager dm = DiskManager.open(dbFile, defaultConfig())) {
            Page heap = dm.allocatePage(PageType.HEAP);
            Page leaf = dm.allocatePage(PageType.INDEX_LEAF);
            assertThat(heap.pageType()).isEqualTo(PageType.HEAP);
            assertThat(leaf.pageType()).isEqualTo(PageType.INDEX_LEAF);
        }
    }

    @Test
    void newPage_hasCorrectPdLowerAndPdUpper() throws IOException {
        Path dbFile = tempDir.resolve("test.db");
        try (DiskManager dm = DiskManager.open(dbFile, defaultConfig())) {
            Page page = dm.allocatePage(PageType.HEAP);
            assertThat(Short.toUnsignedInt(page.pdLower())).isEqualTo(PageHeader.SIZE);
            assertThat(Short.toUnsignedInt(page.pdUpper())).isEqualTo(Constants.PAGE_SIZE);
            assertThat(page.freeBytes()).isEqualTo(Constants.PAGE_SIZE - PageHeader.SIZE);
        }
    }
}
