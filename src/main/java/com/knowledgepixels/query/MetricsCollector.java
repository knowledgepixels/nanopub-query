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

        // Status label metrics
        for (final var status : StatusController.State.values()) {
            AtomicInteger stateGauge = new AtomicInteger(0);
            statusStates.put(status, stateGauge);
            Gauge.builder("registry.server.status", stateGauge, AtomicInteger::get)
                    .description("Server status (1 if current)")
                    .tag("status", status.name())
                    .register(meterRegistry);
        }
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
