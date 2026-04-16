package com.knowledgepixels.query.vocabulary;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Namespace;
import org.nanopub.vocabulary.VocabUtils;

/**
 * Namespace declaration for trust-state IRIs used in nanopub-query.
 *
 * <p>Each mirrored trust state is identified (and its named graph named) by
 * {@code npat:<trustStateHash>}, expanded from
 * {@code http://purl.org/nanopub/admin/truststate/}.
 */
public class NPAT {

    public static final String NAMESPACE = "http://purl.org/nanopub/admin/truststate/";
    public static final String PREFIX = "npat";
    public static final Namespace NS = VocabUtils.createNamespace(PREFIX, NAMESPACE);

    private NPAT() {
    }

    /**
     * Mints the trust-state IRI for the given hash.
     *
     * @param trustStateHash 64-hex-char hash advertised by the registry
     * @return the IRI {@code npat:<trustStateHash>}
     */
    public static IRI forHash(String trustStateHash) {
        return VocabUtils.createIRI(NAMESPACE, trustStateHash);
    }

}
