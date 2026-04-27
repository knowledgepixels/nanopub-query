package com.knowledgepixels.query;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Class to collect metrics for performance analysis.
 */
public final class MetricsCollector {

    private final AtomicInteger loadCounter = new AtomicInteger(0);
    private final AtomicInteger typeRepositoriesCounter = new AtomicInteger(0);
    private final AtomicInteger pubkeyRepositoriesCounter = new AtomicInteger(0);
    private final AtomicInteger fullRepositoriesCounter = new AtomicInteger(0);

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

        // Update status gauge
        final var currentStatus = StatusController.get().getState().state;
        for (final var status : StatusController.State.values()) {
            statusStates.get(status).set(status.equals(currentStatus) ? 1 : 0);
        }
    }
}
