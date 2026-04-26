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
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.QueryLanguage;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryResult;
import org.nanopub.vocabulary.NPA;
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
 * per-tier INSERTs (admin → attachment → maintainer → member → observer) →
 * late-arrival sweep (re-run downstream tiers without the load-number filter
 * iff this cycle added any structural rows). Sets {@code npa:needsFullRebuild}
 * when an admin RI / RoleAssignment / RoleDeclaration was invalidated; periodic
 * worker turns the flag into a from-scratch rebuild.
 *
 * <p>See {@code doc/plan-space-repositories.md} — this implements the "Full
 * build", "Incremental cycle", and "Periodic full rebuild" procedures.
 */
public final class AuthorityResolver {

    private static final Logger log = LoggerFactory.getLogger(AuthorityResolver.class);

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
            log.debug("AuthorityResolver.tick: no current trust state yet — skipping");
            return;
        }
        IRI currentGraph = getCurrentSpaceStateGraph();
        String currentGraphName = (currentGraph == null) ? null
                : currentGraph.stringValue().substring(SpacesVocab.NPASS_NAMESPACE.length());
        if (currentGraphName == null || !currentGraphName.startsWith(trustStateHash + "_")) {
            log.info("AuthorityResolver.tick: trust-state flip detected (now {}); running full build",
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
            log.debug("AuthorityResolver.periodicRebuildTick: no current trust state — deferring");
            return;
        }
        log.info("AuthorityResolver.periodicRebuildTick: needsFullRebuild flag set; rebuilding");
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
                    log.info("AuthorityResolver.cleanOrphans: dropped orphan graph {}", iri);
                }
            }
            if (dropped == 0) {
                log.debug("AuthorityResolver.cleanOrphans: no orphan space-state graphs");
            }
        } catch (Exception ex) {
            log.info("AuthorityResolver.cleanOrphans: failed: {}", ex.toString());
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
        long loadCounter = getCurrentLoadCounter();
        IRI newGraph = SpacesVocab.forSpaceState(trustStateHash, loadCounter);
        IRI oldGraph = getCurrentSpaceStateGraph();
        if (newGraph.equals(oldGraph)) {
            log.debug("AuthorityResolver.runFullBuild: already current at {}", newGraph);
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
        log.info("AuthorityResolver: full build complete — graph={} mirrored={} rows loadCounter={} "
                        + "subjects: adminRIs={} attachmentRAs={} nonAdminRIs={} "
                        + "(inserted-triples: admin={} attachment={} maintainer={} member={} observer={})",
                newGraph, mirrored, loadCounter,
                totals.adminRIs(), totals.attachmentRAs(), totals.nonAdminRIs(),
                counts.admin, counts.attachment, counts.maintainer, counts.member, counts.observer);
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
        long currentLoadCounter = getCurrentLoadCounter();
        long lastProcessed = readProcessedUpTo(graph);
        if (lastProcessed < 0) {
            log.warn("AuthorityResolver.runIncrementalCycle: missing processedUpTo on {}; skipping",
                    graph);
            return;
        }
        if (currentLoadCounter <= lastProcessed) {
            log.debug("AuthorityResolver.runIncrementalCycle: caught up at load {} on {}",
                    currentLoadCounter, graph);
            return;
        }

        boolean structuralInvalidation = applyInvalidations(graph, lastProcessed);
        TierInsertedTriples counts = runAllTierLoops(graph, lastProcessed);
        boolean structuralAdds = (counts.admin > 0)
                || (counts.attachment > 0)
                || newRoleDeclarationsArrived(lastProcessed);
        if (structuralAdds) {
            // Late-arrival sweep: only the leaf tiers (attachment/maintainer/member/observer)
            // can promote candidates whose enabling event arrived in this same cycle. Skip
            // the admin tier — its only enabling event is the admin grant itself, already
            // handled by the regular pass.
            TierInsertedTriples lateCounts = runDownstreamWithoutLoadFilter(graph);
            counts.attachment += lateCounts.attachment;
            counts.maintainer += lateCounts.maintainer;
            counts.member     += lateCounts.member;
            counts.observer   += lateCounts.observer;
        }

        writeProcessedUpTo(graph, currentLoadCounter);

        TierSubjectTotals totals = computeTierSubjectTotals(graph);
        log.info("AuthorityResolver: incremental cycle complete — graph={} delta=({}, {}] "
                        + "subjects: adminRIs={} attachmentRAs={} nonAdminRIs={} "
                        + "(inserted-triples: admin={} attachment={} maintainer={} member={} observer={}) "
                        + "structuralInvalidation={} structuralAdds={}",
                graph, lastProcessed, currentLoadCounter,
                totals.adminRIs(), totals.attachmentRAs(), totals.nonAdminRIs(),
                counts.admin, counts.attachment, counts.maintainer, counts.member, counts.observer,
                structuralInvalidation, structuralAdds);
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
        // Leaf-tier RI deletes — no flag.
        executeUpdate(leafTierInvalidationDelete(graph, lastProcessed));
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
        c.attachment = runTierLabeled("attachment(late)", graph,
                attachmentValidationUpdate(graph, -1));
        c.maintainer = runTierLabeled("maintainer(late)", graph,
                nonAdminTierUpdate(graph, -1, GEN.MAINTAINER_ROLE, PUBLISHER_IS_ADMIN));
        c.member = runTierLabeled("member(admin-pub,late)", graph,
                nonAdminTierUpdate(graph, -1, GEN.MEMBER_ROLE, PUBLISHER_IS_ADMIN));
        c.member += runTierLabeled("member(maint-pub,late)", graph,
                nonAdminTierUpdate(graph, -1,
                        GEN.MEMBER_ROLE, publisherIsTieredRole(GEN.MAINTAINER_ROLE)));
        c.observer = runTierLabeled("observer(self,late)", graph,
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
        int attachment;
        int maintainer;
        int member;
        int observer;
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
        // Observer tier: self-evidence only per the plan's policy table
        // (gen:ObserverRole = self). Authority-publisher sub-tiers were overreach;
        // the three of them have been removed, so an observer instantiation is
        // validated iff the assignee's own pubkey signed it.
        c.observer = runTierLabeled("observer(self)", graph, nonAdminTierUpdate(graph, lastProcessed,
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
        return """
                ?acct a npa:AccountState ;
                      npa:pubkey ?pkh ;
                      npa:agent  ?publisher .
                ?tierRI a gen:RoleInstantiation ;
                        npa:forSpace ?space ;
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
            log.error("AuthorityResolver: tier={} failed with SPARQL UPDATE:\n{}\n", tier, sparqlUpdate, ex);
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
            log.warn("AuthorityResolver: countDistinctSubjects on {} failed: {}",
                    graph, ex.toString());
            return 0;
        }
    }

    // ---------------- SPARQL templates ----------------

    /**
     * Reusable invalidation filter on a bound nanopub-IRI variable. Pass the bare
     * variable name (no leading {@code ?}); e.g. {@code invalidationFilter("np")}
     * produces an outer-scoped {@code FILTER NOT EXISTS { GRAPH npa:spacesGraph
     * { ?_inv_np a npa:Invalidation ; npa:invalidates ?np . } }}.
     *
     * <p>Important: this filter must be placed OUTSIDE the surrounding
     * {@code GRAPH npa:spacesGraph { ... }} block, not nested inside it. When
     * nested, RDF4J's planner couples the FILTER NOT EXISTS evaluation into the
     * join order (per-row scan of {@code ?_inv a npa:Invalidation} multiplied by
     * the candidate set), which we measured turning a 39ms query into a 60s+
     * timeout on the live observer-tier data. Outside the GRAPH block, the
     * planner defers the filter until {@code ?np}/{@code ?rdNp} are bound and
     * does a targeted index lookup.
     *
     * <p>Variable names must match {@code [A-Za-z0-9_]+} per SPARQL grammar —
     * embedding a {@code ?} inside {@code ?_inv_?np} would yield a parse error.
     */
    private static String invalidationFilter(String bareVarName) {
        return "FILTER NOT EXISTS { GRAPH <" + SpacesVocab.SPACES_GRAPH + "> {"
                + " ?_inv_" + bareVarName
                + " a <" + SpacesVocab.INVALIDATION + "> ; "
                + "<" + SpacesVocab.INVALIDATES + "> ?" + bareVarName + " . } }";
    }

    /**
     * Admin tier: seed from {@code npadef:...hasRootAdmin} (trusted by construction)
     * plus closed-over admin grants; insert any {@code gen:RoleInstantiation} with
     * {@code npa:inverseProperty gen:hasAdmin} whose publisher (resolved via mirrored
     * trust-approved AccountState) is already in the admin set.
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
        return """
                PREFIX npa:  <%1$s>
                PREFIX gen:  <%2$s>
                INSERT { GRAPH <%3$s> {
                  ?ri a gen:RoleInstantiation ;
                      npa:forSpace ?space ;
                      npa:inverseProperty gen:hasAdmin ;
                      npa:forAgent ?agent ;
                      npa:viaNanopub ?np .
                } }
                WHERE {
                  # 1. Anchor: who is already an admin of which space?
                  {
                    # Seed branch: root-admin in a non-invalidated SpaceDefinition.
                    GRAPH <%4$s> {
                      ?def a npa:SpaceDefinition ;
                           npa:forSpaceRef  ?spaceRef ;
                           npa:hasRootAdmin ?publisher ;
                           npa:viaNanopub   ?defNp .
                      ?spaceRef npa:spaceIri ?space .
                    }
                    %7$s
                  }
                  UNION
                  {
                    # Closed-over branch: an existing admin in this space-state graph.
                    GRAPH <%3$s> {
                      ?prev a gen:RoleInstantiation ;
                            npa:forSpace        ?space ;
                            npa:inverseProperty gen:hasAdmin ;
                            npa:forAgent        ?publisher .
                    }
                  }
                  # 2. Mirror: resolve ?publisher → ?pkh via the trust-approved row.
                  GRAPH <%3$s> {
                    ?acct a npa:AccountState ;
                          npa:agent  ?publisher ;
                          npa:pubkey ?pkh .
                  }
                  # 3. Targeted instantiation lookup by space + pubkey.
                  GRAPH <%4$s> {
                    ?ri a gen:RoleInstantiation ;
                        npa:forSpace        ?space ;
                        npa:inverseProperty gen:hasAdmin ;
                        npa:forAgent        ?agent ;
                        npa:pubkeyHash      ?pkh ;
                        npa:viaNanopub      ?np .
                  }
                  %6$s
                  # 4. Load-number filter on bound ?np.
                  GRAPH <%8$s> {
                    ?np npa:hasLoadNumber ?ln .
                    FILTER (?ln > %5$d)
                  }
                  # 5. Dedup last.
                  FILTER NOT EXISTS { GRAPH <%3$s> {
                    ?existing a gen:RoleInstantiation ;
                              npa:forSpace ?space ;
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
                invalidationFilter("defNp"),
                NPA.GRAPH);
    }

    /**
     * {@code gen:hasRole} attachment validation: an attachment is validated iff its
     * publisher is already a validated admin of the target space. Adds
     * {@code gen:RoleAssignment} rows to the space-state graph.
     */
    static String attachmentValidationUpdate(IRI graph, long lastProcessed) {
        return """
                PREFIX npa:  <%1$s>
                PREFIX gen:  <%2$s>
                INSERT { GRAPH <%3$s> {
                  ?ra a gen:RoleAssignment ;
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
                    ?adminRI a gen:RoleInstantiation ;
                             npa:forSpace ?space ;
                             npa:inverseProperty gen:hasAdmin ;
                             npa:forAgent ?publisher .
                  }
                  %6$s
                  FILTER NOT EXISTS { GRAPH <%3$s> {
                    ?existing a gen:RoleAssignment ;
                              npa:forSpace ?space ;
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
            ?adminRI a gen:RoleInstantiation ;
                     npa:forSpace ?space ;
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
        //   2. Match the tier-pinned RoleDeclaration by ?role.
        //   3. Pair role-decl direction to instantiation direction in one UNION
        //      so only (reg, reg)/(inv, inv) combos are explored.
        //   4. Targeted instantiation lookup — (?space, ?pred) are bound.
        //   5. Publisher constraint (incl. AccountState resolution).
        //   6. Load-number filter on bound ?np.
        //   7. Dedup at the end.
        return """
                PREFIX npa:  <%1$s>
                PREFIX gen:  <%2$s>
                INSERT { GRAPH <%3$s> {
                  ?ri a gen:RoleInstantiation ;
                      npa:forSpace ?space ;
                      npa:forAgent ?agent ;
                      npa:viaNanopub ?np .
                } }
                WHERE {
                  # 1. Anchor: validated attachments in this space-state graph.
                  GRAPH <%3$s> {
                    ?ra a gen:RoleAssignment ;
                        gen:hasRole  ?role ;
                        npa:forSpace ?space .
                  }
                  # 2. Tier-pinned RoleDeclaration (?role bound from the attachment).
                  GRAPH <%4$s> {
                    ?rd a npa:RoleDeclaration ;
                        npa:hasRoleType <%7$s> ;
                        npa:role        ?role ;
                        npa:viaNanopub  ?rdNp .
                    # 3. Pair direction so only matching combos are explored.
                    {
                      ?rd gen:hasRegularProperty ?pred .
                      ?ri npa:regularProperty    ?pred .
                    }
                    UNION
                    {
                      ?rd gen:hasInverseProperty ?pred .
                      ?ri npa:inverseProperty    ?pred .
                    }
                    # 4. Targeted instantiation lookup — (?space, ?pred) bound.
                    ?ri a gen:RoleInstantiation ;
                        npa:forSpace   ?space ;
                        npa:forAgent   ?agent ;
                        npa:pubkeyHash ?pkh ;
                        npa:viaNanopub ?np .
                  }
                  # 5. Publisher constraint (incl. AccountState resolution).
                  GRAPH <%3$s> {
                    %9$s
                  }
                  # 6. Load-number filter on bound ?np.
                  GRAPH <%10$s> {
                    ?np npa:hasLoadNumber ?ln .
                    FILTER (?ln > %5$d)
                  }
                  # 7. Invalidation filters — outside the GRAPH block so the
                  #    planner defers them until ?rdNp/?np are bound.
                  %8$s
                  %6$s
                  # 8. Dedup last.
                  FILTER NOT EXISTS { GRAPH <%3$s> {
                    ?existing a gen:RoleInstantiation ;
                              npa:forSpace ?space ;
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

    // ---------------- Invalidation templates (incremental cycle) ----------------

    /**
     * WHERE clause shared by the admin-RI invalidation ASK precheck and the
     * matching DELETE. Identifies admin-tier {@code gen:RoleInstantiation} rows
     * in the space-state graph whose {@code npa:viaNanopub} equals the target
     * of an {@code npa:Invalidation} that landed in {@code (lastProcessed, ∞)}.
     */
    static String adminInvalidationCheckWhere(IRI graph, long lastProcessed) {
        return String.format("""
                  GRAPH <%1$s> {
                    ?ri a gen:RoleInstantiation ;
                        npa:inverseProperty gen:hasAdmin ;
                        npa:viaNanopub ?np .
                  }
                  GRAPH <%2$s> {
                    ?inv a npa:Invalidation ;
                         npa:invalidates ?np ;
                         npa:viaNanopub  ?invNp .
                  }
                  GRAPH <%3$s> {
                    ?invNp npa:hasLoadNumber ?ln .
                    FILTER (?ln > %4$d)
                  }
                """, graph, SpacesVocab.SPACES_GRAPH, NPA.GRAPH, lastProcessed);
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
                    ?inv a npa:Invalidation ;
                         npa:invalidates ?np ;
                         npa:viaNanopub  ?invNp .
                  }
                  GRAPH <%3$s> {
                    ?invNp npa:hasLoadNumber ?ln .
                    FILTER (?ln > %4$d)
                  }
                """, graph, SpacesVocab.SPACES_GRAPH, NPA.GRAPH, lastProcessed);
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
                    ?inv a npa:Invalidation ;
                         npa:invalidates ?np ;
                         npa:viaNanopub  ?invNp .
                  }
                  GRAPH <%2$s> {
                    ?invNp npa:hasLoadNumber ?ln .
                    FILTER (?ln > %3$d)
                  }
                """, SpacesVocab.SPACES_GRAPH, NPA.GRAPH, lastProcessed);
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
                    ?inv a npa:Invalidation ;
                         npa:invalidates ?np ;
                         npa:viaNanopub  ?invNp .
                  }
                  GRAPH <%5$s> {
                    ?invNp npa:hasLoadNumber ?ln .
                    FILTER (?ln > %6$d)
                  }
                }
                """, NPA.NAMESPACE, GEN.NAMESPACE, graph,
                SpacesVocab.SPACES_GRAPH, NPA.GRAPH, lastProcessed);
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
                        log.warn("AuthorityResolver.mirror: account {} missing agent or pubkey; skipping",
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
            log.warn("AuthorityResolver: failed to read hasCurrentSpaceState pointer: {}", ex.toString());
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
                log.warn("AuthorityResolver: non-numeric currentLoadCounter: {}", v);
                return 0;
            }
        } catch (Exception ex) {
            log.warn("AuthorityResolver: failed to read currentLoadCounter: {}", ex.toString());
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
            log.warn("AuthorityResolver: failed to read processedUpTo for {}: {}", graph, ex.toString());
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
            log.warn("AuthorityResolver: failed to read needsFullRebuild: {}", ex.toString());
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
            log.info("AuthorityResolver: dropped old space-state graph {}", graph);
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
            log.warn("AuthorityResolver: failed to read trust-repo current pointer: {}", ex.toString());
            return Optional.empty();
        }
    }

    private static String abbrev(String hash) {
        return hash.length() > 12 ? hash.substring(0, 12) + "…" : hash;
    }

}
