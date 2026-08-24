package com.knowledgepixels.query;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
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

import com.google.common.hash.Hashing;
import com.knowledgepixels.query.vocabulary.GEN;
import com.knowledgepixels.query.vocabulary.NPAA;
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
 * non-admin RI) → trust mirror-step delta is implicit (rebuilt only on full build) →
 * pending-account mirror delta (issue #195, bounded by its own meta-repo watermark) →
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
    /** Source of introduction-nanopub meta triples for the pending-account mirror (issue #195). */
    private static final String META_REPO = "meta";

    /** NPA constants pulled in locally (trust-side). */
    private static final IRI NPA_HAS_CURRENT_TRUST_STATE =
            vf.createIRI(NPA.NAMESPACE, "hasCurrentTrustState");
    private static final IRI NPA_ACCOUNT_STATE = vf.createIRI(NPA.NAMESPACE, "AccountState");
    private static final IRI NPA_AGENT = vf.createIRI(NPA.NAMESPACE, "agent");
    private static final IRI NPA_PUBKEY = vf.createIRI(NPA.NAMESPACE, "pubkey");
    private static final IRI NPA_TRUST_STATUS = vf.createIRI(NPA.NAMESPACE, "trustStatus");
    private static final IRI NPA_VIA_NANOPUB = vf.createIRI(NPA.NAMESPACE, "viaNanopub");
    private static final IRI NPA_LOADED = vf.createIRI(NPA.NAMESPACE, "loaded");
    private static final IRI NPA_TO_LOAD = vf.createIRI(NPA.NAMESPACE, "toLoad");

    /**
     * Class of the introduced-but-unapproved account rows written by
     * {@link #mirrorPendingAccounts} (issue #195).
     *
     * <p>Deliberately <em>not</em> {@code npa:AccountState}. An {@code AccountState}
     * row is the pubkey&rarr;agent identity binding every authority join in this class
     * resolves through ({@code PUBLISHER_IS_ADMIN}, {@link #publisherIsTieredRole},
     * {@link #adminTierUpdate}, the {@code revoker*} blocks), and those joins pair it
     * with a {@code RoleInstantiation} keyed on the <em>agent</em>. Since an
     * introduction is self-asserted, emitting these rows as bare {@code AccountState}
     * would let anyone bind their own key to any agent that already holds authority
     * without having an approved account — 43 such agents existed on the production
     * fleet when this was written, three of them admins. The distinct class fails
     * closed for every existing join site, and for published queries that cannot be
     * retrofitted.
     */
    private static final IRI NPA_PENDING_ACCOUNT_STATE =
            vf.createIRI(NPA.NAMESPACE, "PendingAccountState");
    /** Trust status stamped on pending rows and on the roles materialized through them. */
    private static final IRI NPA_SEEN = vf.createIRI(NPA.NAMESPACE, "seen");

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

    /**
     * Raised when the space-state bookkeeping in the {@code spaces} repo cannot be
     * <em>read</em>, as opposed to being legitimately absent.
     *
     * <p>The distinction is the whole point. Before 2026-08-05 every reader here
     * collapsed both cases onto the same value — {@code null} pointer, load counter
     * {@code 0}, {@code processedUpTo} {@code -1} — so a degraded RDF4J looked
     * identical to a fresh install. On that day RDF4J was answering reads with
     * {@code Read timed out}; {@link #tick()} saw a {@code null} pointer, logged a
     * "trust-state flip" that had not happened, and ran a full build whose every
     * source read also failed. The build inserted nothing, published the resulting
     * empty graph, and dropped the previous good one — 2730 triples of live space
     * state, on a query server that then served zero rows to every state query for
     * hours. A sibling instance that stayed healthy still had all of it.
     *
     * <p>Throwing instead lets the caller abort. Doing nothing this tick is always
     * safe; acting on a failed read is not.
     */
    static class SpaceStateUnavailableException extends RuntimeException {
        SpaceStateUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
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
            logger.debug("AuthorityResolver.tick: no current trust state yet — skipping");
            return;
        }
        // Any of the reads below may throw SpaceStateUnavailableException. Let it
        // propagate: the caller logs "AuthorityResolver tick failed" and we retry on
        // the next tick with the state untouched.
        IRI currentGraph = getCurrentSpaceStateGraph();
        String currentGraphName = (currentGraph == null) ? null
                : currentGraph.stringValue().substring(SpacesVocab.NPASS_NAMESPACE.length());
        if (currentGraphName == null) {
            logger.info("AuthorityResolver.tick: no current space-state graph; running full build");
            runFullBuild(trustStateHash);
            return;
        }
        if (!currentGraphName.startsWith(trustStateHash + "_")) {
            logger.info("AuthorityResolver.tick: trust-state flip detected (now {}); running full build",
                    abbrev(trustStateHash));
            runFullBuild(trustStateHash);
            return;
        }
        // A pointer at a graph that never got its processedUpTo stamp means the build
        // that published it did not finish. runIncrementalCycle used to log "missing
        // processedUpTo; skipping" and return — every 2 s, forever, with every
        // state-backed query answering empty in the meantime. Rebuild instead.
        if (readProcessedUpTo(currentGraph) < 0) {
            logger.warn("AuthorityResolver.tick: current space-state graph {} has no processedUpTo "
                    + "stamp (incomplete or damaged build); running full build", currentGraph);
            runFullBuild(trustStateHash);
            return;
        }
        // Integrity check: the stateTripleCount stamp is rewritten by every mutation,
        // so a disagreement means part of a write was lost after the fact — e.g. rdf4j
        // dropping acked-but-unmerged changesets across a restart (2026-08-22: a state
        // graph survived with 7,439 of 19,283 triples, processedUpTo intact, and served
        // truncated authority data until repaired by hand). A stamp of -1 is a graph
        // published by a pre-stamp version: skip, it becomes verifiable at its next
        // mutation.
        long expectedCount = readStateTripleCount(currentGraph);
        if (expectedCount >= 0) {
            long actualCount = countStateGraphTriples(currentGraph);
            if (actualCount != expectedCount) {
                logger.warn("AuthorityResolver.tick: current space-state graph {} holds {} triples "
                        + "but its stateTripleCount stamp says {} — truncated or partially lost "
                        + "state; running full build", currentGraph, actualCount, expectedCount);
                runFullBuild(trustStateHash);
                return;
            }
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
    public synchronized void cleanOrphans() {
        if (!FeatureFlags.spacesEnabled()) return;
        IRI current;
        try {
            current = getCurrentSpaceStateGraph();
        } catch (SpaceStateUnavailableException ex) {
            // Every npass:* graph is "not the current one" when the pointer cannot be
            // read, so continuing here would drop the live state along with the
            // orphans. Skipping costs nothing: orphans are inert, and the next start
            // will clean them up.
            logger.warn("AuthorityResolver.cleanOrphans: cannot read the current-state pointer, "
                    + "skipping so orphan cleanup cannot delete the live graph: {}", ex.toString());
            return;
        }
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
                    conn.begin(IsolationLevels.SNAPSHOT);
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
        boolean rebuildInPlace = newGraph.equals(oldGraph);
        if (rebuildInPlace) {
            // "Already current" is only true if that graph was actually finished AND
            // still holds everything it claims to. Without the processedUpTo check
            // this early return was the second half of the 2026-08-05 trap: once a
            // damaged graph was published, the pointer name still matched, so every
            // subsequent full build returned here and the instance could never repair
            // itself. The integrity-count check closes the same loophole for the
            // 2026-08-22 shape (graph truncated after publication, stamps intact) —
            // without it, tick() would detect the mismatch, call this method, and be
            // bounced right back here forever.
            long expected = readStateTripleCount(oldGraph);
            boolean countConsistent = expected < 0 || expected == countStateGraphTriples(oldGraph);
            if (readProcessedUpTo(oldGraph) >= 0 && countConsistent) {
                logger.debug("AuthorityResolver.runFullBuild: already current at {}", newGraph);
                return;
            }
            logger.warn("AuthorityResolver.runFullBuild: {} is the current graph but is "
                    + "unfinished or inconsistent (processedUpTo={}, countConsistent={}); "
                    + "rebuilding it in place", newGraph, readProcessedUpTo(oldGraph), countConsistent);
            dropGraph(newGraph);
        }

        // 1. Mirror trust-approved rows into the new graph.
        int mirrored = mirrorTrustState(trustStateHash, newGraph);

        // 1b. Mirror introduced-but-unapproved accounts (issue #195). Runs before the
        //     tier loops so this build's observer(self,pending) pass sees the rows.
        //     Additive and authority-free, so it is deliberately not part of the
        //     empty-build guard below.
        int pendingMirrored = mirrorPendingAccountsSafely(newGraph, /*fromScratch=*/ true);

        // 2. Per-tier UPDATE loops (from scratch: lastProcessed = -1 so the
        //    delta filter FILTER(?ln > ?lastProcessed) includes everything).
        TierInsertedTriples counts = runAllTierLoops(newGraph, -1);

        // 2b. Refuse to publish an empty build over a state we already have — but only
        // when the emptiness cannot be true.
        //
        // Steps 4 and 5 below are destructive, so a build that read nothing must not
        // reach them. The trap is that "produced nothing" has two causes: every source
        // read failed, or the sources really are empty. Refusing in the second case
        // would pin a stale space state forever, and stale trust data is
        // over-permissive — revocations would stop propagating. That is the wrong way
        // to fail for a trust-derived state.
        //
        // So the condition is: nothing was produced *while the trust state still has
        // content to mirror*. That is the shape of a read failure. A genuinely empty
        // trust state yields an empty build and is published normally.
        //
        // Only guarded when a previous state exists: a genuinely empty first build on
        // a fresh instance has nothing to lose and must still be allowed to publish.
        //
        // Note this would NOT have fired on 2026-08-05: that build reported
        // subspace-prefix=2478, so it was not empty. The wipe there came from the
        // registry's trust state collapsing (correctly reflected) plus 2478 triples
        // that were reported inserted and then measured as zero. This guard is for the
        // total-read-failure case, which the same outage came close to several times.
        long insertedTotal = totalInserted(counts);
        if (mirrored == 0 && insertedTotal == 0 && oldGraph != null
                && trustStateHasContent(trustStateHash)) {
            logger.error("AuthorityResolver.runFullBuild: build produced an empty state graph "
                    + "(mirrored=0, inserted=0) while trust state {} still has content and {} "
                    + "holds the current state — refusing to flip the pointer or drop it. "
                    + "This is the shape of a total read failure; the next tick will retry.",
                    abbrev(trustStateHash), oldGraph);
            if (!rebuildInPlace) {
                dropGraph(newGraph);
            }
            return;
        }

        // 3. Stamp processedUpTo inside the new graph.
        writeProcessedUpTo(newGraph, loadCounter);

        // 3b. Stamp the integrity triple-count, so tick() can detect a graph
        //     that later loses part of its content (truncated writes, dropped
        //     changesets across a store restart) and rebuild it automatically.
        writeStateTripleCount(newGraph);

        // 4. Flip the current-space-state pointer.
        flipPointer(newGraph);

        // 5. Drop the old graph if a *different* one existed. Dropping it when
        //    rebuilding in place would delete what we just built.
        if (oldGraph != null && !rebuildInPlace) {
            dropGraph(oldGraph);
        }

        TierSubjectTotals totals = computeTierSubjectTotals(newGraph);
        long durationMs = (System.nanoTime() - startNanos) / 1_000_000L;
        lastSubjectTotals = totals;
        lastInsertedTriplesTotal = insertedTotal;
        lastFullBuildDurationMs = durationMs;
        lastProcessedUpToLag = 0L;
        logger.info("AuthorityResolver: full build complete — graph={} mirrored={} rows pending={} rows loadCounter={} "
                        + "subjects: adminRIs={} attachmentRAs={} nonAdminRIs={} "
                        + "(inserted-triples: admin={} alias={} preset-attachment={} preset-assignment-ref={} attachment={} maintainer={} member={} observer={} "
                        + "subspace={} subspace-prefix={} maintained-resource={} governing-space-ref={}) durationMs={}",
                newGraph, mirrored, pendingMirrored, loadCounter,
                totals.adminRIs(), totals.attachmentRAs(), totals.nonAdminRIs(),
                counts.admin, counts.alias, counts.presetAttachment, counts.presetAssignmentRef, counts.attachment, counts.maintainer, counts.member, counts.observer,
                counts.subSpace, counts.subSpacePrefix, counts.maintainedResource, counts.governingSpaceRef,
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
            // tick() now catches this first and rebuilds, so reaching here means a
            // direct caller. Still refuse to run a delta against a graph that was
            // never finished — the deltas would be layered onto missing base rows.
            logger.warn("AuthorityResolver.runIncrementalCycle: missing processedUpTo on {}; "
                    + "skipping (a full build is needed to repair this graph)", graph);
            return;
        }
        lastProcessedUpToLag = currentLoadCounter - lastProcessed;
        if (currentLoadCounter <= lastProcessed) {
            logger.debug("AuthorityResolver.runIncrementalCycle: caught up at load {} on {}",
                    currentLoadCounter, graph);
            return;
        }

        boolean structuralInvalidation = applyInvalidations(graph, lastProcessed);
        // Pending-account delta (issue #195), before the tier loops so a newcomer's
        // introduction and their self-signed role can land in the same cycle. Keyed on
        // the meta repo's own load numbers, hence its own watermark.
        int pendingMirrored = mirrorPendingAccountsSafely(graph, /*fromScratch=*/ false);
        TierInsertedTriples counts = runAllTierLoops(graph, lastProcessed);
        boolean structuralAdds = (pendingMirrored > 0)
                || (counts.admin > 0)
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
            counts.governingSpaceRef  += lateCounts.governingSpaceRef;
            counts.maintainedResource += lateCounts.maintainedResource;
        }

        writeProcessedUpTo(graph, currentLoadCounter);
        // Re-stamp the integrity count after this cycle's mutations (also
        // upgrades pre-stamp graphs to verifiable on their first mutation).
        writeStateTripleCount(graph);

        TierSubjectTotals totals = computeTierSubjectTotals(graph);
        long durationMs = (System.nanoTime() - startNanos) / 1_000_000L;
        lastSubjectTotals = totals;
        lastInsertedTriplesTotal = (long) counts.admin + counts.alias + counts.presetAttachment
                + counts.presetAssignmentRef
                + counts.attachment + counts.maintainer + counts.member + counts.observer
                + counts.subSpace + counts.subSpacePrefix + counts.maintainedResource
                + counts.governingSpaceRef;
        lastIncrementalCycleDurationMs = durationMs;
        logger.info("AuthorityResolver: incremental cycle complete — graph={} delta=({}, {}] pending={} rows "
                        + "subjects: adminRIs={} attachmentRAs={} nonAdminRIs={} "
                        + "(inserted-triples: admin={} alias={} preset-attachment={} preset-assignment-ref={} attachment={} maintainer={} member={} observer={} "
                        + "subspace={} subspace-prefix={} maintained-resource={} governing-space-ref={}) "
                        + "structuralInvalidation={} structuralAdds={} durationMs={}",
                graph, lastProcessed, currentLoadCounter, pendingMirrored,
                totals.adminRIs(), totals.attachmentRAs(), totals.nonAdminRIs(),
                counts.admin, counts.alias, counts.presetAttachment, counts.presetAssignmentRef, counts.attachment, counts.maintainer, counts.member, counts.observer,
                counts.subSpace, counts.subSpacePrefix, counts.maintainedResource, counts.governingSpaceRef,
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
        // Role-declaration invalidation is deliberately NOT acted on (see
        // nonAdminTierUpdate): a role assignment is governed by the admin-validated
        // attachment, not by the declaration author's later supersession/retraction, so
        // an invalidated RD neither deletes rows nor triggers a rebuild.
        // Sub-space declarations are structural — invalidating one (Mode A) or one
        // of two co-declarations (Mode B) changes the validated parent/child
        // topology. The DELETE removes the per-declaration row; the convenience-edge
        // cleanup then drops the now-unbacked direct triples (issue #125 finding #5)
        // instead of leaving them sticky until the periodic rebuild. The structural
        // flag still fires so downstream rows derived through a removed edge stay
        // rebuild-bounded.
        if (wouldInvalidate(graph, lastProcessed, /*adminPinned=*/ false,
                            subSpaceInvalidationCheckWhere(graph, lastProcessed))) {
            executeUpdate(subSpaceInvalidationDelete(graph, lastProcessed));
            executeUpdate(subSpaceConvenienceEdgeCleanup(graph, lastProcessed));
            structural = true;
        }
        // Space-alias declarations are structural — invalidating one removes an
        // owl:sameAs edge that feeds the admin-authority closure (issue #113). The
        // DELETE removes the per-declaration row; the convenience-edge cleanup then
        // drops the now-unbacked npa:sameAsSpace edge (issue #125 finding #5 — the
        // load-bearing case), so admin authority can no longer outlive a retraction
        // until the next periodic rebuild.
        if (wouldInvalidate(graph, lastProcessed, /*adminPinned=*/ false,
                            aliasInvalidationCheckWhere(graph, lastProcessed))) {
            executeUpdate(aliasInvalidationDelete(graph, lastProcessed));
            executeUpdate(aliasConvenienceEdgeCleanup(graph, lastProcessed));
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
        // Admin role-instantiation revocation (issue #129). STRUCTURAL — admin RIs feed every
        // downstream tier, so a removed admin must bound the staleness via a full rebuild
        // (mirrors adminInvalidationDelete). Root admins are exempt inside the check-where.
        if (wouldInvalidate(graph, lastProcessed, /*adminPinned=*/ true,
                            adminRevocationCheckWhere(graph, lastProcessed))) {
            executeUpdate(adminRevocationDelete(graph, lastProcessed));
            structural = true;
        }
        // Role detachment (issue #129). STRUCTURAL — removing a (ref, role) attachment
        // (direct or preset-derived) cascades to the instantiations anchored on it, bounded
        // by the periodic full rebuild. The attachment-tier inline filters then keep the
        // detached role suppressed until a newer attachment / preset assignment out-ranks it.
        if (wouldInvalidate(graph, lastProcessed, /*adminPinned=*/ false,
                            roleDetachmentCheckWhere(graph, lastProcessed))) {
            executeUpdate(roleDetachmentDelete(graph, lastProcessed));
            structural = true;
        }
        // Non-admin role-instantiation revocation (issue #129), run once per tier so the
        // authorization arms are the compile-time set for that tier (mirrors the inline
        // suppression filter). STRUCTURAL: a revoked maintainer or member is a sub-granting
        // authority — members/observers they granted are validated via the maint-pub /
        // member-pub arms of nonAdminTierUpdate, so removing the revoked agent's own RI must
        // schedule a full rebuild to re-evaluate (and drop) those now-unauthorized downstream
        // grants. The inline suppression filter prevents re-materialization on that rebuild.
        for (IRI revTier : List.of(GEN.MAINTAINER_ROLE, GEN.MEMBER_ROLE, GEN.OBSERVER_ROLE)) {
            if (wouldInvalidate(graph, lastProcessed, /*adminPinned=*/ false,
                                roleRevocationCheckWhere(graph, lastProcessed, revTier))) {
                executeUpdate(roleRevocationDelete(graph, lastProcessed, revTier));
                structural = true;
            }
        }
        // Leaf-tier RI deletes — no flag.
        executeUpdate(leafTierInvalidationDelete(graph, lastProcessed));
        // Ref-scoped preset-assignment listing stamps whose assignment nanopub was
        // hard-retracted (issue #122) — no flag (display leaf, nothing downstream).
        executeUpdate(presetAssignmentRefInvalidationDelete(graph, lastProcessed));
        // Maintained-resource declaration deletes — no flag (leaf relation, no
        // downstream caches to bound). The per-declaration delete removes the row; the
        // convenience-edge cleanup drops the now-unbacked isMaintainedBy edges (issue
        // #125 finding #5). Guarded so the orphan-sweep only scans when something was
        // actually invalidated (the delete itself was already a no-op otherwise).
        if (wouldInvalidate(graph, lastProcessed, /*adminPinned=*/ false,
                            maintainedResourceInvalidationCheckWhere(graph, lastProcessed))) {
            executeUpdate(maintainedResourceInvalidationDelete(graph, lastProcessed));
            executeUpdate(maintainedResourceConvenienceEdgeCleanup(graph, lastProcessed));
        }
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
        // Reflexive governing-space-ref late sweep (issue #130): catches refs whose
        // SpaceRef aggregate became visible only this cycle. Self-healing dedup.
        c.governingSpaceRef = runTierLabeled("governing-space-ref(late)", graph,
                governingSpaceRefReflexiveUpdate(graph));
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
        c.observer += runTierLabeled("observer(self,pending,late)", graph,
                nonAdminTierUpdate(graph, -1, GEN.OBSERVER_ROLE,
                        PUBLISHER_IS_SELF_PENDING, PENDING_ROLE_STAMP));
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
    /**
     * Total triples inserted across every tier. Used both for the metrics gauge and
     * for the empty-build guard in {@link #runFullBuild}, so the two can never
     * disagree about what "this build produced nothing" means.
     */
    static long totalInserted(TierInsertedTriples c) {
        return (long) c.admin + c.alias + c.presetAttachment + c.presetAssignmentRef
                + c.attachment + c.maintainer + c.member + c.observer
                + c.subSpace + c.subSpacePrefix + c.maintainedResource
                + c.governingSpaceRef;
    }

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
        int governingSpaceRef;
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
        // Reflexive governing-space-ref edges (issue #130). Self-healing, no load filter;
        // runs after the maintained-resource admit so a maintained resource that is itself
        // a space already has its maintained governing edge by now (the two are independent
        // anyway — different subjects/objects). Order vs. other tiers doesn't matter.
        c.governingSpaceRef = runTierLabeled("governing-space-ref", graph,
                governingSpaceRefReflexiveUpdate(graph));
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
        // Self-attestation by a not-yet-approved account (issue #195). Runs after the
        // approved pass; the two are disjoint by construction (a pending row only exists
        // for an agent with no approved row) and the tier's dedup covers the rest.
        c.observer += runTierLabeled("observer(self,pending)", graph, nonAdminTierUpdate(graph, lastProcessed,
                GEN.OBSERVER_ROLE, PUBLISHER_IS_SELF_PENDING, PENDING_ROLE_STAMP));
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

    // ---------------- Role revocation / detachment (issue #129) ----------------

    /**
     * {@code xsd:dateTime} epoch literal — the latest-wins fallback for any assertion that
     * lacks {@code dct:created}. Per issue #129's "treat missing as epoch": a positive
     * assertion without a timestamp sorts oldest (always loses), and a negative
     * (revocation / detachment) without one is inert (can never out-rank a timestamped
     * positive). Written as a full datatype IRI since the tier templates only declare
     * {@code npa:} / {@code gen:}.
     */
    private static final String EPOCH_DT =
            "\"1970-01-01T00:00:00.000Z\"^^<http://www.w3.org/2001/XMLSchema#dateTime>";

    /** Inner {@code GRAPH} block matching a revoker who is a validated admin of {@code ?spaceRef}. */
    private static String revokerAdminGraphBlock(IRI graph) {
        return String.format("""
                GRAPH <%1$s> {
                  ?revAcct a npa:AccountState ; npa:pubkey ?revPkh ; npa:agent ?revAgent .
                  ?revRI a gen:RoleInstantiation ;
                         npa:forSpaceRef ?spaceRef ;
                         npa:inverseProperty gen:hasAdmin ;
                         npa:forAgent ?revAgent .
                }""", graph);
    }

    /** Inner {@code GRAPH} block matching a revoker who holds {@code tier} in {@code ?spaceRef}. */
    private static String revokerTierGraphBlock(IRI graph, IRI tier) {
        return String.format("""
                GRAPH <%1$s> {
                  ?revAcct a npa:AccountState ; npa:pubkey ?revPkh ; npa:agent ?revAgent .
                  ?revRI a gen:RoleInstantiation ;
                         npa:forSpaceRef ?spaceRef ;
                         npa:forAgent ?revAgent ;
                         npa:hasRoleType <%2$s> .
                }""", graph, tier);
    }

    /** Inner {@code GRAPH} block matching a self-revoke: the revoker's key belongs to {@code ?agent}. */
    private static String revokerSelfGraphBlock(IRI graph) {
        return String.format("""
                GRAPH <%1$s> {
                  ?revAcct a npa:AccountState ; npa:pubkey ?revPkh ; npa:agent ?agent .
                }""", graph);
    }

    /**
     * Authorization arms for an instantiation revocation targeting a <em>compile-time</em>
     * tier — issue #129's matrix, the single arm builder used by BOTH the inline suppression
     * filter and the (per-tier-scoped) displacement DELETE, so the two paths can never
     * authorize different revokers and flip-flop a row. UNION of only the arms the matrix
     * permits for {@code targetTier}: admin of {@code ?spaceRef} (revokes any non-admin); a
     * maintainer (member/observer targets); a member (observer target); plus the assignee
     * itself (self-leave, any tier). A revoker must hold a tier strictly higher than the
     * target. Compile-time selection (no runtime {@code ?tier} variable) deliberately avoids
     * the SPARQL pitfall where a {@code FILTER} inside a {@code UNION} branch cannot see a
     * {@code ?tier} bound in the enclosing group.
     */
    private static String revocationAuthorityArmsForTier(IRI graph, IRI targetTier) {
        List<String> arms = new ArrayList<>();
        arms.add("{ " + revokerAdminGraphBlock(graph) + " }");
        if (GEN.MEMBER_ROLE.equals(targetTier) || GEN.OBSERVER_ROLE.equals(targetTier)) {
            arms.add("{ " + revokerTierGraphBlock(graph, GEN.MAINTAINER_ROLE) + " }");
        }
        if (GEN.OBSERVER_ROLE.equals(targetTier)) {
            arms.add("{ " + revokerTierGraphBlock(graph, GEN.MEMBER_ROLE) + " }");
        }
        arms.add("{ " + revokerSelfGraphBlock(graph) + " }");
        return String.join("\nUNION\n", arms);
    }

    /**
     * Inline suppression filter for {@code nonAdminTierUpdate}: rejects a candidate
     * instantiation ({@code ?ri}, created {@code ?candCreated}) whose {@code (space, agent,
     * role)} key has a newer authorized {@code npa:RoleRevocation}, using
     * {@link #revocationAuthorityArmsForTier} for {@code targetTier} (the loop tier) — the same
     * builder the displacement DELETE uses, so suppression and re-materialization always agree.
     * The revocation's named space is matched against any IRI denoting {@code ?spaceRef}
     * (canonical or validated {@code owl:sameAs} alias, issue #113), so an alias-named
     * revocation is not a silent no-op. Latest-wins by {@code dct:created} ({@link #EPOCH_DT}
     * fallback) with an {@code STR()} subject tiebreak. Not wrapped in {@code invalidationFilter}:
     * per issue #129 the only un-revoke path is a newer positive re-assignment.
     */
    private static String nonAdminRevocationSuppressionFilter(IRI graph, IRI targetTier) {
        return String.format("""
                FILTER NOT EXISTS {
                  { GRAPH <%2$s> { ?spaceRef npa:spaceIri ?revSpace . } }
                  UNION
                  { GRAPH <%1$s> { ?revSpace npa:sameAsSpace ?spaceRef . } }
                  GRAPH <%2$s> {
                    ?rev a npa:RoleRevocation ;
                         npa:forSpace    ?revSpace ;
                         npa:forAgent    ?agent ;
                         npa:revokedRole ?role ;
                         npa:pubkeyHash  ?revPkh .
                    OPTIONAL { ?rev <http://purl.org/dc/terms/created> ?revCreatedRaw . }
                  }
                  BIND(COALESCE(?revCreatedRaw, %4$s) AS ?revCreated)
                  FILTER (?revCreated > ?candCreated
                          || (?revCreated = ?candCreated && STR(?rev) > STR(?ri)))
                  { %3$s }
                }""", graph, SpacesVocab.SPACES_GRAPH,
                revocationAuthorityArmsForTier(graph, targetTier), EPOCH_DT);
    }

    /**
     * Inline suppression filter for {@code adminTierUpdate}: rejects an admin instantiation
     * ({@code ?ri}, created {@code ?candCreated}) whose {@code (ref, agent)} key has a newer
     * authorized admin {@code npa:RoleRevocation} ({@code revokedRole = gen:AdminRole}) —
     * authorized by an admin of the ref (admins revoke admins) or by the agent itself
     * (self-leave). <b>Root admins are exempt</b> (issue #129/#110): a nested
     * {@code FILTER NOT EXISTS} on {@code npa:hasRootAdmin} makes any revocation against a
     * root admin structurally inert, overriding self-leave. {@code gen:AdminRole} resolves
     * via the {@code gen:} prefix the admin-tier template declares.
     */
    private static String adminRevocationSuppressionFilter(IRI graph) {
        return String.format("""
                FILTER NOT EXISTS {
                  FILTER NOT EXISTS { GRAPH <%2$s> {
                    ?rootDef a npa:SpaceDefinition ;
                             npa:forSpaceRef  ?spaceRef ;
                             npa:hasRootAdmin ?agent .
                  } }
                  { GRAPH <%2$s> { ?spaceRef npa:spaceIri ?revSpace . } }
                  UNION
                  { GRAPH <%1$s> { ?revSpace npa:sameAsSpace ?spaceRef . } }
                  GRAPH <%2$s> {
                    ?rev a npa:RoleRevocation ;
                         npa:forSpace    ?revSpace ;
                         npa:forAgent    ?agent ;
                         npa:revokedRole gen:AdminRole ;
                         npa:pubkeyHash  ?revPkh .
                    OPTIONAL { ?rev <http://purl.org/dc/terms/created> ?revCreatedRaw . }
                  }
                  BIND(COALESCE(?revCreatedRaw, %4$s) AS ?revCreated)
                  FILTER (?revCreated > ?candCreated
                          || (?revCreated = ?candCreated && STR(?rev) > STR(?ri)))
                  { %3$s }
                }""", graph, SpacesVocab.SPACES_GRAPH,
                "{ " + revokerAdminGraphBlock(graph) + " }\nUNION\n{ "
                        + revokerSelfGraphBlock(graph) + " }",
                EPOCH_DT);
    }

    /**
     * Inline suppression filter for the attachment tiers ({@code attachmentValidationUpdate}
     * and {@code presetAttachmentValidationUpdate}): rejects a {@code (targetRef, role)}
     * attachment whose effective timestamp ({@code ?<createdVar>}) is out-ranked by a newer
     * admin-authored {@code npa:RoleDetachment} (issue #129). Authority = admin of
     * {@code ?targetRef} (matching who may attach). Non-sticky latest-wins: a newer
     * attachment / preset assignment naturally re-attaches because its timestamp beats the
     * detachment. The detachment's named space is matched against any IRI denoting
     * {@code ?targetRef} (canonical or {@code owl:sameAs} alias).
     *
     * @param createdVar     bare name of the attachment's effective-created variable
     * @param attachSubjVar  bare name of the attachment subject variable (for the STR tiebreak)
     */
    private static String roleDetachmentSuppressionFilter(IRI graph, String createdVar, String attachSubjVar) {
        return String.format("""
                FILTER NOT EXISTS {
                  { GRAPH <%2$s> { ?targetRef npa:spaceIri ?detSpace . } }
                  UNION
                  { GRAPH <%1$s> { ?detSpace npa:sameAsSpace ?targetRef . } }
                  GRAPH <%2$s> {
                    ?det a npa:RoleDetachment ;
                         npa:forSpace    ?detSpace ;
                         npa:revokedRole ?role ;
                         npa:pubkeyHash  ?detPkh .
                    OPTIONAL { ?det <http://purl.org/dc/terms/created> ?detCreatedRaw . }
                  }
                  BIND(COALESCE(?detCreatedRaw, %5$s) AS ?detCreated)
                  FILTER (?detCreated > ?%3$s
                          || (?detCreated = ?%3$s && STR(?det) > STR(?%4$s)))
                  GRAPH <%1$s> {
                    ?detAcct a npa:AccountState ; npa:pubkey ?detPkh ; npa:agent ?detAgent .
                    ?detAdminRI a gen:RoleInstantiation ;
                                npa:forSpaceRef ?targetRef ;
                                npa:inverseProperty gen:hasAdmin ;
                                npa:forAgent ?detAgent .
                  }
                }""", graph, SpacesVocab.SPACES_GRAPH, createdVar, attachSubjVar, EPOCH_DT);
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
                + " <" + NPX.INVALIDATES + "> ?" + bareVarName + " . "
                + samePublisherClause("_inv_" + bareVarName, bareVarName)
                + " } }";
    }

    /**
     * SPARQL triple pair (placed inside a {@code GRAPH npa:graph { ... }} block)
     * requiring the invalidating nanopub and its target to share a signing public
     * key — the self-retraction authority gate for issue #112. Without it, the
     * materializer honors {@code npx:invalidates}/{@code retracts}/{@code supersedes}
     * from <em>any</em> validly-signed nanopub, so any agent can erase another
     * space's materialized state (griefing/DoS of the view — fail-closed, no
     * privilege escalation, but real). Additions are already admin-gated; this is
     * the symmetric gate on removals.
     *
     * <p>Both {@code npa:hasValidSignatureForPublicKeyHash} triples live in
     * {@code npa:graph} of the spaces repo: the target via its own space-load, the
     * invalidator via the symmetric retractor propagation in
     * {@link com.knowledgepixels.query.NanopubLoader} (forward {@code
     * loadInvalidateStatements} + reverse {@code loadInvalidatorIntoSpacesRepo}),
     * so the join is populated regardless of load order.
     *
     * <p>"Same pubkey" is intentionally stricter than "same agent": a retraction
     * signed by a different key the author owns (key rotation) is not honored, and
     * cross-admin supersession is out of scope here (would need an admin-authority
     * arm). The pubkey-bridge variable is suffixed with {@code targetVar} so two
     * filters in one query (e.g. on {@code ?np} and {@code ?rdNp}) don't collide.
     *
     * @param invVar    invalidator nanopub variable name (no leading {@code ?})
     * @param targetVar invalidated-target nanopub variable name (no leading {@code ?})
     */
    private static String samePublisherClause(String invVar, String targetVar) {
        String pk = "?_invpk_" + targetVar;
        return "?" + invVar + " <" + NPA.HAS_VALID_SIGNATURE_FOR_PUBLIC_KEY_HASH + "> " + pk + " . "
                + "?" + targetVar + " <" + NPA.HAS_VALID_SIGNATURE_FOR_PUBLIC_KEY_HASH + "> " + pk + " .";
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
                       # Stamp the admin tier so consumers read tier uniformly across all
                       # RoleInstantiations (?ri npa:hasRoleType ?tier) with no admin
                       # special-case — matching the non-admin path (issue #125, #127).
                       npa:hasRoleType gen:AdminRole ;
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
                    # Candidate grant timestamp for the admin-revocation latest-wins (#129).
                    OPTIONAL { ?ri <http://purl.org/dc/terms/created> ?candCreatedRaw . }
                  }
                  BIND(COALESCE(?candCreatedRaw, %9$s) AS ?candCreated)
                  # 3a. Mint the per-ref state subject: (?ri, ?spaceRef) → ?sri.
                  BIND(IRI(CONCAT(STR(?ri), "__", ENCODE_FOR_URI(STR(?spaceRef)))) AS ?sri)
                  %6$s
                  # 4. Load-number filter on bound ?np.
                  GRAPH <%8$s> {
                    ?np npa:hasLoadNumber ?ln .
                    FILTER (?ln > %5$d)
                  }
                  # 4a. Admin-revocation latest-wins (issue #129): suppress if a newer
                  #     authorized admin revocation shadows (ref, agent) — unless ?agent is a
                  #     root admin (constitutional exemption, overrides self-leave).
                  %10$s
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
                NPA.GRAPH,
                EPOCH_DT,
                adminRevocationSuppressionFilter(graph));
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
                    # Attachment timestamp for the detachment latest-wins (issue #129).
                    OPTIONAL { ?ra <http://purl.org/dc/terms/created> ?attCreatedRaw . }
                  }
                  BIND(COALESCE(?attCreatedRaw, %8$s) AS ?attCreated)
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
                  # Detachment latest-wins (issue #129): suppress if a newer admin-authored
                  # gen:detachedRole out-ranks this (ref, role) attachment.
                  %9$s
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
                NPA.GRAPH,
                EPOCH_DT,
                roleDetachmentSuppressionFilter(graph, "attCreated", "ra"));
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
                  # 8a. Detachment latest-wins (issue #129): suppress if a newer admin-authored
                  #     gen:detachedRole out-ranks this preset-derived (ref, role) attachment.
                  #     Non-sticky: a newer PresetAssignment (newer ?created) re-attaches.
                  %10$s
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
                invalidationFilter("pdNp"),
                roleDetachmentSuppressionFilter(graph, "created", "pa"));
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
     * Observer self-evidence from a not-yet-approved account (issue #195): same shape,
     * but resolved through the pending row {@link #mirrorPendingAccounts} wrote.
     *
     * <p>Safe only because the observer tier is self-assignable by the tier model — the
     * grant carries no authority, and {@code ?agent} is already bound by the
     * instantiation, so this can never bind a pending key to a <em>different</em> agent.
     * It is the single place in this class where a non-approved row is accepted; every
     * other constraint keeps matching {@code npa:AccountState}.
     */
    static final String PUBLISHER_IS_SELF_PENDING = """
            ?acct a npa:PendingAccountState ;
                  npa:pubkey ?pkh ;
                  npa:agent  ?agent .
            """;

    /**
     * Extra INSERT triples stamped on roles materialized through a pending account, so
     * read queries can render them as "pending approval" with a one-triple OPTIONAL
     * instead of re-deriving the account's status.
     */
    static final String PENDING_ROLE_STAMP = "npa:trustStatus npa:seen ;\n                       ";

    /**
     * Maintainer / Member / Observer tier INSERT. Same shape: find an instantiation
     * whose predicate matches a RoleDeclaration of the given tier attached to the
     * target space, and whose publisher passes the tier-specific constraint.
     */
    static String nonAdminTierUpdate(IRI graph, long lastProcessed,
                                     IRI tierClass, String publisherConstraint) {
        return nonAdminTierUpdate(graph, lastProcessed, tierClass, publisherConstraint, "");
    }

    /**
     * Variant that stamps {@code extraInsert} onto every materialized row — used by the
     * pending-account observer pass to mark the row as awaiting trust approval.
     */
    static String nonAdminTierUpdate(IRI graph, long lastProcessed, IRI tierClass,
                                     String publisherConstraint, String extraInsert) {
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
                       # Persist the tier and role IRI that are already bound at this point —
                       # the loop's tierClass arg (%7$s) and the anchoring attachment's ?role
                       # (step 1) — so ref-scoped consumers key on identity rather than
                       # re-deriving the tier from the bare predicate against GLOBAL
                       # RoleDeclarations. The bare-predicate re-derivation bleeds tiers
                       # across spaces that declare the same predicate at different tiers
                       # (issue #125): consumers should match ?ri2 npa:hasRoleType <tier>
                       # / gen:hasRole ?role, exactly as the *-roles-ref queries do.
                       npa:hasRoleType <%7$s> ;
                       gen:hasRole ?role ;
                       %12$snpa:viaNanopub ?np .
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
                  # 2. Tier-pinned RoleDeclaration (?role bound from the attachment). Its
                  #    nanopub's invalidation is intentionally NOT consulted (see step 7), so
                  #    no ?rdNp binding is needed.
                  GRAPH <%4$s> {
                    ?rd a npa:RoleDeclaration ;
                        npa:hasRoleType <%7$s> ;
                        npa:role        ?role .
                    # 3. Pair role-decl direction to the instantiation in one UNION so only
                    #    matching combos are explored, binding (?instSpace, ?agent) per arm.
                    #    ?dirPred carries the resolved direction so the materialized row
                    #    records the role property (read by get-space-members and
                    #    publisherIsTieredRole) — identical shape whichever arm matched.
                    #
                    #    The first two arms handle instantiations the extractor already
                    #    classified (npa:regularProperty / npa:inverseProperty). The last two
                    #    resolve a custom predicate the extractor left neutral (npa:rolePredicate
                    #    with raw npa:bindingSubject / npa:bindingObject): the role declaration
                    #    supplies the direction, which fixes which raw endpoint is the space vs
                    #    the agent. INVERSE = <space> pred <agent>; REGULAR = <agent> pred <space>.
                    {
                      ?rd gen:hasRegularProperty ?pred .
                      ?ri npa:regularProperty ?pred ;
                          npa:forSpace ?instSpace ;
                          npa:forAgent ?agent .
                      BIND(npa:regularProperty AS ?dirPred)
                    }
                    UNION
                    {
                      ?rd gen:hasInverseProperty ?pred .
                      ?ri npa:inverseProperty ?pred ;
                          npa:forSpace ?instSpace ;
                          npa:forAgent ?agent .
                      BIND(npa:inverseProperty AS ?dirPred)
                    }
                    UNION
                    {
                      ?rd gen:hasInverseProperty ?pred .
                      ?ri npa:rolePredicate   ?pred ;
                          npa:bindingSubject  ?instSpace ;
                          npa:bindingObject   ?agent .
                      BIND(npa:inverseProperty AS ?dirPred)
                    }
                    UNION
                    {
                      ?rd gen:hasRegularProperty ?pred .
                      ?ri npa:rolePredicate   ?pred ;
                          npa:bindingObject   ?instSpace ;
                          npa:bindingSubject  ?agent .
                      BIND(npa:regularProperty AS ?dirPred)
                    }
                    # 4. Common instantiation columns. ?instSpace was resolved to this ref
                    #    above (canonical or owl:sameAs alias), so an alias-named instantiation
                    #    joins the same ?spaceRef as a canonical one. The materialized row still
                    #    carries npa:forSpace ?space (the attachment's IRI) for the transitional
                    #    dual-emit, so pre-ref reads see the member under the space's primary IRI.
                    ?ri a gen:RoleInstantiation ;
                        npa:pubkeyHash ?pkh ;
                        npa:viaNanopub ?np .
                    # Candidate grant timestamp for the revocation latest-wins (issue #129);
                    # absent ⇒ epoch (always loses).
                    OPTIONAL { ?ri <http://purl.org/dc/terms/created> ?candCreatedRaw . }
                  }
                  BIND(COALESCE(?candCreatedRaw, %11$s) AS ?candCreated)
                  # 5. Publisher constraint (incl. AccountState resolution).
                  GRAPH <%3$s> {
                    %8$s
                  }
                  # 5a. Mint the per-ref state subject: (?ri, ?spaceRef) → ?ri2.
                  BIND(IRI(CONCAT(STR(?ri), "__", ENCODE_FOR_URI(STR(?spaceRef)))) AS ?ri2)
                  # 6. Load-number filter on bound ?np.
                  GRAPH <%9$s> {
                    ?np npa:hasLoadNumber ?ln .
                    FILTER (?ln > %5$d)
                  }
                  # 7. Instantiation invalidation filter — outside the GRAPH block so the
                  #    planner defers it until ?np is bound. Role-DECLARATION invalidation is
                  #    deliberately NOT consulted: the tier already anchors on the admin-
                  #    validated attachment (?ra), which is removed when an admin retracts it,
                  #    so admin control is fully enforced there. Letting the declaration's
                  #    author (usually not the space admin) supersede/retract their declaration
                  #    strip a space's members is the same cross-author-strip anti-pattern as
                  #    issue #112. Role IRIs are version-pinned, so the attached definition is
                  #    immutable regardless of the declaration nanopub's later lifecycle.
                  %6$s
                  # 7a. Revocation latest-wins (issue #129): suppress if a newer authorized
                  #     gen:RevokedRoleInstantiation shadows this (space, agent, role) key.
                  %10$s
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
                publisherConstraint,
                NPA.GRAPH,
                nonAdminRevocationSuppressionFilter(graph, tierClass),
                EPOCH_DT,
                extraInsert);
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
                  # Reified per-(nanopub, ref-pair) provenance link (issue #125 finding #5):
                  # carries npa:viaNanopub plus both the ref and IRI endpoints, so the
                  # invalidation cleanup can drop the convenience edges below once no
                  # surviving link backs them — instead of leaving them sticky until the
                  # next periodic full rebuild.
                  ?ssLink a npa:SubSpaceLink ;
                          npa:viaNanopub     ?np ;
                          npa:childSpaceRef  ?childRef ;
                          npa:parentSpaceRef ?parentRef ;
                          npa:childSpace     ?child ;
                          npa:parentSpace    ?parent .
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
                  # 6. Mint the per-(nanopub, ref-pair) provenance link IRI and dedup on it
                  #    (not on the bare edge). Keyed on ?np so every backing declaration of
                  #    the same ref-pair records its own removable link; the convenience
                  #    edges above are re-asserted idempotently.
                  BIND(IRI(CONCAT("http://purl.org/nanopub/admin/spacelink/subspace/",
                                  MD5(CONCAT(STR(?np), "|", STR(?childRef), "|", STR(?parentRef))))) AS ?ssLink)
                  FILTER NOT EXISTS { GRAPH <%3$s> {
                    ?ssLink a npa:SubSpaceLink .
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
                  # Uniform ref-valued resource→governing-space-ref edge (issue #130). The
                  # same predicate the reflexive space self-edge uses, so a single consumer
                  # hop covers both "resource maintained by space S" and "resource IS a space".
                  # Backed by the same MaintainedResourceLink below, so the invalidation
                  # cleanup sweeps it alongside isMaintainedBy.
                  ?r npa:hasGoverningSpaceRef  ?sRef .
                  # Reified per-(nanopub, resource→ref) provenance link (issue #125 finding
                  # #5): lets the invalidation cleanup drop the convenience edges below once
                  # no surviving link backs them, instead of leaving them sticky.
                  ?mrLink a npa:MaintainedResourceLink ;
                          npa:viaNanopub         ?np ;
                          npa:resourceIri        ?r ;
                          npa:maintainerSpaceRef ?sRef ;
                          npa:maintainerSpace    ?s .
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
                  # 6. Mint the per-(nanopub, resource→ref) provenance link IRI and dedup on
                  #    it (not on the bare edge), so every backing declaration records its own
                  #    removable link; the convenience edges above are re-asserted idempotently.
                  BIND(IRI(CONCAT("http://purl.org/nanopub/admin/spacelink/maintained/",
                                  MD5(CONCAT(STR(?np), "|", STR(?r), "|", STR(?sRef))))) AS ?mrLink)
                  FILTER NOT EXISTS { GRAPH <%3$s> {
                    ?mrLink a npa:MaintainedResourceLink .
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
                  # Reified per-(nanopub, alias→canonical ref) provenance link (issue #125
                  # finding #5). The alias edge feeds the admin-authority closure, so this
                  # is the load-bearing case: the cleanup can now drop the edge when its
                  # declaration is invalidated, rather than letting admin authority outlive
                  # a retraction until the next periodic full rebuild.
                  ?alLink a npa:SpaceAliasLink ;
                          npa:viaNanopub        ?np ;
                          npa:aliasSpace        ?alias ;
                          npa:canonicalSpaceRef ?canonRef ;
                          npa:canonicalSpace    ?canonical .
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
                  # 6. Mint the per-(nanopub, alias→canonical ref) provenance link IRI and
                  #    dedup on it (not on the bare edge), so every backing declaration records
                  #    its own removable link; the convenience edges above are re-asserted
                  #    idempotently.
                  BIND(IRI(CONCAT("http://purl.org/nanopub/admin/spacelink/alias/",
                                  MD5(CONCAT(STR(?np), "|", STR(?alias), "|", STR(?canonRef))))) AS ?alLink)
                  FILTER NOT EXISTS { GRAPH <%3$s> {
                    ?alLink a npa:SpaceAliasLink .
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
                          # Ref endpoints too (issue #125 finding #5), so the sub-space
                          # orphan-sweep recognizes a prefix-derived ref edge as backed and
                          # never deletes it. Derived links have no source nanopub, so they
                          # are never invalidation-deleted; the fallback self-heals each cycle.
                          npa:childSpaceRef  ?childRef ;
                          npa:parentSpaceRef ?parentRef ;
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

    /**
     * Reflexive governing-space-ref pass (issue #130). For every {@code SpaceRef}
     * aggregate {@code ?spaceRef} (identified by {@code npa:spaceIri ?space} in the
     * extraction graph), emits {@code <space> npa:hasGoverningSpaceRef <spaceRef>} into
     * the space-state graph — the space pointing at its own ref through the same predicate
     * a maintained resource uses to point at its maintaining space's ref (emitted in
     * {@link #maintainedResourceAdmitUpdate}).
     *
     * <p>This removes the zero-hop special case from consumer authority gates: instead of
     * {@code ?resource npa:isMaintainedBy? ?space} (a bare-IRI optional path that breaks
     * once the hop is ref-valued), a consumer does a single mandatory
     * {@code ?resource npa:hasGoverningSpaceRef ?spaceRef} that binds whether the resource
     * is a maintained resource or a space itself. A space IRI claimed by several refs emits
     * one edge per ref — the non-ref consumer variant's merged-across-refs behaviour falls
     * out naturally; the ref variant pins {@code ?passedRef}.
     *
     * <p>Self-healing, like {@link #subSpacePrefixFallbackUpdate}: the edge has no source
     * nanopub (it follows purely from a {@code SpaceRef} existing), so there is no
     * invalidation handling and no load-number filter — always full-scan, with the dedup
     * {@code FILTER NOT EXISTS} on the edge preventing re-insertion. A {@code SpaceRef}
     * disappearing is itself a structural-rebuild event, which clears its reflexive edge.
     */
    static String governingSpaceRefReflexiveUpdate(IRI graph) {
        return """
                PREFIX npa: <%1$s>
                INSERT { GRAPH <%2$s> {
                  ?space npa:hasGoverningSpaceRef ?spaceRef .
                } }
                WHERE {
                  GRAPH <%3$s> { ?spaceRef npa:spaceIri ?space . }
                  FILTER NOT EXISTS { GRAPH <%2$s> {
                    ?space npa:hasGoverningSpaceRef ?spaceRef .
                  } }
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
                    %5$s
                  }
                """, graph, NPA.GRAPH, NPX.INVALIDATES, lastProcessed,
                samePublisherClause("invNp", "np"));
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
                    %5$s
                  }
                """, graph, NPA.GRAPH, NPX.INVALIDATES, lastProcessed,
                samePublisherClause("invNp", "np"));
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
                    %7$s
                  }
                }
                """, NPA.NAMESPACE, GEN.NAMESPACE, graph,
                NPA.GRAPH, NPX.INVALIDATES, lastProcessed,
                samePublisherClause("invNp", "np"));
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
                    %5$s
                  }
                """, graph, NPA.GRAPH, NPX.INVALIDATES, lastProcessed,
                samePublisherClause("invNp", "np"));
    }

    /**
     * DELETE template for validated {@code npa:SubSpaceDeclaration} rows whose
     * source nanopub was invalidated. Removes the per-declaration row by subject;
     * the convenience direct triples ({@code <child> npa:isSubSpaceOf <parent>}
     * and inverse) are then dropped by {@link #subSpaceConvenienceEdgeCleanup} in the
     * same cycle (issue #125 finding #5) once no surviving link backs them.
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
     * and inverse) are then dropped by {@link #maintainedResourceConvenienceEdgeCleanup}
     * in the same cycle (issue #125 finding #5). No structural-rebuild flag —
     * maintained-resource is a leaf relation, no downstream consumers depend on its
     * closure, so the prompt edge cleanup fully resolves its invalidation.
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
                    %7$s
                  }
                }
                """, NPA.NAMESPACE, GEN.NAMESPACE, graph,
                NPA.GRAPH, NPX.INVALIDATES, lastProcessed,
                samePublisherClause("invNp", "np"));
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
                    %5$s
                  }
                """, graph, NPA.GRAPH, NPX.INVALIDATES, lastProcessed,
                samePublisherClause("invNp", "np"));
    }

    /**
     * DELETE template for validated {@code npa:SpaceAliasDeclaration} rows whose
     * source nanopub was invalidated. Removes the per-declaration row by subject; the
     * convenience {@code <alias> npa:sameAsSpace <canonical>} edge is then dropped by
     * {@link #aliasConvenienceEdgeCleanup} in the same cycle (issue #125 finding #5),
     * so an alias can no longer grant admin authority after its declaration is retracted.
     * The alias feeds the authority closure, so this kind is still structural and flips
     * {@code npa:needsFullRebuild} to bound any rows already derived through the edge.
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
     * WHERE clause shared by the maintained-resource invalidation ASK precheck and the
     * matching cleanup. Identifies validated {@code npa:MaintainedResourceDeclaration}
     * rows in the space-state graph whose {@code npa:viaNanopub} is the target of an
     * {@code npx:invalidates} triple in {@code npa:graph} whose subject nanopub has a
     * load number in {@code (lastProcessed, ∞)}.
     */
    static String maintainedResourceInvalidationCheckWhere(IRI graph, long lastProcessed) {
        return String.format("""
                  GRAPH <%1$s> {
                    ?d a npa:MaintainedResourceDeclaration ;
                       npa:viaNanopub ?np .
                  }
                  GRAPH <%2$s> {
                    ?invNp <%3$s> ?np ;
                           npa:hasLoadNumber ?ln .
                    FILTER (?ln > %4$d)
                    %5$s
                  }
                """, graph, NPA.GRAPH, NPX.INVALIDATES, lastProcessed,
                samePublisherClause("invNp", "np"));
    }

    /**
     * Convenience-edge cleanup for invalidated sub-space declarations (issue #125
     * finding #5). Run after {@link #subSpaceInvalidationDelete} (which removes the
     * {@code npa:SubSpaceDeclaration} rows). Two phases as one multi-operation update:
     * <ol>
     *   <li>delete every {@code npa:SubSpaceLink} provenance link whose
     *       {@code npa:viaNanopub} was invalidated (same {@code npx:invalidates} +
     *       same-publisher gate as the declaration delete);</li>
     *   <li>orphan-sweep: delete the convenience {@code npa:isSubSpaceOf} /
     *       {@code npa:hasSubSpace} edges (both ref- and IRI-valued) that no surviving
     *       link backs — neither a {@code npa:SubSpaceLink} (explicit declaration) nor a
     *       {@code npa:DerivedSubSpaceLink} (URL-prefix fallback).</li>
     * </ol>
     * Edges backed by another surviving declaration or by the URL-prefix fallback are
     * kept. The {@code npa:needsFullRebuild} flag still fires for the structural kind, so
     * downstream rows derived through a removed edge remain rebuild-bounded; this only
     * stops the convenience edges themselves from going sticky.
     */
    static String subSpaceConvenienceEdgeCleanup(IRI graph, long lastProcessed) {
        return String.format("""
                PREFIX npa: <%1$s>
                # 1. Drop sub-space provenance links whose source nanopub was invalidated.
                DELETE { GRAPH <%2$s> { ?l ?p ?o . } }
                WHERE {
                  GRAPH <%2$s> {
                    ?l a npa:SubSpaceLink ;
                       npa:viaNanopub ?np .
                    ?l ?p ?o .
                  }
                  GRAPH <%3$s> {
                    ?invNp <%4$s> ?np ;
                           npa:hasLoadNumber ?ln .
                    FILTER (?ln > %5$d)
                    %6$s
                  }
                } ;
                # 2. Orphan-sweep isSubSpaceOf edges (ref- and IRI-valued) with no backing link.
                DELETE { GRAPH <%2$s> { ?c npa:isSubSpaceOf ?p . } }
                WHERE {
                  GRAPH <%2$s> {
                    ?c npa:isSubSpaceOf ?p .
                    FILTER NOT EXISTS {
                      { ?l a npa:SubSpaceLink } UNION { ?l a npa:DerivedSubSpaceLink }
                      { { ?l npa:childSpaceRef ?c . ?l npa:parentSpaceRef ?p }
                        UNION
                        { ?l npa:childSpace ?c . ?l npa:parentSpace ?p } }
                    }
                  }
                } ;
                # 3. Orphan-sweep the inverse hasSubSpace edges symmetrically.
                DELETE { GRAPH <%2$s> { ?p npa:hasSubSpace ?c . } }
                WHERE {
                  GRAPH <%2$s> {
                    ?p npa:hasSubSpace ?c .
                    FILTER NOT EXISTS {
                      { ?l a npa:SubSpaceLink } UNION { ?l a npa:DerivedSubSpaceLink }
                      { { ?l npa:childSpaceRef ?c . ?l npa:parentSpaceRef ?p }
                        UNION
                        { ?l npa:childSpace ?c . ?l npa:parentSpace ?p } }
                    }
                  }
                }
                """, NPA.NAMESPACE, graph, NPA.GRAPH, NPX.INVALIDATES, lastProcessed,
                samePublisherClause("invNp", "np"));
    }

    /**
     * Convenience-edge cleanup for invalidated maintained-resource declarations (issue
     * #125 finding #5). Run after {@link #maintainedResourceInvalidationDelete}. Deletes
     * the {@code npa:MaintainedResourceLink} provenance links whose source nanopub was
     * invalidated, then orphan-sweeps the {@code npa:isMaintainedBy} /
     * {@code npa:hasMaintainedResource} edges (ref- and IRI-valued) that no surviving link
     * backs. See {@link #subSpaceConvenienceEdgeCleanup} for the two-phase structure.
     */
    static String maintainedResourceConvenienceEdgeCleanup(IRI graph, long lastProcessed) {
        return String.format("""
                PREFIX npa: <%1$s>
                # 1. Drop maintained-resource provenance links whose source nanopub was invalidated.
                DELETE { GRAPH <%2$s> { ?l ?p ?o . } }
                WHERE {
                  GRAPH <%2$s> {
                    ?l a npa:MaintainedResourceLink ;
                       npa:viaNanopub ?np .
                    ?l ?p ?o .
                  }
                  GRAPH <%3$s> {
                    ?invNp <%4$s> ?np ;
                           npa:hasLoadNumber ?ln .
                    FILTER (?ln > %5$d)
                    %6$s
                  }
                } ;
                # 2. Orphan-sweep isMaintainedBy edges (ref- and IRI-valued) with no backing link.
                DELETE { GRAPH <%2$s> { ?r npa:isMaintainedBy ?o . } }
                WHERE {
                  GRAPH <%2$s> {
                    ?r npa:isMaintainedBy ?o .
                    FILTER NOT EXISTS {
                      ?l a npa:MaintainedResourceLink ;
                         npa:resourceIri ?r .
                      { ?l npa:maintainerSpaceRef ?o } UNION { ?l npa:maintainerSpace ?o }
                    }
                  }
                } ;
                # 3. Orphan-sweep the inverse hasMaintainedResource edges symmetrically.
                DELETE { GRAPH <%2$s> { ?o npa:hasMaintainedResource ?r . } }
                WHERE {
                  GRAPH <%2$s> {
                    ?o npa:hasMaintainedResource ?r .
                    FILTER NOT EXISTS {
                      ?l a npa:MaintainedResourceLink ;
                         npa:resourceIri ?r .
                      { ?l npa:maintainerSpaceRef ?o } UNION { ?l npa:maintainerSpace ?o }
                    }
                  }
                } ;
                # 4. Orphan-sweep the maintained arm of hasGoverningSpaceRef (issue #130).
                #    Only the ref-valued maintained edge is removed here — it is backed by a
                #    MaintainedResourceLink. The reflexive space self-edge (subject = a space
                #    IRI that has its own SpaceRef) is NOT a maintained edge and is left to the
                #    self-healing reflexive pass, so the guard keeps any ?r that is itself a space.
                DELETE { GRAPH <%2$s> { ?r npa:hasGoverningSpaceRef ?o . } }
                WHERE {
                  GRAPH <%2$s> {
                    ?r npa:hasGoverningSpaceRef ?o .
                    FILTER NOT EXISTS {
                      ?l a npa:MaintainedResourceLink ;
                         npa:resourceIri ?r ;
                         npa:maintainerSpaceRef ?o .
                    }
                    FILTER NOT EXISTS { GRAPH <%7$s> { ?o npa:spaceIri ?r . } }
                  }
                }
                """, NPA.NAMESPACE, graph, NPA.GRAPH, NPX.INVALIDATES, lastProcessed,
                samePublisherClause("invNp", "np"), SpacesVocab.SPACES_GRAPH);
    }

    /**
     * Convenience-edge cleanup for invalidated space-alias declarations (issue #125
     * finding #5 — the load-bearing case, since the alias edge feeds the admin-authority
     * closure). Run after {@link #aliasInvalidationDelete}. Deletes the
     * {@code npa:SpaceAliasLink} provenance links whose source nanopub was invalidated,
     * then orphan-sweeps the {@code npa:sameAsSpace} edges (ref- and IRI-valued) that no
     * surviving link backs. See {@link #subSpaceConvenienceEdgeCleanup} for the two-phase
     * structure.
     */
    static String aliasConvenienceEdgeCleanup(IRI graph, long lastProcessed) {
        return String.format("""
                PREFIX npa: <%1$s>
                # 1. Drop alias provenance links whose source nanopub was invalidated.
                DELETE { GRAPH <%2$s> { ?l ?p ?o . } }
                WHERE {
                  GRAPH <%2$s> {
                    ?l a npa:SpaceAliasLink ;
                       npa:viaNanopub ?np .
                    ?l ?p ?o .
                  }
                  GRAPH <%3$s> {
                    ?invNp <%4$s> ?np ;
                           npa:hasLoadNumber ?ln .
                    FILTER (?ln > %5$d)
                    %6$s
                  }
                } ;
                # 2. Orphan-sweep sameAsSpace edges (ref- and IRI-valued) with no backing link.
                DELETE { GRAPH <%2$s> { ?alias npa:sameAsSpace ?o . } }
                WHERE {
                  GRAPH <%2$s> {
                    ?alias npa:sameAsSpace ?o .
                    FILTER NOT EXISTS {
                      ?l a npa:SpaceAliasLink ;
                         npa:aliasSpace ?alias .
                      { ?l npa:canonicalSpaceRef ?o } UNION { ?l npa:canonicalSpace ?o }
                    }
                  }
                }
                """, NPA.NAMESPACE, graph, NPA.GRAPH, NPX.INVALIDATES, lastProcessed,
                samePublisherClause("invNp", "np"));
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
     * WHERE clause matching a materialized <em>non-admin</em> {@code gen:RoleInstantiation}
     * row whose {@code (forSpaceRef, forAgent, gen:hasRole)} key is shadowed by a newer
     * authorized {@code npa:RoleRevocation} (issue #129). The grant timestamp comes from the
     * originating instantiation in the extraction graph (the materialized row carries no
     * {@code dct:created}); the revocation nanopub's load number must be in
     * {@code (lastProcessed, ∞)} so only revocations new in this cycle trigger a delete.
     * Authorization is keyed on the row's bound {@code ?tier} (the matrix: a strictly-higher
     * tier in the ref, or self). Not an {@code npx:invalidates} check.
     */
    static String roleRevocationCheckWhere(IRI graph, long lastProcessed, IRI targetTier) {
        return String.format("""
                  GRAPH <%1$s> {
                    ?ri2 a gen:RoleInstantiation ;
                         npa:forSpaceRef ?spaceRef ;
                         npa:forAgent    ?agent ;
                         gen:hasRole     ?role ;
                         npa:hasRoleType <%7$s> ;
                         npa:viaNanopub  ?np .
                  }
                  OPTIONAL { GRAPH <%2$s> {
                    ?riSrc npa:viaNanopub ?np ;
                           <http://purl.org/dc/terms/created> ?candCreatedRaw .
                  } }
                  BIND(COALESCE(?candCreatedRaw, %5$s) AS ?candCreated)
                  { GRAPH <%2$s> { ?spaceRef npa:spaceIri ?revSpace . } }
                  UNION
                  { GRAPH <%1$s> { ?revSpace npa:sameAsSpace ?spaceRef . } }
                  GRAPH <%2$s> {
                    ?rev a npa:RoleRevocation ;
                         npa:forSpace    ?revSpace ;
                         npa:forAgent    ?agent ;
                         npa:revokedRole ?role ;
                         npa:pubkeyHash  ?revPkh ;
                         npa:viaNanopub  ?revNp .
                    OPTIONAL { ?rev <http://purl.org/dc/terms/created> ?revCreatedRaw . }
                  }
                  BIND(COALESCE(?revCreatedRaw, %5$s) AS ?revCreated)
                  GRAPH <%3$s> {
                    ?revNp npa:hasLoadNumber ?lnRev .
                    FILTER (?lnRev > %4$d)
                  }
                  FILTER (?revCreated > ?candCreated
                          || (?revCreated = ?candCreated && STR(?rev) > STR(?ri2)))
                  { %6$s }
                """, graph, SpacesVocab.SPACES_GRAPH, NPA.GRAPH, lastProcessed,
                EPOCH_DT, revocationAuthorityArmsForTier(graph, targetTier), targetTier);
    }

    /**
     * DELETE template removing a non-admin {@code gen:RoleInstantiation} row of {@code
     * targetTier} shadowed by a newer authorized revocation (issue #129). Removes the whole
     * row by subject. Run once per non-admin tier (maintainer/member/observer) so the
     * authorization arms are the compile-time set for that tier — matching the inline
     * suppression filter, no runtime {@code ?tier} (see {@link #revocationAuthorityArmsForTier}).
     * Caller sets {@code needsFullRebuild} (a revoked maintainer/member is a sub-granting
     * authority).
     */
    static String roleRevocationDelete(IRI graph, long lastProcessed, IRI targetTier) {
        return String.format("""
                PREFIX npa: <%1$s>
                PREFIX gen: <%2$s>
                DELETE { GRAPH <%3$s> {
                  ?ri2 ?p ?o .
                } }
                WHERE {
                  GRAPH <%3$s> { ?ri2 ?p ?o . }
                %4$s
                }
                """, NPA.NAMESPACE, GEN.NAMESPACE, graph,
                roleRevocationCheckWhere(graph, lastProcessed, targetTier));
    }

    /**
     * WHERE clause matching a materialized <em>admin</em> {@code gen:RoleInstantiation} row
     * whose {@code (forSpaceRef, forAgent)} key is shadowed by a newer authorized admin
     * {@code npa:RoleRevocation} ({@code revokedRole = gen:AdminRole}), authorized by an
     * admin of the ref or by the agent itself. <b>Root admins are exempt</b> (constitutional,
     * issue #129/#110): the nested {@code FILTER NOT EXISTS} on {@code npa:hasRootAdmin}
     * makes the revocation inert. The revocation nanopub's load number must be in
     * {@code (lastProcessed, ∞)}.
     */
    static String adminRevocationCheckWhere(IRI graph, long lastProcessed) {
        return String.format("""
                  GRAPH <%1$s> {
                    ?sri a gen:RoleInstantiation ;
                         npa:forSpaceRef     ?spaceRef ;
                         npa:inverseProperty gen:hasAdmin ;
                         npa:forAgent        ?agent ;
                         npa:viaNanopub      ?np .
                  }
                  FILTER NOT EXISTS { GRAPH <%2$s> {
                    ?rootDef a npa:SpaceDefinition ;
                             npa:forSpaceRef  ?spaceRef ;
                             npa:hasRootAdmin ?agent .
                  } }
                  OPTIONAL { GRAPH <%2$s> {
                    ?riSrc npa:viaNanopub ?np ;
                           <http://purl.org/dc/terms/created> ?candCreatedRaw .
                  } }
                  BIND(COALESCE(?candCreatedRaw, %5$s) AS ?candCreated)
                  { GRAPH <%2$s> { ?spaceRef npa:spaceIri ?revSpace . } }
                  UNION
                  { GRAPH <%1$s> { ?revSpace npa:sameAsSpace ?spaceRef . } }
                  GRAPH <%2$s> {
                    ?rev a npa:RoleRevocation ;
                         npa:forSpace    ?revSpace ;
                         npa:forAgent    ?agent ;
                         npa:revokedRole gen:AdminRole ;
                         npa:pubkeyHash  ?revPkh ;
                         npa:viaNanopub  ?revNp .
                    OPTIONAL { ?rev <http://purl.org/dc/terms/created> ?revCreatedRaw . }
                  }
                  BIND(COALESCE(?revCreatedRaw, %5$s) AS ?revCreated)
                  GRAPH <%3$s> {
                    ?revNp npa:hasLoadNumber ?lnRev .
                    FILTER (?lnRev > %4$d)
                  }
                  FILTER (?revCreated > ?candCreated
                          || (?revCreated = ?candCreated && STR(?rev) > STR(?sri)))
                  { %6$s }
                """, graph, SpacesVocab.SPACES_GRAPH, NPA.GRAPH, lastProcessed, EPOCH_DT,
                "{ " + revokerAdminGraphBlock(graph) + " }\nUNION\n{ "
                        + revokerSelfGraphBlock(graph) + " }");
    }

    /**
     * DELETE template removing an admin {@code gen:RoleInstantiation} row shadowed by a newer
     * authorized admin revocation (issue #129). Removes the whole row by subject.
     * <b>Structural</b> — admin RIs feed every downstream tier — so the caller sets
     * {@code npa:needsFullRebuild} (mirrors {@code adminInvalidationDelete}). The
     * {@code adminTierUpdate} inline suppression filter prevents re-materialization.
     */
    static String adminRevocationDelete(IRI graph, long lastProcessed) {
        return String.format("""
                PREFIX npa: <%1$s>
                PREFIX gen: <%2$s>
                DELETE { GRAPH <%3$s> {
                  ?sri ?p ?o .
                } }
                WHERE {
                  GRAPH <%3$s> { ?sri ?p ?o . }
                %4$s
                }
                """, NPA.NAMESPACE, GEN.NAMESPACE, graph,
                adminRevocationCheckWhere(graph, lastProcessed));
    }

    /**
     * WHERE clause matching a materialized {@code gen:RoleAssignment} row (direct
     * <em>or</em> preset-derived) whose {@code (forSpaceRef, gen:hasRole)} key is shadowed by
     * a newer admin-authored {@code npa:RoleDetachment} (issue #129). The attachment
     * timestamp comes from whichever extraction row shares the materialized row's
     * {@code npa:viaNanopub} (a {@code RoleAssignment} for direct attachments, a
     * {@code PresetAssignment} for preset-derived ones). The detachment nanopub's load number
     * must be in {@code (lastProcessed, ∞)}; authority = admin of the ref.
     */
    static String roleDetachmentCheckWhere(IRI graph, long lastProcessed) {
        return String.format("""
                  GRAPH <%1$s> {
                    ?ra2 a gen:RoleAssignment ;
                         npa:forSpaceRef ?targetRef ;
                         gen:hasRole     ?role ;
                         npa:viaNanopub  ?np .
                  }
                  OPTIONAL { GRAPH <%2$s> {
                    ?attSrc npa:viaNanopub ?np ;
                            <http://purl.org/dc/terms/created> ?attCreatedRaw .
                  } }
                  BIND(COALESCE(?attCreatedRaw, %5$s) AS ?attCreated)
                  { GRAPH <%2$s> { ?targetRef npa:spaceIri ?detSpace . } }
                  UNION
                  { GRAPH <%1$s> { ?detSpace npa:sameAsSpace ?targetRef . } }
                  GRAPH <%2$s> {
                    ?det a npa:RoleDetachment ;
                         npa:forSpace    ?detSpace ;
                         npa:revokedRole ?role ;
                         npa:pubkeyHash  ?detPkh ;
                         npa:viaNanopub  ?detNp .
                    OPTIONAL { ?det <http://purl.org/dc/terms/created> ?detCreatedRaw . }
                  }
                  BIND(COALESCE(?detCreatedRaw, %5$s) AS ?detCreated)
                  GRAPH <%3$s> {
                    ?detNp npa:hasLoadNumber ?lnDet .
                    FILTER (?lnDet > %4$d)
                  }
                  FILTER (?detCreated > ?attCreated
                          || (?detCreated = ?attCreated && STR(?det) > STR(?ra2)))
                  GRAPH <%1$s> {
                    ?detAcct a npa:AccountState ; npa:pubkey ?detPkh ; npa:agent ?detAgent .
                    ?detAdminRI a gen:RoleInstantiation ;
                                npa:forSpaceRef ?targetRef ;
                                npa:inverseProperty gen:hasAdmin ;
                                npa:forAgent ?detAgent .
                  }
                """, graph, SpacesVocab.SPACES_GRAPH, NPA.GRAPH, lastProcessed, EPOCH_DT);
    }

    /**
     * DELETE template removing a {@code gen:RoleAssignment} row (direct or preset-derived)
     * shadowed by a newer admin-authored {@code gen:detachedRole} (issue #129). Removes the
     * whole row by subject. <b>Structural</b> — instantiations anchored on the removed
     * attachment are bounded by the periodic full rebuild (the cascade), so the caller sets
     * {@code npa:needsFullRebuild}. The attachment-tier inline filters prevent
     * re-materialization until a newer attachment / preset assignment out-ranks the detach
     * (non-sticky).
     */
    static String roleDetachmentDelete(IRI graph, long lastProcessed) {
        return String.format("""
                PREFIX npa: <%1$s>
                PREFIX gen: <%2$s>
                DELETE { GRAPH <%3$s> {
                  ?ra2 ?p ?o .
                } }
                WHERE {
                  GRAPH <%3$s> { ?ra2 ?p ?o . }
                %4$s
                }
                """, NPA.NAMESPACE, GEN.NAMESPACE, graph,
                roleDetachmentCheckWhere(graph, lastProcessed));
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
                    %7$s
                  }
                }
                """, NPA.NAMESPACE, GEN.NAMESPACE, graph,
                NPA.GRAPH, NPX.INVALIDATES, lastProcessed,
                samePublisherClause("invNp", "assignNp"));
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
    /**
     * Whether the given trust state's graph holds anything at all.
     *
     * <p>Used by {@link #runFullBuild} to tell a build that read nothing because the store
     * would not answer from a build that read nothing because there is nothing to read. Only
     * the first is a reason to withhold the result; withholding the second would freeze a
     * stale space state in place, and stale trust data is over-permissive.
     *
     * <p>Throws rather than guessing if the trust repo cannot be read — {@link #runFullBuild}
     * then aborts without publishing or dropping anything, which is the safe direction.
     *
     * @param trustStateHash the trust state hash
     * @return true if the trust state graph contains at least one triple
     */
    boolean trustStateHasContent(String trustStateHash) {
        IRI trustStateIri = NPAT.forHash(trustStateHash);
        try (RepositoryConnection conn = TripleStore.get().getRepoConnection(TRUST_REPO)) {
            String query = String.format("ASK { GRAPH <%s> { ?s ?p ?o } }", trustStateIri);
            return conn.prepareBooleanQuery(QueryLanguage.SPARQL, query).evaluate();
        } catch (Exception ex) {
            throw new SpaceStateUnavailableException(
                    "failed to read trust state graph " + trustStateIri, ex);
        }
    }

    /**
     * First object of {@code (subject, predicate)} in {@code context}, or {@code null}.
     *
     * <p>Closes the underlying {@link RepositoryResult}: the {@code .stream().findFirst()}
     * shorthand this replaces leaves the iteration open, which RDF4J reports as
     * "Connection closed before all iterations were closed" whenever the cleaner has not
     * collected it before the connection closes.
     */
    private static Value firstObject(RepositoryConnection conn, IRI subject, IRI predicate, IRI context) {
        try (RepositoryResult<Statement> r = conn.getStatements(subject, predicate, null, context)) {
            return r.hasNext() ? r.next().getObject() : null;
        }
    }

    int mirrorTrustState(String trustStateHash, IRI newGraph) {
        IRI trustStateIri = NPAT.forHash(trustStateHash);
        int count = 0;
        try (RepositoryConnection trustConn = TripleStore.get().getRepoConnection(TRUST_REPO);
             RepositoryConnection spacesConn = TripleStore.get().getRepoConnection(SPACES_REPO)) {
            trustConn.begin(IsolationLevels.READ_COMMITTED);
            // Append-only writes into the not-yet-published newGraph (the current-state
            // pointer is swapped to it only after the build completes), and all spaces
            // writers serialise via this class's synchronized methods; see
            // NanopubLoader#repoWriteLocks for why SERIALIZABLE is avoided.
            spacesConn.begin(IsolationLevels.READ_COMMITTED);
            // Walk rdf:type triples in the trust state's graph; for each AccountState,
            // check status and copy the approved ones verbatim (minus status-specific
            // detail triples, which we don't need for validation).
            try (RepositoryResult<Statement> typeRows = trustConn.getStatements(
                    null, RDF.TYPE, NPA_ACCOUNT_STATE, trustStateIri)) {
                while (typeRows.hasNext()) {
                    Statement st = typeRows.next();
                    if (!(st.getSubject() instanceof IRI accountStateIri)) continue;
                    Value status = firstObject(trustConn, accountStateIri, NPA_TRUST_STATUS, trustStateIri);
                    if (!(status instanceof IRI statusIri) || !APPROVED_SET.contains(statusIri)) continue;
                    Value agent = firstObject(trustConn, accountStateIri, NPA_AGENT, trustStateIri);
                    Value pubkey = firstObject(trustConn, accountStateIri, NPA_PUBKEY, trustStateIri);
                    if (agent == null || pubkey == null) {
                        logger.warn("AuthorityResolver.mirror: account {} missing agent or pubkey; skipping",
                                accountStateIri);
                        continue;
                    }
                    spacesConn.add(accountStateIri, RDF.TYPE, NPA_ACCOUNT_STATE, newGraph);
                    spacesConn.add(accountStateIri, NPA_AGENT, agent, newGraph);
                    spacesConn.add(accountStateIri, NPA_PUBKEY, pubkey, newGraph);
                    spacesConn.add(accountStateIri, NPA_TRUST_STATUS, statusIri, newGraph);
                    // Mirror the authorizing introduction provenance when present (issue #125
                    // finding #4). Optional: absent for snapshots from registries that predate
                    // nanopub-registry#117/#118, so consumers (e.g. get-space-members-ref) must
                    // treat npa:viaNanopub on an AccountState as best-effort, not guaranteed.
                    Value viaNanopub = firstObject(trustConn, accountStateIri, NPA_VIA_NANOPUB, trustStateIri);
                    if (viaNanopub != null) {
                        spacesConn.add(accountStateIri, NPA_VIA_NANOPUB, viaNanopub, newGraph);
                    }
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

    // ---------------- Pending-account mirror (issue #195) ----------------

    /**
     * Result of one {@link #mirrorPendingAccounts} pass.
     *
     * @param rows        number of {@code npa:PendingAccountState} rows written
     * @param scannedUpTo the new watermark: the highest {@code meta} load number this
     *                    pass looked at, or the incoming watermark when it saw nothing
     */
    record PendingMirrorResult(int rows, long scannedUpTo) {}

    /** Approved (agent, pubkey) pairs already present in a space-state graph. */
    private record ApprovedIndex(Set<IRI> agents, Set<String> pubkeys) {}

    /**
     * Reads the approved {@code npa:AccountState} rows of a space-state graph into
     * two lookup sets. Used by {@link #mirrorPendingAccounts} to keep self-asserted
     * introductions away from identities that trust approval has already settled.
     */
    private ApprovedIndex readApprovedIndex(IRI graph) {
        Set<IRI> agents = new HashSet<>();
        Set<String> pubkeys = new HashSet<>();
        String query = String.format("""
                SELECT ?agent ?pubkey WHERE {
                  GRAPH <%1$s> {
                    ?acct a <%2$s> ;
                          <%3$s> ?agent ;
                          <%4$s> ?pubkey .
                  }
                }
                """, graph, NPA_ACCOUNT_STATE, NPA_AGENT, NPA_PUBKEY);
        try (RepositoryConnection conn = TripleStore.get().getRepoConnection(SPACES_REPO);
             TupleQueryResult r = conn.prepareTupleQuery(QueryLanguage.SPARQL, query).evaluate()) {
            while (r.hasNext()) {
                BindingSet b = r.next();
                if (b.getValue("agent") instanceof IRI agent) agents.add(agent);
                pubkeys.add(b.getValue("pubkey").stringValue());
            }
        } catch (Exception ex) {
            // Must not degrade to an empty index: that would drop both exclusions and
            // let a self-asserted introduction claim an already-approved identity.
            throw new SpaceStateUnavailableException(
                    "failed to read approved account rows from " + graph, ex);
        }
        return new ApprovedIndex(agents, pubkeys);
    }

    /**
     * Mirrors introduced-but-unapproved accounts into {@code graph} as
     * {@code npa:PendingAccountState} rows, so that self-signed observer roles and
     * own-profile view displays from not-yet-approved users become visible
     * (issue #195). Reads introduction nanopubs from {@code npa:graph} of the
     * {@code meta} repo — the registry distributes them for unknown pubkeys too when
     * optional load is on, which is why the data is there at all.
     *
     * <p>Only <em>authoritative</em> introductions count: the declared key must be the
     * key that signed the introduction ({@code SHA256(?pubkey) = ?pkh}), so the other
     * keys of a multi-key introduction are deliberately not mirrored. Self-retraction
     * is honoured through the same publisher-matched {@code npx:invalidates} gate the
     * tiers use (issue #112).
     *
     * <p>Two exclusions keep self-asserted data away from settled identities:
     * an agent that already has an approved {@code AccountState} row is skipped (so a
     * rogue introduction cannot pollute an approved user's page or lists), and so is a
     * pubkey that already has one (so an approved key cannot be re-bound to a second
     * identity).
     *
     * <p>The rows never confer authority: they carry {@link #NPA_PENDING_ACCOUNT_STATE},
     * which no authority join in this class matches. Approval needs no cleanup pass —
     * it flips the trust-state hash, which makes {@link #tick()} run a full build that
     * re-derives everything and simply stops mirroring the pending row.
     *
     * @param graph          the space-state graph to write into
     * @param fromLoadNumber scan only introductions with a {@code meta} load number
     *                       greater than this ({@code -1} scans everything)
     * @return the number of rows written and the new watermark
     */
    PendingMirrorResult mirrorPendingAccounts(IRI graph, long fromLoadNumber) {
        if (!FeatureFlags.pendingAccountsEnabled()) {
            return new PendingMirrorResult(0, fromLoadNumber);
        }
        ApprovedIndex approved = readApprovedIndex(graph);
        // ?pkh, not ?pubkey, is what goes into the row: npa:pubkey on a space-state
        // account row holds the pubkey *hash* (it is joined against the extraction
        // graph's npa:pubkeyHash and against npa:hasValidSignatureForPublicKeyHash),
        // despite the predicate's name. The full key is only used to prove the
        // declaration is authoritative.
        String query = String.format("""
                SELECT DISTINCT ?agent ?pkh ?np ?ln WHERE {
                  GRAPH <%1$s> {
                    ?np <%2$s> ?agent ;
                        <%3$s> ?pubkey ;
                        <%4$s> ?pkh ;
                        <%5$s> ?ln .
                    # Authoritative introduction: the declared key signed it.
                    FILTER (SHA256(?pubkey) = STR(?pkh))
                    # Self-retraction gate (issue #112): only the introduction's own
                    # publisher can invalidate it.
                    FILTER NOT EXISTS {
                      ?inv <%6$s> ?np ;
                           <%4$s> ?pkh .
                    }
                    FILTER (?ln > %7$d)
                  }
                }
                """, NPA.GRAPH, NPA.IS_INTRODUCTION_OF, NPA.DECLARES_PUBKEY,
                NPA.HAS_VALID_SIGNATURE_FOR_PUBLIC_KEY_HASH, NPA.HAS_LOAD_NUMBER,
                NPX.INVALIDATES, fromLoadNumber);

        List<Statement[]> pending = new ArrayList<>();
        long maxLoadNumber = fromLoadNumber;
        try (RepositoryConnection metaConn = TripleStore.get().getRepoConnection(META_REPO);
             TupleQueryResult r = metaConn.prepareTupleQuery(QueryLanguage.SPARQL, query).evaluate()) {
            while (r.hasNext()) {
                BindingSet b = r.next();
                long ln = Long.parseLong(b.getValue("ln").stringValue());
                maxLoadNumber = Math.max(maxLoadNumber, ln);
                if (!(b.getValue("agent") instanceof IRI agent)) continue;
                if (!(b.getValue("np") instanceof IRI introNp)) continue;
                Value pubkeyHash = b.getValue("pkh");
                if (approved.agents().contains(agent)) continue;
                if (approved.pubkeys().contains(pubkeyHash.stringValue())) continue;
                IRI rowIri = pendingAccountIri(graph, pubkeyHash.stringValue(), agent);
                pending.add(new Statement[] {
                        vf.createStatement(rowIri, RDF.TYPE, NPA_PENDING_ACCOUNT_STATE, graph),
                        vf.createStatement(rowIri, NPA_AGENT, agent, graph),
                        vf.createStatement(rowIri, NPA_PUBKEY, pubkeyHash, graph),
                        vf.createStatement(rowIri, NPA_TRUST_STATUS, NPA_SEEN, graph),
                        vf.createStatement(rowIri, NPA_VIA_NANOPUB, introNp, graph),
                });
            }
        } catch (Exception ex) {
            throw new SpaceStateUnavailableException(
                    "failed to read introduction nanopubs from the " + META_REPO + " repo", ex);
        }

        int count = 0;
        try (RepositoryConnection spacesConn = TripleStore.get().getRepoConnection(SPACES_REPO)) {
            // Append-only writes, same rationale as mirrorTrustState: all spaces writers
            // serialise through this class's synchronized methods.
            spacesConn.begin(IsolationLevels.READ_COMMITTED);
            for (Statement[] row : pending) {
                IRI rowIri = (IRI) row[0].getSubject();
                // Re-running a cycle (or a watermark rewind) must not duplicate rows.
                if (spacesConn.hasStatement(rowIri, RDF.TYPE, NPA_PENDING_ACCOUNT_STATE, false, graph)) {
                    continue;
                }
                for (Statement st : row) {
                    spacesConn.add(st);
                }
                count++;
            }
            spacesConn.commit();
        }
        return new PendingMirrorResult(count, maxLoadNumber);
    }

    /**
     * Mints the {@code npaa:} subject of a pending-account row. Keyed on the state
     * graph (which encodes the trust-state hash and the build's load counter), so the
     * IRI is stable within a build and different across builds — the same property
     * {@code TrustStateLoader.accountStateHash} gives approved rows.
     */
    private static IRI pendingAccountIri(IRI graph, String pubkeyHash, IRI agent) {
        String composite = graph.stringValue() + "|" + pubkeyHash + "|" + agent.stringValue() + "|pending";
        return NPAA.forHash(Hashing.sha256().hashString(composite, StandardCharsets.UTF_8).toString());
    }

    /**
     * Runs {@link #mirrorPendingAccounts} and advances the watermark, or logs and
     * carries on when the read fails.
     *
     * <p>Fail-soft is deliberate and is the opposite call from the trust mirror's:
     * pending rows are purely additive and confer no authority, so losing a pass costs
     * visibility for not-yet-approved users until the next cycle, whereas aborting the
     * cycle would stall the whole space state over a display-only feature. The
     * watermark is advanced only on success, so a failed pass is retried in full.
     *
     * @return number of rows written (0 if disabled or on read failure)
     */
    private int mirrorPendingAccountsSafely(IRI graph, boolean fromScratch) {
        long fromLoadNumber = -1;
        try {
            if (!fromScratch) fromLoadNumber = readPendingScannedUpTo(graph);
            PendingMirrorResult result = mirrorPendingAccounts(graph, fromLoadNumber);
            if (result.scannedUpTo() != fromLoadNumber) {
                writePendingScannedUpTo(graph, result.scannedUpTo());
            }
            return result.rows();
        } catch (Exception ex) {
            logger.warn("AuthorityResolver: pending-account mirror failed on {} (watermark stays at {}); "
                    + "not-yet-approved accounts stay invisible until the next cycle: {}",
                    graph, fromLoadNumber, ex.getMessage(), ex);
            return 0;
        }
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
            throw new SpaceStateUnavailableException("failed to read hasCurrentSpaceState pointer", ex);
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
                // Was "return 0", which would name the new graph <hash>_0 and make it
                // differ from the real current graph — so the build proceeded and then
                // dropped the good one. Corrupt bookkeeping must stop the build.
                throw new SpaceStateUnavailableException("non-numeric currentLoadCounter: " + v, ex);
            }
        } catch (SpaceStateUnavailableException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new SpaceStateUnavailableException("failed to read currentLoadCounter", ex);
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
            conn.begin(IsolationLevels.SNAPSHOT);
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
            conn.begin(IsolationLevels.SNAPSHOT);
            conn.prepareUpdate(QueryLanguage.SPARQL, update).execute();
            conn.commit();
        }
    }

    /**
     * Writes the pending-account watermark ({@link SpacesVocab#PENDING_SCANNED_UP_TO})
     * into the given space-state graph. Same replace-in-place shape as
     * {@link #writeProcessedUpTo}.
     */
    void writePendingScannedUpTo(IRI graph, long metaLoadNumber) {
        String update = String.format("""
                DELETE { GRAPH <%s> { <%s> <%s> ?old } }
                INSERT { GRAPH <%s> { <%s> <%s> "%d"^^<http://www.w3.org/2001/XMLSchema#long> } }
                WHERE  { OPTIONAL { GRAPH <%s> { <%s> <%s> ?old } } }
                """,
                graph, graph, SpacesVocab.PENDING_SCANNED_UP_TO,
                graph, graph, SpacesVocab.PENDING_SCANNED_UP_TO, metaLoadNumber,
                graph, graph, SpacesVocab.PENDING_SCANNED_UP_TO);
        try (RepositoryConnection conn = TripleStore.get().getRepoConnection(SPACES_REPO)) {
            conn.begin(IsolationLevels.SNAPSHOT);
            conn.prepareUpdate(QueryLanguage.SPARQL, update).execute();
            conn.commit();
        }
    }

    /**
     * Reads the pending-account watermark from the given space-state graph.
     * Returns {@code -1} when absent — which is both the never-scanned case and the
     * upgrade case (a graph built by a version without this step), and correctly makes
     * the next cycle scan every introduction.
     */
    long readPendingScannedUpTo(IRI graph) {
        String query = String.format(
                "SELECT ?n WHERE { GRAPH <%s> { <%s> <%s> ?n } }",
                graph, graph, SpacesVocab.PENDING_SCANNED_UP_TO);
        try (RepositoryConnection conn = TripleStore.get().getRepoConnection(SPACES_REPO);
             TupleQueryResult r = conn.prepareTupleQuery(QueryLanguage.SPARQL, query).evaluate()) {
            if (!r.hasNext()) return -1;
            return Long.parseLong(r.next().getBinding("n").getValue().stringValue());
        } catch (Exception ex) {
            // Unlike processedUpTo, -1 here is harmless (it re-scans), but a read that
            // failed is still a read failure: let the caller's fail-soft wrapper log it
            // rather than silently rescanning every cycle.
            throw new SpaceStateUnavailableException("failed to read pendingScannedUpTo for " + graph, ex);
        }
    }

    /**
     * Rewrites the {@link SpacesVocab#STATE_TRIPLE_COUNT} integrity stamp so it
     * equals the graph's actual triple count (stamp triple included). Runs as a
     * single transaction — delete old stamp, count, insert new stamp — so the
     * stamp is either consistent with the content it was measured against or
     * absent, never half-updated. Called after every mutation of a space-state
     * graph; {@link #tick()} verifies it and rebuilds on mismatch.
     */
    void writeStateTripleCount(IRI graph) {
        try (RepositoryConnection conn = TripleStore.get().getRepoConnection(SPACES_REPO)) {
            conn.begin(IsolationLevels.SNAPSHOT);
            conn.prepareUpdate(QueryLanguage.SPARQL, String.format(
                    "DELETE WHERE { GRAPH <%s> { <%s> <%s> ?old } }",
                    graph, graph, SpacesVocab.STATE_TRIPLE_COUNT)).execute();
            long withoutStamp;
            try (TupleQueryResult r = conn.prepareTupleQuery(QueryLanguage.SPARQL, String.format(
                    "SELECT (COUNT(*) AS ?n) WHERE { GRAPH <%s> { ?s ?p ?o } }", graph)).evaluate()) {
                withoutStamp = Long.parseLong(r.next().getBinding("n").getValue().stringValue());
            }
            // +1 for the stamp triple itself, so a plain count of the graph matches the stamp.
            conn.prepareUpdate(QueryLanguage.SPARQL, String.format(
                    "INSERT DATA { GRAPH <%s> { <%s> <%s> \"%d\"^^<http://www.w3.org/2001/XMLSchema#long> } }",
                    graph, graph, SpacesVocab.STATE_TRIPLE_COUNT, withoutStamp + 1)).execute();
            conn.commit();
        }
    }

    /**
     * Reads the {@link SpacesVocab#STATE_TRIPLE_COUNT} stamp from the given
     * space-state graph. Returns {@code -1} if absent (graph published by a
     * pre-stamp version; it becomes verifiable at its next mutation). Throws on
     * read failure — the same absent-vs-error distinction as
     * {@link #readProcessedUpTo}: a timed-out read must not look like damage.
     */
    long readStateTripleCount(IRI graph) {
        try (RepositoryConnection conn = TripleStore.get().getRepoConnection(SPACES_REPO)) {
            String query = String.format(
                    "SELECT ?n WHERE { GRAPH <%s> { <%s> <%s> ?n } }",
                    graph, graph, SpacesVocab.STATE_TRIPLE_COUNT);
            try (TupleQueryResult r = conn.prepareTupleQuery(QueryLanguage.SPARQL, query).evaluate()) {
                if (!r.hasNext()) return -1;
                return Long.parseLong(r.next().getBinding("n").getValue().stringValue());
            }
        } catch (Exception ex) {
            throw new SpaceStateUnavailableException("failed to read stateTripleCount for " + graph, ex);
        }
    }

    /**
     * Counts the triples in the given space-state graph. Throws on read failure
     * rather than returning a sentinel, for the same reason as
     * {@link #readStateTripleCount}.
     */
    long countStateGraphTriples(IRI graph) {
        try (RepositoryConnection conn = TripleStore.get().getRepoConnection(SPACES_REPO)) {
            String query = String.format(
                    "SELECT (COUNT(*) AS ?n) WHERE { GRAPH <%s> { ?s ?p ?o } }", graph);
            try (TupleQueryResult r = conn.prepareTupleQuery(QueryLanguage.SPARQL, query).evaluate()) {
                return Long.parseLong(r.next().getBinding("n").getValue().stringValue());
            }
        } catch (Exception ex) {
            throw new SpaceStateUnavailableException("failed to count triples of " + graph, ex);
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
            // Must not collapse to -1: callers read -1 as "this graph was never
            // finished" and rebuild from scratch. A timed-out read returning -1 would
            // make a healthy state look damaged and trigger a destructive rebuild.
            throw new SpaceStateUnavailableException("failed to read processedUpTo for " + graph, ex);
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
            conn.begin(IsolationLevels.SNAPSHOT);
            conn.prepareUpdate(QueryLanguage.SPARQL, update).execute();
            conn.commit();
        }
    }

    void dropGraph(IRI graph) {
        try (RepositoryConnection conn = TripleStore.get().getRepoConnection(SPACES_REPO)) {
            conn.begin(IsolationLevels.SNAPSHOT);
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
