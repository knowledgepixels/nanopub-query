package com.knowledgepixels.query;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.eclipse.rdf4j.common.transaction.IsolationLevels;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.FOAF;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.QueryLanguage;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryResult;
import org.nanopub.vocabulary.NPA;
import org.nanopub.vocabulary.NPX;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.knowledgepixels.query.vocabulary.GEN;
import com.knowledgepixels.query.vocabulary.NPAT;
import com.knowledgepixels.query.vocabulary.SpacesVocab;

/**
 * Drives the space-state materialization pipeline. Three entry points scheduled
 * by {@code MainVerticle}:
 * <ul>
 *   <li>{@link #tick()} — detects trust-state flips (full build) and otherwise
 *       advances the current space-state graph by an {@link #runIncrementalCycle
 *       incremental cycle} bounded by {@code (processedUpTo, currentLoadCounter]}.</li>
 *   <li>{@link #periodicRebuildTick()} — checks the {@code npa:needsFullRebuild}
 *       flag set by structural invalidations and re-runs the full build into a
 *       fresh graph, atomically flips the pointer, drops the old graph.</li>
 *   <li>{@link #cleanOrphans()} — startup cleanup of {@code npass:*} graphs the
 *       pointer isn't referencing.</li>
 * </ul>
 *
 * <p>Incremental cycle order: invalidation DELETEs (admin RI / RoleAssignment /
 * non-admin RI) → mirror-step delta is implicit (rebuilt only on full build) →
 * per-tier INSERTs (admin → alias → attachment → maintainer → member → observer) →
 * late-arrival sweep (re-run downstream tiers without the load-number filter
 * iff this cycle added any structural rows). Sets {@code npa:needsFullRebuild}
 * when an admin RI / RoleAssignment / RoleDeclaration was invalidated; periodic
 * worker turns the flag into a from-scratch rebuild.
 *
 * <p>See {@code doc/design-space-repositories.md} — this implements the "Full
 * build", "Incremental cycle", and "Periodic full rebuild" procedures.
 */
public final class AuthorityResolver {

    private static final Logger logger = LoggerFactory.getLogger(AuthorityResolver.class);

    private static final ValueFactory vf = SimpleValueFactory.getInstance();

    private static final String SPACES_REPO = "spaces";
    private static final String TRUST_REPO = "trust";

    /** NPA constants pulled in locally (trust-side). */
    private static final IRI NPA_HAS_CURRENT_TRUST_STATE =
            vf.createIRI(NPA.NAMESPACE, "hasCurrentTrustState");
    private static final IRI NPA_ACCOUNT_STATE = vf.createIRI(NPA.NAMESPACE, "AccountState");
    private static final IRI NPA_AGENT = vf.createIRI(NPA.NAMESPACE, "agent");
    private static final IRI NPA_PUBKEY = vf.createIRI(NPA.NAMESPACE, "pubkey");
    private static final IRI NPA_TRUST_STATUS = vf.createIRI(NPA.NAMESPACE, "trustStatus");
    private static final IRI NPA_LOADED = vf.createIRI(NPA.NAMESPACE, "loaded");
    private static final IRI NPA_TO_LOAD = vf.createIRI(NPA.NAMESPACE, "toLoad");

    /**
     * Trust-approved set: rows with one of these {@code npa:trustStatus} values
     * are mirrored into the space-state graph. Per
     * {@code doc/design-trust-state-repos.md}, these are the two "authority-
     * approving" statuses; {@code npa:contested}, {@code npa:skipped}, and the
     * transient statuses are distinct values of the same predicate and are
     * excluded automatically by this positive-list filter.
     */
    private static final Set<IRI> APPROVED_SET = Set.of(NPA_LOADED, NPA_TO_LOAD);

    private static AuthorityResolver instance;

    /** Returns the singleton. */
    public static synchronized AuthorityResolver get() {
        if (instance == null) {
            instance = new AuthorityResolver();
        }
        return instance;
    }

    private AuthorityResolver() {
    }

    // ---------------- Operational metrics snapshot ----------------
    //
    // Updated at the end of each runFullBuild / runIncrementalCycle, read by
    // MetricsCollector via the get*() accessors below. volatile is enough —
    // writers serialise via the synchronized methods, and readers (Prometheus
    // scrapes) only need most-recent visibility, not transactional consistency
    // across the snapshot. Defaults to zero values so a scrape that races a
    // boot before the first cycle returns 0, not NaN.

    private volatile TierSubjectTotals lastSubjectTotals = new TierSubjectTotals(0L, 0L, 0L);
    private volatile long lastInsertedTriplesTotal;
    private volatile long lastFullBuildDurationMs;
    private volatile long lastIncrementalCycleDurationMs;
    private volatile long lastProcessedUpToLag;

    public TierSubjectTotals getLastSubjectTotals() { return lastSubjectTotals; }
    public long getLastInsertedTriplesTotal() { return lastInsertedTriplesTotal; }
    public long getLastFullBuildDurationMs() { return lastFullBuildDurationMs; }
    public long getLastIncrementalCycleDurationMs() { return lastIncrementalCycleDurationMs; }
    public long getLastProcessedUpToLag() { return lastProcessedUpToLag; }

    // ---------------- Public entry points ----------------

    /**
     * Poll entry point. Behaviour:
     * <ul>
     *   <li>If no current space-state graph or the trust state has flipped → full build.</li>
     *   <li>Otherwise → {@link #runIncrementalCycle incremental cycle} on the load-number
     *       delta {@code (processedUpTo, currentLoadCounter]}. No-op if {@code
     *       processedUpTo == currentLoadCounter}.</li>
     * </ul>
     * Safe to call repeatedly on a schedule. Gated by {@link FeatureFlags#spacesEnabled()}.
     */
    public void tick() {
        if (!FeatureFlags.spacesEnabled()) return;
        String trustStateHash = TrustStateRegistry.get().getCurrentHash().orElse(null);
        if (trustStateHash == null) {
            logger.debug("AuthorityResolver.tick: no current trust state yet — skipping");
            return;
        }
        IRI currentGraph = getCurrentSpaceStateGraph();
        String currentGraphName = (currentGraph == null) ? null
                : currentGraph.stringValue().substring(SpacesVocab.NPASS_NAMESPACE.length());
        if (currentGraphName == null || !currentGraphName.startsWith(trustStateHash + "_")) {
            logger.info("AuthorityResolver.tick: trust-state flip detected (now {}); running full build",
                    abbrev(trustStateHash));
            runFullBuild(trustStateHash);
            return;
        }
        runIncrementalCycle(currentGraph);
    }

    /**
     * Periodic worker. If {@code npa:needsFullRebuild} was raised by an
     * incremental cycle's structural DELETE, runs a from-scratch rebuild into
     * a fresh space-state graph (using the current trust-state hash and load
     * counter) and clears the flag. No-op when the flag is not set. Safe to
     * call concurrently with {@link #tick()} when both are scheduled on the
     * same single-threaded executor.
     */
    public void periodicRebuildTick() {
        if (!FeatureFlags.spacesEnabled()) return;
        if (!readNeedsFullRebuild()) return;
        String trustStateHash = TrustStateRegistry.get().getCurrentHash().orElse(null);
        if (trustStateHash == null) {
            logger.debug("AuthorityResolver.periodicRebuildTick: no current trust state — deferring");
            return;
        }
        logger.info("AuthorityResolver.periodicRebuildTick: needsFullRebuild flag set; rebuilding");
        runFullBuild(trustStateHash);
        clearNeedsFullRebuild();
    }

    /**
     * Startup cleanup: drop any {@code npass:*} graph that the
     * {@code npa:hasCurrentSpaceState} pointer isn't pointing at. Orphans come
     * from crashes mid-build. Safe to call at any time; idempotent.
     */
    public void cleanOrphans() {
        if (!FeatureFlags.spacesEnabled()) return;
        IRI current = getCurrentSpaceStateGraph();
        try (RepositoryConnection conn = TripleStore.get().getRepoConnection(SPACES_REPO)) {
            int dropped = 0;
            try (RepositoryResult<org.eclipse.rdf4j.model.Resource> ctxs = conn.getContextIDs()) {
                List<IRI> toDrop = new ArrayList<>();
                while (ctxs.hasNext()) {
                    org.eclipse.rdf4j.model.Resource ctx = ctxs.next();
                    if (!(ctx instanceof IRI iri)) continue;
                    if (!iri.stringValue().startsWith(SpacesVocab.NPASS_NAMESPACE)) continue;
                    if (iri.equals(current)) continue;
                    toDrop.add(iri);
                }
                for (IRI iri : toDrop) {
                    conn.begin(IsolationLevels.SERIALIZABLE);
                    conn.clear(iri);
                    conn.commit();
                    dropped++;
                    logger.info("AuthorityResolver.cleanOrphans: dropped orphan graph {}", iri);
                }
            }
            if (dropped == 0) {
                logger.debug("AuthorityResolver.cleanOrphans: no orphan space-state graphs");
            }
        } catch (Exception ex) {
            logger.info("AuthorityResolver.cleanOrphans: failed: {}", ex.toString());
        }
    }

    // ---------------- Full build ----------------

    /**
     * Mutex-protected full build of the space-state graph for the given trust
     * state. Captures {@code M = currentLoadCounter}, mirrors trust-approved
     * rows, (PR 2b: runs per-tier UPDATE loops from scratch), stamps
     * {@code processedUpTo = M}, flips the pointer, drops the previous graph.
     */
    synchronized void runFullBuild(String trustStateHash) {
        long startNanos = System.nanoTime();
        long loadCounter = getCurrentLoadCounter();
        IRI newGraph = SpacesVocab.forSpaceState(trustStateHash, loadCounter);
        IRI oldGraph = getCurrentSpaceStateGraph();
        if (newGraph.equals(oldGraph)) {
            logger.debug("AuthorityResolver.runFullBuild: already current at {}", newGraph);
            return;
        }

        // 1. Mirror trust-approved rows into the new graph.
        int mirrored = mirrorTrustState(trustStateHash, newGraph);

        // 2. Per-tier UPDATE loops (from scratch: lastProcessed = -1 so the
        //    delta filter FILTER(?ln > ?lastProcessed) includes everything).
        TierInsertedTriples counts = runAllTierLoops(newGraph, -1);

        // 3. Stamp processedUpTo inside the new graph.
        writeProcessedUpTo(newGraph, loadCounter);

        // 4. Flip the current-space-state pointer.
        flipPointer(newGraph);

        // 5. Drop the old graph if one existed.
        if (oldGraph != null) {
            dropGraph(oldGraph);
        }

        TierSubjectTotals totals = computeTierSubjectTotals(newGraph);
        long durationMs = (System.nanoTime() - startNanos) / 1_000_000L;
        lastSubjectTotals = totals;
        lastInsertedTriplesTotal = (long) counts.admin + counts.alias + counts.presetAttachment
                + counts.presetAssignmentRef
                + counts.attachment + counts.maintainer + counts.member + counts.observer
                + counts.subSpace + counts.subSpacePrefix + counts.maintainedResource;
        lastFullBuildDurationMs = durationMs;
        lastProcessedUpToLag = 0L;
        logger.info("AuthorityResolver: full build complete — graph={} mirrored={} rows loadCounter={} "
                        + "subjects: adminRIs={} attachmentRAs={} nonAdminRIs={} "
                        + "(inserted-triples: admin={} alias={} preset-attachment={} preset-assignment-ref={} attachment={} maintainer={} member={} observer={} "
                        + "subspace={} subspace-prefix={} maintained-resource={}) durationMs={}",
                newGraph, mirrored, loadCounter,
                totals.adminRIs(), totals.attachmentRAs(), totals.nonAdminRIs(),
                counts.admin, counts.alias, counts.presetAttachment, counts.presetAssignmentRef, counts.attachment, counts.maintainer, counts.member, counts.observer,
                counts.subSpace, counts.subSpacePrefix, counts.maintainedResource,
                durationMs);
    }

    // ---------------- Incremental cycle ----------------

    /**
     * Single delta cycle on the current space-state graph. Bounded by
     * {@code (processedUpTo, currentLoadCounter]}; no-op if the range is empty.
     *
     * <p>Order:
     * <ol>
     *   <li>Apply invalidation DELETEs (admin RI, RoleAssignment, non-admin RI)
     *       and the RoleDeclaration ASK. Any DELETE on a structural kind sets
     *       {@code npa:needsFullRebuild} to bound the staleness from sticky
     *       downstream entries; the periodic worker turns that into a from-scratch
     *       rebuild on its next pass.</li>
     *   <li>Run per-tier INSERTs in the same order as the full build.</li>
     *   <li>Late-arrival sweep: if any structural row was added, re-run downstream
     *       tier INSERTs with {@code lastProcessed = -1} to catch candidates whose
     *       enabling event landed in this same cycle. Dedup filters protect
     *       against double-insert.</li>
     *   <li>Bump {@code processedUpTo} to {@code currentLoadCounter}.</li>
     * </ol>
     */
    synchronized void runIncrementalCycle(IRI graph) {
        long startNanos = System.nanoTime();
        long currentLoadCounter = getCurrentLoadCounter();
        long lastProcessed = readProcessedUpTo(graph);
        if (lastProcessed < 0) {
            logger.warn("AuthorityResolver.runIncrementalCycle: missing processedUpTo on {}; skipping",
                    graph);
            return;
        }
        lastProcessedUpToLag = currentLoadCounter - lastProcessed;
        if (currentLoadCounter <= lastProcessed) {
            logger.debug("AuthorityResolver.runIncrementalCycle: caught up at load {} on {}",
                    currentLoadCounter, graph);
            return;
        }

        boolean structuralInvalidation = applyInvalidations(graph, lastProcessed);
        TierInsertedTriples counts = runAllTierLoops(graph, lastProcessed);
        boolean structuralAdds = (counts.admin > 0)
                || (counts.alias > 0)
                || (counts.presetAttachment > 0)
                || (counts.attachment > 0)
                || (counts.subSpace > 0)
                || newRoleDeclarationsArrived(lastProcessed)
                || newPresetAssignmentsArrived(lastProcessed);
        if (structuralAdds) {
            // Late-arrival sweep: leaf tiers (attachment/maintainer/member/observer)
            // can promote candidates whose enabling event arrived in this same cycle.
            // Sub-space admit is also re-run here for Mode-B late-arrival (a new
            // partner declaration can validate an older primary that the regular
            // pass's load-number filter excluded). The URL-prefix fallback also
            // re-runs so newly-orphaned children pick up derived edges. Skip the
            // admin tier — its only enabling event is the admin grant itself,
            // already handled by the regular pass.
            TierInsertedTriples lateCounts = runDownstreamWithoutLoadFilter(graph);
            counts.alias              += lateCounts.alias;
            counts.presetAttachment   += lateCounts.presetAttachment;
            counts.presetAssignmentRef += lateCounts.presetAssignmentRef;
            counts.attachment         += lateCounts.attachment;
            counts.maintainer         += lateCounts.maintainer;
            counts.member             += lateCounts.member;
            counts.observer           += lateCounts.observer;
            counts.subSpace           += lateCounts.subSpace;
            counts.subSpacePrefix     += lateCounts.subSpacePrefix;
            counts.maintainedResource += lateCounts.maintainedResource;
        }

        writeProcessedUpTo(graph, currentLoadCounter);

        TierSubjectTotals totals = computeTierSubjectTotals(graph);
        long durationMs = (System.nanoTime() - startNanos) / 1_000_000L;
        lastSubjectTotals = totals;
        lastInsertedTriplesTotal = (long) counts.admin + counts.alias + counts.presetAttachment
                + counts.presetAssignmentRef
                + counts.attachment + counts.maintainer + counts.member + counts.observer
                + counts.subSpace + counts.subSpacePrefix + counts.maintainedResource;
        lastIncrementalCycleDurationMs = durationMs;
        logger.info("AuthorityResolver: incremental cycle complete — graph={} delta=({}, {}] "
                        + "subjects: adminRIs={} attachmentRAs={} nonAdminRIs={} "
                        + "(inserted-triples: admin={} alias={} preset-attachment={} preset-assignment-ref={} attachment={} maintainer={} member={} observer={} "
                        + "subspace={} subspace-prefix={} maintained-resource={}) "
                        + "structuralInvalidation={} structuralAdds={} durationMs={}",
                graph, lastProcessed, currentLoadCounter,
                totals.adminRIs(), totals.attachmentRAs(), totals.nonAdminRIs(),
                counts.admin, counts.alias, counts.presetAttachment, counts.presetAssignmentRef, counts.attachment, counts.maintainer, counts.member, counts.observer,
                counts.subSpace, counts.subSpacePrefix, counts.maintainedResource,
                structuralInvalidation, structuralAdds, durationMs);
    }

    /**
     * Runs the four invalidation-DELETE / ASK steps. Sets {@code npa:needsFullRebuild}
     * when admin-RI, RoleAssignment, or RoleDeclaration invalidations matched (the
     * three structural kinds). Leaf-tier RI deletes don't set the flag.
     *
     * @return true iff at least one structural kind was invalidated
     */
    boolean applyInvalidations(IRI graph, long lastProcessed) {
        boolean structural = false;
        if (wouldInvalidate(graph, lastProcessed, /*adminPinned=*/ true,
                            adminInvalidationCheckWhere(graph, lastProcessed))) {
            executeUpdate(adminInvalidationDelete(graph, lastProcessed));
            structural = true;
        }
        if (wouldInvalidate(graph, lastProcessed, /*adminPinned=*/ false,
                            roleAssignmentInvalidationCheckWhere(graph, lastProcessed))) {
            executeUpdate(roleAssignmentInvalidationDelete(graph, lastProcessed));
            structural = true;
        }
        // RoleDeclaration ASK only — RDs aren't materialized into the space-state
        // graph, so there's nothing to DELETE here. The flag still flips because
        // sticky downstream RIs derived from the now-invalidated RD need a
        // from-scratch recompute.
        if (wouldInvalidate(graph, lastProcessed, /*adminPinned=*/ false,
                            roleDeclarationInvalidationCheckWhere(lastProcessed))) {
            structural = true;
        }
        // Sub-space declarations are structural — invalidating one (Mode A) or one
        // of two co-declarations (Mode B) changes the validated parent/child
        // topology. The DELETE removes the per-declaration row; the convenience
        // direct triples are left sticky and cleaned on the next periodic rebuild.
        if (wouldInvalidate(graph, lastProcessed, /*adminPinned=*/ false,
                            subSpaceInvalidationCheckWhere(graph, lastProcessed))) {
            executeUpdate(subSpaceInvalidationDelete(graph, lastProcessed));
            structural = true;
        }
        // Space-alias declarations are structural — invalidating one removes an
        // owl:sameAs edge that feeds the admin-authority closure (issue #113). The
        // DELETE removes the per-declaration row; the convenience npa:sameAsSpace edge
        // is left sticky and cleaned on the next periodic rebuild (same policy as
        // sub-space declarations).
        if (wouldInvalidate(graph, lastProcessed, /*adminPinned=*/ false,
                            aliasInvalidationCheckWhere(graph, lastProcessed))) {
            executeUpdate(aliasInvalidationDelete(graph, lastProcessed));
            structural = true;
        }
        // Preset-derived RoleAssignment removal (issue #302). NOT npx:invalidates: a newer
        // admin-authored same-(preset,resource) assignment supersedes by dct:created (a
        // gen:DeactivatedPresetAssignment, or any newer assignment that is no longer active).
        // Structural — sticky downstream non-admin RIs derived through a removed attachment
        // are bounded by the periodic full rebuild. The DELETE is scoped by
        // npa:derivedFromPreset so directly-published gen:hasRole attachments are never
        // touched; the §4.3 re-INSERT re-materializes only currently-active pairs in the same
        // cycle. See doc/design-preset-role-materialization.md §4.4.
        if (wouldInvalidate(graph, lastProcessed, /*adminPinned=*/ false,
                            presetDeactivationCheckWhere(graph, lastProcessed))) {
            executeUpdate(presetDeactivationDelete(graph, lastProcessed));
            structural = true;
        }
        // Leaf-tier RI deletes — no flag.
        executeUpdate(leafTierInvalidationDelete(graph, lastProcessed));
        // Ref-scoped preset-assignment listing stamps whose assignment nanopub was
        // hard-retracted (issue #122) — no flag (display leaf, nothing downstream).
        executeUpdate(presetAssignmentRefInvalidationDelete(graph, lastProcessed));
        // Maintained-resource declaration deletes — no flag (leaf relation, no
        // downstream caches to bound).
        executeUpdate(maintainedResourceInvalidationDelete(graph, lastProcessed));
        if (structural) setNeedsFullRebuild();
        return structural;
    }

    /**
     * Runs the four leaf tiers (attachment/maintainer/member/observer) with
     * {@code lastProcessed = -1} so the load-number filter on the candidate
     * side admits everything. Dedup filters in the tier templates prevent
     * double-insert. Used by the late-arrival sweep.
     */
    TierInsertedTriples runDownstreamWithoutLoadFilter(IRI graph) {
        TierInsertedTriples c = new TierInsertedTriples();
        // Alias late-arrival: catches alias declarations whose canonical admin grant
        // became valid only in this same cycle (the load-number filter on the
        // declaration's nanopub would otherwise exclude it). Runs first so the
        // attachment / role tiers below see this cycle's fresh npa:sameAsSpace edges.
        c.alias = runTierLabeled("alias(late)", graph, aliasAdmitUpdate(graph, -1));
        // Sub-space late-arrival: catches Mode-B candidates whose primary
        // declaration is older than lastProcessed but whose partner just landed.
        c.subSpace = runTierLabeled("subspace(late)", graph,
                subSpaceAdmitUpdate(graph, -1));
        // Maintained-resource late-arrival: catches declarations that landed
        // before the publisher's admin grant became valid in this state.
        c.maintainedResource = runTierLabeled("maintained-resource(late)", graph,
                maintainedResourceAdmitUpdate(graph, -1));
        // URL-prefix fallback: re-run after the late-arrival sub-space admit so
        // any newly-validated children get their fallback edges suppressed (for
        // future inserts) and any newly-orphaned children pick up fallback edges.
        c.subSpacePrefix = runTierLabeled("subspace-prefix(late)", graph,
                subSpacePrefixFallbackUpdate(graph));
        // Preset-attachment late-arrival: catches assignments whose preset declaration or
        // admin grant only became valid in this same cycle. Runs before attachment(late)
        // so the non-admin late tiers below see this cycle's fresh preset-derived RAs.
        c.presetAttachment = runTierLabeled("preset-attachment(late)", graph,
                presetAttachmentValidationUpdate(graph, -1));
        // Ref-scoped preset-assignment late stamp: catches assignments whose authorizing
        // admin grant only became valid this cycle (the load filter would skip the older
        // assignment nanopub). Mirrors the preset-attachment late sweep above.
        c.presetAssignmentRef = runTierLabeled("preset-assignment-ref(late)", graph,
                presetAssignmentRefStampUpdate(graph, -1));
        c.attachment = runTierLabeled("attachment(late)", graph,
                attachmentValidationUpdate(graph, -1));
        c.maintainer = runTierLabeled("maintainer(late)", graph,
                nonAdminTierUpdate(graph, -1, GEN.MAINTAINER_ROLE, PUBLISHER_IS_ADMIN));
        c.member = runTierLabeled("member(admin-pub,late)", graph,
                nonAdminTierUpdate(graph, -1, GEN.MEMBER_ROLE, PUBLISHER_IS_ADMIN));
        c.member += runTierLabeled("member(maint-pub,late)", graph,
                nonAdminTierUpdate(graph, -1,
                        GEN.MEMBER_ROLE, publisherIsTieredRole(GEN.MAINTAINER_ROLE)));
        c.observer = runTierLabeled("observer(admin-pub,late)", graph,
                nonAdminTierUpdate(graph, -1, GEN.OBSERVER_ROLE, PUBLISHER_IS_ADMIN));
        c.observer += runTierLabeled("observer(maint-pub,late)", graph,
                nonAdminTierUpdate(graph, -1,
                        GEN.OBSERVER_ROLE, publisherIsTieredRole(GEN.MAINTAINER_ROLE)));
        c.observer += runTierLabeled("observer(member-pub,late)", graph,
                nonAdminTierUpdate(graph, -1,
                        GEN.OBSERVER_ROLE, publisherIsTieredRole(GEN.MEMBER_ROLE)));
        c.observer += runTierLabeled("observer(self,late)", graph,
                nonAdminTierUpdate(graph, -1, GEN.OBSERVER_ROLE, PUBLISHER_IS_SELF));
        return c;
    }

    /**
     * Cheap ASK: did any new {@code npa:RoleDeclaration} extraction land in the
     * load-number delta {@code (lastProcessed, ∞)}? Used by the late-arrival
     * trigger so an RD that arrives in the same cycle as a matching candidate
     * still gets validated.
     */
    boolean newRoleDeclarationsArrived(long lastProcessed) {
        String ask = String.format("""
                PREFIX npa: <%1$s>
                ASK {
                  GRAPH <%2$s> {
                    ?rd a npa:RoleDeclaration ;
                        npa:viaNanopub ?np .
                  }
                  GRAPH <%3$s> {
                    ?np npa:hasLoadNumber ?ln .
                    FILTER (?ln > %4$d)
                  }
                }
                """, NPA.NAMESPACE, SpacesVocab.SPACES_GRAPH, NPA.GRAPH, lastProcessed);
        return runAsk(ask);
    }

    /**
     * Cheap ASK: did any new {@code npa:PresetAssignment} or {@code npa:PresetDeclaration}
     * extraction land in the load-number delta {@code (lastProcessed, ∞)}? Drives the
     * late-arrival re-run so a preset assignment that arrives in the same cycle as its
     * declaration (or admin grant) still materializes, and so an arriving newer assignment
     * triggers the deactivation/latest-wins re-evaluation.
     */
    boolean newPresetAssignmentsArrived(long lastProcessed) {
        String ask = String.format("""
                PREFIX npa: <%1$s>
                ASK {
                  GRAPH <%2$s> {
                    ?x a ?t ;
                       npa:viaNanopub ?np .
                    FILTER (?t = npa:PresetAssignment || ?t = npa:PresetDeclaration)
                  }
                  GRAPH <%3$s> {
                    ?np npa:hasLoadNumber ?ln .
                    FILTER (?ln > %4$d)
                  }
                }
                """, NPA.NAMESPACE, SpacesVocab.SPACES_GRAPH, NPA.GRAPH, lastProcessed);
        return runAsk(ask);
    }

    // ---------------- Tier UPDATE loops ----------------

    /**
     * Per-tier inserted-triple tallies for one build or cycle. Counts the sum
     * of {@code (graphSize_after - graphSize_before)} across all iterations of
     * each tier's fixed-point INSERT loop — i.e. inserted *triples*, not
     * distinct subjects (a single RoleInstantiation insert writes 4–5 triples).
     *
     * <p>Used internally by the {@link #runIncrementalCycle structuralAdds}
     * boolean check (we only care whether any tier inserted at all).
     * Not what the log lines report: see {@link TierSubjectTotals} +
     * {@link #computeTierSubjectTotals} for the distinct-subject totals
     * surfaced to operators.
     */
    static final class TierInsertedTriples {
        int admin;
        int alias;
        int presetAttachment;
        int presetAssignmentRef;
        int attachment;
        int maintainer;
        int member;
        int observer;
        int subSpace;
        int subSpacePrefix;
        int maintainedResource;
    }

    /**
     * Snapshot of distinct-subject totals in a space-state graph at a moment
     * in time. Independent of which tier-loop added each subject.
     */
    record TierSubjectTotals(long adminRIs, long attachmentRAs, long nonAdminRIs) {}

    /**
     * Runs the five tier loops in order: admin → {@code gen:hasRole} attachment
     * validation → maintainer → member → observer. Each loop iterates a SPARQL
     * INSERT to fixed point (no new triples added). Returns per-tier counts.
     *
     * @param graph         target space-state graph
     * @param lastProcessed load-number horizon; use {@code -1} for full build
     */
    TierInsertedTriples runAllTierLoops(IRI graph, long lastProcessed) {
        TierInsertedTriples c = new TierInsertedTriples();
        c.admin = runTierLabeled("admin", graph, adminTierUpdate(graph, lastProcessed));
        // Alias admit runs after the admin closure has settled (both the authority
        // gate and the anti-hijack check read the admin set) and before attachment /
        // role tiers (their alias-aware admin lookups consume the npa:sameAsSpace edge
        // this pass emits). See issue #113.
        c.alias = runTierLabeled("alias", graph, aliasAdmitUpdate(graph, lastProcessed));
        // Sub-space admit runs after admin closure has settled (Mode A + Mode B both
        // need the admin set). Independent of role tiers — order between subspace
        // and attachment / maintainer / member / observer doesn't matter.
        c.subSpace = runTierLabeled("subspace", graph, subSpaceAdmitUpdate(graph, lastProcessed));
        // Maintained-resource admit also depends only on the admin closure. Single
        // Mode A: publisher must be admin of the maintaining space. No co-declaration
        // partner, no URL-prefix fallback.
        c.maintainedResource = runTierLabeled("maintained-resource", graph,
                maintainedResourceAdmitUpdate(graph, lastProcessed));
        // URL-prefix sub-space fallback runs after the explicit-declaration admit
        // pass commits so the per-child suppression check sees this cycle's fresh
        // validations. No load filter — depends on which Spaces exist, not on
        // delta-arrivals; the dedup FILTER NOT EXISTS prevents re-insertion.
        c.subSpacePrefix = runTierLabeled("subspace-prefix", graph,
                subSpacePrefixFallbackUpdate(graph));
        // Preset-attachment runs immediately before the regular attachment tier so the
        // gen:RoleAssignment rows it materializes (from active, admin-authored preset
        // assignments) are picked up by the downstream non-admin tiers in the same pass,
        // exactly like directly-published attachments. See
        // doc/design-preset-role-materialization.md.
        c.presetAttachment = runTierLabeled("preset-attachment", graph,
                presetAttachmentValidationUpdate(graph, lastProcessed));
        // Ref-scoped preset-assignment listing stamp (issue #122). Display-only leaf —
        // independent of the role tiers and of structuralAdds; order doesn't matter.
        c.presetAssignmentRef = runTierLabeled("preset-assignment-ref", graph,
                presetAssignmentRefStampUpdate(graph, lastProcessed));
        c.attachment = runTierLabeled("attachment", graph,
                attachmentValidationUpdate(graph, lastProcessed));
        c.maintainer = runTierLabeled("maintainer", graph, nonAdminTierUpdate(graph, lastProcessed,
                GEN.MAINTAINER_ROLE, PUBLISHER_IS_ADMIN));
        // Member tier: admin OR maintainer publisher — split into two simpler updates
        // so the query planner doesn't struggle with the UNION.
        c.member = runTierLabeled("member(admin-pub)", graph, nonAdminTierUpdate(graph, lastProcessed,
                GEN.MEMBER_ROLE, PUBLISHER_IS_ADMIN));
        c.member += runTierLabeled("member(maint-pub)", graph, nonAdminTierUpdate(graph, lastProcessed,
                GEN.MEMBER_ROLE, publisherIsTieredRole(GEN.MAINTAINER_ROLE)));
        // Observer tier: self-evidence OR a downward grant from any higher tier.
        // ObserverRole is the default tier when a role definition omits an
        // explicit subclass (see "Role types" in design-space-repositories.md), so
        // most "X assigned Y this role" nanopubs land here. Restricting the tier
        // to PUBLISHER_IS_SELF would silently drop those grants. The four
        // sub-loops mirror the trust-state's downward-only chain: admin grants
        // anything; maintainers and members grant observer; everyone may
        // self-attest.
        c.observer = runTierLabeled("observer(admin-pub)", graph, nonAdminTierUpdate(graph, lastProcessed,
                GEN.OBSERVER_ROLE, PUBLISHER_IS_ADMIN));
        c.observer += runTierLabeled("observer(maint-pub)", graph, nonAdminTierUpdate(graph, lastProcessed,
                GEN.OBSERVER_ROLE, publisherIsTieredRole(GEN.MAINTAINER_ROLE)));
        c.observer += runTierLabeled("observer(member-pub)", graph, nonAdminTierUpdate(graph, lastProcessed,
                GEN.OBSERVER_ROLE, publisherIsTieredRole(GEN.MEMBER_ROLE)));
        c.observer += runTierLabeled("observer(self)", graph, nonAdminTierUpdate(graph, lastProcessed,
                GEN.OBSERVER_ROLE, PUBLISHER_IS_SELF));
        return c;
    }

    /**
     * Builds a publisher constraint requiring the publisher to be a validated holder
     * of the given tier's role (maintainer or member) in the target space.
     * Owns its own AccountState resolution so ?publisher is bound through the
     * targeted (pkh → agent) lookup rather than enumerated.
     */
    private static String publisherIsTieredRole(IRI tierClass) {
        // Re-keyed on the assignment's ref (alias → canonical already resolved by the
        // attachment tier). Relies on materialized non-admin RIs carrying their role
        // property (npa:regularProperty / npa:inverseProperty) — supplied by the
        // enrichment in nonAdminTierUpdate; without it this constraint matched nothing.
        return """
                ?acct a npa:AccountState ;
                      npa:pubkey ?pkh ;
                      npa:agent  ?publisher .
                ?tierRI a gen:RoleInstantiation ;
                        npa:forSpaceRef ?spaceRef ;
                        npa:forAgent ?publisher .
                ?rdT a npa:RoleDeclaration ;
                     npa:hasRoleType <%1$s> .
                { ?tierRI npa:regularProperty ?predT . ?rdT gen:hasRegularProperty ?predT . }
                UNION
                { ?tierRI npa:inverseProperty ?predT . ?rdT gen:hasInverseProperty ?predT . }
                """.formatted(tierClass);
    }

    /** Wraps {@link #runTierLoop} with tier-name context for logs/exceptions. */
    private int runTierLabeled(String tier, IRI graph, String sparqlUpdate) {
        try {
            return runTierLoop(graph, sparqlUpdate);
        } catch (RuntimeException ex) {
            logger.error("AuthorityResolver: tier={} failed with SPARQL UPDATE:\n{}\n", tier, sparqlUpdate, ex);
            throw ex;
        }
    }

    /**
     * Runs a single tier's INSERT to fixed point. Counts rows by probing
     * graph size before/after each INSERT; stops when the size doesn't change.
     *
     * @return total number of triples inserted by this tier across all iterations
     */
    int runTierLoop(IRI graph, String sparqlUpdate) {
        int total = 0;
        long before = graphSize(graph);
        while (true) {
            // Note: no explicit transaction wrapping here. In tests we observed that
            // HTTPRepository's RDF4J-transaction protocol silently no-op'd cross-graph
            // SPARQL UPDATEs with UNION sub-patterns inside conn.begin()/commit(),
            // while the same UPDATE POSTed directly to /statements applied correctly.
            // A bare prepareUpdate().execute() takes the direct /statements path and
            // runs the UPDATE atomically per SPARQL 1.1 semantics — which is all we
            // need; there's nothing else to commit atomically alongside the UPDATE.
            try (RepositoryConnection conn = TripleStore.get().getRepoConnection(SPACES_REPO)) {
                conn.prepareUpdate(QueryLanguage.SPARQL, sparqlUpdate).execute();
            }
            long after = graphSize(graph);
            long added = after - before;
            if (added <= 0) break;
            total += added;
            before = after;
        }
        return total;
    }

    private long graphSize(IRI graph) {
        try (RepositoryConnection conn = TripleStore.get().getRepoConnection(SPACES_REPO)) {
            return conn.size(graph);
        }
    }

    /**
     * Distinct-subject totals in the given space-state graph, broken down by
     * RoleInstantiation kind (admin-pinned vs not) and RoleAssignment.
     * Three SELECT-COUNT queries — cheap, called once per build/cycle for
     * the user-facing log line. Returns zeros on failure (logged) so a flaky
     * count read can't wedge the cycle.
     */
    TierSubjectTotals computeTierSubjectTotals(IRI graph) {
        long adminRIs       = countDistinctSubjects(graph, """
                ?ri a gen:RoleInstantiation ; npa:inverseProperty gen:hasAdmin .
                """, "ri");
        long attachmentRAs  = countDistinctSubjects(graph, """
                ?ra a gen:RoleAssignment .
                """, "ra");
        long nonAdminRIs    = countDistinctSubjects(graph, """
                ?ri a gen:RoleInstantiation .
                FILTER NOT EXISTS { ?ri npa:inverseProperty gen:hasAdmin }
                """, "ri");
        return new TierSubjectTotals(adminRIs, attachmentRAs, nonAdminRIs);
    }

    private long countDistinctSubjects(IRI graph, String wherePattern, String varName) {
        String query = String.format("""
                PREFIX npa: <%1$s>
                PREFIX gen: <%2$s>
                SELECT (COUNT(DISTINCT ?%3$s) AS ?n) WHERE {
                  GRAPH <%4$s> {
                    %5$s
                  }
                }
                """, NPA.NAMESPACE, GEN.NAMESPACE, varName, graph, wherePattern);
        try (RepositoryConnection conn = TripleStore.get().getRepoConnection(SPACES_REPO);
             TupleQueryResult r = conn.prepareTupleQuery(QueryLanguage.SPARQL, query).evaluate()) {
            if (!r.hasNext()) return 0;
            return Long.parseLong(r.next().getBinding("n").getValue().stringValue());
        } catch (Exception ex) {
            logger.warn("AuthorityResolver: countDistinctSubjects on {} failed: {}",
                    graph, ex.toString());
            return 0;
        }
    }

    // ---------------- SPARQL templates ----------------

    /**
     * Reusable invalidation filter on a bound nanopub-IRI variable. Pass the bare
     * variable name (no leading {@code ?}); e.g. {@code invalidationFilter("np")}
     * produces an outer-scoped {@code FILTER NOT EXISTS { GRAPH npa:graph
     * { ?_inv_np npx:invalidates ?np . } }}.
     *
     * <p>Joins on the raw {@code npx:invalidates} triple in {@code npa:graph},
     * which {@link com.knowledgepixels.query.NanopubLoader} writes into the
     * spaces repo from two complementary directions, making the filter symmetric
     * in load order:
     * <ul>
     *   <li>At the invalidator's own load: the loader's space-repo trigger fires
     *       whenever the nanopub has either its own space-relevant extractions
     *       OR an {@code npx:invalidates}/{@code npx:retracts}/{@code npx:supersedes}
     *       triple, so a pure-retraction nanopub still lands its raw triple plus
     *       {@code npa:hasLoadNumber} stamp in {@code npa:graph}.</li>
     *   <li>At the invalidated target's load (when the invalidator landed
     *       earlier): {@code NanopubLoader.getInvalidatingStatements} reads the
     *       triple back from the meta repo and mirrors it into the target's own
     *       write to the spaces repo.</li>
     * </ul>
     *
     * <p>The earlier shape joined on a structured {@code npa:Invalidation} entry
     * in {@code npa:spacesGraph} that was only emitted on the invalidator's side
     * AND only when the invalidated target's meta had already loaded, leaving a
     * window where a superseding nanopub loaded before its target produced no
     * entry and the stale row was never filtered out (see also the matching
     * change in the tier-specific {@code *InvalidationCheckWhere}/{@code
     * *InvalidationDelete} templates below).
     *
     * <p>Important: this filter must be placed OUTSIDE the surrounding
     * {@code GRAPH npa:spacesGraph { ... }} block, not nested inside it. When
     * nested, RDF4J's planner couples the FILTER NOT EXISTS evaluation into the
     * join order (per-row scan multiplied by the candidate set), which we
     * measured turning a 39ms query into a 60s+ timeout on the live observer-tier
     * data. Outside the GRAPH block, the planner defers the filter until
     * {@code ?np}/{@code ?rdNp} are bound and does a targeted index lookup.
     *
     * <p>Variable names must match {@code [A-Za-z0-9_]+} per SPARQL grammar —
     * embedding a {@code ?} inside {@code ?_inv_?np} would yield a parse error.
     */
    private static String invalidationFilter(String bareVarName) {
        return "FILTER NOT EXISTS { GRAPH <" + NPA.GRAPH + "> {"
                + " ?_inv_" + bareVarName
                + " <" + NPX.INVALIDATES + "> ?" + bareVarName + " . } }";
    }

    /**
     * Admin tier: seed from {@code npadef:...hasRootAdmin} (trusted by construction)
     * plus closed-over admin grants; insert any {@code gen:RoleInstantiation} with
     * {@code npa:inverseProperty gen:hasAdmin} whose publisher (resolved via mirrored
     * trust-approved AccountState) is already in the admin set.
     *
     * <p>The seed is gated by {@link #spaceRefAliveFilter} (not the per-nanopub
     * {@code invalidationFilter("defNp")}): the {@code hasRootAdmin} seed is anchored
     * to the root NPID, which is the immutable space-ref identity, so superseding the
     * root <em>nanopub</em> with a continuation revision must not strip the seed —
     * only retracting every definition of the ref removes it. See issue #110.
     */
    static String adminTierUpdate(IRI graph, long lastProcessed) {
        // Order tuned for RDF4J's evaluator:
        //   1. Anchor on the small (seed UNION closed-over) set to bind ?publisher
        //      and ?space cheaply.
        //   2. Resolve ?pkh from the mirrored AccountState row (?publisher bound).
        //   3. Probe instantiations using the now-bound (?space, ?pkh) — targeted
        //      lookup, not a full RoleInstantiation scan.
        //   4. Load-number filter on bound ?np.
        //   5. Dedup at the end.
        // Authority is keyed on the space *ref* (npa:forSpaceRef), not the bare Space
        // IRI: two refs that share an IRI but have different roots are independent
        // domains (see doc/design-spaceref-isolation.md). The instantiation evidence in
        // the extraction graph is IRI-keyed (a gen:hasAdmin nanopub names the bare IRI),
        // so we project it per-ref by joining each instantiation naming ?space to the
        // admin rows of every ref of ?space whose admin set contains the publisher. The
        // inserted subject is minted per (?ri, ?spaceRef) so one instantiation validating
        // into N refs yields N distinct rows. TRANSITIONAL-DUAL-EMIT (Phase 4: remove):
        // forSpace is still emitted alongside forSpaceRef so the not-yet-migrated
        // downstream tiers / pre-ref read queries keep functioning on a mixed-version
        // fleet; it is dropped once everything keys on forSpaceRef.
        return """
                PREFIX npa:  <%1$s>
                PREFIX gen:  <%2$s>
                INSERT { GRAPH <%3$s> {
                  ?sri a gen:RoleInstantiation ;
                       npa:forSpaceRef ?spaceRef ;
                       npa:forSpace ?space ;
                       npa:inverseProperty gen:hasAdmin ;
                       npa:forAgent ?agent ;
                       npa:viaNanopub ?np .
                } }
                WHERE {
                  # 1. Anchor: who is already an admin of which space ref?
                  {
                    # Seed branch: root-admin of a space ref that is still alive
                    # (has at least one non-invalidated definition). NOT filtered on
                    # ?def's own invalidation — superseding the root nanopub with a
                    # continuation revision must keep the seed; only a fully-retracted
                    # ref drops it (issue #110).
                    GRAPH <%4$s> {
                      ?def a npa:SpaceDefinition ;
                           npa:forSpaceRef  ?spaceRef ;
                           npa:hasRootAdmin ?publisher .
                      ?spaceRef npa:spaceIri ?space .
                    }
                    %7$s
                  }
                  UNION
                  {
                    # Closed-over branch: an existing admin of this ref. Recurse on the
                    # ref, then resolve its bare IRI to probe the IRI-keyed instantiation.
                    GRAPH <%3$s> {
                      ?prev a gen:RoleInstantiation ;
                            npa:forSpaceRef     ?spaceRef ;
                            npa:inverseProperty gen:hasAdmin ;
                            npa:forAgent        ?publisher .
                    }
                    GRAPH <%4$s> {
                      ?spaceRef npa:spaceIri ?space .
                    }
                  }
                  # 2. Mirror: resolve ?publisher → ?pkh via the trust-approved row.
                  GRAPH <%3$s> {
                    ?acct a npa:AccountState ;
                          npa:agent  ?publisher ;
                          npa:pubkey ?pkh .
                  }
                  # 3. Targeted instantiation lookup by space + pubkey (IRI-keyed).
                  GRAPH <%4$s> {
                    ?ri a gen:RoleInstantiation ;
                        npa:forSpace        ?space ;
                        npa:inverseProperty gen:hasAdmin ;
                        npa:forAgent        ?agent ;
                        npa:pubkeyHash      ?pkh ;
                        npa:viaNanopub      ?np .
                  }
                  # 3a. Mint the per-ref state subject: (?ri, ?spaceRef) → ?sri.
                  BIND(IRI(CONCAT(STR(?ri), "__", ENCODE_FOR_URI(STR(?spaceRef)))) AS ?sri)
                  %6$s
                  # 4. Load-number filter on bound ?np.
                  GRAPH <%8$s> {
                    ?np npa:hasLoadNumber ?ln .
                    FILTER (?ln > %5$d)
                  }
                  # 5. Dedup last — keyed on (ref, agent).
                  FILTER NOT EXISTS { GRAPH <%3$s> {
                    ?existing a gen:RoleInstantiation ;
                              npa:forSpaceRef ?spaceRef ;
                              npa:forAgent ?agent ;
                              npa:inverseProperty gen:hasAdmin .
                  } }
                }
                """.formatted(
                NPA.NAMESPACE,
                GEN.NAMESPACE,
                graph,
                SpacesVocab.SPACES_GRAPH,
                lastProcessed,
                invalidationFilter("np"),
                spaceRefAliveFilter(),
                NPA.GRAPH);
    }

    /**
     * Seed-survival filter for the admin tier (issue #110). The {@code hasRootAdmin}
     * seed is anchored to the root NPID, which is the immutable space-ref identity, so
     * it must survive supersession of the root <em>nanopub</em> by a continuation
     * revision (a later definition re-roots to the same ref via
     * {@code gen:hasRootDefinition} and so carries no {@code hasRootAdmin} of its own).
     * The previous {@code invalidationFilter("defNp")} dropped the seed the moment the
     * root revision was superseded, leaving the whole admin closure — and everything
     * cascading from it — unmaterialized for any space whose definition had ever been
     * updated.
     *
     * <p>Expressed positively: the seed survives iff the space ref still has at least
     * one non-invalidated {@link SpacesVocab#SPACE_DEFINITION}. A fully-retracted ref
     * (every definition invalidated) has no live definition, so the {@code FILTER
     * EXISTS} fails and the seed correctly disappears. Anchored on the already-bound
     * {@code ?spaceRef}, so it's a targeted lookup over that ref's (few) definitions.
     */
    private static String spaceRefAliveFilter() {
        return """
                FILTER EXISTS {
                  GRAPH <%1$s> {
                    ?liveDef a npa:SpaceDefinition ;
                             npa:forSpaceRef ?spaceRef ;
                             npa:viaNanopub  ?liveNp .
                  }
                  %2$s
                }
                """.formatted(SpacesVocab.SPACES_GRAPH, invalidationFilter("liveNp"));
    }

    /**
     * {@code gen:hasRole} attachment validation: an attachment is validated iff its
     * publisher is already a validated admin of the target space. Adds
     * {@code gen:RoleAssignment} rows to the space-state graph.
     */
    static String attachmentValidationUpdate(IRI graph, long lastProcessed) {
        // Ref-keyed (see doc/design-spaceref-isolation.md). The attachment names a bare
        // Space IRI; it is validated per-ref for every ref of that IRI whose admin set
        // contains the publisher (direct), or — when the named IRI is an owl:sameAs alias
        // — for the canonical ref it maps to (issue #113). ?targetRef is the ref the
        // RoleAssignment attaches to; the inserted subject is minted per (?ra, ?targetRef)
        // so one attachment validating into N refs yields N distinct rows.
        // TRANSITIONAL-DUAL-EMIT (Phase 4: remove): forSpace (the attached IRI, possibly an
        // alias) is kept so the non-admin tier can probe the IRI-keyed instantiations
        // naming it, and so pre-ref read queries keep functioning on a mixed-version fleet.
        return """
                PREFIX npa:  <%1$s>
                PREFIX gen:  <%2$s>
                INSERT { GRAPH <%3$s> {
                  ?ra2 a gen:RoleAssignment ;
                       npa:forSpaceRef ?targetRef ;
                       npa:forSpace ?space ;
                       gen:hasRole  ?role ;
                       npa:viaNanopub ?np .
                } }
                WHERE {
                  GRAPH <%4$s> {
                    ?ra a gen:RoleAssignment ;
                        npa:forSpace ?space ;
                        gen:hasRole  ?role ;
                        npa:pubkeyHash ?pkh ;
                        npa:viaNanopub ?np .
                  }
                  GRAPH <%7$s> {
                    ?np npa:hasLoadNumber ?ln .
                    FILTER (?ln > %5$d)
                  }
                  GRAPH <%3$s> {
                    ?acct a npa:AccountState ;
                          npa:agent  ?publisher ;
                          npa:pubkey ?pkh .
                  }
                  # Per-ref admin gate. ?targetRef = a ref of ?space the publisher admins
                  # (direct), or the canonical ref ?space is an owl:sameAs alias of.
                  {
                    GRAPH <%4$s> { ?targetRef npa:spaceIri ?space . }
                    GRAPH <%3$s> {
                      ?adminRI a gen:RoleInstantiation ;
                               npa:forSpaceRef ?targetRef ;
                               npa:inverseProperty gen:hasAdmin ;
                               npa:forAgent ?publisher .
                    }
                  }
                  UNION
                  {
                    GRAPH <%3$s> {
                      ?space npa:sameAsSpace ?targetRef .
                      ?adminRI a gen:RoleInstantiation ;
                               npa:forSpaceRef ?targetRef ;
                               npa:inverseProperty gen:hasAdmin ;
                               npa:forAgent ?publisher .
                    }
                  }
                  BIND(IRI(CONCAT(STR(?ra), "__", ENCODE_FOR_URI(STR(?targetRef)))) AS ?ra2)
                  %6$s
                  FILTER NOT EXISTS { GRAPH <%3$s> {
                    ?existing a gen:RoleAssignment ;
                              npa:forSpaceRef ?targetRef ;
                              gen:hasRole  ?role .
                  } }
                }
                """.formatted(
                NPA.NAMESPACE,
                GEN.NAMESPACE,
                graph,
                SpacesVocab.SPACES_GRAPH,
                lastProcessed,
                invalidationFilter("np"),
                NPA.GRAPH);
    }

    /**
     * Preset-bundled role materialization (Nanodash issue #302). For each active,
     * admin-authored {@code gen:PresetAssignment} targeting a {@code gen:Space}, inserts
     * one {@code gen:RoleAssignment} per role the preset bundles — exactly as if
     * {@code <space> gen:hasRole <role>} had been published by the assignment's publisher.
     * The materialized rows carry {@code npa:derivedFromPreset} (the assignment nanopub)
     * so the deactivation delete and read-side marking can scope to them without touching
     * directly-published attachments. See {@code doc/design-preset-role-materialization.md}.
     *
     * <p>Activation is resolved by an <b>authorization-scoped latest-wins</b> over the
     * {@code (preset, resource)} pair, NOT {@code npx:invalidates} (§3): the candidate set
     * for the {@code MAX(dct:created)} comparison is restricted to assignments whose
     * publisher is also a validated admin of the target ref, so an unauthorized key's newer
     * assignment cannot shadow an admin's activation (the #113-class anti-hijack rule).
     */
    static String presetAttachmentValidationUpdate(IRI graph, long lastProcessed) {
        // Ref-keyed like attachmentValidationUpdate: the assignment names a bare resource
        // IRI; it is validated per-ref for every Space ref of that IRI whose admin set
        // contains the publisher. The inserted subject is minted per (assignment, ref, role)
        // — one assignment fans out to N roles and N refs. Non-Space targets resolve no
        // ?targetRef and so insert nothing (correct no-op; maintained-resource / individual
        // targets are future work, see design doc §2). TRANSITIONAL-DUAL-EMIT (Phase 4:
        // remove): forSpace kept alongside forSpaceRef so the non-admin tiers can probe the
        // IRI-keyed instantiations and pre-ref read queries keep functioning.
        return """
                PREFIX npa:  <%1$s>
                PREFIX gen:  <%2$s>
                INSERT { GRAPH <%3$s> {
                  ?ra2 a gen:RoleAssignment ;
                       npa:forSpaceRef ?targetRef ;
                       npa:forSpace    ?resource ;
                       gen:hasRole     ?role ;
                       npa:viaNanopub  ?assignNp ;
                       npa:derivedFromPreset ?assignNp .
                } }
                WHERE {
                  # 1. Anchor: active preset assignments in the extraction graph.
                  GRAPH <%4$s> {
                    ?pa a npa:PresetAssignment ;
                        npa:ofPreset    ?preset ;
                        npa:forResource ?resource ;
                        npa:isActivated true ;
                        npa:pubkeyHash  ?pkh ;
                        npa:viaNanopub  ?assignNp ;
                        <http://purl.org/dc/terms/created> ?created .
                  }
                  # 2. Load-number filter on the assignment nanopub.
                  GRAPH <%7$s> {
                    ?assignNp npa:hasLoadNumber ?ln .
                    FILTER (?ln > %5$d)
                  }
                  # 3. Resolve publisher pkh -> agent via the mirrored trust-approved row.
                  GRAPH <%3$s> {
                    ?acct a npa:AccountState ;
                          npa:agent  ?publisher ;
                          npa:pubkey ?pkh .
                  }
                  # 4. Target must be a Space ref the publisher admins — direct, or the
                  #    canonical ref ?resource is an owl:sameAs alias of (issue #113 parity
                  #    with attachmentValidationUpdate, so a preset assigned against an alias
                  #    IRI still materializes against the canonical ref).
                  {
                    GRAPH <%4$s> { ?targetRef npa:spaceIri ?resource . }
                    GRAPH <%3$s> {
                      ?adminRI a gen:RoleInstantiation ;
                               npa:forSpaceRef ?targetRef ;
                               npa:inverseProperty gen:hasAdmin ;
                               npa:forAgent ?publisher .
                    }
                  }
                  UNION
                  {
                    GRAPH <%3$s> {
                      ?resource npa:sameAsSpace ?targetRef .
                      ?adminRI a gen:RoleInstantiation ;
                               npa:forSpaceRef ?targetRef ;
                               npa:inverseProperty gen:hasAdmin ;
                               npa:forAgent ?publisher .
                    }
                  }
                  # 5. Resolve the assignment's referenced preset IRI (node or kind) to its
                  #    canonical kind, mirroring how Nanodash views key on dct:isVersionOf
                  #    (ViewDisplay.getViewKindIri). Every declaration carries npa:ofPreset for
                  #    both its node IRI and kind, so either reference maps to the same ?kind.
                  GRAPH <%4$s> {
                    ?pdMap a npa:PresetDeclaration ;
                           npa:ofPreset   ?preset ;
                           npa:presetKind ?kind .
                  }
                  # 5a. Roles come from the LATEST live declaration of that kind, restricted to
                  #     Space-targeted presets — so a superseded preset version's roles never leak
                  #     (the per-view-kind latest-wins, ported to materialization).
                  GRAPH <%4$s> {
                    ?pd a npa:PresetDeclaration ;
                        npa:presetKind           ?kind ;
                        npa:presetRole           ?role ;
                        npa:appliesToInstancesOf gen:Space ;
                        npa:viaNanopub           ?pdNp ;
                        <http://purl.org/dc/terms/created> ?pdCreated .
                  }
                  # 5b. Latest-declaration-per-kind: reject if a newer LIVE declaration of the
                  #     same kind exists (tiebreak on subject IRI for equal timestamps).
                  FILTER NOT EXISTS {
                    GRAPH <%4$s> {
                      ?pdNewer a npa:PresetDeclaration ;
                               npa:presetKind ?kind ;
                               npa:viaNanopub ?pdNpNewer ;
                               <http://purl.org/dc/terms/created> ?pdCreatedNewer .
                      FILTER (?pdCreatedNewer > ?pdCreated
                              || (?pdCreatedNewer = ?pdCreated && STR(?pdNewer) > STR(?pd)))
                    }
                    %8$s
                  }
                  # 5c. The chosen declaration must itself be live (not superseded/retracted).
                  %9$s
                  # 6. Mint the per (assignment, ref, role) subject.
                  BIND(IRI(CONCAT(STR(?pa), "__", ENCODE_FOR_URI(STR(?targetRef)),
                                  "__", ENCODE_FOR_URI(STR(?role)))) AS ?ra2)
                  # 7. Authorization-scoped latest-wins (anti-hijack, design doc §3): reject
                  #    if a newer same-(preset,resource) assignment exists whose publisher is
                  #    ALSO a validated admin of ?targetRef. Filtering the shadowing candidate
                  #    to admin-authored rows BEFORE taking the latest is what stops an
                  #    unauthorized key from suppressing an admin's activation. Placed after
                  #    the main vars are bound so the planner defers it (RDF4J quirk).
                  FILTER NOT EXISTS {
                    GRAPH <%4$s> {
                      ?paNewer a npa:PresetAssignment ;
                               npa:ofPreset    ?preset ;
                               npa:forResource ?resource ;
                               npa:pubkeyHash  ?pkhNewer ;
                               <http://purl.org/dc/terms/created> ?createdNewer .
                      FILTER (?createdNewer > ?created
                              || (?createdNewer = ?created && STR(?paNewer) > STR(?pa)))
                    }
                    GRAPH <%3$s> {
                      ?acctNewer a npa:AccountState ;
                                 npa:agent  ?publisherNewer ;
                                 npa:pubkey ?pkhNewer .
                      ?adminRINewer a gen:RoleInstantiation ;
                                    npa:forSpaceRef ?targetRef ;
                                    npa:inverseProperty gen:hasAdmin ;
                                    npa:forAgent ?publisherNewer .
                    }
                  }
                  # 8. Defensive: drop if the assignment nanopub itself was hard-retracted.
                  %6$s
                  # 9. Dedup last — keyed on (ref, role).
                  FILTER NOT EXISTS { GRAPH <%3$s> {
                    ?existing a gen:RoleAssignment ;
                              npa:forSpaceRef ?targetRef ;
                              gen:hasRole ?role .
                  } }
                }
                """.formatted(
                NPA.NAMESPACE,
                GEN.NAMESPACE,
                graph,
                SpacesVocab.SPACES_GRAPH,
                lastProcessed,
                invalidationFilter("assignNp"),
                NPA.GRAPH,
                invalidationFilter("pdNpNewer"),
                invalidationFilter("pdNp"));
    }

    /**
     * Stamps a ref-scoped, admin-validated mirror of each {@code npa:PresetAssignment}
     * into the state graph (issue #122). The publisher-agnostic extraction row
     * ({@link SpacesExtractor#extractPresetAssignment}) is keyed only by
     * {@code npa:forResource}, so a consumer listing a space's preset assignments by IRI
     * sees the union across <em>all</em> refs claiming that IRI. This stamp adds
     * {@code npa:forSpaceRef ?targetRef} so the "Assigned presets" listing is no longer
     * merged across refs of the same IRI — the one remaining About-tab listing that still
     * merged across refs (every other ref-scoped listing already has a {@code forSpaceRef}
     * companion).
     *
     * <p>Faithful per-assignment mirror — deliberately <em>not</em> role-gated and
     * <em>not</em> latest-wins-resolved, unlike {@link #presetAttachmentValidationUpdate}:
     * <ul>
     *   <li>No {@code npa:PresetDeclaration}/role join, so a preset that bundles only
     *       <em>views</em> (no roles) is still listed.</li>
     *   <li>Emits active <em>and</em> deactivated rows (carries {@code npa:isActivated})
     *       so the listing can show state; a deactivation is just a newer admin-authored
     *       row, so no {@code dct:created}-driven removal is needed here (contrast §4.4).</li>
     *   <li>Latest-wins is deferred to the consumer query, which ranges only over these
     *       admin-authored rows — so it is authorization-scoped for free (design §3): a
     *       non-admin of the ref can never get a row stamped, so it cannot enter the
     *       latest-wins race.</li>
     * </ul>
     *
     * <p>Display-only leaf: nothing downstream derives from these rows (contrast the
     * preset-derived {@code gen:RoleAssignment}), so the caller must <em>not</em> feed this
     * tier's count into {@code structuralAdds}. The {@code npa:forSpaceRef} predicate also
     * distinguishes a stamped row from the IRI-keyed extraction row (which never carries it),
     * so {@link #presetAssignmentRefInvalidationDelete} can target exactly these rows.
     * Reuses steps 1–4 of {@link #presetAttachmentValidationUpdate}; see
     * doc/design-preset-role-materialization.md §3 and issue #122.
     */
    static String presetAssignmentRefStampUpdate(IRI graph, long lastProcessed) {
        return """
                PREFIX npa:  <%1$s>
                PREFIX gen:  <%2$s>
                INSERT { GRAPH <%3$s> {
                  ?paRef a npa:PresetAssignment ;
                         npa:ofPreset    ?preset ;
                         npa:forResource ?resource ;
                         npa:forSpaceRef ?targetRef ;
                         npa:isActivated ?activated ;
                         npa:viaNanopub  ?assignNp ;
                         <http://purl.org/dc/terms/created> ?created .
                } }
                WHERE {
                  # 1. Anchor: every assignment row (active or not) in the extraction graph.
                  GRAPH <%4$s> {
                    ?pa a npa:PresetAssignment ;
                        npa:ofPreset    ?preset ;
                        npa:forResource ?resource ;
                        npa:isActivated ?activated ;
                        npa:pubkeyHash  ?pkh ;
                        npa:viaNanopub  ?assignNp ;
                        <http://purl.org/dc/terms/created> ?created .
                  }
                  # 2. Load-number filter on the assignment nanopub (delta window).
                  GRAPH <%6$s> {
                    ?assignNp npa:hasLoadNumber ?ln .
                    FILTER (?ln > %5$d)
                  }
                  # 3. Resolve publisher pkh -> agent via the mirrored trust-approved row.
                  GRAPH <%3$s> {
                    ?acct a npa:AccountState ;
                          npa:agent  ?publisher ;
                          npa:pubkey ?pkh .
                  }
                  # 4. Target must be a Space ref the publisher admins. ?targetRef = that ref;
                  #    fan-out to N refs the publisher admins (per-ref isolation, consistent
                  #    with the role materializer and design-spaceref-isolation.md). Direct,
                  #    or the canonical ref ?resource is an owl:sameAs alias of (issue #113),
                  #    so an assignment naming an alias is still listed under the canonical ref.
                  {
                    GRAPH <%4$s> { ?targetRef npa:spaceIri ?resource . }
                    GRAPH <%3$s> {
                      ?adminRI a gen:RoleInstantiation ;
                               npa:forSpaceRef ?targetRef ;
                               npa:inverseProperty gen:hasAdmin ;
                               npa:forAgent ?publisher .
                    }
                  }
                  UNION
                  {
                    GRAPH <%3$s> {
                      ?resource npa:sameAsSpace ?targetRef .
                      ?adminRI a gen:RoleInstantiation ;
                               npa:forSpaceRef ?targetRef ;
                               npa:inverseProperty gen:hasAdmin ;
                               npa:forAgent ?publisher .
                    }
                  }
                  # 5. Defensive: drop if the assignment nanopub itself was hard-retracted.
                  %7$s
                  # 6. Mint per (assignment, ref); dedup on the bound subject. No latest-wins
                  #    here — a deactivation is just a newer admin-authored row, and the
                  #    consumer resolves latest dct:created per (preset,resource) over these
                  #    admin-authored rows (so the resolution is authorization-scoped).
                  BIND(IRI(CONCAT(STR(?pa), "__", ENCODE_FOR_URI(STR(?targetRef)))) AS ?paRef)
                  FILTER NOT EXISTS { GRAPH <%3$s> { ?paRef a npa:PresetAssignment . } }
                }
                """.formatted(
                NPA.NAMESPACE,
                GEN.NAMESPACE,
                graph,
                SpacesVocab.SPACES_GRAPH,
                lastProcessed,
                NPA.GRAPH,
                invalidationFilter("assignNp"));
    }

    /**
     * Non-admin tier publisher constraints (inserted as a SPARQL sub-pattern).
     * Each constraint owns the AccountState (pkh → agent) lookup so the join
     * variable is bound through a targeted pattern. The observer-self variant
     * binds {@code npa:agent ?agent} directly — no separate {@code ?publisher}
     * variable, no post-join equality filter — which lets the planner anchor
     * the AccountState lookup on the already-bound {@code ?agent} instead of
     * enumerating all approved publishers and filtering at the end.
     */
    static final String PUBLISHER_IS_ADMIN = """
            ?acct a npa:AccountState ;
                  npa:pubkey ?pkh ;
                  npa:agent  ?publisher .
            # Admin of the assignment's ref. The ref already resolves alias →
            # canonical (the attachment tier bound ?spaceRef through the owl:sameAs
            # alias edge for aliased IRIs, issue #113), so no alias arm is needed here.
            ?adminRI a gen:RoleInstantiation ;
                     npa:forSpaceRef ?spaceRef ;
                     npa:inverseProperty gen:hasAdmin ;
                     npa:forAgent ?publisher .
            """;

    /** Observer self-evidence: the assignee's own pubkey signed the instantiation. */
    static final String PUBLISHER_IS_SELF = """
            ?acct a npa:AccountState ;
                  npa:pubkey ?pkh ;
                  npa:agent  ?agent .
            """;

    /**
     * Maintainer / Member / Observer tier INSERT. Same shape: find an instantiation
     * whose predicate matches a RoleDeclaration of the given tier attached to the
     * target space, and whose publisher passes the tier-specific constraint.
     */
    static String nonAdminTierUpdate(IRI graph, long lastProcessed,
                                     IRI tierClass, String publisherConstraint) {
        // Order tuned for RDF4J's evaluator (which executes BGPs roughly in order).
        // The crucial choice is the *anchor*: instantiation-first plans send the
        // planner exploring the full ~thousands of candidate RIs and only filter
        // by tier at the very end. Attachment-first anchors on the small set of
        // gen:RoleAssignment rows already validated in this space-state graph
        // (~hundreds, often zero) and walks outward by bound (?role, ?space).
        //
        //   1. Anchor on RoleAssignments in this space-state graph (small).
        //   1a. Resolve the IRIs that denote the assignment's ref — its canonical
        //      IRI plus any validated owl:sameAs aliases — so an instantiation that
        //      names an alias of the space still matches (issue #113). Bound here so
        //      the instantiation lookup below stays anchored by ?instSpace.
        //   2. Match the tier-pinned RoleDeclaration by ?role.
        //   3. Pair role-decl direction to instantiation direction in one UNION
        //      so only (reg, reg)/(inv, inv) combos are explored.
        //   4. Targeted instantiation lookup — (?instSpace, ?pred) are bound.
        //   5. Publisher constraint (incl. AccountState resolution).
        //   6. Load-number filter on bound ?np.
        //   7. Dedup at the end.
        return """
                PREFIX npa:  <%1$s>
                PREFIX gen:  <%2$s>
                INSERT { GRAPH <%3$s> {
                  ?ri2 a gen:RoleInstantiation ;
                       npa:forSpaceRef ?spaceRef ;
                       # TRANSITIONAL-DUAL-EMIT (Phase 4: remove): forSpace alongside
                       # forSpaceRef so pre-ref read queries (e.g. get-space-members) keep
                       # functioning on a mixed-version fleet; downstream tiers key on the ref.
                       npa:forSpace ?space ;
                       npa:forAgent ?agent ;
                       ?dirPred ?pred ;
                       npa:viaNanopub ?np .
                } }
                WHERE {
                  # 1. Anchor: validated attachments in this space-state graph (ref-keyed).
                  GRAPH <%3$s> {
                    ?ra a gen:RoleAssignment ;
                        gen:hasRole     ?role ;
                        npa:forSpaceRef ?spaceRef ;
                        npa:forSpace    ?space .
                  }
                  # 1a. The IRIs that denote this ref: its canonical IRI, plus any validated
                  #     owl:sameAs aliases of it (issue #113) — so an instantiation naming an
                  #     alias of the space still materializes here. Bound BEFORE the
                  #     instantiation BGP so that lookup stays anchored by ?instSpace (planner
                  #     note above); ?spaceRef is already bound, so each arm is a targeted
                  #     lookup yielding a tiny IRI set. The alias arm only follows admin-
                  #     validated npa:sameAsSpace edges, so it grants no authority the admin
                  #     tier would not (anti-hijack is enforced upstream, not relaxed here).
                  {
                    GRAPH <%4$s> { ?spaceRef npa:spaceIri ?instSpace . }
                  }
                  UNION
                  {
                    GRAPH <%3$s> { ?instSpace npa:sameAsSpace ?spaceRef . }
                  }
                  # 2. Tier-pinned RoleDeclaration (?role bound from the attachment).
                  GRAPH <%4$s> {
                    ?rd a npa:RoleDeclaration ;
                        npa:hasRoleType <%7$s> ;
                        npa:role        ?role ;
                        npa:viaNanopub  ?rdNp .
                    # 3. Pair direction so only matching combos are explored. ?dirPred
                    #    carries the matched direction so the materialized row records the
                    #    role property (read by get-space-members and publisherIsTieredRole).
                    {
                      ?rd gen:hasRegularProperty ?pred .
                      ?ri npa:regularProperty    ?pred .
                      BIND(npa:regularProperty AS ?dirPred)
                    }
                    UNION
                    {
                      ?rd gen:hasInverseProperty ?pred .
                      ?ri npa:inverseProperty    ?pred .
                      BIND(npa:inverseProperty AS ?dirPred)
                    }
                    # 4. Targeted instantiation lookup — (?instSpace, ?pred) bound. The
                    #    instantiation names its space by IRI; ?instSpace was resolved to this
                    #    ref above (canonical or owl:sameAs alias), so an alias-named
                    #    instantiation joins the same ?spaceRef as a canonical one. The
                    #    materialized row still carries npa:forSpace ?space (the attachment's
                    #    IRI) for the transitional dual-emit, so pre-ref reads see the member
                    #    under the space's primary IRI.
                    ?ri a gen:RoleInstantiation ;
                        npa:forSpace   ?instSpace ;
                        npa:forAgent   ?agent ;
                        npa:pubkeyHash ?pkh ;
                        npa:viaNanopub ?np .
                  }
                  # 5. Publisher constraint (incl. AccountState resolution).
                  GRAPH <%3$s> {
                    %9$s
                  }
                  # 5a. Mint the per-ref state subject: (?ri, ?spaceRef) → ?ri2.
                  BIND(IRI(CONCAT(STR(?ri), "__", ENCODE_FOR_URI(STR(?spaceRef)))) AS ?ri2)
                  # 6. Load-number filter on bound ?np.
                  GRAPH <%10$s> {
                    ?np npa:hasLoadNumber ?ln .
                    FILTER (?ln > %5$d)
                  }
                  # 7. Invalidation filters — outside the GRAPH block so the
                  #    planner defers them until ?rdNp/?np are bound.
                  %8$s
                  %6$s
                  # 8. Dedup last — keyed on (ref, agent, nanopub).
                  FILTER NOT EXISTS { GRAPH <%3$s> {
                    ?existing a gen:RoleInstantiation ;
                              npa:forSpaceRef ?spaceRef ;
                              npa:forAgent ?agent ;
                              npa:viaNanopub ?np .
                  } }
                }
                """.formatted(
                NPA.NAMESPACE,
                GEN.NAMESPACE,
                graph,
                SpacesVocab.SPACES_GRAPH,
                lastProcessed,
                invalidationFilter("np"),
                tierClass,
                invalidationFilter("rdNp"),
                publisherConstraint,
                NPA.GRAPH);
    }

    /**
     * Sub-space admit pass. Copies validated {@code npa:SubSpaceDeclaration}
     * extraction rows into the space-state graph (preserving the {@code npasub:}
     * subject) and emits convenience {@code <child> npa:isSubSpaceOf <parent>} and
     * {@code <parent> npa:hasSubSpace <child>} direct triples. Two satisfaction
     * modes joined by UNION:
     * <ul>
     *   <li>Mode A — the declaration's publisher is a validated admin of both the
     *       child and the parent space.</li>
     *   <li>Mode B — a different non-invalidated declaration for the same
     *       {@code (child, parent)} pair exists, and the two publishers between
     *       them cover both admin sides (i.e. one of them is admin of the child,
     *       one of them is admin of the parent — possibly the same one twice if
     *       both happen to be admin of both).</li>
     * </ul>
     *
     * <p>Mode-B late-arrival: when only the partner declaration is new in this
     * cycle (the primary is older than {@code lastProcessed}), the load-number
     * filter on {@code ?np} excludes the candidate. The late-arrival sweep
     * ({@link #runDownstreamWithoutLoadFilter}) re-runs this pass without the
     * load filter and catches it.
     */
    static String subSpaceAdmitUpdate(IRI graph, long lastProcessed) {
        return """
                PREFIX npa: <%1$s>
                PREFIX gen: <%2$s>
                INSERT { GRAPH <%3$s> {
                  ?d a npa:SubSpaceDeclaration ;
                     npa:childSpace  ?child ;
                     npa:parentSpace ?parent ;
                     npa:viaNanopub  ?np .
                  ?childRef  npa:isSubSpaceOf ?parentRef .
                  ?parentRef npa:hasSubSpace  ?childRef  .
                  # TRANSITIONAL-DUAL-EMIT (Phase 1.5; remove in Phase 4): IRI-valued
                  # sub-space edge alongside the ref-to-ref one, so pre-ref published
                  # queries that key on the bare Space IRI keep binding on a mixed-version
                  # fleet. See doc/report-2026-06-12-mixed-fleet-spaceref-breakage.md.
                  ?child  npa:isSubSpaceOf ?parent .
                  ?parent npa:hasSubSpace  ?child  .
                } }
                WHERE {
                  # 1. Anchor: candidate declarations from the extraction graph.
                  GRAPH <%4$s> {
                    ?d a npa:SubSpaceDeclaration ;
                       npa:childSpace  ?child ;
                       npa:parentSpace ?parent ;
                       npa:pubkeyHash  ?pkh ;
                       npa:viaNanopub  ?np .
                  }
                  # 2. Mirror: resolve ?pkh → ?publisher via the trust-approved row.
                  GRAPH <%3$s> {
                    ?acct a npa:AccountState ;
                          npa:pubkey ?pkh ;
                          npa:agent  ?publisher .
                  }
                  # 3. Authority gate, ref-keyed. The edge is emitted ref-to-ref between
                  #    the child ref and parent ref the authorizing admin governs; the
                  #    admin rows' dual-emitted npa:forSpace binds the refs to the child /
                  #    parent IRIs (cross-product when an IRI has several governed refs).
                  {
                    # Mode A — publisher is admin of BOTH a child ref and a parent ref.
                    GRAPH <%3$s> {
                      ?riC a gen:RoleInstantiation ;
                           npa:inverseProperty gen:hasAdmin ;
                           npa:forSpace ?child ;
                           npa:forSpaceRef ?childRef ;
                           npa:forAgent ?publisher .
                      ?riP a gen:RoleInstantiation ;
                           npa:inverseProperty gen:hasAdmin ;
                           npa:forSpace ?parent ;
                           npa:forSpaceRef ?parentRef ;
                           npa:forAgent ?publisher .
                    }
                  }
                  UNION
                  {
                    # Mode B — co-declaration whose publisher covers the side this
                    # one's publisher doesn't. Between {publisher, publisher2},
                    # both admin sides must be covered.
                    GRAPH <%4$s> {
                      ?d2 a npa:SubSpaceDeclaration ;
                          npa:childSpace  ?child ;
                          npa:parentSpace ?parent ;
                          npa:pubkeyHash  ?pkh2 ;
                          npa:viaNanopub  ?np2 .
                      FILTER (?np2 != ?np)
                    }
                    %8$s
                    GRAPH <%3$s> {
                      ?acct2 a npa:AccountState ;
                             npa:pubkey ?pkh2 ;
                             npa:agent  ?publisher2 .
                      ?riA a gen:RoleInstantiation ;
                           npa:inverseProperty gen:hasAdmin ;
                           npa:forSpace ?child ;
                           npa:forSpaceRef ?childRef .
                      { ?riA npa:forAgent ?publisher } UNION { ?riA npa:forAgent ?publisher2 }
                      ?riB a gen:RoleInstantiation ;
                           npa:inverseProperty gen:hasAdmin ;
                           npa:forSpace ?parent ;
                           npa:forSpaceRef ?parentRef .
                      { ?riB npa:forAgent ?publisher } UNION { ?riB npa:forAgent ?publisher2 }
                    }
                  }
                  # 4. Invalidation filter on the primary declaration's nanopub.
                  %6$s
                  # 5. Load-number filter on bound ?np.
                  GRAPH <%7$s> {
                    ?np npa:hasLoadNumber ?ln .
                    FILTER (?ln > %5$d)
                  }
                  # 6. Dedup last — on the emitted ref-to-ref edge.
                  FILTER NOT EXISTS { GRAPH <%3$s> {
                    ?childRef npa:isSubSpaceOf ?parentRef .
                  } }
                }
                """.formatted(
                NPA.NAMESPACE,
                GEN.NAMESPACE,
                graph,
                SpacesVocab.SPACES_GRAPH,
                lastProcessed,
                invalidationFilter("np"),
                NPA.GRAPH,
                invalidationFilter("np2"));
    }

    /**
     * Maintained-resource admit pass. Copies validated
     * {@code npa:MaintainedResourceDeclaration} extraction rows into the space-state
     * graph (preserving the {@code npamrd:} subject) and emits convenience
     * {@code <r> npa:isMaintainedBy <s>} and {@code <s> npa:hasMaintainedResource <r>}
     * direct triples. Single satisfaction mode:
     * <ul>
     *   <li>Mode A — the declaration's publisher is a validated admin of the
     *       maintaining space.</li>
     * </ul>
     *
     * <p>No Mode B because only one space is involved; the two-sides-must-be-covered
     * concern that drives sub-space Mode B doesn't apply. Late-arrival is still
     * possible (declaration lands before the publisher's admin grant becomes valid):
     * the load-number filter on {@code ?np} excludes the candidate, and the
     * late-arrival sweep ({@link #runDownstreamWithoutLoadFilter}) re-runs this pass
     * without the load filter and catches it.
     */
    static String maintainedResourceAdmitUpdate(IRI graph, long lastProcessed) {
        return """
                PREFIX npa: <%1$s>
                PREFIX gen: <%2$s>
                INSERT { GRAPH <%3$s> {
                  ?d a npa:MaintainedResourceDeclaration ;
                     npa:resourceIri     ?r ;
                     npa:maintainerSpace ?s ;
                     npa:viaNanopub      ?np .
                  ?r npa:isMaintainedBy        ?sRef .
                  ?sRef npa:hasMaintainedResource ?r .
                  # TRANSITIONAL-DUAL-EMIT (Phase 1.5; remove in Phase 4): IRI-valued
                  # maintained-resource edge alongside the resource→ref one, so pre-ref
                  # published queries (e.g. get-view-displays' maintained hop) keep binding
                  # on a mixed-version fleet. This is the edge whose absence broke 1.15.0 —
                  # see doc/report-2026-06-12-mixed-fleet-spaceref-breakage.md.
                  ?r npa:isMaintainedBy        ?s .
                  ?s npa:hasMaintainedResource ?r .
                } }
                WHERE {
                  # 1. Anchor: candidate declarations from the extraction graph.
                  GRAPH <%4$s> {
                    ?d a npa:MaintainedResourceDeclaration ;
                       npa:resourceIri     ?r ;
                       npa:maintainerSpace ?s ;
                       npa:pubkeyHash      ?pkh ;
                       npa:viaNanopub      ?np .
                  }
                  # 2. Mirror: resolve ?pkh → ?publisher via the trust-approved row.
                  GRAPH <%3$s> {
                    ?acct a npa:AccountState ;
                          npa:pubkey ?pkh ;
                          npa:agent  ?publisher .
                    # 3. Authority gate (Mode A only): publisher is admin of a ref of the
                    #    maintaining space. ?sRef = that ref (resource → ref edge).
                    ?riA a gen:RoleInstantiation ;
                         npa:inverseProperty gen:hasAdmin ;
                         npa:forSpace ?s ;
                         npa:forSpaceRef ?sRef ;
                         npa:forAgent ?publisher .
                  }
                  # 4. Invalidation filter on the declaration's nanopub.
                  %6$s
                  # 5. Load-number filter on bound ?np.
                  GRAPH <%7$s> {
                    ?np npa:hasLoadNumber ?ln .
                    FILTER (?ln > %5$d)
                  }
                  # 6. Dedup last — on the emitted resource → ref edge.
                  FILTER NOT EXISTS { GRAPH <%3$s> {
                    ?r npa:isMaintainedBy ?sRef .
                  } }
                }
                """.formatted(
                NPA.NAMESPACE,
                GEN.NAMESPACE,
                graph,
                SpacesVocab.SPACES_GRAPH,
                lastProcessed,
                invalidationFilter("np"),
                NPA.GRAPH);
    }

    /**
     * Space-alias admit pass (issue #113). Copies validated
     * {@code npa:SpaceAliasDeclaration} extraction rows into the space-state graph
     * (preserving the {@code npaalias:} subject) and emits the directional
     * {@code <alias> npa:sameAsSpace <canonical>} edge consumed by the alias-aware
     * admin-authority lookups in {@link #attachmentValidationUpdate},
     * {@link #PUBLISHER_IS_ADMIN}, and {@link #publisherIsTieredRole}.
     *
     * <p>Two gates, both read against the (already-settled) admin closure in the
     * space-state graph:
     * <ul>
     *   <li><b>Authority</b> — the declaration's publisher (resolved via the mirrored
     *       trust-approved {@code AccountState}) is a validated admin of the
     *       <em>canonical</em> space. The alias is declared inside the canonical
     *       space's own {@code gen:Space} nanopub, so this is the same evidence rule
     *       as a {@code gen:hasRole} attachment.</li>
     *   <li><b>Anti-hijack</b> — the alias must not be an independently-governed live
     *       space: it must have no admin who is not also an admin of the canonical
     *       space ({@code admins(alias) ⊆ admins(canonical)}). The common rename case
     *       (the alias's own definition was superseded, so it has no live admin
     *       closure) passes trivially; an attacker publishing
     *       {@code <evil> owl:sameAs <activeSpace>} is rejected because the active
     *       space has admins not in evil's set.</li>
     * </ul>
     *
     * <p>Late-arrival: when the canonical admin grant only becomes valid in the same
     * cycle as the declaration, the load-number filter on {@code ?np} excludes the
     * candidate; the late-arrival sweep ({@link #runDownstreamWithoutLoadFilter})
     * re-runs this pass without the load filter and catches it.
     */
    static String aliasAdmitUpdate(IRI graph, long lastProcessed) {
        // Ref-keyed (see doc/design-spaceref-isolation.md). The declaration names bare
        // canonical/alias IRIs. It is admitted per canonical *ref* whose admin set
        // contains the publisher; the emitted edge is ref-valued on the canonical side
        // (<alias> npa:sameAsSpace <canonicalRef>), which is what the alias-aware admin
        // lookups in the attachment tier consume. Anti-hijack compares the alias IRI's
        // admins against that specific canonical ref's admins — strictly tighter than the
        // old bare-IRI form.
        return """
                PREFIX npa: <%1$s>
                PREFIX gen: <%2$s>
                INSERT { GRAPH <%3$s> {
                  ?d a npa:SpaceAliasDeclaration ;
                     npa:canonicalSpace ?canonical ;
                     npa:aliasSpace     ?alias ;
                     npa:viaNanopub     ?np .
                  ?alias npa:sameAsSpace ?canonRef .
                  # TRANSITIONAL-DUAL-EMIT (Phase 1.5; remove in Phase 4): IRI-valued
                  # alias edge alongside the ref-valued one, so pre-ref published queries
                  # that resolve owl:sameAs by bare canonical IRI keep binding on a
                  # mixed-version fleet. Internal alias-aware lookups (attachment tier)
                  # join through npa:forSpaceRef, which is ref-valued, so this IRI-valued
                  # object never satisfies them — it is inert internally, read-only for
                  # legacy consumers. See doc/report-2026-06-12-mixed-fleet-spaceref-breakage.md.
                  ?alias npa:sameAsSpace ?canonical .
                } }
                WHERE {
                  # 1. Anchor: candidate alias declarations from the extraction graph.
                  GRAPH <%4$s> {
                    ?d a npa:SpaceAliasDeclaration ;
                       npa:canonicalSpace ?canonical ;
                       npa:aliasSpace     ?alias ;
                       npa:pubkeyHash     ?pkh ;
                       npa:viaNanopub     ?np .
                  }
                  # 2. Authority gate per canonical ref: ?canonRef is a ref of ?canonical
                  #    whose admin set contains the declaration's publisher.
                  GRAPH <%4$s> { ?canonRef npa:spaceIri ?canonical . }
                  GRAPH <%3$s> {
                    ?acct a npa:AccountState ;
                          npa:pubkey ?pkh ;
                          npa:agent  ?publisher .
                    ?adminRI a gen:RoleInstantiation ;
                             npa:inverseProperty gen:hasAdmin ;
                             npa:forSpaceRef ?canonRef ;
                             npa:forAgent ?publisher .
                  }
                  # 3. Anti-hijack: the alias IRI must have no admin who is not also an
                  #    admin of this canonical ref (admins(alias) ⊆ admins(canonRef)).
                  FILTER NOT EXISTS {
                    GRAPH <%3$s> {
                      ?aliasAdmin a gen:RoleInstantiation ;
                                  npa:inverseProperty gen:hasAdmin ;
                                  npa:forSpace ?alias ;
                                  npa:forAgent ?otherAgent .
                    }
                    FILTER NOT EXISTS {
                      GRAPH <%3$s> {
                        ?canonAdmin a gen:RoleInstantiation ;
                                    npa:inverseProperty gen:hasAdmin ;
                                    npa:forSpaceRef ?canonRef ;
                                    npa:forAgent ?otherAgent .
                      }
                    }
                  }
                  # 4. Invalidation filter on the declaration's nanopub.
                  %6$s
                  # 5. Load-number filter on bound ?np.
                  GRAPH <%7$s> {
                    ?np npa:hasLoadNumber ?ln .
                    FILTER (?ln > %5$d)
                  }
                  # 6. Dedup last — on the emitted (alias, canonical ref) edge.
                  FILTER NOT EXISTS { GRAPH <%3$s> {
                    ?alias npa:sameAsSpace ?canonRef .
                  } }
                }
                """.formatted(
                NPA.NAMESPACE,
                GEN.NAMESPACE,
                graph,
                SpacesVocab.SPACES_GRAPH,
                lastProcessed,
                invalidationFilter("np"),
                NPA.GRAPH);
    }

    /**
     * URL-prefix sub-space fallback admit pass. For every pair of {@code SpaceRef}
     * aggregates where the child's {@code npa:hasIdPrefix} matches the parent's
     * {@code npa:spaceIri}, emits convenience {@code <child> npa:isSubSpaceOf <parent>}
     * and {@code <parent> npa:hasSubSpace <child>} direct triples plus a reified
     * {@code npa:DerivedSubSpaceLink} tag carrying {@code npa:derivationKind
     * npa:byUrlPrefix} so consumers can hide derived edges.
     *
     * <p>Per-child suppression: any validated {@code npa:SubSpaceDeclaration} on the
     * child in {@code npass:<…>} suppresses every fallback edge for that child.
     * Suppression checks the validated set (not raw extraction-graph declarations)
     * so an unapproved or in-flight Mode B declaration doesn't silently hide both
     * the URL-prefix fallback and the (still-invalid) explicit relation.
     *
     * <p>Run order: must run after {@link #subSpaceAdmitUpdate} commits in the
     * same cycle so the suppression check sees this cycle's freshly-validated
     * declarations.
     *
     * <p>No load-number filter: the fallback depends on which Spaces exist (parent
     * + child {@code SpaceRef}s), not on which were just added. Always full-scan;
     * the dedup {@code FILTER NOT EXISTS} on the tag IRI prevents re-insertion.
     *
     * <p>No invalidation handling: derived edges have no source nanopub. Two
     * staleness modes: (a) child later gets first validated declaration → old
     * derived edges stay sticky until the next periodic rebuild (same policy as
     * admin-RI invalidation); (b) child loses last validated declaration → the
     * regular fallback pass on the next cycle re-engages, adds derived edges
     * incrementally, no rebuild needed.
     */
    static String subSpacePrefixFallbackUpdate(IRI graph) {
        return """
                PREFIX npa: <%1$s>
                INSERT { GRAPH <%2$s> {
                  ?childRef  npa:isSubSpaceOf ?parentRef .
                  ?parentRef npa:hasSubSpace  ?childRef  .
                  # TRANSITIONAL-DUAL-EMIT (Phase 1.5; remove in Phase 4): IRI-valued
                  # derived sub-space edge alongside the ref-to-ref one, mirroring the
                  # explicit sub-space pass, so pre-ref published queries keep binding on a
                  # mixed-version fleet. See doc/report-2026-06-12-mixed-fleet-spaceref-breakage.md.
                  ?child  npa:isSubSpaceOf ?parent .
                  ?parent npa:hasSubSpace  ?child  .
                  ?tagIri a npa:DerivedSubSpaceLink ;
                          npa:childSpace     ?child ;
                          npa:parentSpace    ?parent ;
                          npa:derivationKind npa:byUrlPrefix .
                } }
                WHERE {
                  # 1. Anchor: child SpaceRef → its path-prefixes (extracted at load
                  #    time from the Space IRI; see SpacesExtractor.enumerateIdPrefixes).
                  GRAPH <%3$s> {
                    ?childRef  npa:spaceIri    ?child ;
                               npa:hasIdPrefix ?parent .
                    # 2. Parent SpaceRef must exist for the same IRI as the prefix.
                    ?parentRef npa:spaceIri    ?parent .
                  }
                  # 3. Suppress fallback for any child that has a validated declaration
                  #    in this state graph. Per-child IRI, all-or-nothing.
                  FILTER NOT EXISTS {
                    GRAPH <%2$s> {
                      ?d a npa:SubSpaceDeclaration ;
                         npa:childSpace ?child .
                    }
                  }
                  # 4. Mint a deterministic tag IRI per (child ref, parent ref) — the edge
                  #    is emitted ref-to-ref, so the tag and dedup are per ref-pair.
                  BIND(IRI(CONCAT("http://purl.org/nanopub/admin/derivedlink/",
                                  MD5(CONCAT(STR(?childRef), "|", STR(?parentRef))))) AS ?tagIri)
                  # 5. Dedup: don't re-insert if this tag is already present.
                  FILTER NOT EXISTS {
                    GRAPH <%2$s> {
                      ?tagIri a npa:DerivedSubSpaceLink .
                    }
                  }
                }
                """.formatted(
                NPA.NAMESPACE,
                graph,
                SpacesVocab.SPACES_GRAPH);
    }

    // ---------------- Invalidation templates (incremental cycle) ----------------

    /**
     * WHERE clause shared by the admin-RI invalidation ASK precheck and the
     * matching DELETE. Identifies admin-tier {@code gen:RoleInstantiation} rows
     * in the space-state graph whose {@code npa:viaNanopub} is the target of an
     * {@code npx:invalidates} triple in {@code npa:graph} whose subject nanopub
     * has a load number in {@code (lastProcessed, ∞)}.
     */
    static String adminInvalidationCheckWhere(IRI graph, long lastProcessed) {
        return String.format("""
                  GRAPH <%1$s> {
                    ?ri a gen:RoleInstantiation ;
                        npa:inverseProperty gen:hasAdmin ;
                        npa:viaNanopub ?np .
                  }
                  GRAPH <%2$s> {
                    ?invNp <%3$s> ?np ;
                           npa:hasLoadNumber ?ln .
                    FILTER (?ln > %4$d)
                  }
                """, graph, NPA.GRAPH, NPX.INVALIDATES, lastProcessed);
    }

    /** DELETE template for admin-tier RoleInstantiations whose source nanopub was invalidated. */
    static String adminInvalidationDelete(IRI graph, long lastProcessed) {
        return String.format("""
                PREFIX npa: <%1$s>
                PREFIX gen: <%2$s>
                DELETE { GRAPH <%3$s> {
                  ?ri ?p ?o .
                } }
                WHERE {
                  GRAPH <%3$s> { ?ri ?p ?o . }
                %4$s
                }
                """, NPA.NAMESPACE, GEN.NAMESPACE, graph,
                adminInvalidationCheckWhere(graph, lastProcessed));
    }

    /** WHERE clause for RoleAssignment invalidation. */
    static String roleAssignmentInvalidationCheckWhere(IRI graph, long lastProcessed) {
        return String.format("""
                  GRAPH <%1$s> {
                    ?ra a gen:RoleAssignment ;
                        npa:viaNanopub ?np .
                  }
                  GRAPH <%2$s> {
                    ?invNp <%3$s> ?np ;
                           npa:hasLoadNumber ?ln .
                    FILTER (?ln > %4$d)
                  }
                """, graph, NPA.GRAPH, NPX.INVALIDATES, lastProcessed);
    }

    /** DELETE template for RoleAssignments whose source nanopub was invalidated. */
    static String roleAssignmentInvalidationDelete(IRI graph, long lastProcessed) {
        return String.format("""
                PREFIX npa: <%1$s>
                PREFIX gen: <%2$s>
                DELETE { GRAPH <%3$s> {
                  ?ra ?p ?o .
                } }
                WHERE {
                  GRAPH <%3$s> { ?ra ?p ?o . }
                %4$s
                }
                """, NPA.NAMESPACE, GEN.NAMESPACE, graph,
                roleAssignmentInvalidationCheckWhere(graph, lastProcessed));
    }

    /**
     * WHERE clause for RoleDeclaration invalidation. ASK-only (no DELETE):
     * RoleDeclarations live in {@code npa:spacesGraph} and aren't materialized
     * into the space-state graph, so there's nothing to remove from the
     * space-state. The ASK still flips {@code npa:needsFullRebuild} because
     * sticky downstream RIs that were derived under the now-invalidated RD
     * need a from-scratch recompute.
     */
    static String roleDeclarationInvalidationCheckWhere(long lastProcessed) {
        return String.format("""
                  GRAPH <%1$s> {
                    ?rd a npa:RoleDeclaration ;
                        npa:viaNanopub ?np .
                  }
                  GRAPH <%2$s> {
                    ?invNp <%3$s> ?np ;
                           npa:hasLoadNumber ?ln .
                    FILTER (?ln > %4$d)
                  }
                """, SpacesVocab.SPACES_GRAPH, NPA.GRAPH, NPX.INVALIDATES, lastProcessed);
    }

    /**
     * DELETE template for non-admin (leaf-tier) RoleInstantiations whose source
     * nanopub was invalidated. Identified as {@code gen:RoleInstantiation} rows
     * lacking the admin-pinning {@code npa:inverseProperty gen:hasAdmin} triple.
     * No flag is set; leaf-tier removals are recoverable on the next cycle.
     */
    static String leafTierInvalidationDelete(IRI graph, long lastProcessed) {
        return String.format("""
                PREFIX npa: <%1$s>
                PREFIX gen: <%2$s>
                DELETE { GRAPH <%3$s> {
                  ?ri ?p ?o .
                } }
                WHERE {
                  GRAPH <%3$s> {
                    ?ri a gen:RoleInstantiation ;
                        npa:viaNanopub ?np .
                    FILTER NOT EXISTS { ?ri npa:inverseProperty gen:hasAdmin }
                    ?ri ?p ?o .
                  }
                  GRAPH <%4$s> {
                    ?invNp <%5$s> ?np ;
                           npa:hasLoadNumber ?ln .
                    FILTER (?ln > %6$d)
                  }
                }
                """, NPA.NAMESPACE, GEN.NAMESPACE, graph,
                NPA.GRAPH, NPX.INVALIDATES, lastProcessed);
    }

    /**
     * WHERE clause shared by the sub-space invalidation ASK precheck and the
     * matching DELETE. Identifies validated {@code npa:SubSpaceDeclaration} rows
     * in the space-state graph whose {@code npa:viaNanopub} is the target of an
     * {@code npx:invalidates} triple in {@code npa:graph} whose subject nanopub
     * has a load number in {@code (lastProcessed, ∞)}.
     */
    static String subSpaceInvalidationCheckWhere(IRI graph, long lastProcessed) {
        return String.format("""
                  GRAPH <%1$s> {
                    ?d a npa:SubSpaceDeclaration ;
                       npa:viaNanopub ?np .
                  }
                  GRAPH <%2$s> {
                    ?invNp <%3$s> ?np ;
                           npa:hasLoadNumber ?ln .
                    FILTER (?ln > %4$d)
                  }
                """, graph, NPA.GRAPH, NPX.INVALIDATES, lastProcessed);
    }

    /**
     * DELETE template for validated {@code npa:SubSpaceDeclaration} rows whose
     * source nanopub was invalidated. Removes the per-declaration row by subject;
     * the convenience direct triples ({@code <child> npa:isSubSpaceOf <parent>}
     * and inverse) are left sticky and cleaned by the next periodic full rebuild
     * (same staleness policy as admin-RI invalidation — see {@code
     * doc/design-space-repositories.md} on the structural-rebuild flag).
     */
    static String subSpaceInvalidationDelete(IRI graph, long lastProcessed) {
        return String.format("""
                PREFIX npa: <%1$s>
                PREFIX gen: <%2$s>
                DELETE { GRAPH <%3$s> {
                  ?d ?p ?o .
                } }
                WHERE {
                  GRAPH <%3$s> { ?d ?p ?o . }
                %4$s
                }
                """, NPA.NAMESPACE, GEN.NAMESPACE, graph,
                subSpaceInvalidationCheckWhere(graph, lastProcessed));
    }

    /**
     * DELETE template for validated {@code npa:MaintainedResourceDeclaration} rows
     * whose source nanopub was invalidated. Removes the per-declaration row by
     * subject; the convenience direct triples ({@code <r> npa:isMaintainedBy <s>}
     * and inverse) are left sticky and cleaned by the next periodic full rebuild
     * (same staleness policy as sub-space declaration invalidation, but without
     * the structural-rebuild flag — maintained-resource is a leaf relation, no
     * downstream consumers depend on its closure).
     */
    static String maintainedResourceInvalidationDelete(IRI graph, long lastProcessed) {
        return String.format("""
                PREFIX npa: <%1$s>
                PREFIX gen: <%2$s>
                DELETE { GRAPH <%3$s> {
                  ?d ?p ?o .
                } }
                WHERE {
                  GRAPH <%3$s> {
                    ?d a npa:MaintainedResourceDeclaration ;
                       npa:viaNanopub ?np .
                    ?d ?p ?o .
                  }
                  GRAPH <%4$s> {
                    ?invNp <%5$s> ?np ;
                           npa:hasLoadNumber ?ln .
                    FILTER (?ln > %6$d)
                  }
                }
                """, NPA.NAMESPACE, GEN.NAMESPACE, graph,
                NPA.GRAPH, NPX.INVALIDATES, lastProcessed);
    }

    /**
     * WHERE clause shared by the alias invalidation ASK precheck and the matching
     * DELETE. Identifies validated {@code npa:SpaceAliasDeclaration} rows in the
     * space-state graph whose {@code npa:viaNanopub} is the target of an
     * {@code npx:invalidates} triple in {@code npa:graph} whose subject nanopub has a
     * load number in {@code (lastProcessed, ∞)}.
     */
    static String aliasInvalidationCheckWhere(IRI graph, long lastProcessed) {
        return String.format("""
                  GRAPH <%1$s> {
                    ?d a npa:SpaceAliasDeclaration ;
                       npa:viaNanopub ?np .
                  }
                  GRAPH <%2$s> {
                    ?invNp <%3$s> ?np ;
                           npa:hasLoadNumber ?ln .
                    FILTER (?ln > %4$d)
                  }
                """, graph, NPA.GRAPH, NPX.INVALIDATES, lastProcessed);
    }

    /**
     * DELETE template for validated {@code npa:SpaceAliasDeclaration} rows whose
     * source nanopub was invalidated. Removes the per-declaration row by subject; the
     * convenience {@code <alias> npa:sameAsSpace <canonical>} edge is left sticky and
     * cleaned by the next periodic full rebuild (same staleness policy as sub-space
     * declaration invalidation — the alias feeds the authority closure, so this kind
     * is structural and flips {@code npa:needsFullRebuild}).
     */
    static String aliasInvalidationDelete(IRI graph, long lastProcessed) {
        return String.format("""
                PREFIX npa: <%1$s>
                PREFIX gen: <%2$s>
                DELETE { GRAPH <%3$s> {
                  ?d ?p ?o .
                } }
                WHERE {
                  GRAPH <%3$s> { ?d ?p ?o . }
                %4$s
                }
                """, NPA.NAMESPACE, GEN.NAMESPACE, graph,
                aliasInvalidationCheckWhere(graph, lastProcessed));
    }

    /**
     * WHERE clause shared by the preset-deactivation ASK precheck and the matching DELETE
     * (Nanodash issue #302). Binds {@code ?ra} = a materialized preset-derived
     * {@code gen:RoleAssignment} ({@code npa:derivedFromPreset}) for which a <em>newer,
     * admin-authored</em> same-{@code (preset, resource)} assignment exists by
     * {@code dct:created} (load number in {@code (lastProcessed, ∞)}). This is NOT an
     * {@code npx:invalidates} check — preset activation is latest-wins by timestamp.
     *
     * <p>Authorization-scoped (anti-hijack, design doc §3/§4.4): the newer assignment's
     * publisher must itself be a validated admin of the row's {@code npa:forSpaceRef}, so an
     * unauthorized key's newer assignment can neither delete nor shadow an admin's
     * materialized role. {@code dct:created} is written as a full IRI (not a {@code dct:}
     * prefix) because {@link #wouldInvalidate}'s ASK wrapper only declares {@code npa:} /
     * {@code gen:}.
     */
    static String presetDeactivationCheckWhere(IRI graph, long lastProcessed) {
        return String.format("""
                  GRAPH <%1$s> {
                    ?ra a gen:RoleAssignment ;
                        npa:derivedFromPreset ?assignNp ;
                        npa:forSpaceRef ?targetRef .
                  }
                  GRAPH <%2$s> {
                    ?pa a npa:PresetAssignment ;
                        npa:viaNanopub  ?assignNp ;
                        npa:ofPreset    ?preset ;
                        npa:forResource ?resource ;
                        <http://purl.org/dc/terms/created> ?created .
                    ?paNewer a npa:PresetAssignment ;
                             npa:ofPreset    ?preset ;
                             npa:forResource ?resource ;
                             npa:pubkeyHash  ?pkhNewer ;
                             npa:viaNanopub  ?assignNpNewer ;
                             <http://purl.org/dc/terms/created> ?createdNewer .
                    FILTER (?createdNewer > ?created
                            || (?createdNewer = ?created && STR(?paNewer) > STR(?pa)))
                  }
                  GRAPH <%3$s> {
                    ?assignNpNewer npa:hasLoadNumber ?lnNewer .
                    FILTER (?lnNewer > %4$d)
                  }
                  GRAPH <%1$s> {
                    ?acctNewer a npa:AccountState ;
                               npa:agent  ?publisherNewer ;
                               npa:pubkey ?pkhNewer .
                    ?adminRINewer a gen:RoleInstantiation ;
                                  npa:forSpaceRef ?targetRef ;
                                  npa:inverseProperty gen:hasAdmin ;
                                  npa:forAgent ?publisherNewer .
                  }
                """, graph, SpacesVocab.SPACES_GRAPH, NPA.GRAPH, lastProcessed);
    }

    /**
     * DELETE template for preset-derived {@code gen:RoleAssignment} rows superseded by a
     * newer admin-authored same-pair assignment (issue #302). Removes the whole row by
     * subject; scoped via {@code npa:derivedFromPreset} so directly-published attachments
     * are never touched. The {@link #presetAttachmentValidationUpdate} re-INSERT in the
     * same cycle re-materializes the pair iff the newest assignment is still active.
     */
    static String presetDeactivationDelete(IRI graph, long lastProcessed) {
        return String.format("""
                PREFIX npa: <%1$s>
                PREFIX gen: <%2$s>
                DELETE { GRAPH <%3$s> {
                  ?ra ?p ?o .
                } }
                WHERE {
                  GRAPH <%3$s> { ?ra ?p ?o . }
                %4$s
                }
                """, NPA.NAMESPACE, GEN.NAMESPACE, graph,
                presetDeactivationCheckWhere(graph, lastProcessed));
    }

    /**
     * DELETE template for ref-scoped preset-assignment stamps ({@link
     * #presetAssignmentRefStampUpdate}) whose underlying assignment nanopub was
     * hard-retracted (issue #122). Removes the whole row by subject; scoped to
     * state-graph {@code npa:PresetAssignment} rows that carry {@code npa:forSpaceRef}
     * (the IRI-keyed extraction rows never do), so it can never touch them.
     *
     * <p>Leaf delete — no structural flag: nothing downstream derives from a listing
     * stamp, so a stale row only mis-displays a retracted assignment until this cycle's
     * delete runs. Admin-grant revocation is bounded by the periodic full rebuild (same
     * sticky-convenience policy as the alias / sub-space declaration edges). A
     * <em>deactivation</em> needs no delete here: it is represented as a newer
     * admin-authored stamp with {@code npa:isActivated false}, resolved by the consumer's
     * latest-wins.
     */
    static String presetAssignmentRefInvalidationDelete(IRI graph, long lastProcessed) {
        return String.format("""
                PREFIX npa: <%1$s>
                PREFIX gen: <%2$s>
                DELETE { GRAPH <%3$s> {
                  ?paRef ?p ?o .
                } }
                WHERE {
                  GRAPH <%3$s> {
                    ?paRef a npa:PresetAssignment ;
                           npa:forSpaceRef ?targetRef ;
                           npa:viaNanopub  ?assignNp .
                    ?paRef ?p ?o .
                  }
                  GRAPH <%4$s> {
                    ?invNp <%5$s> ?assignNp ;
                           npa:hasLoadNumber ?ln .
                    FILTER (?ln > %6$d)
                  }
                }
                """, NPA.NAMESPACE, GEN.NAMESPACE, graph,
                NPA.GRAPH, NPX.INVALIDATES, lastProcessed);
    }

    /** Wraps an ASK by joining the shared prefixes. */
    private boolean wouldInvalidate(IRI graph, long lastProcessed,
                                    boolean adminPinned, String whereClause) {
        // adminPinned is informational only — kept to make call sites read clearly;
        // the WHERE clause already encodes the kind via its own type predicates.
        String ask = String.format("""
                PREFIX npa: <%1$s>
                PREFIX gen: <%2$s>
                ASK { %3$s }
                """, NPA.NAMESPACE, GEN.NAMESPACE, whereClause);
        return runAsk(ask);
    }

    private boolean runAsk(String sparql) {
        try (RepositoryConnection conn = TripleStore.get().getRepoConnection(SPACES_REPO)) {
            return conn.prepareBooleanQuery(QueryLanguage.SPARQL, sparql).evaluate();
        }
    }

    private void executeUpdate(String sparqlUpdate) {
        try (RepositoryConnection conn = TripleStore.get().getRepoConnection(SPACES_REPO)) {
            conn.prepareUpdate(QueryLanguage.SPARQL, sparqlUpdate).execute();
        }
    }

    // ---------------- Mirror step ----------------

    /**
     * Copies trust-approved {@code npa:AccountState} rows from {@code npat:<T>}
     * in the {@code trust} repo into {@code newGraph} in the {@code spaces} repo,
     * inside one spaces-side serializable transaction.
     *
     * @return number of rows mirrored (useful for metrics / logging)
     */
    int mirrorTrustState(String trustStateHash, IRI newGraph) {
        IRI trustStateIri = NPAT.forHash(trustStateHash);
        int count = 0;
        try (RepositoryConnection trustConn = TripleStore.get().getRepoConnection(TRUST_REPO);
             RepositoryConnection spacesConn = TripleStore.get().getRepoConnection(SPACES_REPO)) {
            trustConn.begin(IsolationLevels.READ_COMMITTED);
            spacesConn.begin(IsolationLevels.SERIALIZABLE);
            // Walk rdf:type triples in the trust state's graph; for each AccountState,
            // check status and copy the approved ones verbatim (minus status-specific
            // detail triples, which we don't need for validation).
            try (RepositoryResult<Statement> typeRows = trustConn.getStatements(
                    null, RDF.TYPE, NPA_ACCOUNT_STATE, trustStateIri)) {
                while (typeRows.hasNext()) {
                    Statement st = typeRows.next();
                    if (!(st.getSubject() instanceof IRI accountStateIri)) continue;
                    Value status = trustConn.getStatements(accountStateIri, NPA_TRUST_STATUS, null, trustStateIri)
                            .stream().findFirst().map(Statement::getObject).orElse(null);
                    if (!(status instanceof IRI statusIri) || !APPROVED_SET.contains(statusIri)) continue;
                    Value agent = trustConn.getStatements(accountStateIri, NPA_AGENT, null, trustStateIri)
                            .stream().findFirst().map(Statement::getObject).orElse(null);
                    Value pubkey = trustConn.getStatements(accountStateIri, NPA_PUBKEY, null, trustStateIri)
                            .stream().findFirst().map(Statement::getObject).orElse(null);
                    if (agent == null || pubkey == null) {
                        logger.warn("AuthorityResolver.mirror: account {} missing agent or pubkey; skipping",
                                accountStateIri);
                        continue;
                    }
                    spacesConn.add(accountStateIri, RDF.TYPE, NPA_ACCOUNT_STATE, newGraph);
                    spacesConn.add(accountStateIri, NPA_AGENT, agent, newGraph);
                    spacesConn.add(accountStateIri, NPA_PUBKEY, pubkey, newGraph);
                    spacesConn.add(accountStateIri, NPA_TRUST_STATUS, statusIri, newGraph);
                    count++;
                }
            }
            // Mirror canonical foaf:name triples for approved agents. The trust
            // loader emits one per agent (across approved keys, MAX(ratio) wins).
            // Copying them into the space-state graph means consumers reading
            // ?agent foaf:name ?n inside the state graph hit local data, with no
            // cross-repo SERVICE.
            try (RepositoryResult<Statement> nameRows = trustConn.getStatements(
                    null, FOAF.NAME, null, trustStateIri)) {
                while (nameRows.hasNext()) {
                    Statement st = nameRows.next();
                    spacesConn.add(st.getSubject(), st.getPredicate(), st.getObject(), newGraph);
                }
            }
            spacesConn.commit();
            trustConn.commit();
        }
        return count;
    }

    // ---------------- Pointer + counter helpers ----------------

    /**
     * Reads the current {@code npa:hasCurrentSpaceState} pointer from the
     * {@code npa:graph} admin graph of the {@code spaces} repo. Returns
     * {@code null} if no pointer exists yet.
     */
    IRI getCurrentSpaceStateGraph() {
        try (RepositoryConnection conn = TripleStore.get().getRepoConnection(SPACES_REPO)) {
            Value v = Utils.getObjectForPattern(conn, NPA.GRAPH, NPA.THIS_REPO,
                    SpacesVocab.HAS_CURRENT_SPACE_STATE);
            return (v instanceof IRI iri) ? iri : null;
        } catch (Exception ex) {
            logger.warn("AuthorityResolver: failed to read hasCurrentSpaceState pointer: {}", ex.toString());
            return null;
        }
    }

    long getCurrentLoadCounter() {
        try (RepositoryConnection conn = TripleStore.get().getRepoConnection(SPACES_REPO)) {
            Value v = Utils.getObjectForPattern(conn, NPA.GRAPH, NPA.THIS_REPO,
                    SpacesVocab.CURRENT_LOAD_COUNTER);
            if (v == null) return 0;
            try {
                return Long.parseLong(v.stringValue());
            } catch (NumberFormatException ex) {
                logger.warn("AuthorityResolver: non-numeric currentLoadCounter: {}", v);
                return 0;
            }
        } catch (Exception ex) {
            logger.warn("AuthorityResolver: failed to read currentLoadCounter: {}", ex.toString());
            return 0;
        }
    }

    /**
     * Atomic pointer flip: a single SPARQL {@code DELETE … INSERT … WHERE}
     * replaces the old pointer with the new one in one statement, so readers
     * never see a zero-pointer window.
     */
    void flipPointer(IRI newGraph) {
        String update = String.format("""
                DELETE { GRAPH <%s> { <%s> <%s> ?old } }
                INSERT { GRAPH <%s> { <%s> <%s> <%s> } }
                WHERE  { OPTIONAL { GRAPH <%s> { <%s> <%s> ?old } } }
                """,
                NPA.GRAPH, NPA.THIS_REPO, SpacesVocab.HAS_CURRENT_SPACE_STATE,
                NPA.GRAPH, NPA.THIS_REPO, SpacesVocab.HAS_CURRENT_SPACE_STATE, newGraph,
                NPA.GRAPH, NPA.THIS_REPO, SpacesVocab.HAS_CURRENT_SPACE_STATE);
        try (RepositoryConnection conn = TripleStore.get().getRepoConnection(SPACES_REPO)) {
            conn.begin(IsolationLevels.SERIALIZABLE);
            conn.prepareUpdate(QueryLanguage.SPARQL, update).execute();
            conn.commit();
        }
    }

    void writeProcessedUpTo(IRI graph, long loadCounter) {
        String update = String.format("""
                DELETE { GRAPH <%s> { <%s> <%s> ?old } }
                INSERT { GRAPH <%s> { <%s> <%s> "%d"^^<http://www.w3.org/2001/XMLSchema#long> } }
                WHERE  { OPTIONAL { GRAPH <%s> { <%s> <%s> ?old } } }
                """,
                graph, graph, SpacesVocab.PROCESSED_UP_TO,
                graph, graph, SpacesVocab.PROCESSED_UP_TO, loadCounter,
                graph, graph, SpacesVocab.PROCESSED_UP_TO);
        try (RepositoryConnection conn = TripleStore.get().getRepoConnection(SPACES_REPO)) {
            conn.begin(IsolationLevels.SERIALIZABLE);
            conn.prepareUpdate(QueryLanguage.SPARQL, update).execute();
            conn.commit();
        }
    }

    /**
     * Reads {@code processedUpTo} from the given space-state graph.
     * Returns {@code -1} if absent (graph not fully built yet).
     */
    long readProcessedUpTo(IRI graph) {
        try (RepositoryConnection conn = TripleStore.get().getRepoConnection(SPACES_REPO)) {
            String query = String.format(
                    "SELECT ?n WHERE { GRAPH <%s> { <%s> <%s> ?n } }",
                    graph, graph, SpacesVocab.PROCESSED_UP_TO);
            try (TupleQueryResult r = conn.prepareTupleQuery(QueryLanguage.SPARQL, query).evaluate()) {
                if (!r.hasNext()) return -1;
                BindingSet b = r.next();
                return Long.parseLong(b.getBinding("n").getValue().stringValue());
            }
        } catch (Exception ex) {
            logger.warn("AuthorityResolver: failed to read processedUpTo for {}: {}", graph, ex.toString());
            return -1;
        }
    }

    /**
     * Reads the {@code npa:needsFullRebuild} flag (boolean literal) from
     * {@code npa:graph} in the {@code spaces} repo. Defaults to {@code false}
     * when the triple is absent.
     */
    boolean readNeedsFullRebuild() {
        try (RepositoryConnection conn = TripleStore.get().getRepoConnection(SPACES_REPO)) {
            Value v = Utils.getObjectForPattern(conn, NPA.GRAPH, NPA.THIS_REPO,
                    SpacesVocab.NEEDS_FULL_REBUILD);
            return v != null && Boolean.parseBoolean(v.stringValue());
        } catch (Exception ex) {
            logger.warn("AuthorityResolver: failed to read needsFullRebuild: {}", ex.toString());
            return false;
        }
    }

    void setNeedsFullRebuild() {
        writeNeedsFullRebuild(true);
    }

    void clearNeedsFullRebuild() {
        writeNeedsFullRebuild(false);
    }

    private void writeNeedsFullRebuild(boolean value) {
        String update = String.format("""
                DELETE { GRAPH <%s> { <%s> <%s> ?old } }
                INSERT { GRAPH <%s> { <%s> <%s> "%s"^^<http://www.w3.org/2001/XMLSchema#boolean> } }
                WHERE  { OPTIONAL { GRAPH <%s> { <%s> <%s> ?old } } }
                """,
                NPA.GRAPH, NPA.THIS_REPO, SpacesVocab.NEEDS_FULL_REBUILD,
                NPA.GRAPH, NPA.THIS_REPO, SpacesVocab.NEEDS_FULL_REBUILD, value,
                NPA.GRAPH, NPA.THIS_REPO, SpacesVocab.NEEDS_FULL_REBUILD);
        try (RepositoryConnection conn = TripleStore.get().getRepoConnection(SPACES_REPO)) {
            conn.begin(IsolationLevels.SERIALIZABLE);
            conn.prepareUpdate(QueryLanguage.SPARQL, update).execute();
            conn.commit();
        }
    }

    void dropGraph(IRI graph) {
        try (RepositoryConnection conn = TripleStore.get().getRepoConnection(SPACES_REPO)) {
            conn.begin(IsolationLevels.SERIALIZABLE);
            conn.clear(graph);
            conn.commit();
            logger.info("AuthorityResolver: dropped old space-state graph {}", graph);
        }
    }

    // ---------------- Trust-repo pointer lookup (used by TrustStateRegistry's bootstrap) ----------------

    /**
     * Queries the {@code trust} repo directly for the current trust-state hash.
     * Prefer {@link TrustStateRegistry#getCurrentHash()} in normal operation —
     * this helper exists for tests and diagnostics.
     *
     * @return the current trust-state hash, or empty if none is set
     */
    Optional<String> readTrustRepoCurrentHash() {
        try (RepositoryConnection conn = TripleStore.get().getRepoConnection(TRUST_REPO)) {
            Value v = Utils.getObjectForPattern(conn, NPA.GRAPH, NPA.THIS_REPO,
                    NPA_HAS_CURRENT_TRUST_STATE);
            if (!(v instanceof IRI iri)) return Optional.empty();
            String s = iri.stringValue();
            if (!s.startsWith(NPAT.NAMESPACE)) return Optional.empty();
            return Optional.of(s.substring(NPAT.NAMESPACE.length()));
        } catch (Exception ex) {
            logger.warn("AuthorityResolver: failed to read trust-repo current pointer: {}", ex.toString());
            return Optional.empty();
        }
    }

    private static String abbrev(String hash) {
        return hash.length() > 12 ? hash.substring(0, 12) + "…" : hash;
    }

}
