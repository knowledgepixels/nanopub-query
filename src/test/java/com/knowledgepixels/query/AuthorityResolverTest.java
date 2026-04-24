package com.knowledgepixels.query;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pure-logic tests for {@link AuthorityResolver}. The full-cycle behaviour
 * (mirror / runFullBuild / pointer swap / orphan cleanup) exercises real RDF4J
 * connections; the project's current dependency versions mix
 * {@code sail-base:5.3.0} with {@code sail-memory:5.1.5} / {@code common-concurrent:5.1.5}
 * in a way that breaks pattern-delete and SPARQL UPDATE on both MemoryStore and
 * NativeStore in tests. Those paths are covered by the integration smoke test
 * against a live deployment (see {@code doc/plan-space-repositories.md} and the
 * live-instance spot-checks accompanying this PR).
 */
class AuthorityResolverTest {

    @BeforeEach
    void resetSingletons() throws Exception {
        // Prevent state bleeding across tests.
        reset(AuthorityResolver.class, "instance");
        reset(TrustStateRegistry.class, "instance");
    }

    private static void reset(Class<?> cls, String fieldName) throws Exception {
        Field f = cls.getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(null, null);
    }

    @Test
    void get_returnsSameSingleton() {
        AuthorityResolver a = AuthorityResolver.get();
        AuthorityResolver b = AuthorityResolver.get();
        assertNotNull(a);
        assertSame(a, b, "get() must always return the same instance");
    }

    @Test
    void tick_noCurrentTrustState_isNoOp() {
        // Registry is fresh; getCurrentHash is empty.
        assertFalse(TrustStateRegistry.get().getCurrentHash().isPresent());
        // tick() should exit early and not throw.
        AuthorityResolver.get().tick();
        assertTrue(TrustStateRegistry.get().getCurrentHash().isEmpty(),
                "tick() must not seed a hash when none is available");
    }

    // ---------------- SPARQL template structure ----------------

    private static final org.eclipse.rdf4j.model.IRI TEST_GRAPH =
            com.knowledgepixels.query.vocabulary.SpacesVocab.forSpaceState(
                    "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789", 42L);

    @Test
    void adminTierUpdate_containsSeedAndClosedOverBranches() {
        String sparql = AuthorityResolver.adminTierUpdate(TEST_GRAPH, 17);
        assertTrue(sparql.contains("INSERT"), "INSERT clause");
        assertTrue(sparql.contains("npa:regularProperty gen:hasAdmin"),
                "pinned admin predicate");
        assertTrue(sparql.contains("npa:hasRootAdmin"),
                "seed branch references hasRootAdmin");
        assertTrue(sparql.contains("UNION"),
                "seed + closed-over branches joined by UNION");
        assertTrue(sparql.contains("FILTER (?ln > 17)"),
                "load-number delta filter with lastProcessed substituted");
        assertTrue(sparql.contains("FILTER NOT EXISTS"),
                "existence + invalidation filters");
        assertTrue(sparql.contains("npa:AccountState"),
                "mirrored-row join");
    }

    @Test
    void attachmentValidationUpdate_requiresAdminPublisher() {
        String sparql = AuthorityResolver.attachmentValidationUpdate(TEST_GRAPH, 5);
        assertTrue(sparql.contains("gen:RoleAssignment"),
                "attachment-type inserted");
        assertTrue(sparql.contains("gen:hasRole"), "gen:hasRole predicate copied");
        assertTrue(sparql.contains("npa:regularProperty gen:hasAdmin"),
                "publisher-is-admin check");
        assertTrue(sparql.contains("FILTER (?ln > 5)"),
                "delta filter on attachment nanopub");
    }

    @Test
    void maintainerTierUpdate_pinsMaintainerRoleAndAdminConstraint() {
        String sparql = AuthorityResolver.nonAdminTierUpdate(
                TEST_GRAPH, 0,
                com.knowledgepixels.query.vocabulary.GEN.MAINTAINER_ROLE,
                AuthorityResolver.PUBLISHER_IS_ADMIN);
        assertTrue(sparql.contains("gen:hasRegularProperty") || sparql.contains("gen:hasInverseProperty"),
                "maps predicate to a RoleDeclaration property direction");
        assertTrue(sparql.contains(
                com.knowledgepixels.query.vocabulary.GEN.MAINTAINER_ROLE.stringValue()),
                "tier class is substituted");
        assertTrue(sparql.contains("gen:RoleAssignment"),
                "attachment gate present");
    }

    @Test
    void observerTierUpdate_allowsSelfEvidence() {
        String sparql = AuthorityResolver.nonAdminTierUpdate(
                TEST_GRAPH, 0,
                com.knowledgepixels.query.vocabulary.GEN.OBSERVER_ROLE,
                AuthorityResolver.PUBLISHER_IS_SELF_OR_TIERED);
        // Self-evidence: publisher agent (from mirrored rows) matches the assignee.
        assertTrue(sparql.contains("?publisher = ?agent"),
                "observer tier accepts self-evidence");
    }

}
