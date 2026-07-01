package com.knowledgepixels.query.vocabulary;

import java.util.Map;
import java.util.Set;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;

/**
 * Currently-used properties that historically stood in for
 * {@link GEN#ROLE_INSTANTIATION} before the type existed. Nanopubs that are
 * indexed under any of these predicate IRIs as an {@code npx:hasNanopubType}
 * (via the registry's single-triple-assertion type-propagation trick) are
 * treated as role-instantiation nanopubs by the extractor.
 *
 * <p>This is the set of role predicates whose direction is known statically, so
 * the extractor can resolve them without a per-nanopub direction pin. It is still
 * intended to be <em>drained</em>: once role-assigning nanopubs pin their predicate
 * direction in pubinfo (see {@code doc/design-role-direction-pinning.md}), the few
 * remaining old relations here either break or get converted, and the list can go.
 * {@code gen:hasHelper} is deliberately kept <em>out</em> of this list as the pilot
 * predicate for the pinning mechanism. See {@code doc/design-space-repositories.md}
 * for the reference list.
 *
 * <p>Direction: each predicate is classified as either {@link Direction#REGULAR}
 * (agent &rarr; space — the natural direction of a role from its bearer) or
 * {@link Direction#INVERSE} (space &rarr; agent — the converse view), which
 * determines whether the extractor emits {@code npa:regularProperty} or
 * {@code npa:inverseProperty} and which side of the assertion triple is the
 * space vs. the agent. Matches the convention used by published role-definition
 * nanopubs ({@code gen:hasRegularProperty} / {@code gen:hasInverseProperty}).
 */
public final class BackcompatRolePredicates {

    /** Triple-direction of an assignment predicate. */
    public enum Direction {
        /** {@code <agent> <predicate> <space>} — agent-centric, the role's natural direction. */
        REGULAR,
        /** {@code <space> <predicate> <agent>} — space-centric, the converse view. */
        INVERSE
    }

    private static final ValueFactory vf = SimpleValueFactory.getInstance();

    private static IRI iri(String s) {
        return vf.createIRI(s);
    }

    /** Direction map for the backwards-compat predicates. */
    public static final Map<IRI, Direction> DIRECTIONS = Map.ofEntries(
            // Wikidata
            Map.entry(iri("http://www.wikidata.org/entity/P1344"), Direction.REGULAR),   // "participant in" (agent → event)
            Map.entry(iri("http://www.wikidata.org/entity/P463"),  Direction.REGULAR),   // "member of" (agent → space)
            Map.entry(iri("http://www.wikidata.org/entity/P710"),  Direction.INVERSE),   // "participant" (event → agent)
            Map.entry(iri("http://www.wikidata.org/entity/P823"),  Direction.INVERSE),   // "speaker" (event → agent)
            // FAIR 3pff
            Map.entry(iri("https://w3id.org/fair/3pff/has-event-assistant"),                Direction.INVERSE),
            Map.entry(iri("https://w3id.org/fair/3pff/has-event-facilitator"),              Direction.INVERSE),
            Map.entry(iri("https://w3id.org/fair/3pff/has-event-organizer"),                Direction.INVERSE),
            Map.entry(iri("https://w3id.org/fair/3pff/participatedAsFacilitatorAssistantIn"), Direction.REGULAR),
            Map.entry(iri("https://w3id.org/fair/3pff/participatedAsFacilitatorIn"),          Direction.REGULAR),
            Map.entry(iri("https://w3id.org/fair/3pff/participatedAsImplementerAspirantIn"),  Direction.REGULAR),
            Map.entry(iri("https://w3id.org/fair/3pff/participatedAsImplementerIn"),          Direction.REGULAR),
            Map.entry(iri("https://w3id.org/fair/3pff/participatedAsParticipantIn"),          Direction.REGULAR),
            Map.entry(iri("https://w3id.org/fair/3pff/participatedAsTrainerAssistantIn"),     Direction.REGULAR),
            Map.entry(iri("https://w3id.org/fair/3pff/participatedAsTrainerIn"),              Direction.REGULAR),
            // KPXL gen terms
            Map.entry(iri("https://w3id.org/kpxl/gen/terms/hasAdmin"),       Direction.INVERSE),
            Map.entry(iri("https://w3id.org/kpxl/gen/terms/hasGuest"),       Direction.INVERSE),
            Map.entry(iri("https://w3id.org/kpxl/gen/terms/hasHost"),        Direction.INVERSE),
            Map.entry(iri("https://w3id.org/kpxl/gen/terms/hasMaintainer"),  Direction.INVERSE),   // issue #136
            Map.entry(iri("https://w3id.org/kpxl/gen/terms/hasObserver"),    Direction.INVERSE),
            Map.entry(iri("https://w3id.org/kpxl/gen/terms/hasProjectLead"), Direction.INVERSE),
            Map.entry(iri("https://w3id.org/kpxl/gen/terms/hasTeamMember"),  Direction.INVERSE),
            Map.entry(iri("https://w3id.org/kpxl/gen/terms/plansToAttend"),  Direction.REGULAR));

    /** Convenience set of just the predicate IRIs — useful for type-lookup membership tests. */
    public static final Set<IRI> ALL = DIRECTIONS.keySet();

    private BackcompatRolePredicates() {
    }

}
