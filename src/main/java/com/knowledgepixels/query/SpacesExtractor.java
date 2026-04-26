package com.knowledgepixels.query;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.DCTERMS;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.nanopub.Nanopub;
import org.nanopub.NanopubUtils;
import org.nanopub.vocabulary.NPA;
import org.nanopub.vocabulary.NPX;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.knowledgepixels.query.vocabulary.BackcompatRolePredicates;
import com.knowledgepixels.query.vocabulary.GEN;
import com.knowledgepixels.query.vocabulary.SpacesVocab;

import net.trustyuri.TrustyUriUtils;

/**
 * Pure-logic extractor from a loaded {@link Nanopub} to the add-only summary
 * triples destined for {@code npa:spacesGraph}. Implements the per-type schema
 * from {@code doc/plan-space-repositories.md}.
 *
 * <p>Dispatch is by nanopub type — {@link NanopubUtils#getTypes(Nanopub)} returns
 * both {@code rdf:type} / {@code npx:hasNanopubType} declarations and, for
 * single-predicate-assertion nanopubs, the predicate itself. That means the
 * four predefined types ({@link GEN#SPACE}, {@link GEN#HAS_ROLE},
 * {@link GEN#SPACE_MEMBER_ROLE}, {@link GEN#ROLE_INSTANTIATION}) and all 14
 * {@link BackcompatRolePredicates backwards-compat predicates} can be detected
 * with a single type-set lookup.
 *
 * <p>Output: a list of RDF4J {@link Statement}s, all in the
 * {@link SpacesVocab#SPACES_GRAPH} named graph, that the caller writes into the
 * {@code spaces} repo. Deterministic and idempotent — the same nanopub always
 * produces the same statement set.
 */
public final class SpacesExtractor {

    private static final Logger log = LoggerFactory.getLogger(SpacesExtractor.class);

    private static final ValueFactory vf = SimpleValueFactory.getInstance();

    private static final IRI GRAPH = SpacesVocab.SPACES_GRAPH;

    private SpacesExtractor() {
    }

    /**
     * Bundles the information a single extraction needs beyond the nanopub itself.
     *
     * @param artifactCode trusty-URI artifact code of {@code np} (used for minting
     *                     {@code npari:}/{@code npara:}/{@code npard:}/{@code npadef:}
     *                     subject IRIs).
     * @param signedBy     signer agent IRI from pubinfo, or {@code null} if absent.
     * @param pubkeyHash   hash of the signing public key, or {@code null} if absent.
     * @param createdAt    creation timestamp, or {@code null} if the nanopub lacks one.
     */
    public record Context(String artifactCode, IRI signedBy, String pubkeyHash, Date createdAt) {
    }

    /**
     * Runs the extractor on a loaded nanopub. Returns an empty list if the nanopub is
     * not space-relevant.
     *
     * @param np  the nanopub to inspect
     * @param ctx the extraction context
     * @return statements to write into {@code npa:spacesGraph}
     */
    public static List<Statement> extract(Nanopub np, Context ctx) {
        Set<IRI> types = NanopubUtils.getTypes(np);
        List<Statement> out = new ArrayList<>();

        boolean isSpace = types.contains(GEN.SPACE);
        boolean isHasRole = types.contains(GEN.HAS_ROLE);
        boolean isSpaceMemberRole = types.contains(GEN.SPACE_MEMBER_ROLE);
        boolean isRoleInstantiation = types.contains(GEN.ROLE_INSTANTIATION)
                || anyMatch(types, BackcompatRolePredicates.ALL);

        if (!isSpace && !isHasRole && !isSpaceMemberRole && !isRoleInstantiation) {
            return Collections.emptyList();
        }

        if (isSpace) extractSpace(np, ctx, out);
        if (isHasRole) extractHasRole(np, ctx, out);
        if (isSpaceMemberRole) extractSpaceMemberRole(np, ctx, out);
        if (isRoleInstantiation) extractRoleInstantiation(np, ctx, out);

        return out;
    }

    /**
     * Emits the {@link SpacesVocab#INVALIDATION} entry for an invalidator nanopub
     * whose target has at least one space-relevant type. Caller (the loader's
     * invalidation-propagation loop) passes in the types of the invalidated
     * nanopub so we can check space-relevance without re-reading the meta repo.
     *
     * @param thisNp        the invalidator nanopub
     * @param invalidatedNp URI of the nanopub being invalidated
     * @param targetTypes   types of the invalidated nanopub (from the meta repo)
     * @param ctx           extraction context for the invalidator
     * @return the invalidation entry statements, or empty if no target type is space-relevant
     */
    public static List<Statement> extractInvalidation(Nanopub thisNp, IRI invalidatedNp,
                                                      Set<IRI> targetTypes, Context ctx) {
        if (!isSpaceRelevant(targetTypes)) return Collections.emptyList();
        IRI subject = SpacesVocab.forInvalidation(ctx.artifactCode());
        List<Statement> out = new ArrayList<>();
        out.add(vf.createStatement(subject, RDF.TYPE, SpacesVocab.INVALIDATION, GRAPH));
        out.add(vf.createStatement(subject, SpacesVocab.INVALIDATES, invalidatedNp, GRAPH));
        out.add(vf.createStatement(subject, SpacesVocab.VIA_NANOPUB, thisNp.getUri(), GRAPH));
        addProvenance(subject, ctx, out);
        return out;
    }

    /** True iff any type in {@code types} is a predefined type or a backwards-compat predicate. */
    public static boolean isSpaceRelevant(Set<IRI> types) {
        return types.contains(GEN.SPACE)
                || types.contains(GEN.HAS_ROLE)
                || types.contains(GEN.SPACE_MEMBER_ROLE)
                || types.contains(GEN.ROLE_INSTANTIATION)
                || anyMatch(types, BackcompatRolePredicates.ALL);
    }

    // ---------------- gen:Space ----------------

    private static void extractSpace(Nanopub np, Context ctx, List<Statement> out) {
        // A single gen:Space nanopub may declare multiple Space IRIs, each via its own
        // gen:hasRootDefinition triple. We emit one SpaceRef + SpaceDefinition per
        // Space IRI. A nanopub missing any hasRootDefinition is accepted as its own
        // root for every Space IRI it declares (transition backcompat).
        Set<IRI> handled = new LinkedHashSet<>();
        List<IRI> adminAgents = collectAdminAgents(np);

        // Rooted case: gen:hasRootDefinition explicitly declared.
        for (Statement st : np.getAssertion()) {
            if (!st.getPredicate().equals(GEN.HAS_ROOT_DEFINITION)) continue;
            if (!(st.getSubject() instanceof IRI spaceIri)) continue;
            if (!(st.getObject() instanceof IRI rootUri)) continue;
            String rootNanopubId = TrustyUriUtils.getArtifactCode(rootUri.stringValue());
            if (rootNanopubId == null || rootNanopubId.isEmpty()) {
                log.warn("Ignoring space {}: gen:hasRootDefinition target is not a trusty URI: {}",
                        spaceIri, rootUri);
                continue;
            }
            if (!handled.add(spaceIri)) continue;
            emitSpaceEntry(np, ctx, spaceIri, rootUri, rootNanopubId, adminAgents, out);
        }

        // Rootless transition case: any Space IRI in the assertion that didn't get a
        // hasRootDefinition triple is treated as if it were its own root. Detect by
        // looking for triples that reference a Space IRI we haven't handled yet —
        // typically via gen:hasAdmin subjects or the rdf:type gen:Space triple on a
        // blank-node assertion subject. The common template publishes the Space IRI
        // as the subject of at least one triple in the assertion, so we scan for that.
        for (Statement st : np.getAssertion()) {
            if (!(st.getSubject() instanceof IRI spaceIri)) continue;
            if (handled.contains(spaceIri)) continue;
            // Skip IRIs that clearly aren't Space IRIs (role IRIs embedded in this nanopub).
            if (spaceIri.stringValue().startsWith(np.getUri().stringValue())) continue;
            // Require at least one structural signal that this is a Space IRI:
            // an rdf:type gen:Space, or a gen:hasAdmin triple with this as subject.
            if (!looksLikeSpaceIri(np, spaceIri)) continue;
            handled.add(spaceIri);
            String rootNanopubId = TrustyUriUtils.getArtifactCode(np.getUri().stringValue());
            if (rootNanopubId == null || rootNanopubId.isEmpty()) continue;
            emitSpaceEntry(np, ctx, spaceIri, np.getUri(), rootNanopubId, adminAgents, out);
        }
    }

    private static void emitSpaceEntry(Nanopub np, Context ctx, IRI spaceIri, IRI rootUri,
                                       String rootNanopubId, List<IRI> adminAgents,
                                       List<Statement> out) {
        String spaceRef = rootNanopubId + "_" + Utils.createHash(spaceIri);
        IRI refIri = SpacesVocab.forSpaceRef(spaceRef);
        IRI defIri = SpacesVocab.forSpaceDefinition(ctx.artifactCode());

        // Aggregate entry: contributor-independent, reinforced on every contribution.
        out.add(vf.createStatement(refIri, RDF.TYPE, SpacesVocab.SPACE_REF, GRAPH));
        out.add(vf.createStatement(refIri, SpacesVocab.SPACE_IRI, spaceIri, GRAPH));
        out.add(vf.createStatement(refIri, SpacesVocab.ROOT_NANOPUB, rootUri, GRAPH));

        // Per-contributor entry: signer, pubkey, created-at, link back to nanopub.
        out.add(vf.createStatement(defIri, RDF.TYPE, SpacesVocab.SPACE_DEFINITION, GRAPH));
        out.add(vf.createStatement(defIri, SpacesVocab.FOR_SPACE_REF, refIri, GRAPH));
        out.add(vf.createStatement(defIri, SpacesVocab.VIA_NANOPUB, np.getUri(), GRAPH));
        addProvenance(defIri, ctx, out);

        // Trust seed: this is the root nanopub iff rootUri equals the nanopub's own URI.
        boolean isOwnRoot = rootUri.equals(np.getUri());
        if (isOwnRoot) {
            for (IRI adminAgent : adminAgents) {
                out.add(vf.createStatement(defIri, SpacesVocab.HAS_ROOT_ADMIN, adminAgent, GRAPH));
            }
        }

        // gen:RoleInstantiation entry for the admins asserted in this gen:Space nanopub,
        // so admins show up in the same SPARQL pattern as ordinary admin instantiations.
        if (!adminAgents.isEmpty()) {
            IRI riIri = SpacesVocab.forRoleInstantiation(ctx.artifactCode());
            out.add(vf.createStatement(riIri, RDF.TYPE, GEN.ROLE_INSTANTIATION, GRAPH));
            out.add(vf.createStatement(riIri, SpacesVocab.FOR_SPACE, spaceIri, GRAPH));
            out.add(vf.createStatement(riIri, SpacesVocab.INVERSE_PROPERTY, GEN.HAS_ADMIN, GRAPH));
            for (IRI adminAgent : adminAgents) {
                out.add(vf.createStatement(riIri, SpacesVocab.FOR_AGENT, adminAgent, GRAPH));
            }
            out.add(vf.createStatement(riIri, SpacesVocab.VIA_NANOPUB, np.getUri(), GRAPH));
            addProvenance(riIri, ctx, out);
        }
    }

    /**
     * Heuristic: does {@code candidate} look like a Space IRI in {@code np}'s assertion,
     * independent of any {@code gen:hasRootDefinition} triple? We accept it if the
     * assertion contains {@code candidate rdf:type gen:Space} or
     * {@code candidate gen:hasAdmin ?x}.
     */
    private static boolean looksLikeSpaceIri(Nanopub np, IRI candidate) {
        for (Statement st : np.getAssertion()) {
            if (!candidate.equals(st.getSubject())) continue;
            if (st.getPredicate().equals(RDF.TYPE) && GEN.SPACE.equals(st.getObject())) return true;
            if (st.getPredicate().equals(GEN.HAS_ADMIN)) return true;
        }
        return false;
    }

    private static List<IRI> collectAdminAgents(Nanopub np) {
        Set<IRI> agents = new LinkedHashSet<>();
        for (Statement st : np.getAssertion()) {
            if (!st.getPredicate().equals(GEN.HAS_ADMIN)) continue;
            if (!(st.getObject() instanceof IRI agent)) continue;
            agents.add(agent);
        }
        return new ArrayList<>(agents);
    }

    // ---------------- gen:hasRole (role attachment) ----------------

    private static void extractHasRole(Nanopub np, Context ctx, List<Statement> out) {
        // A gen:hasRole nanopub asserts <space> gen:hasRole <role>.
        for (Statement st : np.getAssertion()) {
            if (!st.getPredicate().equals(GEN.HAS_ROLE)) continue;
            if (!(st.getSubject() instanceof IRI spaceIri)) continue;
            if (!(st.getObject() instanceof IRI roleIri)) continue;
            IRI subject = SpacesVocab.forRoleAssignment(ctx.artifactCode());
            out.add(vf.createStatement(subject, RDF.TYPE, GEN.ROLE_ASSIGNMENT, GRAPH));
            out.add(vf.createStatement(subject, SpacesVocab.FOR_SPACE, spaceIri, GRAPH));
            out.add(vf.createStatement(subject, GEN.HAS_ROLE, roleIri, GRAPH));
            out.add(vf.createStatement(subject, SpacesVocab.VIA_NANOPUB, np.getUri(), GRAPH));
            addProvenance(subject, ctx, out);
            // One attachment per nanopub — the subject IRI is derived from the nanopub
            // artifact code so multiple hasRole triples in the same nanopub would collide.
            // If that case shows up in practice, we'll refine the subject-minting scheme.
            return;
        }
    }

    // ---------------- gen:SpaceMemberRole (role declaration) ----------------

    private static void extractSpaceMemberRole(Nanopub np, Context ctx, List<Statement> out) {
        // The role IRI is embedded in this nanopub, so look for an assertion statement
        // of the shape <roleIri> rdf:type gen:SpaceMemberRole where <roleIri> starts
        // with the nanopub IRI (valid embedded mint).
        IRI roleIri = null;
        for (Statement st : np.getAssertion()) {
            if (!st.getPredicate().equals(RDF.TYPE)) continue;
            if (!GEN.SPACE_MEMBER_ROLE.equals(st.getObject())) continue;
            if (!(st.getSubject() instanceof IRI candidate)) continue;
            if (!candidate.stringValue().startsWith(np.getUri().stringValue())) continue;
            roleIri = candidate;
            break;
        }
        if (roleIri == null) return;

        IRI roleType = findRoleTier(np, roleIri);
        List<IRI> regulars = collectRolePredicate(np, roleIri, GEN.HAS_REGULAR_PROPERTY);
        List<IRI> inverses = collectRolePredicate(np, roleIri, GEN.HAS_INVERSE_PROPERTY);

        IRI subject = SpacesVocab.forRoleDeclaration(ctx.artifactCode());
        out.add(vf.createStatement(subject, RDF.TYPE, SpacesVocab.ROLE_DECLARATION, GRAPH));
        out.add(vf.createStatement(subject, SpacesVocab.ROLE, roleIri, GRAPH));
        out.add(vf.createStatement(subject, SpacesVocab.HAS_ROLE_TYPE, roleType, GRAPH));
        for (IRI reg : regulars) {
            out.add(vf.createStatement(subject, GEN.HAS_REGULAR_PROPERTY, reg, GRAPH));
        }
        for (IRI inv : inverses) {
            out.add(vf.createStatement(subject, GEN.HAS_INVERSE_PROPERTY, inv, GRAPH));
        }
        out.add(vf.createStatement(subject, SpacesVocab.VIA_NANOPUB, np.getUri(), GRAPH));
        if (ctx.createdAt() != null) {
            out.add(vf.createStatement(subject, DCTERMS.CREATED, vf.createLiteral(ctx.createdAt()), GRAPH));
        }
    }

    /**
     * Looks for a tier rdf:type ({@code gen:MaintainerRole} / {@code gen:MemberRole} /
     * {@code gen:ObserverRole}) on the role IRI in the assertion; defaults to
     * {@code gen:ObserverRole} if none is declared.
     */
    private static IRI findRoleTier(Nanopub np, IRI roleIri) {
        for (Statement st : np.getAssertion()) {
            if (!roleIri.equals(st.getSubject())) continue;
            if (!st.getPredicate().equals(RDF.TYPE)) continue;
            if (!(st.getObject() instanceof IRI type)) continue;
            if (GEN.MAINTAINER_ROLE.equals(type) || GEN.MEMBER_ROLE.equals(type)
                    || GEN.OBSERVER_ROLE.equals(type)) {
                return type;
            }
        }
        return GEN.OBSERVER_ROLE;
    }

    private static List<IRI> collectRolePredicate(Nanopub np, IRI roleIri, IRI predicate) {
        List<IRI> out = new ArrayList<>();
        for (Statement st : np.getAssertion()) {
            if (!roleIri.equals(st.getSubject())) continue;
            if (!predicate.equals(st.getPredicate())) continue;
            if (!(st.getObject() instanceof IRI obj)) continue;
            out.add(obj);
        }
        return out;
    }

    // ---------------- gen:RoleInstantiation (and backcompat) ----------------

    private static void extractRoleInstantiation(Nanopub np, Context ctx, List<Statement> out) {
        // Find the assignment triple. Directionality (matches the publisher convention
        // used by gen:hasRegularProperty / gen:hasInverseProperty in role-definition
        // nanopubs):
        //   REGULAR: <agent> <predicate> <space>  → npa:regularProperty.
        //   INVERSE: <space> <predicate> <agent>  → npa:inverseProperty.
        // gen:hasAdmin is hardcoded INVERSE (space-centric: <space> hasAdmin <agent>).
        // The 14 backwards-compat predicates are classified in
        // {@link BackcompatRolePredicates#DIRECTIONS}. User-defined role predicates from
        // gen:SpaceMemberRole nanopubs aren't resolvable here without the role-declaration
        // registry; FIXME: the materializer in PR 2 should refine direction for the
        // typed-but-unknown-predicate case. For now we emit only triples whose predicate
        // we know the direction of.
        for (Statement st : np.getAssertion()) {
            IRI predicate = st.getPredicate();
            BackcompatRolePredicates.Direction direction = directionFor(predicate);
            if (direction == null) continue;
            if (!(st.getSubject() instanceof IRI subjIri)) continue;
            if (!(st.getObject() instanceof IRI objIri)) continue;

            IRI spaceSide;
            IRI agentSide;
            if (direction == BackcompatRolePredicates.Direction.REGULAR) {
                agentSide = subjIri;
                spaceSide = objIri;
            } else {
                spaceSide = subjIri;
                agentSide = objIri;
            }

            // Deduplicate against the (possibly already emitted) admin instantiation
            // from the gen:Space path — a single nanopub can be typed gen:Space AND
            // have a gen:hasAdmin triple that the backcompat list also catches. The
            // subject IRI is the same (derived from artifact code) and the payload
            // would conflict if re-emitted. Skip if we already have a RoleInstantiation
            // entry on this subject.
            IRI subject = SpacesVocab.forRoleInstantiation(ctx.artifactCode());
            Statement typeSt = vf.createStatement(subject, RDF.TYPE, GEN.ROLE_INSTANTIATION, GRAPH);
            if (out.contains(typeSt)) return;

            out.add(typeSt);
            out.add(vf.createStatement(subject, SpacesVocab.FOR_SPACE, spaceSide, GRAPH));
            IRI directionPredicate = (direction == BackcompatRolePredicates.Direction.REGULAR)
                    ? SpacesVocab.REGULAR_PROPERTY
                    : SpacesVocab.INVERSE_PROPERTY;
            out.add(vf.createStatement(subject, directionPredicate, predicate, GRAPH));
            out.add(vf.createStatement(subject, SpacesVocab.FOR_AGENT, agentSide, GRAPH));
            out.add(vf.createStatement(subject, SpacesVocab.VIA_NANOPUB, np.getUri(), GRAPH));
            addProvenance(subject, ctx, out);
            return;
        }
    }

    private static BackcompatRolePredicates.Direction directionFor(IRI predicate) {
        if (GEN.HAS_ADMIN.equals(predicate)) return BackcompatRolePredicates.Direction.INVERSE;
        return BackcompatRolePredicates.DIRECTIONS.get(predicate);
    }

    // ---------------- shared helpers ----------------

    private static void addProvenance(Resource subject, Context ctx, List<Statement> out) {
        if (ctx.signedBy() != null) {
            out.add(vf.createStatement(subject, NPX.SIGNED_BY, ctx.signedBy(), GRAPH));
        }
        if (ctx.pubkeyHash() != null) {
            out.add(vf.createStatement(subject, SpacesVocab.PUBKEY_HASH,
                    vf.createLiteral(ctx.pubkeyHash()), GRAPH));
        }
        if (ctx.createdAt() != null) {
            Literal ts = vf.createLiteral(ctx.createdAt());
            out.add(vf.createStatement(subject, DCTERMS.CREATED, ts, GRAPH));
        }
    }

    private static boolean anyMatch(Set<IRI> types, Set<IRI> candidates) {
        for (IRI c : candidates) {
            if (types.contains(c)) return true;
        }
        return false;
    }

    // ---------------- load-number stamping ----------------

    /**
     * Stamps {@code <thisNP> npa:hasLoadNumber <N>} on the given nanopub. Intended to
     * be called by the loader once per nanopub, in the same transaction as the
     * extraction writes. Also bumps {@code npa:thisRepo npa:currentLoadCounter <N>}
     * in the admin graph so the materializer's delta cycles know the horizon.
     *
     * @param npId        nanopub IRI
     * @param loadNumber  the load counter value
     * @return two statements: load-number stamp + current-load-counter value
     */
    public static List<Statement> loadCounterStatements(IRI npId, long loadNumber) {
        List<Statement> out = new ArrayList<>(2);
        Literal lit = vf.createLiteral(loadNumber);
        out.add(vf.createStatement(npId, NPA.HAS_LOAD_NUMBER, lit, NPA.GRAPH));
        out.add(vf.createStatement(NPA.THIS_REPO, SpacesVocab.CURRENT_LOAD_COUNTER, lit, NPA.GRAPH));
        return out;
    }

}
