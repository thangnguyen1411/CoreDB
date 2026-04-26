package com.coredb.index;

import com.coredb.page.Page;
import com.coredb.page.PageType;
import com.coredb.util.Constants;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.assertj.core.api.Assertions.assertThat;

class BTPageOpaqueTest {

    @Test
    void specialOffsetForIndex_shouldBePageSizeMinus12() {
        assertThat(BTPageOpaque.specialOffsetForIndex())
                .isEqualTo(Constants.PAGE_SIZE - 12);
    }

    @Test
    void btpoPrev_roundTrip() {
        Page page = Page.Factory.allocate(1, PageType.INDEX_LEAF);
        BTPageOpaque opaque = BTPageOpaque.of(page.buffer(), BTPageOpaque.specialOffsetForIndex());

        assertThat(opaque.btpoPrev()).isEqualTo(0);

        opaque.setBtpoPrev(42);
        assertThat(opaque.btpoPrev()).isEqualTo(42);

        opaque.setBtpoPrev(0);
        assertThat(opaque.btpoPrev()).isEqualTo(0);
    }

    @Test
    void btpoNext_roundTrip() {
        Page page = Page.Factory.allocate(1, PageType.INDEX_LEAF);
        BTPageOpaque opaque = BTPageOpaque.of(page.buffer(), BTPageOpaque.specialOffsetForIndex());

        assertThat(opaque.btpoNext()).isEqualTo(0);

        opaque.setBtpoNext(100);
        assertThat(opaque.btpoNext()).isEqualTo(100);
    }

    @Test
    void btpoLevel_roundTrip() {
        Page page = Page.Factory.allocate(1, PageType.INDEX_LEAF);
        BTPageOpaque opaque = BTPageOpaque.of(page.buffer(), BTPageOpaque.specialOffsetForIndex());

        assertThat(opaque.btpoLevel()).isEqualTo(0);

        opaque.setBtpoLevel(1);
        assertThat(opaque.btpoLevel()).isEqualTo(1);

        opaque.setBtpoLevel(2);
        assertThat(opaque.btpoLevel()).isEqualTo(2);
    }

    @Test
    void initAsLeaf_setsCorrectDefaults() {
        Page page = Page.Factory.allocate(1, PageType.INDEX_LEAF);
        BTPageOpaque opaque = BTPageOpaque.of(page.buffer(), BTPageOpaque.specialOffsetForIndex());

        // Set some values first
        opaque.setBtpoPrev(99);
        opaque.setBtpoNext(88);
        opaque.setBtpoLevel(5);

        // Initialize as leaf
        opaque.initAsLeaf();

        assertThat(opaque.btpoPrev()).isEqualTo(0);
        assertThat(opaque.btpoNext()).isEqualTo(0);
        assertThat(opaque.btpoLevel()).isEqualTo(0);
    }

    @Test
    void isLeaf_returnsCorrectValue() {
        Page page = Page.Factory.allocate(1, PageType.INDEX_LEAF);
        BTPageOpaque opaque = BTPageOpaque.of(page.buffer(), BTPageOpaque.specialOffsetForIndex());

        opaque.setBtpoLevel(0);
        assertThat(opaque.isLeaf()).isTrue();

        opaque.setBtpoLevel(1);
        assertThat(opaque.isLeaf()).isFalse();

        opaque.setBtpoLevel(5);
        assertThat(opaque.isLeaf()).isFalse();
    }

    @Test
    void siblingChainSetup() {
        // Simulate: Page 1 <-> Page 2 <-> Page 3
        ByteBuffer buf = ByteBuffer.allocate(Constants.PAGE_SIZE);

        // Page 1 (no prev, next = 2)
        BTPageOpaque opaque1 = BTPageOpaque.of(buf, BTPageOpaque.specialOffsetForIndex());
        opaque1.setBtpoPrev(0);
        opaque1.setBtpoNext(2);

        assertThat(opaque1.btpoPrev()).isEqualTo(0);
        assertThat(opaque1.btpoNext()).isEqualTo(2);

        // Page 2 (prev = 1, next = 3)
        BTPageOpaque opaque2 = BTPageOpaque.of(buf, BTPageOpaque.specialOffsetForIndex());
        opaque2.setBtpoPrev(1);
        opaque2.setBtpoNext(3);

        assertThat(opaque2.btpoPrev()).isEqualTo(1);
        assertThat(opaque2.btpoNext()).isEqualTo(3);

        // Page 3 (prev = 2, no next)
        BTPageOpaque opaque3 = BTPageOpaque.of(buf, BTPageOpaque.specialOffsetForIndex());
        opaque3.setBtpoPrev(2);
        opaque3.setBtpoNext(0);

        assertThat(opaque3.btpoPrev()).isEqualTo(2);
        assertThat(opaque3.btpoNext()).isEqualTo(0);
    }
}
