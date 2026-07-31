package com.knowledgepixels.query;

import io.vertx.core.http.HttpServerResponse;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.mockito.Mockito.*;

class MainVerticleGlobalHeadersTest {

    @BeforeEach
    void setUp() {
        StatusController.get().resetForTest();
    }

    private void initializeStatusController(MockedStatic<TripleStore> mockedTripleStore) {
        mockedTripleStore.when(TripleStore::get).thenReturn(mock(TripleStore.class));
        when(TripleStore.get().getAdminRepoConnection()).thenReturn(mock(RepositoryConnection.class));
        when(TripleStore.get().getAdminRepoConnection().getValueFactory()).thenReturn(SimpleValueFactory.getInstance());
        when(TripleStore.get().getAdminRepoConnection().getStatements(any(), any(), any(), any())).thenReturn(mock(RepositoryResult.class));
        StatusController.get().initialize();
    }

    @Test
    void setsStatusHeader() {
        try (MockedStatic<TripleStore> mockedTripleStore = mockStatic(TripleStore.class)) {
            initializeStatusController(mockedTripleStore);
            StatusController.get().updateState(StatusController.State.READY, 42);

            HttpServerResponse response = mock(HttpServerResponse.class);
            when(response.putHeader(anyString(), anyString())).thenReturn(response);

            MainVerticle.applyGlobalHeaders(response);

            verify(response).putHeader("Nanopub-Query-Status", "READY");
        }
    }

    @Test
    void setsLoaderAgeHeaderOnceTheLoaderHasTicked() {
        try (MockedStatic<TripleStore> mockedTripleStore = mockStatic(TripleStore.class)) {
            initializeStatusController(mockedTripleStore);
            long saved = JellyNanopubLoader.lastSuccessfulBatchAtMs;
            try {
                JellyNanopubLoader.lastSuccessfulBatchAtMs = System.currentTimeMillis() - 7_440_000L;

                HttpServerResponse response = mock(HttpServerResponse.class);
                when(response.putHeader(anyString(), anyString())).thenReturn(response);

                MainVerticle.applyGlobalHeaders(response);

                // 7440 s — the age the loader had reached when the 2026-07-31 stall was noticed.
                verify(response).putHeader("Nanopub-Query-Loader-Last-Success-Age-Seconds", "7440");
            } finally {
                JellyNanopubLoader.lastSuccessfulBatchAtMs = saved;
            }
        }
    }

    @Test
    void omitsLoaderAgeHeaderBeforeTheFirstTick() {
        // Sending 0 here would be indistinguishable from "just ticked", which is the one
        // reading a consumer must not confuse with a freshly started instance.
        try (MockedStatic<TripleStore> mockedTripleStore = mockStatic(TripleStore.class)) {
            initializeStatusController(mockedTripleStore);
            long saved = JellyNanopubLoader.lastSuccessfulBatchAtMs;
            try {
                JellyNanopubLoader.lastSuccessfulBatchAtMs = 0L;

                HttpServerResponse response = mock(HttpServerResponse.class);
                when(response.putHeader(anyString(), anyString())).thenReturn(response);

                MainVerticle.applyGlobalHeaders(response);

                verify(response, never()).putHeader(eq("Nanopub-Query-Loader-Last-Success-Age-Seconds"), anyString());
            } finally {
                JellyNanopubLoader.lastSuccessfulBatchAtMs = saved;
            }
        }
    }

    @Test
    void setsRegistryUrlHeader() {
        try (MockedStatic<TripleStore> mockedTripleStore = mockStatic(TripleStore.class)) {
            initializeStatusController(mockedTripleStore);

            HttpServerResponse response = mock(HttpServerResponse.class);
            when(response.putHeader(anyString(), anyString())).thenReturn(response);

            MainVerticle.applyGlobalHeaders(response);

            verify(response).putHeader("Nanopub-Query-Registry-Url", JellyNanopubLoader.registryUrl);
        }
    }

    @Test
    void setsLoadCounterHeader() {
        try (MockedStatic<TripleStore> mockedTripleStore = mockStatic(TripleStore.class)) {
            initializeStatusController(mockedTripleStore);
            StatusController.get().updateState(StatusController.State.LOADING_UPDATES, 1500);

            HttpServerResponse response = mock(HttpServerResponse.class);
            when(response.putHeader(anyString(), anyString())).thenReturn(response);

            MainVerticle.applyGlobalHeaders(response);

            verify(response).putHeader("Nanopub-Query-Load-Counter", "1500");
        }
    }

    @Test
    void setsSetupIdHeaderWhenPresent() {
        try (MockedStatic<TripleStore> mockedTripleStore = mockStatic(TripleStore.class)) {
            initializeStatusController(mockedTripleStore);
            StatusController.get().setRegistrySetupId(12345L);

            HttpServerResponse response = mock(HttpServerResponse.class);
            when(response.putHeader(anyString(), anyString())).thenReturn(response);

            MainVerticle.applyGlobalHeaders(response);

            verify(response).putHeader("Nanopub-Query-Registry-Setup-Id", "12345");
        }
    }

    @Test
    void setsSetupIdHeaderEmptyWhenNull() {
        try (MockedStatic<TripleStore> mockedTripleStore = mockStatic(TripleStore.class)) {
            initializeStatusController(mockedTripleStore);
            // Don't set a setup ID — it should remain null

            HttpServerResponse response = mock(HttpServerResponse.class);
            when(response.putHeader(anyString(), anyString())).thenReturn(response);

            MainVerticle.applyGlobalHeaders(response);

            verify(response).putHeader("Nanopub-Query-Registry-Setup-Id", "");
        }
    }

    @Test
    void setsAllHeadersTogether() {
        try (MockedStatic<TripleStore> mockedTripleStore = mockStatic(TripleStore.class)) {
            initializeStatusController(mockedTripleStore);
            StatusController.get().updateState(StatusController.State.LOADING_INITIAL, 500);
            StatusController.get().setRegistrySetupId(99L);

            HttpServerResponse response = mock(HttpServerResponse.class);
            when(response.putHeader(anyString(), anyString())).thenReturn(response);

            MainVerticle.applyGlobalHeaders(response);

            verify(response).putHeader("Nanopub-Query-Status", "LOADING_INITIAL");
            verify(response).putHeader("Nanopub-Query-Registry-Url", JellyNanopubLoader.registryUrl);
            verify(response).putHeader("Nanopub-Query-Registry-Setup-Id", "99");
            verify(response).putHeader("Nanopub-Query-Load-Counter", "500");
        }
    }

    @Test
    void forwardsCoverageTypesWhenPresent() {
        try (MockedStatic<TripleStore> mockedTripleStore = mockStatic(TripleStore.class)) {
            initializeStatusController(mockedTripleStore);
            JellyNanopubLoader.lastCoverageTypes = "hash1,hash2";

            HttpServerResponse response = mock(HttpServerResponse.class);
            when(response.putHeader(anyString(), anyString())).thenReturn(response);

            MainVerticle.applyGlobalHeaders(response);

            verify(response).putHeader("Nanopub-Query-Registry-Coverage-Types", "hash1,hash2");
            JellyNanopubLoader.lastCoverageTypes = null;
        }
    }

    @Test
    void defaultsCoverageTypesToAllWhenNull() {
        try (MockedStatic<TripleStore> mockedTripleStore = mockStatic(TripleStore.class)) {
            initializeStatusController(mockedTripleStore);
            JellyNanopubLoader.lastCoverageTypes = null;

            HttpServerResponse response = mock(HttpServerResponse.class);
            when(response.putHeader(anyString(), anyString())).thenReturn(response);

            MainVerticle.applyGlobalHeaders(response);

            verify(response).putHeader("Nanopub-Query-Registry-Coverage-Types", "all");
        }
    }

    @Test
    void forwardsCoverageAgentsWhenPresent() {
        try (MockedStatic<TripleStore> mockedTripleStore = mockStatic(TripleStore.class)) {
            initializeStatusController(mockedTripleStore);
            JellyNanopubLoader.lastCoverageAgents = "viaSetting abc123:5000";

            HttpServerResponse response = mock(HttpServerResponse.class);
            when(response.putHeader(anyString(), anyString())).thenReturn(response);

            MainVerticle.applyGlobalHeaders(response);

            verify(response).putHeader("Nanopub-Query-Registry-Coverage-Agents", "viaSetting abc123:5000");
            JellyNanopubLoader.lastCoverageAgents = null;
        }
    }

    @Test
    void defaultsCoverageAgentsToViaSettingWhenNull() {
        try (MockedStatic<TripleStore> mockedTripleStore = mockStatic(TripleStore.class)) {
            initializeStatusController(mockedTripleStore);
            JellyNanopubLoader.lastCoverageAgents = null;

            HttpServerResponse response = mock(HttpServerResponse.class);
            when(response.putHeader(anyString(), anyString())).thenReturn(response);

            MainVerticle.applyGlobalHeaders(response);

            verify(response).putHeader("Nanopub-Query-Registry-Coverage-Agents", "viaSetting");
        }
    }

    @Test
    void forwardsTestInstanceWhenPresent() {
        try (MockedStatic<TripleStore> mockedTripleStore = mockStatic(TripleStore.class)) {
            initializeStatusController(mockedTripleStore);
            JellyNanopubLoader.lastTestInstance = "true";

            HttpServerResponse response = mock(HttpServerResponse.class);
            when(response.putHeader(anyString(), anyString())).thenReturn(response);

            MainVerticle.applyGlobalHeaders(response);

            verify(response).putHeader("Nanopub-Query-Registry-Test-Instance", "true");
            JellyNanopubLoader.lastTestInstance = null;
        }
    }

    @Test
    void forwardsNanopubCountWhenPresent() {
        try (MockedStatic<TripleStore> mockedTripleStore = mockStatic(TripleStore.class)) {
            initializeStatusController(mockedTripleStore);
            JellyNanopubLoader.lastNanopubCount = "50000";

            HttpServerResponse response = mock(HttpServerResponse.class);
            when(response.putHeader(anyString(), anyString())).thenReturn(response);

            MainVerticle.applyGlobalHeaders(response);

            verify(response).putHeader("Nanopub-Query-Registry-Nanopub-Count", "50000");
            JellyNanopubLoader.lastNanopubCount = null;
        }
    }

    @Test
    void emitsLoadedNanopubCountWhenPresent() {
        try (MockedStatic<TripleStore> mockedTripleStore = mockStatic(TripleStore.class)) {
            initializeStatusController(mockedTripleStore);
            NanopubLoader.loadedNanopubCount = 49998L;

            HttpServerResponse response = mock(HttpServerResponse.class);
            when(response.putHeader(anyString(), anyString())).thenReturn(response);

            MainVerticle.applyGlobalHeaders(response);

            verify(response).putHeader("Nanopub-Query-Loaded-Nanopub-Count", "49998");
            NanopubLoader.loadedNanopubCount = null;
        }
    }

    @Test
    void emitsLoadedNanopubChecksumWhenPresent() {
        try (MockedStatic<TripleStore> mockedTripleStore = mockStatic(TripleStore.class)) {
            initializeStatusController(mockedTripleStore);
            NanopubLoader.loadedNanopubChecksum = "JfE2EFk0EFgTXmY6B-ftuZFl0S-WELl3yMiaRuRpIME";

            HttpServerResponse response = mock(HttpServerResponse.class);
            when(response.putHeader(anyString(), anyString())).thenReturn(response);

            MainVerticle.applyGlobalHeaders(response);

            verify(response).putHeader("Nanopub-Query-Loaded-Nanopub-Checksum", "JfE2EFk0EFgTXmY6B-ftuZFl0S-WELl3yMiaRuRpIME");
            NanopubLoader.loadedNanopubChecksum = null;
        }
    }
}
