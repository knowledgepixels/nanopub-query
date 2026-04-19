package com.knowledgepixels.query.vocabulary;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Namespace;
import org.nanopub.vocabulary.VocabUtils;

/**
 * Namespace declaration for extract IRIs in the {@code spaces} repo.
 *
 * <p>Each extract is identified by {@code npax:<extractHash>}, expanded from
 * {@code http://purl.org/nanopub/admin/extract/}. The hash is
 * {@code SHA-256(spaceRef + "|" + sourceNanopubUri + "|" + extractKindLocalName + "|" + payload)}
 * — deterministic, so re-extracting the same source nanopub for the same
 * space yields the same IRI (idempotent re-extraction; no duplicates).
 */
public class NPAX {

    public static final String NAMESPACE = "http://purl.org/nanopub/admin/extract/";
    public static final String PREFIX = "npax";
    public static final Namespace NS = VocabUtils.createNamespace(PREFIX, NAMESPACE);

    private NPAX() {
    }

    /**
     * Mints the extract IRI for the given hash.
     *
     * @param extractHash 64-hex-char SHA-256 of the extract's stable inputs
     * @return the IRI {@code npax:<extractHash>}
     */
    public static IRI forHash(String extractHash) {
        return VocabUtils.createIRI(NAMESPACE, extractHash);
    }

}
