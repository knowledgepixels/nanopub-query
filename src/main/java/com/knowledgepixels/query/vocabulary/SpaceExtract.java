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
 * and produce the validated authority view. Each extract has the shape:
 *
 * <pre>{@code
 * npax:<hash> a npa:Extract ;
 *             npa:extractKind <kind> ;
 *             npa:viaNanopub  <sourceNp> ;
 *             … kind-specific payload predicates … .
 * }</pre>
 *
 * <p>Kind individuals enumerate what the extract represents (admin grant,
 * profile field, etc.). Payload predicates (e.g. {@link #FIELD_KEY},
 * {@link #FIELD_VALUE}, plus shared {@link SpaceAuthority#AGENT}) carry the
 * kind-specific detail.
 *
 * <p>See {@code doc/plan-space-repositories.md}.
 */
public class SpaceExtract {

    /** RDF type for extract objects. */
    public static final IRI EXTRACT = createIRI("Extract");

    /** Tags an {@link #EXTRACT} object with one of the extract-kind individuals below. */
    public static final IRI EXTRACT_KIND = createIRI("extractKind");

    /** Extract kind: a {@code gen:hasAdmin} grant (object = granted agent IRI in {@link SpaceAuthority#AGENT}). */
    public static final IRI ADMIN_GRANT = createIRI("adminGrant");

    /** Extract kind: a profile field about the Space IRI (carries {@link #FIELD_KEY} + {@link #FIELD_VALUE}). */
    public static final IRI PROFILE_FIELD = createIRI("profileField");

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
