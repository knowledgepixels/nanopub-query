package com.knowledgepixels.query;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

class MetricsCollectorTest {

    @Test
    void constructWithNullMeterRegistryThrowsException() {
        assertThrows(NullPointerException.class, () -> new MetricsCollector(null));
    }

    @Test
    void constructWithValidMeterRegistry() {
        MeterRegistry meterRegistry = mock(MeterRegistry.class);
        MetricsCollector collector = new MetricsCollector(meterRegistry);
        assertNotNull(collector);
    }

    @Test
    void registersSpacesGauges() {
        // Real registry so we can introspect what was registered. Gauges are
        // registered against AuthorityResolver.get() — its singleton init has
        // no side effects, so this works without TripleStore mocking.
        var registry = new SimpleMeterRegistry();
        new MetricsCollector(registry);
        assertNotNull(registry.find("registry.spaces.subjects.admin_ris").gauge());
        assertNotNull(registry.find("registry.spaces.subjects.attachment_ras").gauge());
        assertNotNull(registry.find("registry.spaces.subjects.non_admin_ris").gauge());
        assertNotNull(registry.find("registry.spaces.delta.last_inserted_triples").gauge());
        assertNotNull(registry.find("registry.spaces.rebuild.last_duration_seconds").gauge());
        assertNotNull(registry.find("registry.spaces.cycle.last_duration_seconds").gauge());
        assertNotNull(registry.find("registry.spaces.processed_up_to_lag").gauge());
        // Pre-cycle, all spaces gauges read 0.
        assertEquals(0.0, registry.find("registry.spaces.subjects.admin_ris").gauge().value());
        assertEquals(0.0, registry.find("registry.spaces.processed_up_to_lag").gauge().value());
    }

    @Test
    void exportsTheMetricNamesTheAlertRulesReferenceOn() {
        // monitoring/prometheus-alerts.yml matches on these exact strings. Micrometer
        // maps dots to underscores on export, so a rename here silently stops the
        // alerts firing rather than breaking anything loudly — hence pinning them.
        var registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        new MetricsCollector(registry);
        String scrape = registry.scrape();
        for (String metric : new String[]{
                "registry_loader_breaker_active",
                "registry_loader_consecutive_batch_failures",
                "registry_loader_last_successful_batch_age_seconds",
                "registry_loader_sync_lag_nanopubs",
                "registry_reconciler_shards_repaired_total",
                "registry_reconciler_shards_relost_total",
        }) {
            assertTrue(scrape.contains(metric), "alert rules reference missing metric: " + metric);
        }
    }

    @Test
    void syncLagIsUnknownUntilBothCountsAreAvailable() {
        String savedRegistryCount = JellyNanopubLoader.lastNanopubCount;
        Long savedLoaded = NanopubLoader.loadedNanopubCount;
        try {
            // No registry poll yet: must report the sentinel, not a fabricated 0 —
            // "unknown" and "in sync" have to stay distinguishable to an alert.
            JellyNanopubLoader.lastNanopubCount = null;
            NanopubLoader.loadedNanopubCount = 100L;
            assertEquals(-1L, MetricsCollector.computeSyncLag());

            JellyNanopubLoader.lastNanopubCount = "not a number";
            assertEquals(-1L, MetricsCollector.computeSyncLag());

            JellyNanopubLoader.lastNanopubCount = "107";
            assertEquals(7L, MetricsCollector.computeSyncLag());

            JellyNanopubLoader.lastNanopubCount = "100";
            assertEquals(0L, MetricsCollector.computeSyncLag());

            // Loaded count runs ahead between registry polls; clamped so it can never
            // be mistaken for the -1 sentinel.
            JellyNanopubLoader.lastNanopubCount = "98";
            assertEquals(0L, MetricsCollector.computeSyncLag());
        } finally {
            JellyNanopubLoader.lastNanopubCount = savedRegistryCount;
            NanopubLoader.loadedNanopubCount = savedLoaded;
        }
    }

    @Test
    void updateMetrics() {
        MeterRegistry meterRegistry = mock(MeterRegistry.class);
        MetricsCollector collector = new MetricsCollector(meterRegistry);
        try (MockedStatic<TripleStore> tripleStoreMockedStatic = mockStatic(TripleStore.class)) {
            TripleStore tripleStore = mock(TripleStore.class);
            Set<String> repositoryNames = Set.of("type_repo1", "pubkey_repo1", "full_repo1");
            tripleStoreMockedStatic.when(TripleStore::get).thenReturn(tripleStore);
            tripleStoreMockedStatic.when(tripleStore::getRepositoryNames).thenReturn(repositoryNames);
            assertDoesNotThrow(collector::updateMetrics);
        }
    }

}