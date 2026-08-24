package com.knowledgepixels.query;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The shutdown-time guarantee: no store write may be in flight when the backend goes
 * down.
 *
 * <p>Why it matters concretely — an aborted LMDB write is the incomplete-rollback
 * condition of eclipse-rdf4j/rdf4j#4775, and on 2026-08-20 and 2026-08-24 that class of
 * abort left the fleet with metadata reattached to the wrong subjects and two nanopubs
 * gone from a node while its registry still served them. Signal forwarding and a long
 * {@code stop_grace_period} keep the backend from being hard-killed; this is the other
 * half, on the writer's side.
 */
class ShutdownQuiescenceTest {

    @AfterEach
    void clearShutdownState() {
        // Both pieces of state are static: without this, a later test in the same JVM
        // would meet a suspended loader holding every repo write lock.
        NanopubLoader.resetShutdownStateForTesting();
    }

    @Test
    void quiescesImmediatelyWhenNoWriteIsRunning() {
        NanopubLoader.repoWriteLock("meta");
        NanopubLoader.repoWriteLock("full");

        assertTrue(NanopubLoader.suspendWritesAndAwaitQuiescence(5_000),
                "an idle loader quiesces at once");
        assertTrue(NanopubLoader.writesSuspended(),
                "and new writes are refused from that point on");
    }

    @Test
    void waitsForAWriteThatIsAlreadyInFlight() throws Exception {
        ReentrantLock metaLock = NanopubLoader.repoWriteLock("meta");
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread writer = new Thread(() -> {
            metaLock.lock();
            try {
                holding.countDown();
                release.await();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } finally {
                metaLock.unlock();
            }
        });
        writer.start();
        assertTrue(holding.await(5, TimeUnit.SECONDS), "writer took the lock");

        // Budget expires while the write is still running: shutdown must report that
        // rather than close the store underneath it.
        assertFalse(NanopubLoader.suspendWritesAndAwaitQuiescence(200),
                "an in-flight write is reported, not ignored");

        release.countDown();
        writer.join(5_000);

        // Once the writer is done, the same call succeeds — the wait is what buys
        // quiescence, and a slow write only needs a bigger budget.
        assertTrue(NanopubLoader.suspendWritesAndAwaitQuiescence(5_000),
                "quiesces as soon as the in-flight write finishes");
    }

    @Test
    void suspensionIsVisibleToWritePathsBeforeTheWaitCompletes() throws Exception {
        ReentrantLock spacesLock = NanopubLoader.repoWriteLock("spaces");
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread writer = new Thread(() -> {
            spacesLock.lock();
            try {
                holding.countDown();
                release.await();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } finally {
                spacesLock.unlock();
            }
        });
        writer.start();
        assertTrue(holding.await(5, TimeUnit.SECONDS), "writer took the lock");

        assertFalse(NanopubLoader.suspendWritesAndAwaitQuiescence(200));
        // The flag is set up front, so a writer that has not yet taken its lock aborts
        // instead of queueing behind the one we are waiting for — otherwise draining
        // would never finish under a steady load stream.
        assertTrue(NanopubLoader.writesSuspended(),
                "the gate closes immediately, not only after a clean drain");

        release.countDown();
        writer.join(5_000);
    }
}
