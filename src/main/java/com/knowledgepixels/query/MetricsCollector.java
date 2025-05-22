package com.knowledgepixels.query;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class MetricsCollector {

    private final AtomicInteger loadCounter = new AtomicInteger(0);
    private final AtomicInteger typeRepositoriesCounter = new AtomicInteger(0);
    private final AtomicInteger pubkeyRepositoriesCounter = new AtomicInteger(0);
    private final AtomicInteger fullRepositoriesCounter = new AtomicInteger(0);

    private final Map<StatusController.State, AtomicInteger> statusStates = new ConcurrentHashMap<>();

    public MetricsCollector(MeterRegistry meterRegistry) {
        // Numeric metrics
        Gauge.builder("registry.load.counter", loadCounter, AtomicInteger::get).register(meterRegistry);
        Gauge.builder("registry.type.repositories.counter", typeRepositoriesCounter, AtomicInteger::get).register(meterRegistry);
        Gauge.builder("registry.pubkey.repositories.counter", pubkeyRepositoriesCounter, AtomicInteger::get).register(meterRegistry);
        Gauge.builder("registry.full.repositories.counter", fullRepositoriesCounter, AtomicInteger::get).register(meterRegistry);

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

    public void updateMetrics() {
        // Update numeric metrics
        loadCounter.set((int) StatusController.get().getState().loadCounter);
        typeRepositoriesCounter.set(
            (int) Optional
                .ofNullable(TripleStore.get().getRepositoryNames())
                .orElse(Set.of())
                .stream()
                .filter(repo -> repo.startsWith("type_"))
                .count()
        );
        pubkeyRepositoriesCounter.set(
            (int) Optional
                .ofNullable(TripleStore.get().getRepositoryNames())
                .orElse(Set.of())
                .stream()
                .filter(repo -> repo.startsWith("pubkey_"))
                .count()
        );
        fullRepositoriesCounter.set(
            Optional
                .ofNullable(TripleStore.get().getRepositoryNames())
                .orElse(Set.of())
                .size()
        );

        // Update status gauge
        final var currentStatus = StatusController.get().getState().state;
        for (final var status : StatusController.State.values()) {
            statusStates.get(status).set(status.equals(currentStatus) ? 1 : 0);
        }
    }
}
