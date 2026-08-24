package com.knowledgepixels.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;

import org.eclipse.rdf4j.model.IRI;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.nanopub.vocabulary.NPA;

import com.google.common.hash.Hashing;
import com.knowledgepixels.query.vocabulary.NPAT;
import com.knowledgepixels.query.vocabulary.SpacesVocab;

/**
 * Tests for the pending-account mirror (issue #195): the step that turns
 * introduced-but-unapproved accounts into {@code npa:PendingAccountState} rows in the
 * space-state graph, so self-signed observer roles and own-profile view displays from
 * not-yet-approved users become visible.
 *
 * <p>Runs against real in-process repositories via {@link InMemoryTripleStore}, like
 * {@link AuthorityResolverPipelineTest}. The two exclusions (approved agent, approved
 * pubkey) and the authoritative-introduction filter are the security-relevant parts:
 * an introduction is self-asserted, so without them a rogue key could claim a settled
 * identity. The complementary guarantee — that a pending row grants no authority
 * anywhere — is asserted in {@link AuthorityResolverTierIsolationTest}.
 */
class AuthorityResolverPendingAccountTest {

    private static final String SPACES = "spaces";
    private static final String META = "meta";

    private static final String HASH =
            "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789";
    private static final IRI STATE = SpacesVocab.forSpaceState(HASH, 1L);

    private static final String ALICE = "https://example.org/alice";
    private static final String BOB = "https://example.org/bob";
    private static final String PK1 = "MIGfMA0-key-one";
    private static final String PK2 = "MIGfMA0-key-two";

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

    private static String sha256(String s) {
        return Hashing.sha256().hashString(s, StandardCharsets.UTF_8).toString();
    }

    // ---------------- fixtures ----------------

    /** An introduction nanopub as {@link NanopubLoader} stamps it into the meta repo. */
    private static void intro(InMemoryTripleStore store, String np, String agent,
                              String declaredPubkey, String signingPubkey, long loadNumber) {
        store.update(META, """
                INSERT DATA { GRAPH <%s> {
                  <%s> <%sisIntroductionOf> <%s> ;
                       <%sdeclaresPubkey>   "%s" ;
                       <%shasValidSignatureForPublicKeyHash> "%s" ;
                       <%shasLoadNumber> "%d"^^<http://www.w3.org/2001/XMLSchema#long> .
                } }
                """.formatted(NPA.GRAPH, np, NPA.NAMESPACE, agent, NPA.NAMESPACE, declaredPubkey,
                NPA.NAMESPACE, sha256(signingPubkey), NPA.NAMESPACE, loadNumber));
    }

    /** A retractor of an introduction, signed by the given key. */
    private static void retraction(InMemoryTripleStore store, String invNp, String targetNp,
                                   String signingPubkey) {
        store.update(META, """
                INSERT DATA { GRAPH <%s> {
                  <%s> <http://purl.org/nanopub/x/invalidates> <%s> ;
                       <%shasValidSignatureForPublicKeyHash> "%s" .
                } }
                """.formatted(NPA.GRAPH, invNp, targetNp, NPA.NAMESPACE, sha256(signingPubkey)));
    }

    /** An approved account row, as the trust mirror leaves it in the state graph. */
    private static void approvedAccount(InMemoryTripleStore store, String local,
                                        String agent, String pubkey) {
        store.update(SPACES, """
                INSERT DATA { GRAPH <%s> {
                  <https://example.org/acct/%s> a <%sAccountState> ;
                      <%sagent>  <%s> ;
                      <%spubkey> "%s" .
                } }
                """.formatted(STATE, local, NPA.NAMESPACE, NPA.NAMESPACE, agent,
                NPA.NAMESPACE, sha256(pubkey)));
    }

    /** @param pubkey the full key; the row stores its hash, as approved rows do */
    private static boolean hasPendingRow(InMemoryTripleStore store, String agent, String pubkey) {
        return store.ask(SPACES, """
                ASK { GRAPH <%s> {
                  ?row a <%sPendingAccountState> ;
                       <%sagent>  <%s> ;
                       <%spubkey> "%s" .
                } }
                """.formatted(STATE, NPA.NAMESPACE, NPA.NAMESPACE, agent, NPA.NAMESPACE, sha256(pubkey)));
    }

    private static long countPendingRows(InMemoryTripleStore store) {
        return store.count(SPACES, """
                SELECT (COUNT(DISTINCT ?row) AS ?c) WHERE { GRAPH <%s> { ?row a <%sPendingAccountState> } }
                """.formatted(STATE, NPA.NAMESPACE));
    }

    // ---------------- mirror behaviour ----------------

    @Test
    void mirrorsIntroducedButUnapprovedAccounts() {
        try (InMemoryTripleStore store = new InMemoryTripleStore()) {
            intro(store, "https://example.org/np1", ALICE, PK1, PK1, 7L);

            AuthorityResolver.PendingMirrorResult result = resolver().mirrorPendingAccounts(STATE, -1);

            assertEquals(1, result.rows(), "the unapproved introduced account is mirrored");
            assertEquals(7L, result.scannedUpTo(), "watermark advances to the highest load number seen");
            assertTrue(hasPendingRow(store, ALICE, PK1));
            assertTrue(store.ask(SPACES, """
                    ASK { GRAPH <%s> { ?row a <%sPendingAccountState> ;
                              <%strustStatus> <%sseen> ;
                              <%sviaNanopub>  <https://example.org/np1> . } }
                    """.formatted(STATE, NPA.NAMESPACE, NPA.NAMESPACE, NPA.NAMESPACE, NPA.NAMESPACE)),
                    "row carries its status and the introduction it came from");
            assertFalse(store.ask(SPACES, "ASK { GRAPH <%s> { ?row a <%sAccountState> } }"
                    .formatted(STATE, NPA.NAMESPACE)),
                    "a pending row is never a bare AccountState — that class is the identity "
                            + "binding every authority join resolves through");
        }
    }

    @Test
    void skipsAgentsThatAlreadyHaveAnApprovedAccount() {
        // A rogue introduction claiming an approved user's IRI must not add a second
        // key binding for them — approved identities are settled.
        try (InMemoryTripleStore store = new InMemoryTripleStore()) {
            approvedAccount(store, "a1", ALICE, PK1);
            intro(store, "https://example.org/np-rogue", ALICE, PK2, PK2, 3L);

            assertEquals(0, resolver().mirrorPendingAccounts(STATE, -1).rows());
            assertFalse(hasPendingRow(store, ALICE, PK2));
        }
    }

    @Test
    void skipsPubkeysThatAlreadyHaveAnApprovedAccount() {
        // An approved key must not be re-bound to a second identity by an introduction.
        try (InMemoryTripleStore store = new InMemoryTripleStore()) {
            approvedAccount(store, "a1", ALICE, PK1);
            intro(store, "https://example.org/np-claim", BOB, PK1, PK1, 3L);

            assertEquals(0, resolver().mirrorPendingAccounts(STATE, -1).rows());
            assertFalse(hasPendingRow(store, BOB, PK1));
        }
    }

    @Test
    void mirrorsOnlyTheKeyThatSignedTheIntroduction() {
        // Multi-key introduction: only the declared key that actually signed it is
        // authoritative evidence that the key belongs to the introduced agent.
        try (InMemoryTripleStore store = new InMemoryTripleStore()) {
            intro(store, "https://example.org/np1", ALICE, PK1, PK1, 4L);
            intro(store, "https://example.org/np1", ALICE, PK2, PK1, 4L);

            assertEquals(1, resolver().mirrorPendingAccounts(STATE, -1).rows());
            assertTrue(hasPendingRow(store, ALICE, PK1));
            assertFalse(hasPendingRow(store, ALICE, PK2), "co-declared non-signing key is not mirrored");
        }
    }

    @Test
    void honoursSelfRetractionButIgnoresForeignRetraction() {
        try (InMemoryTripleStore store = new InMemoryTripleStore()) {
            intro(store, "https://example.org/np-self", ALICE, PK1, PK1, 1L);
            intro(store, "https://example.org/np-other", BOB, PK2, PK2, 2L);
            retraction(store, "https://example.org/inv1", "https://example.org/np-self", PK1);
            retraction(store, "https://example.org/inv2", "https://example.org/np-other", PK1);

            assertEquals(1, resolver().mirrorPendingAccounts(STATE, -1).rows());
            assertFalse(hasPendingRow(store, ALICE, PK1), "own retraction removes the account");
            assertTrue(hasPendingRow(store, BOB, PK2),
                    "a foreign key must not be able to retract someone else's introduction (issue #112)");
        }
    }

    @Test
    void watermarkBoundsTheScanAndRerunsAreIdempotent() {
        try (InMemoryTripleStore store = new InMemoryTripleStore()) {
            intro(store, "https://example.org/np1", ALICE, PK1, PK1, 5L);

            AuthorityResolver.PendingMirrorResult first = resolver().mirrorPendingAccounts(STATE, -1);
            assertEquals(1, first.rows());

            // Nothing new above the watermark.
            assertEquals(0, resolver().mirrorPendingAccounts(STATE, first.scannedUpTo()).rows());

            // A newcomer above the watermark is picked up; the old row is not duplicated.
            intro(store, "https://example.org/np2", BOB, PK2, PK2, 9L);
            AuthorityResolver.PendingMirrorResult second =
                    resolver().mirrorPendingAccounts(STATE, first.scannedUpTo());
            assertEquals(1, second.rows());
            assertEquals(9L, second.scannedUpTo());

            // A full re-scan (as a full build does) must not duplicate anything either.
            assertEquals(0, resolver().mirrorPendingAccounts(STATE, -1).rows());
            assertEquals(2L, countPendingRows(store), "one row per (agent, pubkey)");
        }
    }

    @Test
    void ignoresNonIntroductionNanopubs() {
        try (InMemoryTripleStore store = new InMemoryTripleStore()) {
            store.update(META, """
                    INSERT DATA { GRAPH <%s> {
                      <https://example.org/np-other> <%sdeclaresPubkey> "%s" ;
                          <%shasValidSignatureForPublicKeyHash> "%s" ;
                          <%shasLoadNumber> "1"^^<http://www.w3.org/2001/XMLSchema#long> .
                    } }
                    """.formatted(NPA.GRAPH, NPA.NAMESPACE, PK1, NPA.NAMESPACE, sha256(PK1),
                    NPA.NAMESPACE));

            assertEquals(0, resolver().mirrorPendingAccounts(STATE, -1).rows());
        }
    }

    @Test
    void mirroredRowJoinsTheTierPassEndToEnd() {
        // The mirror and the tier must agree on what npa:pubkey holds: on a space-state
        // account row it is the pubkey *hash* (joined against npa:pubkeyHash in the
        // extraction graph), not the full key. Seeding both sides from the same full key
        // is what makes this test able to catch a mismatch the per-step tests cannot.
        try (InMemoryTripleStore store = new InMemoryTripleStore()) {
            intro(store, "https://example.org/np1", ALICE, PK1, PK1, 7L);
            seedObserverAssignment(store, sha256(PK1), ALICE);

            assertEquals(1, resolver().mirrorPendingAccounts(STATE, -1).rows());
            store.update(SPACES, AuthorityResolver.nonAdminTierUpdate(
                    STATE, -1, com.knowledgepixels.query.vocabulary.GEN.OBSERVER_ROLE,
                    AuthorityResolver.PUBLISHER_IS_SELF_PENDING, AuthorityResolver.PENDING_ROLE_STAMP));

            assertTrue(store.ask(SPACES, """
                    ASK { GRAPH <%s> {
                      ?ri a <%sRoleInstantiation> ;
                          <%sforSpaceRef> <https://example.org/ref1> ;
                          <%sforAgent>    <%s> ;
                          <%strustStatus> <%sseen> .
                    } }
                    """.formatted(STATE, com.knowledgepixels.query.vocabulary.GEN.NAMESPACE,
                    NPA.NAMESPACE, NPA.NAMESPACE, ALICE, NPA.NAMESPACE, NPA.NAMESPACE)),
                    "the self-signed observer role of a mirrored pending account materializes, "
                            + "stamped as awaiting approval");
        }
    }

    /**
     * Minimal validated-attachment fixture: one space ref, an observer-tier role
     * declaration, and a self-signed instantiation in the extraction graph.
     */
    private static void seedObserverAssignment(InMemoryTripleStore store, String pubkeyHash, String agent) {
        String gen = com.knowledgepixels.query.vocabulary.GEN.NAMESPACE;
        store.update(SPACES, """
                INSERT DATA { GRAPH <%s> {
                  <https://example.org/ref1> <%sspaceIri> <https://example.org/spaceS> .
                  <https://example.org/rd1> a <%sRoleDeclaration> ;
                      <%shasRoleType> <%sObserverRole> ;
                      <%srole> <https://example.org/roleX> ;
                      <%shasInverseProperty> <https://example.org/hasObserver> ;
                      <%sviaNanopub> <https://example.org/np_rd> .
                  <https://example.org/ri_o> a <%sRoleInstantiation> ;
                      <%sforSpace> <https://example.org/spaceS> ;
                      <%sforAgent> <%s> ;
                      <%sinverseProperty> <https://example.org/hasObserver> ;
                      <%spubkeyHash> "%s" ;
                      <%sviaNanopub> <https://example.org/np_o> .
                } }
                """.formatted(SpacesVocab.SPACES_GRAPH, NPA.NAMESPACE, NPA.NAMESPACE,
                NPA.NAMESPACE, gen, NPA.NAMESPACE, gen, NPA.NAMESPACE,
                gen, NPA.NAMESPACE, NPA.NAMESPACE, agent, NPA.NAMESPACE,
                NPA.NAMESPACE, pubkeyHash, NPA.NAMESPACE));
        store.update(SPACES, """
                INSERT DATA { GRAPH <%s> {
                  <https://example.org/np_o>  <%shasLoadNumber> "1"^^<http://www.w3.org/2001/XMLSchema#long> .
                  <https://example.org/np_rd> <%shasLoadNumber> "1"^^<http://www.w3.org/2001/XMLSchema#long> .
                } }
                """.formatted(NPA.GRAPH, NPA.NAMESPACE, NPA.NAMESPACE));
        store.update(SPACES, """
                INSERT DATA { GRAPH <%s> {
                  <https://example.org/ra1> a <%sRoleAssignment> ;
                      <%shasRole> <https://example.org/roleX> ;
                      <%sforSpaceRef> <https://example.org/ref1> ;
                      <%sforSpace> <https://example.org/spaceS> .
                } }
                """.formatted(STATE, gen, gen, NPA.NAMESPACE, NPA.NAMESPACE));
    }

    @Test
    void featureFlagOffSkipsTheMirrorEntirely() {
        try (InMemoryTripleStore store = new InMemoryTripleStore()) {
            intro(store, "https://example.org/np1", ALICE, PK1, PK1, 7L);
            try (MockedStatic<Utils> env = mockStatic(Utils.class, CALLS_REAL_METHODS)) {
                env.when(() -> Utils.getRawEnv("NANOPUB_QUERY_ENABLE_PENDING_ACCOUNTS")).thenReturn("false");

                AuthorityResolver.PendingMirrorResult result = resolver().mirrorPendingAccounts(STATE, -1);

                assertEquals(0, result.rows());
                assertEquals(-1L, result.scannedUpTo(), "watermark is untouched while disabled");
            }
            assertEquals(0L, countPendingRows(store));
        }
    }

    @Test
    void trustStateMirrorStillWritesOnlyApprovedRows() {
        // Guard against the pending step leaking into the approved path: the trust
        // mirror's own output shape is unchanged.
        try (InMemoryTripleStore store = new InMemoryTripleStore()) {
            store.update("trust", """
                    INSERT DATA { GRAPH <%s> {
                      <https://example.org/acct/t1> a <%sAccountState> ;
                          <%sagent> <%s> ; <%spubkey> "%s" ; <%strustStatus> <%sloaded> .
                    } }
                    """.formatted(NPAT.forHash(HASH), NPA.NAMESPACE, NPA.NAMESPACE, ALICE,
                    NPA.NAMESPACE, PK1, NPA.NAMESPACE, NPA.NAMESPACE));

            assertEquals(1, resolver().mirrorTrustState(HASH, STATE));
            assertEquals(0L, countPendingRows(store));
        }
    }
}
