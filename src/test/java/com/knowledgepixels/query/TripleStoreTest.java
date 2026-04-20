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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
}