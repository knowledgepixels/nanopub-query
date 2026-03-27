package com.knowledgepixels.query;

import io.vertx.core.Vertx;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.ext.web.Router;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(VertxExtension.class)
class HeadRequestTest {

    private HttpServer server;
    private int port;

    @BeforeEach
    void setUp(Vertx vertx, VertxTestContext ctx) {
        StatusController.get().resetForTest();

        Router router = Router.router(vertx);

        // Register a GET route (like the real server has)
        router.route(HttpMethod.GET, "/").handler(req -> {
            req.response().putHeader("content-type", "text/html").end("<html>OK</html>");
        });

        // Register the HEAD catch-all (same as in MainVerticle)
        router.route(HttpMethod.HEAD, "/*").handler(req -> {
            req.response().setStatusCode(200).end();
        });

        server = vertx.createHttpServer();
        server.requestHandler(req -> {
            MainVerticle.applyGlobalHeaders(req.response());
            router.handle(req);
        });
        server.listen(0).onComplete(ctx.succeeding(s -> {
            port = s.actualPort();
            ctx.completeNow();
        }));
    }

    @AfterEach
    void tearDown(VertxTestContext ctx) {
        if (server != null) {
            server.close().onComplete(ctx.succeeding(v -> ctx.completeNow()));
        } else {
            ctx.completeNow();
        }
    }

    private void initializeStatusController(MockedStatic<TripleStore> mockedTripleStore) {
        mockedTripleStore.when(TripleStore::get).thenReturn(mock(TripleStore.class));
        when(TripleStore.get().getAdminRepoConnection()).thenReturn(mock(RepositoryConnection.class));
        when(TripleStore.get().getAdminRepoConnection().getValueFactory()).thenReturn(SimpleValueFactory.getInstance());
        when(TripleStore.get().getAdminRepoConnection().getStatements(any(), any(), any(), any())).thenReturn(mock(RepositoryResult.class));
        StatusController.get().initialize();
    }

    @Test
    void headRequestReturns200() throws Exception {
        try (MockedStatic<TripleStore> mockedTripleStore = mockStatic(TripleStore.class)) {
            initializeStatusController(mockedTripleStore);
            StatusController.get().updateState(StatusController.State.READY, 42);

            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + port + "/"))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode());
        }
    }

    @Test
    void headRequestReturnsGlobalHeaders() throws Exception {
        try (MockedStatic<TripleStore> mockedTripleStore = mockStatic(TripleStore.class)) {
            initializeStatusController(mockedTripleStore);
            StatusController.get().updateState(StatusController.State.READY, 42);
            StatusController.get().setRegistrySetupId(999L);

            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + port + "/"))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            assertEquals("READY", response.headers().firstValue("Nanopub-Query-Status").orElse(null));
            assertEquals(JellyNanopubLoader.registryUrl, response.headers().firstValue("Nanopub-Query-Registry-Url").orElse(null));
            assertEquals("999", response.headers().firstValue("Nanopub-Query-Registry-Setup-Id").orElse(null));
            assertEquals("42", response.headers().firstValue("Nanopub-Query-Load-Counter").orElse(null));
        }
    }

    @Test
    void headRequestReturnsEmptyBody() throws Exception {
        try (MockedStatic<TripleStore> mockedTripleStore = mockStatic(TripleStore.class)) {
            initializeStatusController(mockedTripleStore);

            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + port + "/"))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            assertEquals("", response.body());
        }
    }

    @Test
    void headRequestWorksOnArbitraryPath() throws Exception {
        try (MockedStatic<TripleStore> mockedTripleStore = mockStatic(TripleStore.class)) {
            initializeStatusController(mockedTripleStore);
            StatusController.get().updateState(StatusController.State.LOADING_INITIAL, 100);

            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + port + "/some/arbitrary/path"))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode());
            assertEquals("LOADING_INITIAL", response.headers().firstValue("Nanopub-Query-Status").orElse(null));
            assertEquals("100", response.headers().firstValue("Nanopub-Query-Load-Counter").orElse(null));
        }
    }

    @Test
    void getRequestStillWorks() throws Exception {
        try (MockedStatic<TripleStore> mockedTripleStore = mockStatic(TripleStore.class)) {
            initializeStatusController(mockedTripleStore);
            StatusController.get().updateState(StatusController.State.READY, 10);

            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + port + "/"))
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("<html>OK</html>"));
            assertEquals("READY", response.headers().firstValue("Nanopub-Query-Status").orElse(null));
        }
    }
}
