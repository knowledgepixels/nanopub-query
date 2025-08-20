package com.knowledgepixels.query;

import org.apache.http.client.HttpClient;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.nanopub.MalformedNanopubException;
import org.nanopub.Nanopub;
import org.nanopub.NanopubImpl;
import org.nanopub.extra.security.NanopubSignatureElement;
import org.nanopub.extra.security.SignatureUtils;
import org.nanopub.extra.server.GetNanopub;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.security.GeneralSecurityException;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NanopubLoaderTest {

    private static final String nanopubUri = "https://w3id.org/np/RA6T-YLqLnYd5XfnqR9PaGUjCzudvHdYjcG4GvOc7fdpA";

    @Test
    void hasValidSignaturePrintsException() {
        try (MockedStatic<SignatureUtils> mockedStatic = mockStatic(SignatureUtils.class)) {
            mockedStatic.when(() -> SignatureUtils.hasValidSignature(any(NanopubSignatureElement.class))).thenThrow(GeneralSecurityException.class);
            assertFalse(NanopubLoader.hasValidSignature(mock(NanopubSignatureElement.class)));
        }
    }

    @Test
    void constructWithValidSignature() throws MalformedNanopubException, IOException {
        try (MockedStatic<NanopubLoader> mockedLoader = mockStatic(NanopubLoader.class, CALLS_REAL_METHODS);
             MockedStatic<GetNanopub> mockedGetNanopub = mockStatic(GetNanopub.class);
             MockedStatic<Utils> mockedUtils = mockStatic(Utils.class, CALLS_REAL_METHODS)) {

            Map<String, Value> hashToObjectMap = mock(Map.class);
            mockedUtils.when(Utils::getHashToObjectMap).thenReturn(hashToObjectMap);
            when(hashToObjectMap.containsKey(anyString())).thenReturn(true);

            Nanopub nanopub = new NanopubImpl(new File(Objects.requireNonNull(this.getClass().getResource("/testsuite/valid/signed/RA6T-YLqLnYd5XfnqR9PaGUjCzudvHdYjcG4GvOc7fdpA.trig")).getPath()));

            mockedLoader.when(() -> NanopubLoader.isNanopubLoaded(anyString())).thenReturn(false);
            mockedLoader.when(NanopubLoader::getHttpClient).thenReturn(mock(HttpClient.class));
            mockedLoader.when(() -> NanopubLoader.getInvalidatingStatements(any())).thenReturn(List.of());

            mockedGetNanopub.when(() -> GetNanopub.get(anyString(), any(HttpClient.class))).thenReturn(nanopub);

            NanopubLoader nanopubLoader = new NanopubLoader(nanopub, -1);
            assertNotNull(nanopubLoader);
        }
    }

    @Test
    void constructWithIntroNanopub() throws MalformedNanopubException, IOException {
        try (MockedStatic<NanopubLoader> mockedLoader = mockStatic(NanopubLoader.class, CALLS_REAL_METHODS);
             MockedStatic<GetNanopub> mockedGetNanopub = mockStatic(GetNanopub.class);
             MockedStatic<Utils> mockedUtils = mockStatic(Utils.class, CALLS_REAL_METHODS)) {

            Map<String, Value> hashToObjectMap = mock(Map.class);
            mockedUtils.when(Utils::getHashToObjectMap).thenReturn(hashToObjectMap);
            when(hashToObjectMap.containsKey(anyString())).thenReturn(true);

            Nanopub nanopub = new NanopubImpl(new File(Objects.requireNonNull(this.getClass().getResource("/testsuite/valid/signed/RATq2i1SMq-Ci6-1MAFALTELRRSL7xAsI4iQOC3cgMldE.trig")).getPath()));

            mockedLoader.when(() -> NanopubLoader.isNanopubLoaded(anyString())).thenReturn(false);
            mockedLoader.when(NanopubLoader::getHttpClient).thenReturn(mock(HttpClient.class));
            mockedLoader.when(() -> NanopubLoader.getInvalidatingStatements(any())).thenReturn(List.of());

            mockedGetNanopub.when(() -> GetNanopub.get(anyString(), any(HttpClient.class))).thenReturn(nanopub);

            NanopubLoader nanopubLoader = new NanopubLoader(nanopub, 1);
            assertNotNull(nanopubLoader);
        }
    }

    @Test
    void loadWhenNanopubAlreadyLoaded() {
        try (MockedStatic<NanopubLoader> mockedLoader = mockStatic(NanopubLoader.class, CALLS_REAL_METHODS)) {
            mockedLoader.when(() -> NanopubLoader.isNanopubLoaded(nanopubUri)).thenReturn(true);

            ByteArrayOutputStream errContent = new ByteArrayOutputStream();
            PrintStream originalErr = System.err;
            System.setErr(new PrintStream(errContent));

            NanopubLoader.load(nanopubUri);

            System.setErr(originalErr);
            assertEquals("Already loaded: " + nanopubUri + "\n", errContent.toString());
        }
    }

    @Test
    void loadWhenNanopubNotLoadedInvalidSignature() throws MalformedNanopubException, IOException {
        try (MockedStatic<NanopubLoader> mockedLoader = mockStatic(NanopubLoader.class, CALLS_REAL_METHODS);
             MockedStatic<GetNanopub> mockedGetNanopub = mockStatic(GetNanopub.class)) {

            Nanopub nanopub = new NanopubImpl(new File(Objects.requireNonNull(this.getClass().getResource("/testsuite/invalid/signed/simple1-invalid-rsa.trig")).getPath()));

            mockedLoader.when(() -> NanopubLoader.isNanopubLoaded(anyString())).thenReturn(false);
            mockedLoader.when(NanopubLoader::getHttpClient).thenReturn(mock(HttpClient.class));
            mockedGetNanopub.when(() -> GetNanopub.get(anyString(), any(HttpClient.class))).thenReturn(nanopub);

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