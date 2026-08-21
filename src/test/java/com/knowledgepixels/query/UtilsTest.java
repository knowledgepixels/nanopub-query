package com.knowledgepixels.query;

import com.github.jsonldjava.shaded.com.google.common.hash.Hashing;
import org.apache.http.client.config.RequestConfig;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.util.Values;
import org.eclipse.rdf4j.query.*;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.nanopub.vocabulary.NPA;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.*;

class UtilsTest {

    private final Value mockValue = Values.literal("testValue");
    private final String existingHash = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08";

    @BeforeEach
    void setUp() throws NoSuchFieldException, IllegalAccessException {
        Field field = Utils.class.getDeclaredField("hashToObjMap");
        field.setAccessible(true);
        field.set(null, null);
    }

    @Test
    void getObjectForHash() {
        Map<String, Value> mockMap = new HashMap<>();
        mockMap.put(existingHash, mockValue);

        try (MockedStatic<Utils> mockedUtils = Mockito.mockStatic(Utils.class, Mockito.CALLS_REAL_METHODS)) {
            mockedUtils.when(Utils::getHashToObjectMap).thenReturn(mockMap);

            assertEquals(mockValue, Utils.getObjectForHash(existingHash));
            String nonExistingHash = "e33d45cb2fa55238c2ef0ff905d407fe26c9343ff36b44f9b03cb6e44d6cb62c";
            assertNull(Utils.getObjectForHash(nonExistingHash));
        }
    }

    @Test
    void getShortPubkeyName() {
        String pubkey = "03a34b6c8e9071f4c9c26a7f21d0a73e87f96a8bb3a2c5f1c74827aaf4f2c6e4d8";
        String expectedShortName = "0..b3a2c..";
        assertEquals(expectedShortName, Utils.getShortPubkeyName(pubkey));

        String emptyPubkey = "";
        String expectedEmptyResult = "";
        assertEquals(expectedEmptyResult, Utils.getShortPubkeyName(emptyPubkey));

        String invalidPubkey = "short";
        String expectedInvalidResult = "short";
        assertEquals(expectedInvalidResult, Utils.getShortPubkeyName(invalidPubkey));
    }

    @Test
    void getObjectForPattern() {
        RepositoryConnection mockConnection = mock(RepositoryConnection.class);
        TupleQuery mockQuery = mock(TupleQuery.class);
        TupleQueryResult mockResult = mock(TupleQueryResult.class);
        BindingSet mockBinding = mock(BindingSet.class);

        IRI mockGraph = Values.iri("https://knowledgepixels.com/graph");
        IRI mockSubj = Values.iri("https://knowledgepixels.com/subject");
        IRI mockPred = Values.iri("https://knowledgepixels.com/predicate");
        Value mockObject = Values.literal("testValue");

        when(mockConnection.prepareTupleQuery(QueryLanguage.SPARQL, "SELECT * { graph <" + mockGraph.stringValue() + "> { <" + mockSubj.stringValue() + "> <" + mockPred.stringValue() + "> ?o } }")).thenReturn(mockQuery);
        when(mockQuery.evaluate()).thenReturn(mockResult);
        when(mockResult.hasNext()).thenReturn(true, false);
        when(mockResult.next()).thenReturn(mockBinding);
        when(mockBinding.getBinding("o")).thenReturn(mock(Binding.class));
        when(mockBinding.getBinding("o").getValue()).thenReturn(mockObject);

        // Test case: Match found
        Value result = Utils.getObjectForPattern(mockConnection, mockGraph, mockSubj, mockPred);
        assertEquals(mockObject, result);

        // Test case: No match found
        when(mockResult.hasNext()).thenReturn(false);
        Value noResult = Utils.getObjectForPattern(mockConnection, mockGraph, mockSubj, mockPred);
        assertNull(noResult);
    }

    @Test
    void getObjectsForPattern() {
        RepositoryConnection mockConnection = mock(RepositoryConnection.class);
        TupleQuery mockQuery = mock(TupleQuery.class);
        TupleQueryResult mockResult = mock(TupleQueryResult.class);
        BindingSet mockBinding = mock(BindingSet.class);

        IRI mockGraph = Values.iri("https://knowledgepixels.com/graph");
        IRI mockSubj = Values.iri("https://knowledgepixels.com/subject");
        IRI mockPred = Values.iri("https://knowledgepixels.com/predicate");
        List<Value> mockObject = List.of(Values.literal("testValue1"), Values.literal("testValue2"));

        when(mockConnection.prepareTupleQuery(QueryLanguage.SPARQL, "SELECT * { graph <" + mockGraph.stringValue() + "> { <" + mockSubj.stringValue() + "> <" + mockPred.stringValue() + "> ?o } }")).thenReturn(mockQuery);
        when(mockQuery.evaluate()).thenReturn(mockResult);

        when(mockResult.hasNext()).thenReturn(true, true, false);
        when(mockResult.next()).thenReturn(mockBinding);
        when(mockBinding.getBinding("o")).thenReturn(mock(Binding.class));
        when(mockBinding.getBinding("o").getValue()).thenReturn(mockObject.get(0), mockObject.get(1));

        // Test case: Match found
        List<Value> result = Utils.getObjectsForPattern(mockConnection, mockGraph, mockSubj, mockPred);
        assertEquals(mockObject, result);

        // Test case: No match found
        when(mockResult.hasNext()).thenReturn(false);
        List<Value> noResult = Utils.getObjectsForPattern(mockConnection, mockGraph, mockSubj, mockPred);
        assertEquals(List.of(), noResult);
    }

    @Test
    void getEnvString() {
        final String defaultValue = "default";
        final String mockValueString = "value";

        // getEnvString now reads the env via the Utils.getRawEnv seam (System.getenv
        // cannot be mocked directly — see #117). CALLS_REAL_METHODS keeps getEnvString real.
        try (MockedStatic<Utils> mockedUtils = Mockito.mockStatic(Utils.class, Mockito.CALLS_REAL_METHODS)) {
            mockedUtils.when(() -> Utils.getRawEnv("EXISTING_VAR")).thenReturn(mockValueString);
            mockedUtils.when(() -> Utils.getRawEnv("EMPTY_VAR")).thenReturn("");
            mockedUtils.when(() -> Utils.getRawEnv("NULL_VAR")).thenReturn(null);
            mockedUtils.when(() -> Utils.getRawEnv("NON_EXISTING_VAR")).thenReturn(null);

            assertEquals(mockValueString, Utils.getEnvString("EXISTING_VAR", defaultValue));
            assertEquals(defaultValue, Utils.getEnvString("NON_EXISTING_VAR", defaultValue));
            assertEquals(defaultValue, Utils.getEnvString("EMPTY_VAR", defaultValue));
            assertEquals(defaultValue, Utils.getEnvString("NULL_VAR", defaultValue));
        }
    }

    @Test
    void getEnvInt() {
        final int defaultValue = 0;
        final String validIntValue = "42";
        final int validInt = Integer.parseInt(validIntValue);
        final String invalidIntValue = "not_an_int";

        try (MockedStatic<Utils> mockedUtils = Mockito.mockStatic(Utils.class, Mockito.CALLS_REAL_METHODS)) {
            mockedUtils.when(() -> Utils.getRawEnv("VALID_INT")).thenReturn(validIntValue);
            mockedUtils.when(() -> Utils.getRawEnv("INVALID_INT")).thenReturn(invalidIntValue);
            mockedUtils.when(() -> Utils.getRawEnv("EMPTY_VAR")).thenReturn("");
            mockedUtils.when(() -> Utils.getRawEnv("NULL_VAR")).thenReturn(null);
            mockedUtils.when(() -> Utils.getRawEnv("NON_EXISTING_VAR")).thenReturn(null);

            assertEquals(validInt, Utils.getEnvInt("VALID_INT", defaultValue));
            assertEquals(defaultValue, Utils.getEnvInt("NON_EXISTING_VAR", defaultValue));
            assertEquals(defaultValue, Utils.getEnvInt("INVALID_INT", defaultValue));
            assertEquals(defaultValue, Utils.getEnvInt("EMPTY_VAR", defaultValue));
            assertEquals(defaultValue, Utils.getEnvInt("NULL_VAR", defaultValue));
        }
    }

    @Test
    void getHashToObjectMapWhenNotNull() {
        Map<String, Value> mockMap = new HashMap<>();

        RepositoryConnection mockConnection = mock(RepositoryConnection.class);
        TupleQuery mockQuery = mock(TupleQuery.class);
        TupleQueryResult mockResult = mock(TupleQueryResult.class);
        BindingSet mockBindingSet = mock(BindingSet.class);

        try (MockedStatic<TripleStore> mockedTripleStore = Mockito.mockStatic(TripleStore.class)) {
            TripleStore mockTripleStore = mock(TripleStore.class);
            mockedTripleStore.when(TripleStore::get).thenReturn(mockTripleStore);
            when(mockTripleStore.getAdminRepoConnection()).thenReturn(mockConnection);

            when(mockConnection.prepareTupleQuery(QueryLanguage.SPARQL, "SELECT * { graph ?g { ?s ?p ?o } }")).thenReturn(mockQuery);
            mockQuery.setBinding("g", NPA.GRAPH);
            mockQuery.setBinding("p", NPA.IS_HASH_OF);

            when(mockQuery.evaluate()).thenReturn(mockResult);

            // Mock result iteration
            when(mockResult.hasNext()).thenReturn(true, false);
            when(mockResult.next()).thenReturn(mockBindingSet);

            // Mock BindingSet values
            Value mockSubject = Values.iri(NPA.HASH + UtilsTest.this.existingHash);

            when(mockBindingSet.getBinding("s")).thenReturn(mock(Binding.class));
            when(mockBindingSet.getBinding("o")).thenReturn(mock(Binding.class));
            when(mockBindingSet.getBinding("s").getValue()).thenReturn(mockSubject);
            when(mockBindingSet.getBinding("o").getValue()).thenReturn(mockValue);

            mockMap.put(existingHash, mockValue);

            Map<String, Value> result = Utils.getHashToObjectMap();
            assertEquals(mockMap, result);

            assertEquals(mockMap, Utils.getHashToObjectMap());
            verify(mockConnection, times(1)).prepareTupleQuery(QueryLanguage.SPARQL, "SELECT * { graph ?g { ?s ?p ?o } }");
        }
    }

    @Test
    void getHashToObjectMapWhenNull() {
        Map<String, Value> mockMap = new HashMap<>();

        RepositoryConnection mockConnection = mock(RepositoryConnection.class);
        TupleQuery mockQuery = mock(TupleQuery.class);
        TupleQueryResult mockResult = mock(TupleQueryResult.class);
        BindingSet mockBindingSet = mock(BindingSet.class);

        try (MockedStatic<TripleStore> mockedTripleStore = Mockito.mockStatic(TripleStore.class)) {
            TripleStore mockTripleStore = mock(TripleStore.class);
            mockedTripleStore.when(TripleStore::get).thenReturn(mockTripleStore);
            when(mockTripleStore.getAdminRepoConnection()).thenReturn(mockConnection);

            when(mockConnection.prepareTupleQuery(QueryLanguage.SPARQL, "SELECT * { graph ?g { ?s ?p ?o } }")).thenReturn(mockQuery);
            mockQuery.setBinding("g", NPA.GRAPH);
            mockQuery.setBinding("p", NPA.IS_HASH_OF);

            when(mockQuery.evaluate()).thenReturn(mockResult);

            // Mock result iteration
            when(mockResult.hasNext()).thenReturn(true, false);
            when(mockResult.next()).thenReturn(mockBindingSet);

            // Mock BindingSet values
            Value mockSubject = Values.iri(NPA.HASH + UtilsTest.this.existingHash);

            when(mockBindingSet.getBinding("s")).thenReturn(mock(Binding.class));
            when(mockBindingSet.getBinding("o")).thenReturn(mock(Binding.class));
            when(mockBindingSet.getBinding("s").getValue()).thenReturn(mockSubject);
            when(mockBindingSet.getBinding("o").getValue()).thenReturn(mockValue);

            mockMap.put(existingHash, mockValue);

            Map<String, Value> result = Utils.getHashToObjectMap();
            assertEquals(mockMap, result);
        }
    }

    @Test
    void getValueFromAValueObject() {
        Value value = Values.literal("this is a test value");
        Value retrievedValue = Utils.getValue(value);
        assertEquals(value, retrievedValue);
    }

    @Test
    void getValueFromAnObject() {
        String value = "this is a test value as a string";
        assertEquals(Utils.getValue(value), Values.literal(value));
    }

    @Test
    void createHashWhenNotExists() {
        try (MockedStatic<Utils> mockedUtils = Mockito.mockStatic(Utils.class, Mockito.CALLS_REAL_METHODS);
             MockedStatic<TripleStore> mockedTripleStore = Mockito.mockStatic(TripleStore.class)) {
            RepositoryConnection mockConnection = mock(RepositoryConnection.class);

            Object obj = "testObject";
            String expectedHash = Hashing.sha256().hashString(obj.toString(), StandardCharsets.UTF_8).toString();

            mockedTripleStore.when(TripleStore::get).thenReturn(mock(TripleStore.class));
            when(TripleStore.get().getAdminRepoConnection()).thenReturn(mockConnection);
            mockedUtils.when(Utils::getHashToObjectMap).thenReturn(new HashMap<>());

            String resultHash = Utils.createHash(obj);
            assertEquals(expectedHash, resultHash);
        }
    }

    @Test
    void createHashWhenAlreadyExists() {
        try (MockedStatic<Utils> mockedUtils = Mockito.mockStatic(Utils.class, Mockito.CALLS_REAL_METHODS)) {
            Object obj = "testObject";
            String expectedHash = Hashing.sha256().hashString(obj.toString(), StandardCharsets.UTF_8).toString();
            mockedUtils.when(Utils::getHashToObjectMap).thenReturn(Map.of(expectedHash, Values.literal("existingValue")));
            String resultHash = Utils.createHash(obj);
            assertEquals(expectedHash, resultHash);
        }
    }

    @Test
    void getRequestConfig() {
        RequestConfig rc = Utils.getHttpRequestConfig();
        assert (rc != null);
    }

    @Test
    void appendQueryTimeoutAddsParamWithoutQueryString() {
        assertEquals("/rdf4j-server/repositories/full?timeout=60",
                Utils.appendQueryTimeout("/rdf4j-server/repositories/full", 60));
    }

    @Test
    void appendQueryTimeoutAddsParamToExistingQueryString() {
        assertEquals("/rdf4j-server/repositories/full?query=select&timeout=60",
                Utils.appendQueryTimeout("/rdf4j-server/repositories/full?query=select", 60));
    }

    @Test
    void appendQueryTimeoutKeepsClientSuppliedTimeout() {
        assertEquals("/rdf4j-server/repositories/full?timeout=5",
                Utils.appendQueryTimeout("/rdf4j-server/repositories/full?timeout=5", 60));
        assertEquals("/rdf4j-server/repositories/full?query=select&timeout=5",
                Utils.appendQueryTimeout("/rdf4j-server/repositories/full?query=select&timeout=5", 60));
    }

    @Test
    void appendQueryTimeoutDoesNotMatchOtherParamsEndingInTimeout() {
        assertEquals("/repo?mytimeout=5&timeout=60",
                Utils.appendQueryTimeout("/repo?mytimeout=5", 60));
    }

    @Test
    void appendQueryTimeoutDisabledByNonPositiveValue() {
        assertEquals("/repo/full", Utils.appendQueryTimeout("/repo/full", 0));
        assertEquals("/repo/full", Utils.appendQueryTimeout("/repo/full", -1));
        assertNull(Utils.appendQueryTimeout(null, 60));
    }

}