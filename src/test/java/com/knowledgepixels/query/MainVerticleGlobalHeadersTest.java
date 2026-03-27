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
}
