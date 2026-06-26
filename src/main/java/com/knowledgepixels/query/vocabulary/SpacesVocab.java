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
 *   <li>{@link #NPASUB_NAMESPACE} ({@code npasub:}) — {@link #forSubSpaceDeclaration(String, String) sub-space-declaration} entries (one per {@code (child, parent)} pair).
 *   <li>{@link #NPAMRD_NAMESPACE} ({@code npamrd:}) — {@link #forMaintainedResourceDeclaration(String, String) maintained-resource-declaration} entries (one per {@code (resource, space)} pair).
 *   <li>{@link #NPAALIAS_NAMESPACE} ({@code npaalias:}) — {@link #forSpaceAliasDeclaration(String, String) space-alias-declaration} entries (one per {@code owl:sameAs} pair).
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
    /** Namespace for sub-space-declaration entries ({@code npasub:<artifactCode>_<parentHash>}). */
    public static final String NPASUB_NAMESPACE = "http://purl.org/nanopub/admin/subspace/";
    /** Namespace for maintained-resource-declaration entries ({@code npamrd:<artifactCode>_<resourceHash>}). */
    public static final String NPAMRD_NAMESPACE = "http://purl.org/nanopub/admin/maintainedresource/";
    /** Namespace for space-alias-declaration entries ({@code npaalias:<artifactCode>_<aliasHash>}). */
    public static final String NPAALIAS_NAMESPACE = "http://purl.org/nanopub/admin/spacealias/";
    /** Namespace for preset-assignment entries ({@code npapa:<artifactCode>}). */
    public static final String NPAPA_NAMESPACE = "http://purl.org/nanopub/admin/presetassign/";
    /** Namespace for preset-declaration entries ({@code npapd:<artifactCode>}). */
    public static final String NPAPD_NAMESPACE = "http://purl.org/nanopub/admin/presetdecl/";
    /** Namespace for role-revocation entries ({@code nparev:<artifactCode>_<discriminatorHash>}). */
    public static final String NPAREV_NAMESPACE = "http://purl.org/nanopub/admin/rolerevoc/";
    /** Namespace for role-detachment entries ({@code npadet:<artifactCode>_<roleHash>}). */
    public static final String NPADET_NAMESPACE = "http://purl.org/nanopub/admin/roledetach/";
    /** Namespace for space-state graph IRIs ({@code npass:<trustStateHash>_<loadCounter>}). */
    public static final String NPASS_NAMESPACE = "http://purl.org/nanopub/admin/spacestate/";

    // -------- RDF types on extraction entries --------

    /** RDF type for the aggregate per-space entry keyed by its space ref. */
    public static final IRI SPACE_REF = vf.createIRI(NPA.NAMESPACE, "SpaceRef");

    /** RDF type for the per-contributor space-definition entry. */
    public static final IRI SPACE_DEFINITION = vf.createIRI(NPA.NAMESPACE, "SpaceDefinition");

    /** RDF type for the summarized role-definition entry. */
    public static final IRI ROLE_DECLARATION = vf.createIRI(NPA.NAMESPACE, "RoleDeclaration");

    /** RDF type for a sub-space-declaration extraction entry. */
    public static final IRI SUB_SPACE_DECLARATION = vf.createIRI(NPA.NAMESPACE, "SubSpaceDeclaration");

    /** RDF type for a maintained-resource-declaration extraction entry. */
    public static final IRI MAINTAINED_RESOURCE_DECLARATION = vf.createIRI(NPA.NAMESPACE, "MaintainedResourceDeclaration");

    /** RDF type for a space-alias-declaration extraction entry (from {@code owl:sameAs} in a {@code gen:Space} nanopub). */
    public static final IRI SPACE_ALIAS_DECLARATION = vf.createIRI(NPA.NAMESPACE, "SpaceAliasDeclaration");

    /** RDF type for a preset-assignment extraction entry (from a {@code gen:PresetAssignment} nanopub). */
    public static final IRI PRESET_ASSIGNMENT = vf.createIRI(NPA.NAMESPACE, "PresetAssignment");

    /** RDF type for a preset-declaration extraction entry (from a {@code gen:Preset} nanopub). */
    public static final IRI PRESET_DECLARATION = vf.createIRI(NPA.NAMESPACE, "PresetDeclaration");

    /**
     * RDF type for a role-revocation extraction entry (from a {@code gen:RevokedRoleInstantiation}
     * nanopub). A key-level negative on {@code (forSpace, forAgent, revokedRole)}; never
     * materializes as a state row — consumed as a suppression filter / displacement DELETE in the
     * tier where the key is bound. See {@code doc/design-space-repositories.md} (issue #129).
     */
    public static final IRI ROLE_REVOCATION = vf.createIRI(NPA.NAMESPACE, "RoleRevocation");

    /**
     * RDF type for a role-detachment extraction entry (from a {@code gen:detachedRole} triple).
     * A key-level negative on {@code (forSpace, revokedRole)}; competes against both direct and
     * preset-derived attachments by latest-wins. See issue #129.
     */
    public static final IRI ROLE_DETACHMENT = vf.createIRI(NPA.NAMESPACE, "RoleDetachment");

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

    /**
     * Custom role predicate on an <em>unresolved</em> {@code gen:RoleInstantiation} — one
     * whose direction the extractor can't classify (not {@code gen:hasAdmin} and not in
     * {@link com.knowledgepixels.query.vocabulary.BackcompatRolePredicates}). Carries the
     * raw assertion predicate IRI; the materializer resolves its direction (and hence
     * tier) by joining it against an {@link #ROLE_DECLARATION}'s
     * {@code gen:hasRegularProperty} / {@code gen:hasInverseProperty} that is attached to
     * the target space. Emitted alongside {@link #BINDING_SUBJECT} / {@link #BINDING_OBJECT}
     * (the raw assertion endpoints) instead of {@link #FOR_SPACE} / {@link #FOR_AGENT},
     * since which side is the space isn't known until the direction is resolved.
     */
    public static final IRI ROLE_PREDICATE = vf.createIRI(NPA.NAMESPACE, "rolePredicate");

    /** Raw assertion subject of an unresolved {@link #ROLE_PREDICATE} binding. */
    public static final IRI BINDING_SUBJECT = vf.createIRI(NPA.NAMESPACE, "bindingSubject");

    /** Raw assertion object of an unresolved {@link #ROLE_PREDICATE} binding. */
    public static final IRI BINDING_OBJECT = vf.createIRI(NPA.NAMESPACE, "bindingObject");

    /** Literal pubkey hash stamped alongside {@link org.nanopub.vocabulary.NPX#SIGNED_BY}. */
    public static final IRI PUBKEY_HASH = vf.createIRI(NPA.NAMESPACE, "pubkeyHash");

    /** Links a {@link #ROLE_DECLARATION} to the actual role IRI embedded in the defining nanopub. */
    public static final IRI ROLE = vf.createIRI(NPA.NAMESPACE, "role");

    /** Tier class of a {@link #ROLE_DECLARATION}: gen:MaintainerRole / MemberRole / ObserverRole. */
    public static final IRI HAS_ROLE_TYPE = vf.createIRI(NPA.NAMESPACE, "hasRoleType");

    /**
     * The role IRI carried by a {@link #ROLE_REVOCATION} or {@link #ROLE_DETACHMENT} negative
     * entry — the version-pinned role being revoked/detached ({@code gen:AdminRole} for admin
     * revocations). Matched against a materialized RI's {@code gen:hasRole} / admin
     * {@code npa:hasRoleType gen:AdminRole}, or an attachment's {@code gen:hasRole}.
     */
    public static final IRI REVOKED_ROLE = vf.createIRI(NPA.NAMESPACE, "revokedRole");

    /** Links a {@link #SUB_SPACE_DECLARATION} to the child Space IRI. */
    public static final IRI CHILD_SPACE = vf.createIRI(NPA.NAMESPACE, "childSpace");

    /** Links a {@link #SUB_SPACE_DECLARATION} to the parent Space IRI. */
    public static final IRI PARENT_SPACE = vf.createIRI(NPA.NAMESPACE, "parentSpace");

    /** Links a {@link #MAINTAINED_RESOURCE_DECLARATION} to the maintained resource IRI. */
    public static final IRI RESOURCE_IRI = vf.createIRI(NPA.NAMESPACE, "resourceIri");

    /** Links a {@link #MAINTAINED_RESOURCE_DECLARATION} to the maintaining Space IRI. */
    public static final IRI MAINTAINER_SPACE = vf.createIRI(NPA.NAMESPACE, "maintainerSpace");

    /** Links a {@link #SPACE_ALIAS_DECLARATION} to the canonical Space IRI (the {@code owl:sameAs} subject). */
    public static final IRI CANONICAL_SPACE = vf.createIRI(NPA.NAMESPACE, "canonicalSpace");

    /** Links a {@link #SPACE_ALIAS_DECLARATION} to the alias Space IRI (the {@code owl:sameAs} object). */
    public static final IRI ALIAS_SPACE = vf.createIRI(NPA.NAMESPACE, "aliasSpace");

    // -------- Preset extraction-entry properties (Nanodash issue #302) --------

    /**
     * Join key linking a {@link #PRESET_ASSIGNMENT} (its assigned preset) and a
     * {@link #PRESET_DECLARATION} (its own identity). A declaration emits this for
     * <em>both</em> the {@code gen:Preset}-typed node IRI and its
     * {@code dct:isVersionOf} kind, so an assignment referencing either joins.
     */
    public static final IRI OF_PRESET = vf.createIRI(NPA.NAMESPACE, "ofPreset");

    /** Links a {@link #PRESET_ASSIGNMENT} to the resource it targets ({@code gen:isAssignmentFor}). */
    public static final IRI FOR_RESOURCE = vf.createIRI(NPA.NAMESPACE, "forResource");

    /** Boolean literal on a {@link #PRESET_ASSIGNMENT}: active (true) unless explicitly deactivated. */
    public static final IRI IS_ACTIVATED = vf.createIRI(NPA.NAMESPACE, "isActivated");

    /**
     * Canonical version-independent identity of a {@link #PRESET_DECLARATION}: its
     * {@code dct:isVersionOf} kind, or the {@code gen:Preset} node IRI when the preset
     * declares no kind (same fallback as Nanodash {@code ViewDisplay.getViewKindIri()}).
     * Roles are resolved from the <em>latest</em> declaration per kind, mirroring the
     * per-view-kind latest-wins in Nanodash's {@code get-view-displays} expansion.
     */
    public static final IRI PRESET_KIND = vf.createIRI(NPA.NAMESPACE, "presetKind");

    /** Links a {@link #PRESET_DECLARATION} to each role IRI the preset bundles ({@code gen:hasRole}). */
    public static final IRI PRESET_ROLE = vf.createIRI(NPA.NAMESPACE, "presetRole");

    /** Resource type(s) a {@link #PRESET_DECLARATION} applies to ({@code gen:appliesToInstancesOf}). */
    public static final IRI APPLIES_TO_INSTANCES_OF = vf.createIRI(NPA.NAMESPACE, "appliesToInstancesOf");

    /**
     * Marker on a materialized {@code gen:RoleAssignment} that was derived from a preset
     * assignment (its value is the assignment nanopub). Scopes the preset-deactivation
     * delete and the read-side from-preset marking; keeps both clear of
     * directly-published attachments.
     */
    public static final IRI DERIVED_FROM_PRESET = vf.createIRI(NPA.NAMESPACE, "derivedFromPreset");

    /**
     * Validated alias edge written into the space-state graph: {@code <alias> npa:sameAsSpace <canonical>}.
     * Materialized by the alias-admit tier once a {@link #SPACE_ALIAS_DECLARATION} passes the
     * publisher-admin and anti-hijack gates; consumed by the alias-aware admin-authority lookups.
     */
    public static final IRI SAME_AS_SPACE = vf.createIRI(NPA.NAMESPACE, "sameAsSpace");

    /**
     * Links a {@link #SPACE_REF} aggregate to each intermediate path-prefix of its
     * Space IRI (down to host-only). Identity-derived; reinforced by every contributor.
     * Used by the URL-prefix sub-space fallback in the materializer.
     */
    public static final IRI HAS_ID_PREFIX = vf.createIRI(NPA.NAMESPACE, "hasIdPrefix");

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

    /**
     * Mints {@code npari:<artifactCode>_<discriminatorHash>} for a role-instantiation
     * entry where a single nanopub emits multiple RIs (e.g. a {@code gen:Space}
     * nanopub with inline role-predicate triples for several distinct roles).
     * The hash is on the predicate IRI so RIs for different roles in the same
     * nanopub get distinct subjects.
     */
    public static IRI forRoleInstantiation(String artifactCode, String discriminatorHash) {
        return vf.createIRI(NPARI_NAMESPACE, artifactCode + "_" + discriminatorHash);
    }

    /** Mints {@code npara:<artifactCode>} for a role-attachment entry. */
    public static IRI forRoleAssignment(String artifactCode) {
        return vf.createIRI(NPARA_NAMESPACE, artifactCode);
    }

    /** Mints {@code npard:<artifactCode>} for a role-declaration entry. */
    public static IRI forRoleDeclaration(String artifactCode) {
        return vf.createIRI(NPARD_NAMESPACE, artifactCode);
    }

    /**
     * Mints {@code npasub:<artifactCode>_<parentHash>} for a sub-space-declaration entry.
     * Including the parent-IRI hash in the local name lets a single nanopub declare
     * multiple parents without subject collision.
     *
     * @param artifactCode trusty-URI artifact code of the originating nanopub
     * @param parentHash   {@code Utils.createHash(<parentSpaceIri>)}
     */
    public static IRI forSubSpaceDeclaration(String artifactCode, String parentHash) {
        return vf.createIRI(NPASUB_NAMESPACE, artifactCode + "_" + parentHash);
    }

    /**
     * Mints {@code npamrd:<artifactCode>_<resourceHash>} for a maintained-resource-declaration entry.
     * Including the resource-IRI hash in the local name lets a single nanopub declare
     * multiple maintained resources without subject collision.
     *
     * @param artifactCode trusty-URI artifact code of the originating nanopub
     * @param resourceHash {@code Utils.createHash(<resourceIri>)}
     */
    public static IRI forMaintainedResourceDeclaration(String artifactCode, String resourceHash) {
        return vf.createIRI(NPAMRD_NAMESPACE, artifactCode + "_" + resourceHash);
    }

    /**
     * Mints {@code npaalias:<artifactCode>_<aliasHash>} for a space-alias-declaration entry.
     * Including the alias-IRI hash in the local name lets a single nanopub declare multiple
     * aliases without subject collision.
     *
     * @param artifactCode trusty-URI artifact code of the originating nanopub
     * @param aliasHash    {@code Utils.createHash(<aliasSpaceIri>)}
     */
    public static IRI forSpaceAliasDeclaration(String artifactCode, String aliasHash) {
        return vf.createIRI(NPAALIAS_NAMESPACE, artifactCode + "_" + aliasHash);
    }

    /** Mints {@code npapa:<artifactCode>} for a preset-assignment entry. */
    public static IRI forPresetAssignment(String artifactCode) {
        return vf.createIRI(NPAPA_NAMESPACE, artifactCode);
    }

    /** Mints {@code npapd:<artifactCode>} for a preset-declaration entry. */
    public static IRI forPresetDeclaration(String artifactCode) {
        return vf.createIRI(NPAPD_NAMESPACE, artifactCode);
    }

    /**
     * Mints {@code nparev:<artifactCode>_<discriminatorHash>} for a role-revocation entry.
     * Including a discriminator hash (on {@code <agent>+<role>}) lets a single nanopub revoke
     * multiple {@code (agent, role)} keys without subject collision.
     *
     * @param artifactCode     trusty-URI artifact code of the originating nanopub
     * @param discriminatorHash {@code Utils.createHash(<agentIri> + <roleIri>)}
     */
    public static IRI forRoleRevocation(String artifactCode, String discriminatorHash) {
        return vf.createIRI(NPAREV_NAMESPACE, artifactCode + "_" + discriminatorHash);
    }

    /**
     * Mints {@code npadet:<artifactCode>_<roleHash>} for a role-detachment entry.
     * Including the role-IRI hash lets a single nanopub detach multiple roles without
     * subject collision.
     *
     * @param artifactCode trusty-URI artifact code of the originating nanopub
     * @param roleHash     {@code Utils.createHash(<roleIri>)}
     */
    public static IRI forRoleDetachment(String artifactCode, String roleHash) {
        return vf.createIRI(NPADET_NAMESPACE, artifactCode + "_" + roleHash);
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
