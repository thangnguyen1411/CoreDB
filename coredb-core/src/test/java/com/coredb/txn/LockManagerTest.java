package com.coredb.txn;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class LockManagerTest {

    private LockManager lm;
    private static final LockTag TAG_USERS = new LockTag(1002, LockTag.LockType.TABLE);
    private static final LockTag TAG_ORDERS = new LockTag(1003, LockTag.LockType.TABLE);
    private static final long SHORT_TIMEOUT = 100L;

    @BeforeEach
    void setUp() {
        lm = new LockManager();
    }

    @Test
    void share_lock_granted_immediately() {
        assertThat(lm.acquire(10, TAG_USERS, LockMode.SHARE, SHORT_TIMEOUT)).isTrue();
        assertThat(lm.holdersOf(TAG_USERS)).containsExactly(10);
    }

    @Test
    void two_share_locks_granted_concurrently() {
        assertThat(lm.acquire(10, TAG_USERS, LockMode.SHARE, SHORT_TIMEOUT)).isTrue();
        assertThat(lm.acquire(11, TAG_USERS, LockMode.SHARE, SHORT_TIMEOUT)).isTrue();
        assertThat(lm.holdersOf(TAG_USERS)).containsExactlyInAnyOrder(10, 11);
    }

    @Test
    void exclusive_lock_granted_when_no_holders() {
        assertThat(lm.acquire(10, TAG_USERS, LockMode.EXCLUSIVE, SHORT_TIMEOUT)).isTrue();
        assertThat(lm.holdersOf(TAG_USERS)).containsExactly(10);
    }

    @Test
    void reentrant_same_xid_acquires_twice_single_entry() {
        assertThat(lm.acquire(10, TAG_USERS, LockMode.SHARE, SHORT_TIMEOUT)).isTrue();
        assertThat(lm.acquire(10, TAG_USERS, LockMode.SHARE, SHORT_TIMEOUT)).isTrue();
        assertThat(lm.holdersOf(TAG_USERS)).containsExactly(10);
    }

    @Test
    void reentrant_share_then_exclusive_same_xid_granted() {
        assertThat(lm.acquire(10, TAG_USERS, LockMode.SHARE, SHORT_TIMEOUT)).isTrue();
        assertThat(lm.acquire(10, TAG_USERS, LockMode.EXCLUSIVE, SHORT_TIMEOUT)).isTrue();
        assertThat(lm.holdersOf(TAG_USERS)).containsExactly(10);
    }

    @Test
    void exclusive_blocks_share_then_grants_after_release() throws Exception {
        lm.acquire(10, TAG_USERS, LockMode.EXCLUSIVE, SHORT_TIMEOUT);

        AtomicBoolean acquired = new AtomicBoolean(false);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1);

        Thread waiter = new Thread(() -> {
            started.countDown();
            acquired.set(lm.acquire(11, TAG_USERS, LockMode.SHARE, 2000L));
            done.countDown();
        });
        waiter.start();

        started.await();
        Thread.sleep(50); // let the waiter block
        assertThat(lm.holdersOf(TAG_USERS)).containsExactly(10);

        lm.release(10, TAG_USERS);
        done.await();

        assertThat(acquired.get()).isTrue();
        assertThat(lm.holdersOf(TAG_USERS)).containsExactly(11);
        waiter.join();
    }

    @Test
    void share_blocks_exclusive_then_grants_after_all_share_release() throws Exception {
        lm.acquire(10, TAG_USERS, LockMode.SHARE, SHORT_TIMEOUT);
        lm.acquire(11, TAG_USERS, LockMode.SHARE, SHORT_TIMEOUT);

        AtomicBoolean acquired = new AtomicBoolean(false);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1);

        Thread writer = new Thread(() -> {
            started.countDown();
            acquired.set(lm.acquire(12, TAG_USERS, LockMode.EXCLUSIVE, 2000L));
            done.countDown();
        });
        writer.start();

        started.await();
        Thread.sleep(50);
        assertThat(acquired.get()).isFalse(); // still blocked

        lm.release(10, TAG_USERS);
        Thread.sleep(20);
        assertThat(acquired.get()).isFalse(); // still one reader

        lm.release(11, TAG_USERS);
        done.await();

        assertThat(acquired.get()).isTrue();
        assertThat(lm.holdersOf(TAG_USERS)).containsExactly(12);
        writer.join();
    }

    @Test
    void exclusive_blocks_second_exclusive_times_out() {
        lm.acquire(10, TAG_USERS, LockMode.EXCLUSIVE, SHORT_TIMEOUT);

        boolean result = lm.acquire(11, TAG_USERS, LockMode.EXCLUSIVE, SHORT_TIMEOUT);
        assertThat(result).isFalse();
        assertThat(lm.holdersOf(TAG_USERS)).containsExactly(10);
    }

    @Test
    void release_removes_from_holders() {
        lm.acquire(10, TAG_USERS, LockMode.SHARE, SHORT_TIMEOUT);
        lm.release(10, TAG_USERS);
        assertThat(lm.holdersOf(TAG_USERS)).isEmpty();
    }

    @Test
    void release_all_removes_xid_from_every_lock() {
        lm.acquire(10, TAG_USERS, LockMode.SHARE, SHORT_TIMEOUT);
        lm.acquire(10, TAG_ORDERS, LockMode.EXCLUSIVE, SHORT_TIMEOUT);

        assertThat(lm.locksHeldBy(10)).containsExactlyInAnyOrder(TAG_USERS, TAG_ORDERS);

        lm.releaseAll(10);

        assertThat(lm.holdersOf(TAG_USERS)).isEmpty();
        assertThat(lm.holdersOf(TAG_ORDERS)).isEmpty();
        assertThat(lm.locksHeldBy(10)).isEmpty();
    }

    @Test
    void release_all_unblocks_waiters() throws Exception {
        lm.acquire(10, TAG_USERS, LockMode.EXCLUSIVE, SHORT_TIMEOUT);

        AtomicBoolean acquired = new AtomicBoolean(false);
        CountDownLatch done = new CountDownLatch(1);

        Thread waiter = new Thread(() -> {
            acquired.set(lm.acquire(11, TAG_USERS, LockMode.SHARE, 2000L));
            done.countDown();
        });
        waiter.start();
        Thread.sleep(50); // let it block

        lm.releaseAll(10);
        done.await();

        assertThat(acquired.get()).isTrue();
        waiter.join();
    }

    @Test
    void holders_of_returns_correct_set() {
        lm.acquire(5, TAG_USERS, LockMode.SHARE, SHORT_TIMEOUT);
        lm.acquire(8, TAG_USERS, LockMode.SHARE, SHORT_TIMEOUT);
        assertThat(lm.holdersOf(TAG_USERS)).isEqualTo(Set.of(5, 8));
    }

    @Test
    void holders_of_unknown_tag_returns_empty() {
        assertThat(lm.holdersOf(new LockTag(9999, LockTag.LockType.TABLE))).isEmpty();
    }

    @Test
    void locks_held_by_returns_all_tables() {
        lm.acquire(7, TAG_USERS, LockMode.EXCLUSIVE, SHORT_TIMEOUT);
        lm.acquire(7, TAG_ORDERS, LockMode.SHARE, SHORT_TIMEOUT);
        assertThat(lm.locksHeldBy(7)).containsExactlyInAnyOrder(TAG_USERS, TAG_ORDERS);
    }

    @Test
    void all_locks_shows_holders_and_waiters() throws Exception {
        lm.acquire(10, TAG_USERS, LockMode.EXCLUSIVE, SHORT_TIMEOUT);

        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean done = new AtomicBoolean(false);

        Thread waiter = new Thread(() -> {
            started.countDown();
            lm.acquire(11, TAG_USERS, LockMode.SHARE, 2000L);
            done.set(true);
        });
        waiter.start();
        started.await();
        Thread.sleep(30); // let waiter block

        var snaps = lm.allLocks();
        assertThat(snaps).hasSize(1);
        var snap = snaps.get(0);
        assertThat(snap.holders()).containsKey(10);
        assertThat(snap.waiters()).hasSize(1);
        assertThat(snap.waiters().get(0).xid()).isEqualTo(11);
        assertThat(snap.waiters().get(0).mode()).isEqualTo(LockMode.SHARE);

        lm.releaseAll(10);
        waiter.join();
    }
}
