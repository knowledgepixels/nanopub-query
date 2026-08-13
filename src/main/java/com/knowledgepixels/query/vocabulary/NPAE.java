package com.knowledgepixels.query.vocabulary;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Namespace;
import org.nanopub.vocabulary.VocabUtils;

/**
 * Namespace declaration for endorsement-link IRIs used in nanopub-query.
 *
 * <p>Each agent-level endorsement edge in a mirrored trust state is identified
 * by {@code npae:<endorsementLinkHash>}, expanded from
 * {@code http://purl.org/nanopub/admin/endorsementlink/}. The hash is
 * {@code SHA-256(trustStateHash + "|" + fromAgent + "|" + toAgent + "|" + viaNanopub)},
 * so the IRI is stable within a snapshot, different across snapshots, and
 * identical for the per-pubkey duplicate rows of one agent-level edge (which
 * therefore collapse onto a single node).
 */
public class NPAE {

    public static final String NAMESPACE = "http://purl.org/nanopub/admin/endorsementlink/";
    public static final String PREFIX = "npae";
    public static final Namespace NS = VocabUtils.createNamespace(PREFIX, NAMESPACE);

    private NPAE() {
    }

    /**
     * Mints the endorsement-link IRI for the given hash.
     *
     * @param endorsementLinkHash SHA-256 hex of the composite
     *                            {@code trustStateHash + "|" + fromAgent + "|" + toAgent + "|" + viaNanopub}
     * @return the IRI {@code npae:<endorsementLinkHash>}
     */
    public static IRI forHash(String endorsementLinkHash) {
        return VocabUtils.createIRI(NAMESPACE, endorsementLinkHash);
    }

}
