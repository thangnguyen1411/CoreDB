package com.coredb.mvcc;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for Snapshot record.
 */
class SnapshotTest {

    @Test
    void bootstrapSnapshotSeesEverything() {
        Snapshot bootstrap = Snapshot.BOOTSTRAP;

        assertThat(bootstrap.xmin()).isEqualTo(Integer.MAX_VALUE);
        assertThat(bootstrap.xmax()).isEqualTo(Integer.MAX_VALUE);
        assertThat(bootstrap.activeXids()).isEmpty();
    }

    @Test
    void snapshotFieldsAreAccessible() {
        Set<Integer> active = Set.of(5, 6, 7);
        Snapshot snap = new Snapshot(3, 10, active);

        assertThat(snap.xmin()).isEqualTo(3);
        assertThat(snap.xmax()).isEqualTo(10);
        assertThat(snap.activeXids()).containsExactlyInAnyOrder(5, 6, 7);
    }

    @Test
    void snapshotDefensivelyCopiesActiveSet() {
        Set<Integer> original = new java.util.HashSet<>();
        original.add(5);
        original.add(6);

        Snapshot snap = new Snapshot(3, 10, original);
        original.add(7);

        assertThat(snap.activeXids()).containsExactlyInAnyOrder(5, 6);
    }

    @Test
    void isActiveReturnsTrueForActiveXid() {
        Snapshot snap = new Snapshot(3, 10, Set.of(5, 6, 7));

        assertThat(snap.isActive(5)).isTrue();
        assertThat(snap.isActive(6)).isTrue();
        assertThat(snap.isActive(7)).isTrue();
    }

    @Test
    void isActiveReturnsFalseForNonActiveXid() {
        Snapshot snap = new Snapshot(3, 10, Set.of(5, 6, 7));

        assertThat(snap.isActive(4)).isFalse();
        assertThat(snap.isActive(8)).isFalse();
        assertThat(snap.isActive(100)).isFalse();
    }

    @Test
    void snapshotWithEmptyActiveSet() {
        Snapshot snap = new Snapshot(10, 10, Collections.emptySet());

        assertThat(snap.xmin()).isEqualTo(10);
        assertThat(snap.xmax()).isEqualTo(10);
        assertThat(snap.activeXids()).isEmpty();
    }
}
