package com.knowledgepixels.query.vocabulary;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Namespace;
import org.nanopub.vocabulary.VocabUtils;

/**
 * IRIs and prefix declarations from the KPXL "gen" terms vocabulary
 * (<a href="https://w3id.org/kpxl/gen/terms/">https://w3id.org/kpxl/gen/terms/</a>).
 *
 * <p>Defines the subset needed by the space-extraction layer
 * (see {@code doc/plan-space-repositories.md}):
 * the {@link #SPACE} type and {@link #HAS_ROOT_DEFINITION} predicate for space
 * declarations; {@link #SPACE_MEMBER_ROLE} and its tier subclasses
 * ({@link #MAINTAINER_ROLE}, {@link #MEMBER_ROLE}, {@link #OBSERVER_ROLE}) for
 * role definitions; {@link #HAS_REGULAR_PROPERTY} and {@link #HAS_INVERSE_PROPERTY}
 * for role-predicate bindings; {@link #HAS_ROLE} for space/role attachments;
 * {@link #ROLE_INSTANTIATION} and {@link #ROLE_ASSIGNMENT} as rdf:types on
 * extraction entries; {@link #HAS_ADMIN} as the single hardcoded admin predicate.
 */
public class GEN {

    public static final String NAMESPACE = "https://w3id.org/kpxl/gen/terms/";
    public static final String PREFIX = "gen";
    public static final Namespace NS = VocabUtils.createNamespace(PREFIX, NAMESPACE);

    /** Class IRI marking a nanopub as a Space-defining nanopub. */
    public static final IRI SPACE = VocabUtils.createIRI(NAMESPACE, "Space");

    /**
     * Predicate connecting a Space IRI to the URI of its root nanopub. For a root
     * nanopub itself, the object is the nanopub's own URI; for an update, the object
     * points back at the original root nanopub.
     */
    public static final IRI HAS_ROOT_DEFINITION = VocabUtils.createIRI(NAMESPACE, "hasRootDefinition");

    /** Superclass of all role types (admin, maintainer, member, observer). */
    public static final IRI SPACE_MEMBER_ROLE = VocabUtils.createIRI(NAMESPACE, "SpaceMemberRole");

    /** Admin role tier. Single hardcoded instance; no user-defined admin roles. */
    public static final IRI ADMIN_ROLE = VocabUtils.createIRI(NAMESPACE, "AdminRole");

    /** Maintainer role tier. */
    public static final IRI MAINTAINER_ROLE = VocabUtils.createIRI(NAMESPACE, "MaintainerRole");

    /** Member role tier. */
    public static final IRI MEMBER_ROLE = VocabUtils.createIRI(NAMESPACE, "MemberRole");

    /** Observer role tier. Default tier when a role definition doesn't declare one. */
    public static final IRI OBSERVER_ROLE = VocabUtils.createIRI(NAMESPACE, "ObserverRole");

    /** Predicate granting admin status: hardcoded for the admin tier. */
    public static final IRI HAS_ADMIN = VocabUtils.createIRI(NAMESPACE, "hasAdmin");

    /** Predicate attaching a role IRI to a space. */
    public static final IRI HAS_ROLE = VocabUtils.createIRI(NAMESPACE, "hasRole");

    /** Predicate binding a role to the "regular" direction property (space &rarr; agent). */
    public static final IRI HAS_REGULAR_PROPERTY = VocabUtils.createIRI(NAMESPACE, "hasRegularProperty");

    /** Predicate binding a role to the "inverse" direction property (agent &rarr; space). */
    public static final IRI HAS_INVERSE_PROPERTY = VocabUtils.createIRI(NAMESPACE, "hasInverseProperty");

    /**
     * Nanopub type (new) for role-instantiation nanopubs — those that grant a role to
     * an agent in a space. Extraction entries are also tagged with this class.
     */
    public static final IRI ROLE_INSTANTIATION = VocabUtils.createIRI(NAMESPACE, "RoleInstantiation");

    /**
     * Tag on extraction entries derived from {@code gen:hasRole} (space/role attachment)
     * nanopubs. The raw nanopub itself is identified by the presence of a
     * {@code gen:hasRole} triple; the tag applies to the {@code npara:<artifactCode>}
     * extraction entry.
     */
    public static final IRI ROLE_ASSIGNMENT = VocabUtils.createIRI(NAMESPACE, "RoleAssignment");

    private GEN() {
    }

}
