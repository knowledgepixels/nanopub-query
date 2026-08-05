package com.knowledgepixels.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;

import org.eclipse.rdf4j.model.IRI;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.nanopub.vocabulary.NPA;

import com.knowledgepixels.query.vocabulary.NPAT;
import com.knowledgepixels.query.vocabulary.SpacesVocab;

/**
 * Executable tests for the {@link AuthorityResolver} pipeline — the mirror step,
 * the pointer/counter helpers, orphan cleanup, the tier fixed-point loop, and the
 * {@code tick} / {@code periodicRebuildTick} routing — run against real in-process
 * repositories via {@link InMemoryTripleStore}.
 *
 * <p>The sibling {@code AuthorityResolver*Test} classes assert on the <em>text</em>
 * of the generated SPARQL templates, which was the only option while the in-memory
 * UPDATE path was broken (see {@link AuthorityResolverTest}'s javadoc). That leaves
 * everything around the templates — which query runs when, what the mirror copies,
 * whether the pointer swap is atomic, whether the loop terminates — untested. This
 * class covers that layer.
 */
class AuthorityResolverPipelineTest {

    private static final String SPACES = "spaces";
    private static final String TRUST = "trust";

    private static final String GEN_NS = com.knowledgepixels.query.vocabulary.GEN.NAMESPACE;
    private static final String NPA_NS = NPA.NAMESPACE;

    /** A 64-hex trust-state hash, matching what the registry actually produces. */
    private static final String HASH =
            "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789";

    @BeforeEach
    void resetSingletons() throws Exception {
        reset(AuthorityResolver.class, "instance");
        reset(TrustStateRegistry.class, "instance");
    }

    private static void reset(Class<?> cls, String fieldName) throws Exception {
        Field f = cls.getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(null, null);
    }

    private static AuthorityResolver resolver() {
        return AuthorityResolver.get();
    }

    // ---------------- fixtures ----------------

    /**
     * Writes one npa:AccountState row into a trust-state graph of the trust repo,
     * shaped exactly as {@link TrustStateLoader#materialize} writes it.
     */
    private static void trustAccount(InMemoryTripleStore store, String trustHash,
                                     String acctLocal, String agent, String pubkey,
                                     String status) {
        store.update(TRUST, """
                INSERT DATA { GRAPH <%s> {
                  <https://example.org/acct/%s> a <%sAccountState> ;
                      <%sagent>       <%s> ;
                      <%spubkey>      "%s" ;
                      <%strustStatus> <%s%s> .
                } }
                """.formatted(NPAT.forHash(trustHash), acctLocal, NPA.NAMESPACE,
                NPA.NAMESPACE, agent, NPA.NAMESPACE, pubkey,
                NPA.NAMESPACE, NPA.NAMESPACE, status));
    }

    private static void setLoadCounter(InMemoryTripleStore store, long n) {
        store.update(SPACES, """
                INSERT DATA { GRAPH <%s> {
                  <%s> <%s> "%d"^^<http://www.w3.org/2001/XMLSchema#long> .
                } }""".formatted(NPA.GRAPH, NPA.THIS_REPO, SpacesVocab.CURRENT_LOAD_COUNTER, n));
    }

    // ---------------- mirror step ----------------

    @Test
    void mirrorTrustState_copiesOnlyTrustApprovedRows() {
        // loaded + toLoad are the two authority-approving statuses; contested and
        // skipped rows must not confer any authority in the space state.
        try (InMemoryTripleStore store = new InMemoryTripleStore()) {
            trustAccount(store, HASH, "a1", "https://example.org/alice", "pk1", "loaded");
            trustAccount(store, HASH, "a2", "https://example.org/bob", "pk2", "toLoad");
            trustAccount(store, HASH, "a3", "https://example.org/mallory", "pk3", "contested");
            trustAccount(store, HASH, "a4", "https://example.org/eve", "pk4", "skipped");

            IRI target = SpacesVocab.forSpaceState(HASH, 1L);
            int mirrored = resolver().mirrorTrustState(HASH, target);

            assertEquals(2, mirrored, "only the two approved rows are mirrored");
            assertTrue(store.ask(SPACES, "ASK { GRAPH <%s> { <https://example.org/acct/a1> <%sagent> <https://example.org/alice> } }"
                    .formatted(target, NPA.NAMESPACE)));
            assertTrue(store.ask(SPACES, "ASK { GRAPH <%s> { <https://example.org/acct/a2> <%sagent> <https://example.org/bob> } }"
                    .formatted(target, NPA.NAMESPACE)));
            assertFalse(store.ask(SPACES, "ASK { GRAPH <%s> { <https://example.org/acct/a3> ?p ?o } }".formatted(target)),
                    "contested must not be mirrored");
            assertFalse(store.ask(SPACES, "ASK { GRAPH <%s> { <https://example.org/acct/a4> ?p ?o } }".formatted(target)),
                    "skipped must not be mirrored");
        }
    }

    @Test
    void mirrorTrustState_skipsRowsMissingAgentOrPubkey() {
        // A malformed row must be dropped rather than mirrored half-populated —
        // an AccountState with no pubkey would match no publisher and silently
        // grant nothing, but one with no agent could join wrongly downstream.
        try (InMemoryTripleStore store = new InMemoryTripleStore()) {
            store.update(TRUST, """
                    INSERT DATA { GRAPH <%s> {
                      <https://example.org/acct/noAgent> a <%sAccountState> ;
                          <%spubkey> "pk1" ; <%strustStatus> <%sloaded> .
                      <https://example.org/acct/noPubkey> a <%sAccountState> ;
                          <%sagent> <https://example.org/x> ; <%strustStatus> <%sloaded> .
                    } }
                    """.formatted(NPAT.forHash(HASH), NPA.NAMESPACE, NPA.NAMESPACE,
                    NPA.NAMESPACE, NPA.NAMESPACE, NPA.NAMESPACE, NPA.NAMESPACE,
                    NPA.NAMESPACE, NPA.NAMESPACE));

            IRI target = SpacesVocab.forSpaceState(HASH, 1L);
            assertEquals(0, resolver().mirrorTrustState(HASH, target),
                    "incomplete rows are skipped, not mirrored");
            assertEquals(0L, store.graphSize(SPACES, target));
        }
    }

    @Test
    void mirrorTrustState_mirrorsViaNanopubWhenPresent() {
        // Optional provenance (nanopub-registry#117/#118) — mirrored when present,
        // absent otherwise, and its absence must not drop the row.
        try (InMemoryTripleStore store = new InMemoryTripleStore()) {
            trustAccount(store, HASH, "withIntro", "https://example.org/alice", "pk1", "loaded");
            store.update(TRUST, "INSERT DATA { GRAPH <%s> { <https://example.org/acct/withIntro> <%sviaNanopub> <http://purl.org/np/RAintro> } }"
                    .formatted(NPAT.forHash(HASH), NPA.NAMESPACE));
            trustAccount(store, HASH, "noIntro", "https://example.org/bob", "pk2", "loaded");

            IRI target = SpacesVocab.forSpaceState(HASH, 1L);
            assertEquals(2, resolver().mirrorTrustState(HASH, target));
            assertTrue(store.ask(SPACES, "ASK { GRAPH <%s> { <https://example.org/acct/withIntro> <%sviaNanopub> <http://purl.org/np/RAintro> } }"
                    .formatted(target, NPA.NAMESPACE)));
            assertFalse(store.ask(SPACES, "ASK { GRAPH <%s> { <https://example.org/acct/noIntro> <%sviaNanopub> ?o } }"
                    .formatted(target, NPA.NAMESPACE)));
        }
    }

    @Test
    void mirrorTrustState_copiesCanonicalFoafNames() {
        // Names are mirrored so consumers can read ?agent foaf:name inside the
        // state graph without a cross-repo SERVICE join.
        try (InMemoryTripleStore store = new InMemoryTripleStore()) {
            trustAccount(store, HASH, "a1", "https://example.org/alice", "pk1", "loaded");
            store.update(TRUST, """
                    INSERT DATA { GRAPH <%s> {
                      <https://example.org/alice> <http://xmlns.com/foaf/0.1/name> "Alice" .
                    } }""".formatted(NPAT.forHash(HASH)));

            IRI target = SpacesVocab.forSpaceState(HASH, 1L);
            resolver().mirrorTrustState(HASH, target);

            assertTrue(store.ask(SPACES, """
                    ASK { GRAPH <%s> { <https://example.org/alice> <http://xmlns.com/foaf/0.1/name> "Alice" } }"""
                    .formatted(target)));
        }
    }

    @Test
    void mirrorTrustState_readsNothingFromAnUnrelatedTrustState() {
        // Graph scoping: rows in a different trust state's graph must be invisible.
        try (InMemoryTripleStore store = new InMemoryTripleStore()) {
            trustAccount(store, "otherhash", "a1", "https://example.org/alice", "pk1", "loaded");

            IRI target = SpacesVocab.forSpaceState(HASH, 1L);
            assertEquals(0, resolver().mirrorTrustState(HASH, target),
                    "the mirror is scoped to its own trust-state graph");
        }
    }

    // ---------------- pointer + counter helpers ----------------

    @Test
    void getCurrentSpaceStateGraph_returnsNullBeforeAnyBuild() {
        try (InMemoryTripleStore ignored = new InMemoryTripleStore()) {
            assertNull(resolver().getCurrentSpaceStateGraph());
        }
    }

    @Test
    void flipPointer_replacesTheOldPointerInOneStatement() {
        // Readers must never see zero or two pointers, so the flip is a single
        // DELETE/INSERT/WHERE rather than a remove followed by an add.
        try (InMemoryTripleStore store = new InMemoryTripleStore()) {
            IRI g1 = SpacesVocab.forSpaceState(HASH, 1L);
            IRI g2 = SpacesVocab.forSpaceState(HASH, 2L);

            resolver().flipPointer(g1);
            assertEquals(g1, resolver().getCurrentSpaceStateGraph());

            resolver().flipPointer(g2);
            assertEquals(g2, resolver().getCurrentSpaceStateGraph());
            assertEquals(1L, store.count(SPACES,
                    "SELECT (COUNT(*) AS ?c) WHERE { GRAPH <%s> { <%s> <%s> ?o } }"
                            .formatted(NPA.GRAPH, NPA.THIS_REPO, SpacesVocab.HAS_CURRENT_SPACE_STATE)),
                    "exactly one pointer survives the flip");
        }
    }

    @Test
    void getCurrentLoadCounter_defaultsToZeroAndParsesTheStoredValue() {
        try (InMemoryTripleStore store = new InMemoryTripleStore()) {
            assertEquals(0L, resolver().getCurrentLoadCounter(), "absent counter reads as 0");
            setLoadCounter(store, 987L);
            assertEquals(987L, resolver().getCurrentLoadCounter());
        }
    }

    @Test
    void getCurrentLoadCounter_throwsOnANonNumericValue() {
        // A corrupt counter must stop the build, not degrade to 0. Reading it as 0
        // names the new graph <hash>_0, which differs from the real current graph, so
        // the build proceeds and then drops the good one.
        try (InMemoryTripleStore store = new InMemoryTripleStore()) {
            store.update(SPACES, """
                    INSERT DATA { GRAPH <%s> { <%s> <%s> "not-a-number" } }"""
                    .formatted(NPA.GRAPH, NPA.THIS_REPO, SpacesVocab.CURRENT_LOAD_COUNTER));
            assertThrows(AuthorityResolver.SpaceStateUnavailableException.class,
                    () -> resolver().getCurrentLoadCounter());
        }
    }

    @Test
    void processedUpTo_roundTripsAndIsScopedToItsOwnGraph() {
        try (InMemoryTripleStore ignored = new InMemoryTripleStore()) {
            IRI g1 = SpacesVocab.forSpaceState(HASH, 1L);
            IRI g2 = SpacesVocab.forSpaceState(HASH, 2L);

            assertEquals(-1L, resolver().readProcessedUpTo(g1), "absent stamp reads as -1");

            resolver().writeProcessedUpTo(g1, 100L);
            assertEquals(100L, resolver().readProcessedUpTo(g1));
            assertEquals(-1L, resolver().readProcessedUpTo(g2),
                    "the stamp lives inside its own state graph");

            // Overwrite rather than accumulate.
            resolver().writeProcessedUpTo(g1, 250L);
            assertEquals(250L, resolver().readProcessedUpTo(g1));
        }
    }

    @Test
    void needsFullRebuildFlag_defaultsFalseAndRoundTrips() {
        try (InMemoryTripleStore ignored = new InMemoryTripleStore()) {
            assertFalse(resolver().readNeedsFullRebuild(), "absent flag reads as false");
            resolver().setNeedsFullRebuild();
            assertTrue(resolver().readNeedsFullRebuild());
            resolver().clearNeedsFullRebuild();
            assertFalse(resolver().readNeedsFullRebuild());
        }
    }

    @Test
    void dropGraph_removesOnlyTheTargetGraph() {
        try (InMemoryTripleStore store = new InMemoryTripleStore()) {
            IRI g1 = SpacesVocab.forSpaceState(HASH, 1L);
            IRI g2 = SpacesVocab.forSpaceState(HASH, 2L);
            store.update(SPACES, "INSERT DATA { GRAPH <%s> { <http://example.org/s> <http://example.org/p> 1 } }".formatted(g1));
            store.update(SPACES, "INSERT DATA { GRAPH <%s> { <http://example.org/s> <http://example.org/p> 2 } }".formatted(g2));

            resolver().dropGraph(g1);

            assertEquals(0L, store.graphSize(SPACES, g1));
            assertEquals(1L, store.graphSize(SPACES, g2), "sibling graph untouched");
        }
    }

    @Test
    void readTrustRepoCurrentHash_readsPointerAndRejectsForeignNamespaces() {
        try (InMemoryTripleStore store = new InMemoryTripleStore()) {
            assertTrue(resolver().readTrustRepoCurrentHash().isEmpty(), "no pointer yet");

            store.update(TRUST, "INSERT DATA { GRAPH <%s> { <%s> <%shasCurrentTrustState> <%s> } }"
                    .formatted(NPA.GRAPH, NPA.THIS_REPO, NPA.NAMESPACE, NPAT.forHash(HASH)));
            assertEquals(HASH, resolver().readTrustRepoCurrentHash().orElseThrow());
        }

        try (InMemoryTripleStore store = new InMemoryTripleStore()) {
            store.update(TRUST, "INSERT DATA { GRAPH <%s> { <%s> <%shasCurrentTrustState> <https://example.org/elsewhere> } }"
                    .formatted(NPA.GRAPH, NPA.THIS_REPO, NPA.NAMESPACE));
            assertTrue(resolver().readTrustRepoCurrentHash().isEmpty(),
                    "a pointer outside npat: must not be sliced into a bogus hash");
        }
    }

    // ---------------- orphan cleanup ----------------

    @Test
    void cleanOrphans_dropsUnreferencedStateGraphsAndKeepsTheCurrentOne() {
        // Orphans come from crashes mid-build. The current graph, and anything
        // outside the npass: namespace, must survive.
        try (InMemoryTripleStore store = new InMemoryTripleStore()) {
            IRI current = SpacesVocab.forSpaceState(HASH, 3L);
            IRI orphanA = SpacesVocab.forSpaceState(HASH, 1L);
            IRI orphanB = SpacesVocab.forSpaceState(HASH, 2L);
            for (IRI g : new IRI[] { current, orphanA, orphanB }) {
                store.update(SPACES, "INSERT DATA { GRAPH <%s> { <http://example.org/s> <http://example.org/p> <http://example.org/o> } }".formatted(g));
            }
            store.update(SPACES, "INSERT DATA { GRAPH <%s> { <http://example.org/s> <http://example.org/p> <http://example.org/o> } }"
                    .formatted(SpacesVocab.SPACES_GRAPH));
            resolver().flipPointer(current);

            resolver().cleanOrphans();

            assertTrue(store.graphSize(SPACES, current) > 0, "current graph kept");
            assertEquals(0L, store.graphSize(SPACES, orphanA), "orphan dropped");
            assertEquals(0L, store.graphSize(SPACES, orphanB), "orphan dropped");
            assertTrue(store.graphSize(SPACES, SpacesVocab.SPACES_GRAPH) > 0,
                    "non-npass graphs are out of scope for orphan cleanup");
        }
    }

    @Test
    void cleanOrphans_isANoOpWhenThereIsNothingToDrop() {
        try (InMemoryTripleStore ignored = new InMemoryTripleStore()) {
            resolver().cleanOrphans();  // must not throw on an empty repo
        }
    }

    @Test
    void cleanOrphans_swallowsStoreFailures() {
        // Startup cleanup is best-effort; a store error must not abort boot.
        try (MockedStatic<TripleStore> ts = Mockito.mockStatic(TripleStore.class)) {
            TripleStore broken = Mockito.mock(TripleStore.class);
            Mockito.when(broken.getRepoConnection(Mockito.anyString()))
                    .thenThrow(new RuntimeException("store down"));
            ts.when(TripleStore::get).thenReturn(broken);

            resolver().cleanOrphans();
        }
    }

    // ---------------- tier loop ----------------

    @Test
    void runTierLoop_iteratesToFixedPointAndCountsInsertedTriples() {
        // The loop re-runs its INSERT until the graph stops growing. This fixture
        // uses a transitive closure so a single pass is provably not enough:
        // a->b->c->d needs three iterations to close.
        try (InMemoryTripleStore store = new InMemoryTripleStore()) {
            IRI g = SpacesVocab.forSpaceState(HASH, 1L);
            store.update(SPACES, """
                    INSERT DATA { GRAPH <%s> {
                      <http://example.org/a> <http://example.org/link> <http://example.org/b> .
                      <http://example.org/b> <http://example.org/link> <http://example.org/c> .
                      <http://example.org/c> <http://example.org/link> <http://example.org/d> .
                    } }""".formatted(g));

            String closure = """
                    INSERT { GRAPH <%1$s> { ?x <http://example.org/link> ?z } }
                    WHERE {
                      GRAPH <%1$s> {
                        ?x <http://example.org/link> ?y .
                        ?y <http://example.org/link> ?z .
                      }
                    }""".formatted(g);

            int inserted = resolver().runTierLoop(g, closure);

            // Closure of a 3-edge path adds a->c, b->d, a->d.
            assertEquals(3, inserted, "returns the number of triples added across all iterations");
            assertEquals(6L, store.graphSize(SPACES, g));
            // Idempotent once at fixed point.
            assertEquals(0, resolver().runTierLoop(g, closure),
                    "re-running at fixed point inserts nothing");
        }
    }

    @Test
    void runTierLoop_returnsZeroWhenTheUpdateMatchesNothing() {
        try (InMemoryTripleStore ignored = new InMemoryTripleStore()) {
            IRI g = SpacesVocab.forSpaceState(HASH, 1L);
            assertEquals(0, resolver().runTierLoop(g, """
                    INSERT { GRAPH <%1$s> { ?s <http://example.org/derived> ?o } }
                    WHERE  { GRAPH <%1$s> { ?s <http://example.org/absent> ?o } }""".formatted(g)));
        }
    }

    // ---------------- subject totals ----------------

    @Test
    void computeTierSubjectTotals_separatesAdminFromNonAdminRoleInstantiations() {
        try (InMemoryTripleStore store = new InMemoryTripleStore()) {
            IRI g = SpacesVocab.forSpaceState(HASH, 1L);
            store.update(SPACES, """
                    INSERT DATA { GRAPH <%1$s> {
                      <http://example.org/ri1> a <%2$sRoleInstantiation> ;
                          <%3$sinverseProperty> <%2$shasAdmin> .
                      <http://example.org/ri2> a <%2$sRoleInstantiation> ;
                          <%3$sinverseProperty> <%2$shasAdmin> .
                      <http://example.org/ri3> a <%2$sRoleInstantiation> .
                      <http://example.org/ra1> a <%2$sRoleAssignment> .
                    } }""".formatted(g, GEN_NS, NPA.NAMESPACE));

            AuthorityResolver.TierSubjectTotals totals = resolver().computeTierSubjectTotals(g);

            assertEquals(2L, totals.adminRIs());
            assertEquals(1L, totals.attachmentRAs());
            assertEquals(1L, totals.nonAdminRIs(), "the admin-pinned RIs are excluded here");
        }
    }

    @Test
    void computeTierSubjectTotals_returnsZerosWhenTheCountQueryFails() {
        // A flaky count read must not wedge a build — these metrics are best-effort.
        try (MockedStatic<TripleStore> ts = Mockito.mockStatic(TripleStore.class)) {
            TripleStore broken = Mockito.mock(TripleStore.class);
            Mockito.when(broken.getRepoConnection(Mockito.anyString()))
                    .thenThrow(new RuntimeException("store down"));
            ts.when(TripleStore::get).thenReturn(broken);

            AuthorityResolver.TierSubjectTotals totals =
                    resolver().computeTierSubjectTotals(SpacesVocab.forSpaceState(HASH, 1L));
            assertEquals(0L, totals.adminRIs());
            assertEquals(0L, totals.attachmentRAs());
            assertEquals(0L, totals.nonAdminRIs());
        }
    }

    // ---------------- late-arrival ASK probes ----------------

    @Test
    void newRoleDeclarationsArrived_onlyFiresForDeclarationsAboveTheHorizon() {
        try (InMemoryTripleStore store = new InMemoryTripleStore()) {
            store.update(SPACES, """
                    INSERT DATA { GRAPH <%s> {
                      <http://example.org/rd1> a <%sRoleDeclaration> ;
                          <%sviaNanopub> <http://example.org/np1> .
                    } }""".formatted(SpacesVocab.SPACES_GRAPH, NPA.NAMESPACE, NPA.NAMESPACE));
            store.update(SPACES, """
                    INSERT DATA { GRAPH <%s> {
                      <http://example.org/np1> <%shasLoadNumber> 50 .
                    } }""".formatted(NPA.GRAPH, NPA.NAMESPACE));

            assertTrue(resolver().newRoleDeclarationsArrived(10L),
                    "a declaration loaded at 50 is inside the (10, ∞) delta");
            assertFalse(resolver().newRoleDeclarationsArrived(50L),
                    "the horizon is exclusive — 50 is not > 50");
            assertFalse(resolver().newRoleDeclarationsArrived(99L));
        }
    }

    @Test
    void newPresetAssignmentsArrived_matchesBothPresetKinds() {
        for (String kind : new String[] { "PresetAssignment", "PresetDeclaration" }) {
            try (InMemoryTripleStore store = new InMemoryTripleStore()) {
                store.update(SPACES, """
                        INSERT DATA { GRAPH <%s> {
                          <http://example.org/x> a <%s%s> ; <%sviaNanopub> <http://example.org/np1> .
                        } }""".formatted(SpacesVocab.SPACES_GRAPH, NPA.NAMESPACE, kind, NPA.NAMESPACE));
                store.update(SPACES, """
                        INSERT DATA { GRAPH <%s> { <http://example.org/np1> <%shasLoadNumber> 7 } }"""
                        .formatted(NPA.GRAPH, NPA.NAMESPACE));

                assertTrue(resolver().newPresetAssignmentsArrived(0L), kind + " triggers the sweep");
                assertFalse(resolver().newPresetAssignmentsArrived(7L), kind + " respects the horizon");
            }
        }
    }

    @Test
    void newPresetAssignmentsArrived_ignoresUnrelatedExtractionTypes() {
        try (InMemoryTripleStore store = new InMemoryTripleStore()) {
            store.update(SPACES, """
                    INSERT DATA { GRAPH <%s> {
                      <http://example.org/x> a <%sSpaceDefinition> ; <%sviaNanopub> <http://example.org/np1> .
                    } }""".formatted(SpacesVocab.SPACES_GRAPH, NPA.NAMESPACE, NPA.NAMESPACE));
            store.update(SPACES, """
                    INSERT DATA { GRAPH <%s> { <http://example.org/np1> <%shasLoadNumber> 7 } }"""
                    .formatted(NPA.GRAPH, NPA.NAMESPACE));

            assertFalse(resolver().newPresetAssignmentsArrived(0L),
                    "the type filter must not admit other extraction kinds");
        }
    }

    // ---------------- full build ----------------

    @Test
    void runFullBuild_mirrorsStampsFlipsAndDropsTheOldGraph() {
        try (InMemoryTripleStore store = new InMemoryTripleStore()) {
            trustAccount(store, HASH, "a1", "https://example.org/alice", "pk1", "loaded");
            setLoadCounter(store, 42L);
            // An older state that the build must supersede and drop.
            IRI stale = SpacesVocab.forSpaceState(HASH, 7L);
            store.update(SPACES, "INSERT DATA { GRAPH <%s> { <http://example.org/s> <http://example.org/p> 1 } }".formatted(stale));
            resolver().flipPointer(stale);

            resolver().runFullBuild(HASH);

            IRI expected = SpacesVocab.forSpaceState(HASH, 42L);
            assertEquals(expected, resolver().getCurrentSpaceStateGraph(), "pointer moved to the new graph");
            assertEquals(42L, resolver().readProcessedUpTo(expected), "processedUpTo stamped at the captured counter");
            assertTrue(store.ask(SPACES, "ASK { GRAPH <%s> { <https://example.org/acct/a1> <%sagent> <https://example.org/alice> } }"
                    .formatted(expected, NPA.NAMESPACE)), "trust rows mirrored into the new graph");
            assertEquals(0L, store.graphSize(SPACES, stale), "the superseded graph is dropped");
        }
    }

    @Test
    void runFullBuild_isANoOpWhenAlreadyCurrent() {
        // Same trust hash + same load counter => same graph IRI. Rebuilding would
        // be pure waste, and dropping "the old graph" would delete the live one.
        try (InMemoryTripleStore store = new InMemoryTripleStore()) {
            trustAccount(store, HASH, "a1", "https://example.org/alice", "pk1", "loaded");
            setLoadCounter(store, 42L);
            resolver().runFullBuild(HASH);

            IRI graph = SpacesVocab.forSpaceState(HASH, 42L);
            long sizeAfterFirst = store.graphSize(SPACES, graph);

            resolver().runFullBuild(HASH);

            assertEquals(graph, resolver().getCurrentSpaceStateGraph());
            assertEquals(sizeAfterFirst, store.graphSize(SPACES, graph),
                    "the early return leaves the live graph exactly as it was");
        }
    }

    @Test
    void runFullBuild_updatesTheMetricsSnapshot() {
        try (InMemoryTripleStore store = new InMemoryTripleStore()) {
            trustAccount(store, HASH, "a1", "https://example.org/alice", "pk1", "loaded");
            setLoadCounter(store, 5L);

            AuthorityResolver ar = resolver();
            ar.runFullBuild(HASH);

            assertNotNull(ar.getLastSubjectTotals());
            assertEquals(0L, ar.getLastProcessedUpToLag(), "a full build is by definition caught up");
        }
    }

    // ---------------- incremental cycle ----------------

    @Test
    void runIncrementalCycle_skipsAGraphWithNoProcessedUpToStamp() {
        // An unstamped graph means the build never finished; advancing it would
        // silently skip the delta it never processed.
        try (InMemoryTripleStore store = new InMemoryTripleStore()) {
            setLoadCounter(store, 10L);
            IRI g = SpacesVocab.forSpaceState(HASH, 1L);

            resolver().runIncrementalCycle(g);

            assertEquals(-1L, resolver().readProcessedUpTo(g),
                    "the cycle must not stamp a graph it refused to process");
        }
    }

    @Test
    void runIncrementalCycle_isANoOpWhenCaughtUp() {
        try (InMemoryTripleStore store = new InMemoryTripleStore()) {
            IRI g = SpacesVocab.forSpaceState(HASH, 1L);
            setLoadCounter(store, 10L);
            resolver().writeProcessedUpTo(g, 10L);

            resolver().runIncrementalCycle(g);

            assertEquals(10L, resolver().readProcessedUpTo(g));
            assertEquals(0L, resolver().getLastProcessedUpToLag(), "lag is zero when caught up");
        }
    }

    @Test
    void runIncrementalCycle_advancesProcessedUpToAndRecordsLag() {
        try (InMemoryTripleStore store = new InMemoryTripleStore()) {
            IRI g = SpacesVocab.forSpaceState(HASH, 1L);
            setLoadCounter(store, 100L);
            resolver().writeProcessedUpTo(g, 60L);

            AuthorityResolver ar = resolver();
            ar.runIncrementalCycle(g);

            assertEquals(100L, ar.readProcessedUpTo(g), "the horizon advances to the captured counter");
            assertEquals(40L, ar.getLastProcessedUpToLag(), "lag is measured before the cycle runs");
        }
    }

    // ---------------- tick routing ----------------

    @Test
    void tick_runsAFullBuildWhenNoSpaceStateExistsYet() {
        try (InMemoryTripleStore store = new InMemoryTripleStore()) {
            trustAccount(store, HASH, "a1", "https://example.org/alice", "pk1", "loaded");
            setLoadCounter(store, 9L);
            TrustStateRegistry.get().setCurrentHash(HASH);

            resolver().tick();

            assertEquals(SpacesVocab.forSpaceState(HASH, 9L), resolver().getCurrentSpaceStateGraph());
        }
    }

    @Test
    void tick_runsAFullBuildWhenTheTrustStateFlipped() {
        // The graph name embeds the trust hash, so a hash change must not be served
        // by an incremental cycle on the old state's graph.
        String otherHash = "0000000000000000000000000000000000000000000000000000000000000000";
        try (InMemoryTripleStore store = new InMemoryTripleStore()) {
            trustAccount(store, otherHash, "a1", "https://example.org/alice", "pk1", "loaded");
            setLoadCounter(store, 4L);
            IRI oldGraph = SpacesVocab.forSpaceState(HASH, 4L);
            resolver().flipPointer(oldGraph);
            resolver().writeProcessedUpTo(oldGraph, 4L);
            TrustStateRegistry.get().setCurrentHash(otherHash);

            resolver().tick();

            assertEquals(SpacesVocab.forSpaceState(otherHash, 4L),
                    resolver().getCurrentSpaceStateGraph(),
                    "a trust-state flip forces a full build into a fresh graph");
        }
    }

    @Test
    void tick_runsAnIncrementalCycleWhenTheTrustStateIsUnchanged() {
        try (InMemoryTripleStore store = new InMemoryTripleStore()) {
            IRI graph = SpacesVocab.forSpaceState(HASH, 3L);
            resolver().flipPointer(graph);
            resolver().writeProcessedUpTo(graph, 3L);
            setLoadCounter(store, 11L);
            TrustStateRegistry.get().setCurrentHash(HASH);

            resolver().tick();

            assertEquals(graph, resolver().getCurrentSpaceStateGraph(),
                    "the pointer stays put — this was an incremental cycle, not a rebuild");
            assertEquals(11L, resolver().readProcessedUpTo(graph), "the delta was processed");
        }
    }

    @Test
    void tick_isANoOpWithoutATrustState() {
        try (InMemoryTripleStore ignored = new InMemoryTripleStore()) {
            resolver().tick();
            assertNull(resolver().getCurrentSpaceStateGraph(),
                    "nothing can be built before a trust state is known");
        }
    }

    @Test
    void tick_isANoOpWhenSpacesAreDisabled() {
        try (InMemoryTripleStore ignored = new InMemoryTripleStore();
             MockedStatic<Utils> env = Mockito.mockStatic(Utils.class, Mockito.CALLS_REAL_METHODS)) {
            env.when(() -> Utils.getRawEnv("NANOPUB_QUERY_ENABLE_SPACES")).thenReturn("false");
            TrustStateRegistry.get().setCurrentHash(HASH);

            resolver().tick();
            resolver().periodicRebuildTick();
            resolver().cleanOrphans();

            assertNull(resolver().getCurrentSpaceStateGraph(),
                    "all three entry points must no-op while the flag is off");
        }
    }

    // ---------------- periodic rebuild ----------------

    @Test
    void periodicRebuildTick_isANoOpWhenTheFlagIsNotSet() {
        try (InMemoryTripleStore store = new InMemoryTripleStore()) {
            setLoadCounter(store, 5L);
            TrustStateRegistry.get().setCurrentHash(HASH);

            resolver().periodicRebuildTick();

            assertNull(resolver().getCurrentSpaceStateGraph(),
                    "no rebuild without the needsFullRebuild flag");
        }
    }

    @Test
    void periodicRebuildTick_rebuildsAndClearsTheFlag() {
        try (InMemoryTripleStore store = new InMemoryTripleStore()) {
            trustAccount(store, HASH, "a1", "https://example.org/alice", "pk1", "loaded");
            setLoadCounter(store, 5L);
            TrustStateRegistry.get().setCurrentHash(HASH);
            resolver().setNeedsFullRebuild();

            resolver().periodicRebuildTick();

            assertEquals(SpacesVocab.forSpaceState(HASH, 5L), resolver().getCurrentSpaceStateGraph());
            assertFalse(resolver().readNeedsFullRebuild(),
                    "the flag is cleared so the next pass doesn't rebuild again");
        }
    }

    @Test
    void periodicRebuildTick_defersWhenTheFlagIsSetButNoTrustStateIsKnown() {
        // Deferring (rather than clearing) matters: the rebuild still has to happen
        // once a trust state arrives.
        try (InMemoryTripleStore ignored = new InMemoryTripleStore()) {
            resolver().setNeedsFullRebuild();

            resolver().periodicRebuildTick();

            assertTrue(resolver().readNeedsFullRebuild(),
                    "the flag must survive so the rebuild is not lost");
        }
    }

    @Test
    void runIncrementalCycle_runsTheLateArrivalSweepWhenARoleDeclarationLandsInTheSameCycle() {
        // structuralAdds is what gates runDownstreamWithoutLoadFilter — the re-run of
        // every downstream tier with the load filter lifted, so a candidate whose
        // enabling event arrived in this very cycle still gets validated. A fresh
        // RoleDeclaration in the delta is one of the triggers; this exercises the
        // whole sweep (all fourteen tier templates) against a real SPARQL engine,
        // which is also the only test that proves they all parse and execute.
        try (InMemoryTripleStore store = new InMemoryTripleStore()) {
            IRI g = SpacesVocab.forSpaceState(HASH, 1L);
            resolver().writeProcessedUpTo(g, 10L);
            setLoadCounter(store, 30L);

            store.update(SPACES, """
                    INSERT DATA { GRAPH <%s> {
                      <http://example.org/rd1> a <%sRoleDeclaration> ;
                          <%sviaNanopub> <http://example.org/npRd> .
                    } }""".formatted(SpacesVocab.SPACES_GRAPH, NPA_NS, NPA_NS));
            store.update(SPACES, """
                    INSERT DATA { GRAPH <%s> { <http://example.org/npRd> <%shasLoadNumber> 25 } }"""
                    .formatted(NPA.GRAPH, NPA_NS));

            assertTrue(resolver().newRoleDeclarationsArrived(10L),
                    "precondition: the declaration is inside this cycle's delta");

            resolver().runIncrementalCycle(g);

            assertEquals(30L, resolver().readProcessedUpTo(g),
                    "the cycle completes and advances the horizon after the sweep");
        }
    }

    @Test
    void runDownstreamWithoutLoadFilter_executesEveryTierAndInsertsNothingOnAnEmptyGraph() {
        // Called directly so a failure points at the sweep rather than at the cycle
        // that schedules it. On a graph with no candidates every tier must be a
        // clean no-op — no accidental unconstrained INSERT.
        try (InMemoryTripleStore store = new InMemoryTripleStore()) {
            IRI g = SpacesVocab.forSpaceState(HASH, 1L);

            AuthorityResolver.TierInsertedTriples c = resolver().runDownstreamWithoutLoadFilter(g);

            assertEquals(0, c.admin + c.alias + c.presetAttachment + c.presetAssignmentRef
                            + c.attachment + c.maintainer + c.member + c.observer
                            + c.subSpace + c.subSpacePrefix + c.maintainedResource
                            + c.governingSpaceRef,
                    "no tier may insert anything when there are no candidates");
            assertEquals(0L, store.graphSize(SPACES, g));
        }
    }

    // ---------------- degraded-store fallbacks ----------------

    @Test
    void bookkeepingReads_throwWhenTheStoreIsUnavailable() {
        // These three reads decide what the next build does, and each has a sentinel
        // that also means something legitimate: null pointer = fresh install, counter
        // 0 = nothing loaded yet, processedUpTo -1 = graph never finished. Collapsing
        // a failed read onto the sentinel makes a degraded store look like one of
        // those and drives a destructive rebuild, so they must throw instead.
        try (MockedStatic<TripleStore> ts = Mockito.mockStatic(TripleStore.class)) {
            TripleStore broken = Mockito.mock(TripleStore.class);
            Mockito.when(broken.getRepoConnection(Mockito.anyString()))
                    .thenThrow(new RuntimeException("store down"));
            ts.when(TripleStore::get).thenReturn(broken);

            AuthorityResolver ar = resolver();
            IRI g = SpacesVocab.forSpaceState(HASH, 1L);

            assertThrows(AuthorityResolver.SpaceStateUnavailableException.class,
                    () -> ar.getCurrentSpaceStateGraph(), "pointer read must not report 'no pointer'");
            assertThrows(AuthorityResolver.SpaceStateUnavailableException.class,
                    () -> ar.getCurrentLoadCounter(), "counter read must not report 0");
            assertThrows(AuthorityResolver.SpaceStateUnavailableException.class,
                    () -> ar.readProcessedUpTo(g), "horizon read must not report -1");
        }
    }

    @Test
    void advisoryReads_returnSafeDefaultsWhenTheStoreIsUnavailable() {
        // These two only gate optional work — a rebuild the flag would have scheduled
        // anyway, and a diagnostic pointer lookup. Neither can destroy state on a
        // wrong answer, so a store blip stays a warning rather than an exception that
        // kills the periodic worker.
        try (MockedStatic<TripleStore> ts = Mockito.mockStatic(TripleStore.class)) {
            TripleStore broken = Mockito.mock(TripleStore.class);
            Mockito.when(broken.getRepoConnection(Mockito.anyString()))
                    .thenThrow(new RuntimeException("store down"));
            ts.when(TripleStore::get).thenReturn(broken);

            AuthorityResolver ar = resolver();

            assertFalse(ar.readNeedsFullRebuild(), "flag defaults to false");
            assertTrue(ar.readTrustRepoCurrentHash().isEmpty());
        }
    }

    @Test
    void tick_abortsOnAHardStoreOutageInsteadOfRebuilding() {
        // The outage has to stop the tick at the very first read. If it ever gets
        // past getCurrentSpaceStateGraph, tick() reads the null pointer as "no
        // current graph" and runs a full build against the same broken store, which
        // publishes an empty graph and drops the live one. Doing nothing this tick
        // and retrying on the next is always safe.
        try (MockedStatic<TripleStore> ts = Mockito.mockStatic(TripleStore.class)) {
            TripleStore broken = Mockito.mock(TripleStore.class);
            Mockito.when(broken.getRepoConnection(Mockito.anyString()))
                    .thenThrow(new RuntimeException("store down"));
            ts.when(TripleStore::get).thenReturn(broken);
            TrustStateRegistry.get().setCurrentHash(HASH);

            assertThrows(AuthorityResolver.SpaceStateUnavailableException.class,
                    () -> resolver().tick());
        }
    }

    // ---------------- invalidation orchestration ----------------

    @Test
    void applyInvalidations_reportsNoStructuralChangeOnAQuietGraph() {
        // All the check-WHERE ASKs execute here for real; on an empty graph none of
        // them may match, and the rebuild flag must stay down.
        try (InMemoryTripleStore ignored = new InMemoryTripleStore()) {
            IRI g = SpacesVocab.forSpaceState(HASH, 1L);

            assertFalse(resolver().applyInvalidations(g, 0L),
                    "nothing to invalidate on an empty state graph");
            assertFalse(resolver().readNeedsFullRebuild(),
                    "the rebuild flag is only raised by an actual structural delete");
        }
    }

    @Test
    void applyInvalidations_deletesARevokedAdminRowAndRaisesTheRebuildFlag() {
        // Issue #129: an admin RI whose grant is superseded by a newer admin-authored
        // npa:RoleRevocation must be removed, and — because admin RIs feed every
        // downstream tier — must schedule a full rebuild.
        try (InMemoryTripleStore store = new InMemoryTripleStore()) {
            IRI g = SpacesVocab.forSpaceState(HASH, 1L);
            String ref = "http://example.org/ref1";

            // Materialized state: the target admin RI, plus the revoker's own admin RI
            // and the AccountState linking the revoking pubkey to its agent.
            store.update(SPACES, """
                    INSERT DATA { GRAPH <%s> {
                      <http://example.org/ri-target> a <%sRoleInstantiation> ;
                          <%sforSpaceRef> <%s> ;
                          <%sinverseProperty> <%shasAdmin> ;
                          <%sforAgent> <https://example.org/victim> ;
                          <%sviaNanopub> <http://example.org/npGrant> .
                      <http://example.org/acct-rev> a <%sAccountState> ;
                          <%spubkey> "revpk" ;
                          <%sagent> <https://example.org/revoker> .
                      <http://example.org/ri-revoker> a <%sRoleInstantiation> ;
                          <%sforSpaceRef> <%s> ;
                          <%sinverseProperty> <%shasAdmin> ;
                          <%sforAgent> <https://example.org/revoker> .
                    } }
                    """.formatted(g, GEN_NS, NPA_NS, ref, NPA_NS, GEN_NS, NPA_NS, NPA_NS,
                    NPA_NS, NPA_NS, NPA_NS, GEN_NS, NPA_NS, ref, NPA_NS, GEN_NS, NPA_NS));

            // Extraction rows: the grant's timestamp and the newer revocation.
            store.update(SPACES, """
                    INSERT DATA { GRAPH <%s> {
                      <http://example.org/grantSrc> <%sviaNanopub> <http://example.org/npGrant> ;
                          <http://purl.org/dc/terms/created> "2025-01-01T00:00:00Z"^^<http://www.w3.org/2001/XMLSchema#dateTime> .
                      <%s> <%sspaceIri> <https://example.org/space1> .
                      <http://example.org/rev1> a <%sRoleRevocation> ;
                          <%sforSpace> <https://example.org/space1> ;
                          <%srevokedRole> <%sAdminRole> ;
                          <%sforAgent> <https://example.org/victim> ;
                          <%spubkeyHash> "revpk" ;
                          <%sviaNanopub> <http://example.org/npRev> ;
                          <http://purl.org/dc/terms/created> "2026-01-01T00:00:00Z"^^<http://www.w3.org/2001/XMLSchema#dateTime> .
                    } }
                    """.formatted(SpacesVocab.SPACES_GRAPH, NPA_NS, ref, NPA_NS, NPA_NS, NPA_NS, NPA_NS,
                    GEN_NS, NPA_NS, NPA_NS, NPA_NS, NPA_NS));

            store.update(SPACES, """
                    INSERT DATA { GRAPH <%s> { <http://example.org/npRev> <%shasLoadNumber> 20 } }"""
                    .formatted(NPA.GRAPH, NPA_NS));

            boolean structural = resolver().applyInvalidations(g, 10L);

            assertTrue(structural, "an admin revocation is a structural invalidation");
            assertFalse(store.ask(SPACES, "ASK { GRAPH <%s> { <http://example.org/ri-target> ?p ?o } }".formatted(g)),
                    "the revoked admin RI is deleted");
            assertTrue(store.ask(SPACES, "ASK { GRAPH <%s> { <http://example.org/ri-revoker> ?p ?o } }".formatted(g)),
                    "the revoker's own RI is untouched");
            assertTrue(resolver().readNeedsFullRebuild(),
                    "the rebuild flag bounds the staleness of rows derived from the removed admin");
        }
    }

    @Test
    void applyInvalidations_ignoresARevocationBelowTheLoadHorizon() {
        // Same fixture as above but with the revocation's load number at/below the
        // horizon: it was already processed in an earlier cycle, so this cycle's
        // delta must not re-fire on it.
        try (InMemoryTripleStore store = new InMemoryTripleStore()) {
            IRI g = SpacesVocab.forSpaceState(HASH, 1L);
            String ref = "http://example.org/ref1";

            store.update(SPACES, """
                    INSERT DATA { GRAPH <%s> {
                      <http://example.org/ri-target> a <%sRoleInstantiation> ;
                          <%sforSpaceRef> <%s> ;
                          <%sinverseProperty> <%shasAdmin> ;
                          <%sforAgent> <https://example.org/victim> ;
                          <%sviaNanopub> <http://example.org/npGrant> .
                      <http://example.org/acct-rev> a <%sAccountState> ;
                          <%spubkey> "revpk" ; <%sagent> <https://example.org/revoker> .
                      <http://example.org/ri-revoker> a <%sRoleInstantiation> ;
                          <%sforSpaceRef> <%s> ;
                          <%sinverseProperty> <%shasAdmin> ;
                          <%sforAgent> <https://example.org/revoker> .
                    } }
                    """.formatted(g, GEN_NS, NPA_NS, ref, NPA_NS, GEN_NS, NPA_NS, NPA_NS,
                    NPA_NS, NPA_NS, NPA_NS, GEN_NS, NPA_NS, ref, NPA_NS, GEN_NS, NPA_NS));
            store.update(SPACES, """
                    INSERT DATA { GRAPH <%s> {
                      <%s> <%sspaceIri> <https://example.org/space1> .
                      <http://example.org/rev1> a <%sRoleRevocation> ;
                          <%sforSpace> <https://example.org/space1> ;
                          <%srevokedRole> <%sAdminRole> ;
                          <%sforAgent> <https://example.org/victim> ;
                          <%spubkeyHash> "revpk" ;
                          <%sviaNanopub> <http://example.org/npRev> ;
                          <http://purl.org/dc/terms/created> "2026-01-01T00:00:00Z"^^<http://www.w3.org/2001/XMLSchema#dateTime> .
                    } }
                    """.formatted(SpacesVocab.SPACES_GRAPH, ref, NPA_NS, NPA_NS, NPA_NS, NPA_NS,
                    GEN_NS, NPA_NS, NPA_NS, NPA_NS));
            store.update(SPACES, """
                    INSERT DATA { GRAPH <%s> { <http://example.org/npRev> <%shasLoadNumber> 5 } }"""
                    .formatted(NPA.GRAPH, NPA_NS));

            assertFalse(resolver().applyInvalidations(g, 10L),
                    "a revocation at load 5 is outside the (10, ∞) delta");
            assertTrue(store.ask(SPACES, "ASK { GRAPH <%s> { <http://example.org/ri-target> ?p ?o } }".formatted(g)),
                    "the row survives this cycle");
        }
    }
}
