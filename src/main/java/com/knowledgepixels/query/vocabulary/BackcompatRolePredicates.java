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
 * <p>Temporary — these should be dropped once existing deployments have moved
 * to publishing with {@link GEN#ROLE_INSTANTIATION}. See
 * {@code doc/plan-space-repositories.md} for the current list.
 *
 * <p>Direction: each predicate is classified as either {@link Direction#REGULAR}
 * (space &rarr; agent) or {@link Direction#INVERSE} (agent &rarr; space),
 * which determines whether the extractor emits {@code npa:regularProperty} or
 * {@code npa:inverseProperty} and which side of the assertion triple is the
 * space vs. the agent.
 */
public final class BackcompatRolePredicates {

    /** Triple-direction of an assignment predicate. */
    public enum Direction {
        /** {@code <space> <predicate> <agent>}. */
        REGULAR,
        /** {@code <agent> <predicate> <space>}. */
        INVERSE
    }

    private static final ValueFactory vf = SimpleValueFactory.getInstance();

    private static IRI iri(String s) {
        return vf.createIRI(s);
    }

    /** Direction map for the backwards-compat predicates. */
    public static final Map<IRI, Direction> DIRECTIONS = Map.ofEntries(
            // Wikidata
            Map.entry(iri("http://www.wikidata.org/entity/P1344"), Direction.INVERSE),   // "participant in"
            Map.entry(iri("http://www.wikidata.org/entity/P463"),  Direction.INVERSE),   // "member of"
            Map.entry(iri("http://www.wikidata.org/entity/P710"),  Direction.REGULAR),   // "participant"
            Map.entry(iri("http://www.wikidata.org/entity/P823"),  Direction.REGULAR),   // "speaker"
            // FAIR 3pff
            Map.entry(iri("https://w3id.org/fair/3pff/has-event-assistant"),                Direction.REGULAR),
            Map.entry(iri("https://w3id.org/fair/3pff/has-event-facilitator"),              Direction.REGULAR),
            Map.entry(iri("https://w3id.org/fair/3pff/participatedAsFacilitatorAssistantIn"), Direction.INVERSE),
            Map.entry(iri("https://w3id.org/fair/3pff/participatedAsImplementerAspirantIn"),  Direction.INVERSE),
            Map.entry(iri("https://w3id.org/fair/3pff/participatedAsParticipantIn"),          Direction.INVERSE),
            // KPXL gen terms
            Map.entry(iri("https://w3id.org/kpxl/gen/terms/hasAdmin"),       Direction.REGULAR),
            Map.entry(iri("https://w3id.org/kpxl/gen/terms/hasObserver"),    Direction.REGULAR),
            Map.entry(iri("https://w3id.org/kpxl/gen/terms/hasProjectLead"), Direction.REGULAR),
            Map.entry(iri("https://w3id.org/kpxl/gen/terms/hasTeamMember"),  Direction.REGULAR),
            Map.entry(iri("https://w3id.org/kpxl/gen/terms/plansToAttend"),  Direction.INVERSE));

    /** Convenience set of just the predicate IRIs — useful for type-lookup membership tests. */
    public static final Set<IRI> ALL = DIRECTIONS.keySet();

    private BackcompatRolePredicates() {
    }

}
