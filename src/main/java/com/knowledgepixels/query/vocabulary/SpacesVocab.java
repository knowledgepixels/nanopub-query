package com.knowledgepixels.query.vocabulary;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.nanopub.vocabulary.NPA;

/**
 * IRIs, prefixes and subject-minting helpers used by the space-extraction layer
 * (see {@code doc/design-space-repositories.md}).
 *
 * <p>Every extraction entry in {@code npa:spacesGraph} has a dedicated subject IRI
 * derived from the originating nanopub's trusty-URI artifact code, so subjects
 * never collide with user nanopub IRIs, role IRIs, or anything else a nanopub
 * might declare types on. Prefixes:
 *
 * <ul>
 *   <li>{@link #NPAS_NAMESPACE} ({@code npas:}) — {@link #forSpaceRef(String) space-ref} IRIs for aggregate {@link #SPACE_REF} entries.
 *   <li>{@link #NPADEF_NAMESPACE} ({@code npadef:}) — {@link #forSpaceDefinition(String) per-contributor} {@link #SPACE_DEFINITION} entries.
 *   <li>{@link #NPARI_NAMESPACE} ({@code npari:}) — {@link #forRoleInstantiation(String) role-instantiation} entries.
 *   <li>{@link #NPARA_NAMESPACE} ({@code npara:}) — {@link #forRoleAssignment(String) role-attachment} entries (from {@code gen:hasRole} nanopubs).
 *   <li>{@link #NPARD_NAMESPACE} ({@code npard:}) — {@link #forRoleDeclaration(String) role-declaration} entries (from {@code gen:SpaceMemberRole} nanopubs).
 *   <li>{@link #NPAINV_NAMESPACE} ({@code npainv:}) — {@link #forInvalidation(String) invalidation} entries.
 *   <li>{@link #NPASS_NAMESPACE} ({@code npass:}) — space-state graph IRIs (used by the materializer in a later PR).
 * </ul>
 */
public final class SpacesVocab {

    private static final ValueFactory vf = SimpleValueFactory.getInstance();

    /** Namespace for aggregate space-ref entries ({@code npas:<spaceRef>}). */
    public static final String NPAS_NAMESPACE = "http://purl.org/nanopub/admin/space/";
    /** Namespace for per-contributor space-definition entries ({@code npadef:<artifactCode>}). */
    public static final String NPADEF_NAMESPACE = "http://purl.org/nanopub/admin/spacedef/";
    /** Namespace for role-instantiation entries ({@code npari:<artifactCode>}). */
    public static final String NPARI_NAMESPACE = "http://purl.org/nanopub/admin/roleinst/";
    /** Namespace for role-attachment entries ({@code npara:<artifactCode>}). */
    public static final String NPARA_NAMESPACE = "http://purl.org/nanopub/admin/roleassign/";
    /** Namespace for role-declaration entries ({@code npard:<artifactCode>}). */
    public static final String NPARD_NAMESPACE = "http://purl.org/nanopub/admin/roledecl/";
    /** Namespace for invalidation entries ({@code npainv:<artifactCode>}). */
    public static final String NPAINV_NAMESPACE = "http://purl.org/nanopub/admin/invalidation/";
    /** Namespace for space-state graph IRIs ({@code npass:<trustStateHash>_<loadCounter>}). */
    public static final String NPASS_NAMESPACE = "http://purl.org/nanopub/admin/spacestate/";

    // -------- RDF types on extraction entries --------

    /** RDF type for the aggregate per-space entry keyed by its space ref. */
    public static final IRI SPACE_REF = vf.createIRI(NPA.NAMESPACE, "SpaceRef");

    /** RDF type for the per-contributor space-definition entry. */
    public static final IRI SPACE_DEFINITION = vf.createIRI(NPA.NAMESPACE, "SpaceDefinition");

    /** RDF type for the summarized role-definition entry. */
    public static final IRI ROLE_DECLARATION = vf.createIRI(NPA.NAMESPACE, "RoleDeclaration");

    /** RDF type for an invalidation event recorded as add-only data. */
    public static final IRI INVALIDATION = vf.createIRI(NPA.NAMESPACE, "Invalidation");

    // -------- Properties on extraction entries --------

    /** Links a space-ref aggregate to its user-facing Space IRI. */
    public static final IRI SPACE_IRI = vf.createIRI(NPA.NAMESPACE, "spaceIri");

    /** Links a space-ref aggregate to its root nanopub URI. */
    public static final IRI ROOT_NANOPUB = vf.createIRI(NPA.NAMESPACE, "rootNanopub");

    /** Links a {@link #SPACE_REF} to each contributing nanopub URI (root + updates). */
    public static final IRI HAS_DEFINITION = vf.createIRI(NPA.NAMESPACE, "hasDefinition");

    /** Links a {@link #SPACE_DEFINITION} back to its parent {@link #SPACE_REF}. */
    public static final IRI FOR_SPACE_REF = vf.createIRI(NPA.NAMESPACE, "forSpaceRef");

    /** Trust-seed admin agents attached to a root {@link #SPACE_DEFINITION}. */
    public static final IRI HAS_ROOT_ADMIN = vf.createIRI(NPA.NAMESPACE, "hasRootAdmin");

    /** Links any extraction entry to the nanopub it was derived from. */
    public static final IRI VIA_NANOPUB = vf.createIRI(NPA.NAMESPACE, "viaNanopub");

    /** Links an extraction entry to its target space by Space IRI. */
    public static final IRI FOR_SPACE = vf.createIRI(NPA.NAMESPACE, "forSpace");

    /** Links an extraction entry to the assigned/admin'd agent (multi-valued for root admin lists). */
    public static final IRI FOR_AGENT = vf.createIRI(NPA.NAMESPACE, "forAgent");

    /** The "regular" direction predicate used in the source assertion (space &rarr; agent). */
    public static final IRI REGULAR_PROPERTY = vf.createIRI(NPA.NAMESPACE, "regularProperty");

    /** The "inverse" direction predicate used in the source assertion (agent &rarr; space). */
    public static final IRI INVERSE_PROPERTY = vf.createIRI(NPA.NAMESPACE, "inverseProperty");

    /** Literal pubkey hash stamped alongside {@link org.nanopub.vocabulary.NPX#SIGNED_BY}. */
    public static final IRI PUBKEY_HASH = vf.createIRI(NPA.NAMESPACE, "pubkeyHash");

    /** Links a {@link #ROLE_DECLARATION} to the actual role IRI embedded in the defining nanopub. */
    public static final IRI ROLE = vf.createIRI(NPA.NAMESPACE, "role");

    /** Tier class of a {@link #ROLE_DECLARATION}: gen:MaintainerRole / MemberRole / ObserverRole. */
    public static final IRI HAS_ROLE_TYPE = vf.createIRI(NPA.NAMESPACE, "hasRoleType");

    /** Links an {@link #INVALIDATION} entry to the nanopub it invalidates. */
    public static final IRI INVALIDATES = vf.createIRI(NPA.NAMESPACE, "invalidates");

    // -------- Named graphs & repo pointers --------

    /** Named graph in the {@code spaces} repo that holds all extraction triples. */
    public static final IRI SPACES_GRAPH = vf.createIRI(NPA.NAMESPACE, "spacesGraph");

    /** Pointer predicate for the currently-active space-state graph. */
    public static final IRI HAS_CURRENT_SPACE_STATE = vf.createIRI(NPA.NAMESPACE, "hasCurrentSpaceState");

    /** Repo-wide counter tracking the highest load number seen by the extractor. */
    public static final IRI CURRENT_LOAD_COUNTER = vf.createIRI(NPA.NAMESPACE, "currentLoadCounter");

    /** Load-number horizon that a given space-state graph has been brought up to. */
    public static final IRI PROCESSED_UP_TO = vf.createIRI(NPA.NAMESPACE, "processedUpTo");

    /**
     * Flag (boolean literal) set in {@code npa:graph} when an incremental cycle
     * has DELETEd a structural derivation (admin-tier RoleInstantiation,
     * RoleAssignment, or RoleDeclaration). Triggers the periodic full-rebuild
     * worker on its next pass; cleared once the rebuild completes.
     */
    public static final IRI NEEDS_FULL_REBUILD = vf.createIRI(NPA.NAMESPACE, "needsFullRebuild");

    // -------- Subject-minting helpers --------

    /** Mints {@code npas:<spaceRef>} for an aggregate space-ref entry. */
    public static IRI forSpaceRef(String spaceRef) {
        return vf.createIRI(NPAS_NAMESPACE, spaceRef);
    }

    /** Mints {@code npadef:<artifactCode>} for a space-definition entry. */
    public static IRI forSpaceDefinition(String artifactCode) {
        return vf.createIRI(NPADEF_NAMESPACE, artifactCode);
    }

    /** Mints {@code npari:<artifactCode>} for a role-instantiation entry. */
    public static IRI forRoleInstantiation(String artifactCode) {
        return vf.createIRI(NPARI_NAMESPACE, artifactCode);
    }

    /** Mints {@code npara:<artifactCode>} for a role-attachment entry. */
    public static IRI forRoleAssignment(String artifactCode) {
        return vf.createIRI(NPARA_NAMESPACE, artifactCode);
    }

    /** Mints {@code npard:<artifactCode>} for a role-declaration entry. */
    public static IRI forRoleDeclaration(String artifactCode) {
        return vf.createIRI(NPARD_NAMESPACE, artifactCode);
    }

    /** Mints {@code npainv:<artifactCode>} for an invalidation entry. */
    public static IRI forInvalidation(String artifactCode) {
        return vf.createIRI(NPAINV_NAMESPACE, artifactCode);
    }

    /**
     * Mints {@code npass:<trustStateHash>_<loadCounterAtBuildStart>} for a space-state graph.
     *
     * @param trustStateHash       the source trust-state hash
     * @param loadCounterAtBuildStart the value of {@code npa:currentLoadCounter} when the build kicked off
     * @return the graph IRI
     */
    public static IRI forSpaceState(String trustStateHash, long loadCounterAtBuildStart) {
        return vf.createIRI(NPASS_NAMESPACE, trustStateHash + "_" + loadCounterAtBuildStart);
    }

    private SpacesVocab() {
    }

}
