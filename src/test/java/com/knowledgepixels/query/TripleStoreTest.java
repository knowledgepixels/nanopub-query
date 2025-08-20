package com.knowledgepixels.query;

import org.apache.http.HttpEntity;
import org.apache.http.StatusLine;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.eclipse.rdf4j.repository.Repository;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.*;

class TripleStoreTest {

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
        CloseableHttpClient httpClientMock = mock(CloseableHttpClient.class);

        when(httpClientMock.execute(any(HttpUriRequest.class))).thenThrow(new IOException());

        try (var mockedStatic = mockStatic(HttpClients.class)) {
            mockedStatic.when(HttpClients::createDefault).thenReturn(httpClientMock);
            assertNull(mock.getRepositoryNames());
        }
    }

    @Test
    void getRepositoryNamesReturnsNullForNonValidResponse() throws IOException {
        TripleStore mock = mock(TripleStore.class, CALLS_REAL_METHODS);
        CloseableHttpClient httpClientMock = mock(CloseableHttpClient.class);
        CloseableHttpResponse responseMock = mock(CloseableHttpResponse.class);

        when(httpClientMock.execute(any(HttpUriRequest.class))).thenReturn(responseMock);
        when(responseMock.getEntity()).thenReturn(mock(HttpEntity.class));
        when(responseMock.getEntity().getContent()).thenReturn(mock(InputStream.class));
        when(responseMock.getStatusLine()).thenReturn(mock(StatusLine.class));
        when(responseMock.getStatusLine().getStatusCode()).thenReturn(500);

        try (var mockedStatic = mockStatic(HttpClients.class)) {
            mockedStatic.when(HttpClients::createDefault).thenReturn(httpClientMock);
            assertNull(mock.getRepositoryNames());
        }
    }

    @Test
    void getRepositoryNamesReturnsSetOfRepositoryNames() throws IOException {
        TripleStore mock = mock(TripleStore.class, CALLS_REAL_METHODS);
        CloseableHttpClient httpClientMock = mock(CloseableHttpClient.class);
        CloseableHttpResponse responseMock = mock(CloseableHttpResponse.class);

        when(httpClientMock.execute(any(HttpUriRequest.class))).thenReturn(responseMock);
        when(responseMock.getEntity()).thenReturn(mock(HttpEntity.class));
        String content = "id,name\n1,repo1\n2,repo2\n";
        when(responseMock.getEntity().getContent()).thenReturn(new ByteArrayInputStream(content.getBytes()));

        when(responseMock.getStatusLine()).thenReturn(mock(StatusLine.class));
        when(responseMock.getStatusLine().getStatusCode()).thenReturn(200);

        try (var mockedStatic = mockStatic(HttpClients.class)) {
            mockedStatic.when(HttpClients::createDefault).thenReturn(httpClientMock);
            Set<String> result = mock.getRepositoryNames();
            assertEquals(Set.of("repo1", "repo2"), result);
        }
    }

}