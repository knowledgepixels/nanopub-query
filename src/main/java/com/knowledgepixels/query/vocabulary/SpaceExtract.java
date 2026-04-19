package com.knowledgepixels.query.vocabulary;

import org.eclipse.rdf4j.model.IRI;
import org.nanopub.vocabulary.NPA;
import org.nanopub.vocabulary.VocabUtils;

/**
 * IRIs in the {@code npa:} namespace used for <em>extract</em> objects in a
 * space's named graph in the {@code spaces} repo.
 *
 * <p>Extracts are the loader's per-source-nanopub contributions — the inputs
 * the materializer (see {@link SpaceAuthority}) iterates to compute closures
 * and produce the validated authority view. Each extract is an instance of one
 * extract-kind class:
 *
 * <pre>{@code
 * npax:<hash> a npa:AdminGrant ;
 *             npa:viaNanopub <sourceNp> ;
 *             … kind-specific payload predicates … .
 * }</pre>
 *
 * <p>Class IRIs discriminate the kind directly via {@code rdf:type} (mirroring
 * the trust-state pattern with {@code npa:TrustState} / {@code npa:AccountState}).
 * Payload predicates ({@link #FIELD_KEY}, {@link #FIELD_VALUE}, plus shared
 * {@link SpaceAuthority#AGENT}) carry the kind-specific detail.
 *
 * <p>See {@code doc/plan-space-repositories.md}.
 */
public class SpaceExtract {

    /** Extract class: a {@code gen:hasAdmin} grant (granted agent in {@link SpaceAuthority#AGENT}). */
    public static final IRI ADMIN_GRANT = createIRI("AdminGrant");

    /** Extract class: a profile field about the Space IRI (carries {@link #FIELD_KEY} + {@link #FIELD_VALUE}). */
    public static final IRI PROFILE_FIELD = createIRI("ProfileField");

    /** Predicate of the extracted profile triple (e.g. {@code dcterms:description}, {@code owl:sameAs}). */
    public static final IRI FIELD_KEY = createIRI("fieldKey");

    /** Object of the extracted profile triple — IRI or literal. */
    public static final IRI FIELD_VALUE = createIRI("fieldValue");

    private SpaceExtract() {
    }

    private static IRI createIRI(String localName) {
        return VocabUtils.createIRI(NPA.NAMESPACE, localName);
    }

}
