package com.knowledgepixels.query;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.http.HttpEntity;
import org.apache.http.StatusLine;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.eclipse.rdf4j.model.IRI;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.nanopub.vocabulary.NPA;

import com.knowledgepixels.query.vocabulary.NPAA;
import com.knowledgepixels.query.vocabulary.NPAE;
import com.knowledgepixels.query.vocabulary.NPAT;

/**
 * Tests for {@link TrustStateLoader}, covering the whole class: the pure
 * helpers (hashing, retention, canonical-name resolution), the HTTP fetch and
 * its degraded-registry branches, the {@code trust}-repo materialization and
 * retention pruning, the startup bootstrap, and the {@code maybeUpdate} entry
 * point that ties them together.
 *
 * <p>
 * Anything that touches the triple store runs against real in-process
 * repositories via {@link InMemoryTripleStore} rather than against asserted
 * SPARQL strings, so the triple shapes, the atomic pointer swap and the pruning
 * order are verified as executed behaviour. The HTTP path is driven through the
 * {@link TrustStateLoader#executeGet(String)} seam — see its javadoc for why
 * one is needed.
 *
 * <p>
 * Tests are hermetic: no test reaches the real registry or a real triple store.
 */
class TrustStateLoaderTest {

    private static final String TRUST = TrustStateLoader.TRUST_REPO;

    // Datatypes the loader produces via createLiteral on the boxed Java types.
    private static final String XSD_INT = "<http://www.w3.org/2001/XMLSchema#int>";
    private static final String XSD_LONG = "<http://www.w3.org/2001/XMLSchema#long>";
    private static final String XSD_DOUBLE = "<http://www.w3.org/2001/XMLSchema#double>";

    @BeforeEach
    void setUp() throws NoSuchFieldException, IllegalAccessException {
        // Reset the registry singleton between tests so the "current hash"
        // doesn't leak across test cases.
        Field instance = TrustStateRegistry.class.getDeclaredField("instance");
        instance.setAccessible(true);
        instance.set(null, null);
    }

    // ---------------- fixtures ----------------
    private static TrustStateSnapshot snapshot(String hash, long counter,
            TrustStateSnapshot.AccountEntry... accounts) {
        return new TrustStateSnapshot(hash, counter,
                Instant.parse("2026-01-01T00:00:00Z"), List.of(accounts));
    }

    /**
     * Shorthand for tests that don't care about the hash or counter.
     */
    private static TrustStateSnapshot snapshotOf(TrustStateSnapshot.AccountEntry... accounts) {
        return snapshot("h", 1L, accounts);
    }

    private static TrustStateSnapshot.AccountEntry approved(String pubkey, String agent) {
        return new TrustStateSnapshot.AccountEntry(pubkey, agent, "loaded",
                1, 2, 0.5, 1000L, null, null, null);
    }

    /**
     * The npa:AccountState IRI the loader will mint for this entry.
     */
    private static IRI accountIri(String trustHash, TrustStateSnapshot.AccountEntry a) {
        return NPAA.forHash(TrustStateLoader.accountStateHash(trustHash, a));
    }

    /**
     * Builds a mock HTTP response with the given status and body.
     */
    private static CloseableHttpResponse response(int status, String body) {
        CloseableHttpResponse resp = Mockito.mock(CloseableHttpResponse.class);
        StatusLine line = Mockito.mock(StatusLine.class);
        Mockito.when(line.getStatusCode()).thenReturn(status);
        Mockito.when(line.getReasonPhrase()).thenReturn("reason-" + status);
        Mockito.when(resp.getStatusLine()).thenReturn(line);

        HttpEntity entity = Mockito.mock(HttpEntity.class);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        try {
            Mockito.when(entity.getContent()).thenReturn(new ByteArrayInputStream(bytes));
        } catch (IOException impossible) {
            throw new AssertionError(impossible);
        }
        Mockito.when(entity.getContentLength()).thenReturn((long) bytes.length);
        Mockito.when(entity.isStreaming()).thenReturn(false);
        Mockito.when(resp.getEntity()).thenReturn(entity);
        return resp;
    }

    private static final String VALID_BODY = """
            {
              "trustStateHash": "abc123",
              "trustStateCounter": {"$numberLong": "18"},
              "createdAt": "2026-04-15T14:16:16.112094241Z[Etc/UTC]",
              "accounts": [
                {
                  "pubkey": "pk1",
                  "agent": "https://orcid.org/0000-0001-5118-256X",
                  "status": "loaded",
                  "depth": 1,
                  "pathCount": 1,
                  "ratio": 0.5,
                  "quota": 100
                }
              ]
            }
            """;

    // ---------------- maybeUpdate: early-return guards ----------------
    @Test
    void maybeUpdate_isNoOpForNullHash() {
        // Should not throw, should not change registry state.
        TrustStateLoader.maybeUpdate(null);
        assertFalse(TrustStateRegistry.get().getCurrentHash().isPresent());
    }

    @Test
    void maybeUpdate_isNoOpForEmptyHash() {
        TrustStateLoader.maybeUpdate("");
        assertFalse(TrustStateRegistry.get().getCurrentHash().isPresent());
    }

    @Test
    void maybeUpdate_isNoOpWhenHashEqualsCurrent() {
        // Pre-seed the registry as if a previous materialization had set this.
        TrustStateRegistry.get().setCurrentHash("abc123");
        // Same hash → no-op (no exception, no change).
        TrustStateLoader.maybeUpdate("abc123");
        assertEquals("abc123", TrustStateRegistry.get().getCurrentHash().orElseThrow());
    }

    // ---------------- accountStateHash ----------------
    @Test
    void accountStateHash_isDeterministicOverSameInputs() {
        TrustStateSnapshot.AccountEntry a = new TrustStateSnapshot.AccountEntry(
                "pk1", "https://orcid.org/0000-0001-5118-256X", "loaded",
                1, 1, 0.008, 100000L, null, null);
        String h1 = TrustStateLoader.accountStateHash("trustA", a);
        String h2 = TrustStateLoader.accountStateHash("trustA", a);
        assertEquals(h1, h2);
        // Known SHA-256 hex length
        assertEquals(64, h1.length());
    }

    @Test
    void accountStateHash_differsAcrossTrustStates() {
        // Same (pubkey, agent) under different trust states → different IRIs (by design).
        TrustStateSnapshot.AccountEntry a = new TrustStateSnapshot.AccountEntry(
                "pk1", "https://example.org/agent", "loaded", 1, 1, 0.5, 1000L, null, null);
        String h1 = TrustStateLoader.accountStateHash("trustA", a);
        String h2 = TrustStateLoader.accountStateHash("trustB", a);
        assertNotEquals(h1, h2);
    }

    @Test
    void accountStateHash_differsAcrossAgents() {
        TrustStateSnapshot.AccountEntry a1 = new TrustStateSnapshot.AccountEntry(
                "pk1", "https://example.org/agentA", "loaded", 1, 1, 0.5, 1000L, null, null);
        TrustStateSnapshot.AccountEntry a2 = new TrustStateSnapshot.AccountEntry(
                "pk1", "https://example.org/agentB", "loaded", 1, 1, 0.5, 1000L, null, null);
        assertNotEquals(
                TrustStateLoader.accountStateHash("trustA", a1),
                TrustStateLoader.accountStateHash("trustA", a2));
    }

    @Test
    void accountStateHash_differsAcrossPubkeys() {
        TrustStateSnapshot.AccountEntry a1 = new TrustStateSnapshot.AccountEntry(
                "pk1", "https://example.org/agent", "loaded", 1, 1, 0.5, 1000L, null, null);
        TrustStateSnapshot.AccountEntry a2 = new TrustStateSnapshot.AccountEntry(
                "pk2", "https://example.org/agent", "loaded", 1, 1, 0.5, 1000L, null, null);
        assertNotEquals(
                TrustStateLoader.accountStateHash("trustA", a1),
                TrustStateLoader.accountStateHash("trustA", a2));
    }

    // ---------------- effectiveRetention ----------------
    @Test
    void effectiveRetention_defaultsTo100() {
        // No env var set → default.
        assertEquals(100, TrustStateLoader.effectiveRetention());
    }

    @Test
    void effectiveRetention_readsEnvAndCoercesInvalidValuesToDefault() {
        try (MockedStatic<Utils> env = Mockito.mockStatic(Utils.class, Mockito.CALLS_REAL_METHODS)) {
            env.when(() -> Utils.getRawEnv("TRUST_STATE_LOCAL_RETENTION")).thenReturn("5");
            assertEquals(5, TrustStateLoader.effectiveRetention(), "valid value is honoured");

            // The plan rejects retention=0 (it would prune the state just written).
            env.when(() -> Utils.getRawEnv("TRUST_STATE_LOCAL_RETENTION")).thenReturn("0");
            assertEquals(TrustStateLoader.DEFAULT_LOCAL_RETENTION,
                    TrustStateLoader.effectiveRetention(), "0 is coerced to the default");

            env.when(() -> Utils.getRawEnv("TRUST_STATE_LOCAL_RETENTION")).thenReturn("-3");
            assertEquals(TrustStateLoader.DEFAULT_LOCAL_RETENTION,
                    TrustStateLoader.effectiveRetention(), "negative is coerced to the default");

            // Non-numeric falls through Utils.getEnvInt's own catch to the default.
            env.when(() -> Utils.getRawEnv("TRUST_STATE_LOCAL_RETENTION")).thenReturn("not-a-number");
            assertEquals(TrustStateLoader.DEFAULT_LOCAL_RETENTION,
                    TrustStateLoader.effectiveRetention(), "garbage is coerced to the default");
        }
    }

    // ---------------- canonical name resolution (#62) ----------------
    @Test
    void resolveCanonicalNames_picksRowWithMaxRatio() {
        // Same agent, two approved keys with different names. The MAX(ratio)
        // policy chooses the more-trusted key's stamped name, which is
        // semantically "the name from the agent's most-endorsed declaration".
        TrustStateSnapshot.AccountEntry low = new TrustStateSnapshot.AccountEntry(
                "pk1", "https://example.org/alice", "loaded", 1, 1, 0.10, 100L,
                "Old Alice", Instant.parse("2025-01-01T00:00:00Z"));
        TrustStateSnapshot.AccountEntry high = new TrustStateSnapshot.AccountEntry(
                "pk2", "https://example.org/alice", "loaded", 1, 1, 0.90, 100L,
                "Alice", Instant.parse("2024-01-01T00:00:00Z"));
        Map<String, String> names
                = TrustStateLoader.resolveCanonicalNames(snapshotOf(low, high));
        assertEquals("Alice", names.get("https://example.org/alice"));
    }

    @Test
    void resolveCanonicalNames_breaksTiesOnLexMinName() {
        // Equal ratio → MIN(name) lex tiebreak so the resolution is
        // deterministic across rebuilds and across MongoDB iteration order.
        TrustStateSnapshot.AccountEntry charlie = new TrustStateSnapshot.AccountEntry(
                "pk1", "https://example.org/agent", "loaded", 1, 1, 0.5, 100L,
                "Charlie", Instant.parse("2025-06-01T00:00:00Z"));
        TrustStateSnapshot.AccountEntry alice = new TrustStateSnapshot.AccountEntry(
                "pk2", "https://example.org/agent", "loaded", 1, 1, 0.5, 100L,
                "Alice", Instant.parse("2025-01-01T00:00:00Z"));
        Map<String, String> names
                = TrustStateLoader.resolveCanonicalNames(snapshotOf(charlie, alice));
        assertEquals("Alice", names.get("https://example.org/agent"));
    }

    @Test
    void resolveCanonicalNames_keepsTheIncumbentWhenALowerRatioRowFollows() {
        // Order-independence: the winner must not depend on which row the iteration
        // happens to see first, so the losing comparison arm needs covering too.
        TrustStateSnapshot.AccountEntry high = new TrustStateSnapshot.AccountEntry(
                "pk1", "https://example.org/agent", "loaded", 1, 1, 0.90, 100L,
                "Winner", Instant.parse("2025-01-01T00:00:00Z"));
        TrustStateSnapshot.AccountEntry low = new TrustStateSnapshot.AccountEntry(
                "pk2", "https://example.org/agent", "loaded", 1, 1, 0.10, 100L,
                "Loser", Instant.parse("2025-06-01T00:00:00Z"));
        // high first, low second — the reverse of resolveCanonicalNames_picksRowWithMaxRatio.
        assertEquals("Winner",
                TrustStateLoader.resolveCanonicalNames(snapshotOf(high, low))
                        .get("https://example.org/agent"));
    }

    @Test
    void resolveCanonicalNames_keepsTheIncumbentOnATieWithALexGreaterName() {
        // Tie on ratio, and the incoming name sorts after the incumbent's: the
        // incumbent stays. Together with resolveCanonicalNames_breaksTiesOnLexMinName
        // this pins the tiebreak as order-independent.
        TrustStateSnapshot.AccountEntry alice = new TrustStateSnapshot.AccountEntry(
                "pk1", "https://example.org/agent", "loaded", 1, 1, 0.5, 100L,
                "Alice", Instant.parse("2025-01-01T00:00:00Z"));
        TrustStateSnapshot.AccountEntry charlie = new TrustStateSnapshot.AccountEntry(
                "pk2", "https://example.org/agent", "loaded", 1, 1, 0.5, 100L,
                "Charlie", Instant.parse("2025-06-01T00:00:00Z"));
        assertEquals("Alice",
                TrustStateLoader.resolveCanonicalNames(snapshotOf(alice, charlie))
                        .get("https://example.org/agent"));
    }

    @Test
    void resolveCanonicalNames_skipsUnapprovedRows() {
        // A higher-ratio row with status=skipped or status=contested must not
        // beat an approved-but-lower-ratio row, otherwise rejected agents could
        // shadow the trust-graph's actual chosen name.
        TrustStateSnapshot.AccountEntry approved = new TrustStateSnapshot.AccountEntry(
                "pk1", "https://example.org/agent", "loaded", 1, 1, 0.10, 100L,
                "Approved Name", Instant.parse("2025-01-01T00:00:00Z"));
        TrustStateSnapshot.AccountEntry skipped = new TrustStateSnapshot.AccountEntry(
                "pk2", "https://example.org/agent", "skipped", 5, null, null, null,
                "Skipped Name", Instant.parse("2025-06-01T00:00:00Z"));
        Map<String, String> names
                = TrustStateLoader.resolveCanonicalNames(snapshotOf(approved, skipped));
        assertEquals("Approved Name", names.get("https://example.org/agent"));
    }

    @Test
    void resolveCanonicalNames_omitsAgentsWithNoName() {
        // Approved key, but the declaring intro had no foaf:name. No entry in
        // the result map → consumer materializer emits no foaf:name triple.
        TrustStateSnapshot.AccountEntry noName = new TrustStateSnapshot.AccountEntry(
                "pk1", "https://example.org/agent", "loaded", 1, 1, 0.5, 100L,
                null, null);
        Map<String, String> names = TrustStateLoader.resolveCanonicalNames(snapshotOf(noName));
        assertTrue(names.isEmpty(), "agent with no name across approved keys must not appear");
        assertNull(names.get("https://example.org/agent"));
    }

    @Test
    void resolveCanonicalNames_skipsApprovedRowsWithANameButNoRatio() {
        // ratio is the ranking key; a row without one cannot be compared, so it is
        // dropped rather than allowed to win by default.
        TrustStateSnapshot.AccountEntry noRatio = new TrustStateSnapshot.AccountEntry(
                "pk1", "https://example.org/agent", "loaded", 1, 1, null, 100L,
                "Unrankable", Instant.parse("2025-01-01T00:00:00Z"));
        assertTrue(TrustStateLoader.resolveCanonicalNames(snapshotOf(noRatio)).isEmpty());
    }

    @Test
    void resolveCanonicalNames_acceptsToLoadStatusToo() {
        // toLoad is the second authority-approving status alongside loaded.
        // Per APPROVED_STATUSES, both should contribute names.
        TrustStateSnapshot.AccountEntry toLoad = new TrustStateSnapshot.AccountEntry(
                "pk1", "https://example.org/agent", "toLoad", 1, 1, 0.5, 100L,
                "ToLoad Name", Instant.parse("2025-01-01T00:00:00Z"));
        Map<String, String> names = TrustStateLoader.resolveCanonicalNames(snapshotOf(toLoad));
        assertEquals("ToLoad Name", names.get("https://example.org/agent"));
    }

    // ---------------- fetchSnapshot ----------------
    //
    // Every failure branch here is a degraded-registry case, and each has to fail
    // the same way: return Optional.empty() without throwing, so the caller leaves
    // the registry hash unadvanced and the next poll simply retries. A throw would
    // escape into the polling loop; a non-empty return on a bad body would
    // materialize garbage.
    @Test
    void fetchSnapshot_parsesASuccessfulResponse() {
        try (MockedStatic<TrustStateLoader> loader
                = Mockito.mockStatic(TrustStateLoader.class, Mockito.CALLS_REAL_METHODS)) {
            CloseableHttpResponse resp = response(200, VALID_BODY);
            loader.when(() -> TrustStateLoader.executeGet(Mockito.anyString())).thenReturn(resp);

            Optional<TrustStateSnapshot> result = TrustStateLoader.fetchSnapshot("abc123");

            assertTrue(result.isPresent());
            assertEquals("abc123", result.get().trustStateHash());
            assertEquals(18L, result.get().trustStateCounter());
            assertEquals(1, result.get().accounts().size());
        }
    }

    @Test
    void fetchSnapshot_requestsTheHashUrlEncoded() {
        // The hash goes straight into a URL path segment; anything exotic must be
        // encoded rather than allowed to alter the path.
        try (MockedStatic<TrustStateLoader> loader
                = Mockito.mockStatic(TrustStateLoader.class, Mockito.CALLS_REAL_METHODS)) {
            CloseableHttpResponse resp = response(200, VALID_BODY);
            loader.when(() -> TrustStateLoader.executeGet(Mockito.anyString())).thenReturn(resp);

            TrustStateLoader.fetchSnapshot("a/b c");

            loader.verify(() -> TrustStateLoader.executeGet(
                    Mockito.argThat(url -> url.endsWith("trust-state/a%2Fb+c.json"))));
        }
    }

    @Test
    void fetchSnapshot_returnsEmptyOn404() {
        // The registry prunes old snapshots; a 404 is expected operation, not an error.
        try (MockedStatic<TrustStateLoader> loader
                = Mockito.mockStatic(TrustStateLoader.class, Mockito.CALLS_REAL_METHODS)) {
            CloseableHttpResponse resp = response(404, "");
            loader.when(() -> TrustStateLoader.executeGet(Mockito.anyString())).thenReturn(resp);

            assertTrue(TrustStateLoader.fetchSnapshot("prunedHash").isEmpty());
        }
    }

    @Test
    void fetchSnapshot_returnsEmptyOnServerError() {
        try (MockedStatic<TrustStateLoader> loader
                = Mockito.mockStatic(TrustStateLoader.class, Mockito.CALLS_REAL_METHODS)) {
            CloseableHttpResponse resp = response(500, "boom");
            loader.when(() -> TrustStateLoader.executeGet(Mockito.anyString())).thenReturn(resp);

            assertTrue(TrustStateLoader.fetchSnapshot("someHash").isEmpty());
        }
    }

    @Test
    void fetchSnapshot_returnsEmptyOnRedirectOrOtherNon2xx() {
        // The guard is a 2xx range check, not an equality test on 200.
        try (MockedStatic<TrustStateLoader> loader
                = Mockito.mockStatic(TrustStateLoader.class, Mockito.CALLS_REAL_METHODS)) {
            CloseableHttpResponse redirect = response(302, "");
            loader.when(() -> TrustStateLoader.executeGet(Mockito.anyString())).thenReturn(redirect);
            assertTrue(TrustStateLoader.fetchSnapshot("someHash").isEmpty());

            CloseableHttpResponse informational = response(199, "");
            loader.when(() -> TrustStateLoader.executeGet(Mockito.anyString())).thenReturn(informational);
            assertTrue(TrustStateLoader.fetchSnapshot("someHash").isEmpty());
        }
    }

    @Test
    void fetchSnapshot_acceptsAny2xx() {
        try (MockedStatic<TrustStateLoader> loader
                = Mockito.mockStatic(TrustStateLoader.class, Mockito.CALLS_REAL_METHODS)) {
            CloseableHttpResponse resp = response(299, VALID_BODY);
            loader.when(() -> TrustStateLoader.executeGet(Mockito.anyString())).thenReturn(resp);

            assertTrue(TrustStateLoader.fetchSnapshot("abc123").isPresent());
        }
    }

    @Test
    void fetchSnapshot_returnsEmptyOnIoFailure() {
        // A connection reset must not escape into the polling loop.
        try (MockedStatic<TrustStateLoader> loader
                = Mockito.mockStatic(TrustStateLoader.class, Mockito.CALLS_REAL_METHODS)) {
            loader.when(() -> TrustStateLoader.executeGet(Mockito.anyString()))
                    .thenThrow(new IOException("connection reset"));

            assertTrue(TrustStateLoader.fetchSnapshot("someHash").isEmpty());
        }
    }

    @Test
    void fetchSnapshot_returnsEmptyOnMalformedBody() {
        // A 200 carrying a body that isn't a valid envelope: the IllegalArgumentException
        // from the parser is caught here so the poll retries rather than crashing.
        try (MockedStatic<TrustStateLoader> loader
                = Mockito.mockStatic(TrustStateLoader.class, Mockito.CALLS_REAL_METHODS)) {
            CloseableHttpResponse resp = response(200, "{ this is not json");
            loader.when(() -> TrustStateLoader.executeGet(Mockito.anyString())).thenReturn(resp);

            assertTrue(TrustStateLoader.fetchSnapshot("someHash").isEmpty());
        }
    }

    @Test
    void fetchSnapshot_returnsEmptyWhenTheEnvelopeIsMissingRequiredFields() {
        try (MockedStatic<TrustStateLoader> loader
                = Mockito.mockStatic(TrustStateLoader.class, Mockito.CALLS_REAL_METHODS)) {
            CloseableHttpResponse resp = response(200, "{\"trustStateHash\": \"abc\"}");
            loader.when(() -> TrustStateLoader.executeGet(Mockito.anyString())).thenReturn(resp);

            assertTrue(TrustStateLoader.fetchSnapshot("abc").isEmpty());
        }
    }

    // ---------------- materialize: account triples ----------------
    @Test
    void materialize_writesAccountStateTriplesIntoTheTrustStateGraph() {
        try (InMemoryTripleStore store = new InMemoryTripleStore()) {
            TrustStateSnapshot.AccountEntry a = approved("pk1", "https://example.org/alice");
            TrustStateSnapshot s = snapshot("hashA", 7L, a);

            TrustStateLoader.materialize(s);

            IRI graph = NPAT.forHash("hashA");
            IRI acct = accountIri("hashA", a);
            // Datatypes are pinned deliberately: the boxed Java types the loader
            // passes to createLiteral decide them (Integer -> xsd:int, Double ->
            // xsd:double, Long -> xsd:long), and consumer queries that compare
            // against a differently-typed literal would silently not match.
            assertTrue(store.ask(TRUST, """
                    ASK { GRAPH <%s> {
                      <%s> a <%sAccountState> ;
                           <%sagent>       <https://example.org/alice> ;
                           <%spubkey>      "pk1" ;
                           <%strustStatus> <%sloaded> ;
                           <%sdepth>       "1"^^%s ;
                           <%spathCount>   "2"^^%s ;
                           <%sratio>       "0.5"^^%s ;
                           <%squota>       "1000"^^%s .
                    } }
                    """.formatted(graph, acct, NPA.NAMESPACE, NPA.NAMESPACE, NPA.NAMESPACE,
                    NPA.NAMESPACE, NPA.NAMESPACE, NPA.NAMESPACE, XSD_INT, NPA.NAMESPACE, XSD_INT,
                    NPA.NAMESPACE, XSD_DOUBLE, NPA.NAMESPACE, XSD_LONG)),
                    "every non-null account field must be materialized");
        }
    }

    @Test
    void materialize_omitsTriplesForNullStatsOfSkippedAccounts() {
        // status=skipped accounts carry null depth/pathCount/ratio/quota — the loader
        // must emit no triple at all rather than a null-ish literal.
        try (InMemoryTripleStore store = new InMemoryTripleStore()) {
            TrustStateSnapshot.AccountEntry skipped = new TrustStateSnapshot.AccountEntry(
                    "pk9", "https://example.org/mallory", "skipped",
                    null, null, null, null, null, null, null);
            TrustStateLoader.materialize(snapshot("hashS", 1L, skipped));

            IRI graph = NPAT.forHash("hashS");
            IRI acct = accountIri("hashS", skipped);
            for (String absent : List.of("depth", "pathCount", "ratio", "quota")) {
                assertFalse(store.ask(TRUST,
                        "ASK { GRAPH <%s> { <%s> <%s%s> ?o } }".formatted(
                                graph, acct, NPA.NAMESPACE, absent)),
                        "no npa:" + absent + " triple for an account whose value is null");
            }
            // The mandatory fields are still there — the row exists, just without stats.
            assertTrue(store.ask(TRUST, "ASK { GRAPH <%s> { <%s> <%strustStatus> <%sskipped> } }"
                    .formatted(graph, acct, NPA.NAMESPACE, NPA.NAMESPACE)));
        }
    }

    @Test
    void materialize_emitsViaNanopubOnlyWhenIntroIsPresentAndNonBlank() {
        // introNanopub is additive (nanopub-registry#117/#118): absent on older
        // snapshots. Blank is treated the same as absent — createIRI("") would
        // otherwise mint a bogus relative IRI.
        try (InMemoryTripleStore store = new InMemoryTripleStore()) {
            TrustStateSnapshot.AccountEntry withIntro = new TrustStateSnapshot.AccountEntry(
                    "pk1", "https://example.org/a", "loaded", 1, 1, 0.5, 1L, null, null,
                    "http://purl.org/np/RAintro");
            TrustStateSnapshot.AccountEntry blankIntro = new TrustStateSnapshot.AccountEntry(
                    "pk2", "https://example.org/b", "loaded", 1, 1, 0.5, 1L, null, null, "   ");
            TrustStateSnapshot.AccountEntry noIntro = new TrustStateSnapshot.AccountEntry(
                    "pk3", "https://example.org/c", "loaded", 1, 1, 0.5, 1L, null, null, null);
            TrustStateSnapshot s = snapshot("hashI", 1L, withIntro, blankIntro, noIntro);

            TrustStateLoader.materialize(s);

            IRI graph = NPAT.forHash("hashI");
            assertTrue(store.ask(TRUST, "ASK { GRAPH <%s> { <%s> <%sviaNanopub> <http://purl.org/np/RAintro> } }"
                    .formatted(graph, accountIri("hashI", withIntro), NPA.NAMESPACE)),
                    "present intro is mirrored");
            assertEquals(1L, store.count(TRUST,
                    "SELECT (COUNT(*) AS ?c) WHERE { GRAPH <%s> { ?s <%sviaNanopub> ?o } }"
                            .formatted(graph, NPA.NAMESPACE)),
                    "blank and null intros must not produce a viaNanopub triple");
        }
    }

    @Test
    void materialize_emitsCanonicalFoafNamePerAgent() {
        // One foaf:name per agent, not one per account row: two approved keys for
        // the same agent must collapse to the MAX(ratio) name.
        try (InMemoryTripleStore store = new InMemoryTripleStore()) {
            TrustStateSnapshot.AccountEntry low = new TrustStateSnapshot.AccountEntry(
                    "pk1", "https://example.org/alice", "loaded", 1, 1, 0.10, 1L,
                    "Old Alice", Instant.parse("2025-01-01T00:00:00Z"), null);
            TrustStateSnapshot.AccountEntry high = new TrustStateSnapshot.AccountEntry(
                    "pk2", "https://example.org/alice", "loaded", 1, 1, 0.90, 1L,
                    "Alice", Instant.parse("2024-01-01T00:00:00Z"), null);
            TrustStateLoader.materialize(snapshot("hashN", 1L, low, high));

            IRI graph = NPAT.forHash("hashN");
            assertEquals(1L, store.count(TRUST,
                    """
                    SELECT (COUNT(*) AS ?c) WHERE {
                      GRAPH <%s> { <https://example.org/alice> <http://xmlns.com/foaf/0.1/name> ?n }
                    }""".formatted(graph)),
                    "exactly one canonical name per agent");
            assertTrue(store.ask(TRUST, """
                    ASK { GRAPH <%s> {
                      <https://example.org/alice> <http://xmlns.com/foaf/0.1/name> "Alice"
                    } }""".formatted(graph)),
                    "the MAX(ratio) row supplies the canonical name");
        }
    }

    // ---------------- materialize: endorsement links (#184) ----------------
    @Test
    void materialize_writesEndorsementLinkTriplesIntoTheTrustStateGraph() {
        try (InMemoryTripleStore store = new InMemoryTripleStore()) {
            TrustStateSnapshot.EdgeEntry e = new TrustStateSnapshot.EdgeEntry(
                    "https://example.org/alice", "pk1",
                    "https://example.org/bob", "pk2",
                    "http://purl.org/np/RAendorse");
            TrustStateLoader.materialize(new TrustStateSnapshot("hashE", 1L,
                    Instant.parse("2026-01-01T00:00:00Z"),
                    List.of(approved("pk1", "https://example.org/alice"),
                            approved("pk2", "https://example.org/bob")),
                    List.of(e)));

            IRI graph = NPAT.forHash("hashE");
            IRI link = NPAE.forHash(TrustStateLoader.endorsementLinkHash("hashE", e));
            assertTrue(store.ask(TRUST, """
                    ASK { GRAPH <%s> {
                      <%s> a <%sEndorsementLink> ;
                           <%sfromAgent>  <https://example.org/alice> ;
                           <%stoAgent>    <https://example.org/bob> ;
                           <%sviaNanopub> <http://purl.org/np/RAendorse> .
                    } }
                    """.formatted(graph, link, NPA.NAMESPACE, NPA.NAMESPACE,
                    NPA.NAMESPACE, NPA.NAMESPACE)),
                    "every edge field must be materialized on the link node");
        }
    }

    @Test
    void materialize_collapsesPerPubkeyDuplicatesOfAnAgentLevelEdge() {
        // The registry lists edges per pubkey pair, so one agent-level edge can
        // arrive as several rows. The link IRI hashes only the agent-level
        // fields, so those rows must land on a single node.
        try (InMemoryTripleStore store = new InMemoryTripleStore()) {
            TrustStateSnapshot.EdgeEntry viaKey1 = new TrustStateSnapshot.EdgeEntry(
                    "https://example.org/alice", "pk1a",
                    "https://example.org/bob", "pk2",
                    "http://purl.org/np/RAendorse");
            TrustStateSnapshot.EdgeEntry viaKey2 = new TrustStateSnapshot.EdgeEntry(
                    "https://example.org/alice", "pk1b",
                    "https://example.org/bob", "pk2",
                    "http://purl.org/np/RAendorse");
            TrustStateLoader.materialize(new TrustStateSnapshot("hashD", 1L,
                    Instant.parse("2026-01-01T00:00:00Z"),
                    List.of(), List.of(viaKey1, viaKey2)));

            assertEquals(1L, store.count(TRUST,
                    "SELECT (COUNT(DISTINCT ?s) AS ?c) WHERE { GRAPH <%s> { ?s a <%sEndorsementLink> } }"
                            .formatted(NPAT.forHash("hashD"), NPA.NAMESPACE)),
                    "per-pubkey duplicate rows must collapse onto one link node");
        }
    }

    @Test
    void materialize_omitsEdgeViaNanopubWhenAbsentOrBlank() {
        // Same nullable-field policy as the account-level viaNanopub: no triple
        // rather than a bogus IRI minted from "" or whitespace.
        try (InMemoryTripleStore store = new InMemoryTripleStore()) {
            TrustStateSnapshot.EdgeEntry noVia = new TrustStateSnapshot.EdgeEntry(
                    "https://example.org/alice", "pk1",
                    "https://example.org/bob", "pk2", null);
            TrustStateSnapshot.EdgeEntry blankVia = new TrustStateSnapshot.EdgeEntry(
                    "https://example.org/bob", "pk2",
                    "https://example.org/carol", "pk3", "   ");
            TrustStateLoader.materialize(new TrustStateSnapshot("hashV", 1L,
                    Instant.parse("2026-01-01T00:00:00Z"),
                    List.of(), List.of(noVia, blankVia)));

            IRI graph = NPAT.forHash("hashV");
            assertEquals(2L, store.count(TRUST,
                    "SELECT (COUNT(DISTINCT ?s) AS ?c) WHERE { GRAPH <%s> { ?s a <%sEndorsementLink> } }"
                            .formatted(graph, NPA.NAMESPACE)),
                    "both edges are materialized");
            assertFalse(store.ask(TRUST,
                    "ASK { GRAPH <%s> { ?s a <%sEndorsementLink> ; <%sviaNanopub> ?o } }"
                            .formatted(graph, NPA.NAMESPACE, NPA.NAMESPACE)),
                    "no viaNanopub triple for null or blank values");
        }
    }

    @Test
    void materialize_writesNoEndorsementLinksForAnEdgelessSnapshot() {
        // Pre-#184 registries produce snapshots without edges; the back-compat
        // constructor defaults to an empty list and nothing edge-shaped appears.
        try (InMemoryTripleStore store = new InMemoryTripleStore()) {
            TrustStateLoader.materialize(
                    snapshot("hashO", 1L, approved("pk1", "https://example.org/a")));
            assertFalse(store.ask(TRUST,
                    "ASK { GRAPH <%s> { ?s a <%sEndorsementLink> } }"
                            .formatted(NPAT.forHash("hashO"), NPA.NAMESPACE)));
        }
    }

    // ---------------- materialize: metadata + pointer swap ----------------
    @Test
    void materialize_writesCrossStateMetadataIntoNpaGraph() {
        try (InMemoryTripleStore store = new InMemoryTripleStore()) {
            TrustStateLoader.materialize(
                    snapshot("hashM", 42L, approved("pk1", "https://example.org/a")));

            IRI ts = NPAT.forHash("hashM");
            assertTrue(store.ask(TRUST, """
                    ASK { GRAPH <%s> {
                      <%s> a <%sTrustState> ;
                           <%shasTrustStateHash>    "hashM" ;
                           <%shasTrustStateCounter> "42"^^%s ;
                           <%shasCreatedAt> "2026-01-01T00:00:00Z"^^<http://www.w3.org/2001/XMLSchema#dateTime> .
                    } }
                    """.formatted(NPA.GRAPH, ts, NPA.NAMESPACE, NPA.NAMESPACE,
                    NPA.NAMESPACE, XSD_LONG, NPA.NAMESPACE)),
                    "hash, counter and createdAt land in npa:graph as cross-state metadata");
        }
    }

    @Test
    void materialize_pointerSwapLeavesExactlyOneCurrentTrustState() {
        // The pointer is the entry point for every reader; two of them (or none)
        // would be a correctness bug, so the remove-then-add must be a true swap.
        try (InMemoryTripleStore store = new InMemoryTripleStore()) {
            TrustStateLoader.materialize(snapshot("hash1", 1L, approved("pk1", "https://example.org/a")));
            TrustStateLoader.materialize(snapshot("hash2", 2L, approved("pk1", "https://example.org/a")));

            assertEquals(1L, store.count(TRUST,
                    "SELECT (COUNT(*) AS ?c) WHERE { GRAPH <%s> { <%s> <%shasCurrentTrustState> ?o } }"
                            .formatted(NPA.GRAPH, NPA.THIS_REPO, NPA.NAMESPACE)),
                    "exactly one current-state pointer after a swap");
            assertTrue(store.ask(TRUST,
                    "ASK { GRAPH <%s> { <%s> <%shasCurrentTrustState> <%s> } }".formatted(
                            NPA.GRAPH, NPA.THIS_REPO, NPA.NAMESPACE, NPAT.forHash("hash2"))),
                    "pointer names the newest state");
            // The older state's data is retained (retention window), only the pointer moved.
            assertTrue(store.graphSize(TRUST, NPAT.forHash("hash1")) > 0,
                    "superseded state stays materialized until pruned");
        }
    }

    @Test
    void materialize_isIdempotentForTheSameSnapshot() {
        // Re-materializing the same hash rewrites identical triples; RDF set
        // semantics mean the graph must not grow.
        try (InMemoryTripleStore store = new InMemoryTripleStore()) {
            TrustStateSnapshot s = snapshot("hashDup", 3L,
                    approved("pk1", "https://example.org/a"),
                    approved("pk2", "https://example.org/b"));
            TrustStateLoader.materialize(s);
            long afterFirst = store.graphSize(TRUST, NPAT.forHash("hashDup"));
            TrustStateLoader.materialize(s);
            assertEquals(afterFirst, store.graphSize(TRUST, NPAT.forHash("hashDup")),
                    "re-materializing the same snapshot must not duplicate triples");
        }
    }

    // ---------------- retention pruning ----------------
    @Test
    void materialize_prunesStatesBeyondTheRetentionWindow() {
        // Retention of 2: after materializing three states, the lowest-counter one
        // must have both its named graph and its npa:graph metadata removed.
        try (InMemoryTripleStore store = new InMemoryTripleStore(); MockedStatic<Utils> env = Mockito.mockStatic(Utils.class, Mockito.CALLS_REAL_METHODS)) {
            env.when(() -> Utils.getRawEnv("TRUST_STATE_LOCAL_RETENTION")).thenReturn("2");

            for (int i = 1; i <= 3; i++) {
                TrustStateLoader.materialize(
                        snapshot("hash" + i, i, approved("pk" + i, "https://example.org/a" + i)));
            }

            assertEquals(0L, store.graphSize(TRUST, NPAT.forHash("hash1")),
                    "oldest state's named graph is cleared");
            assertFalse(store.ask(TRUST, "ASK { GRAPH <%s> { <%s> ?p ?o } }"
                    .formatted(NPA.GRAPH, NPAT.forHash("hash1"))),
                    "oldest state's metadata is removed from npa:graph too");
            assertTrue(store.graphSize(TRUST, NPAT.forHash("hash2")) > 0, "hash2 retained");
            assertTrue(store.graphSize(TRUST, NPAT.forHash("hash3")) > 0, "hash3 retained");
        }
    }

    @Test
    void materialize_keepsEverythingWhenWithinRetention() {
        try (InMemoryTripleStore store = new InMemoryTripleStore(); MockedStatic<Utils> env = Mockito.mockStatic(Utils.class, Mockito.CALLS_REAL_METHODS)) {
            env.when(() -> Utils.getRawEnv("TRUST_STATE_LOCAL_RETENTION")).thenReturn("10");

            for (int i = 1; i <= 3; i++) {
                TrustStateLoader.materialize(
                        snapshot("keep" + i, i, approved("pk" + i, "https://example.org/a" + i)));
            }
            for (int i = 1; i <= 3; i++) {
                assertTrue(store.graphSize(TRUST, NPAT.forHash("keep" + i)) > 0,
                        "keep" + i + " must survive when inside the retention window");
            }
        }
    }

    @Test
    void materialize_prunesByCounterNotByInsertionOrder() {
        // Pruning orders by npa:hasTrustStateCounter DESC, so materializing out of
        // counter order must still keep the highest counters.
        try (InMemoryTripleStore store = new InMemoryTripleStore(); MockedStatic<Utils> env = Mockito.mockStatic(Utils.class, Mockito.CALLS_REAL_METHODS)) {
            env.when(() -> Utils.getRawEnv("TRUST_STATE_LOCAL_RETENTION")).thenReturn("2");

            for (long c : List.of(50L, 10L, 30L)) {
                TrustStateLoader.materialize(
                        snapshot("c" + c, c, approved("pk", "https://example.org/a")));
            }

            assertTrue(store.graphSize(TRUST, NPAT.forHash("c50")) > 0, "highest counter kept");
            assertTrue(store.graphSize(TRUST, NPAT.forHash("c30")) > 0, "second-highest kept");
            assertEquals(0L, store.graphSize(TRUST, NPAT.forHash("c10")),
                    "lowest counter pruned even though it was not written first");
        }
    }

    // ---------------- bootstrap ----------------
    @Test
    void bootstrap_seedsRegistryFromThePersistedPointer() {
        try (InMemoryTripleStore ignored = new InMemoryTripleStore()) {
            // materialize() writes the pointer but deliberately does not touch the
            // in-memory registry (maybeUpdate does that), so this is exactly the
            // post-restart state: pointer on disk, registry cold.
            TrustStateLoader.materialize(
                    snapshot("bootHash", 1L, approved("pk1", "https://example.org/a")));
            assertTrue(TrustStateRegistry.get().getCurrentHash().isEmpty());

            TrustStateLoader.bootstrap();

            assertEquals("bootHash", TrustStateRegistry.get().getCurrentHash().orElseThrow(),
                    "bootstrap must recover the hash so the first poll is a no-op");
        }
    }

    @Test
    void bootstrap_isNoOpWhenNoPointerExists() {
        try (InMemoryTripleStore ignored = new InMemoryTripleStore()) {
            TrustStateLoader.bootstrap();
            assertTrue(TrustStateRegistry.get().getCurrentHash().isEmpty(),
                    "a fresh deployment has no pointer and must seed nothing");
        }
    }

    @Test
    void bootstrap_ignoresPointerOutsideTheTrustStateNamespace() {
        // Defensive: a pointer written by something else must not be sliced into a
        // bogus "hash" via substring().
        try (InMemoryTripleStore store = new InMemoryTripleStore()) {
            store.update(TRUST, """
                    INSERT DATA { GRAPH <%s> {
                      <%s> <%shasCurrentTrustState> <https://example.org/not-a-trust-state> .
                    } }""".formatted(NPA.GRAPH, NPA.THIS_REPO, NPA.NAMESPACE));

            TrustStateLoader.bootstrap();

            assertTrue(TrustStateRegistry.get().getCurrentHash().isEmpty(),
                    "unexpected pointer IRI must be rejected, not parsed");
        }
    }

    @Test
    void bootstrap_ignoresPointerWithEmptyHashSuffix() {
        try (InMemoryTripleStore store = new InMemoryTripleStore()) {
            store.update(TRUST, """
                    INSERT DATA { GRAPH <%s> {
                      <%s> <%shasCurrentTrustState> <%s> .
                    } }""".formatted(NPA.GRAPH, NPA.THIS_REPO, NPA.NAMESPACE, NPAT.NAMESPACE));

            TrustStateLoader.bootstrap();

            assertTrue(TrustStateRegistry.get().getCurrentHash().isEmpty(),
                    "namespace-only pointer has no hash to seed");
        }
    }

    @Test
    void bootstrap_swallowsStoreFailures() {
        // The trust repo may not exist yet on a fresh deployment. Bootstrap is
        // best-effort: it logs and falls through so the first poll rebuilds.
        try (MockedStatic<TripleStore> ts = Mockito.mockStatic(TripleStore.class)) {
            TripleStore broken = Mockito.mock(TripleStore.class);
            Mockito.when(broken.getRepoConnection(Mockito.anyString()))
                    .thenThrow(new RuntimeException("repo unavailable"));
            ts.when(TripleStore::get).thenReturn(broken);

            TrustStateLoader.bootstrap();  // must not propagate

            assertTrue(TrustStateRegistry.get().getCurrentHash().isEmpty());
        }
    }

    // ---------------- feature-flag gating ----------------
    @Test
    void bootstrapAndMaybeUpdate_areNoOpsWhenTrustStateIsDisabled() {
        try (InMemoryTripleStore store = new InMemoryTripleStore(); MockedStatic<Utils> env = Mockito.mockStatic(Utils.class, Mockito.CALLS_REAL_METHODS)) {
            // Seed a pointer that bootstrap would otherwise pick up.
            store.update(TRUST, """
                    INSERT DATA { GRAPH <%s> {
                      <%s> <%shasCurrentTrustState> <%sflagged> .
                    } }""".formatted(NPA.GRAPH, NPA.THIS_REPO, NPA.NAMESPACE, NPAT.NAMESPACE));
            env.when(() -> Utils.getRawEnv("NANOPUB_QUERY_ENABLE_TRUST_STATE")).thenReturn("false");

            TrustStateLoader.bootstrap();
            TrustStateLoader.maybeUpdate("someNewHash");

            assertTrue(TrustStateRegistry.get().getCurrentHash().isEmpty(),
                    "both entry points must no-op while the flag is off");
        }
    }

    // ---------------- maybeUpdate: full cycle ----------------
    @Test
    void maybeUpdate_materializesAndAdvancesTheRegistryOnAFreshHash() {
        try (InMemoryTripleStore store = new InMemoryTripleStore(); MockedStatic<TrustStateLoader> loader
                = Mockito.mockStatic(TrustStateLoader.class, Mockito.CALLS_REAL_METHODS)) {
            TrustStateSnapshot s = snapshot("newHash", 5L,
                    approved("pk1", "https://example.org/alice"));
            loader.when(() -> TrustStateLoader.fetchSnapshot("newHash")).thenReturn(Optional.of(s));

            TrustStateLoader.maybeUpdate("newHash");

            assertEquals("newHash", TrustStateRegistry.get().getCurrentHash().orElseThrow(),
                    "registry advances only after a successful materialization");
            assertTrue(store.graphSize(TRUST, NPAT.forHash("newHash")) > 0);
            assertTrue(store.ask(TRUST, "ASK { GRAPH <%s> { <%s> <%shasCurrentTrustState> <%s> } }"
                    .formatted(NPA.GRAPH, NPA.THIS_REPO, NPA.NAMESPACE, NPAT.forHash("newHash"))),
                    "pointer is swapped as part of the same update");
        }
    }

    @Test
    void maybeUpdate_rejectsSnapshotWhoseBodyHashDisagreesWithTheUrl() {
        // Integrity guard: fetching /trust-state/<X>.json but getting a body that
        // claims hash Y means something is serving the wrong snapshot. Nothing may
        // be materialized and the registry must stay put.
        try (InMemoryTripleStore store = new InMemoryTripleStore(); MockedStatic<TrustStateLoader> loader
                = Mockito.mockStatic(TrustStateLoader.class, Mockito.CALLS_REAL_METHODS)) {
            loader.when(() -> TrustStateLoader.fetchSnapshot("urlHash"))
                    .thenReturn(Optional.of(
                            snapshot("bodySaysSomethingElse", 1L,
                                    approved("pk1", "https://example.org/a"))));

            TrustStateLoader.maybeUpdate("urlHash");

            assertTrue(TrustStateRegistry.get().getCurrentHash().isEmpty(),
                    "hash mismatch must abort before materialization");
            assertEquals(0L, store.graphSize(TRUST, NPAT.forHash("bodySaysSomethingElse")),
                    "nothing is written when the integrity check fails");
        }
    }

    @Test
    void maybeUpdate_leavesRegistryUntouchedWhenTheSnapshotCannotBeFetched() {
        // 404 (pruned by the registry) or any I/O error → empty Optional. The
        // registry must not advance, so the next poll retries.
        try (InMemoryTripleStore ignored = new InMemoryTripleStore(); MockedStatic<TrustStateLoader> loader
                = Mockito.mockStatic(TrustStateLoader.class, Mockito.CALLS_REAL_METHODS)) {
            loader.when(() -> TrustStateLoader.fetchSnapshot("goneHash"))
                    .thenReturn(Optional.empty());

            TrustStateLoader.maybeUpdate("goneHash");

            assertTrue(TrustStateRegistry.get().getCurrentHash().isEmpty(),
                    "an unfetchable snapshot must not advance the registry");
        }
    }

    @Test
    void maybeUpdate_doesNotAdvanceRegistryWhenMaterializationThrows() {
        // materialize() failing (e.g. the store is down mid-transaction) is caught and
        // logged; the registry must stay behind so the next poll retries the same hash.
        try (MockedStatic<TripleStore> ts = Mockito.mockStatic(TripleStore.class); MockedStatic<TrustStateLoader> loader
                = Mockito.mockStatic(TrustStateLoader.class, Mockito.CALLS_REAL_METHODS)) {
            TripleStore broken = Mockito.mock(TripleStore.class);
            Mockito.when(broken.getRepoConnection(Mockito.anyString()))
                    .thenThrow(new RuntimeException("store down"));
            ts.when(TripleStore::get).thenReturn(broken);
            loader.when(() -> TrustStateLoader.fetchSnapshot("failHash"))
                    .thenReturn(Optional.of(
                            snapshot("failHash", 1L, approved("pk1", "https://example.org/a"))));

            TrustStateLoader.maybeUpdate("failHash");  // must not propagate

            assertTrue(TrustStateRegistry.get().getCurrentHash().isEmpty(),
                    "a failed materialization must leave the hash unadvanced for retry");
        }
    }
}
