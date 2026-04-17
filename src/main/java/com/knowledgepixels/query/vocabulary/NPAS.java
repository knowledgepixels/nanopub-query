package com.knowledgepixels.query.vocabulary;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Namespace;
import org.nanopub.vocabulary.VocabUtils;

/**
 * Namespace declaration for per-space graph IRIs in the {@code spaces} repo.
 *
 * <p>Each space has its own named graph in the {@code spaces} repo, identified
 * by {@code npas:<spaceRef>}, expanded from
 * {@code http://purl.org/nanopub/admin/space/}. The space ref has the form
 * {@code <rootNanopubId>_<SPACEIRIHASH>}; see
 * {@link com.knowledgepixels.query.SpaceRegistry} for construction.
 */
public class NPAS {

    public static final String NAMESPACE = "http://purl.org/nanopub/admin/space/";
    public static final String PREFIX = "npas";
    public static final Namespace NS = VocabUtils.createNamespace(PREFIX, NAMESPACE);

    private NPAS() {
    }

    /**
     * Mints the named-graph IRI for the given space ref.
     *
     * @param spaceRef the space ref of the form {@code <rootNanopubId>_<SPACEIRIHASH>}
     * @return the IRI {@code npas:<spaceRef>}
     */
    public static IRI forSpaceRef(String spaceRef) {
        return VocabUtils.createIRI(NAMESPACE, spaceRef);
    }

}
