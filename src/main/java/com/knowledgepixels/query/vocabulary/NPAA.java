package com.knowledgepixels.query.vocabulary;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Namespace;
import org.nanopub.vocabulary.VocabUtils;

/**
 * Namespace declaration for account-state IRIs used in nanopub-query.
 *
 * <p>Each account entry in a mirrored trust state is identified by
 * {@code npaa:<accountStateHash>}, expanded from
 * {@code http://purl.org/nanopub/admin/accountstate/}. The hash is
 * {@code SHA-256(trustStateHash + "|" + pubkey + "|" + agent)}, so the IRI
 * is stable within a snapshot and different across snapshots.
 */
public class NPAA {

    public static final String NAMESPACE = "http://purl.org/nanopub/admin/accountstate/";
    public static final String PREFIX = "npaa";
    public static final Namespace NS = VocabUtils.createNamespace(PREFIX, NAMESPACE);

    private NPAA() {
    }

    /**
     * Mints the account-state IRI for the given hash.
     *
     * @param accountStateHash SHA-256 hex of the composite
     *                         {@code trustStateHash + "|" + pubkey + "|" + agent}
     * @return the IRI {@code npaa:<accountStateHash>}
     */
    public static IRI forHash(String accountStateHash) {
        return VocabUtils.createIRI(NAMESPACE, accountStateHash);
    }

}
