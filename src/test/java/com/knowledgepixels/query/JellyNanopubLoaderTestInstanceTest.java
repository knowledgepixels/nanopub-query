package com.knowledgepixels.query;

import com.knowledgepixels.query.JellyNanopubLoader.LoadingType;
import com.knowledgepixels.query.JellyNanopubLoader.RegistryMetadata;
import org.eclipse.rdf4j.query.BooleanQuery;
import org.eclipse.rdf4j.query.QueryLanguage;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.stubbing.Answer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Content of a registry that declares itself a test instance must not be ingested
 * (issue #25).
 *
 * <p>Nanopub Registry sets {@code Nanopub-Registry-Test-Instance} at DB initialization
 * and already refuses to sync from peers that report it; Query applies the same rule to
 * the registry it is attached to, so test nanopubs never reach a production index.
 * {@code NANOPUB_QUERY_ALLOW_TEST_REGISTRY=true} opts a deliberately-paired test Query
 * back in.
 */
class JellyNanopubLoaderTestInstanceTest {

    /** Counter this instance has already committed. */
    private static final long COUNTER = 42L;

    /** Registry counter, deliberately ahead so an un-gated poll would load a batch. */
    private static final long TARGET = 142L;

    private static RegistryMetadata metadata(String testInstance, Long setupId) {
        return new RegistryMetadata(TARGET, setupId, "all", "viaSetting", testInstance,
                "999", "trusthash");
    }

    @BeforeEach
    @AfterEach
    void resetLoaderState() {
        JellyNanopubLoader.lastSuccessfulBatchAtMs = 0L;
        JellyNanopubLoader.lastStoreProbeAtMs = 0L;
        JellyNanopubLoader.consecutiveBatchFailures = 0;
        JellyNanopubLoader.lastTestInstanceWarnAtMs = 0L;
        JellyNanopubLoader.setLastKnownSetupId(null);
    }

    private static StatusController mockedStatusController(MockedStatic<StatusController> statics) {
        StatusController status = mock(StatusController.class);
        statics.when(StatusController::get).thenReturn(status);
        when(status.getState())
                .thenReturn(StatusController.LoadingStatus.of(StatusController.State.READY, COUNTER));
        return status;
    }

    /**
     * Wires a reachable triple store so {@code probeStoreReachable} succeeds, i.e. so a
     * skipped poll is distinguishable from one that failed to reach RDF4J.
     */
    private static RepositoryConnection mockedStore(MockedStatic<TripleStore> statics) {
        TripleStore store = mock(TripleStore.class);
        statics.when(TripleStore::get).thenReturn(store);
        RepositoryConnection conn = mock(RepositoryConnection.class);
        BooleanQuery ask = mock(BooleanQuery.class);
        when(store.getAdminRepoConnection()).thenReturn(conn);
        when(conn.prepareBooleanQuery(any(QueryLanguage.class), eq("ASK {}"))).thenReturn(ask);
        when(ask.evaluate()).thenReturn(true);
        return conn;
    }

    /**
     * Static mock of the loader that answers {@code fetchRegistryMetadata} with the
     * given metadata, makes {@code loadBatch} a no-op, and runs everything else for
     * real.
     *
     * <p>Both are wired through the default answer rather than through
     * {@code when(...)}: stubbing a <em>void</em> static that way executes the real
     * method once while recording the invocation, which for {@code loadBatch} means a
     * live HTTP request to the configured registry — 10 s of connect timeout per test,
     * and a hard dependency on the network in CI.
     */
    private static MockedStatic<JellyNanopubLoader> loaderMock(RegistryMetadata metadata) {
        Answer<Object> answer = invocation -> switch (invocation.getMethod().getName()) {
            case "fetchRegistryMetadata" -> metadata;
            case "loadBatch" -> null;
            default -> invocation.callRealMethod();
        };
        return mockStatic(JellyNanopubLoader.class, answer);
    }

    private static void allowTestRegistry(MockedStatic<Utils> mockedUtils, String value) {
        mockedUtils.when(() -> Utils.getRawEnv("NANOPUB_QUERY_ALLOW_TEST_REGISTRY")).thenReturn(value);
    }

    @Test
    void metadataReadsTheTestInstanceHeader() {
        assertTrue(metadata("true", null).isTestInstance());
        assertTrue(metadata("TRUE", null).isTestInstance(), "the header value is case-insensitive");
        assertFalse(metadata("false", null).isTestInstance());
        assertFalse(metadata(null, null).isTestInstance(),
                "registries older than the header must not be read as test instances");
    }

    @Test
    void updatePollLoadsNothingFromATestRegistry() {
        try (MockedStatic<JellyNanopubLoader> loader = loaderMock(metadata("true", 1234L));
             MockedStatic<StatusController> statusStatic = mockStatic(StatusController.class);
             MockedStatic<TrustStateLoader> trustStatic = mockStatic(TrustStateLoader.class);
             MockedStatic<TripleStore> storeStatic = mockStatic(TripleStore.class)) {

            StatusController status = mockedStatusController(statusStatic);
            mockedStore(storeStatic);

            JellyNanopubLoader.loadUpdates();

            loader.verify(() -> JellyNanopubLoader.loadBatch(anyLong(), any(LoadingType.class)), never());
            trustStatic.verify(() -> TrustStateLoader.maybeUpdate(anyString()), never());
            verify(status, never()).setLoadingUpdates(anyLong());
        }
    }

    /**
     * The skip is a healthy tick, not a failed one: the circuit breaker must not creep
     * up, the instance must stay READY, and the liveness stamp must still be earned by
     * a real round trip to RDF4J — otherwise an instance attached to a test registry
     * would report perfect health straight through a store outage.
     */
    @Test
    void skippedPollStillCountsAsAHealthyTick() {
        try (MockedStatic<JellyNanopubLoader> loader = loaderMock(metadata("true", 1234L));
             MockedStatic<StatusController> statusStatic = mockStatic(StatusController.class);
             MockedStatic<TrustStateLoader> trustStatic = mockStatic(TrustStateLoader.class);
             MockedStatic<TripleStore> storeStatic = mockStatic(TripleStore.class)) {

            StatusController status = mockedStatusController(statusStatic);
            RepositoryConnection conn = mockedStore(storeStatic);

            long before = System.currentTimeMillis();
            JellyNanopubLoader.loadUpdates();

            assertEquals(0, JellyNanopubLoader.consecutiveBatchFailures);
            assertTrue(JellyNanopubLoader.lastSuccessfulBatchAtMs >= before,
                    "a verified round trip must record liveness even when nothing is ingested");
            verify(status, atLeastOnce()).setReady();
            verify(conn, atLeastOnce()).close();
        }
    }

    /**
     * The forwarded headers keep tracking the registry even while its content is
     * ignored, so an operator can see from outside <em>why</em> the instance is empty.
     */
    @Test
    void skippedPollStillForwardsRegistryMetadata() {
        String savedTestInstance = JellyNanopubLoader.lastTestInstance;
        String savedCount = JellyNanopubLoader.lastNanopubCount;
        try (MockedStatic<JellyNanopubLoader> loader = loaderMock(metadata("true", 1234L));
             MockedStatic<StatusController> statusStatic = mockStatic(StatusController.class);
             MockedStatic<TrustStateLoader> trustStatic = mockStatic(TrustStateLoader.class);
             MockedStatic<TripleStore> storeStatic = mockStatic(TripleStore.class)) {

            mockedStatusController(statusStatic);
            mockedStore(storeStatic);

            JellyNanopubLoader.loadUpdates();

            assertEquals("true", JellyNanopubLoader.lastTestInstance);
            assertEquals("999", JellyNanopubLoader.lastNanopubCount);
        } finally {
            JellyNanopubLoader.lastTestInstance = savedTestInstance;
            JellyNanopubLoader.lastNanopubCount = savedCount;
        }
    }

    /**
     * A test registry's setupId must not drive this instance's state either: a change
     * would otherwise wipe and re-stream the store from content we are refusing to load.
     */
    @Test
    void testRegistryResetDoesNotTriggerAResync() {
        try (MockedStatic<JellyNanopubLoader> loader = loaderMock(metadata("true", 999L));
             MockedStatic<StatusController> statusStatic = mockStatic(StatusController.class);
             MockedStatic<TrustStateLoader> trustStatic = mockStatic(TrustStateLoader.class);
             MockedStatic<TripleStore> storeStatic = mockStatic(TripleStore.class)) {

            StatusController status = mockedStatusController(statusStatic);
            mockedStore(storeStatic);
            JellyNanopubLoader.setLastKnownSetupId(1L);

            JellyNanopubLoader.loadUpdates();

            verify(status, never()).setResetting();
            verify(status, never()).setRegistrySetupId(anyLong());
            loader.verify(() -> JellyNanopubLoader.loadBatch(anyLong(), any(LoadingType.class)), never());
        }
    }

    @Test
    void initialLoadLoadsNothingFromATestRegistry() {
        try (MockedStatic<JellyNanopubLoader> loader = loaderMock(metadata("true", 1234L));
             MockedStatic<TrustStateLoader> trustStatic = mockStatic(TrustStateLoader.class);
             MockedStatic<StatusController> statusStatic = mockStatic(StatusController.class)) {

            mockedStatusController(statusStatic);

            JellyNanopubLoader.loadInitial(-1L);

            loader.verify(() -> JellyNanopubLoader.loadBatch(anyLong(), any(LoadingType.class)), never());
            trustStatic.verify(() -> TrustStateLoader.maybeUpdate(anyString()), never());
        }
    }

    /**
     * The opt-in override, for a Query instance deliberately paired with a test
     * registry.
     */
    @Test
    void allowTestRegistryFlagRestoresIngestion() {
        try (MockedStatic<JellyNanopubLoader> loader = loaderMock(metadata("true", null));
             MockedStatic<StatusController> statusStatic = mockStatic(StatusController.class);
             MockedStatic<TrustStateLoader> trustStatic = mockStatic(TrustStateLoader.class);
             MockedStatic<Utils> utilsStatic = mockStatic(Utils.class, CALLS_REAL_METHODS)) {

            mockedStatusController(statusStatic);
            allowTestRegistry(utilsStatic, "true");

            JellyNanopubLoader.loadUpdates();

            loader.verify(() -> JellyNanopubLoader.loadBatch(COUNTER, LoadingType.UPDATE));
            trustStatic.verify(() -> TrustStateLoader.maybeUpdate("trusthash"));
        }
    }

    /**
     * A registry that does not report itself as a test instance is unaffected — the
     * gate must not become a blanket stop on ingestion.
     */
    @Test
    void ordinaryRegistryIsUnaffected() {
        try (MockedStatic<JellyNanopubLoader> loader = loaderMock(metadata("false", null));
             MockedStatic<StatusController> statusStatic = mockStatic(StatusController.class);
             MockedStatic<TrustStateLoader> trustStatic = mockStatic(TrustStateLoader.class)) {

            mockedStatusController(statusStatic);

            JellyNanopubLoader.loadUpdates();

            loader.verify(() -> JellyNanopubLoader.loadBatch(COUNTER, LoadingType.UPDATE));
        }
    }

}
