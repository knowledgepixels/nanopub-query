package com.knowledgepixels.query;

import com.knowledgepixels.query.AuthorityResolver.SpaceStateUnavailableException;
import com.knowledgepixels.query.AuthorityResolver.TierInsertedTriples;
import com.knowledgepixels.query.AuthorityResolver.TierSubjectTotals;
import com.knowledgepixels.query.vocabulary.SpacesVocab;
import org.eclipse.rdf4j.model.IRI;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Guards on the destructive half of {@link AuthorityResolver#runFullBuild}.
 *
 * <p>From the space-state incident of 2026-08-05, where RDF4J was answering reads with
 * {@code Read timed out} and each reader collapsed that onto its "legitimately absent"
 * value. What that demonstrably caused, and what these tests cover:
 * <ul>
 *   <li>{@code getCurrentLoadCounter()} returned {@code 0} from a failed read, so a graph
 *       was built and dropped under the bogus name {@code …_0} — visible in the log as
 *       {@code dropped old space-state graph …/c96bfc42…_0} while the counter was 2570;</li>
 *   <li>{@code getCurrentSpaceStateGraph()} returned {@code null} from a failed read, so
 *       {@code tick()} logged a trust-state flip that had not happened and rebuilt;</li>
 *   <li>{@code processedUpTo} was never stamped, so {@code runIncrementalCycle} logged
 *       "missing processedUpTo; skipping" every 2 s while {@code runFullBuild} returned
 *       "already current" — 25 minutes of that, ended only by an operator deleting the
 *       pointer by hand.</li>
 * </ul>
 *
 * <p>Deliberately <em>not</em> claimed here: that this code emptied the space state. The
 * shape it ended up with (sub-space rows only) was the correct reflection of a trust state
 * that had collapsed in the <em>registry</em> — see nanopub-registry#60. The empty-build
 * guard below would not have fired that day either, since that build inserted 2478
 * sub-space-prefix triples. It is cover for the total-read-failure case, which the same
 * outage came close to repeatedly.
 */
class AuthorityResolverBuildGuardTest {

    private static final String HASH = "934e5df81a7cf333cc4ca1080fd4f2f6a6ba2eaa07d91c58ca4a7af0276d723a";
    private static final long COUNTER = 2570L;

    /** The graph a build for (HASH, COUNTER) targets. */
    private static final IRI NEW_GRAPH = SpacesVocab.forSpaceState(HASH, COUNTER);
    /** A previously published, healthy graph under a different trust hash. */
    private static final IRI OLD_GRAPH = SpacesVocab.forSpaceState("c96bfc42db3bc5df584d992b57bc6932", COUNTER);

    @BeforeEach
    void resetSingletons() throws Exception {
        reset(AuthorityResolver.class, "instance");
        reset(TrustStateRegistry.class, "instance");
    }

    private static void reset(Class<?> cls, String fieldName) throws Exception {
        Field f = cls.getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(null, null);
    }

    private static TierInsertedTriples counts(int admin) {
        TierInsertedTriples c = new TierInsertedTriples();
        c.admin = admin;
        return c;
    }

    /**
     * Spy with every store-touching collaborator stubbed out, so the test exercises
     * only runFullBuild's control flow.
     */
    private static AuthorityResolver buildSpy(IRI oldGraph, int mirrored, TierInsertedTriples tierCounts) {
        AuthorityResolver ar = spy(AuthorityResolver.get());
        doReturn(COUNTER).when(ar).getCurrentLoadCounter();
        doReturn(oldGraph).when(ar).getCurrentSpaceStateGraph();
        doReturn(mirrored).when(ar).mirrorTrustState(anyString(), any(IRI.class));
        doReturn(tierCounts).when(ar).runAllTierLoops(any(IRI.class), anyLong());
        doReturn(new TierSubjectTotals(0L, 0L, 0L)).when(ar).computeTierSubjectTotals(any(IRI.class));
        doReturn(true).when(ar).trustStateHasContent(anyString());
        doNothing().when(ar).writeProcessedUpTo(any(IRI.class), anyLong());
        doNothing().when(ar).writeStateTripleCount(any(IRI.class));
        doNothing().when(ar).flipPointer(any(IRI.class));
        doNothing().when(ar).dropGraph(any(IRI.class));
        return ar;
    }

    @Test
    void emptyBuildIsNotPublishedWhenTheTrustStateStillHasContent() {
        // Total read failure: nothing came back, yet the trust state still holds rows to
        // mirror. The emptiness cannot be true, so it must not be published.
        AuthorityResolver ar = buildSpy(OLD_GRAPH, 0, counts(0));

        ar.runFullBuild(HASH);

        verify(ar, never()).flipPointer(any(IRI.class));
        verify(ar, never()).dropGraph(OLD_GRAPH);
        // The empty graph it just created is cleaned up instead.
        verify(ar).dropGraph(NEW_GRAPH);
    }

    @Test
    void emptyBuildIsPublishedWhenTheTrustStateIsGenuinelyEmpty() {
        // The other half of the guard. If the trust state really is empty, an empty space
        // state is the correct answer and withholding it would pin stale trust data in
        // place — over-permissive, and revocations would stop propagating.
        AuthorityResolver ar = buildSpy(OLD_GRAPH, 0, counts(0));
        doReturn(false).when(ar).trustStateHasContent(anyString());

        ar.runFullBuild(HASH);

        verify(ar).flipPointer(NEW_GRAPH);
        verify(ar).dropGraph(OLD_GRAPH);
    }

    @Test
    void emptyFirstBuildIsStillPublishedWhenThereIsNothingToLose() {
        // No previous state: an empty result may legitimately be the truth, and
        // refusing to publish would leave a fresh instance with no pointer at all.
        AuthorityResolver ar = buildSpy(null, 0, counts(0));

        ar.runFullBuild(HASH);

        verify(ar).flipPointer(NEW_GRAPH);
        verify(ar, never()).dropGraph(any(IRI.class));
    }

    @Test
    void nonEmptyBuildIsPublishedAndReplacesTheOldGraph() {
        AuthorityResolver ar = buildSpy(OLD_GRAPH, 768, counts(1839));

        ar.runFullBuild(HASH);

        verify(ar).writeProcessedUpTo(NEW_GRAPH, COUNTER);
        verify(ar).flipPointer(NEW_GRAPH);
        verify(ar).dropGraph(OLD_GRAPH);
    }

    @Test
    void alreadyCurrentWithHealthyStampDoesNotRebuild() {
        AuthorityResolver ar = buildSpy(NEW_GRAPH, 768, counts(1839));
        doReturn(COUNTER).when(ar).readProcessedUpTo(NEW_GRAPH);
        doReturn(-1L).when(ar).readStateTripleCount(NEW_GRAPH);

        ar.runFullBuild(HASH);

        verify(ar, never()).mirrorTrustState(anyString(), any(IRI.class));
        verify(ar, never()).dropGraph(any(IRI.class));
    }

    @Test
    void alreadyCurrentWithMissingStampRebuildsInPlaceAndKeepsWhatItBuilt() {
        // The self-heal path. Pointer name matches, so the old code returned "already
        // current" forever; the graph behind it was empty and unusable.
        AuthorityResolver ar = buildSpy(NEW_GRAPH, 768, counts(1839));
        doReturn(-1L).when(ar).readProcessedUpTo(NEW_GRAPH);
        doReturn(-1L).when(ar).readStateTripleCount(NEW_GRAPH);

        ar.runFullBuild(HASH);

        verify(ar).mirrorTrustState(HASH, NEW_GRAPH);
        verify(ar).flipPointer(NEW_GRAPH);
        // Cleared once before rebuilding — and NOT again at step 5, which would
        // delete the state we just built.
        verify(ar, times(1)).dropGraph(NEW_GRAPH);
    }

    @Test
    void pointerReadFailureThrowsRatherThanReportingNoPointer() {
        try (MockedStatic<TripleStore> store = mockStatic(TripleStore.class)) {
            TripleStore ts = mock(TripleStore.class);
            store.when(TripleStore::get).thenReturn(ts);
            when(ts.getRepoConnection(anyString()))
                    .thenThrow(new RuntimeException("Read timed out"));

            assertThrows(SpaceStateUnavailableException.class,
                    () -> AuthorityResolver.get().getCurrentSpaceStateGraph(),
                    "an unreadable pointer must not be reported as 'no pointer'");
            assertThrows(SpaceStateUnavailableException.class,
                    () -> AuthorityResolver.get().getCurrentLoadCounter(),
                    "an unreadable load counter must not be reported as 0");
            assertThrows(SpaceStateUnavailableException.class,
                    () -> AuthorityResolver.get().readProcessedUpTo(NEW_GRAPH),
                    "an unreadable processedUpTo must not be reported as -1");
        }
    }

    @Test
    void cleanOrphansBailsOutWhenThePointerCannotBeRead() {
        // With a null pointer every npass:* graph looks like an orphan, so proceeding
        // would clear the live one along with the leftovers.
        AuthorityResolver ar = spy(AuthorityResolver.get());
        doThrow(new SpaceStateUnavailableException("Read timed out", new RuntimeException()))
                .when(ar).getCurrentSpaceStateGraph();

        try (MockedStatic<TripleStore> store = mockStatic(TripleStore.class)) {
            ar.cleanOrphans();
            store.verify(TripleStore::get, never());
        }
    }

    @Test
    void tickRebuildsWhenTheCurrentGraphHasNoProcessedUpToStamp() {
        AuthorityResolver ar = spy(AuthorityResolver.get());
        doReturn(NEW_GRAPH).when(ar).getCurrentSpaceStateGraph();
        doReturn(-1L).when(ar).readProcessedUpTo(NEW_GRAPH);
        doNothing().when(ar).runFullBuild(anyString());

        try (MockedStatic<TrustStateRegistry> reg = mockStatic(TrustStateRegistry.class)) {
            TrustStateRegistry tsr = mock(TrustStateRegistry.class);
            reg.when(TrustStateRegistry::get).thenReturn(tsr);
            when(tsr.getCurrentHash()).thenReturn(Optional.of(HASH));

            ar.tick();

            verify(ar).runFullBuild(HASH);
            verify(ar, never()).runIncrementalCycle(any(IRI.class));
        }
    }

    @Test
    void tickRunsTheIncrementalCycleWhenTheStateIsHealthy() {
        AuthorityResolver ar = spy(AuthorityResolver.get());
        doReturn(NEW_GRAPH).when(ar).getCurrentSpaceStateGraph();
        doReturn(COUNTER).when(ar).readProcessedUpTo(NEW_GRAPH);
        doReturn(19283L).when(ar).readStateTripleCount(NEW_GRAPH);
        doReturn(19283L).when(ar).countStateGraphTriples(NEW_GRAPH);
        doNothing().when(ar).runIncrementalCycle(any(IRI.class));
        doNothing().when(ar).runFullBuild(anyString());

        try (MockedStatic<TrustStateRegistry> reg = mockStatic(TrustStateRegistry.class)) {
            TrustStateRegistry tsr = mock(TrustStateRegistry.class);
            reg.when(TrustStateRegistry::get).thenReturn(tsr);
            when(tsr.getCurrentHash()).thenReturn(Optional.of(HASH));

            ar.tick();

            verify(ar).runIncrementalCycle(NEW_GRAPH);
            verify(ar, never()).runFullBuild(eq(HASH));
        }
    }
}
