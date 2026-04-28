package com.knowledgepixels.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * against a live deployment (see {@code doc/design-space-repositories.md} and the
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
    void snapshotMetrics_defaultsAreZeroBeforeFirstCycle() {
        // Scrape-before-first-build must return 0, not NaN/null. Guards the
        // MetricsCollector lambdas that dereference getLastSubjectTotals().
        AuthorityResolver ar = AuthorityResolver.get();
        AuthorityResolver.TierSubjectTotals totals = ar.getLastSubjectTotals();
        assertNotNull(totals);
        assertEquals(0L, totals.adminRIs());
        assertEquals(0L, totals.attachmentRAs());
        assertEquals(0L, totals.nonAdminRIs());
        assertEquals(0L, ar.getLastInsertedTriplesTotal());
        assertEquals(0L, ar.getLastFullBuildDurationMs());
        assertEquals(0L, ar.getLastIncrementalCycleDurationMs());
        assertEquals(0L, ar.getLastProcessedUpToLag());
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
        assertTrue(sparql.contains("npa:inverseProperty gen:hasAdmin"),
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
        assertTrue(sparql.contains("npa:inverseProperty gen:hasAdmin"),
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
    void observerTierUpdate_acceptsAdminPublishedGrant() {
        // Permissive observer: admin-published grants of an ObserverRole-typed
        // role validate at this tier without needing a separate self-attestation.
        // Without this path, "admin assigned X the speaker role" silently no-ops
        // when the speaker role didn't declare an explicit tier subclass and
        // therefore defaulted to ObserverRole.
        String sparql = AuthorityResolver.nonAdminTierUpdate(
                TEST_GRAPH, 0,
                com.knowledgepixels.query.vocabulary.GEN.OBSERVER_ROLE,
                AuthorityResolver.PUBLISHER_IS_ADMIN);
        assertTrue(sparql.contains(
                com.knowledgepixels.query.vocabulary.GEN.OBSERVER_ROLE.stringValue()),
                "tier class is substituted to ObserverRole");
        assertTrue(sparql.contains("npa:inverseProperty gen:hasAdmin"),
                "admin publisher constraint pinned via gen:hasAdmin");
    }

    @Test
    void observerTierUpdate_allowsSelfEvidence() {
        String sparql = AuthorityResolver.nonAdminTierUpdate(
                TEST_GRAPH, 0,
                com.knowledgepixels.query.vocabulary.GEN.OBSERVER_ROLE,
                AuthorityResolver.PUBLISHER_IS_SELF);
        // Self-evidence: AccountState's npa:agent is bound directly to ?agent
        // (the assignee), so the (pkh, agent) row anchors the join — no
        // separate ?publisher variable, no post-join equality filter.
        assertTrue(sparql.contains("npa:agent  ?agent"),
                "observer tier (self branch) binds AccountState agent to ?agent directly");
        assertFalse(sparql.contains("?publisher = ?agent"),
                "observer tier must not rely on a post-join equality filter");
    }

    // ---------------- Invalidation DELETE templates (PR 2c) ----------------

    @Test
    void adminInvalidationDelete_pinsAdminPredicateAndDeltaFilter() {
        String sparql = AuthorityResolver.adminInvalidationDelete(TEST_GRAPH, 17);
        assertTrue(sparql.contains("DELETE"), "DELETE clause");
        assertTrue(sparql.contains("npa:inverseProperty gen:hasAdmin"),
                "scoped to admin-pinned RoleInstantiations");
        assertTrue(sparql.contains("npa:Invalidation"),
                "joins the Invalidation extraction");
        assertTrue(sparql.contains("npa:invalidates ?np"),
                "links invalidation to the source nanopub");
        assertTrue(sparql.contains("FILTER (?ln > 17)"),
                "delta filter on the invalidator's load number");
    }

    @Test
    void roleAssignmentInvalidationDelete_targetsRoleAssignmentRowsOnly() {
        String sparql = AuthorityResolver.roleAssignmentInvalidationDelete(TEST_GRAPH, 5);
        assertTrue(sparql.contains("DELETE"), "DELETE clause");
        assertTrue(sparql.contains("gen:RoleAssignment"),
                "scoped to RoleAssignment rows");
        assertTrue(sparql.contains("FILTER (?ln > 5)"),
                "delta filter on the invalidator's load number");
        assertFalse(sparql.contains("npa:inverseProperty gen:hasAdmin"),
                "RoleAssignment delete must not pin the admin predicate");
    }

    @Test
    void roleDeclarationInvalidationCheck_isAskOnly() {
        // RoleDeclaration invalidation is ASK-only — RDs aren't materialized into
        // the space-state graph, so there's nothing to DELETE here. The WHERE clause
        // exists only to drive an ASK that flips needsFullRebuild.
        String where = AuthorityResolver.roleDeclarationInvalidationCheckWhere(0);
        assertTrue(where.contains("npa:RoleDeclaration"),
                "scoped to RoleDeclaration rows in spacesGraph");
        assertTrue(where.contains("npa:Invalidation"),
                "joins the Invalidation extraction");
        assertTrue(where.contains("FILTER (?ln > 0)"),
                "delta filter on the invalidator's load number");
    }

    @Test
    void leafTierInvalidationDelete_excludesAdminPinnedRows() {
        String sparql = AuthorityResolver.leafTierInvalidationDelete(TEST_GRAPH, 0);
        assertTrue(sparql.contains("DELETE"), "DELETE clause");
        assertTrue(sparql.contains("gen:RoleInstantiation"),
                "scoped to RoleInstantiation rows");
        assertTrue(sparql.contains("FILTER NOT EXISTS { ?ri npa:inverseProperty gen:hasAdmin }"),
                "must skip admin-pinned RIs (those are handled by adminInvalidationDelete)");
    }

}
