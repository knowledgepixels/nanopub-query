package com.knowledgepixels.query;

import com.knowledgepixels.query.JellyNanopubLoader.RegistryMetadata;
import org.eclipse.rdf4j.query.BooleanQuery;
import org.eclipse.rdf4j.query.QueryLanguage;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryException;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The loader's liveness signal ({@code Nanopub-Query-Loader-Last-Success-Age-Seconds}
 * and {@code registry.loader.last_successful_batch_age_seconds}) must only advance once
 * RDF4J has actually answered.
 *
 * <p>Regression cover for the kpxl outage of 2026-08-05: RDF4J was unreachable for
 * 11 hours while this instance reported READY with age 0, because an idle "caught up"
 * tick reads its counter from in-memory state and otherwise talks only to the registry.
 * A caught-up instance could never fail the check that exists to catch exactly this.
 */
class JellyNanopubLoaderLivenessTest {

    /**
     * Idle tick, registry caught up, so nothing but the probe would touch RDF4J.
     */
    private static final long COUNTER = 42L;

    private static RegistryMetadata caughtUpMetadata() {
        return new RegistryMetadata(COUNTER, null, null, null, null, null, null);
    }

    private static void resetLoaderState() {
        JellyNanopubLoader.lastSuccessfulBatchAtMs = 0L;
        JellyNanopubLoader.lastStoreProbeAtMs = 0L;
        JellyNanopubLoader.consecutiveBatchFailures = 0;
    }

    private static StatusController mockedStatusController(MockedStatic<StatusController> statics) {
        StatusController status = mock(StatusController.class);
        statics.when(StatusController::get).thenReturn(status);
        when(status.getState())
                .thenReturn(StatusController.LoadingStatus.of(StatusController.State.READY, COUNTER));
        return status;
    }

    @Test
    void idleTickDoesNotRecordLivenessWhenStoreIsUnreachable() {
        try (MockedStatic<JellyNanopubLoader> loader = mockStatic(JellyNanopubLoader.class, CALLS_REAL_METHODS);
             MockedStatic<StatusController> statusStatic = mockStatic(StatusController.class);
             MockedStatic<TripleStore> storeStatic = mockStatic(TripleStore.class)) {

            mockedStatusController(statusStatic);
            loader.when(JellyNanopubLoader::fetchRegistryMetadata).thenReturn(caughtUpMetadata());

            TripleStore store = mock(TripleStore.class);
            storeStatic.when(TripleStore::get).thenReturn(store);
            when(store.getAdminRepoConnection())
                    .thenThrow(new RepositoryException("Connect to rdf4j:8080 failed: Connect timed out"));

            resetLoaderState();
            JellyNanopubLoader.loadUpdates();

            assertEquals(0L, JellyNanopubLoader.lastSuccessfulBatchAtMs,
                    "an idle tick must not record liveness when RDF4J never answered");
            assertEquals(1, JellyNanopubLoader.consecutiveBatchFailures,
                    "an unreachable store must count as a failed tick, not a successful idle one");
        }
    }

    @Test
    void idleTickRecordsLivenessWhenStoreAnswers() {
        try (MockedStatic<JellyNanopubLoader> loader = mockStatic(JellyNanopubLoader.class, CALLS_REAL_METHODS);
             MockedStatic<StatusController> statusStatic = mockStatic(StatusController.class);
             MockedStatic<TripleStore> storeStatic = mockStatic(TripleStore.class)) {

            mockedStatusController(statusStatic);
            loader.when(JellyNanopubLoader::fetchRegistryMetadata).thenReturn(caughtUpMetadata());

            TripleStore store = mock(TripleStore.class);
            storeStatic.when(TripleStore::get).thenReturn(store);
            RepositoryConnection conn = mock(RepositoryConnection.class);
            BooleanQuery ask = mock(BooleanQuery.class);
            when(store.getAdminRepoConnection()).thenReturn(conn);
            when(conn.prepareBooleanQuery(any(QueryLanguage.class), eq("ASK {}"))).thenReturn(ask);
            when(ask.evaluate()).thenReturn(true);

            resetLoaderState();
            long before = System.currentTimeMillis();
            JellyNanopubLoader.loadUpdates();

            assertTrue(JellyNanopubLoader.lastSuccessfulBatchAtMs >= before,
                    "a verified round trip must record liveness");
            assertEquals(0, JellyNanopubLoader.consecutiveBatchFailures);
            verify(conn, atLeastOnce()).close();
        }
    }

    @Test
    void idleProbeIsThrottledAcrossConsecutiveTicks() {
        try (MockedStatic<JellyNanopubLoader> loader = mockStatic(JellyNanopubLoader.class, CALLS_REAL_METHODS);
             MockedStatic<StatusController> statusStatic = mockStatic(StatusController.class);
             MockedStatic<TripleStore> storeStatic = mockStatic(TripleStore.class)) {

            mockedStatusController(statusStatic);
            loader.when(JellyNanopubLoader::fetchRegistryMetadata).thenReturn(caughtUpMetadata());

            TripleStore store = mock(TripleStore.class);
            storeStatic.when(TripleStore::get).thenReturn(store);
            RepositoryConnection conn = mock(RepositoryConnection.class);
            BooleanQuery ask = mock(BooleanQuery.class);
            when(store.getAdminRepoConnection()).thenReturn(conn);
            when(conn.prepareBooleanQuery(any(QueryLanguage.class), eq("ASK {}"))).thenReturn(ask);
            when(ask.evaluate()).thenReturn(true);

            resetLoaderState();
            // The idle path runs every UPDATES_POLL_INTERVAL ms; probing on each would be
            // 30 needless round trips a minute. Only the first of a burst may probe.
            JellyNanopubLoader.loadUpdates();
            JellyNanopubLoader.loadUpdates();
            JellyNanopubLoader.loadUpdates();

            verify(store, times(1)).getAdminRepoConnection();
        }
    }
}
