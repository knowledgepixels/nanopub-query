package com.knowledgepixels.query;

import org.eclipse.rdf4j.common.transaction.IsolationLevels;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.QueryLanguage;
import org.eclipse.rdf4j.query.TupleQuery;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryResult;
import org.nanopub.MalformedNanopubException;
import org.nanopub.Nanopub;
import org.nanopub.NanopubImpl;
import org.nanopub.vocabulary.NPA;
import org.nanopub.vocabulary.NPX;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Periodic consistency sweep over the per-nanopub repo fan-out (issue #139).
 *
 * <p>{@link NanopubLoader#executeLoading} treats a per-shard commit acknowledgement
 * as truth: once every shard task has reported success, the {@code meta} write is
 * committed and the load counter moves on. Issue #139 (and the earlier meta-behind-full
 * incident) showed that the backend can acknowledge a shard write that never becomes
 * durable — leaving a nanopub silently missing from one shard forever, while
 * {@code full}/{@code meta} look complete. This class is the safety net: instead of
 * trusting acks, it periodically compares durable end-state across repos and re-loads
 * what is missing.
 *
 * <p>Each {@link #tick()} processes a bounded window of recently loaded nanopubs:
 * <ol>
 *   <li>Enumerate nanopubs from the <em>driver</em> repo ({@code full}, or {@code meta}
 *       when the full repo is disabled) with a load number above the persisted
 *       checkpoint. In-flight loads are never flagged: the {@code meta} stamp is the
 *       loader's completion marker ({@link NanopubLoader#executeLoading} commits it
 *       only after every other shard task succeeded), so a nanopub present in
 *       {@code meta} is verified immediately regardless of age, while a nanopub not
 *       yet in {@code meta} halts the sweep until it either completes or exceeds the
 *       {@link #GRACE_MS} patience (at which point the stalled/lost {@code meta}
 *       write is itself the incident to repair). With {@code meta} as the driver,
 *       driver membership already implies completion.</li>
 *   <li>Derive each nanopub's expected shard repos from its admin metadata
 *       ({@code npa:hasValidSignatureForPublicKeyHash}, {@code npx:hasNanopubType})
 *       using the same type filters as the loader.</li>
 *   <li>Check {@code npa:hasLoadNumber} membership per shard in batched queries.</li>
 *   <li>For any nanopub with a missing shard: reconstruct the nanopub from the driver
 *       repo's four content graphs and re-run {@link NanopubLoader#load(Nanopub, long)},
 *       which is idempotent per repo and fills exactly the missing shards (including
 *       recomputed text filter literals and spaces extractions).</li>
 *   <li>Advance the checkpoint only past nanopubs that verified clean or were
 *       repaired; on a repair failure the tick stops so the next tick retries.</li>
 * </ol>
 *
 * <p>The checkpoint is initialised to the driver repo's current maximum load number on
 * the first tick (or when the driver repo changes), so only nanopubs loaded from then
 * on are swept. Historical gaps can be swept by manually lowering the
 * {@code npa:hasReconciliationCheckpoint} value in the admin repo.
 *
 * <p>Known limitations: nanopubs missing from the driver repo itself are not
 * detectable here (the registry resync path remains the authority for that), and the
 * {@code last30d} repo is not checked (its content expires anyway). The {@code spaces}
 * shard is only expected via the {@link SpacesExtractor#isSpaceRelevant} trigger-type
 * proxy; a trigger-typed nanopub whose extraction turns out empty is logged and
 * treated as consistent after re-verification.
 */
public class ShardReconciler {

    private static final Logger logger = LoggerFactory.getLogger(ShardReconciler.class);

    private static final ValueFactory vf = SimpleValueFactory.getInstance();

    static final IRI HAS_RECONCILIATION_CHECKPOINT = vf.createIRI(NPA.NAMESPACE + "hasReconciliationCheckpoint");
    // @ADMIN-TRIPLE-TABLE@ REPO, npa:hasReconciliationCheckpoint, LOAD_NUMBER, npa:graph, admin, driver-repo load number up to which shard consistency has been verified
    static final IRI HAS_RECONCILIATION_DRIVER = vf.createIRI(NPA.NAMESPACE + "hasReconciliationDriver");
    // @ADMIN-TRIPLE-TABLE@ REPO, npa:hasReconciliationDriver, REPO_NAME, npa:graph, admin, repo the reconciliation checkpoint refers to

    /**
     * Patience for nanopubs that are in the driver repo but not yet stamped in
     * {@code meta}: their fan-out is normally still in flight (the meta task is
     * deferred behind the other shards, and per-shard retry chains can run for
     * minutes), so the sweep halts in front of them instead of racing the loader
     * with spurious repairs. Once a nanopub's driver-repo load timestamp is older
     * than this, a missing {@code meta} stamp is no longer plausible in-flight
     * state and is treated as a lost shard write to repair. Nanopubs already in
     * {@code meta} are verified immediately — completion is proven, not timed.
     */
    static final long GRACE_MS = 30L * 60 * 1000;

    /**
     * Upper bound on nanopubs examined per tick, so a large backlog (e.g. after
     * downtime) drains incrementally instead of producing one huge query.
     */
    static final int MAX_NPS_PER_TICK = 1000;

    /**
     * Cumulative count of nanopubs whose shard fan-out was verified (clean or
     * repaired) since process start. Read by {@link MetricsCollector}.
     */
    static volatile long checkedNanopubCount = 0;

    /**
     * Cumulative count of shard repos found missing and successfully re-loaded
     * since process start. Read by {@link MetricsCollector}. Any non-zero value
     * is evidence of the backend acknowledging a non-durable write.
     */
    static volatile long repairedShardCount = 0;

    private ShardReconciler() {
    }

    /**
     * Runs one bounded reconciliation pass. Never throws: any failure is logged
     * and retried on the next tick (the checkpoint only advances past verified
     * nanopubs). No-op unless the reconciliation feature is enabled and the
     * service is READY.
     */
    public static void tick() {
        if (!FeatureFlags.reconciliationEnabled()) {
            return;
        }
        if (StatusController.get().getState().state != StatusController.State.READY) {
            return;
        }
        try {
            runTick();
        } catch (Exception ex) {
            logger.warn("Shard reconciliation tick failed; will retry on next tick: {}", ex.getMessage(), ex);
        }
    }

    private static void runTick() {
        String driverRepo = FeatureFlags.fullRepoEnabled() ? "full" : "meta";

        Long checkpoint = readCheckpoint(driverRepo);
        if (checkpoint == null) {
            // First run (or driver repo changed): establish the baseline at the
            // driver's current max load number. Only nanopubs loaded after this
            // point are swept.
            long max = fetchMaxLoadNumber(driverRepo);
            persistCheckpoint(driverRepo, max);
            logger.info("Shard reconciliation checkpoint initialized at load number {} of repo '{}'", max, driverRepo);
            return;
        }

        List<WindowEntry> window = fetchWindow(driverRepo, checkpoint);
        if (window.isEmpty()) {
            return;
        }
        Map<IRI, NanopubShardInfo> shardInfos = fetchShardInfos(driverRepo, window);

        // Batched membership lookup: one query per distinct shard repo, covering
        // all window nanopubs that expect it.
        Map<String, List<IRI>> npsByShard = new LinkedHashMap<>();
        for (WindowEntry e : window) {
            NanopubShardInfo info = shardInfos.get(e.npId());
            if (info == null) {
                continue;
            }
            for (String repo : expectedShardRepos(e.npId(), info.pubkeyHash(), info.types(), driverRepo)) {
                npsByShard.computeIfAbsent(repo, k -> new ArrayList<>()).add(e.npId());
            }
        }
        Map<String, Set<IRI>> presentByShard = new HashMap<>();
        for (Map.Entry<String, List<IRI>> e : npsByShard.entrySet()) {
            presentByShard.put(e.getKey(), fetchPresentNanopubs(e.getKey(), e.getValue()));
        }

        long newCheckpoint = checkpoint;
        long checked = 0;
        long repaired = 0;
        try {
            for (WindowEntry e : window) {
                // Completion gate: meta is written last, so a nanopub already in meta
                // has finished its fan-out and can be verified immediately regardless
                // of age. A nanopub not yet in meta is normally an in-flight load —
                // halt the sweep in front of it (checkpoint stays put, next tick
                // re-examines) until it either completes or exceeds the patience
                // window, after which the missing meta stamp is itself the incident
                // and falls through to the regular repair path.
                boolean completed = "meta".equals(driverRepo)
                        || presentByShard.getOrDefault("meta", Set.of()).contains(e.npId());
                if (!completed && System.currentTimeMillis() - e.loadedAtMs() < GRACE_MS) {
                    logger.debug("Halting shard sweep at <{}> (load number {}): not yet in meta, likely in flight", e.npId(), e.loadNumber());
                    return;
                }
                NanopubShardInfo info = shardInfos.get(e.npId());
                if (info == null) {
                    // No pubkey-hash admin triple in the driver repo; nothing we can
                    // derive shards from. Loud, but don't stall the sweep on it.
                    logger.warn("Skipping shard reconciliation for <{}>: no npa:hasValidSignatureForPublicKeyHash found in repo '{}'", e.npId(), driverRepo);
                    newCheckpoint = e.loadNumber();
                    continue;
                }
                List<String> missing = new ArrayList<>();
                for (String repo : expectedShardRepos(e.npId(), info.pubkeyHash(), info.types(), driverRepo)) {
                    if (!presentByShard.getOrDefault(repo, Set.of()).contains(e.npId())) {
                        missing.add(repo);
                    }
                }
                if (!missing.isEmpty()) {
                    if (!repairNanopub(driverRepo, e.npId(), missing)) {
                        // Repair failed: stop here so the checkpoint stays before this
                        // nanopub and the next tick retries.
                        return;
                    }
                    repaired += missing.size();
                }
                newCheckpoint = e.loadNumber();
                checked++;
            }
        } finally {
            checkedNanopubCount += checked;
            repairedShardCount += repaired;
            if (newCheckpoint > checkpoint) {
                persistCheckpoint(driverRepo, newCheckpoint);
            }
        }
        if (repaired > 0) {
            logger.warn("Shard reconciliation repaired {} missing shard(s) across {} nanopub(s); the backend acknowledged non-durable writes (see issue #139)", repaired, checked);
        } else {
            logger.debug("Shard reconciliation verified {} nanopub(s) up to load number {} of repo '{}'", checked, newCheckpoint, driverRepo);
        }
    }

    /**
     * Derives the set of shard repos the loader is expected to have populated for
     * a nanopub, from its admin metadata: {@code meta}, {@code full}/{@code text}
     * (feature-flagged), the signer's pubkey repo, one type repo per eligible
     * computed type (same exclusions as {@link NanopubLoader#executeLoading}:
     * locally-minted IRIs and non-http(s) IRIs are skipped), and {@code spaces}
     * for trigger-typed nanopubs. The driver repo itself is excluded — membership
     * there is what put the nanopub in the window. {@code last30d} is not checked
     * (expiring content).
     */
    static Set<String> expectedShardRepos(IRI npId, String pubkeyHash, Set<IRI> types, String driverRepo) {
        Set<String> repos = new LinkedHashSet<>();
        repos.add("meta");
        if (FeatureFlags.fullRepoEnabled()) {
            repos.add("full");
        }
        if (FeatureFlags.textRepoEnabled()) {
            repos.add("text");
        }
        repos.add("pubkey_" + pubkeyHash);
        for (IRI typeIri : types) {
            if (typeIri.stringValue().startsWith(npId.stringValue())) {
                continue;
            }
            if (!typeIri.stringValue().matches("https?://.*")) {
                continue;
            }
            repos.add("type_" + Utils.createHash(typeIri));
        }
        if (FeatureFlags.spacesEnabled() && SpacesExtractor.isSpaceRelevant(types)) {
            repos.add("spaces");
        }
        repos.remove(driverRepo);
        return repos;
    }

    /**
     * Re-loads a nanopub with missing shards: reconstructs it from the driver
     * repo's four content graphs and re-runs the full (idempotent) load, then
     * re-verifies the shards that were missing. A {@code spaces} shard that is
     * still missing after a successful re-load means the trigger-type proxy
     * over-approximated (empty extraction) — logged and treated as consistent.
     *
     * @return true if all missing shards are accounted for afterwards
     */
    private static boolean repairNanopub(String driverRepo, IRI npId, List<String> missing) {
        logger.warn("Nanopub <{}> is missing from shard repo(s) {}; re-loading (see issue #139)", npId, missing);
        Nanopub np;
        try {
            np = reconstructNanopub(driverRepo, npId);
        } catch (Exception ex) {
            logger.warn("Could not reconstruct nanopub <{}> from repo '{}': {}", npId, driverRepo, ex.getMessage(), ex);
            return false;
        }
        try {
            NanopubLoader.load(np, -1);
        } catch (Exception ex) {
            logger.warn("Re-load of nanopub <{}> failed: {}", npId, ex.getMessage(), ex);
            return false;
        }
        boolean allRepaired = true;
        for (String repo : missing) {
            if (fetchPresentNanopubs(repo, List.of(npId)).contains(npId)) {
                logger.warn("Repaired: nanopub <{}> re-loaded into shard repo '{}'", npId, repo);
            } else if ("spaces".equals(repo)) {
                logger.info("Nanopub <{}> still absent from 'spaces' after re-load; its extraction is empty, treating as consistent", npId);
            } else {
                logger.warn("Nanopub <{}> still missing from shard repo '{}' after re-load", npId, repo);
                allRepaired = false;
            }
        }
        return allRepaired;
    }

    /**
     * Rebuilds a {@link Nanopub} object from the four content graphs stored in the
     * given repo, discovered via {@code <npId> npa:hasGraph ?g} in {@code npa:graph}.
     */
    static Nanopub reconstructNanopub(String repoName, IRI npId) throws MalformedNanopubException {
        List<Statement> content = new ArrayList<>();
        try (RepositoryConnection conn = TripleStore.get().getRepoConnection(repoName)) {
            List<IRI> graphs = new ArrayList<>();
            try (RepositoryResult<Statement> r = conn.getStatements(npId, NPA.HAS_GRAPH, null, NPA.GRAPH)) {
                while (r.hasNext()) {
                    Value o = r.next().getObject();
                    if (o instanceof IRI iri) {
                        graphs.add(iri);
                    }
                }
            }
            for (IRI g : graphs) {
                try (RepositoryResult<Statement> r = conn.getStatements(null, null, null, g)) {
                    while (r.hasNext()) {
                        content.add(r.next());
                    }
                }
            }
        }
        return new NanopubImpl(content);
    }

    private record WindowEntry(IRI npId, long loadNumber, long loadedAtMs) {
    }

    private record NanopubShardInfo(String pubkeyHash, Set<IRI> types) {
    }

    /**
     * Fetches the next batch of nanopubs to verify: load number above the
     * checkpoint, ordered by load number, capped at {@link #MAX_NPS_PER_TICK}.
     * No age filter here — completion gating against {@code meta} (or the
     * {@link #GRACE_MS} patience for meta-pending nanopubs) happens in the
     * sweep loop, where it can halt the checkpoint precisely.
     */
    private static List<WindowEntry> fetchWindow(String driverRepo, long checkpoint) {
        List<WindowEntry> window = new ArrayList<>();
        try (RepositoryConnection conn = TripleStore.get().getRepoConnection(driverRepo)) {
            TupleQuery q = conn.prepareTupleQuery(QueryLanguage.SPARQL,
                    "SELECT ?np ?ln ?ts WHERE { graph <" + NPA.GRAPH + "> { "
                    + "?np <" + NPA.HAS_LOAD_NUMBER + "> ?ln ; <" + NPA.HAS_LOAD_TIMESTAMP + "> ?ts . "
                    + "FILTER (?ln > " + checkpoint + ") "
                    + "} } ORDER BY ?ln LIMIT " + MAX_NPS_PER_TICK);
            try (TupleQueryResult r = q.evaluate()) {
                while (r.hasNext()) {
                    BindingSet b = r.next();
                    if (b.getValue("np") instanceof IRI npId && b.getValue("ts") instanceof Literal tsLit) {
                        window.add(new WindowEntry(npId, Long.parseLong(b.getValue("ln").stringValue()),
                                tsLit.calendarValue().toGregorianCalendar().getTimeInMillis()));
                    }
                }
            }
        }
        return window;
    }

    /**
     * Fetches pubkey hash and computed types for the window's nanopubs from the
     * driver repo's admin graph in one query.
     */
    private static Map<IRI, NanopubShardInfo> fetchShardInfos(String driverRepo, List<WindowEntry> window) {
        Map<IRI, NanopubShardInfo> result = new HashMap<>();
        try (RepositoryConnection conn = TripleStore.get().getRepoConnection(driverRepo)) {
            TupleQuery q = conn.prepareTupleQuery(QueryLanguage.SPARQL,
                    "SELECT ?np ?ph ?t WHERE { graph <" + NPA.GRAPH + "> { "
                    + valuesClause(window.stream().map(WindowEntry::npId).toList())
                    + "?np <" + NPA.HAS_VALID_SIGNATURE_FOR_PUBLIC_KEY_HASH + "> ?ph . "
                    + "OPTIONAL { ?np <" + NPX.HAS_NANOPUB_TYPE + "> ?t } "
                    + "} }");
            try (TupleQueryResult r = q.evaluate()) {
                while (r.hasNext()) {
                    BindingSet b = r.next();
                    if (!(b.getValue("np") instanceof IRI npId)) {
                        continue;
                    }
                    NanopubShardInfo info = result.computeIfAbsent(npId,
                            k -> new NanopubShardInfo(b.getValue("ph").stringValue(), new LinkedHashSet<>()));
                    if (b.getValue("t") instanceof IRI typeIri) {
                        info.types().add(typeIri);
                    }
                }
            }
        }
        return result;
    }

    /**
     * Returns which of the given nanopubs carry an {@code npa:hasLoadNumber} stamp
     * in the given repo's admin graph.
     */
    private static Set<IRI> fetchPresentNanopubs(String repoName, List<IRI> npIds) {
        Set<IRI> present = new HashSet<>();
        try (RepositoryConnection conn = TripleStore.get().getRepoConnection(repoName)) {
            TupleQuery q = conn.prepareTupleQuery(QueryLanguage.SPARQL,
                    "SELECT ?np WHERE { graph <" + NPA.GRAPH + "> { "
                    + valuesClause(npIds)
                    + "?np <" + NPA.HAS_LOAD_NUMBER + "> ?ln . "
                    + "} }");
            try (TupleQueryResult r = q.evaluate()) {
                while (r.hasNext()) {
                    BindingSet b = r.next();
                    if (b.getValue("np") instanceof IRI npId) {
                        present.add(npId);
                    }
                }
            }
        }
        return present;
    }

    private static String valuesClause(List<IRI> npIds) {
        StringBuilder sb = new StringBuilder("VALUES ?np { ");
        for (IRI npId : npIds) {
            sb.append("<").append(npId.stringValue()).append("> ");
        }
        sb.append("} ");
        return sb.toString();
    }

    private static long fetchMaxLoadNumber(String repoName) {
        try (RepositoryConnection conn = TripleStore.get().getRepoConnection(repoName)) {
            TupleQuery q = conn.prepareTupleQuery(QueryLanguage.SPARQL,
                    "SELECT (MAX(?ln) AS ?maxLn) WHERE { graph <" + NPA.GRAPH + "> { "
                    + "?np <" + NPA.HAS_LOAD_NUMBER + "> ?ln . } }");
            try (TupleQueryResult r = q.evaluate()) {
                if (r.hasNext()) {
                    Value v = r.next().getValue("maxLn");
                    if (v != null) {
                        return Long.parseLong(v.stringValue());
                    }
                }
            }
        }
        return -1;
    }

    /**
     * Reads the persisted checkpoint from the admin repo, or {@code null} if none
     * exists yet or it was recorded against a different driver repo.
     */
    private static Long readCheckpoint(String driverRepo) {
        try (RepositoryConnection conn = TripleStore.get().getAdminRepoConnection()) {
            Value driver = Utils.getObjectForPattern(conn, NPA.GRAPH, NPA.THIS_REPO, HAS_RECONCILIATION_DRIVER);
            Value cp = Utils.getObjectForPattern(conn, NPA.GRAPH, NPA.THIS_REPO, HAS_RECONCILIATION_CHECKPOINT);
            if (driver == null || cp == null || !driverRepo.equals(driver.stringValue())) {
                return null;
            }
            return Long.parseLong(cp.stringValue());
        } catch (NumberFormatException ex) {
            logger.warn("Malformed npa:hasReconciliationCheckpoint literal in admin repo: {}", ex.getMessage());
            return null;
        }
    }

    private static void persistCheckpoint(String driverRepo, long checkpoint) {
        try (RepositoryConnection conn = TripleStore.get().getAdminRepoConnection()) {
            conn.begin(IsolationLevels.SERIALIZABLE);
            conn.remove(NPA.THIS_REPO, HAS_RECONCILIATION_DRIVER, null, NPA.GRAPH);
            conn.remove(NPA.THIS_REPO, HAS_RECONCILIATION_CHECKPOINT, null, NPA.GRAPH);
            conn.add(NPA.THIS_REPO, HAS_RECONCILIATION_DRIVER, vf.createLiteral(driverRepo), NPA.GRAPH);
            conn.add(NPA.THIS_REPO, HAS_RECONCILIATION_CHECKPOINT, vf.createLiteral(checkpoint), NPA.GRAPH);
            conn.commit();
        }
    }

}
