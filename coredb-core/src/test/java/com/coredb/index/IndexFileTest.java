package com.coredb.index;

import com.coredb.page.Page;
import com.coredb.page.PageType;
import com.coredb.util.Constants;
import com.coredb.util.CorruptionException;
import com.coredb.util.StorageException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IndexFileTest {

    @TempDir
    Path tempDir;

    @Test
    void create_writesMetaPageAndRootPage() throws IOException {
        Path indexPath = tempDir.resolve("1002_pk");

        try (IndexFile idx = IndexFile.create(indexPath, 1002)) {
            assertThat(idx.oid()).isEqualTo(1002);
            assertThat(idx.rootPageId()).isEqualTo(1);
            assertThat(idx.treeHeight()).isEqualTo(0);
            assertThat(idx.nextPageId()).isEqualTo(2);
            assertThat(idx.pageCount()).isEqualTo(2);
            assertThat(idx.fileSize()).isEqualTo(2L * Constants.PAGE_SIZE);
        }

        // Verify file exists
        assertThat(Files.exists(indexPath)).isTrue();
    }

    @Test
    void open_readsMetaPageCorrectly() throws IOException {
        Path indexPath = tempDir.resolve("1002_pk");

        // Create first
        try (IndexFile idx = IndexFile.create(indexPath, 1002)) {
            // just create
        }

        // Then open
        try (IndexFile idx = IndexFile.open(indexPath, 1002)) {
            assertThat(idx.oid()).isEqualTo(1002);
            assertThat(idx.rootPageId()).isEqualTo(1);
            assertThat(idx.treeHeight()).isEqualTo(0);
            assertThat(idx.nextPageId()).isEqualTo(2);
        }
    }

    @Test
    void open_wrongOid_throwsCorruptionException() throws IOException {
        Path indexPath = tempDir.resolve("1002_pk");

        try (IndexFile idx = IndexFile.create(indexPath, 1002)) {
            // just create
        }

        // Try to open with wrong OID
        assertThatThrownBy(() -> IndexFile.open(indexPath, 9999))
                .isInstanceOf(CorruptionException.class)
                .hasMessageContaining("OID mismatch");
    }

    @Test
    void open_missingFile_throwsIOException() {
        Path indexPath = tempDir.resolve("nonexistent_pk");

        assertThatThrownBy(() -> IndexFile.open(indexPath, 1002))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void allocateNewPage_incrementsNextPageId() throws IOException {
        Path indexPath = tempDir.resolve("1002_pk");

        try (IndexFile idx = IndexFile.create(indexPath, 1002)) {
            assertThat(idx.nextPageId()).isEqualTo(2);

            IndexFile.PinnedPage pinned = idx.allocateNewPage(PageType.INDEX_LEAF);
            Page newPage = pinned.page();
            pinned.unpin(false);

            assertThat(newPage.pageId()).isEqualTo(2);
            assertThat(idx.nextPageId()).isEqualTo(3);
            assertThat(idx.pageCount()).isEqualTo(3);
        }
    }

    @Test
    void allocateMultiplePages_growsFileCorrectly() throws IOException {
        Path indexPath = tempDir.resolve("1002_pk");

        try (IndexFile idx = IndexFile.create(indexPath, 1002)) {
            // Initial: 2 pages (meta + root)
            assertThat(idx.fileSize()).isEqualTo(2L * Constants.PAGE_SIZE);

            idx.allocateNewPage(PageType.INDEX_LEAF);
            assertThat(idx.fileSize()).isEqualTo(3L * Constants.PAGE_SIZE);

            idx.allocateNewPage(PageType.INDEX_LEAF);
            assertThat(idx.fileSize()).isEqualTo(4L * Constants.PAGE_SIZE);
        }
    }

    @Test
    void setRootPageId_updatesRootAndHeight() throws IOException {
        Path indexPath = tempDir.resolve("1002_pk");

        try (IndexFile idx = IndexFile.create(indexPath, 1002)) {
            assertThat(idx.rootPageId()).isEqualTo(1);
            assertThat(idx.treeHeight()).isEqualTo(0);

            idx.setRootPageId(5);

            assertThat(idx.rootPageId()).isEqualTo(5);
            assertThat(idx.treeHeight()).isEqualTo(1);
        }
    }

    @Test
    void readPage_returnsCorrectPage() throws IOException {
        Path indexPath = tempDir.resolve("1002_pk");

        try (IndexFile idx = IndexFile.create(indexPath, 1002)) {
            // Root page should be page 1
            IndexFile.PinnedPage pinned = idx.readPage(1);
            Page rootPage = pinned.page();
            pinned.unpin(false);
            assertThat(rootPage.pageId()).isEqualTo(1);
            assertThat(rootPage.pageType()).isEqualTo(PageType.INDEX_LEAF);
        }
    }

    @Test
    void readPage_nonExistentPage_throws() throws IOException {
        Path indexPath = tempDir.resolve("1002_pk");

        try (IndexFile idx = IndexFile.create(indexPath, 1002)) {
            // nextPageId is 2, so page 2 doesn't exist yet
            assertThatThrownBy(() -> idx.readPage(2))
                    .isInstanceOf(StorageException.class)
                    .hasMessageContaining("does not exist");

            // Page 0 is meta, not readable via readPage
            assertThatThrownBy(() -> idx.readPage(0))
                    .isInstanceOf(StorageException.class)
                    .hasMessageContaining("does not exist");
        }
    }

    @Test
    void writePage_persistsChanges() throws IOException {
        Path indexPath = tempDir.resolve("1002_pk");

        // Create and modify a page
        try (IndexFile idx = IndexFile.create(indexPath, 1002)) {
            IndexFile.PinnedPage pinned = idx.readPage(1);
            Page rootPage = pinned.page();
            IndexPageLayout layout = IndexPageLayout.of(rootPage);

            // Add an entry
            layout.writeLeafEntry(42L, new com.coredb.heap.RecordId(5, 3));
            pinned.unpin(true); // write back to disk
        }

        // Reopen and verify
        try (IndexFile idx = IndexFile.open(indexPath, 1002)) {
            IndexFile.PinnedPage pinned = idx.readPage(1);
            Page rootPage = pinned.page();
            IndexPageLayout layout = IndexPageLayout.of(rootPage);
            pinned.unpin(false);

            assertThat(layout.entryCount()).isEqualTo(1);
            assertThat(layout.readLeafEntry(0).key()).isEqualTo(42L);
        }
    }

    @Test
    void closeThenReopen_preservesAllState() throws IOException {
        Path indexPath = tempDir.resolve("1002_pk");

        // Create, allocate some pages, modify root, close
        try (IndexFile idx = IndexFile.create(indexPath, 1002)) {
            idx.allocateNewPage(PageType.INDEX_LEAF).unpin(false); // page 2
            idx.allocateNewPage(PageType.INDEX_LEAF).unpin(false); // page 3
            idx.setRootPageId(3); // Move root to page 3
        }

        // Reopen and verify all state
        try (IndexFile idx = IndexFile.open(indexPath, 1002)) {
            assertThat(idx.oid()).isEqualTo(1002);
            assertThat(idx.rootPageId()).isEqualTo(3);
            assertThat(idx.treeHeight()).isEqualTo(1);
            assertThat(idx.nextPageId()).isEqualTo(4);
            assertThat(idx.pageCount()).isEqualTo(4);

            // All pages should be readable
            IndexFile.PinnedPage p1 = idx.readPage(1);
            assertThat(p1.page().pageId()).isEqualTo(1);
            p1.unpin(false);

            IndexFile.PinnedPage p2 = idx.readPage(2);
            assertThat(p2.page().pageId()).isEqualTo(2);
            p2.unpin(false);

            IndexFile.PinnedPage p3 = idx.readPage(3);
            assertThat(p3.page().pageId()).isEqualTo(3);
            p3.unpin(false);
        }
    }

    @Test
    void allocateNewPage_leafPage_initializedCorrectly() throws IOException {
        Path indexPath = tempDir.resolve("1002_pk");

        try (IndexFile idx = IndexFile.create(indexPath, 1002)) {
            Page newPage = idx.allocateNewPage(PageType.INDEX_LEAF);

            IndexPageLayout layout = IndexPageLayout.of(newPage);
            assertThat(layout.isLeaf()).isTrue();
            assertThat(layout.btpoPrev()).isEqualTo(0);
            assertThat(layout.btpoNext()).isEqualTo(0);
            assertThat(layout.btpoLevel()).isEqualTo(0);
        }
    }

    @Test
    void indexPath_returnsCorrectPath() throws IOException {
        Path indexPath = tempDir.resolve("base/1/1002_pk");
        Files.createDirectories(indexPath.getParent());

        try (IndexFile idx = IndexFile.create(indexPath, 1002)) {
            assertThat(idx.indexPath()).isEqualTo(indexPath);
        }
    }

    @Test
    void create_inNestedDirectory() throws IOException {
        Path nestedDir = tempDir.resolve("base/1");
        Path indexPath = nestedDir.resolve("1002_pk");

        try (IndexFile idx = IndexFile.create(indexPath, 1002)) {
            assertThat(Files.exists(indexPath)).isTrue();
            assertThat(idx.oid()).isEqualTo(1002);
        }
    }
}
