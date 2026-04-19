package com.knowledgepixels.query;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.nanopub.Nanopub;
import org.nanopub.NanopubUtils;

import com.google.common.hash.Hashing;
import com.knowledgepixels.query.vocabulary.GEN;
import com.knowledgepixels.query.vocabulary.NPAS;
import com.knowledgepixels.query.vocabulary.NPAX;
import com.knowledgepixels.query.vocabulary.SpaceAuthority;
import com.knowledgepixels.query.vocabulary.SpaceExtract;

/**
 * Identifies the contributions a nanopub makes to known spaces and produces
 * the corresponding extract triples for the {@code spaces} repo.
 *
 * <p>This first iteration covers two extract classes:
 *
 * <ul>
 *   <li>{@link SpaceExtract#ROLE_ASSERTION} ({@code npa:RoleAssertion}) — for
 *       any assertion of the form {@code <spaceIri> gen:hasAdmin <agent>}
 *       where {@code spaceIri} is registered in {@link SpaceRegistry}.
 *       Built-in role properties ({@code gen:hasAdmin}, future
 *       {@code gen:hasMaintainer}) are extracted with the predicate and
 *       direction recorded directly; PR-B2 will add the same shape for
 *       learned role properties from role-definition nanopubs.</li>
 *   <li>{@link SpaceExtract#PROFILE_FIELD} ({@code npa:ProfileField}) — for
 *       any assertion whose subject is the Space IRI of a space defined by
 *       *this* nanopub (i.e. a {@code gen:Space}-typed nanopub with a matching
 *       {@code gen:hasRootDefinition}). One extract per
 *       {@code (spaceRef, predicate, value)}, except for the
 *       {@code gen:hasRootDefinition} and {@code gen:hasAdmin} triples
 *       themselves (the former is structural; the latter is captured
 *       independently as a role assertion).</li>
 * </ul>
 *
 * <p>Maintainer grants, role definitions, role assignments, and view-display
 * extracts come in a follow-up PR. The extractor is purely a triple producer:
 * given a {@link Nanopub} and the current {@link SpaceRegistry} state it
 * returns context-tagged statements (graph IRI = {@code npas:<spaceRef>}) plus
 * the set of space refs the nanopub contributed to. The caller decides when
 * to commit them and how to record the source-nanopub reverse index.
 *
 * <p>See {@code doc/plan-space-repositories.md}.
 */
public class SpacesExtractor {

    private static final ValueFactory vf = SimpleValueFactory.getInstance();

    /**
     * Result of an extraction pass over a single nanopub.
     *
     * @param statements   the extract triples to write to the {@code spaces} repo;
     *                     each carries its target named graph as its context
     * @param spaceRefs    the space refs this nanopub contributed extracts to —
     *                     the caller should record the reverse mapping via
     *                     {@link SpaceRegistry#recordSourceNanopub}
     */
    public record ExtractionResult(List<Statement> statements, Set<String> spaceRefs) { }

    private SpacesExtractor() {
    }

    /**
     * Extracts everything the given nanopub contributes to known spaces.
     *
     * @param np       the nanopub to inspect
     * @param registry the registry whose state determines which Space IRIs are known
     * @return the extract triples and the set of space refs the nanopub contributed to
     */
    public static ExtractionResult extract(Nanopub np, SpaceRegistry registry) {
        List<Statement> out = new ArrayList<>();
        Set<String> contributedSpaces = new LinkedHashSet<>();

        // (1) Role-assertion extracts: any <spaceIri> gen:hasAdmin <agent> where
        // spaceIri is currently registered in SpaceRegistry. The triple is in
        // inverse direction (subject = space, object = agent). The role IRI
        // isn't recorded on the extract — Nanodash's admin role lives in a
        // published role-definition nanopub, not in a hardcoded class IRI;
        // PR-B2 will start emitting that link once role definitions are
        // extracted. The publisher check (is the publisher actually entitled
        // to grant?) happens at materialization time, not here.
        for (Statement st : np.getAssertion()) {
            if (!GEN.HAS_ADMIN.equals(st.getPredicate())) continue;
            if (!(st.getSubject() instanceof IRI spaceIri)) continue;
            if (!(st.getObject() instanceof IRI grantedAgent)) continue;
            for (String spaceRef : registry.findSpaceRefsBySpaceIri(spaceIri)) {
                emitRoleAssertion(out, spaceRef, np.getUri(),
                        GEN.HAS_ADMIN, SpaceExtract.INVERSE, grantedAgent);
                contributedSpaces.add(spaceRef);
            }
        }

        // (2) Profile-field extracts: only when this nanopub itself defines a
        // space. Extract every triple in the assertion whose subject is the
        // declared Space IRI, except the structural gen:hasRootDefinition (a
        // detection signal) and gen:hasAdmin (handled above).
        Set<IRI> definedSpaceIris = collectDefinedSpaceIris(np);
        if (!definedSpaceIris.isEmpty()) {
            for (Statement st : np.getAssertion()) {
                if (!(st.getSubject() instanceof IRI subjectIri)) continue;
                if (!definedSpaceIris.contains(subjectIri)) continue;
                IRI predicate = st.getPredicate();
                if (GEN.HAS_ROOT_DEFINITION.equals(predicate)) continue;
                if (GEN.HAS_ADMIN.equals(predicate)) continue;
                // Skip the structural rdf:type gen:Space marker, but keep other
                // type triples (declared subtypes like gen:Alliance / gen:Project).
                if (RDF.TYPE.equals(predicate) && GEN.SPACE.equals(st.getObject())) continue;
                for (String spaceRef : registry.findSpaceRefsBySpaceIri(subjectIri)) {
                    emitProfileField(out, spaceRef, np.getUri(), predicate, st.getObject());
                    contributedSpaces.add(spaceRef);
                }
            }
        }

        return new ExtractionResult(out, contributedSpaces);
    }

    private static Set<IRI> collectDefinedSpaceIris(Nanopub np) {
        boolean isSpaceTyped = false;
        for (IRI typeIri : NanopubUtils.getTypes(np)) {
            if (GEN.SPACE.equals(typeIri)) {
                isSpaceTyped = true;
                break;
            }
        }
        if (!isSpaceTyped) return Set.of();
        Set<IRI> defined = new LinkedHashSet<>();
        for (Statement st : np.getAssertion()) {
            if (!GEN.HAS_ROOT_DEFINITION.equals(st.getPredicate())) continue;
            if (st.getSubject() instanceof IRI spaceIri) {
                defined.add(spaceIri);
            }
        }
        return defined;
    }

    private static void emitRoleAssertion(List<Statement> out, String spaceRef, IRI sourceNp,
                                          IRI rolePredicate, IRI direction, IRI assignedAgent) {
        IRI graph = NPAS.forSpaceRef(spaceRef);
        String payload = rolePredicate.stringValue() + "|" + direction.stringValue() + "|" + assignedAgent.stringValue();
        IRI extract = NPAX.forHash(extractHash(spaceRef, sourceNp, SpaceExtract.ROLE_ASSERTION, payload));
        out.add(vf.createStatement(extract, RDF.TYPE, SpaceExtract.ROLE_ASSERTION, graph));
        out.add(vf.createStatement(extract, SpaceExtract.ROLE_PREDICATE, rolePredicate, graph));
        out.add(vf.createStatement(extract, SpaceExtract.ROLE_DIRECTION, direction, graph));
        out.add(vf.createStatement(extract, SpaceAuthority.AGENT, assignedAgent, graph));
        out.add(vf.createStatement(extract, SpaceAuthority.VIA_NANOPUB, sourceNp, graph));
    }

    private static void emitProfileField(List<Statement> out, String spaceRef, IRI sourceNp, IRI predicate, Value value) {
        IRI graph = NPAS.forSpaceRef(spaceRef);
        // value.stringValue() is sufficient for hashing — IRIs and literals both produce a stable string.
        // Including the predicate IRI separates "same value, different predicate" extracts.
        String payload = predicate.stringValue() + "|" + value.stringValue();
        IRI extract = NPAX.forHash(extractHash(spaceRef, sourceNp, SpaceExtract.PROFILE_FIELD, payload));
        out.add(vf.createStatement(extract, RDF.TYPE, SpaceExtract.PROFILE_FIELD, graph));
        out.add(vf.createStatement(extract, SpaceExtract.FIELD_KEY, predicate, graph));
        out.add(vf.createStatement(extract, SpaceExtract.FIELD_VALUE, value, graph));
        out.add(vf.createStatement(extract, SpaceAuthority.VIA_NANOPUB, sourceNp, graph));
    }

    /**
     * Computes the deterministic hash that identifies an extract within the
     * spaces repo. Stable across re-extraction of the same source nanopub for
     * the same space, so re-running the loader produces no duplicates. The
     * extract-class IRI participates so changing the kind of an otherwise
     * identical payload would yield a different IRI.
     */
    static String extractHash(String spaceRef, IRI sourceNp, IRI extractClass, String payload) {
        String input = spaceRef + "|" + sourceNp.stringValue() + "|" + extractClass.stringValue() + "|" + payload;
        return Hashing.sha256().hashString(input, StandardCharsets.UTF_8).toString();
    }

}
