package com.knowledgepixels.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.query.QueryLanguage;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.nanopub.vocabulary.NPA;

import com.knowledgepixels.query.vocabulary.GEN;
import com.knowledgepixels.query.vocabulary.SpacesVocab;

/**
 * End-to-end execution test for the ref-keyed admin tier (Phase 0 of the per-space-ref
 * authority isolation work; see {@code doc/design-spaceref-isolation.md}). Runs the real
 * {@link AuthorityResolver#adminTierUpdate} SPARQL to fixpoint against an in-memory store
 * seeded with two space refs (R1, R2) that share one Space IRI but have different root
 * admins, and asserts the closure never crosses the ref boundary, fans out into every ref
 * the granter governs, mints distinct per-ref subjects, and dedups.
 *
 * <p>This is the kind of execution test the suite previously could not run: an RDF4J
 * sail-base/sail-memory version skew broke in-process SPARQL UPDATE (see
 * {@link AuthorityResolverTest}'s javadoc), which is why the tier tests there assert on
 * SPARQL strings only. Pinning every RDF4J artifact through the BOM fixed that;
 * {@link Rdf4jUpdateProbeTest} is the focused guard for the fix.
 */
class AuthorityResolverSpaceRefIsolationTest {

    private static final ValueFactory vf = SimpleValueFactory.getInstance();
    private static final IRI STATE = SpacesVocab.forSpaceState(
            "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789", 1L);
    private static final IRI SPACES = SpacesVocab.SPACES_GRAPH;
    private static final IRI ADMINGRAPH = NPA.GRAPH;

    private static IRI npa(String ln) { return vf.createIRI(NPA.NAMESPACE, ln); }
    private static IRI ex(String ln)  { return vf.createIRI("http://example.org/", ln); }

    private static final IRI S     = ex("space-S");
    private static final IRI R1    = ex("ref-R1");
    private static final IRI R2    = ex("ref-R2");
    private static final IRI ALICE = ex("alice");
    private static final IRI BOB   = ex("bob");
    private static final IRI CAROL = ex("carol");
    private static final IRI DAVE  = ex("dave");
    private static final IRI FRANK = ex("frank");
    private static final Literal PKH_A = vf.createLiteral("pkhA");
    private static final Literal PKH_B = vf.createLiteral("pkhB");

    private Repository repo;
    private RepositoryConnection conn;

    @BeforeEach
    void setUp() {
        repo = new SailRepository(new MemoryStore());
        repo.init();
        conn = repo.getConnection();
        seed();
        runToFixpoint(AuthorityResolver.adminTierUpdate(STATE, -1));
    }

    @AfterEach
    void tearDown() {
        if (conn != null) conn.close();
        if (repo != null) repo.shutDown();
    }

    @Test
    void rootAdminSeedsStayWithinTheirOwnRef() {
        assertTrue(isAdmin(ALICE, R1), "Alice is root admin of R1");
        assertFalse(isAdmin(ALICE, R2), "Alice must not leak into R2");
        assertTrue(isAdmin(BOB, R2), "Bob is root admin of R2");
        assertFalse(isAdmin(BOB, R1), "Bob must not leak into R1");
    }

    @Test
    void grantValidatesOnlyInTheGrantersRef() {
        // Bob (admin of R2 only) grants Carol; Alice (admin of R1 only) grants Dave.
        assertTrue(isAdmin(CAROL, R2), "Carol admitted in R2 (Bob's ref)");
        assertFalse(isAdmin(CAROL, R1), "Carol must NOT appear in R1 — no cross-ref leak");
        assertTrue(isAdmin(DAVE, R1), "Dave admitted in R1 (Alice's ref)");
        assertFalse(isAdmin(DAVE, R2), "Dave must NOT appear in R2 — no cross-ref leak");
    }

    @Test
    void grantFansOutToEveryRefTheGranterGovernsWithDistinctSubjects() {
        // Frank is granted by Alice (in R1) and by Bob (in R2) → present in both refs,
        // as two distinct ref-scoped subjects (no subject collision).
        assertTrue(isAdmin(FRANK, R1), "Frank in R1");
        assertTrue(isAdmin(FRANK, R2), "Frank in R2");
        assertEquals(2L, distinctAdminSubjects(FRANK),
                "fan-out mints one distinct subject per ref");
    }

    @Test
    void rerunIsIdempotent() {
        long before = totalAdminSubjects();
        runToFixpoint(AuthorityResolver.adminTierUpdate(STATE, -1));
        assertEquals(before, totalAdminSubjects(), "dedup makes re-materialization a no-op");
        // Sanity: Alice@R1, Bob@R2, Carol@R2, Dave@R1, Frank@R1, Frank@R2.
        assertEquals(6L, before, "expected admin-row count");
    }

    // ---------------- fixtures ----------------

    private void seed() {
        conn.add(R1, SpacesVocab.SPACE_IRI, S, SPACES);
        conn.add(R2, SpacesVocab.SPACE_IRI, S, SPACES);

        spaceDef(ex("def1"), R1, ALICE, ex("rootNp1"));
        spaceDef(ex("def2"), R2, BOB, ex("rootNp2"));

        acct(ex("acctA"), ALICE, PKH_A);
        acct(ex("acctB"), BOB, PKH_B);

        // Self-grants bootstrap the seed admins; then cross-grants exercise isolation/fan-out.
        adminRI(ex("ri_selfA"), ALICE, PKH_A, ex("np_selfA"));
        adminRI(ex("ri_selfB"), BOB, PKH_B, ex("np_selfB"));
        adminRI(ex("ri_bobCarol"), CAROL, PKH_B, ex("np_bc"));
        adminRI(ex("ri_aliceDave"), DAVE, PKH_A, ex("np_ad"));
        adminRI(ex("ri_aliceFrank"), FRANK, PKH_A, ex("np_af"));
        adminRI(ex("ri_bobFrank"), FRANK, PKH_B, ex("np_bf"));
    }

    private void spaceDef(IRI def, IRI ref, IRI rootAdmin, IRI viaNp) {
        conn.add(def, RDF.TYPE, SpacesVocab.SPACE_DEFINITION, SPACES);
        conn.add(def, SpacesVocab.FOR_SPACE_REF, ref, SPACES);
        conn.add(def, SpacesVocab.HAS_ROOT_ADMIN, rootAdmin, SPACES);
        conn.add(def, SpacesVocab.VIA_NANOPUB, viaNp, SPACES);
        conn.add(viaNp, npa("hasLoadNumber"), vf.createLiteral(0L), ADMINGRAPH);
    }

    private void acct(IRI acct, IRI agent, Literal pkh) {
        conn.add(acct, RDF.TYPE, npa("AccountState"), STATE);
        conn.add(acct, npa("agent"), agent, STATE);
        conn.add(acct, npa("pubkey"), pkh, STATE);
    }

    private void adminRI(IRI ri, IRI agent, Literal pkh, IRI viaNp) {
        conn.add(ri, RDF.TYPE, GEN.ROLE_INSTANTIATION, SPACES);
        conn.add(ri, SpacesVocab.FOR_SPACE, S, SPACES);
        conn.add(ri, SpacesVocab.INVERSE_PROPERTY, GEN.HAS_ADMIN, SPACES);
        conn.add(ri, SpacesVocab.FOR_AGENT, agent, SPACES);
        conn.add(ri, SpacesVocab.PUBKEY_HASH, pkh, SPACES);
        conn.add(ri, SpacesVocab.VIA_NANOPUB, viaNp, SPACES);
        conn.add(viaNp, npa("hasLoadNumber"), vf.createLiteral(0L), ADMINGRAPH);
    }

    // ---------------- execution + queries ----------------

    private void runToFixpoint(String update) {
        long before = conn.size(STATE);
        while (true) {
            conn.prepareUpdate(QueryLanguage.SPARQL, update).execute();
            long after = conn.size(STATE);
            if (after <= before) break;
            before = after;
        }
    }

    private boolean isAdmin(IRI agent, IRI ref) {
        return conn.prepareBooleanQuery(QueryLanguage.SPARQL, """
                PREFIX npa: <%1$s>
                PREFIX gen: <%2$s>
                ASK { GRAPH <%3$s> {
                  ?ri a gen:RoleInstantiation ; npa:forSpaceRef <%4$s> ;
                      npa:inverseProperty gen:hasAdmin ; npa:forAgent <%5$s> .
                } }
                """.formatted(NPA.NAMESPACE, GEN.NAMESPACE, STATE, ref, agent)).evaluate();
    }

    private long distinctAdminSubjects(IRI agent) {
        return count("""
                PREFIX npa: <%1$s>
                PREFIX gen: <%2$s>
                SELECT (COUNT(DISTINCT ?ri) AS ?n) WHERE { GRAPH <%3$s> {
                  ?ri a gen:RoleInstantiation ; npa:inverseProperty gen:hasAdmin ;
                      npa:forAgent <%4$s> .
                } }
                """.formatted(NPA.NAMESPACE, GEN.NAMESPACE, STATE, agent));
    }

    private long totalAdminSubjects() {
        return count("""
                PREFIX npa: <%1$s>
                PREFIX gen: <%2$s>
                SELECT (COUNT(DISTINCT ?ri) AS ?n) WHERE { GRAPH <%3$s> {
                  ?ri a gen:RoleInstantiation ; npa:inverseProperty gen:hasAdmin .
                } }
                """.formatted(NPA.NAMESPACE, GEN.NAMESPACE, STATE));
    }

    private long count(String sparql) {
        try (var r = conn.prepareTupleQuery(QueryLanguage.SPARQL, sparql).evaluate()) {
            return ((Literal) r.next().getValue("n")).longValue();
        }
    }
}
