package com.knowledgepixels.query;

import org.apache.http.client.HttpClient;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.nanopub.MalformedNanopubException;
import org.nanopub.Nanopub;
import org.nanopub.NanopubImpl;
import org.nanopub.extra.server.GetNanopub;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NanopubLoaderTest {

    private static final String nanopubUri = "https://w3id.org/np/RA6T-YLqLnYd5XfnqR9PaGUjCzudvHdYjcG4GvOc7fdpA";

    @Test
    void loadWhenNanopubAlreadyLoaded() {
        try (MockedStatic<NanopubLoader> mockedLoader = mockStatic(NanopubLoader.class, CALLS_REAL_METHODS)) {
            mockedLoader.when(() -> NanopubLoader.isNanopubLoaded(nanopubUri)).thenReturn(true);

            ByteArrayOutputStream errContent = new ByteArrayOutputStream();
            PrintStream originalErr = System.err;
            System.setErr(new PrintStream(errContent));

            NanopubLoader.load(nanopubUri);

            System.setErr(originalErr);
            assertTrue(errContent.toString().contains(nanopubUri));
        }
    }

    @Test
    void loadWhenNanopubNotLoaded() throws MalformedNanopubException, IOException {
        try (MockedStatic<NanopubLoader> mockedLoader = mockStatic(NanopubLoader.class, CALLS_REAL_METHODS);
             MockedStatic<GetNanopub> mockedGetNanopub = mockStatic(GetNanopub.class)) {

            HttpClient httpClient = mock(HttpClient.class);
            Nanopub nanopub = new NanopubImpl(new File(Objects.requireNonNull(this.getClass().getResource("/grlc-query.trig")).getPath()));

            mockedLoader.when(() -> NanopubLoader.isNanopubLoaded(nanopubUri)).thenReturn(false);
            mockedLoader.when(NanopubLoader::getHttpClient).thenReturn(httpClient);
            mockedGetNanopub.when(() -> GetNanopub.get(nanopubUri, httpClient)).thenReturn(nanopub);

            NanopubLoader.load(nanopubUri);
            mockedLoader.verify(() -> NanopubLoader.load(nanopub, -1), times(1));
        }
    }

    @Test
    void isNanopubLoadedWithNullObjectForPattern() {
        try (MockedStatic<TripleStore> mockedStore = mockStatic(TripleStore.class);
             MockedStatic<Utils> mockedUtils = mockStatic(Utils.class)) {
            mockedStore.when(TripleStore::get).thenReturn(mock(TripleStore.class));
            mockedStore.when(() -> TripleStore.get().getRepoConnection("meta")).thenReturn(mock(RepositoryConnection.class));
            mockedUtils.when(() -> Utils.getObjectForPattern(any(), any(), any(), any())).thenReturn(null);

            assertFalse(NanopubLoader.isNanopubLoaded(nanopubUri));
        }
    }

    @Test
    void isNanopubLoadedWithNotNullObjectForPattern() {
        try (MockedStatic<TripleStore> mockedStore = mockStatic(TripleStore.class);
             MockedStatic<Utils> mockedUtils = mockStatic(Utils.class)) {
            mockedStore.when(TripleStore::get).thenReturn(mock(TripleStore.class));
            mockedStore.when(() -> TripleStore.get().getRepoConnection("meta")).thenReturn(mock(RepositoryConnection.class));
            mockedUtils.when(() -> Utils.getObjectForPattern(any(), any(), any(), any())).thenReturn(mock(Value.class));

            assertTrue(NanopubLoader.isNanopubLoaded(nanopubUri));
        }
    }

    // TODO mock network calls
    @Test
    void getHttpClientWhenNull() {
        assertNotNull(NanopubLoader.getHttpClient());
    }

    // TODO mock network calls
    @Test
    void getHttpClientWhenNotNull() {
        HttpClient httpClient = NanopubLoader.getHttpClient();
        assertSame(httpClient, NanopubLoader.getHttpClient());
    }

}