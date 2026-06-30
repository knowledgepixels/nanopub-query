package com.knowledgepixels.query;

import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.methods.RequestBuilder;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.BasicResponseHandler;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import com.google.common.hash.Hashing;
import org.eclipse.rdf4j.common.transaction.IsolationLevels;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.base.RepositoryConnectionWrapper;
import org.eclipse.rdf4j.repository.http.HTTPRepository;
import org.nanopub.NanopubUtils;
import org.nanopub.vocabulary.NPA;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Class to access the database in the form of triple stores.
 */
public class TripleStore {

    /**
     * Name of the admin graph.
     */
    public static final String ADMIN_REPO = "admin";

    private static ValueFactory vf = SimpleValueFactory.getInstance();

    private static final Logger logger = LoggerFactory.getLogger(TripleStore.class);

    private final Map<String, Repository> repositories = new LinkedHashMap<>();

    /**
     * Per-repo open-connection counter, read by the eviction loop to skip repos that
     * still have live connections. Incremented in {@link #getRepoConnection(String)}
     * just before the connection is handed out and decremented via a
     * {@link RepositoryConnectionWrapper} that intercepts {@code close()} exactly once.
     */
    private final ConcurrentHashMap<String, AtomicInteger> openConnections = new ConcurrentHashMap<>();

    /**
     * Handles evicted from {@link #repositories} but not yet shut down. Eviction is
     * deferred and idle-only: calling {@link HTTPRepository#shutDown()} closes the
     * session manager and kills any live (possibly streaming) server-side transaction
     * with the {@code MMapIndexInput - Already closed} error, which can wedge the
     * underlying LMDB store on the server for <em>every</em> client. Instead of shutting
     * a handle down the instant its app-side connection count hits zero — a count that
     * can momentarily read zero between operations, or undercount a result set still
     * being streamed — we park it here and only shut it down from
     * {@link #reapPendingShutdowns()} once it has stayed idle for {@link #SHUTDOWN_GRACE_MS}.
     * Guarded by the {@code this} monitor, like {@link #repositories}.
     */
    private final Map<String, Repository> pendingShutdown = new LinkedHashMap<>();
    private final Map<String, Long> pendingShutdownSince = new HashMap<>();

    /** Idle grace period before a parked handle is actually shut down. */
    private static final long SHUTDOWN_GRACE_MS = 60_000;

    /**
     * Repos that are never evicted. The core named repos plus the view-critical type
     * repos: ResourceView and ViewDisplay are queried and federated into constantly by
     * the spaces/view UI, so they churn through the {@code cap 100} LRU fastest and were
     * the handles most exposed to the shutdown-during-use race. Pinning a handful of
     * them is cheap (a few always-warm LMDB envs) and removes the thrash on exactly the
     * repos that wedge.
     */
    private static final Set<String> PINNED_REPO_NAMES = buildPinnedRepoNames();

    private static Set<String> buildPinnedRepoNames() {
        Set<String> names = new HashSet<>(Arrays.asList("admin", "empty", "meta", "full", "spaces", "last30d"));
        for (String typeIri : new String[] {
                "https://w3id.org/kpxl/gen/terms/ResourceView",
                "https://w3id.org/kpxl/gen/terms/ViewDisplay" }) {
            names.add("type_" + Hashing.sha256().hashString(typeIri, StandardCharsets.UTF_8).toString());
        }
        return Collections.unmodifiableSet(names);
    }

    private String endpointBase = null;
    private String endpointType = null;

    private static TripleStore tripleStoreInstance;

    /**
     * Returns singleton triple store instance.
     *
     * @return Triple store instance
     */
    public static TripleStore get() {
        if (tripleStoreInstance == null) {
            try {
                tripleStoreInstance = new TripleStore();
            } catch (IOException ex) {
                logger.info("Could not init TripleStore. ", ex);
            }
        }
        return tripleStoreInstance;
    }

    private TripleStore() throws IOException {
        // Read directly from the JVM's in-memory environment block rather than via
        // Apache Commons Exec's EnvironmentUtils.getProcEnvironment(), which forks a
        // subprocess and can throw under memory pressure / a restart storm — leaving
        // the singleton uninitialised. Same fragile mechanism that caused issue #117
        // on the per-nanopub hot path (see Utils.getEnvString); retired here too.
        endpointBase = System.getenv("ENDPOINT_BASE");
        logger.info("Endpoint base: {}", endpointBase);
        endpointType = System.getenv("ENDPOINT_TYPE");

        getRepository("empty");  // Make sure empty repo exists
    }

    /**
     * Shared HTTP client for all RDF4J traffic. Apache HttpClient treats all requests
     * to a given host + port + protocol as one "route", so every `HTTPRepository` in
     * this process funnels through this single client's connection pool — plus the
     * two ad-hoc users in {@link #createRepo} and {@link #getRepositoryNames}, which
     * have been consolidated onto this client too.
     *
     * <p>The Apache defaults (maxPerRoute=2 / maxTotal=20) throttle four concurrent
     * loader-pool threads down to two-way parallelism at the HTTP layer — invisible
     * client-side serialisation the code is actively fighting. Raised to 10 / 40
     * here: comfortable headroom for the 4-thread loader pool plus admin-repo
     * transactions and the metrics tick, small enough to be a conservative first
     * step with room to grow later.
     *
     * <p>Timeouts set via {@code setDefaultRequestConfig}:
     * <ul>
     *   <li><b>socket-read = 60 s</b> — an individual HTTP response body must arrive
     *       within this window. Healthy per-nanopub commits complete in milliseconds,
     *       so 60 s is pure safety margin; its job is to turn the silent "threads
     *       parked forever inside a commit" wedge (observed in the April test) into
     *       a recoverable error that feeds the existing retry path.</li>
     *   <li><b>connection-request = 30 s</b> — a caller waiting for a pooled connection
     *       gives up after this long. Prevents the invisible self-deadlock in
     *       {@code loadInvalidateStatements} (one thread holding N connections while
     *       waiting for an (N+1)th) from hanging forever.</li>
     *   <li><b>connect = 10 s</b> — kills TCP handshakes that stall. Generous but
     *       bounded.</li>
     * </ul>
     * Without these defaults, HttpClient uses {@code -1} everywhere, which means
     * "wait forever".
     */
    private final CloseableHttpClient httpclient = HttpClients.custom()
            .setMaxConnPerRoute(10)
            .setMaxConnTotal(40)
            .setDefaultRequestConfig(RequestConfig.custom()
                    .setSocketTimeout(60_000)
                    .setConnectionRequestTimeout(30_000)
                    .setConnectTimeout(10_000)
                    .build())
            // Hygiene: kill pooled connections that RDF4J has quietly closed server-side
            // before we try to reuse them. Without this, a half-broken connection is
            // only noticed when the next request fails, spending the full socket-read
            // timeout discovering it.
            .evictExpiredConnections()
            .evictIdleConnections(30, TimeUnit.SECONDS)
            .build();

    @GeneratedFlagForDependentElements
    Repository getRepository(String name) {
        synchronized (this) {
            // Reap parked handles that have now been idle long enough, even when we're
            // not over cap, so a burst that parked some repos doesn't leave them warm
            // forever if load then drops. Cheap: early-returns when nothing is parked.
            reapPendingShutdowns();
            if (repositories.size() > 100) {
                evictIdleRepos();
            }
            if (repositories.containsKey(name)) {
                // Move to the end of the list:
                Repository repo = repositories.remove(name);
                repositories.put(name, repo);
            } else if (pendingShutdown.containsKey(name)) {
                // Needed again before its grace period elapsed: resurrect the parked
                // handle instead of shutting it down and re-creating, avoiding both the
                // re-init round-trip and any shutdown/use race.
                Repository repo = pendingShutdown.remove(name);
                pendingShutdownSince.remove(name);
                repositories.put(name, repo);
            } else {
                Repository repository = null;
                if (endpointType == null || endpointType.equals("rdf4j")) {
                    HTTPRepository hr = new HTTPRepository(endpointBase + "repositories/" + name);
                    hr.setHttpClient(httpclient);
                    repository = hr;
//			} else if (endpointType.equals("virtuoso")) {
//				repository = new VirtuosoRepository(endpointBase + name, username, password);
                } else {
                    throw new RuntimeException("Unknown repository type: " + endpointType);
                }
                repositories.put(name, repository);
                createRepo(name);
                getRepoConnection(name).close();
            }
            return repositories.get(name);
        }
    }

    /**
     * Return the repository connection for the given repository name.
     *
     * @param name repository name
     * @return repository connection
     */
    @GeneratedFlagForDependentElements
    public RepositoryConnection getRepoConnection(String name) {
        // The increment has to happen under the same monitor that guards eviction,
        // otherwise another thread could evict this repo in the window between
        // getRepository() returning and the counter going above zero. getConnection()
        // on HTTPRepository is local (it doesn't do HTTP), so holding the lock here
        // is cheap.
        synchronized (this) {
            Repository repo = getRepository(name);
            if (repo == null) {
                return null;
            }
            AtomicInteger counter = openConnections.computeIfAbsent(name, k -> new AtomicInteger());
            counter.incrementAndGet();
            try {
                return new CountingRepositoryConnection(repo, repo.getConnection(), counter);
            } catch (Throwable t) {
                // If getConnection() or the wrapper constructor throws after we bumped
                // the counter, decrement so the repo isn't pinned against eviction by
                // a phantom "active" connection it doesn't actually have.
                counter.decrementAndGet();
                throw t;
            }
        }
    }

    /**
     * Evicts the eldest cache entries until either the size is back within the
     * 100-entry cap or every remaining entry is pinned or has an open connection.
     * The cap is load-bearing — each cached entry keeps an LMDB environment alive
     * on the RDF4J server (in-memory cache, mmap pages, native memory), so
     * exceeding it by much risks server-side OOM.
     *
     * <p>Eviction is <em>non-destructive</em>: it only unlinks the handle from the
     * live cache into {@link #pendingShutdown}; it does <strong>not</strong> call
     * {@link org.eclipse.rdf4j.repository.http.HTTPRepository#shutDown()} here.
     * Shutting a handle down closes the session manager and kills any live transaction
     * on that repo with a connection-close error (the {@code MMapIndexInput – Already
     * closed} failure mode observed on query-3 in the April test), which can wedge the
     * server-side LMDB store for every client. The actual shutdown happens later in
     * {@link #reapPendingShutdowns()}, only after a repo has stayed provably idle for
     * {@link #SHUTDOWN_GRACE_MS}. Pinned and actively-used repos are skipped entirely.
     * The cap is still enforced immediately, because unlinked handles leave
     * {@link #repositories} right away.
     */
    /**
     * Current time in millis. A seam so tests can drive the eviction grace period
     * deterministically instead of sleeping.
     */
    long nowMillis() {
        return System.currentTimeMillis();
    }

    @GeneratedFlagForDependentElements
    void evictIdleRepos() {
        reapPendingShutdowns();
        List<String> skipped = new ArrayList<>();
        long now = nowMillis();
        Iterator<Entry<String, Repository>> iter = repositories.entrySet().iterator();
        while (iter.hasNext() && repositories.size() > 100) {
            Entry<String, Repository> e = iter.next();
            String name = e.getKey();
            if (PINNED_REPO_NAMES.contains(name)) {
                skipped.add(name);
                continue;
            }
            AtomicInteger active = openConnections.get(name);
            if (active != null && active.get() > 0) {
                skipped.add(name);
                continue;
            }
            iter.remove();
            // Park for deferred shutdown rather than shutting down in this hot path.
            pendingShutdown.put(name, e.getValue());
            pendingShutdownSince.put(name, now);
        }
        if (!skipped.isEmpty()) {
            logger.warn("Skipped eviction for {} active/pinned repo(s); cache size is now {} (cap 100). Active names: {}",
                    skipped.size(), repositories.size(), skipped);
        }
    }

    /**
     * Shuts down handles parked in {@link #pendingShutdown} that have stayed idle
     * ({@code openConnections == 0}) for at least {@link #SHUTDOWN_GRACE_MS}. The grace
     * period keeps eviction from killing a server-side transaction whose app-side
     * connection count merely dipped to zero between operations, or that is still
     * streaming a result set. A handle re-requested before it is reaped is resurrected
     * in {@link #getRepository(String)} and never shut down. Must be called under the
     * {@code this} monitor.
     */
    void reapPendingShutdowns() {
        if (pendingShutdown.isEmpty()) {
            return;
        }
        long now = nowMillis();
        Iterator<Entry<String, Repository>> iter = pendingShutdown.entrySet().iterator();
        while (iter.hasNext()) {
            Entry<String, Repository> e = iter.next();
            String name = e.getKey();
            AtomicInteger active = openConnections.get(name);
            Long since = pendingShutdownSince.get(name);
            if ((active == null || active.get() == 0) && since != null && now - since >= SHUTDOWN_GRACE_MS) {
                iter.remove();
                pendingShutdownSince.remove(name);
                logger.info("Shutting down idle repo (deferred): {}", name);
                try {
                    e.getValue().shutDown();
                } catch (Exception ex) {
                    logger.warn("Deferred shutdown failed for repo '{}': {}", name, ex.getMessage());
                }
                logger.info("Shutdown complete");
            }
        }
    }

    /**
     * Minimal wrapper around a delegate {@link RepositoryConnection} whose only job
     * is to decrement the per-repo open-connection counter exactly once when the
     * caller closes it. Uses {@link AtomicBoolean} so that repeated/idempotent
     * {@code close()} calls (common with try-with-resources plus explicit close)
     * don't decrement more than once.
     */
    private static final class CountingRepositoryConnection extends RepositoryConnectionWrapper {

        private final AtomicInteger counter;
        private final AtomicBoolean closed = new AtomicBoolean();

        CountingRepositoryConnection(Repository repo, RepositoryConnection delegate, AtomicInteger counter) {
            super(repo, delegate);
            this.counter = counter;
        }

        @Override
        public void close() {
            try {
                super.close();
            } finally {
                if (closed.compareAndSet(false, true)) {
                    counter.decrementAndGet();
                }
            }
        }

    }

    @GeneratedFlagForDependentElements
    private void createRepo(String repoName) {
        if (!repoName.equals(ADMIN_REPO)) {
            getRepository(ADMIN_REPO);  // make sure admin repo is loaded first
        }
        // Uses the shared this.httpclient rather than a per-call client so it inherits
        // the configured pool sizes (and, once change 1 of the fix plan lands, the
        // socket/connection-request timeouts).
        try {
            //logger.info("Trying to creating repo " + name);

            // TODO new syntax somehow doesn't work for the Lucene case:

//			String createRegularRepoQueryString = "@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#>.\n"
//					+ "@prefix config: <tag:rdf4j.org,2023:config/>.\n"
//					+ "[] a config:Repository ;\n"
//					+ "    config:rep.id \"" + name + "\" ;\n"
//					+ "    rdfs:label \"" + name + " native store\" ;\n"
//					+ "    config:rep.impl [\n"
//					+ "        config:rep.type \"openrdf:SailRepository\" ;\n"
//					+ "        config:sail.impl [\n"
//					+ "            config:sail.type \"openrdf:NativeStore\" ;\n"
//					+ "            config:sail.iterationCacheSyncThreshold \"10000\";\n"
//					+ "            config:native.tripleIndexes \"spoc,posc,ospc,opsc,psoc,sopc,spoc,cpos,cosp,cops,cpso,csop\" ;\n"
//					+ "            config:sail.defaultQueryEvaluationMode \"STANDARD\"\n"
//					+ "        ]\n"
//					+ "    ].";
//			String createTextRepoQueryString = "@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#>.\n"
//					+ "@prefix config: <tag:rdf4j.org,2023:config/>.\n"
//					+ "[] a config:Repository ;\n"
//					+ "    config:rep.id \"" + name + "\" ;\n"
//					+ "    rdfs:label \"" + name + " native store\" ;\n"
//					+ "    config:rep.impl [\n"
//					+ "        config:rep.type \"openrdf:SailRepository\" ;\n"
//					+ "        config:sail.impl [\n"
//					+ "            config:sail.type \"openrdf:LuceneSail\" ;\n"
//					+ "            config:sail.lucene.indexDir \"index/\" ;\n"
//					+ "            config:delegate [\n"
//					+ "                config:rep.type \"openrdf:SailRepository\" ;\n"
//					+ "                config:sail.impl [\n"
//					+ "                    config:sail.type \"openrdf:NativeStore\" ;\n"
//					+ "                    config:sail.iterationCacheSyncThreshold \"10000\";\n"
//					+ "                    config:native.tripleIndexes \"spoc,posc,ospc,opsc,psoc,sopc,spoc,cpos,cosp,cops,cpso,csop\" ;\n"
//					+ "                    config:sail.defaultQueryEvaluationMode \"STANDARD\"\n"
//					+ "                ]\n"
//					+ "            ]\n"
//					+ "        ]\n"
//					+ "    ].";

            String indexTypes = "spoc,posc,ospc,cspo,cpos,cosp";
            if (repoName.startsWith("meta") || repoName.startsWith("text")) {
                indexTypes = "spoc,posc,ospc";
            }

            String createRegularRepoQueryString =
                    "@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#>.\n" +
                    "@prefix rep: <http://www.openrdf.org/config/repository#>.\n" +
                    "@prefix sr: <http://www.openrdf.org/config/repository/sail#>.\n" +
                    "@prefix sail: <http://www.openrdf.org/config/sail#>.\n" +
                    "@prefix sail-luc: <http://www.openrdf.org/config/sail/lucene#>.\n" +
                    "@prefix lmdb: <http://rdf4j.org/config/sail/lmdb#>.\n" +
                    "@prefix sb: <http://www.openrdf.org/config/sail/base#>.\n" +
                    "\n" +
                    "[] a rep:Repository ;\n" +
                    "    rep:repositoryID \"" + repoName + "\" ;\n" +
                    "    rdfs:label \"" + repoName + " LMDB store\" ;\n" +
                    "    rep:repositoryImpl [\n" +
                    "        rep:repositoryType \"openrdf:SailRepository\" ;\n" +
                    "        sr:sailImpl [\n" +
                    "            sail:sailType \"rdf4j:LmdbStore\" ;\n" +
                    "            sail:iterationCacheSyncThreshold \"10000\";\n" +
                    "            lmdb:tripleIndexes \"" + indexTypes + "\" ;\n" +
                    "            sb:defaultQueryEvaluationMode \"STANDARD\"\n" +
                    "        ]\n"
                    + "    ].\n";

            // TODO Index npa:hasFilterLiteral predicate too (see https://groups.google.com/g/rdf4j-users/c/epF4Af1jXGU):
            String createTextRepoQueryString =
                    "@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#>.\n" +
                    "@prefix rep: <http://www.openrdf.org/config/repository#>.\n" +
                    "@prefix sr: <http://www.openrdf.org/config/repository/sail#>.\n" +
                    "@prefix sail: <http://www.openrdf.org/config/sail#>.\n" +
                    "@prefix sail-luc: <http://www.openrdf.org/config/sail/lucene#>.\n" +
                    "@prefix lmdb: <http://rdf4j.org/config/sail/lmdb#>.\n" +
                    "@prefix sb: <http://www.openrdf.org/config/sail/base#>.\n" +
                    "\n"
                    + "[] a rep:Repository ;\n" +
                    "    rep:repositoryID \"" + repoName + "\" ;\n" +
                    "    rdfs:label \"" + repoName + " store\" ;\n" +
                    "    rep:repositoryImpl [\n" +
                    "        rep:repositoryType \"openrdf:SailRepository\" ;\n" +
                    "        sr:sailImpl [\n" +
                    "            sail:sailType \"openrdf:LuceneSail\" ;\n" +
                    "            sail-luc:indexDir \"index/\" ;\n" +
                    "            sail-luc:transactional false ;\n" +
                    "            sail:delegate [\n" +
                    "              sail:sailType \"rdf4j:LmdbStore\" ;\n" +
                    "              sail:iterationCacheSyncThreshold \"10000\";\n" +
                    "              lmdb:tripleIndexes \"" + indexTypes + "\" ;\n" +
                    "              sb:defaultQueryEvaluationMode \"STANDARD\"\n" +
                    "            ]\n" +
                    "        ]\n" +
                    "    ].";

            String createRepoQueryString = createRegularRepoQueryString;
            if (repoName.startsWith("text")) {
                createRepoQueryString = createTextRepoQueryString;
            }

            HttpUriRequest createRepoRequest = RequestBuilder.put().setUri(endpointBase + "repositories/" + repoName).addHeader("Content-Type", "text/turtle").setEntity(new StringEntity(createRepoQueryString)).build();

            // Response is try-with-resources'd so the connection is released back to
            // the shared pool rather than leaked.
            try (CloseableHttpResponse response = httpclient.execute(createRepoRequest)) {
                int statusCode = response.getStatusLine().getStatusCode();
                if (statusCode == 409) {
                    //logger.info("Already exists.");
                    getRepository(repoName).init();
                } else if (statusCode >= 200 && statusCode < 300) {
                    //logger.info("Successfully created.");
                    initNewRepo(repoName);
                } else {
                    logger.info("Status code: {}", response.getStatusLine().getStatusCode());
                    logger.info(response.getStatusLine().getReasonPhrase());
                    String handledResponse = new BasicResponseHandler().handleResponse(response);
                    logger.info("Response: {}", handledResponse);
                }
            }
        } catch (IOException ex) {
            logger.info("Could not create repo.", ex);
        }
    }

    /**
     * Sends shutdown signal to all repositories.
     */
    @GeneratedFlagForDependentElements
    public void shutdownRepositories() {
        for (Repository repo : repositories.values()) {
            if (repo != null && repo.isInitialized()) {
                repo.shutDown();
            }
        }
    }

    /**
     * Returns admin repo connection.
     *
     * @return repository connection to admin repository
     */
    @GeneratedFlagForDependentElements
    public RepositoryConnection getAdminRepoConnection() {
        return get().getRepoConnection(ADMIN_REPO);
    }

    private Set<String> cachedRepositoryNames = Set.of();
    private boolean repoNamesCacheValid = false;
    private final ReadWriteLock repoNamesCacheLock = new ReentrantReadWriteLock();

    /**
     * Returns set of all repository names.
     *
     * @return Repository name set
     */
    public Set<String> getRepositoryNames() {
        // See if the repository names are cached:
        final var readLock = repoNamesCacheLock.readLock();
        try {
            readLock.lock();
            if (repoNamesCacheValid) {
                return cachedRepositoryNames;
            }
        } finally {
            readLock.unlock();
        }

        // Not cached, get from server:
        final var writeLock = repoNamesCacheLock.writeLock();
        try {
            writeLock.lock();
            // Check again if another thread has already updated the cache:
            if (repoNamesCacheValid) {
                return cachedRepositoryNames;
            }
            Map<String, Boolean> repositoryNames = null;
            // Uses the shared this.httpclient; response try-with-resources releases
            // the pooled connection when done.
            try (CloseableHttpResponse resp = httpclient.execute(RequestBuilder.get()
                    .setUri(endpointBase + "/repositories")
                    .addHeader("Content-Type", "text/csv")
                    .build())) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(resp.getEntity().getContent()));
                int code = resp.getStatusLine().getStatusCode();
                if (code < 200 || code >= 300) return null;
                repositoryNames = new HashMap<>();
                int lineCount = 0;
                while (true) {
                    String line = reader.readLine();
                    if (line == null) break;
                    if (lineCount > 0) {
                        String repoName = line.split(",")[1];
                        repositoryNames.put(repoName, true);
                    }
                    lineCount = lineCount + 1;
                }
            } catch (IOException ex) {
                logger.info("Could not get repository names.", ex);
                return null;
            }
            cachedRepositoryNames = repositoryNames.keySet();
            repoNamesCacheValid = true;
            return cachedRepositoryNames;
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Invalidates the repository names cache. Call this method when a repository is created or deleted.
     */
    private void invalidateRepositoryNamesCache() {
        final var writeLock = repoNamesCacheLock.writeLock();
        try {
            writeLock.lock();
            repoNamesCacheValid = false;
        } finally {
            writeLock.unlock();
        }
    }

    @GeneratedFlagForDependentElements
    private void initNewRepo(String repoName) {
        String repoInitId = new Random().nextLong() + "";
        getRepository(repoName).init();
        if (!repoName.equals("empty")) {
            RepositoryConnection conn = getRepoConnection(repoName);
            try (conn) {
                // Full isolation, just in case.
                conn.begin(IsolationLevels.SERIALIZABLE);
                conn.add(NPA.THIS_REPO, NPA.HAS_REPO_INIT_ID, vf.createLiteral(repoInitId), NPA.GRAPH);
                if (tracksNanopubCountAndChecksum(repoName)) {
                    conn.add(NPA.THIS_REPO, NPA.HAS_NANOPUB_COUNT, vf.createLiteral(0L), NPA.GRAPH);
                    conn.add(NPA.THIS_REPO, NPA.HAS_NANOPUB_CHECKSUM, vf.createLiteral(NanopubUtils.INIT_CHECKSUM), NPA.GRAPH);
                }
                if (repoName.startsWith("pubkey_") || repoName.startsWith("type_")) {
                    String h = repoName.replaceFirst("^[^_]+_", "");
                    conn.add(NPA.THIS_REPO, NPA.HAS_COVERAGE_ITEM, Utils.getObjectForHash(h), NPA.GRAPH);
                    conn.add(NPA.THIS_REPO, NPA.HAS_COVERAGE_HASH, vf.createLiteral(h), NPA.GRAPH);
                    conn.add(NPA.THIS_REPO, NPA.HAS_COVERAGE_FILTER, vf.createLiteral("_" + repoName), NPA.GRAPH);
                }
                conn.commit();
            }
            // Refresh repository names cache
            invalidateRepositoryNamesCache();
        }
    }

    /**
     * Whether the given repo participates in the cumulative nanopub-count / XOR-checksum
     * tracking. Repos that don't produce their own content-addressed population
     * skip the {@code npa:hasNanopubCount} and {@code npa:hasNanopubChecksum}
     * initial triples — leaving them at {@code 0} and the empty-XOR placeholder
     * forever would just be misleading. Currently excluded:
     * <ul>
     *   <li>{@code admin} — holds metadata only.</li>
     *   <li>{@code last30d} — content expires on a periodic cleanup.</li>
     *   <li>{@code trust} — holds derived trust state, mirrored from the registry.</li>
     *   <li>{@code spaces} — holds space-relevant raw nanopubs (a filtered projection
     *       of {@code full}) plus extraction triples; its XOR checksum over loaded
     *       trusty URIs would diverge from {@code full}'s without adding value.</li>
     * </ul>
     */
    private static boolean tracksNanopubCountAndChecksum(String repoName) {
        return !repoName.equals(ADMIN_REPO)
                && !repoName.equals("last30d")
                && !repoName.equals("trust")
                && !repoName.equals("spaces");
    }

}
