package com.knowledgepixels.query;

import io.micrometer.core.instrument.MeterRegistry;
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