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
}
