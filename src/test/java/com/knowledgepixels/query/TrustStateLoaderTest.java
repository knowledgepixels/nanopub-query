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

    @Test
    void accountStateHash_isDeterministicOverSameInputs() {
        TrustStateSnapshot.AccountEntry a = new TrustStateSnapshot.AccountEntry(
                "pk1", "https://orcid.org/0000-0001-5118-256X", "loaded",
                1, 1, 0.008, 100000);
        String h1 = TrustStateLoader.accountStateHash("trustA", a);
        String h2 = TrustStateLoader.accountStateHash("trustA", a);
        assertEquals(h1, h2);
        // Known SHA-256 hex length
        assertEquals(64, h1.length());
    }

    @Test
    void accountStateHash_differsAcrossTrustStates() {
        // Same (pubkey, agent) under different trust states → different IRIs (by design).
        TrustStateSnapshot.AccountEntry a = new TrustStateSnapshot.AccountEntry(
                "pk1", "https://example.org/agent", "loaded", 1, 1, 0.5, 1000);
        String h1 = TrustStateLoader.accountStateHash("trustA", a);
        String h2 = TrustStateLoader.accountStateHash("trustB", a);
        org.junit.jupiter.api.Assertions.assertNotEquals(h1, h2);
    }

    @Test
    void accountStateHash_differsAcrossAgents() {
        TrustStateSnapshot.AccountEntry a1 = new TrustStateSnapshot.AccountEntry(
                "pk1", "https://example.org/agentA", "loaded", 1, 1, 0.5, 1000);
        TrustStateSnapshot.AccountEntry a2 = new TrustStateSnapshot.AccountEntry(
                "pk1", "https://example.org/agentB", "loaded", 1, 1, 0.5, 1000);
        org.junit.jupiter.api.Assertions.assertNotEquals(
                TrustStateLoader.accountStateHash("trustA", a1),
                TrustStateLoader.accountStateHash("trustA", a2));
    }

    @Test
    void accountStateHash_differsAcrossPubkeys() {
        TrustStateSnapshot.AccountEntry a1 = new TrustStateSnapshot.AccountEntry(
                "pk1", "https://example.org/agent", "loaded", 1, 1, 0.5, 1000);
        TrustStateSnapshot.AccountEntry a2 = new TrustStateSnapshot.AccountEntry(
                "pk2", "https://example.org/agent", "loaded", 1, 1, 0.5, 1000);
        org.junit.jupiter.api.Assertions.assertNotEquals(
                TrustStateLoader.accountStateHash("trustA", a1),
                TrustStateLoader.accountStateHash("trustA", a2));
    }

}
