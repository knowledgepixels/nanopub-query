package com.knowledgepixels.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.lang.reflect.Field;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TrustStateLoaderTest {

    @BeforeEach
    void setUp() throws NoSuchFieldException, IllegalAccessException {
        // Reset the registry singleton between tests so the "current hash"
        // doesn't leak across test cases.
        Field instance = TrustStateRegistry.class.getDeclaredField("instance");
        instance.setAccessible(true);
        instance.set(null, null);
    }

    @Test
    void maybeUpdate_isNoOpForNullHash() {
        // Should not throw, should not change registry state.
        TrustStateLoader.maybeUpdate(null);
        assertFalse(TrustStateRegistry.get().getCurrentHash().isPresent());
    }

    @Test
    void maybeUpdate_isNoOpForEmptyHash() {
        TrustStateLoader.maybeUpdate("");
        assertFalse(TrustStateRegistry.get().getCurrentHash().isPresent());
    }

    @Test
    void maybeUpdate_doesNotChangeRegistryState_inStubVersion() {
        // The stub only logs — it doesn't actually materialize anything,
        // so the registry's currentHash stays empty even after detection.
        TrustStateLoader.maybeUpdate("abc123");
        assertFalse(TrustStateRegistry.get().getCurrentHash().isPresent());
    }

    @Test
    void maybeUpdate_isNoOpWhenHashEqualsCurrent() {
        // Pre-seed the registry as if a previous materialization had set this.
        TrustStateRegistry.get().setCurrentHash("abc123");
        // Same hash → no-op (no exception, no change).
        TrustStateLoader.maybeUpdate("abc123");
        assertEquals("abc123", TrustStateRegistry.get().getCurrentHash().orElseThrow());
    }

}
