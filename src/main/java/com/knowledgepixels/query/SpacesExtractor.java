package com.knowledgepixels.query;

import com.knowledgepixels.query.vocabulary.BackcompatRolePredicates;
import com.knowledgepixels.query.vocabulary.GEN;
import com.knowledgepixels.query.vocabulary.SpacesVocab;
import net.trustyuri.TrustyUriUtils;
import org.eclipse.rdf4j.model.*;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.DCTERMS;
import org.eclipse.rdf4j.model.vocabulary.OWL;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.nanopub.Nanopub;
import org.nanopub.NanopubUtils;
import org.nanopub.vocabulary.NPA;
import org.nanopub.vocabulary.NPX;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Pure-logic extractor from a loaded {@link Nanopub} to the add-only summary
 * triples destined for {@code npa:spacesGraph}. Implements the per-type schema
 * from {@code doc/design-space-repositories.md}.
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

    private static final Logger logger = LoggerFactory.getLogger(SpacesExtractor.class);

    private static final ValueFactory vf = SimpleValueFactory.getInstance();

    private static final IRI GRAPH = SpacesVocab.SPACES_GRAPH;

    /**
     * The set of nanopub-level type/predicate IRIs that make a nanopub "space-relevant"
     * — i.e., that dispatch to one of the per-shape extractors in {@link #extract}.
     * Shared with {@link NanopubLoader} so the spaces-load gate and the invalidation
     * propagation paths agree on a single definition of "space-relevant" without
     * needing to re-run the extractor.
     *
     * <p>Membership is checked against {@link NanopubUtils#getTypes(Nanopub)}, which
     * includes both {@code rdf:type} / {@code npx:hasNanopubType} declarations and,
     * for single-predicate-assertion nanopubs, the predicate itself — so predicate
     * markers like {@link GEN#HAS_ROLE} and {@link GEN#IS_MAINTAINED_BY} can appear
     * as types here.
     */
    public static final Set<IRI> TRIGGER_TYPES;

    static {
        Set<IRI> s = new LinkedHashSet<>();
        s.add(GEN.SPACE);
        s.add(GEN.HAS_ROLE);
        s.add(GEN.SPACE_MEMBER_ROLE);
        s.add(GEN.ROLE_INSTANTIATION);
        s.add(GEN.IS_SUB_SPACE_OF);
        s.add(GEN.MAINTAINED_RESOURCE);
        s.add(GEN.IS_MAINTAINED_BY);
        s.add(GEN.PRESET);
        s.add(GEN.PRESET_ASSIGNMENT);
        s.add(GEN.REVOKED_ROLE_INSTANTIATION);
        s.add(GEN.DETACHED_ROLE);
        s.addAll(BackcompatRolePredicates.ALL);
        TRIGGER_TYPES = Collections.unmodifiableSet(s);
    }

    private SpacesExtractor() {
    }

    /**
     * Returns {@code true} iff at least one of {@code types} is in
     * {@link #TRIGGER_TYPES} — i.e., the nanopub carries a type that dispatches
     * to one of the extractor branches. Callers should typically pass
     * {@code NanopubUtils.getTypes(np)} so that single-predicate-assertion
     * auto-typing is included.
     */
    public static boolean isSpaceRelevant(Set<IRI> types) {
        for (IRI t : types) {
            if (TRIGGER_TYPES.contains(t)) {
                return true;
            }
        }
        return false;
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
        if (!isSpaceRelevant(types)) {
            return Collections.emptyList();
        }

        List<Statement> out = new ArrayList<>();

        boolean isSpace = types.contains(GEN.SPACE);
        boolean isHasRole = types.contains(GEN.HAS_ROLE);
        boolean isSpaceMemberRole = types.contains(GEN.SPACE_MEMBER_ROLE);
        boolean isRoleInstantiation = types.contains(GEN.ROLE_INSTANTIATION)
                                      || anyMatch(types, BackcompatRolePredicates.ALL);
        boolean isSubSpaceOf = types.contains(GEN.IS_SUB_SPACE_OF);
        // Maintained-resource nanopubs use either the resource-class marker
        // (gen:MaintainedResource — what Nanodash currently writes) or the
        // predicate marker (gen:isMaintainedBy — single-predicate-assertion
        // auto-typing or explicit npx:hasNanopubType). Both shapes carry the
        // same <r> gen:isMaintainedBy <s> triple in the assertion.
        boolean isMaintainedResource = types.contains(GEN.MAINTAINED_RESOURCE)
                                       || types.contains(GEN.IS_MAINTAINED_BY);
        // Presets (Nanodash issue #302). A preset-defining nanopub is typed gen:Preset;
        // an assignment is typed gen:PresetAssignment (plus gen:Activated/Deactivated).
        // Both shapes are single-subject assertions, so NanopubUtils.getTypes promotes
        // the assertion type even without a pubinfo npx:hasNanopubType marker.
        boolean isPreset = types.contains(GEN.PRESET);
        boolean isPresetAssignment = types.contains(GEN.PRESET_ASSIGNMENT);
        // Role revocation (issue #129). RevokedRoleInstantiation is a typed node carrying
        // gen:forSpace/forAgent/hasRole; gen:detachedRole is a single-predicate-assertion
        // (auto-typed like gen:hasRole). Both emit key-level negatives, never state rows.
        boolean isRevokedRoleInstantiation = types.contains(GEN.REVOKED_ROLE_INSTANTIATION);
        boolean isDetachedRole = types.contains(GEN.DETACHED_ROLE);

        if (isSpace) {
            extractSpace(np, ctx, out);
        }
        if (isHasRole) {
            extractHasRole(np, ctx, out);
        }
        if (isSpaceMemberRole) {
            extractSpaceMemberRole(np, ctx, out);
        }
        if (isRoleInstantiation) {
            extractRoleInstantiation(np, ctx, types.contains(GEN.ROLE_INSTANTIATION), out);
        }
        if (isSubSpaceOf) {
            extractSubSpaceOf(np, ctx, out);
        }
        if (isMaintainedResource) {
            extractIsMaintainedBy(np, ctx, out);
        }
        if (isPreset) {
            extractPreset(np, ctx, out);
        }
        if (isPresetAssignment) {
            extractPresetAssignment(np, ctx, out);
        }
        if (isRevokedRoleInstantiation) {
            extractRevokedRoleInstantiation(np, ctx, out);
        }
        if (isDetachedRole) {
            extractDetachedRole(np, ctx, out);
        }

        return out;
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
            if (!st.getPredicate().equals(GEN.HAS_ROOT_DEFINITION)) {
                continue;
            }
            if (!(st.getSubject() instanceof IRI spaceIri)) {
                continue;
            }
            if (!(st.getObject() instanceof IRI rootUri)) {
                continue;
            }
            String rootNanopubId = TrustyUriUtils.getArtifactCode(rootUri.stringValue());
            if (rootNanopubId == null || rootNanopubId.isEmpty()) {
                logger.warn("Ignoring space {}: gen:hasRootDefinition target is not a trusty URI: {}",
                        spaceIri, rootUri);
                continue;
            }
            if (!handled.add(spaceIri)) {
                continue;
            }
            emitSpaceEntry(np, ctx, spaceIri, rootUri, rootNanopubId, adminAgents, out);
        }

        // Rootless transition case: any Space IRI in the assertion that didn't get a
        // hasRootDefinition triple is treated as if it were its own root. Detect by
        // looking for triples that reference a Space IRI we haven't handled yet —
        // typically via gen:hasAdmin subjects or the rdf:type gen:Space triple on a
        // blank-node assertion subject. The common template publishes the Space IRI
        // as the subject of at least one triple in the assertion, so we scan for that.
        for (Statement st : np.getAssertion()) {
            if (!(st.getSubject() instanceof IRI spaceIri)) {
                continue;
            }
            if (handled.contains(spaceIri)) {
                continue;
            }
            // Skip IRIs that clearly aren't Space IRIs (role IRIs embedded in this nanopub).
            if (spaceIri.stringValue().startsWith(np.getUri().stringValue())) {
                continue;
            }
            // Require at least one structural signal that this is a Space IRI:
            // an rdf:type gen:Space, or a gen:hasAdmin triple with this as subject.
            if (!looksLikeSpaceIri(np, spaceIri)) {
                continue;
            }
            handled.add(spaceIri);
            String rootNanopubId = TrustyUriUtils.getArtifactCode(np.getUri().stringValue());
            if (rootNanopubId == null || rootNanopubId.isEmpty()) {
                continue;
            }
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

        // Identity-derived path-prefix enumeration powering the URL-prefix sub-space
        // fallback in the materializer. Same triples on every contributor (RDF set
        // semantics dedups them).
        for (IRI prefix : enumerateIdPrefixes(spaceIri)) {
            out.add(vf.createStatement(refIri, SpacesVocab.HAS_ID_PREFIX, prefix, GRAPH));
        }

        // Embedded gen:isSubSpaceOf triples in this gen:Space nanopub: emit one
        // SubSpaceDeclaration per (spaceIri, parentIri) pair. Same shape as the
        // standalone path; downstream rules don't distinguish them.
        emitSubSpaceDeclarations(np, ctx, spaceIri, out);

        // Embedded gen:isMaintainedBy triples in this gen:Space nanopub: emit one
        // MaintainedResourceDeclaration per (resourceIri, spaceIri) pair where the
        // object equals the Space being defined. Same shape as the standalone path.
        emitMaintainedResourceDeclarations(np, ctx, spaceIri, out);

        // Embedded owl:sameAs triples: <spaceIri> owl:sameAs <aliasIri> declares that
        // <aliasIri> is an alias of the Space being defined. Emit one
        // SpaceAliasDeclaration per (spaceIri, aliasIri) pair so the materializer can
        // let this space's admin authority cover roles/members attached to the alias
        // (issue #113). Carries provenance — the materializer gates the edge on the
        // declaration's publisher being an admin of the canonical space.
        emitSpaceAliasDeclarations(np, ctx, spaceIri, out);

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

        // Inline non-hasAdmin role triples: a gen:Space nanopub may also assert
        // <space> <pred> <agent> (INVERSE) or <agent> <pred> <space> (REGULAR)
        // for any of the back-compat role predicates (has-event-facilitator,
        // participatedAsParticipantIn, …). Without an extraction path here those
        // are silently dropped because gen:Space nanopubs are not auto-typed
        // with back-compat predicates (only single-triple-assertion nanopubs are).
        // Emit one RoleInstantiation per distinct predicate found, grouping
        // multi-agent like the admin case. The subject is disambiguated by a
        // hash of the predicate IRI so multiple predicates in one nanopub don't
        // collide on the same npari:<artifactCode> subject as the admin RI.
        emitInlineRoleInstantiations(np, ctx, spaceIri, out);
    }

    /**
     * Scans the assertion of a {@code gen:Space} nanopub for inline role triples
     * (excluding {@code gen:hasAdmin}, which is handled separately as the trust
     * seed), grouping by predicate and emitting one {@link GEN#ROLE_INSTANTIATION}
     * per (predicate, direction) pair with multi-valued {@code npa:forAgent}.
     */
    private static void emitInlineRoleInstantiations(Nanopub np, Context ctx, IRI spaceIri,
                                                     List<Statement> out) {
        Map<IRI, BackcompatRolePredicates.Direction> directionByPred = new LinkedHashMap<>();
        Map<IRI, Set<IRI>> agentsByPred = new LinkedHashMap<>();
        for (Statement st : np.getAssertion()) {
            IRI predicate = st.getPredicate();
            if (GEN.HAS_ADMIN.equals(predicate)) {
                continue; // already emitted above
            }
            BackcompatRolePredicates.Direction direction = BackcompatRolePredicates.DIRECTIONS.get(predicate);
            if (direction == null) {
                continue;
            }
            if (!(st.getSubject() instanceof IRI subjIri)) {
                continue;
            }
            if (!(st.getObject() instanceof IRI objIri)) {
                continue;
            }
            IRI agent;
            if (direction == BackcompatRolePredicates.Direction.INVERSE) {
                if (!spaceIri.equals(subjIri)) {
                    continue;
                }
                agent = objIri;
            } else {
                if (!spaceIri.equals(objIri)) {
                    continue;
                }
                agent = subjIri;
            }
            directionByPred.put(predicate, direction);
            agentsByPred.computeIfAbsent(predicate, k -> new LinkedHashSet<>()).add(agent);
        }
        for (Map.Entry<IRI, Set<IRI>> entry : agentsByPred.entrySet()) {
            IRI predicate = entry.getKey();
            BackcompatRolePredicates.Direction direction = directionByPred.get(predicate);
            String predHash = Utils.createHash(predicate.stringValue());
            IRI riIri = SpacesVocab.forRoleInstantiation(ctx.artifactCode(), predHash);
            out.add(vf.createStatement(riIri, RDF.TYPE, GEN.ROLE_INSTANTIATION, GRAPH));
            out.add(vf.createStatement(riIri, SpacesVocab.FOR_SPACE, spaceIri, GRAPH));
            IRI directionPredicate = (direction == BackcompatRolePredicates.Direction.REGULAR)
                    ? SpacesVocab.REGULAR_PROPERTY
                    : SpacesVocab.INVERSE_PROPERTY;
            out.add(vf.createStatement(riIri, directionPredicate, predicate, GRAPH));
            for (IRI agent : entry.getValue()) {
                out.add(vf.createStatement(riIri, SpacesVocab.FOR_AGENT, agent, GRAPH));
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
            if (!candidate.equals(st.getSubject())) {
                continue;
            }
            if (st.getPredicate().equals(RDF.TYPE) && GEN.SPACE.equals(st.getObject())) {
                return true;
            }
            if (st.getPredicate().equals(GEN.HAS_ADMIN)) {
                return true;
            }
        }
        return false;
    }

    private static List<IRI> collectAdminAgents(Nanopub np) {
        Set<IRI> agents = new LinkedHashSet<>();
        for (Statement st : np.getAssertion()) {
            if (!st.getPredicate().equals(GEN.HAS_ADMIN)) {
                continue;
            }
            if (!(st.getObject() instanceof IRI agent)) {
                continue;
            }
            agents.add(agent);
        }
        return new ArrayList<>(agents);
    }

    // ---------------- gen:hasRole (role attachment) ----------------

    private static void extractHasRole(Nanopub np, Context ctx, List<Statement> out) {
        // A gen:hasRole nanopub asserts <space> gen:hasRole <role>.
        for (Statement st : np.getAssertion()) {
            if (!st.getPredicate().equals(GEN.HAS_ROLE)) {
                continue;
            }
            if (!(st.getSubject() instanceof IRI spaceIri)) {
                continue;
            }
            if (!(st.getObject() instanceof IRI roleIri)) {
                continue;
            }
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
            if (!st.getPredicate().equals(RDF.TYPE)) {
                continue;
            }
            if (!GEN.SPACE_MEMBER_ROLE.equals(st.getObject())) {
                continue;
            }
            if (!(st.getSubject() instanceof IRI candidate)) {
                continue;
            }
            if (!candidate.stringValue().startsWith(np.getUri().stringValue())) {
                continue;
            }
            roleIri = candidate;
            break;
        }
        if (roleIri == null) {
            return;
        }

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
            if (!roleIri.equals(st.getSubject())) {
                continue;
            }
            if (!st.getPredicate().equals(RDF.TYPE)) {
                continue;
            }
            if (!(st.getObject() instanceof IRI type)) {
                continue;
            }
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
            if (!roleIri.equals(st.getSubject())) {
                continue;
            }
            if (!predicate.equals(st.getPredicate())) {
                continue;
            }
            if (!(st.getObject() instanceof IRI obj)) {
                continue;
            }
            out.add(obj);
        }
        return out;
    }

    // ---------------- gen:RoleInstantiation (and backcompat) ----------------

    private static void extractRoleInstantiation(Nanopub np, Context ctx,
                                                 boolean explicitRoleInstantiation, List<Statement> out) {
        // Find the assignment triple. Directionality (matches the publisher convention
        // used by gen:hasRegularProperty / gen:hasInverseProperty in role-definition
        // nanopubs):
        //   REGULAR: <agent> <predicate> <space>  → npa:regularProperty.
        //   INVERSE: <space> <predicate> <agent>  → npa:inverseProperty.
        // gen:hasAdmin is hardcoded INVERSE (space-centric: <space> hasAdmin <agent>).
        // The 14 backwards-compat predicates are classified in
        // {@link BackcompatRolePredicates#DIRECTIONS}.
        for (Statement st : np.getAssertion()) {
            IRI predicate = st.getPredicate();
            BackcompatRolePredicates.Direction direction = directionFor(predicate);
            if (direction == null) {
                continue;
            }
            if (!(st.getSubject() instanceof IRI subjIri)) {
                continue;
            }
            if (!(st.getObject() instanceof IRI objIri)) {
                continue;
            }

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
            if (out.contains(typeSt)) {
                return;
            }

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

        // No predicate with a known direction. For a nanopub explicitly typed
        // gen:RoleInstantiation, the assertion still binds an agent to a space via a
        // custom role predicate declared in a gen:SpaceMemberRole nanopub (e.g.
        // gen:hasMaintainer). We can't classify its direction here — that lives in the
        // role declaration, a different nanopub the extractor can't see — so emit a
        // neutral binding carrying the raw (subject, predicate, object). The materializer
        // resolves direction + tier by joining the predicate against the role declaration
        // attached to the space (see AuthorityResolver#nonAdminTierUpdate). Gated on the
        // explicit type so we don't mint inert entries for incidental IRI-valued triples
        // in nanopubs that only matched via a backcompat predicate.
        if (!explicitRoleInstantiation) {
            return;
        }
        for (Statement st : np.getAssertion()) {
            IRI predicate = st.getPredicate();
            if (predicate.equals(RDF.TYPE) || directionFor(predicate) != null) {
                continue;
            }
            if (!(st.getSubject() instanceof IRI subjIri)) {
                continue;
            }
            if (!(st.getObject() instanceof IRI objIri)) {
                continue;
            }
            // Discriminate the subject by predicate so multiple custom-predicate triples
            // in one nanopub don't collide on the artifact-code-derived subject.
            IRI subject = SpacesVocab.forRoleInstantiation(
                    ctx.artifactCode(), Utils.createHash(predicate.stringValue()));
            out.add(vf.createStatement(subject, RDF.TYPE, GEN.ROLE_INSTANTIATION, GRAPH));
            out.add(vf.createStatement(subject, SpacesVocab.ROLE_PREDICATE, predicate, GRAPH));
            out.add(vf.createStatement(subject, SpacesVocab.BINDING_SUBJECT, subjIri, GRAPH));
            out.add(vf.createStatement(subject, SpacesVocab.BINDING_OBJECT, objIri, GRAPH));
            out.add(vf.createStatement(subject, SpacesVocab.VIA_NANOPUB, np.getUri(), GRAPH));
            addProvenance(subject, ctx, out);
        }
    }

    private static BackcompatRolePredicates.Direction directionFor(IRI predicate) {
        if (GEN.HAS_ADMIN.equals(predicate)) {
            return BackcompatRolePredicates.Direction.INVERSE;
        }
        return BackcompatRolePredicates.DIRECTIONS.get(predicate);
    }

    // ---------------- gen:isSubSpaceOf (standalone path) ----------------

    /**
     * Standalone {@code gen:isSubSpaceOf} nanopub: every
     * {@code <childIri> gen:isSubSpaceOf <parentIri>} triple in the assertion emits one
     * {@code npa:SubSpaceDeclaration}. Multi-triple assertions are allowed; one entry
     * per pair. Self-loops ({@code <X> gen:isSubSpaceOf <X>}) are rejected.
     */
    private static void extractSubSpaceOf(Nanopub np, Context ctx, List<Statement> out) {
        for (Statement st : np.getAssertion()) {
            if (!st.getPredicate().equals(GEN.IS_SUB_SPACE_OF)) {
                continue;
            }
            if (!(st.getSubject() instanceof IRI childIri)) {
                continue;
            }
            if (!(st.getObject() instanceof IRI parentIri)) {
                continue;
            }
            emitSubSpaceDeclaration(np, ctx, childIri, parentIri, out);
        }
    }

    /**
     * Embedded path: scan a {@code gen:Space} nanopub's assertion for
     * {@code <spaceIri> gen:isSubSpaceOf <parentIri>} triples (subject must equal the
     * Space IRI we're emitting an entry for, so the subspace declaration is bound to
     * this particular Space). Self-loops are rejected.
     */
    private static void emitSubSpaceDeclarations(Nanopub np, Context ctx, IRI spaceIri,
                                                 List<Statement> out) {
        for (Statement st : np.getAssertion()) {
            if (!st.getPredicate().equals(GEN.IS_SUB_SPACE_OF)) {
                continue;
            }
            if (!spaceIri.equals(st.getSubject())) {
                continue;
            }
            if (!(st.getObject() instanceof IRI parentIri)) {
                continue;
            }
            emitSubSpaceDeclaration(np, ctx, spaceIri, parentIri, out);
        }
    }

    /**
     * Emits one {@code npa:SubSpaceDeclaration} entry, keyed by
     * {@code (artifactCode, parentHash)} so a single nanopub can declare multiple
     * parents without subject collision. Self-loops are silently dropped.
     */
    private static void emitSubSpaceDeclaration(Nanopub np, Context ctx, IRI childIri,
                                                IRI parentIri, List<Statement> out) {
        if (childIri.equals(parentIri)) {
            logger.debug("Ignoring self-loop sub-space declaration on {} in {}", childIri, np.getUri());
            return;
        }
        String parentHash = Utils.createHash(parentIri);
        IRI subject = SpacesVocab.forSubSpaceDeclaration(ctx.artifactCode(), parentHash);

        // Idempotence: the embedded and standalone paths can both fire on the same
        // (np, child, parent) combination if a gen:Space nanopub somehow ends up typed
        // gen:isSubSpaceOf as well. Skip if we've already emitted the type triple for
        // this subject.
        Statement typeSt = vf.createStatement(subject, RDF.TYPE, SpacesVocab.SUB_SPACE_DECLARATION, GRAPH);
        if (out.contains(typeSt)) {
            return;
        }

        out.add(typeSt);
        out.add(vf.createStatement(subject, SpacesVocab.CHILD_SPACE, childIri, GRAPH));
        out.add(vf.createStatement(subject, SpacesVocab.PARENT_SPACE, parentIri, GRAPH));
        out.add(vf.createStatement(subject, SpacesVocab.VIA_NANOPUB, np.getUri(), GRAPH));
        addProvenance(subject, ctx, out);
    }

    // ---------------- gen:isMaintainedBy ----------------

    /**
     * Standalone {@code gen:isMaintainedBy} nanopub: every
     * {@code <resourceIri> gen:isMaintainedBy <spaceIri>} triple in the assertion emits
     * one {@code npa:MaintainedResourceDeclaration}. Multi-triple assertions are
     * allowed; one entry per pair. Self-loops ({@code <X> gen:isMaintainedBy <X>}) are
     * rejected.
     */
    private static void extractIsMaintainedBy(Nanopub np, Context ctx, List<Statement> out) {
        for (Statement st : np.getAssertion()) {
            if (!st.getPredicate().equals(GEN.IS_MAINTAINED_BY)) {
                continue;
            }
            if (!(st.getSubject() instanceof IRI resourceIri)) {
                continue;
            }
            if (!(st.getObject() instanceof IRI spaceIri)) {
                continue;
            }
            emitMaintainedResourceDeclaration(np, ctx, resourceIri, spaceIri, out);
        }
    }

    /**
     * Embedded path: scan a {@code gen:Space} nanopub's assertion for
     * {@code <resourceIri> gen:isMaintainedBy <spaceIri>} triples (object must equal
     * the Space IRI we're emitting an entry for, so the maintained-resource
     * declaration is bound to this particular Space). Self-loops are rejected.
     */
    private static void emitMaintainedResourceDeclarations(Nanopub np, Context ctx, IRI spaceIri,
                                                           List<Statement> out) {
        for (Statement st : np.getAssertion()) {
            if (!st.getPredicate().equals(GEN.IS_MAINTAINED_BY)) {
                continue;
            }
            if (!spaceIri.equals(st.getObject())) {
                continue;
            }
            if (!(st.getSubject() instanceof IRI resourceIri)) {
                continue;
            }
            emitMaintainedResourceDeclaration(np, ctx, resourceIri, spaceIri, out);
        }
    }

    /**
     * Emits one {@code npa:MaintainedResourceDeclaration} entry, keyed by
     * {@code (artifactCode, resourceHash)} so a single nanopub can declare multiple
     * maintained resources without subject collision. Self-loops are silently dropped.
     */
    private static void emitMaintainedResourceDeclaration(Nanopub np, Context ctx, IRI resourceIri,
                                                          IRI spaceIri, List<Statement> out) {
        if (resourceIri.equals(spaceIri)) {
            logger.debug("Ignoring self-loop maintained-resource declaration on {} in {}",
                    resourceIri, np.getUri());
            return;
        }
        String resourceHash = Utils.createHash(resourceIri);
        IRI subject = SpacesVocab.forMaintainedResourceDeclaration(ctx.artifactCode(), resourceHash);

        // Idempotence: the embedded (gen:Space) and standalone (gen:isMaintainedBy)
        // paths can both fire on the same (np, resource, space) combination if a
        // gen:Space nanopub somehow ends up typed gen:isMaintainedBy as well. Skip if
        // we've already emitted the type triple for this subject.
        Statement typeSt = vf.createStatement(subject, RDF.TYPE,
                SpacesVocab.MAINTAINED_RESOURCE_DECLARATION, GRAPH);
        if (out.contains(typeSt)) {
            return;
        }

        out.add(typeSt);
        out.add(vf.createStatement(subject, SpacesVocab.RESOURCE_IRI, resourceIri, GRAPH));
        out.add(vf.createStatement(subject, SpacesVocab.MAINTAINER_SPACE, spaceIri, GRAPH));
        out.add(vf.createStatement(subject, SpacesVocab.VIA_NANOPUB, np.getUri(), GRAPH));
        addProvenance(subject, ctx, out);
    }

    // ---------------- gen:Preset (preset declaration) ----------------

    /**
     * A {@code gen:Preset} nanopub bundles default views and roles. We extract only the
     * role half (views stay read-time in Nanodash; see
     * {@code doc/design-preset-role-materialization.md}): one
     * {@code npa:PresetDeclaration} carrying every {@code gen:hasRole} as
     * {@code npa:presetRole}, the {@code gen:appliesToInstancesOf} target(s), and the
     * preset's identity as {@code npa:ofPreset}.
     *
     * <p>Join robustness: the assignment's {@code gen:isAssignmentOfPreset} may name the
     * versioned preset node or the version-independent {@code dct:isVersionOf} kind
     * (Nanodash treats the kind as the canonical reference). We emit {@code npa:ofPreset}
     * for <em>both</em> so an assignment naming either joins to this declaration.
     */
    private static void extractPreset(Nanopub np, Context ctx, List<Statement> out) {
        // The preset IRI is embedded in this nanopub: <preset> rdf:type gen:Preset where
        // <preset> starts with the nanopub IRI (valid embedded mint), mirroring the
        // gen:SpaceMemberRole role-declaration rule.
        IRI presetIri = null;
        for (Statement st : np.getAssertion()) {
            if (!st.getPredicate().equals(RDF.TYPE)) {
                continue;
            }
            if (!GEN.PRESET.equals(st.getObject())) {
                continue;
            }
            if (!(st.getSubject() instanceof IRI candidate)) {
                continue;
            }
            if (!candidate.stringValue().startsWith(np.getUri().stringValue())) {
                continue;
            }
            presetIri = candidate;
            break;
        }
        if (presetIri == null) {
            return;
        }

        List<IRI> roles = collectObjects(np, presetIri, GEN.HAS_ROLE);
        List<IRI> appliesTo = collectObjects(np, presetIri, GEN.APPLIES_TO_INSTANCES_OF);
        List<IRI> kinds = collectObjects(np, presetIri, DCTERMS.IS_VERSION_OF);
        // Canonical kind: the dct:isVersionOf target, or the node IRI as fallback when the
        // preset declares no kind — same rule as Nanodash ViewDisplay.getViewKindIri().
        IRI presetKind = kinds.isEmpty() ? presetIri : kinds.get(0);

        IRI subject = SpacesVocab.forPresetDeclaration(ctx.artifactCode());
        out.add(vf.createStatement(subject, RDF.TYPE, SpacesVocab.PRESET_DECLARATION, GRAPH));
        // Canonical version-independent grouping key (latest-declaration-per-kind resolution).
        out.add(vf.createStatement(subject, SpacesVocab.PRESET_KIND, presetKind, GRAPH));
        // Lookup keys: the preset's own node IRI plus its version-independent kind, so an
        // assignment naming either is mapped to this declaration's canonical kind.
        out.add(vf.createStatement(subject, SpacesVocab.OF_PRESET, presetIri, GRAPH));
        for (IRI kind : kinds) {
            out.add(vf.createStatement(subject, SpacesVocab.OF_PRESET, kind, GRAPH));
        }
        for (IRI role : roles) {
            out.add(vf.createStatement(subject, SpacesVocab.PRESET_ROLE, role, GRAPH));
        }
        for (IRI type : appliesTo) {
            out.add(vf.createStatement(subject, SpacesVocab.APPLIES_TO_INSTANCES_OF, type, GRAPH));
        }
        out.add(vf.createStatement(subject, SpacesVocab.VIA_NANOPUB, np.getUri(), GRAPH));
        addProvenance(subject, ctx, out);
    }

    // ---------------- gen:PresetAssignment ----------------

    /**
     * A {@code gen:PresetAssignment} nanopub assigns a preset to a resource. Emits one
     * {@code npa:PresetAssignment} row recording the {@code (preset, resource)} pair and
     * its activation state. Activation is <em>active-by-default</em>: active unless the
     * assignment node is explicitly typed {@code gen:DeactivatedPresetAssignment} —
     * matching Nanodash's {@code PresetAssignment.isActive()}.
     *
     * <p>The {@code dct:created} timestamp emitted by {@link #addProvenance} is the
     * latest-wins key the validator uses to resolve same-pair assignments; it must be
     * present for the materialization to converge.
     */
    private static void extractPresetAssignment(Nanopub np, Context ctx, List<Statement> out) {
        // The assignment node is the subject of the gen:isAssignmentOfPreset triple.
        IRI assignmentNode = null;
        IRI presetIri = null;
        for (Statement st : np.getAssertion()) {
            if (!st.getPredicate().equals(GEN.IS_ASSIGNMENT_OF_PRESET)) {
                continue;
            }
            if (!(st.getSubject() instanceof IRI subj)) {
                continue;
            }
            if (!(st.getObject() instanceof IRI preset)) {
                continue;
            }
            assignmentNode = subj;
            presetIri = preset;
            break;
        }
        if (assignmentNode == null) {
            return;
        }

        IRI resource = null;
        for (Statement st : np.getAssertion()) {
            if (!assignmentNode.equals(st.getSubject())) {
                continue;
            }
            if (!st.getPredicate().equals(GEN.IS_ASSIGNMENT_FOR)) {
                continue;
            }
            if (st.getObject() instanceof IRI res) {
                resource = res;
                break;
            }
        }
        if (resource == null) {
            logger.warn("Ignoring preset assignment in {}: no gen:isAssignmentFor resource", np.getUri());
            return;
        }

        // Active-by-default: deactivated only if explicitly typed.
        boolean deactivated = false;
        for (Statement st : np.getAssertion()) {
            if (assignmentNode.equals(st.getSubject())
                && st.getPredicate().equals(RDF.TYPE)
                && GEN.DEACTIVATED_PRESET_ASSIGNMENT.equals(st.getObject())) {
                deactivated = true;
                break;
            }
        }

        IRI subject = SpacesVocab.forPresetAssignment(ctx.artifactCode());
        out.add(vf.createStatement(subject, RDF.TYPE, SpacesVocab.PRESET_ASSIGNMENT, GRAPH));
        out.add(vf.createStatement(subject, SpacesVocab.OF_PRESET, presetIri, GRAPH));
        out.add(vf.createStatement(subject, SpacesVocab.FOR_RESOURCE, resource, GRAPH));
        out.add(vf.createStatement(subject, SpacesVocab.IS_ACTIVATED, vf.createLiteral(!deactivated), GRAPH));
        out.add(vf.createStatement(subject, SpacesVocab.VIA_NANOPUB, np.getUri(), GRAPH));
        addProvenance(subject, ctx, out);
    }

    // ---------------- gen:RevokedRoleInstantiation (role revocation, issue #129) ----------------

    /**
     * A {@code gen:RevokedRoleInstantiation} nanopub asserts that an agent no longer holds a
     * role in a space (a key-level negative on {@code (space, agent, role)}). The assertion
     * shape is a typed node:
     * <pre>{@code :r a gen:RevokedRoleInstantiation ; gen:forSpace <s> ; gen:forAgent <a> ; gen:hasRole <role> .}</pre>
     * For an <em>admin</em> revocation the role is {@code gen:AdminRole} (the admin tier carries
     * no per-role IRI), so the same shape covers every tier. Emits one {@code npa:RoleRevocation}
     * row per revoked node; the {@code dct:created} from {@link #addProvenance} is the latest-wins
     * key the materializer uses to decide whether this negative shadows the standing assertion.
     * This negative never materializes as a state row — it is consumed as a suppression filter /
     * displacement DELETE in the tier where the key is bound.
     */
    private static void extractRevokedRoleInstantiation(Nanopub np, Context ctx, List<Statement> out) {
        // One pass over the assertion: index IRI-valued (subject -> predicate -> object) and
        // collect the RevokedRoleInstantiation-typed nodes. Avoids the quadratic re-scan a
        // per-node singleIriObject lookup would incur on a multi-revocation nanopub.
        Map<IRI, Map<IRI, IRI>> bySubject = new LinkedHashMap<>();
        List<IRI> revokedNodes = new ArrayList<>();
        for (Statement st : np.getAssertion()) {
            if (!(st.getSubject() instanceof IRI s) || !(st.getObject() instanceof IRI o)) {
                continue;
            }
            bySubject.computeIfAbsent(s, k -> new LinkedHashMap<>()).putIfAbsent(st.getPredicate(), o);
            if (st.getPredicate().equals(RDF.TYPE) && GEN.REVOKED_ROLE_INSTANTIATION.equals(o)) {
                revokedNodes.add(s);
            }
        }
        for (IRI node : revokedNodes) {
            Map<IRI, IRI> props = bySubject.getOrDefault(node, Map.of());
            IRI space = props.get(GEN.FOR_SPACE);
            IRI agent = props.get(GEN.FOR_AGENT);
            IRI role = props.get(GEN.HAS_ROLE);
            if (space == null || agent == null || role == null) {
                logger.warn("Ignoring role revocation node {} in {}: missing forSpace/forAgent/hasRole",
                        node, np.getUri());
                continue;
            }
            // Separator that cannot appear in an IRI (newline) so distinct (agent, role) pairs
            // in the same nanopub never collide onto one nparev: subject.
            String discriminator = Utils.createHash(agent.stringValue() + "\n" + role.stringValue());
            IRI subject = SpacesVocab.forRoleRevocation(ctx.artifactCode(), discriminator);
            out.add(vf.createStatement(subject, RDF.TYPE, SpacesVocab.ROLE_REVOCATION, GRAPH));
            out.add(vf.createStatement(subject, SpacesVocab.FOR_SPACE, space, GRAPH));
            out.add(vf.createStatement(subject, SpacesVocab.FOR_AGENT, agent, GRAPH));
            out.add(vf.createStatement(subject, SpacesVocab.REVOKED_ROLE, role, GRAPH));
            out.add(vf.createStatement(subject, SpacesVocab.VIA_NANOPUB, np.getUri(), GRAPH));
            addProvenance(subject, ctx, out);
        }
    }

    // ---------------- gen:detachedRole (role detachment, issue #129) ----------------

    /**
     * A {@code gen:detachedRole} nanopub asserts {@code <space> gen:detachedRole <role>} — the
     * stative antonym of {@code gen:hasRole}, a key-level negative on {@code (space, role)}.
     * Emits one {@code npa:RoleDetachment} row per triple; latest-wins (by {@code dct:created})
     * against direct <em>and</em> preset-derived attachments removes the role's availability in
     * the space, cascading to the instantiations anchored on it. Never materializes as a state row.
     */
    private static void extractDetachedRole(Nanopub np, Context ctx, List<Statement> out) {
        for (Statement st : np.getAssertion()) {
            if (!st.getPredicate().equals(GEN.DETACHED_ROLE)
                || !(st.getSubject() instanceof IRI spaceIri)
                || !(st.getObject() instanceof IRI roleIri)) {
                continue;
            }
            String roleHash = Utils.createHash(roleIri.stringValue());
            IRI subject = SpacesVocab.forRoleDetachment(ctx.artifactCode(), roleHash);
            out.add(vf.createStatement(subject, RDF.TYPE, SpacesVocab.ROLE_DETACHMENT, GRAPH));
            out.add(vf.createStatement(subject, SpacesVocab.FOR_SPACE, spaceIri, GRAPH));
            out.add(vf.createStatement(subject, SpacesVocab.REVOKED_ROLE, roleIri, GRAPH));
            out.add(vf.createStatement(subject, SpacesVocab.VIA_NANOPUB, np.getUri(), GRAPH));
            addProvenance(subject, ctx, out);
        }
    }

    /** Collects all IRI objects of {@code subject predicate ?o} triples in the assertion. */
    private static List<IRI> collectObjects(Nanopub np, IRI subject, IRI predicate) {
        List<IRI> out = new ArrayList<>();
        for (Statement st : np.getAssertion()) {
            if (!subject.equals(st.getSubject())) {
                continue;
            }
            if (!predicate.equals(st.getPredicate())) {
                continue;
            }
            if (st.getObject() instanceof IRI obj) {
                out.add(obj);
            }
        }
        return out;
    }

    // ---------------- owl:sameAs (space aliases) ----------------

    /**
     * Scans a {@code gen:Space} nanopub's assertion for
     * {@code <spaceIri> owl:sameAs <aliasIri>} triples (subject must equal the Space IRI
     * being emitted, so the alias declaration is bound to this particular Space) and emits
     * one {@code npa:SpaceAliasDeclaration} per {@code (spaceIri, aliasIri)} pair. The
     * Space IRI is the canonical side; the {@code owl:sameAs} object is the alias.
     * Self-aliases ({@code <X> owl:sameAs <X>}) are rejected.
     */
    private static void emitSpaceAliasDeclarations(Nanopub np, Context ctx, IRI spaceIri,
                                                   List<Statement> out) {
        for (Statement st : np.getAssertion()) {
            if (!st.getPredicate().equals(OWL.SAMEAS)) {
                continue;
            }
            if (!spaceIri.equals(st.getSubject())) {
                continue;
            }
            if (!(st.getObject() instanceof IRI aliasIri)) {
                continue;
            }
            emitSpaceAliasDeclaration(np, ctx, spaceIri, aliasIri, out);
        }
    }

    /**
     * Emits one {@code npa:SpaceAliasDeclaration} entry, keyed by
     * {@code (artifactCode, aliasHash)} so a single nanopub can declare multiple aliases
     * without subject collision. Self-aliases are silently dropped.
     */
    private static void emitSpaceAliasDeclaration(Nanopub np, Context ctx, IRI canonicalIri,
                                                  IRI aliasIri, List<Statement> out) {
        if (canonicalIri.equals(aliasIri)) {
            logger.debug("Ignoring self-alias declaration on {} in {}", canonicalIri, np.getUri());
            return;
        }
        String aliasHash = Utils.createHash(aliasIri);
        IRI subject = SpacesVocab.forSpaceAliasDeclaration(ctx.artifactCode(), aliasHash);

        // Idempotence: a single (np, canonical, alias) combination should produce one entry
        // even if emitSpaceAliasDeclarations somehow sees the triple twice.
        Statement typeSt = vf.createStatement(subject, RDF.TYPE, SpacesVocab.SPACE_ALIAS_DECLARATION, GRAPH);
        if (out.contains(typeSt)) {
            return;
        }

        out.add(typeSt);
        out.add(vf.createStatement(subject, SpacesVocab.CANONICAL_SPACE, canonicalIri, GRAPH));
        out.add(vf.createStatement(subject, SpacesVocab.ALIAS_SPACE, aliasIri, GRAPH));
        out.add(vf.createStatement(subject, SpacesVocab.VIA_NANOPUB, np.getUri(), GRAPH));
        addProvenance(subject, ctx, out);
    }

    // ---------------- ID-prefix enumeration ----------------

    /**
     * Returns the immediate URL-path parent of a Space IRI, after normalisation,
     * for the URL-prefix sub-space fallback. Strips query / fragment / trailing
     * slash, then drops the last path segment after the {@code ://} scheme
     * separator. Returns at most one IRI; empty for inputs without a scheme
     * separator or without any path beyond the host.
     *
     * <p>Direct-parent-only semantics matches Nanodash's existing
     * {@code SpaceRepository.findSubspaces(...)} URL-regex behaviour. Multi-level
     * containment queries should use SPARQL property paths
     * ({@code <ancestor> npa:hasSubSpace+ ?descendant}) which walk the chain
     * transitively, so deeper descendants remain reachable as long as the
     * intermediate Spaces exist.
     *
     * <p>Examples:
     * <pre>
     *   https://example.org/a/b/c/space  →  [https://example.org/a/b/c]
     *   https://example.org/space        →  [https://example.org]   (single segment → host)
     *   https://example.org/x/           →  [https://example.org]   (trailing slash stripped)
     *   https://example.org/a/space?q=1  →  [https://example.org/a] (query stripped)
     *   https://example.org              →  []                       (no path to strip)
     * </pre>
     */
    static List<IRI> enumerateIdPrefixes(IRI spaceIri) {
        String s = spaceIri.stringValue();
        int hash = s.indexOf('#');
        if (hash >= 0) {
            s = s.substring(0, hash);
        }
        int qmark = s.indexOf('?');
        if (qmark >= 0) {
            s = s.substring(0, qmark);
        }
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);

        int schemeEnd = s.indexOf("://");
        if (schemeEnd < 0) {
            return Collections.emptyList();
        }
        int hostStart = schemeEnd + 3;
        int hostEnd = s.indexOf('/', hostStart);
        if (hostEnd < 0) {
            return Collections.emptyList();   // host-only, nothing to strip
        }

        // Drop the last path segment. If that strips us back to the host (single-
        // segment path), return the host-only IRI as the immediate parent.
        int lastSlash = s.lastIndexOf('/');
        String parent = (lastSlash <= hostEnd) ? s.substring(0, hostEnd) : s.substring(0, lastSlash);
        return List.of(vf.createIRI(parent));
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
            if (types.contains(c)) {
                return true;
            }
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
     * @param npId       nanopub IRI
     * @param loadNumber the load counter value
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
