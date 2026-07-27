package com.knowledgepixels.query;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.nanopub.MalformedNanopubException;
import org.nanopub.Nanopub;
import org.nanopub.NanopubImpl;
import org.nanopub.NanopubUtils;
import org.nanopub.testsuite.NanopubTestSuite;
import org.nanopub.vocabulary.NPA;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ShardReconcilerTest {

    private static final SimpleValueFactory vf = SimpleValueFactory.getInstance();

    private static final IRI npId = vf.createIRI("https://w3id.org/np/RAabcdefghijklmnopqrstuvwxyzABCDEFGHIJK12345");
    private static final String pubkeyHash = "0000000000000000000000000000000000000000000000000000000000000000";

    private static MockedStatic<Utils> mockHashSideEffects() {
        // createHash writes unseen hash mappings to the admin repo; pretend all
        // hashes are known so the derivation stays free of TripleStore access.
        MockedStatic<Utils> mockedUtils = mockStatic(Utils.class, CALLS_REAL_METHODS);
        Map<String, Value> hashToObjectMap = mock(Map.class);
        when(hashToObjectMap.containsKey(anyString())).thenReturn(true);
        mockedUtils.when(Utils::getHashToObjectMap).thenReturn(hashToObjectMap);
        return mockedUtils;
    }

    @Test
    void expectedShardReposCoversCoreAndTypeRepos() {
        try (MockedStatic<Utils> ignored = mockHashSideEffects()) {
            IRI typeA = vf.createIRI("https://w3id.org/kpxl/gen/terms/ResourceView");
            IRI typeB = vf.createIRI("http://example.org/SomeType");
            Set<String> repos = ShardReconciler.expectedShardRepos(npId, pubkeyHash, Set.of(typeA, typeB), "full");
            assertTrue(repos.contains("meta"));
            assertTrue(repos.contains("text"));
            assertTrue(repos.contains("pubkey_" + pubkeyHash));
            assertTrue(repos.contains("type_" + Utils.createHash(typeA)));
            assertTrue(repos.contains("type_" + Utils.createHash(typeB)));
            // The driver repo itself is excluded — membership there is the premise.
            assertFalse(repos.contains("full"));
            assertFalse(repos.contains("spaces"));
            assertFalse(repos.contains("last30d"));
        }
    }

    @Test
    void expectedShardReposAppliesLoaderTypeFilters() {
        try (MockedStatic<Utils> ignored = mockHashSideEffects()) {
            IRI locallyMinted = vf.createIRI(npId.stringValue() + "/localType");
            IRI nonHttp = vf.createIRI("urn:some:type");
            IRI regular = vf.createIRI("https://example.org/RegularType");
            Set<String> repos = ShardReconciler.expectedShardRepos(npId, pubkeyHash, Set.of(locallyMinted, nonHttp, regular), "full");
            assertTrue(repos.contains("type_" + Utils.createHash(regular)));
            assertEquals(1, repos.stream().filter(r -> r.startsWith("type_")).count(),
                    "locally-minted and non-http(s) type IRIs must not produce type shards");
        }
    }

    @Test
    void expectedShardReposIncludesSpacesForTriggerTypes() {
        try (MockedStatic<Utils> ignored = mockHashSideEffects()) {
            IRI triggerType = SpacesExtractor.TRIGGER_TYPES.iterator().next();
            Set<String> repos = ShardReconciler.expectedShardRepos(npId, pubkeyHash, Set.of(triggerType), "full");
            assertTrue(repos.contains("spaces"));
        }
    }

    @Test
    void expectedShardReposWithMetaDriverExpectsFull() {
        try (MockedStatic<Utils> ignored = mockHashSideEffects()) {
            Set<String> repos = ShardReconciler.expectedShardRepos(npId, pubkeyHash, Set.of(), "meta");
            assertTrue(repos.contains("full"));
            assertFalse(repos.contains("meta"));
        }
    }

    @Test
    void reconstructNanopubRoundTrip() throws MalformedNanopubException, IOException {
        Nanopub original = new NanopubImpl(NanopubTestSuite.getLatest()
                .getByArtifactCode("RA6T-YLqLnYd5XfnqR9PaGUjCzudvHdYjcG4GvOc7fdpA").getFirst().toFile());

        SailRepository repo = new SailRepository(new MemoryStore());
        repo.init();
        try (var conn = repo.getConnection()) {
            // Store the nanopub the way loadNanopubToRepo does: the four content
            // graphs plus the npa:hasGraph admin pointers used for discovery.
            conn.add(NanopubUtils.getStatements(original));
            for (IRI g : new IRI[]{original.getHeadUri(), original.getAssertionUri(),
                    original.getProvenanceUri(), original.getPubinfoUri()}) {
                conn.add(original.getUri(), NPA.HAS_GRAPH, g, NPA.GRAPH);
            }
        }

        TripleStore tripleStore = mock(TripleStore.class);
        when(tripleStore.getRepoConnection("full")).thenAnswer(inv -> repo.getConnection());
        try (MockedStatic<TripleStore> mockedTripleStore = mockStatic(TripleStore.class)) {
            mockedTripleStore.when(TripleStore::get).thenReturn(tripleStore);
            Nanopub reconstructed = ShardReconciler.reconstructNanopub("full", original.getUri());
            assertEquals(original.getUri(), reconstructed.getUri());
            assertEquals(original.getAssertionUri(), reconstructed.getAssertionUri());
            assertEquals(NanopubUtils.getStatements(original).size(),
                    NanopubUtils.getStatements(reconstructed).size());
        } finally {
            repo.shutDown();
        }
    }

}
