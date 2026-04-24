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

        // 2. Per-tier UPDATE loops (from scratch: lastProcessed = -1 so the
        //    delta filter FILTER(?ln > ?lastProcessed) includes everything).
        TierCounts counts = runAllTierLoops(newGraph, -1);

        // 3. Stamp processedUpTo inside the new graph.
        writeProcessedUpTo(newGraph, loadCounter);

        // 4. Flip the current-space-state pointer.
        flipPointer(newGraph);

        // 5. Drop the old graph if one existed.
        if (oldGraph != null) {
            dropGraph(oldGraph);
        }

        log.info("AuthorityResolver: full build complete — graph={} mirrored={} rows loadCounter={} "
                        + "tiers: admin={} attachment={} maintainer={} member={} observer={}",
                newGraph, mirrored, loadCounter,
                counts.admin, counts.attachment, counts.maintainer, counts.member, counts.observer);
    }

    // ---------------- Tier UPDATE loops ----------------

    /** Per-tier INSERT counts (for logging/metrics). */
    static final class TierCounts {
        int admin;
        int attachment;
        int maintainer;
        int member;
        int observer;
    }

    /**
     * Runs the five tier loops in order: admin → {@code gen:hasRole} attachment
     * validation → maintainer → member → observer. Each loop iterates a SPARQL
     * INSERT to fixed point (no new triples added). Returns per-tier counts.
     *
     * @param graph         target space-state graph
     * @param lastProcessed load-number horizon; use {@code -1} for full build
     */
    TierCounts runAllTierLoops(IRI graph, long lastProcessed) {
        TierCounts c = new TierCounts();
        c.admin = runTierLabeled("admin", graph, adminTierUpdate(graph, lastProcessed));
        c.attachment = runTierLabeled("attachment", graph,
                attachmentValidationUpdate(graph, lastProcessed));
        c.maintainer = runTierLabeled("maintainer", graph, nonAdminTierUpdate(graph, lastProcessed,
                GEN.MAINTAINER_ROLE, PUBLISHER_IS_ADMIN));
        c.member = runTierLabeled("member", graph, nonAdminTierUpdate(graph, lastProcessed,
                GEN.MEMBER_ROLE, PUBLISHER_IS_ADMIN_OR_MAINTAINER));
        c.observer = runTierLabeled("observer", graph, nonAdminTierUpdate(graph, lastProcessed,
                GEN.OBSERVER_ROLE, PUBLISHER_IS_SELF_OR_TIERED));
        return c;
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
            try (RepositoryConnection conn = TripleStore.get().getRepoConnection(SPACES_REPO)) {
                conn.begin(IsolationLevels.SERIALIZABLE);
                conn.prepareUpdate(QueryLanguage.SPARQL, sparqlUpdate).execute();
                conn.commit();
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

    // ---------------- SPARQL templates ----------------

    /**
     * Reusable invalidation filter on a bound nanopub-IRI variable. Pass the bare
     * variable name (no leading {@code ?}); e.g. {@code invalidationFilter("np")}
     * produces {@code FILTER NOT EXISTS { ?_inv_np a npa:Invalidation ; npa:invalidates ?np . }}.
     * Variable names must match {@code [A-Za-z0-9_]+} per SPARQL grammar — embedding
     * a {@code ?} inside {@code ?_inv_?np} would yield a parse error.
     */
    private static String invalidationFilter(String bareVarName) {
        return "FILTER NOT EXISTS { ?_inv_" + bareVarName
                + " a <" + SpacesVocab.INVALIDATION + "> ; "
                + "<" + SpacesVocab.INVALIDATES + "> ?" + bareVarName + " . }";
    }

    /**
     * Admin tier: seed from {@code npadef:...hasRootAdmin} (trusted by construction)
     * plus closed-over admin grants; insert any {@code gen:RoleInstantiation} with
     * {@code npa:regularProperty gen:hasAdmin} whose publisher (resolved via mirrored
     * trust-approved AccountState) is already in the admin set.
     */
    static String adminTierUpdate(IRI graph, long lastProcessed) {
        // npa:hasLoadNumber lives in the admin graph (NPA.GRAPH), NOT in
        // npa:spacesGraph — NanopubLoader writes the stamp there. Every tier
        // template below joins the delta filter via a separate GRAPH <npa:graph>
        // block. Invalidation entries DO live in npa:spacesGraph.
        return """
                PREFIX npa:  <%1$s>
                PREFIX gen:  <%2$s>
                INSERT { GRAPH <%3$s> {
                  ?ri a gen:RoleInstantiation ;
                      npa:forSpace ?space ;
                      npa:regularProperty gen:hasAdmin ;
                      npa:forAgent ?agent ;
                      npa:viaNanopub ?np .
                } }
                WHERE {
                  GRAPH <%4$s> {
                    ?ri a gen:RoleInstantiation ;
                        npa:forSpace        ?space ;
                        npa:regularProperty gen:hasAdmin ;
                        npa:forAgent        ?agent ;
                        npa:pubkeyHash      ?pkh ;
                        npa:viaNanopub      ?np .
                    %6$s
                  }
                  GRAPH <%8$s> {
                    ?np npa:hasLoadNumber ?ln .
                    FILTER (?ln > %5$d)
                  }
                  {
                    # Seed branch: root-admin in a non-invalidated SpaceDefinition.
                    GRAPH <%4$s> {
                      ?def a npa:SpaceDefinition ;
                           npa:forSpaceRef ?spaceRef ;
                           npa:hasRootAdmin ?publisher ;
                           npa:viaNanopub   ?defNp .
                      ?spaceRef npa:spaceIri ?space .
                      %7$s
                    }
                  }
                  UNION
                  {
                    # Closed-over branch: an existing admin in this space-state graph.
                    GRAPH <%3$s> {
                      ?prev a gen:RoleInstantiation ;
                            npa:forSpace ?space ;
                            npa:regularProperty gen:hasAdmin ;
                            npa:forAgent ?publisher .
                    }
                  }
                  GRAPH <%3$s> {
                    ?acct a npa:AccountState ;
                          npa:agent  ?publisher ;
                          npa:pubkey ?pkh .
                  }
                  FILTER NOT EXISTS { GRAPH <%3$s> {
                    ?existing a gen:RoleInstantiation ;
                              npa:forSpace ?space ;
                              npa:forAgent ?agent ;
                              npa:regularProperty gen:hasAdmin .
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
                    %6$s
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
                             npa:regularProperty gen:hasAdmin ;
                             npa:forAgent ?publisher .
                  }
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

    /** Non-admin tier publisher constraints (inserted as a SPARQL sub-pattern). */
    static final String PUBLISHER_IS_ADMIN = """
            ?adminRI a gen:RoleInstantiation ;
                     npa:forSpace ?space ;
                     npa:regularProperty gen:hasAdmin ;
                     npa:forAgent ?publisher .
            """;

    static final String PUBLISHER_IS_ADMIN_OR_MAINTAINER = """
            {
              ?adminRI a gen:RoleInstantiation ;
                       npa:forSpace ?space ;
                       npa:regularProperty gen:hasAdmin ;
                       npa:forAgent ?publisher .
            }
            UNION
            {
              ?maintRI a gen:RoleInstantiation ;
                       npa:forSpace ?space ;
                       npa:forAgent ?publisher .
              ?rdM a npa:RoleDeclaration ;
                   npa:hasRoleType gen:MaintainerRole .
              { ?maintRI npa:regularProperty ?predM . ?rdM gen:hasRegularProperty ?predM . }
              UNION
              { ?maintRI npa:inverseProperty ?predM . ?rdM gen:hasInverseProperty ?predM . }
            }
            """;

    /**
     * Observer self-evidence: publisher is admin, maintainer, member, OR the
     * mirrored trust row confirms that the signing pubkey maps to the assignee
     * itself (i.e. the assignee is the publisher).
     */
    static final String PUBLISHER_IS_SELF_OR_TIERED = """
            {
              ?adminRI a gen:RoleInstantiation ;
                       npa:forSpace ?space ;
                       npa:regularProperty gen:hasAdmin ;
                       npa:forAgent ?publisher .
            }
            UNION
            {
              ?maintRI a gen:RoleInstantiation ;
                       npa:forSpace ?space ;
                       npa:forAgent ?publisher .
              ?rdM a npa:RoleDeclaration ;
                   npa:hasRoleType gen:MaintainerRole .
              { ?maintRI npa:regularProperty ?predM . ?rdM gen:hasRegularProperty ?predM . }
              UNION
              { ?maintRI npa:inverseProperty ?predM . ?rdM gen:hasInverseProperty ?predM . }
            }
            UNION
            {
              ?memRI a gen:RoleInstantiation ;
                     npa:forSpace ?space ;
                     npa:forAgent ?publisher .
              ?rdMem a npa:RoleDeclaration ;
                     npa:hasRoleType gen:MemberRole .
              { ?memRI npa:regularProperty ?predMem . ?rdMem gen:hasRegularProperty ?predMem . }
              UNION
              { ?memRI npa:inverseProperty ?predMem . ?rdMem gen:hasInverseProperty ?predMem . }
            }
            UNION
            {
              # Self-evidence: publisher pubkey maps to the assignee.
              FILTER (?publisher = ?agent)
            }
            """;

    /**
     * Maintainer / Member / Observer tier INSERT. Same shape: find an instantiation
     * whose predicate matches a RoleDeclaration of the given tier attached to the
     * target space, and whose publisher passes the tier-specific constraint.
     */
    static String nonAdminTierUpdate(IRI graph, long lastProcessed,
                                     IRI tierClass, String publisherConstraint) {
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
                  GRAPH <%4$s> {
                    ?ri a gen:RoleInstantiation ;
                        npa:forSpace ?space ;
                        npa:forAgent ?agent ;
                        npa:pubkeyHash ?pkh ;
                        npa:viaNanopub ?np .
                    { ?ri npa:regularProperty ?pred . }
                    UNION
                    { ?ri npa:inverseProperty ?pred . }
                    %6$s
                    # Predicate maps to a RoleDeclaration of this tier (not invalidated).
                    ?rd a npa:RoleDeclaration ;
                        npa:role ?role ;
                        npa:hasRoleType <%7$s> ;
                        npa:viaNanopub ?rdNp .
                    { ?rd gen:hasRegularProperty ?pred . }
                    UNION
                    { ?rd gen:hasInverseProperty ?pred . }
                    %8$s
                  }
                  GRAPH <%10$s> {
                    ?np npa:hasLoadNumber ?ln .
                    FILTER (?ln > %5$d)
                  }
                  GRAPH <%3$s> {
                    # Role must be admin-attached to the target space.
                    ?ra a gen:RoleAssignment ;
                        npa:forSpace ?space ;
                        gen:hasRole  ?role .
                    # Publisher's agent from the mirrored trust-approved set.
                    ?acct a npa:AccountState ;
                          npa:agent ?publisher ;
                          npa:pubkey ?pkh .
                    %9$s
                  }
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
