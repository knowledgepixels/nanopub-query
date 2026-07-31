package com.knowledgepixels.query;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Class to collect metrics for performance analysis.
 */
public final class MetricsCollector {

    private final AtomicInteger loadCounter = new AtomicInteger(0);
    private final AtomicInteger typeRepositoriesCounter = new AtomicInteger(0);
    private final AtomicInteger pubkeyRepositoriesCounter = new AtomicInteger(0);
    private final AtomicInteger fullRepositoriesCounter = new AtomicInteger(0);

    /**
     * Value behind {@code registry.loader.sync_lag_nanopubs}. Refreshed on the
     * {@code updateMetrics} tick rather than inside the gauge lambda: the gauge is
     * evaluated on the scrape path, which runs on a Vert.x event loop, and
     * {@link NanopubLoader#getLoadedNanopubCount()} can fall through to a SPARQL
     * query on a cold cache. The repo-name counters above are populated the same
     * way and for the same reason.
     */
    private final AtomicLong syncLagNanopubs = new AtomicLong(UNKNOWN_LAG);

    /**
     * Sentinel for {@code registry.loader.sync_lag_nanopubs} when either side of the
     * subtraction is unavailable — before the first registry poll, or if the
     * forwarded count is unparseable. Distinct from {@code 0}, which asserts the
     * instance is genuinely in sync. Real lags are clamped at zero so this value
     * can never arise from arithmetic.
     */
    private static final long UNKNOWN_LAG = -1L;

    private final Map<StatusController.State, AtomicInteger> statusStates = new ConcurrentHashMap<>();

    /**
     * Creates new metrics collector object.
     *
     * @param meterRegistry The registry instance
     */
    public MetricsCollector(MeterRegistry meterRegistry) {
        // Numeric metrics
        Gauge.builder("registry.load.counter", loadCounter, AtomicInteger::get).register(meterRegistry);
        Gauge.builder("registry.type.repositories.counter", typeRepositoriesCounter, AtomicInteger::get).register(meterRegistry);
        Gauge.builder("registry.pubkey.repositories.counter", pubkeyRepositoriesCounter, AtomicInteger::get).register(meterRegistry);
        Gauge.builder("registry.full.repositories.counter", fullRepositoriesCounter, AtomicInteger::get).register(meterRegistry);

        // Circuit-breaker observability: expose both the raw counter and a boolean
        // "breaker active" flag. The boolean is redundant with counter >= threshold
        // but much cleaner to visualise in Grafana (the counter can saturate well
        // above the threshold during a sustained outage, which makes a single
        // "is the breaker tripped?" alert awkward to express over the raw value).
        Gauge.builder("registry.loader.consecutive_batch_failures",
                        () -> (double) JellyNanopubLoader.consecutiveBatchFailures)
                .description("Consecutive loadUpdates batches that threw an exception before succeeding")
                .register(meterRegistry);
        Gauge.builder("registry.loader.breaker_active",
                        () -> JellyNanopubLoader.consecutiveBatchFailures >= JellyNanopubLoader.BREAKER_THRESHOLD ? 1.0 : 0.0)
                .description("1 if the loader circuit breaker is tripped (consecutive failures >= threshold), 0 otherwise")
                .register(meterRegistry);
        // Liveness signal that works without log access: seconds since the last
        // non-exceptional loadUpdates return. Counts both "loaded a batch" and
        // "caught up, nothing to do" as progress. An instance whose value climbs
        // unbounded while peers stay low is stuck on something the other
        // gauges don't capture.
        Gauge.builder("registry.loader.last_successful_batch_age_seconds",
                        () -> {
                            long t = JellyNanopubLoader.lastSuccessfulBatchAtMs;
                            if (t == 0L) return 0.0;    // not started yet
                            return (System.currentTimeMillis() - t) / 1000.0;
                        })
                .description("Seconds since the last non-exceptional loadUpdates return (idle or loading)")
                .register(meterRegistry);
        // How far behind its own registry this instance is. The gauges above all
        // describe the loader's *internal* health; this one is the outcome an
        // operator actually cares about, and it is absolute rather than relative —
        // unlike the monitor's cross-instance checksum comparison, it still fires
        // when every instance stalls at once (incident 2026-07-31).
        //
        // Pair it with last_successful_batch_age_seconds when alerting. The registry
        // side of the subtraction is the count forwarded by the most recent poll, so
        // if polling itself is what broke, both counts freeze together and the lag
        // reads a falsely reassuring 0. Neither signal covers the other's blind spot.
        Gauge.builder("registry.loader.sync_lag_nanopubs", syncLagNanopubs, AtomicLong::get)
                .description("Nanopubs this instance is behind its registry; -1 when either count is unknown")
                .register(meterRegistry);

        // Shard-reconciliation observability (issue #139). Both read volatile
        // counters kept by ShardReconciler — no SPARQL on the scrape path. Any
        // non-zero repaired value means the backend acknowledged a shard write
        // that was not durable, and deserves an alert.
        Gauge.builder("registry.reconciler.nanopubs_checked_total",
                        () -> (double) ShardReconciler.checkedNanopubCount)
                .description("Nanopubs whose shard fan-out was verified by the reconciliation sweep since process start")
                .register(meterRegistry);
        Gauge.builder("registry.reconciler.shards_repaired_total",
                        () -> (double) ShardReconciler.repairedShardCount)
                .description("Missing shard repos detected and re-loaded by the reconciliation sweep since process start")
                .register(meterRegistry);
        Gauge.builder("registry.reconciler.shards_relost_total",
                        () -> (double) ShardReconciler.relostShardCount)
                .description("Shards that a previous sweep verified present and that later vanished (backend revoked readable state, issue #142)")
                .register(meterRegistry);

        // Status label metrics
        for (final var status : StatusController.State.values()) {
            AtomicInteger stateGauge = new AtomicInteger(0);
            statusStates.put(status, stateGauge);
            Gauge.builder("registry.server.status", stateGauge, AtomicInteger::get)
                    .description("Server status (1 if current)")
                    .tag("status", status.name())
                    .register(meterRegistry);
        }

        // Spaces / AuthorityResolver gauges. These read volatile fields kept
        // by AuthorityResolver — no SPARQL on the scrape path. Each lambda
        // re-fetches the singleton to match the lazy-init pattern used by
        // the rest of the codebase.
        Gauge.builder("registry.spaces.subjects.admin_ris",
                        () -> (double) AuthorityResolver.get().getLastSubjectTotals().adminRIs())
                .description("Distinct admin gen:RoleInstantiation subjects in the current space-state graph (last build/cycle observation)")
                .register(meterRegistry);
        Gauge.builder("registry.spaces.subjects.attachment_ras",
                        () -> (double) AuthorityResolver.get().getLastSubjectTotals().attachmentRAs())
                .description("Distinct gen:RoleAssignment subjects in the current space-state graph (last build/cycle observation)")
                .register(meterRegistry);
        Gauge.builder("registry.spaces.subjects.non_admin_ris",
                        () -> (double) AuthorityResolver.get().getLastSubjectTotals().nonAdminRIs())
                .description("Distinct non-admin gen:RoleInstantiation subjects in the current space-state graph (last build/cycle observation)")
                .register(meterRegistry);
        Gauge.builder("registry.spaces.delta.last_inserted_triples",
                        () -> (double) AuthorityResolver.get().getLastInsertedTriplesTotal())
                .description("Total inserted triples across all five tiers in the most recent full build or incremental cycle")
                .register(meterRegistry);
        Gauge.builder("registry.spaces.rebuild.last_duration_seconds",
                        () -> AuthorityResolver.get().getLastFullBuildDurationMs() / 1000.0)
                .description("Wall-clock duration of the most recent full space-state build")
                .register(meterRegistry);
        Gauge.builder("registry.spaces.cycle.last_duration_seconds",
                        () -> AuthorityResolver.get().getLastIncrementalCycleDurationMs() / 1000.0)
                .description("Wall-clock duration of the most recent incremental space-state cycle that did work")
                .register(meterRegistry);
        Gauge.builder("registry.spaces.processed_up_to_lag",
                        () -> (double) AuthorityResolver.get().getLastProcessedUpToLag())
                .description("currentLoadCounter - processedUpTo observed at the start of the most recent incremental cycle (0 after a full build)")
                .register(meterRegistry);
    }

    /**
     * Updates the metrics based on the current state of the system.
     */
    public void updateMetrics() {
        // Update numeric metrics
        loadCounter.set((int) StatusController.get().getState().loadCounter);
        // Request repository names once, to avoid multiple calls
        var repoNames = TripleStore.get().getRepositoryNames();
        if (repoNames == null) {
            repoNames = Set.of();
        }
        typeRepositoriesCounter.set(
                (int) repoNames
                        .stream()
                        .filter(repo -> repo.startsWith("type_"))
                        .count()
        );
        pubkeyRepositoriesCounter.set(
                (int) repoNames
                        .stream()
                        .filter(repo -> repo.startsWith("pubkey_"))
                        .count()
        );
        fullRepositoriesCounter.set(repoNames.size());
        // Keeps the loaded-count/checksum caches warm for applyGlobalHeaders, which
        // runs on the event loop and therefore reads them without a store fallback.
        // This tick is the right host: it already runs unconditionally on its own
        // executor at a fixed cadence, and computeSyncLag below needs the count anyway.
        NanopubLoader.primeHeaderCaches();
        syncLagNanopubs.set(computeSyncLag());

        // Update status gauge
        final var currentStatus = StatusController.get().getState().state;
        for (final var status : StatusController.State.values()) {
            statusStates.get(status).set(status.equals(currentStatus) ? 1 : 0);
        }
    }

    /**
     * Nanopubs this instance is behind its registry, or {@link #UNKNOWN_LAG} if either
     * count is unavailable.
     *
     * <p>Clamped at zero: the loaded count is bumped as each nanopub lands while the
     * registry count only refreshes once per poll, so the loaded side can legitimately
     * run ahead for a tick. Reporting that as negative would collide with the
     * unknown sentinel.
     *
     * @return the lag in nanopubs, clamped to zero, or {@link #UNKNOWN_LAG}
     */
    static long computeSyncLag() {
        String registryCount = JellyNanopubLoader.lastNanopubCount;
        Long loaded = NanopubLoader.getLoadedNanopubCount();
        if (registryCount == null || loaded == null) {
            return UNKNOWN_LAG;
        }
        try {
            return Math.max(0L, Long.parseLong(registryCount.trim()) - loaded);
        } catch (NumberFormatException ex) {
            return UNKNOWN_LAG;
        }
    }
}
