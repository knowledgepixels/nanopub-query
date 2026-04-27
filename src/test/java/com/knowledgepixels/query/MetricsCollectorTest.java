package com.knowledgepixels.query;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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