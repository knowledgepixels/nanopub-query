package com.knowledgepixels.query.vocabulary;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Namespace;
import org.nanopub.vocabulary.VocabUtils;

/**
 * IRIs and prefix declarations from the KPXL "gen" terms vocabulary
 * (<a href="https://w3id.org/kpxl/gen/terms/">https://w3id.org/kpxl/gen/terms/</a>).
 *
 * <p>Only the IRIs needed by the current implementation are defined here. Additional
 * predicates (e.g. {@code gen:hasAdmin}, {@code gen:hasMaintainer}, {@code gen:isDisplayFor},
 * role-type IRIs) will be added as their detection cases are implemented.
 */
public class GEN {

    public static final String NAMESPACE = "https://w3id.org/kpxl/gen/terms/";
    public static final String PREFIX = "gen";
    public static final Namespace NS = VocabUtils.createNamespace(PREFIX, NAMESPACE);

    /**
     * Class IRI marking a nanopub as a Space-defining nanopub.
     */
    public static final IRI SPACE = VocabUtils.createIRI(NAMESPACE, "Space");

    /**
     * Predicate connecting a Space IRI to the URI of its root nanopub. For a root nanopub
     * itself, the object is the nanopub's own URI (resolved at trusty-URI minting time);
     * for an update, the object points back at the original root nanopub.
     */
    public static final IRI HAS_ROOT_DEFINITION = VocabUtils.createIRI(NAMESPACE, "hasRootDefinition");

}
