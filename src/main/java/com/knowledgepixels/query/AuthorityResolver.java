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

import com.knowledgepixels.query.vocabulary.NPAT;
import com.knowledgepixels.query.vocabulary.SpacesVocab;

/**
 * Drives the space-state materialization pipeline: detects trust-state flips,
 * mirrors the approved {@code (agent, pubkey)} rows from the {@code trust} repo
 * into a fresh {@code npass:<T>_<M>} graph in the {@code spaces} repo, runs the
 * per-tier validation loops (stubbed in PR 2a), flips the
 * {@code npa:hasCurrentSpaceState} pointer, and drops the previous graph. Also
 * cleans up orphan {@code npass:*} graphs on startup.
 *
 * <p>See {@code doc/plan-space-repositories.md} — this implements the "Full
 * build" procedure plus pointer management and the mirror step. Per-tier SPARQL
 * UPDATE loops, the incremental cycle, and the periodic-rebuild flag follow in
 * PRs 2b and 2c.
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
     * Poll entry point: checks the current trust-state hash against the active
     * space-state graph's hash component; if they differ (or no space-state graph
     * exists yet), runs a full build. Safe to call repeatedly on a schedule —
     * when the hashes match, it's a no-op. Gated by
     * {@link FeatureFlags#spacesEnabled()}.
     */
    public void tick() {
        if (!FeatureFlags.spacesEnabled()) return;
        String trustStateHash = TrustStateRegistry.get().getCurrentHash().orElse(null);
        if (trustStateHash == null) {
            log.debug("AuthorityResolver.tick: no current trust state yet — skipping");
            return;
        }
        String currentGraphName = getCurrentSpaceStateGraphLocalName();
        if (currentGraphName != null && currentGraphName.startsWith(trustStateHash + "_")) {
            log.debug("AuthorityResolver.tick: already on trust state {}", abbrev(trustStateHash));
            return;
        }
        log.info("AuthorityResolver.tick: trust-state flip detected (now {}); running full build",
                abbrev(trustStateHash));
        runFullBuild(trustStateHash);
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

        // 2. PR 2b placeholder: per-tier UPDATE loops would run here.

        // 3. Stamp processedUpTo inside the new graph.
        writeProcessedUpTo(newGraph, loadCounter);

        // 4. Flip the current-space-state pointer.
        flipPointer(newGraph);

        // 5. Drop the old graph if one existed.
        if (oldGraph != null) {
            dropGraph(oldGraph);
        }

        log.info("AuthorityResolver: full build complete — graph={} mirrored={} rows loadCounter={}",
                newGraph, mirrored, loadCounter);
    }

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

    /** Convenience: local-name of the current space-state graph IRI. */
    private String getCurrentSpaceStateGraphLocalName() {
        IRI iri = getCurrentSpaceStateGraph();
        if (iri == null) return null;
        String s = iri.stringValue();
        if (!s.startsWith(SpacesVocab.NPASS_NAMESPACE)) return null;
        return s.substring(SpacesVocab.NPASS_NAMESPACE.length());
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
