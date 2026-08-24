package com.knowledgepixels.query;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.junit.jupiter.api.Test;
import org.nanopub.vocabulary.NPA;
import org.nanopub.vocabulary.NPX;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #14: an invalidating nanopub propagated into the repos of the types of the
 * nanopub it invalidates must carry its {@code npa:hasValidSignatureForPublicKeyHash}
 * triple there, not only its full {@code npa:hasValidSignatureForPublicKey}.
 *
 * <p>Why it matters: the canonical retraction filter joins invalidator and target on
 * the <em>hash</em> ("both signed by the same key" — see
 * {@code AuthorityResolver#samePublisherClause}). An invalidator whose hash triple is
 * missing from a shard is invisible to that filter, so a retracted nanopub keeps being
 * returned by queries against that shard.
 *
 * <p>The two propagation directions used to disagree.
 * {@code loadInvalidateStatements} (invalidator loaded <em>after</em> its target)
 * always wrote both triples; {@link NanopubLoader#getInvalidatingStatements} — the
 * source for the reverse order, whose result is mirrored into every shard the target
 * is written to — emitted only the full pubkey. Whether a retraction took effect in a
 * type repo therefore depended on the order the two nanopubs happened to arrive in.
 */
class NanopubLoaderInvalidatorPubkeyHashTest {

    private static final ValueFactory VF = SimpleValueFactory.getInstance();

    private static final IRI TARGET = VF.createIRI("http://purl.org/np/RA" + "T".repeat(43));
    private static final IRI INVALIDATOR = VF.createIRI("http://purl.org/np/RA" + "I".repeat(43));
    private static final IRI OTHER_INVALIDATOR = VF.createIRI("http://purl.org/np/RA" + "O".repeat(43));

    private static final String PUBKEY = "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQCtest";
    private static final String OTHER_PUBKEY = "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQCother";

    /** Seeds {@code meta} with the admin triples the loader writes for one invalidator. */
    private static void seedInvalidator(InMemoryTripleStore store, IRI invalidator, IRI target, String pubkey) {
        store.update("meta", "INSERT DATA { GRAPH <" + NPA.GRAPH + "> {"
                + " <" + invalidator + "> <" + NPX.INVALIDATES + "> <" + target + "> ;"
                + " <" + NPA.HAS_VALID_SIGNATURE_FOR_PUBLIC_KEY + "> \"" + pubkey + "\" ."
                + " } }");
    }

    private static boolean contains(List<Statement> statements, IRI subj, IRI pred, String obj) {
        return statements.contains(VF.createStatement(subj, pred, VF.createLiteral(obj), NPA.GRAPH));
    }

    @Test
    void invalidatorMarkersCarryThePubkeyHash() {
        try (InMemoryTripleStore store = new InMemoryTripleStore()) {
            seedInvalidator(store, INVALIDATOR, TARGET, PUBKEY);

            List<Statement> markers = NanopubLoader.getInvalidatingStatements(TARGET);

            assertTrue(markers.contains(VF.createStatement(INVALIDATOR, NPX.INVALIDATES, TARGET, NPA.GRAPH)),
                    "the npx:invalidates marker itself must still be emitted");
            assertTrue(contains(markers, INVALIDATOR, NPA.HAS_VALID_SIGNATURE_FOR_PUBLIC_KEY, PUBKEY),
                    "the full pubkey must still be emitted");
            assertTrue(contains(markers, INVALIDATOR, NPA.HAS_VALID_SIGNATURE_FOR_PUBLIC_KEY_HASH, Utils.createHash(PUBKEY)),
                    "issue #14: the pubkey hash must be emitted alongside the full pubkey");
        }
    }

    /** The hash must be the same value the loader writes for the invalidator's own load. */
    @Test
    void hashMatchesTheOneTheLoaderWritesElsewhere() {
        try (InMemoryTripleStore store = new InMemoryTripleStore()) {
            seedInvalidator(store, INVALIDATOR, TARGET, PUBKEY);

            String emitted = NanopubLoader.getInvalidatingStatements(TARGET).stream()
                    .filter(st -> st.getPredicate().equals(NPA.HAS_VALID_SIGNATURE_FOR_PUBLIC_KEY_HASH))
                    .map(st -> st.getObject().stringValue())
                    .findFirst().orElseThrow();

            assertEquals(Utils.createHash(PUBKEY), emitted);
        }
    }

    @Test
    void eachInvalidatorGetsItsOwnHash() {
        try (InMemoryTripleStore store = new InMemoryTripleStore()) {
            seedInvalidator(store, INVALIDATOR, TARGET, PUBKEY);
            seedInvalidator(store, OTHER_INVALIDATOR, TARGET, OTHER_PUBKEY);

            List<Statement> markers = NanopubLoader.getInvalidatingStatements(TARGET);

            assertEquals(6, markers.size(), "three triples per invalidator");
            assertTrue(contains(markers, INVALIDATOR, NPA.HAS_VALID_SIGNATURE_FOR_PUBLIC_KEY_HASH, Utils.createHash(PUBKEY)));
            assertTrue(contains(markers, OTHER_INVALIDATOR, NPA.HAS_VALID_SIGNATURE_FOR_PUBLIC_KEY_HASH, Utils.createHash(OTHER_PUBKEY)));
        }
    }

    /** An invalidator of some other nanopub must not leak into this nanopub's markers. */
    @Test
    void unrelatedInvalidationsAreNotPickedUp() {
        try (InMemoryTripleStore store = new InMemoryTripleStore()) {
            seedInvalidator(store, INVALIDATOR, OTHER_INVALIDATOR, PUBKEY);

            assertTrue(NanopubLoader.getInvalidatingStatements(TARGET).isEmpty());
        }
    }

    /**
     * Shard eligibility is now one rule shared by the per-type fan-out, both
     * directions of the retractor propagation, and {@link ShardReconciler}. When the
     * reverse path applied it and the forward path didn't, a retractor could be
     * written into a type repo the regular loader never creates, and — the other way
     * round — be assumed already present in a type repo it had in fact skipped,
     * which is one more way its pubkey hash could go missing.
     */
    @Test
    void shardEligibilityExcludesLocallyMintedAndNonHttpTypes() {
        assertTrue(NanopubLoader.isShardedType(VF.createIRI("http://example.org/SomeType"), TARGET));
        assertTrue(NanopubLoader.isShardedType(VF.createIRI("https://example.org/SomeType"), TARGET));
        assertFalse(NanopubLoader.isShardedType(VF.createIRI(TARGET + "#SomeType"), TARGET),
                "a type minted in the nanopub's own namespace gets no shard");
        assertFalse(NanopubLoader.isShardedType(VF.createIRI("urn:example:SomeType"), TARGET),
                "non-http(s) types get no shard");
    }
}
