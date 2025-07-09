package com.knowledgepixels.query;

import org.apache.commons.exec.environment.EnvironmentUtils;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.query.*;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.util.HashMap;
import java.util.Map;

import static com.knowledgepixels.query.Utils.HASH_PREFIX;
import static com.knowledgepixels.query.Utils.IS_HASH_OF;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UtilsTest {

    private final String mockValueString = "value";
    private final Value mockValue = SimpleValueFactory.getInstance().createLiteral("testValue");

    private final String existingHash = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08";
    private final String nonExistingHash = "e33d45cb2fa55238c2ef0ff905d407fe26c9343ff36b44f9b03cb6e44d6cb62c";


    @Test
    void getObjectForHash() {
        Map<String, Value> mockMap = new HashMap<>();
        mockMap.put(existingHash, mockValue);

        try (MockedStatic<Utils> mockedUtils = Mockito.mockStatic(Utils.class, Mockito.CALLS_REAL_METHODS)) {
            mockedUtils.when(Utils::getHashToObjectMap).thenReturn(mockMap);

            assertEquals(mockValue, Utils.getObjectForHash(existingHash));
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

        IRI mockGraph = SimpleValueFactory.getInstance().createIRI("http://knowledgepixels.com/graph");
        IRI mockSubj = SimpleValueFactory.getInstance().createIRI("http://knowledgepixels.com/subject");
        IRI mockPred = SimpleValueFactory.getInstance().createIRI("http://knowledgepixels.com/predicate");
        Value mockObject = SimpleValueFactory.getInstance().createLiteral("testValue");

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
    }

    @Test
    void getEnvString() {
        final String defaultValue = "default";

        Map<String, String> mockEnv = new HashMap<>();
        mockEnv.put("EXISTING_VAR", mockValueString);
        mockEnv.put("EMPTY_VAR", "");
        mockEnv.put("NULL_VAR", null);

        try (MockedStatic<EnvironmentUtils> mockedEnvUtils = Mockito.mockStatic(EnvironmentUtils.class)) {
            mockedEnvUtils.when(EnvironmentUtils::getProcEnvironment).thenReturn(mockEnv);

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

        Map<String, String> mockEnv = new HashMap<>();
        mockEnv.put("VALID_INT", validIntValue);
        mockEnv.put("INVALID_INT", invalidIntValue);
        mockEnv.put("EMPTY_VAR", "");
        mockEnv.put("NULL_VAR", null);

        try (MockedStatic<EnvironmentUtils> mockedEnvUtils = Mockito.mockStatic(EnvironmentUtils.class)) {
            mockedEnvUtils.when(EnvironmentUtils::getProcEnvironment).thenReturn(mockEnv);

            assertEquals(validInt, Utils.getEnvInt("VALID_INT", defaultValue));
            assertEquals(defaultValue, Utils.getEnvInt("NON_EXISTING_VAR", defaultValue));
            assertEquals(defaultValue, Utils.getEnvInt("INVALID_INT", defaultValue));
            assertEquals(defaultValue, Utils.getEnvInt("EMPTY_VAR", defaultValue));
            assertEquals(defaultValue, Utils.getEnvInt("NULL_VAR", defaultValue));
        }
    }

    @Test
    void getHashToObjectMap() {
        Map<String, Value> mockMap = new HashMap<>();

        try (MockedStatic<Utils> mockedUtils = Mockito.mockStatic(Utils.class, Mockito.CALLS_REAL_METHODS)) {
            RepositoryConnection mockConnection = mock(RepositoryConnection.class);
            TupleQuery mockQuery = mock(TupleQuery.class);
            TupleQueryResult mockResult = mock(TupleQueryResult.class);
            BindingSet mockBindingSet = mock(BindingSet.class);

            try (MockedStatic<TripleStore> mockedTripleStore = Mockito.mockStatic(TripleStore.class)) {
                TripleStore mockTripleStore = mock(TripleStore.class);
                mockedTripleStore.when(TripleStore::get).thenReturn(mockTripleStore);
                when(mockTripleStore.getAdminRepoConnection()).thenReturn(mockConnection);

                when(mockConnection.prepareTupleQuery(QueryLanguage.SPARQL, "SELECT * { graph ?g { ?s ?p ?o } }")).thenReturn(mockQuery);
                mockQuery.setBinding("g", NanopubLoader.ADMIN_GRAPH);
                mockQuery.setBinding("p", IS_HASH_OF);

                when(mockQuery.evaluate()).thenReturn(mockResult);

                // Mock result iteration
                when(mockResult.hasNext()).thenReturn(true, false);
                when(mockResult.next()).thenReturn(mockBindingSet);

                // Mock BindingSet values
                Value mockSubject = SimpleValueFactory.getInstance().createIRI(HASH_PREFIX + UtilsTest.this.existingHash);

                when(mockBindingSet.getBinding("s")).thenReturn(mock(Binding.class));
                when(mockBindingSet.getBinding("o")).thenReturn(mock(Binding.class));
                when(mockBindingSet.getBinding("s").getValue()).thenReturn(mockSubject);
                when(mockBindingSet.getBinding("o").getValue()).thenReturn(mockValue);

                mockMap.put(existingHash, mockValue);
                mockedUtils.when(Utils::getHashToObjectMap).thenReturn(mockMap);

                Map<String, Value> result = Utils.getHashToObjectMap();
                assertEquals(mockMap, result);
            }
        }
    }

}