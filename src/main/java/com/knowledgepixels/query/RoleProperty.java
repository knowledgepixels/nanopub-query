package com.knowledgepixels.query;

import org.eclipse.rdf4j.model.IRI;

/**
 * A role property registered for a space. Captures the predicate that
 * role-assignment nanopubs use, the role type it confers (one of the
 * {@code gen:*Role} subclasses), and the triple direction the predicate
 * appears in for valid assignments.
 *
 * <p>Direction notes:
 * <ul>
 *   <li>{@link Direction#REGULAR} — assignment triple is
 *       {@code <member> <predicate> <space>}.</li>
 *   <li>{@link Direction#INVERSE} — assignment triple is
 *       {@code <space> <predicate> <member>}.</li>
 * </ul>
 *
 * <p>A single role-definition nanopub may register multiple role properties
 * (some regular, some inverse) for the same role type; each is stored as its
 * own {@code RoleProperty} record in {@link SpaceRegistry}.
 *
 * @param predicate the predicate IRI used in role-assignment triples
 * @param roleType  the role type IRI ({@code gen:AdminRole},
 *                  {@code gen:MaintainerRole}, {@code gen:MemberRole}, or
 *                  {@code gen:ObserverRole})
 * @param direction whether the predicate appears regular or inverse
 */
public record RoleProperty(IRI predicate, IRI roleType, Direction direction) {

    /**
     * Triple direction for a role-property predicate.
     */
    public enum Direction {
        /** {@code <member> <predicate> <space>}. */
        REGULAR,
        /** {@code <space> <predicate> <member>}. */
        INVERSE
    }

}
