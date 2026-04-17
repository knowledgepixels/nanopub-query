package com.knowledgepixels.query.vocabulary;

import org.eclipse.rdf4j.model.IRI;
import org.nanopub.vocabulary.NPA;
import org.nanopub.vocabulary.VocabUtils;

/**
 * IRIs in the {@code npa:} namespace used to materialize space-authority data
 * into a space's graph in the {@code spaces} repo.
 *
 * <p>The shape is one {@link #ROLE_ASSIGNMENT} per
 * {@code (agent, role, space)} tuple, linking via {@link #HAS_EVIDENCE} to one
 * or more {@link #ROLE_ASSIGNMENT_EVIDENCE} objects, each carrying an
 * {@link #EVIDENCE_KIND} ({@link #AUTHORITY_EVIDENCE} or {@link #SELF_EVIDENCE}),
 * a {@link #VIA_NANOPUB} provenance pointer, and the {@link #VIA_PUBLISHER_AGENT}
 * resolved at materialization time so consumers don't need to join the trust
 * repo. Validated view displays use {@link #VALIDATED_VIEW_DISPLAY}.
 *
 * <p>See {@code doc/plan-space-repositories.md} for the full design.
 */
public class SpaceAuthority {

    /** RDF type for an admin/maintainer/role assignment for a given (agent, role, space) tuple. */
    public static final IRI ROLE_ASSIGNMENT = createIRI("RoleAssignment");

    /** RDF type for an evidence record attached to a role assignment via {@link #HAS_EVIDENCE}. */
    public static final IRI ROLE_ASSIGNMENT_EVIDENCE = createIRI("RoleAssignmentEvidence");

    /** RDF type for a view-display nanopub whose publisher passes the view-display policy. */
    public static final IRI VALIDATED_VIEW_DISPLAY = createIRI("ValidatedViewDisplay");

    /** Links a {@link #ROLE_ASSIGNMENT} (or other authority record) to the space ref it applies to. */
    public static final IRI FOR_SPACE = createIRI("forSpace");

    /** Links a {@link #ROLE_ASSIGNMENT} to its role IRI (e.g. {@code gen:AdminRole}). */
    public static final IRI ROLE = createIRI("role");

    /** Links a {@link #ROLE_ASSIGNMENT} to the assigned-member agent IRI. */
    public static final IRI AGENT = createIRI("agent");

    /** Links a {@link #ROLE_ASSIGNMENT} to one of its {@link #ROLE_ASSIGNMENT_EVIDENCE} pieces. */
    public static final IRI HAS_EVIDENCE = createIRI("hasEvidence");

    /** Tags an evidence object with {@link #AUTHORITY_EVIDENCE} or {@link #SELF_EVIDENCE}. */
    public static final IRI EVIDENCE_KIND = createIRI("evidenceKind");

    /** Provenance pointer from an evidence object to the source nanopub URI. */
    public static final IRI VIA_NANOPUB = createIRI("viaNanopub");

    /** Resolved publisher agent for the source nanopub, computed against the trust state at materialization time. */
    public static final IRI VIA_PUBLISHER_AGENT = createIRI("viaPublisherAgent");

    /** Evidence-kind individual: the source nanopub's publisher resolves to an agent in the appropriate authority closure. */
    public static final IRI AUTHORITY_EVIDENCE = createIRI("authorityEvidence");

    /** Evidence-kind individual: the source nanopub's publisher resolves to the assigned-member agent. */
    public static final IRI SELF_EVIDENCE = createIRI("selfEvidence");

    private SpaceAuthority() {
    }

    private static IRI createIRI(String localName) {
        return VocabUtils.createIRI(NPA.NAMESPACE, localName);
    }

}
