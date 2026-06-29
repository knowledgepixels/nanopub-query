package com.knowledgepixels.query;

import org.apache.http.HttpEntity;
import org.apache.http.StatusLine;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.impl.client.CloseableHttpClient;
import org.eclipse.rdf4j.repository.Repository;
import org.junit.jupiter.api.Test;
import org.junit.platform.commons.support.HierarchyTraversalMode;
import org.junit.platform.commons.support.ReflectionSupport;

import com.google.common.hash.Hashing;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class TripleStoreTest {

    /**
     * Initializes the repoNamesCacheLock field in TripleStore mock.
     * Without this, all calls to getRepositoryNames() would result in a NullPointerException.
     */
    private ReentrantReadWriteLock initRepoNamesCacheLock(TripleStore mock) {
        final var readWriteLock = new ReentrantReadWriteLock();
        final var lockField = ReflectionSupport.findFields(
                TripleStore.class,
                f -> f.getName().equals("repoNamesCacheLock"),
                HierarchyTraversalMode.TOP_DOWN
        ).getFirst();
        lockField.setAccessible(true);
        try {
            lockField.set(mock, readWriteLock);
            return readWriteLock;
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Sets the shared {@code httpclient} field on a TripleStore mock. TripleStore now
     * uses one shared Apache HttpClient for all outbound RDF4J traffic (previously
     * getRepositoryNames built its own via {@code HttpClients.createDefault()}, which
     * the tests mocked via {@code mockStatic(HttpClients.class)}). Since mocks don't
     * run field initialisers, that field is null on a mock instance and must be
     * injected via reflection for each test.
     */
    private void injectHttpClient(TripleStore mock, CloseableHttpClient client) {
        final var field = ReflectionSupport.findFields(
                TripleStore.class,
                f -> f.getName().equals("httpclient"),
                HierarchyTraversalMode.TOP_DOWN
        ).getFirst();
        field.setAccessible(true);
        try {
            field.set(mock, client);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void getRepoConnectionWithValidRepo() {
        TripleStore mock = mock(TripleStore.class);
        Repository repository = mock(Repository.class);
        when(mock.getRepository("test")).thenReturn(repository);
        assertEquals(repository.getConnection(), mock.getRepoConnection("test"));
    }

    @Test
    void getRepoConnectionWithInvalidRepo() {
        TripleStore mock = mock(TripleStore.class);
        when(mock.getRepository("test")).thenReturn(null);
        assertNull(mock.getRepoConnection("test"));
    }

    @Test
    void getRepositoryNamesHandlesIOException() throws IOException {
        TripleStore mock = mock(TripleStore.class, CALLS_REAL_METHODS);
        ReentrantReadWriteLock repoNamesCacheLock = initRepoNamesCacheLock(mock);
        CloseableHttpClient httpClientMock = mock(CloseableHttpClient.class);
        injectHttpClient(mock, httpClientMock);

        when(httpClientMock.execute(any(HttpUriRequest.class))).thenThrow(new IOException());

        assertNull(mock.getRepositoryNames());
        assertEquals(0, repoNamesCacheLock.getReadLockCount());
        assertEquals(0, repoNamesCacheLock.getWriteHoldCount());
    }

    @Test
    void getRepositoryNamesReturnsNullForNonValidResponse() throws IOException {
        TripleStore mock = mock(TripleStore.class, CALLS_REAL_METHODS);
        ReentrantReadWriteLock repoNamesCacheLock = initRepoNamesCacheLock(mock);
        CloseableHttpClient httpClientMock = mock(CloseableHttpClient.class);
        injectHttpClient(mock, httpClientMock);
        CloseableHttpResponse responseMock = mock(CloseableHttpResponse.class);

        when(httpClientMock.execute(any(HttpUriRequest.class))).thenReturn(responseMock);
        when(responseMock.getEntity()).thenReturn(mock(HttpEntity.class));
        when(responseMock.getEntity().getContent()).thenReturn(mock(InputStream.class));
        when(responseMock.getStatusLine()).thenReturn(mock(StatusLine.class));
        when(responseMock.getStatusLine().getStatusCode()).thenReturn(500);

        assertNull(mock.getRepositoryNames());
        assertEquals(0, repoNamesCacheLock.getReadLockCount());
        assertEquals(0, repoNamesCacheLock.getWriteHoldCount());
    }

    @Test
    void getRepositoryNamesReturnsSetOfRepositoryNames() throws IOException {
        TripleStore mock = mock(TripleStore.class, CALLS_REAL_METHODS);
        ReentrantReadWriteLock repoNamesCacheLock = initRepoNamesCacheLock(mock);
        CloseableHttpClient httpClientMock = mock(CloseableHttpClient.class);
        injectHttpClient(mock, httpClientMock);
        CloseableHttpResponse responseMock = mock(CloseableHttpResponse.class);

        when(httpClientMock.execute(any(HttpUriRequest.class))).thenReturn(responseMock);
        when(responseMock.getEntity()).thenReturn(mock(HttpEntity.class));
        String content = "id,name\n1,repo1\n2,repo2\n";
        when(responseMock.getEntity().getContent()).thenReturn(new ByteArrayInputStream(content.getBytes()));

        when(responseMock.getStatusLine()).thenReturn(mock(StatusLine.class));
        when(responseMock.getStatusLine().getStatusCode()).thenReturn(200);

        Set<String> result = mock.getRepositoryNames();
        assertEquals(Set.of("repo1", "repo2"), result);
        assertEquals(0, repoNamesCacheLock.getReadLockCount());
        assertEquals(0, repoNamesCacheLock.getWriteHoldCount());
    }

    @Test
    void getRepositoryNamesCachesResult() throws IOException {
        TripleStore mock = mock(TripleStore.class, CALLS_REAL_METHODS);
        ReentrantReadWriteLock repoNamesCacheLock = initRepoNamesCacheLock(mock);
        CloseableHttpClient httpClientMock = mock(CloseableHttpClient.class);
        injectHttpClient(mock, httpClientMock);
        CloseableHttpResponse responseMock = mock(CloseableHttpResponse.class);

        when(httpClientMock.execute(any(HttpUriRequest.class))).thenReturn(responseMock);
        when(responseMock.getEntity()).thenReturn(mock(HttpEntity.class));
        String content = "id,name\n1,repo1\n2,repo2\n";
        when(responseMock.getEntity().getContent()).thenReturn(new ByteArrayInputStream(content.getBytes()));

        when(responseMock.getStatusLine()).thenReturn(mock(StatusLine.class));
        when(responseMock.getStatusLine().getStatusCode()).thenReturn(200);

        Set<String> firstCallResult = mock.getRepositoryNames();
        Set<String> secondCallResult = mock.getRepositoryNames();
        assertEquals(Set.of("repo1", "repo2"), firstCallResult);
        assertEquals(firstCallResult, secondCallResult);
        verify(httpClientMock, times(1)).execute(any(HttpUriRequest.class));
        assertEquals(0, repoNamesCacheLock.getReadLockCount());
        assertEquals(0, repoNamesCacheLock.getWriteHoldCount());
    }

    // --- repo-cache eviction: deferred idle-only shutdown + pinned hot repos ---

    private static final long GRACE_MS = 60_000;

    private void setField(TripleStore mock, String name, Object value) {
        final Field f = ReflectionSupport.findFields(
                TripleStore.class,
                fl -> fl.getName().equals(name),
                HierarchyTraversalMode.TOP_DOWN
        ).getFirst();
        f.setAccessible(true);
        try {
            f.set(mock, value);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * A {@link TripleStore} mock running the real cache methods, with the four cache
     * maps injected (mocks don't run field initialisers) and {@code nowMillis()} driven
     * by the supplied clock so the grace period is deterministic.
     */
    private TripleStore cacheMock(AtomicLong clock,
                                  Map<String, Repository> repositories,
                                  ConcurrentHashMap<String, AtomicInteger> openConnections,
                                  Map<String, Repository> pendingShutdown,
                                  Map<String, Long> pendingShutdownSince) {
        TripleStore mock = mock(TripleStore.class, CALLS_REAL_METHODS);
        setField(mock, "repositories", repositories);
        setField(mock, "openConnections", openConnections);
        setField(mock, "pendingShutdown", pendingShutdown);
        setField(mock, "pendingShutdownSince", pendingShutdownSince);
        doAnswer(inv -> clock.get()).when(mock).nowMillis();
        return mock;
    }

    /** Inserts {@code n} idle mock repos named {@code type_0..type_{n-1}} in order. */
    private List<Repository> fillIdle(Map<String, Repository> repositories, int n) {
        List<Repository> repos = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Repository r = mock(Repository.class);
            repos.add(r);
            repositories.put("type_" + i, r);
        }
        return repos;
    }

    @Test
    void evictionParksEldestWithoutShutdownThenReapsAfterGrace() {
        AtomicLong clock = new AtomicLong(1_000);
        Map<String, Repository> repositories = new LinkedHashMap<>();
        Map<String, Repository> pending = new LinkedHashMap<>();
        Map<String, Long> since = new HashMap<>();
        TripleStore store = cacheMock(clock, repositories, new ConcurrentHashMap<>(), pending, since);
        List<Repository> repos = fillIdle(repositories, 101);

        store.evictIdleRepos();

        // Cap enforced immediately, eldest parked but NOT shut down.
        assertEquals(100, repositories.size());
        assertFalse(repositories.containsKey("type_0"));
        assertTrue(pending.containsKey("type_0"));
        verify(repos.get(0), never()).shutDown();

        // Still within the grace window: not reaped.
        clock.set(1_000 + GRACE_MS - 1);
        store.reapPendingShutdowns();
        verify(repos.get(0), never()).shutDown();
        assertTrue(pending.containsKey("type_0"));

        // Grace elapsed: now shut down exactly once and unparked.
        clock.set(1_000 + GRACE_MS);
        store.reapPendingShutdowns();
        verify(repos.get(0), times(1)).shutDown();
        assertFalse(pending.containsKey("type_0"));
    }

    @Test
    void activeRepoIsNeverParked() {
        AtomicLong clock = new AtomicLong(1_000);
        Map<String, Repository> repositories = new LinkedHashMap<>();
        ConcurrentHashMap<String, AtomicInteger> open = new ConcurrentHashMap<>();
        Map<String, Repository> pending = new LinkedHashMap<>();
        TripleStore store = cacheMock(clock, repositories, open, pending, new HashMap<>());
        List<Repository> repos = fillIdle(repositories, 101);
        // Eldest has a live connection.
        open.put("type_0", new AtomicInteger(1));

        store.evictIdleRepos();

        // type_0 is skipped; the next idle one (type_1) is parked instead.
        assertTrue(repositories.containsKey("type_0"));
        assertFalse(pending.containsKey("type_0"));
        assertTrue(pending.containsKey("type_1"));
        verify(repos.get(0), never()).shutDown();
    }

    @Test
    void pinnedViewRepoIsNeverParked() {
        AtomicLong clock = new AtomicLong(1_000);
        Map<String, Repository> repositories = new LinkedHashMap<>();
        Map<String, Repository> pending = new LinkedHashMap<>();
        TripleStore store = cacheMock(clock, repositories, new ConcurrentHashMap<>(), pending, new HashMap<>());

        // The ResourceView type repo (the one that wedged) is pinned and sits eldest.
        String resourceView = "type_" + Hashing.sha256()
                .hashString("https://w3id.org/kpxl/gen/terms/ResourceView", StandardCharsets.UTF_8);
        Repository rvRepo = mock(Repository.class);
        repositories.put(resourceView, rvRepo);
        fillIdle(repositories, 100); // 101 total

        store.evictIdleRepos();

        assertTrue(repositories.containsKey(resourceView));
        assertFalse(pending.containsKey(resourceView));
        verify(rvRepo, never()).shutDown();
        // Exactly one non-pinned idle repo was parked to get back under cap.
        assertEquals(1, pending.size());
    }

    @Test
    void reapDoesNotShutDownAHandleThatBecameActiveAgain() {
        AtomicLong clock = new AtomicLong(1_000);
        Map<String, Repository> pending = new LinkedHashMap<>();
        Map<String, Long> since = new HashMap<>();
        ConcurrentHashMap<String, AtomicInteger> open = new ConcurrentHashMap<>();
        TripleStore store = cacheMock(clock, new LinkedHashMap<>(), open, pending, since);

        Repository parked = mock(Repository.class);
        pending.put("type_x", parked);
        since.put("type_x", 1_000L);
        // A connection was opened against it after parking.
        open.put("type_x", new AtomicInteger(1));

        clock.set(1_000 + GRACE_MS);
        store.reapPendingShutdowns();

        verify(parked, never()).shutDown();
        assertTrue(pending.containsKey("type_x"));
    }

    @Test
    void getRepositoryResurrectsParkedHandleWithoutShutdown() {
        AtomicLong clock = new AtomicLong(1_000);
        Map<String, Repository> repositories = new LinkedHashMap<>();
        Map<String, Repository> pending = new LinkedHashMap<>();
        Map<String, Long> since = new HashMap<>();
        TripleStore store = cacheMock(clock, repositories, new ConcurrentHashMap<>(), pending, since);

        Repository parked = mock(Repository.class);
        pending.put("type_x", parked);
        since.put("type_x", 1_000L);

        // Requested again before the grace period elapses.
        clock.set(1_000 + GRACE_MS / 2);
        Repository got = store.getRepository("type_x");

        assertSame(parked, got);
        assertTrue(repositories.containsKey("type_x"));
        assertFalse(pending.containsKey("type_x"));
        verify(parked, never()).shutDown();
    }
}