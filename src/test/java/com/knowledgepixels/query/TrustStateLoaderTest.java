package com.knowledgepixels.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.Map;

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
                1, 1, 0.008, 100000L, null, null);
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
                "pk1", "https://example.org/agent", "loaded", 1, 1, 0.5, 1000L, null, null);
        String h1 = TrustStateLoader.accountStateHash("trustA", a);
        String h2 = TrustStateLoader.accountStateHash("trustB", a);
        org.junit.jupiter.api.Assertions.assertNotEquals(h1, h2);
    }

    @Test
    void accountStateHash_differsAcrossAgents() {
        TrustStateSnapshot.AccountEntry a1 = new TrustStateSnapshot.AccountEntry(
                "pk1", "https://example.org/agentA", "loaded", 1, 1, 0.5, 1000L, null, null);
        TrustStateSnapshot.AccountEntry a2 = new TrustStateSnapshot.AccountEntry(
                "pk1", "https://example.org/agentB", "loaded", 1, 1, 0.5, 1000L, null, null);
        org.junit.jupiter.api.Assertions.assertNotEquals(
                TrustStateLoader.accountStateHash("trustA", a1),
                TrustStateLoader.accountStateHash("trustA", a2));
    }

    @Test
    void effectiveRetention_defaultsTo100() {
        // No env var set → default.
        assertEquals(100, TrustStateLoader.effectiveRetention());
    }

    // ---------------- Canonical name resolution (#62) ----------------

    private static TrustStateSnapshot snapshotOf(TrustStateSnapshot.AccountEntry... accounts) {
        return new TrustStateSnapshot("h", 1L, Instant.parse("2026-01-01T00:00:00Z"), List.of(accounts));
    }

    @Test
    void resolveCanonicalNames_picksRowWithMaxRatio() {
        // Same agent, two approved keys with different names. The MAX(ratio)
        // policy chooses the more-trusted key's stamped name, which is
        // semantically "the name from the agent's most-endorsed declaration".
        TrustStateSnapshot.AccountEntry low = new TrustStateSnapshot.AccountEntry(
                "pk1", "https://example.org/alice", "loaded", 1, 1, 0.10, 100L,
                "Old Alice", Instant.parse("2025-01-01T00:00:00Z"));
        TrustStateSnapshot.AccountEntry high = new TrustStateSnapshot.AccountEntry(
                "pk2", "https://example.org/alice", "loaded", 1, 1, 0.90, 100L,
                "Alice", Instant.parse("2024-01-01T00:00:00Z"));
        Map<String, String> names =
                TrustStateLoader.resolveCanonicalNames(snapshotOf(low, high));
        assertEquals("Alice", names.get("https://example.org/alice"));
    }

    @Test
    void resolveCanonicalNames_breaksTiesOnLexMinName() {
        // Equal ratio → MIN(name) lex tiebreak so the resolution is
        // deterministic across rebuilds and across MongoDB iteration order.
        TrustStateSnapshot.AccountEntry charlie = new TrustStateSnapshot.AccountEntry(
                "pk1", "https://example.org/agent", "loaded", 1, 1, 0.5, 100L,
                "Charlie", Instant.parse("2025-06-01T00:00:00Z"));
        TrustStateSnapshot.AccountEntry alice = new TrustStateSnapshot.AccountEntry(
                "pk2", "https://example.org/agent", "loaded", 1, 1, 0.5, 100L,
                "Alice", Instant.parse("2025-01-01T00:00:00Z"));
        Map<String, String> names =
                TrustStateLoader.resolveCanonicalNames(snapshotOf(charlie, alice));
        assertEquals("Alice", names.get("https://example.org/agent"));
    }

    @Test
    void resolveCanonicalNames_skipsUnapprovedRows() {
        // A higher-ratio row with status=skipped or status=contested must not
        // beat an approved-but-lower-ratio row, otherwise rejected agents could
        // shadow the trust-graph's actual chosen name.
        TrustStateSnapshot.AccountEntry approved = new TrustStateSnapshot.AccountEntry(
                "pk1", "https://example.org/agent", "loaded", 1, 1, 0.10, 100L,
                "Approved Name", Instant.parse("2025-01-01T00:00:00Z"));
        TrustStateSnapshot.AccountEntry skipped = new TrustStateSnapshot.AccountEntry(
                "pk2", "https://example.org/agent", "skipped", 5, null, null, null,
                "Skipped Name", Instant.parse("2025-06-01T00:00:00Z"));
        Map<String, String> names =
                TrustStateLoader.resolveCanonicalNames(snapshotOf(approved, skipped));
        assertEquals("Approved Name", names.get("https://example.org/agent"));
    }

    @Test
    void resolveCanonicalNames_omitsAgentsWithNoName() {
        // Approved key, but the declaring intro had no foaf:name. No entry in
        // the result map → consumer materializer emits no foaf:name triple.
        TrustStateSnapshot.AccountEntry noName = new TrustStateSnapshot.AccountEntry(
                "pk1", "https://example.org/agent", "loaded", 1, 1, 0.5, 100L,
                null, null);
        Map<String, String> names = TrustStateLoader.resolveCanonicalNames(snapshotOf(noName));
        assertTrue(names.isEmpty(), "agent with no name across approved keys must not appear");
        assertNull(names.get("https://example.org/agent"));
    }

    @Test
    void resolveCanonicalNames_acceptsToLoadStatusToo() {
        // toLoad is the second authority-approving status alongside loaded.
        // Per APPROVED_STATUSES, both should contribute names.
        TrustStateSnapshot.AccountEntry toLoad = new TrustStateSnapshot.AccountEntry(
                "pk1", "https://example.org/agent", "toLoad", 1, 1, 0.5, 100L,
                "ToLoad Name", Instant.parse("2025-01-01T00:00:00Z"));
        Map<String, String> names = TrustStateLoader.resolveCanonicalNames(snapshotOf(toLoad));
        assertEquals("ToLoad Name", names.get("https://example.org/agent"));
    }

    @Test
    void accountStateHash_differsAcrossPubkeys() {
        TrustStateSnapshot.AccountEntry a1 = new TrustStateSnapshot.AccountEntry(
                "pk1", "https://example.org/agent", "loaded", 1, 1, 0.5, 1000L, null, null);
        TrustStateSnapshot.AccountEntry a2 = new TrustStateSnapshot.AccountEntry(
                "pk2", "https://example.org/agent", "loaded", 1, 1, 0.5, 1000L, null, null);
        org.junit.jupiter.api.Assertions.assertNotEquals(
                TrustStateLoader.accountStateHash("trustA", a1),
                TrustStateLoader.accountStateHash("trustA", a2));
    }

}
