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
 * npax:<hash> a npa:RoleAssertion ;
 *             npa:rolePredicate gen:hasAdmin ;
 *             npa:roleDirection npa:inverse ;
 *             npa:agent         <orcid:…> ;
 *             npa:viaNanopub    <sourceNp> .
 * }</pre>
 *
 * <p>The role IRI is intentionally absent from PR-B1 extracts — Nanodash's
 * roles are declared in published role-definition nanopubs (e.g. the global
 * "Build-in admin role" nanopub), not in hardcoded class IRIs. PR-B2 will
 * extract those role definitions and add an {@code npa:role} link to role
 * assertions so consumer queries can group by role without enumerating
 * predicates.
 *
 * <p>Class IRIs discriminate the kind directly via {@code rdf:type} (mirroring
 * the trust-state pattern with {@code npa:TrustState} / {@code npa:AccountState}).
 *
 * <p>Note on naming: the validated, materialized counterpart of a role
 * assertion is {@code npa:RoleAssignment} (see {@link SpaceAuthority}). The
 * extract layer uses {@code npa:RoleAssertion} ("someone asserted this") to
 * keep the validated name {@code npa:RoleAssignment} ("this assignment is
 * resolved/valid") clean for consumer queries.
 *
 * <p>See {@code doc/plan-space-repositories.md}.
 */
public class SpaceExtract {

    /**
     * Extract class: an assertion that an agent has been assigned a role for a
     * space, via some role property (built-in like {@code gen:hasAdmin} or
     * learned from a role-definition nanopub). Carries
     * {@link #ROLE_PREDICATE}, {@link #ROLE_DIRECTION}, and
     * {@link SpaceAuthority#AGENT}. The materializer resolves
     * predicate-to-role and validates publisher entitlement separately.
     */
    public static final IRI ROLE_ASSERTION = createIRI("RoleAssertion");

    /** Extract class: a profile field about the Space IRI (carries {@link #FIELD_KEY} + {@link #FIELD_VALUE}). */
    public static final IRI PROFILE_FIELD = createIRI("ProfileField");

    /** Predicate of the role-property used in the underlying assignment triple. */
    public static final IRI ROLE_PREDICATE = createIRI("rolePredicate");

    /**
     * Direction of the role-property in the underlying assignment triple — one of
     * {@link #REGULAR} ({@code <member> <predicate> <space>}) or {@link #INVERSE}
     * ({@code <space> <predicate> <member>}).
     */
    public static final IRI ROLE_DIRECTION = createIRI("roleDirection");

    /** {@link #ROLE_DIRECTION} value: regular — assignment triple is {@code <member> <predicate> <space>}. */
    public static final IRI REGULAR = createIRI("regular");

    /** {@link #ROLE_DIRECTION} value: inverse — assignment triple is {@code <space> <predicate> <member>}. */
    public static final IRI INVERSE = createIRI("inverse");

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
