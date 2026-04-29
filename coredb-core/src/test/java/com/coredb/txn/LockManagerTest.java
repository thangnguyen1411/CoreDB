package com.coredb.txn;

import com.coredb.util.DeadlockException;
import com.coredb.util.LockTimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

        assertThatThrownBy(() -> lm.acquire(11, TAG_USERS, LockMode.EXCLUSIVE, SHORT_TIMEOUT))
            .isInstanceOf(LockTimeoutException.class);
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

    // --- 13B: deadlock detection ---

    @Test
    void deadlock_detected_second_acquire_throws_DeadlockException() throws Exception {
        // T1 holds USERS, T2 holds ORDERS.
        // T1 tries ORDERS → blocks.
        // T2 tries USERS → deadlock detected, DeadlockException thrown in T2.
        lm.acquire(1, TAG_USERS, LockMode.EXCLUSIVE, 5000L);
        lm.acquire(2, TAG_ORDERS, LockMode.EXCLUSIVE, 5000L);

        CountDownLatch t1Blocking = new CountDownLatch(1);
        AtomicReference<Throwable> t1Error = new AtomicReference<>();

        Thread t1 = new Thread(() -> {
            t1Blocking.countDown();
            try {
                lm.acquire(1, TAG_ORDERS, LockMode.EXCLUSIVE, 5000L);
            } catch (Throwable e) {
                t1Error.set(e);
            }
        });
        t1.start();

        t1Blocking.await();
        Thread.sleep(50); // ensure T1 is in TAG_ORDERS wait queue

        // T2 tries to acquire USERS — T1 holds USERS, T1 waits on ORDERS which T2 holds → cycle
        assertThatThrownBy(() -> lm.acquire(2, TAG_USERS, LockMode.EXCLUSIVE, 5000L))
            .isInstanceOf(DeadlockException.class);

        // T2 simulates rollback: release its lock on ORDERS
        lm.releaseAll(2);

        // T1 should now acquire ORDERS and complete
        t1.join(1000);
        assertThat(t1.isAlive()).isFalse();
        assertThat(t1Error.get()).isNull();
        assertThat(lm.holdersOf(TAG_ORDERS)).containsExactly(1);
    }

    @Test
    void deadlock_victim_releases_allow_other_xid_to_proceed() throws Exception {
        lm.acquire(1, TAG_USERS, LockMode.EXCLUSIVE, 5000L);
        lm.acquire(2, TAG_ORDERS, LockMode.EXCLUSIVE, 5000L);

        CountDownLatch t1Queued = new CountDownLatch(1);
        AtomicBoolean t1Succeeded = new AtomicBoolean(false);

        Thread t1 = new Thread(() -> {
            t1Queued.countDown();
            try {
                lm.acquire(1, TAG_ORDERS, LockMode.EXCLUSIVE, 5000L);
                t1Succeeded.set(true);
            } catch (Throwable ignored) {}
        });
        t1.start();

        t1Queued.await();
        Thread.sleep(50);

        // T2 is the victim: acquire throws
        assertThatThrownBy(() -> lm.acquire(2, TAG_USERS, LockMode.EXCLUSIVE, 5000L))
            .isInstanceOf(DeadlockException.class);

        // Simulate victim rollback
        lm.releaseAll(2);

        t1.join(1000);
        assertThat(t1Succeeded.get()).isTrue();
    }

    @Test
    void randomised_deadlock_detection_no_thread_hangs() throws Exception {
        for (int iter = 0; iter < 100; iter++) {
            LockManager freshLm = new LockManager();
            LockTag tagA = new LockTag(100 + iter, LockTag.LockType.TABLE);
            LockTag tagB = new LockTag(200 + iter, LockTag.LockType.TABLE);

            freshLm.acquire(1, tagA, LockMode.EXCLUSIVE, 5000L);
            freshLm.acquire(2, tagB, LockMode.EXCLUSIVE, 5000L);

            CountDownLatch t1Started = new CountDownLatch(1);
            AtomicReference<Throwable> t1Error = new AtomicReference<>();

            Thread t1 = new Thread(() -> {
                t1Started.countDown();
                try {
                    freshLm.acquire(1, tagB, LockMode.EXCLUSIVE, 5000L);
                } catch (Throwable e) {
                    t1Error.set(e);
                }
            });
            t1.start();

            t1Started.await();
            Thread.sleep(20);

            DeadlockException deadlock = null;
            try {
                freshLm.acquire(2, tagA, LockMode.EXCLUSIVE, 5000L);
            } catch (DeadlockException e) {
                deadlock = e;
            }

            assertThat(deadlock).as("iteration %d: deadlock expected", iter)
                .isNotNull();

            freshLm.releaseAll(2);
            t1.join(500);
            assertThat(t1.isAlive()).as("iteration %d: T1 should have unblocked", iter)
                .isFalse();
            assertThat(t1Error.get()).isNull();

            freshLm.releaseAll(1);
        }
    }
}
