package com.knowledgepixels.query;

import org.apache.http.client.HttpClient;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.PROV;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.model.vocabulary.RDFS;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.nanopub.MalformedNanopubException;
import org.nanopub.Nanopub;
import org.nanopub.NanopubCreator;
import org.nanopub.NanopubImpl;
import org.nanopub.extra.security.NanopubSignatureElement;
import org.nanopub.extra.security.SignatureUtils;
import org.nanopub.extra.server.GetNanopub;
import org.nanopub.testsuite.NanopubTestSuite;
import org.nanopub.vocabulary.NPX;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.List;
import java.util.Map;

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

            Nanopub nanopub = new NanopubImpl(NanopubTestSuite.getLatest().getByArtifactCode("RA6T-YLqLnYd5XfnqR9PaGUjCzudvHdYjcG4GvOc7fdpA").getFirst().toFile());

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

            Nanopub nanopub = new NanopubImpl(NanopubTestSuite.getLatest().getByArtifactCode("RATq2i1SMq-Ci6-1MAFALTELRRSL7xAsI4iQOC3cgMldE").getFirst().toFile());

            mockedLoader.when(() -> NanopubLoader.isNanopubLoaded(anyString())).thenReturn(false);
            mockedLoader.when(NanopubLoader::getHttpClient).thenReturn(mock(HttpClient.class));
            mockedLoader.when(() -> NanopubLoader.getInvalidatingStatements(any())).thenReturn(List.of());

            mockedGetNanopub.when(() -> GetNanopub.get(anyString(), any(HttpClient.class))).thenReturn(nanopub);

            NanopubLoader nanopubLoader = new NanopubLoader(nanopub, 1);
            assertNotNull(nanopubLoader);
        }
    }

    @Test
    void loadWhenNanopubNotLoadedInvalidSignature() throws MalformedNanopubException, IOException {
        try (MockedStatic<NanopubLoader> mockedLoader = mockStatic(NanopubLoader.class, CALLS_REAL_METHODS);
             MockedStatic<GetNanopub> mockedGetNanopub = mockStatic(GetNanopub.class);
             MockedStatic<TripleStore> mockedStore = mockStatic(TripleStore.class)) {

            Nanopub nanopub = new NanopubImpl(NanopubTestSuite.getLatest().getByNanopubUri("http://example.org/nanopub-validator-example/RAeUPiCKlke8Pw9wYbqIESyBqFJM5UDSkx4uF9kkRfCh0").getFirst().toFile());

            mockedLoader.when(() -> NanopubLoader.isNanopubLoaded(anyString())).thenReturn(false);
            mockedLoader.when(NanopubLoader::getHttpClient).thenReturn(mock(HttpClient.class));
            mockedGetNanopub.when(() -> GetNanopub.get(anyString(), any(HttpClient.class))).thenReturn(nanopub);
            // The invalid-signature path now writes an "Invalid signature" audit note
            // to the admin repo so silent aborts aren't invisible. Mock the TripleStore
            // so the test doesn't try to hit a real RDF4J endpoint.
            mockedStore.when(TripleStore::get).thenReturn(mock(TripleStore.class));
            when(TripleStore.get().getAdminRepoConnection()).thenReturn(mock(RepositoryConnection.class));

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

    /**
     * Builds a real (unsigned) trusty nanopub, optionally typed as
     * npx:ProtectedNanopub in the pubinfo, or carrying that type triple in the
     * assertion instead (which must NOT count as protected).
     */
    private static Nanopub createTrustyNanopub(boolean protectedInPubinfo, boolean protectedTypeInAssertion) throws Exception {
        ValueFactory vf = SimpleValueFactory.getInstance();
        String baseUri = "http://example.org/protected-test";
        NanopubCreator creator = new NanopubCreator(baseUri);
        IRI thing = vf.createIRI(baseUri + "#thing");
        creator.addAssertionStatement(thing, RDFS.LABEL, vf.createLiteral("something"));
        if (protectedTypeInAssertion) {
            creator.addAssertionStatement(vf.createIRI(baseUri), RDF.TYPE, NPX.PROTECTED_NANOPUB);
        }
        creator.addProvenanceStatement(PROV.WAS_ATTRIBUTED_TO, vf.createIRI("http://example.org/someone"));
        creator.addTimestampNow();
        if (protectedInPubinfo) {
            creator.addPubinfoStatement(RDF.TYPE, NPX.PROTECTED_NANOPUB);
        }
        return creator.finalizeTrustyNanopub();
    }

    @Test
    void constructAbortsOnProtectedNanopubByDefault() throws Exception {
        NanopubLoader loader = new NanopubLoader(createTrustyNanopub(true, false), -1);
        assertTrue(loader.isAborted());
        assertTrue(loader.getNotes().stream().anyMatch(n -> n.contains("protected")));
        // Rejected before the signature check — no "Invalid signature" note.
        assertFalse(loader.getNotes().contains("Invalid signature"));
    }

    @Test
    void constructSkipsProtectedCheckOnLocalInstance() throws Exception {
        try (MockedStatic<Utils> mockedUtils = mockStatic(Utils.class, CALLS_REAL_METHODS)) {
            mockedUtils.when(() -> Utils.getRawEnv("NANOPUB_QUERY_LOCAL_INSTANCE")).thenReturn("true");
            NanopubLoader loader = new NanopubLoader(createTrustyNanopub(true, false), -1);
            // The unsigned test nanopub still aborts, but on the signature check:
            // the protected check no longer fires on a local instance.
            assertTrue(loader.isAborted());
            assertTrue(loader.getNotes().contains("Invalid signature"));
            assertFalse(loader.getNotes().stream().anyMatch(n -> n.contains("protected")));
        }
    }

    @Test
    void constructIgnoresProtectedTypeOutsidePubinfo() throws Exception {
        NanopubLoader loader = new NanopubLoader(createTrustyNanopub(false, true), -1);
        // Only the pubinfo type triple on the nanopub itself marks it protected.
        assertTrue(loader.getNotes().contains("Invalid signature"));
        assertFalse(loader.getNotes().stream().anyMatch(n -> n.contains("protected")));
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