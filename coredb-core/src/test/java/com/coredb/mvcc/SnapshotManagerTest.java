package com.coredb.mvcc;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for SnapshotManager.
 */
class SnapshotManagerTest {

    @Test
    void takeSnapshotWithNoActiveTransactions() {
        SnapshotManager mgr = new SnapshotManager(10);

        Snapshot snap = mgr.takeSnapshot();

        assertThat(snap.xmin()).isEqualTo(10);
        assertThat(snap.xmax()).isEqualTo(10);
        assertThat(snap.activeXids()).isEmpty();
    }

    @Test
    void takeSnapshotCapturesActiveXids() {
        SnapshotManager mgr = new SnapshotManager(10);
        mgr.registerActiveXid(5);
        mgr.registerActiveXid(6);
        mgr.registerActiveXid(7);

        Snapshot snap = mgr.takeSnapshot();

        assertThat(snap.xmin()).isEqualTo(5);
        assertThat(snap.xmax()).isEqualTo(10);
        assertThat(snap.activeXids()).containsExactlyInAnyOrder(5, 6, 7);
    }

    @Test
    void snapshotIsImmutableAfterUnregister() {
        SnapshotManager mgr = new SnapshotManager(10);
        mgr.registerActiveXid(5);

        Snapshot snap = mgr.takeSnapshot();
        mgr.unregisterActiveXid(5);
        Snapshot snap2 = mgr.takeSnapshot();

        assertThat(snap.activeXids()).containsExactly(5);
        assertThat(snap2.activeXids()).isEmpty();
    }

    @Test
    void oldestActiveXminWithActiveTransactions() {
        SnapshotManager mgr = new SnapshotManager(20);
        mgr.registerActiveXid(10);
        mgr.registerActiveXid(15);
        mgr.registerActiveXid(12);

        assertThat(mgr.oldestActiveXmin()).isEqualTo(10);
    }

    @Test
    void oldestActiveXminWithNoActiveTransactions() {
        SnapshotManager mgr = new SnapshotManager(20);

        assertThat(mgr.oldestActiveXmin()).isEqualTo(20);
    }

    @Test
    void allocateXidIncrementsNextXid() {
        SnapshotManager mgr = new SnapshotManager(10);

        int xid1 = mgr.allocateXid();
        int xid2 = mgr.allocateXid();
        int xid3 = mgr.allocateXid();

        assertThat(xid1).isEqualTo(10);
        assertThat(xid2).isEqualTo(11);
        assertThat(xid3).isEqualTo(12);
        assertThat(mgr.nextXid()).isEqualTo(13);
    }

    @Test
    void activeCountReflectsRegisteredXids() {
        SnapshotManager mgr = new SnapshotManager(10);

        assertThat(mgr.activeCount()).isEqualTo(0);

        mgr.registerActiveXid(10);
        assertThat(mgr.activeCount()).isEqualTo(1);

        mgr.registerActiveXid(11);
        assertThat(mgr.activeCount()).isEqualTo(2);

        mgr.unregisterActiveXid(10);
        assertThat(mgr.activeCount()).isEqualTo(1);

        mgr.unregisterActiveXid(11);
        assertThat(mgr.activeCount()).isEqualTo(0);
    }

    @Test
    void duplicateRegistrationIsIdempotent() {
        SnapshotManager mgr = new SnapshotManager(10);

        mgr.registerActiveXid(5);
        mgr.registerActiveXid(5);

        assertThat(mgr.activeCount()).isEqualTo(1);
        assertThat(mgr.takeSnapshot().activeXids()).containsExactly(5);
    }

    @Test
    void unregisterUnknownXidIsNoOp() {
        SnapshotManager mgr = new SnapshotManager(10);

        mgr.unregisterActiveXid(5);

        assertThat(mgr.activeCount()).isEqualTo(0);
    }

    @Test
    void xminIsMinimumOfActiveXids() {
        SnapshotManager mgr = new SnapshotManager(100);
        mgr.registerActiveXid(50);
        mgr.registerActiveXid(30);
        mgr.registerActiveXid(40);

        Snapshot snap = mgr.takeSnapshot();

        assertThat(snap.xmin()).isEqualTo(30);
    }
}
