package com.knowledgepixels.query;

import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpHead;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.util.EntityUtils;
import org.eclipse.rdf4j.query.QueryLanguage;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.nanopub.NanopubUtils;
import org.nanopub.jelly.NanopubStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Loads nanopubs from the attached Nanopub Registry via a restartable Jelly stream.
 */
public class JellyNanopubLoader {
    static final String registryUrl;
    private static long lastCommittedCounter = -1;
    private static Long lastKnownSetupId = null;
    // Latest registry metadata fields, updated on each metadata fetch and forwarded to clients
    static volatile String lastCoverageTypes = null;
    static volatile String lastCoverageAgents = null;
    static volatile String lastTestInstance = null;
    static volatile String lastNanopubCount = null;
    private static final CloseableHttpClient metadataClient;
    private static final CloseableHttpClient jellyStreamClient;

    private static final int MAX_RETRIES_METADATA = 10;
    private static final int RETRY_DELAY_METADATA = 3000;
    private static final int RETRY_DELAY_JELLY = 5000;

    /**
     * Circuit-breaker state. Counts consecutive {@code loadUpdates} invocations in
     * which the catch block fired; resets to zero on the next successful batch.
     * Scheduled on the single-threaded executor in {@link MainVerticle}, so a plain
     * {@code int} is enough — no concurrency.
     *
     * <p>When the counter reaches {@link #BREAKER_THRESHOLD}, the next invocation
     * sleeps {@link #BREAKER_PAUSE_MS} before proceeding. Lets the saturated RDF4J
     * drain instead of being hammered every {@link #UPDATES_POLL_INTERVAL} ms.
     *
     * <p>Depends on {@link com.knowledgepixels.query.TripleStore}'s socket timeouts
     * (change 1 of the fix plan) to turn parked commits into propagating exceptions;
     * without them, {@code loadBatch} can park forever, the catch never fires, and
     * this counter stays at zero.
     */
    static volatile int consecutiveBatchFailures = 0;

    /**
     * The load counter observed when {@link #consecutiveBatchFailures} last went from
     * zero to one. If the counter has not moved after several consecutive failures,
     * the batch is failing deterministically on the same entry — the "poison entry"
     * shape of issue #208 (one unloadable stream entry wedging ingestion fleet-wide)
     * — rather than transiently on store pressure. Used only to escalate the log
     * level with operator guidance; skipping is never based on failure counting,
     * because a store outage would produce the same signature and skipping there
     * would silently drop real nanopubs.
     */
    static volatile long counterAtFirstFailure = -1L;

    /** Consecutive same-counter batch failures after which the wedge alarm is logged. */
    static final int WEDGE_ALERT_THRESHOLD = 3;

    static final int BREAKER_THRESHOLD = 3;
    static final long BREAKER_PAUSE_MS = 30_000L;

    /**
     * Epoch-millis of the last time the loader demonstrably reached the triple store —
     * by committing a load counter, by completing a batch, or, on an idle tick, by
     * passing {@link #probeStoreReachable()}. Read by {@link MetricsCollector} to expose
     * {@code registry.loader.last_successful_batch_age_seconds} and served as the
     * {@code Nanopub-Query-Loader-Last-Success-Age-Seconds} header, so an operator can
     * tell from outside whether an instance is still able to serve.
     *
     * <p><strong>Must only be stamped after RDF4J has actually answered.</strong> Until
     * 2026-08-05 the idle branch stamped it unconditionally, but an idle tick reads the
     * counter from in-memory {@link StatusController} state and otherwise talks only to
     * the registry — it never touched RDF4J. A caught-up instance therefore reported
     * age 0 and READY throughout an 11-hour total RDF4J outage on kpxl (Tomcat's acceptor
     * thread had died of {@code OutOfMemoryError}, so every connect timed out), which is
     * precisely the failure this signal exists to surface. A caught-up instance could
     * never fail the check, which made the check worthless exactly when it mattered.
     * Every stamp site below therefore follows a completed round trip: a committed
     * counter and a completed batch are both stronger proof than the ASK probe.
     *
     * <p><strong>The initial-load paths stamp it too.</strong> They did not until
     * 2026-08-05, and because a resync runs entirely inside {@link #loadInitial} —
     * {@code loadUpdates} does not run again until {@code performResync} returns, which
     * for a full re-stream is tens of minutes — the age climbed unbounded for the whole
     * of any legitimate resync. A healthy resync was indistinguishable from a wedged one
     * both here and in Grafana (incident 2026-08-05, query.nanodash.net: a resync stalled
     * with the load counter pinned at -1, and the climbing age looked exactly like the
     * resync itself). Read this together with {@code Nanopub-Query-Status}: a climbing
     * age under {@code LOADING_INITIAL} now means a resync that has stopped making
     * progress, not merely a long one.
     *
     * <p>On a healthy idle instance the age oscillates between 0 and
     * {@link #STORE_PROBE_INTERVAL_MS}, rather than sitting at 0.
     */
    static volatile long lastSuccessfulBatchAtMs = 0L;

    /**
     * Minimum interval between idle-path store-reachability probes, and hence the
     * granularity of {@link #lastSuccessfulBatchAtMs} on a caught-up instance. The idle
     * path runs every {@link #UPDATES_POLL_INTERVAL} ms; probing on every poll would add
     * 30 round trips a minute without sharpening the signal, since any alert on this
     * value is measured in minutes.
     */
    static final long STORE_PROBE_INTERVAL_MS = 30_000L;

    /**
     * Epoch-millis of the last store-reachability probe, whether it was a dedicated
     * {@link #probeStoreReachable()} call or a batch commit (which proves the same thing
     * more strongly). Confined to the single-threaded loader executor in
     * {@link MainVerticle}, so a plain field is enough. Package-private only so tests
     * can reset it between cases.
     */
    static long lastStoreProbeAtMs = 0L;

    /**
     * Epoch-millis of the last {@link #updateForwardingMetadata} call, i.e. of the last
     * time the registry-derived headers we forward to clients were refreshed. Used by
     * {@link #maybeRefreshForwardingMetadata} to rate-limit mid-load refreshes.
     */
    private static volatile long lastForwardingMetadataAtMs = 0L;

    /**
     * Minimum interval between opportunistic refreshes of the forwarded registry
     * metadata during a long-running load.
     *
     * <p>{@link #loadUpdates} refreshes on every poll (~{@value #UPDATES_POLL_INTERVAL} ms)
     * so this limiter never engages there; it exists for {@link #loadInitial}, which
     * used to fetch metadata exactly once and then stream for as long as the batch
     * took. Thirty seconds is far below the resolution anyone reads these values at
     * while costing one HEAD request per interval.
     */
    private static final long METADATA_REFRESH_INTERVAL_MS = 30_000L;

    /**
     * Epoch-millis of the last "registry is a test instance, ignoring its content"
     * warning. Confined to the single-threaded loader executor in {@link MainVerticle},
     * so a plain field is enough. Package-private only so tests can reset it.
     */
    static long lastTestInstanceWarnAtMs = 0L;

    /**
     * Minimum interval between test-instance skip warnings. The condition is a stable
     * configuration mismatch, not an event, and {@link #loadUpdates} runs every
     * {@value #UPDATES_POLL_INTERVAL} ms — unthrottled it would emit ~1800 identical
     * WARN lines an hour.
     */
    static final long TEST_INSTANCE_WARN_INTERVAL_MS = 300_000L;

    /**
     * Heartbeat counter for loadUpdates invocations. A summary log line is emitted
     * every {@link #HEARTBEAT_INTERVAL_INVOCATIONS} invocations so a truncated or
     * sampled log still shows the loader's state evolving. At the default 2 s poll
     * interval, 30 invocations ≈ 1 line per minute.
     */
    private static long loadUpdatesInvocations = 0L;
    private static final long HEARTBEAT_INTERVAL_INVOCATIONS = 30;

    private static final Logger logger = LoggerFactory.getLogger(JellyNanopubLoader.class);

    /**
     * Registry metadata returned by a HEAD request.
     */
    record RegistryMetadata(long loadCounter, Long setupId, String coverageTypes,
                            String coverageAgents, String testInstance, String nanopubCount,
                            String trustStateHash) {

        /**
         * Whether the registry declares itself a test instance.
         *
         * <p>The header is absent on registries older than
         * <a href="https://github.com/knowledgepixels/nanopub-registry/commit/c62b6adf">c62b6adf</a>,
         * which is treated as "not a test instance" — the same reading the registry
         * itself applies to its peers.
         *
         * @return {@code true} if {@code Nanopub-Registry-Test-Instance} is {@code true}
         */
        boolean isTestInstance() {
            return "true".equalsIgnoreCase(testInstance);
        }
    }

    /**
     * The interval in milliseconds at which the updates loader should poll for new nanopubs.
     */
    public static final int UPDATES_POLL_INTERVAL = 2000;

    enum LoadingType {
        INITIAL,
        UPDATE,
    }

    static {
        // Initialize registryUrl
        var url = Utils.getEnvString(
                "REGISTRY_FIXED_URL", "https://registry.knowledgepixels.com/"
        );
        if (!url.endsWith("/")) {
            url += "/";
        }
        registryUrl = url;

        metadataClient = HttpClientBuilder.create().setDefaultRequestConfig(Utils.getHttpRequestConfig()).build();
        jellyStreamClient = NanopubUtils.getHttpClient();
    }

    /**
     * Start or continue (after restart) the initial loading procedure. This simply loads all
     * nanopubs from the attached Registry.
     *
     * @param afterCounter which counter to start from (-1 for the beginning)
     */
    public static void loadInitial(long afterCounter) {
        RegistryMetadata metadata = fetchRegistryMetadata();
        updateForwardingMetadata(metadata);
        if (shouldIgnoreRegistryContent(metadata)) {
            // Returning here leaves the caller free to mark the instance READY: an
            // empty-but-healthy Query is the intended outcome of pointing a production
            // instance at a test registry, and loadUpdates keeps re-checking the flag.
            return;
        }
        TrustStateLoader.maybeUpdate(metadata.trustStateHash());
        long targetCounter = metadata.loadCounter();
        logger.info("Fetched Registry load counter: {}", targetCounter);
        // Store setupId on initial load
        if (metadata.setupId() != null && lastKnownSetupId == null) {
            lastKnownSetupId = metadata.setupId();
            StatusController.get().setRegistrySetupId(metadata.setupId());
        }
        lastCommittedCounter = afterCounter;
        while (lastCommittedCounter < targetCounter) {
            // Keep the forwarded registry headers moving even across a batch that
            // fails and retries without loading anything. Rate-limited, so the first
            // iteration (right after the fetch above) is a no-op. Note this refreshes
            // only what we forward to clients — targetCounter stays as sampled at
            // entry, and anything the registry gained since is picked up by
            // loadUpdates once this initial load returns.
            maybeRefreshForwardingMetadata();
            // Same circuit-breaker logic as loadUpdates: after BREAKER_THRESHOLD
            // consecutive failed batches, pause before retrying so a saturated RDF4J
            // (e.g. during a restart storm) can drain instead of being hammered on the
            // 5-second RETRY_DELAY_JELLY cadence.
            if (consecutiveBatchFailures >= BREAKER_THRESHOLD) {
                logger.warn("Circuit breaker active during initial load after {} consecutive batch failures; pausing {} ms before next attempt",
                        consecutiveBatchFailures, BREAKER_PAUSE_MS);
                try {
                    Thread.sleep(BREAKER_PAUSE_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted while waiting for circuit breaker.");
                }
            }
            try {
                loadBatch(lastCommittedCounter, LoadingType.INITIAL);
                consecutiveBatchFailures = 0;
                // A completed batch wrote to RDF4J, so it satisfies the "only stamp
                // after the store answered" rule and, like an update batch, is a
                // stronger reachability proof than the ASK probe.
                lastSuccessfulBatchAtMs = System.currentTimeMillis();
                lastStoreProbeAtMs = lastSuccessfulBatchAtMs;
                logger.info("Initial load: loaded batch up to counter {}", lastCommittedCounter);
            } catch (Exception e) {
                consecutiveBatchFailures++;
                logger.info("Failed to load batch starting from counter {} (consecutive failures: {})",
                        lastCommittedCounter, consecutiveBatchFailures);
                logger.info("Failure reason: ", e);
                try {
                    Thread.sleep(RETRY_DELAY_JELLY);
                } catch (InterruptedException e2) {
                    throw new RuntimeException("Interrupted while waiting to retry loading batch.");
                }
            }
        }
        logger.info("Initial load complete.");
    }

    /**
     * Check if the Registry has any new nanopubs. If it does, load them.
     * This method should be called periodically, and you should wait for it to finish before
     * calling it again.
     */
    public static void loadUpdates() {
        // Circuit breaker: after BREAKER_THRESHOLD consecutive failed batches, pause
        // before the next attempt so a saturated RDF4J can drain. Check happens before
        // any RDF4J-touching work so the sleep isn't itself under the broken regime.
        if (consecutiveBatchFailures >= BREAKER_THRESHOLD) {
            logger.warn("Circuit breaker active after {} consecutive batch failures; pausing {} ms before next attempt",
                    consecutiveBatchFailures, BREAKER_PAUSE_MS);
            try {
                Thread.sleep(BREAKER_PAUSE_MS);
            } catch (InterruptedException e) {
                // Preserve interruption semantics so a graceful shutdown (e.g. via
                // MainVerticle's shutdown hook) isn't blocked by the pause.
                Thread.currentThread().interrupt();
                return;
            }
        }
        try {
            final var status = StatusController.get().getState();
            lastCommittedCounter = status.loadCounter;
            RegistryMetadata metadata = fetchRegistryMetadata();
            updateForwardingMetadata(metadata);
            if (shouldIgnoreRegistryContent(metadata)) {
                // Same bookkeeping as the caught-up branch below: a deliberate skip is
                // still a successful tick, so the breaker resets, but liveness may only
                // be stamped once RDF4J has actually answered — otherwise an instance
                // attached to a test registry would report perfect health through a
                // total store outage. Note the resync detection below is skipped too:
                // a test registry's setupId must not drive this instance's state.
                boolean probed = probeStoreReachable();
                StatusController.get().setReady();
                consecutiveBatchFailures = 0;
                if (probed) {
                    lastSuccessfulBatchAtMs = System.currentTimeMillis();
                }
                return;
            }
            TrustStateLoader.maybeUpdate(metadata.trustStateHash());
            long targetCounter = metadata.loadCounter();
            Long currentSetupId = metadata.setupId();

            // Detect reset via setupId change
            if (lastKnownSetupId != null && currentSetupId != null
                && !lastKnownSetupId.equals(currentSetupId)) {
                logger.warn("Registry reset detected: setupId {} -> {}", lastKnownSetupId, currentSetupId);
                performResync(currentSetupId);
                return;
            }
            // Detect reset via counter decrease (also covers first run after upgrade
            // where no setupId was persisted yet but the registry has already been reset)
            if (lastCommittedCounter > 0 && targetCounter >= 0
                && targetCounter < lastCommittedCounter) {
                logger.warn("Registry counter decreased {} -> {}, triggering resync",
                        lastCommittedCounter, targetCounter);
                performResync(currentSetupId);
                return;
            }

            // Update lastKnownSetupId on first successful poll
            if (currentSetupId != null && lastKnownSetupId == null) {
                if (lastCommittedCounter > 0) {
                    // Upgrade from a version without setupId tracking. The DB has data but
                    // we can't verify it matches the current registry. Force a resync.
                    logger.warn("No stored setupId but DB has data (counter: {}). "
                                + "Forcing resync to ensure data consistency.", lastCommittedCounter);
                    performResync(currentSetupId);
                    return;
                }
                lastKnownSetupId = currentSetupId;
                StatusController.get().setRegistrySetupId(currentSetupId);
            }

            if (lastCommittedCounter >= targetCounter) {
                // Nothing to do. Keep state at READY (setReady is idempotent) and
                // skip the redundant setLoadingUpdates → setReady admin-repo write
                // that the old flow did on every idle poll. Also reset the breaker
                // counter — a successful "nothing to do" is still a successful tick
                // and should clear stale failure state from earlier transient errors.
                //
                // Probe the store *before* recording liveness: nothing else on this
                // path touches RDF4J, so without it "caught up" would keep passing
                // for liveness while the store was unreachable. A failing probe
                // throws into the catch below, which increments the breaker and
                // leaves lastSuccessfulBatchAtMs to go stale — the intended signal.
                boolean probed = probeStoreReachable();
                StatusController.get().setReady();
                consecutiveBatchFailures = 0;
                if (probed) {
                    lastSuccessfulBatchAtMs = System.currentTimeMillis();
                }
                maybeLogHeartbeat(targetCounter, true);
                return;
            }
            StatusController.get().setLoadingUpdates(status.loadCounter);
            loadBatch(lastCommittedCounter, LoadingType.UPDATE);
            // Batch completed without an exception — reset the breaker counter.
            consecutiveBatchFailures = 0;
            lastSuccessfulBatchAtMs = System.currentTimeMillis();
            // A committed batch is a stronger reachability proof than the ASK probe,
            // so it also defers the next one.
            lastStoreProbeAtMs = lastSuccessfulBatchAtMs;
            maybeLogHeartbeat(targetCounter, false);
            logger.info("Loaded {} update(s). Counter: {}, target was: {}",
                    lastCommittedCounter - status.loadCounter, lastCommittedCounter, targetCounter);
            if (lastCommittedCounter < targetCounter) {
                logger.info("Warning: expected to load nanopubs up to (inclusive) counter {} based on the counter reported in Registry's headers, but loaded only up to {}.", targetCounter, lastCommittedCounter);
            }
        } catch (Exception e) {
            consecutiveBatchFailures++;
            if (consecutiveBatchFailures == 1) {
                counterAtFirstFailure = lastCommittedCounter;
            }
            logger.warn("Failed to load updates. Current counter: {} (consecutive failures: {})",
                    lastCommittedCounter, consecutiveBatchFailures, e);
            if (consecutiveBatchFailures >= WEDGE_ALERT_THRESHOLD && lastCommittedCounter == counterAtFirstFailure) {
                logger.error("Loader wedged: {} consecutive batch failures without advancing past counter {}. "
                        + "If the store is healthy, the stream entry at counter {} is likely unloadable "
                        + "(see issue #208); it should have been skipped with a note — check the "
                        + "'NOT loading nanopub' log lines and the entry's validity on the Registry.",
                        consecutiveBatchFailures, lastCommittedCounter, lastCommittedCounter + 1);
            }
        } finally {
            try {
                StatusController.get().setReady();
            } catch (Exception e) {
                logger.info("Update loader: failed to set status to READY.");
                logger.info("Failure Reason: ", e);
            }
        }
    }

    /**
     * Verify that the triple store is reachable and answering, so that
     * {@link #lastSuccessfulBatchAtMs} means "this instance can still serve" rather than
     * merely "the loader loop is still running".
     *
     * <p>Uses {@code ASK {}} against the admin repo: it matches the empty group pattern
     * without touching any index, so the cost is one HTTP round trip and essentially no
     * query work — while still exercising the exact client, connection pool and endpoint
     * that a real query would use.
     *
     * <p>Throttled to one probe per {@link #STORE_PROBE_INTERVAL_MS}. Callers must treat
     * a {@code false} return as "not verified now" and leave the liveness timestamp
     * alone, so the stamp always refers to a round trip that actually happened.
     *
     * @return true if a probe ran and succeeded; false if one ran too recently to repeat
     * @throws RuntimeException (from RDF4J) if the store did not answer
     */
    private static boolean probeStoreReachable() {
        long now = System.currentTimeMillis();
        if (now - lastStoreProbeAtMs < STORE_PROBE_INTERVAL_MS) {
            return false;
        }
        try (RepositoryConnection conn = TripleStore.get().getAdminRepoConnection()) {
            conn.prepareBooleanQuery(QueryLanguage.SPARQL, "ASK {}").evaluate();
        }
        lastStoreProbeAtMs = now;
        return true;
    }

    /**
     * Emit a heartbeat summary log line roughly every
     * {@link #HEARTBEAT_INTERVAL_INVOCATIONS} invocations of {@link #loadUpdates}.
     * Lets an operator reconstruct loader progress from a sparse or sampled log
     * export, independent of Prometheus retention.
     */
    private static void maybeLogHeartbeat(long targetCounter, boolean idle) {
        loadUpdatesInvocations++;
        if (loadUpdatesInvocations % HEARTBEAT_INTERVAL_INVOCATIONS != 0) {
            return;
        }
        logger.info("Loader heartbeat: counter={} target={} idle={} consecutiveBatchFailures={} breakerActive={}",
                lastCommittedCounter, targetCounter, idle, consecutiveBatchFailures,
                consecutiveBatchFailures >= BREAKER_THRESHOLD);
    }

    /**
     * Re-stream all nanopubs from the registry after a reset is detected.
     * Existing nanopubs are skipped by NanopubLoader's per-repo dedup.
     *
     * @param newSetupId the new setup ID from the registry, or null if unknown
     */
    private static void performResync(Long newSetupId) {
        logger.warn("Starting resync with registry. New setupId: {}", newSetupId);
        StatusController.get().setResetting();
        lastKnownSetupId = newSetupId;
        if (newSetupId != null) {
            StatusController.get().setRegistrySetupId(newSetupId);
        }
        StatusController.get().setLoadingInitial(-1);
        loadInitial(-1);
        StatusController.get().setReady();
        logger.warn("Resync complete. Counter: {}", lastCommittedCounter);
    }

    /**
     * Load a batch of nanopubs from the Jelly stream.
     * <p>
     * The method requests the list of all nanopubs from the Registry and reads it for as long
     * as it can. If the stream is interrupted, the method will throw an exception, and you
     * can resume loading from the last known counter.
     *
     * @param afterCounter the last known nanopub counter to have been committed in the DB
     * @param type         the type of loading operation (initial or update)
     */
    static void loadBatch(long afterCounter, LoadingType type) {
        CloseableHttpResponse response;
        try {
            var request = new HttpGet(makeStreamFetchUrl(afterCounter));
            response = jellyStreamClient.execute(request);
        } catch (IOException e) {
            throw new RuntimeException("Failed to fetch Jelly stream from the Registry (I/O error).", e);
        }

        int httpStatus = response.getStatusLine().getStatusCode();
        if (httpStatus < 200 || httpStatus >= 300) {
            EntityUtils.consumeQuietly(response.getEntity());
            throw new RuntimeException("Jelly stream HTTP status is not 2xx: " + httpStatus + ".");
        }

        try (
                var is = response.getEntity().getContent();
                var npStream = NanopubStream.fromByteStream(is).getAsNanopubs()
        ) {
            AtomicLong checkpointTime = new AtomicLong(System.currentTimeMillis());
            AtomicLong checkpointCounter = new AtomicLong(lastCommittedCounter);
            AtomicLong lastSavedCounter = new AtomicLong(lastCommittedCounter);
            AtomicLong loaded = new AtomicLong(0L);

            npStream.forEach(m -> {
                if (!m.isSuccess()) {
                    throw new RuntimeException("Failed to load " +
                                               "nanopub from Jelly stream. Last known counter: " + lastCommittedCounter,
                            m.getException()
                    );
                }
                if (m.getCounter() < lastCommittedCounter) {
                    throw new RuntimeException("Received a nanopub with a counter lower than " +
                                               "the last known counter. Last known counter: " + lastCommittedCounter +
                                               ", received counter: " + m.getCounter());
                }
                NanopubLoader.load(m.getNanopub(), m.getCounter());
                // Bump the in-memory counter BEFORE persisting it. The previous order
                // wrote the *previous* nanopub's counter to the DB at each checkpoint,
                // so a crash-restart silently re-processed one extra nanopub and the
                // contract "saved counter == last fully loaded nanopub" was violated.
                lastCommittedCounter = m.getCounter();
                if (m.getCounter() % 10 == 0) {
                    // Save the committed counter only every 10 nanopubs to reduce DB load
                    saveCommittedCounter(type);
                    lastSavedCounter.set(m.getCounter());
                }
                loaded.getAndIncrement();

                if (loaded.get() % 50 == 0) {
                    long currTime = System.currentTimeMillis();
                    double speed = 50 / ((currTime - checkpointTime.get()) / 1000.0);
                    logger.info("Loading speed: {} np/s. Counter: {}", String.format("%.2f", speed), lastCommittedCounter);
                    checkpointTime.set(currTime);
                    checkpointCounter.set(lastCommittedCounter);
                    // A full re-stream is a single loadBatch call lasting tens of
                    // minutes; without this the forwarded registry count would hold
                    // its entry-time value for the whole of it, and the sync-lag
                    // gauge derived from it would drift with it.
                    maybeRefreshForwardingMetadata();
                }
            });
            // Make sure to save the last committed counter at the end of the batch
            if (lastCommittedCounter >= lastSavedCounter.get()) {
                saveCommittedCounter(type);
            }
        } catch (IOException e) {
            throw new RuntimeException("I/O error while reading the response Jelly stream.", e);
        } finally {
            try {
                response.close();
            } catch (IOException e) {
                logger.info("Failed to close the Jelly stream response.");
            }
        }
    }

    /**
     * Save the last committed counter to the DB. Do this every N nanopubs to reduce DB load.
     * Remember to call this method at the end of the batch as well.
     *
     * @param type the type of loading operation (initial or update)
     */
    private static void saveCommittedCounter(LoadingType type) {
        try {
            if (type == LoadingType.INITIAL) {
                StatusController.get().setLoadingInitial(lastCommittedCounter);
            } else {
                StatusController.get().setLoadingUpdates(lastCommittedCounter);
            }
            // A committed counter is an admin-repo write that RDF4J acknowledged, so it
            // is the finest-grained evidence of liveness there is — and the only one
            // that ticks inside a batch rather than at its end, which is what makes a
            // long initial load legible. Stamped after the call, never before: a failed
            // commit is exactly how the loader wedges, and must not read as progress.
            lastSuccessfulBatchAtMs = System.currentTimeMillis();
            lastStoreProbeAtMs = lastSuccessfulBatchAtMs;
        } catch (Exception e) {
            throw new RuntimeException("Could not update the nanopub counter in DB", e);
        }
    }

    /**
     * Set the last known setup ID. Called from MainVerticle on startup to restore persisted state.
     *
     * @param setupId the setup ID to set, or null if not known
     */
    static void setLastKnownSetupId(Long setupId) {
        lastKnownSetupId = setupId;
    }

    /**
     * Whether the attached registry's content must be ignored because the registry
     * declares itself a test instance (issue #25).
     *
     * <p>A registry sets {@code Nanopub-Registry-Test-Instance} at DB initialization
     * from its own {@code REGISTRY_TEST_INSTANCE} variable, marking content that is
     * not meant to reach production indexes. Nanopub Registry already refuses to sync
     * from peers that report it; Query does the same for the registry it is attached
     * to, loading neither nanopubs nor trust state from it.
     *
     * <p>The check is deliberately made per poll rather than once at startup: a
     * registry that is wiped and re-initialized as a test instance under a running
     * Query must stop feeding it, and one that is re-initialized the other way must
     * be picked up without a restart. Nanopubs already loaded before the flag
     * appeared are left in place — ignoring content is not the same as deleting it,
     * and a registry reset shows up separately as a setupId change.
     *
     * <p>Overridable with {@code NANOPUB_QUERY_ALLOW_TEST_REGISTRY=true} for a Query
     * instance deliberately paired with a test registry; see
     * {@link FeatureFlags#allowTestRegistry()}.
     *
     * @param metadata the registry metadata just fetched
     * @return {@code true} if this poll must not ingest anything
     */
    private static boolean shouldIgnoreRegistryContent(RegistryMetadata metadata) {
        if (!metadata.isTestInstance() || FeatureFlags.allowTestRegistry()) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (now - lastTestInstanceWarnAtMs >= TEST_INSTANCE_WARN_INTERVAL_MS) {
            lastTestInstanceWarnAtMs = now;
            logger.warn("Registry {} reports Nanopub-Registry-Test-Instance: true — ignoring its content. "
                        + "No nanopubs and no trust state will be loaded from it. Set "
                        + "NANOPUB_QUERY_ALLOW_TEST_REGISTRY=true if this instance is deliberately "
                        + "paired with a test registry.", registryUrl);
        }
        return true;
    }

    /**
     * Update the cached metadata fields used for forwarding to clients.
     */
    private static void updateForwardingMetadata(RegistryMetadata metadata) {
        lastCoverageTypes = metadata.coverageTypes();
        lastCoverageAgents = metadata.coverageAgents();
        lastTestInstance = metadata.testInstance();
        lastNanopubCount = metadata.nanopubCount();
        lastForwardingMetadataAtMs = System.currentTimeMillis();
    }

    /**
     * Re-read the registry's metadata headers mid-load so the values forwarded to
     * clients don't freeze for the duration of a long batch.
     *
     * <p>Rate-limited to one fetch per {@link #METADATA_REFRESH_INTERVAL_MS} and
     * best-effort: on failure the previous values stay in place and the caller
     * continues. Deliberately calls {@link #fetchRegistryMetadataInner} rather than
     * {@link #fetchRegistryMetadata} — the latter retries {@value #MAX_RETRIES_METADATA}
     * times with {@value #RETRY_DELAY_METADATA} ms between attempts, which against an
     * unhealthy registry would park the nanopub stream for half a minute to refresh a
     * header. One attempt, bounded by the client's socket timeout, is the right trade
     * for a value that is only ever advisory.
     *
     * <p>Refreshes only the forwarded fields. It must not touch the load counter the
     * caller is driving its loop from: re-targeting mid-load would let a busy registry
     * keep {@code loadInitial} running indefinitely instead of handing over to
     * {@code loadUpdates}.
     */
    private static void maybeRefreshForwardingMetadata() {
        if (System.currentTimeMillis() - lastForwardingMetadataAtMs < METADATA_REFRESH_INTERVAL_MS) {
            return;
        }
        try {
            updateForwardingMetadata(fetchRegistryMetadataInner());
        } catch (Exception e) {
            // Stamp anyway so a persistently failing registry is retried on the same
            // interval rather than on every single call.
            lastForwardingMetadataAtMs = System.currentTimeMillis();
            logger.info("Mid-load registry metadata refresh failed; keeping previous values: {}", e.toString());
        }
    }

    /**
     * Run a HEAD request to the Registry to fetch its current metadata (load counter and setup ID).
     *
     * @return the registry metadata
     */
    static RegistryMetadata fetchRegistryMetadata() {
        int tries = 0;
        RegistryMetadata metadata = null;
        while (metadata == null && tries < MAX_RETRIES_METADATA) {
            try {
                metadata = fetchRegistryMetadataInner();
            } catch (Exception e) {
                tries++;
                logger.info("Failed to fetch registry metadata, try {}. Retrying in {}ms...", tries, RETRY_DELAY_METADATA);
                logger.info("Failure Reason: ", e);
                try {
                    Thread.sleep(RETRY_DELAY_METADATA);
                } catch (InterruptedException e2) {
                    throw new RuntimeException(
                            "Interrupted while waiting to retry fetching registry metadata.");
                }
            }
        }
        if (metadata == null) {
            throw new RuntimeException("Failed to fetch registry metadata after " + MAX_RETRIES_METADATA + " retries.");
        }
        return metadata;
    }

    /**
     * Inner logic for fetching the registry metadata via HEAD request.
     *
     * @return the registry metadata (load counter and setup ID)
     * @throws IOException if the HTTP request fails
     */
    private static RegistryMetadata fetchRegistryMetadataInner() throws IOException {
        var request = new HttpHead(registryUrl);
        try (var response = metadataClient.execute(request)) {
            int status = response.getStatusLine().getStatusCode();
            EntityUtils.consumeQuietly(response.getEntity());
            if (status < 200 || status >= 300) {
                throw new RuntimeException("Registry metadata HTTP status is not 2xx: " +
                                           status + ".");
            }

            // Check if the registry is ready
            var hStatus = response.getHeaders("Nanopub-Registry-Status");
            if (hStatus.length == 0) {
                throw new RuntimeException("Registry did not return a Nanopub-Registry-Status header.");
            }
            if (!"ready".equals(hStatus[0].getValue()) && !"updating".equals(hStatus[0].getValue())) {
                throw new RuntimeException("Registry is not in ready state.");
            }

            // Get the load counter
            var hCounter = response.getHeaders("Nanopub-Registry-Load-Counter");
            if (hCounter.length == 0) {
                throw new RuntimeException("Registry did not return a Nanopub-Registry-Load-Counter header.");
            }
            long loadCounter = Long.parseLong(hCounter[0].getValue());

            // Get the setup ID (optional — older registries may not have it)
            Long setupId = null;
            var hSetupId = response.getHeaders("Nanopub-Registry-Setup-Id");
            if (hSetupId.length > 0) {
                try {
                    setupId = Long.parseLong(hSetupId[0].getValue());
                } catch (NumberFormatException e) {
                    logger.info("Could not parse Nanopub-Registry-Setup-Id header: {}", hSetupId[0].getValue());
                }
            }

            // Read metadata headers for forwarding to clients
            String coverageTypes = getHeaderValue(response, "Nanopub-Registry-Coverage-Types");
            String coverageAgents = getHeaderValue(response, "Nanopub-Registry-Coverage-Agents");
            String testInstance = getHeaderValue(response, "Nanopub-Registry-Test-Instance");
            String nanopubCount = getHeaderValue(response, "Nanopub-Registry-Nanopub-Count");
            // Optional — older registries (without trust calculation) won't set this header.
            String trustStateHash = getHeaderValue(response, "Nanopub-Registry-Trust-State-Hash");

            return new RegistryMetadata(loadCounter, setupId, coverageTypes, coverageAgents,
                    testInstance, nanopubCount, trustStateHash);
        }
    }

    private static String getHeaderValue(CloseableHttpResponse response, String name) {
        var headers = response.getHeaders(name);
        return headers.length > 0 ? headers[0].getValue() : null;
    }

    /**
     * Construct the URL for fetching the Jelly stream.
     *
     * @param afterCounter the last known counter to have been committed in the DB
     * @return the URL for fetching the Jelly stream
     */
    private static String makeStreamFetchUrl(long afterCounter) {
        return registryUrl + "nanopubs.jelly?afterCounter=" + afterCounter;
    }
}
