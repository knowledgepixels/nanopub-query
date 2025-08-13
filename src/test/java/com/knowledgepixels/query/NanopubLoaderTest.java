package com.knowledgepixels.query;

import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class NanopubLoaderTest {

    private static final String nanopubUri = "https://w3id.org/np/RA6T-YLqLnYd5XfnqR9PaGUjCzudvHdYjcG4GvOc7fdpA";

    @Test
    void testLoadNanopubAlreadyLoaded() {
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

}