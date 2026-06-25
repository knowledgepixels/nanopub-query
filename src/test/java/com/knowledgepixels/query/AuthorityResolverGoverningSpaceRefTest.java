package com.knowledgepixels.query;

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
 * Issue #130 — uniform ref-valued {@code npa:hasGoverningSpaceRef} edge that collapses the
 * consumer authority gate and removes the cross-ref bleed.
 *
 * <p>The materializer emits, in each per-ref state graph: a reflexive self-edge from every
 * space IRI to its own {@code SpaceRef} ({@link AuthorityResolver#governingSpaceRefReflexiveUpdate}),
 * and a resource→maintaining-ref edge for every maintained resource
 * ({@link AuthorityResolver#maintainedResourceAdmitUpdate}). Both use the same predicate, so a
 * consumer gate does one mandatory hop whether the subject is a space or a maintained resource.
 *
 * <p>These tests assert: the reflexive edge is emitted once per ref (merged-across-refs falls
 * out); the maintained edge is ref-valued and isolated to the authorizing ref (no bleed to a
 * rival ref claiming the same space IRI); the simplified consumer gate from the issue resolves
 * authority for both a space and a maintained resource; and the maintained governing edge is
 * swept on invalidation while the reflexive self-edge is not.
 */
class AuthorityResolverGoverningSpaceRefTest {

    private static final ValueFactory vf = SimpleValueFactory.getInstance();
    private static final IRI STATE = SpacesVocab.forSpaceState(
            "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789", 1L);
    private static final IRI SPACES = SpacesVocab.SPACES_GRAPH;
    private static final IRI INVALIDATES = vf.createIRI("http://purl.org/nanopub/x/invalidates");

    private static IRI npa(String ln) { return vf.createIRI(NPA.NAMESPACE, ln); }
    private static IRI ex(String ln)  { return vf.createIRI("http://example.org/", ln); }

    private static final IRI S     = ex("space-S");
    private static final IRI R1    = ex("ref-R1");
    private static final IRI R2    = ex("ref-R2");
    private static final IRI ALICE = ex("alice");
    private static final IRI BOB   = ex("bob");
    private static final Literal PKH_A = vf.createLiteral("pkhA");
    private static final Literal PKH_B = vf.createLiteral("pkhB");

    private Repository repo;
    private RepositoryConnection c;

    @BeforeEach
    void setUp() {
        repo = new SailRepository(new MemoryStore());
        repo.init();
        c = repo.getConnection();
        // Two refs sharing IRI S; Alice admins R1, Bob admins R2.
        refIri(R1, S);
        refIri(R2, S);
        account("acctA", ALICE, PKH_A);
        account("acctB", BOB, PKH_B);
        admin("adm_a", R1, S, ALICE);
        admin("adm_b", R2, S, BOB);
    }

    @AfterEach
    void tearDown() {
        if (c != null) c.close();
        if (repo != null) repo.shutDown();
    }

    @Test
    void reflexiveEdgeEmittedOncePerRef() {
        runToFixpoint(AuthorityResolver.governingSpaceRefReflexiveUpdate(STATE));
        assertTrue(edge(S, npa("hasGoverningSpaceRef"), R1), "space → its ref R1");
        assertTrue(edge(S, npa("hasGoverningSpaceRef"), R2), "space → its ref R2 (claimed by another root)");
    }

    @Test
    void maintainedEdgeIsRefValuedAndIsolatedToAuthorizingRef() {
        IRI res = ex("resource-1");
        extMaintained("mr1", res, S, PKH_A, ex("np_mr"));   // declared by Alice (admin of R1)
        runToFixpoint(AuthorityResolver.maintainedResourceAdmitUpdate(STATE, -1));

        assertTrue(edge(res, npa("hasGoverningSpaceRef"), R1),
                "maintained resource → the authorizing ref R1");
        assertFalse(edge(res, npa("hasGoverningSpaceRef"), R2),
                "must NOT bleed to rival ref R2 (no cross-ref authority)");
        // The legacy ref-valued isMaintainedBy edge is still emitted alongside.
        assertTrue(edge(res, npa("isMaintainedBy"), R1), "isMaintainedBy ref edge still present");
    }

    @Test
    void simplifiedGateResolvesAdminForSpaceViaReflexiveEdge() {
        runToFixpoint(AuthorityResolver.governingSpaceRefReflexiveUpdate(STATE));
        // The space itself is the "resource": reflexive edge + admin RI on the ref.
        assertTrue(gateBinds(S, R1), "admin of S's ref R1 governs the space via the reflexive edge");
    }

    @Test
    void simplifiedGateResolvesForMaintainedResourceButNotRivalRef() {
        IRI res = ex("resource-1");
        extMaintained("mr1", res, S, PKH_A, ex("np_mr"));
        runToFixpoint(AuthorityResolver.maintainedResourceAdmitUpdate(STATE, -1));
        runToFixpoint(AuthorityResolver.governingSpaceRefReflexiveUpdate(STATE));

        assertTrue(gateBinds(res, R1),
                "admin of the authorizing ref R1 governs the maintained resource");
        assertFalse(gateBinds(res, R2),
                "admin of rival ref R2 does NOT govern the maintained resource (cross-ref bleed fixed)");
    }

    @Test
    void maintainedGoverningEdgeSweptOnInvalidation_reflexiveKept() {
        IRI res = ex("resource-1");
        IRI np = ex("np_mr");
        extMaintained("mr1", res, S, PKH_A, np);
        runToFixpoint(AuthorityResolver.maintainedResourceAdmitUpdate(STATE, -1));
        runToFixpoint(AuthorityResolver.governingSpaceRefReflexiveUpdate(STATE));
        assertTrue(edge(res, npa("hasGoverningSpaceRef"), R1), "maintained governing edge present before invalidation");
        assertTrue(edge(S, npa("hasGoverningSpaceRef"), R1), "reflexive governing edge present before invalidation");

        invalidate(np, ex("inv_mr"), PKH_A);
        run(AuthorityResolver.maintainedResourceConvenienceEdgeCleanup(STATE, -1));

        assertFalse(edge(res, npa("hasGoverningSpaceRef"), R1),
                "maintained governing edge swept after its declaration is invalidated");
        assertTrue(edge(S, npa("hasGoverningSpaceRef"), R1),
                "reflexive self-edge is self-healing and must NOT be swept by the cleanup");
    }

    // ---------------- seeding helpers ----------------

    private void refIri(IRI ref, IRI iri) { c.add(ref, SpacesVocab.SPACE_IRI, iri, SPACES); }

    private void account(String name, IRI agent, Literal pkh) {
        IRI a = ex(name);
        c.add(a, RDF.TYPE, npa("AccountState"), STATE);
        c.add(a, npa("agent"), agent, STATE);
        c.add(a, npa("pubkey"), pkh, STATE);
    }

    /** Admin RoleInstantiation, carrying npa:hasRoleType gen:AdminRole as the materializer now does (#127). */
    private void admin(String name, IRI ref, IRI iri, IRI agent) {
        IRI ri = ex(name);
        c.add(ri, RDF.TYPE, GEN.ROLE_INSTANTIATION, STATE);
        c.add(ri, SpacesVocab.FOR_SPACE_REF, ref, STATE);
        c.add(ri, SpacesVocab.FOR_SPACE, iri, STATE);
        c.add(ri, SpacesVocab.INVERSE_PROPERTY, GEN.HAS_ADMIN, STATE);
        c.add(ri, SpacesVocab.HAS_ROLE_TYPE, GEN.ADMIN_ROLE, STATE);
        c.add(ri, SpacesVocab.FOR_AGENT, agent, STATE);
    }

    private void loadNum(IRI np) { c.add(np, npa("hasLoadNumber"), vf.createLiteral(0L), NPA.GRAPH); }

    private void invalidate(IRI target, IRI inv, Literal pkh) {
        c.add(inv, INVALIDATES, target, NPA.GRAPH);
        c.add(inv, npa("hasLoadNumber"), vf.createLiteral(0L), NPA.GRAPH);
        c.add(inv, NPA.HAS_VALID_SIGNATURE_FOR_PUBLIC_KEY_HASH, pkh, NPA.GRAPH);
        c.add(target, NPA.HAS_VALID_SIGNATURE_FOR_PUBLIC_KEY_HASH, pkh, NPA.GRAPH);
    }

    private void extMaintained(String name, IRI resource, IRI space, Literal pkh, IRI np) {
        IRI d = ex(name);
        c.add(d, RDF.TYPE, SpacesVocab.MAINTAINED_RESOURCE_DECLARATION, SPACES);
        c.add(d, SpacesVocab.RESOURCE_IRI, resource, SPACES);
        c.add(d, SpacesVocab.MAINTAINER_SPACE, space, SPACES);
        c.add(d, SpacesVocab.PUBKEY_HASH, pkh, SPACES);
        c.add(d, SpacesVocab.VIA_NANOPUB, np, SPACES);
        loadNum(np);
    }

    // ---------------- execution + assertions ----------------

    private void runToFixpoint(String update) {
        long before = c.size(STATE);
        while (true) {
            c.prepareUpdate(QueryLanguage.SPARQL, update).execute();
            long after = c.size(STATE);
            if (after <= before) break;
            before = after;
        }
    }

    private void run(String update) {
        c.prepareUpdate(QueryLanguage.SPARQL, update).execute();
    }

    /** The simplified consumer authority gate from issue #130 (admin/maintainer arm). */
    private boolean gateBinds(IRI resource, IRI passedRef) {
        return c.prepareBooleanQuery(QueryLanguage.SPARQL, """
                PREFIX npa: <%1$s>
                PREFIX gen: <%2$s>
                ASK { GRAPH <%3$s> {
                  <%4$s> npa:hasGoverningSpaceRef <%5$s> .
                  ?ri a gen:RoleInstantiation ; npa:forSpaceRef <%5$s> ;
                      npa:hasRoleType ?rt ; npa:forAgent ?authAgent .
                  FILTER(?rt = gen:AdminRole || ?rt = gen:MaintainerRole)
                  ?authAcct a npa:AccountState ; npa:agent ?authAgent ; npa:pubkey ?pubkey .
                } }
                """.formatted(NPA.NAMESPACE, GEN.NAMESPACE, STATE, resource, passedRef)).evaluate();
    }

    private boolean edge(IRI s, IRI p, IRI o) {
        return c.prepareBooleanQuery(QueryLanguage.SPARQL, """
                PREFIX npa: <%1$s>
                ASK { GRAPH <%2$s> { <%3$s> <%4$s> <%5$s> . } }
                """.formatted(NPA.NAMESPACE, STATE, s, p, o)).evaluate();
    }
}
