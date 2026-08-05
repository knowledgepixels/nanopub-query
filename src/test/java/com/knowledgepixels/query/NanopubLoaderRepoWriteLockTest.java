package com.knowledgepixels.query;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.junit.jupiter.api.Test;
import org.nanopub.NanopubUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The guarantee {@code repoWriteLock} has to provide, now that
 * {@code loadNanopubToRepo} no longer buys it with {@code IsolationLevels.SERIALIZABLE}.
 *
 * <p>The protected invariant is the one the replaced comment named: the per-repo nanopub
 * count and order-independent XOR checksum are a read-modify-write, and concurrent writers
 * must not interleave. Two writers both reading {@code count=N} and both writing
 * {@code N+1} lose an increment, and the last checksum write drops the other's contribution
 * — leaving the repo's bookkeeping understating what it holds. That bookkeeping is also the
 * instrument used to detect backend-side loss (issue #139), so corrupting it would blind the
 * detector as well as the data.
 *
 * <p>Scope, stated plainly: these tests exercise the locking mechanism and the arithmetic it
 * protects, not {@code loadNanopubToRepo} end to end. That method drives a live RDF4J
 * connection, and this project's unit tests cannot — see the note in
 * {@code AuthorityResolverTest} about the sail-base / sail-memory version mix breaking
 * SPARQL UPDATE in tests. The lost-update control below is what gives the passing case its
 * meaning.
 */
class NanopubLoaderRepoWriteLockTest {

    private static final SimpleValueFactory VF = SimpleValueFactory.getInstance();
    private static final int THREADS = 8;
    private static final int WRITES_PER_THREAD = 25;

    private static final String TRUSTY_BASE = "http://purl.org/np/RA";
    private static final String B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";

    /**
     * A distinct, well-formed trusty artifact code per (thread, i). {@code updateXorChecksum}
     * decodes the code, so it has to be real base64url of the right length — the values
     * themselves are arbitrary as long as they are unique.
     */
    private static String trustyCode(int thread, int i) {
        // Unique by construction: the index is encoded base64url across the leading chars.
        // Collisions would XOR-cancel and quietly weaken the checksum assertion.
        int seed = thread * WRITES_PER_THREAD + i;
        StringBuilder sb = new StringBuilder("A".repeat(43));
        for (int c = 0; c < 6; c++) {
            sb.setCharAt(c, B64.charAt((seed >> (6 * c)) & 63));
        }
        return sb.toString();
    }

    /** Stand-in for a repo's {@code npa:hasNanopubCount} / {@code npa:hasNanopubChecksum} pair. */
    private static final class RepoStatus {
        long count;
        String checksum = NanopubUtils.INIT_CHECKSUM;
    }

    @Test
    void sameRepoSharesOneLockAndDifferentReposDoNot() {
        assertSame(NanopubLoader.repoWriteLock("full"), NanopubLoader.repoWriteLock("full"));
        assertNotSame(NanopubLoader.repoWriteLock("full"), NanopubLoader.repoWriteLock("meta"),
                "writers to different repos must not contend");
    }

    @Test
    void lockIsReentrantSoNestedCallsOnOneThreadCannotSelfDeadlock() {
        ReentrantLock lock = NanopubLoader.repoWriteLock("reentrancy-check");
        lock.lock();
        try {
            assertTrue(lock.tryLock(), "a second acquisition on the same thread must succeed");
            lock.unlock();
        } finally {
            lock.unlock();
        }
    }

    /**
     * The real assertion: under the lock, concurrent writers to one repo produce exactly the
     * count and checksum of a serial run.
     */
    @Test
    void concurrentWritersUnderTheLockKeepTheCountAndChecksumChainIntact() throws Exception {
        RepoStatus status = runConcurrentWrites(true);

        assertEquals(THREADS * WRITES_PER_THREAD, status.count,
                "every write must be counted exactly once");
        assertEquals(expectedChecksum(), status.checksum,
                "the checksum must equal the XOR of every loaded nanopub");
    }

    /**
     * Control. Without the lock the same workload loses updates — which is what makes the
     * test above evidence rather than a restatement.
     */
    @Test
    void withoutTheLockTheChainIsCorrupted() throws Exception {
        // Interleaving is not guaranteed on any single run, so retry rather than risk a
        // flaky control. In practice the first attempt corrupts every time.
        boolean corrupted = false;
        for (int attempt = 0; attempt < 20 && !corrupted; attempt++) {
            RepoStatus status = runConcurrentWrites(false);
            corrupted = status.count != (long) THREADS * WRITES_PER_THREAD
                    || !expectedChecksum().equals(status.checksum);
        }
        assertTrue(corrupted,
                "expected lost updates without mutual exclusion; if this never corrupts, the "
                        + "workload is no longer contended and the sibling test proves nothing");
    }

    /** XOR is order-independent, so a correct run has one expected checksum regardless of order. */
    private static String expectedChecksum() {
        String checksum = NanopubUtils.INIT_CHECKSUM;
        for (IRI np : allNanopubs()) {
            checksum = NanopubUtils.updateXorChecksum(np, checksum);
        }
        return checksum;
    }

    private static List<IRI> allNanopubs() {
        List<IRI> l = new ArrayList<>();
        for (int t = 0; t < THREADS; t++) {
            for (int i = 0; i < WRITES_PER_THREAD; i++) {
                l.add(VF.createIRI(TRUSTY_BASE + trustyCode(t, i)));
            }
        }
        return l;
    }

    private static RepoStatus runConcurrentWrites(boolean useLock) throws Exception {
        RepoStatus status = new RepoStatus();
        ReentrantLock lock = new ReentrantLock();
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < THREADS; t++) {
                final int thread = t;
                futures.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < WRITES_PER_THREAD; i++) {
                        IRI np = VF.createIRI(TRUSTY_BASE + trustyCode(thread, i));
                        if (useLock) {
                            lock.lock();
                        }
                        try {
                            // Exactly the shape of loadNanopubToRepo's transaction body:
                            // read the current pair, derive the next one, write both back.
                            long count = status.count;
                            String checksum = status.checksum;
                            Thread.yield(); // widen the window a real store would open anyway
                            status.count = count + 1;
                            status.checksum = NanopubUtils.updateXorChecksum(np, checksum);
                        } finally {
                            if (useLock) {
                                lock.unlock();
                            }
                        }
                    }
                    return null;
                }));
            }
            start.countDown();
            for (var f : futures) {
                f.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        return status;
    }
}
