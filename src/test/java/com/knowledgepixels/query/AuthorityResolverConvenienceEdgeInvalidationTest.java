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
 * Issue #125 finding #5 — convenience structural edges (sub-space / maintained-resource /
 * alias) must be dropped when their declaration's source nanopub is invalidated, instead
 * of going sticky until the next periodic full rebuild.
 *
 * <p>Each admit pass now reifies its contribution as a provenance link carrying
 * {@code npa:viaNanopub}; the {@code *ConvenienceEdgeCleanup} updates delete the links of
 * invalidated nanopubs and then orphan-sweep any edge no surviving link backs. These tests
 * drive admit → invalidate → cleanup on an in-memory store and assert: the edge disappears
 * when its only backer is invalidated; it survives when a second declaration still backs it;
 * and URL-prefix-derived sub-space edges are never swept.
 */
class AuthorityResolverConvenienceEdgeInvalidationTest {

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

    // ---------------- alias (the load-bearing case) ----------------

    @Test
    void aliasEdgeRemovedWhenSoleDeclarationInvalidated() {
        IRI aold = ex("space-S-old");
        IRI np = ex("np_al");
        extAlias("al1", S, aold, PKH_A, np);
        runToFixpoint(AuthorityResolver.aliasAdmitUpdate(STATE, -1));
        assertTrue(edge(aold, npa("sameAsSpace"), R1), "ref-valued alias edge present before invalidation");
        assertTrue(edge(aold, npa("sameAsSpace"), S), "IRI-valued alias edge present before invalidation");

        invalidate(np, ex("inv_al"), PKH_A);
        run(AuthorityResolver.aliasConvenienceEdgeCleanup(STATE, -1));

        assertFalse(edge(aold, npa("sameAsSpace"), R1), "ref-valued alias edge swept after invalidation");
        assertFalse(edge(aold, npa("sameAsSpace"), S), "IRI-valued alias edge swept after invalidation");
        assertFalse(ask("?l a npa:SpaceAliasLink ."), "alias provenance link removed");
    }

    @Test
    void aliasEdgeSurvivesWhileASecondDeclarationStillBacksIt() {
        IRI aold = ex("space-S-old");
        IRI np1 = ex("np_al1"), np2 = ex("np_al2");
        // Two independent admins (Alice on R1, Bob would need to admin canonical too) —
        // here both declarations come from Alice's key but as distinct nanopubs, both
        // validly backing the same alias→R1 edge.
        extAlias("al1", S, aold, PKH_A, np1);
        extAlias("al2", S, aold, PKH_A, np2);
        runToFixpoint(AuthorityResolver.aliasAdmitUpdate(STATE, -1));
        assertTrue(edge(aold, npa("sameAsSpace"), R1), "alias edge present with two backers");

        invalidate(np1, ex("inv_al1"), PKH_A);
        run(AuthorityResolver.aliasConvenienceEdgeCleanup(STATE, -1));

        assertTrue(edge(aold, npa("sameAsSpace"), R1),
                "alias edge survives — second declaration still backs it");
        assertTrue(edge(aold, npa("sameAsSpace"), S), "IRI-valued alias edge likewise survives");
    }

    // ---------------- maintained-resource ----------------

    @Test
    void maintainedEdgeRemovedWhenDeclarationInvalidated() {
        IRI res = ex("resource-1");
        IRI np = ex("np_mr");
        extMaintained("mr1", res, S, PKH_A, np);
        runToFixpoint(AuthorityResolver.maintainedResourceAdmitUpdate(STATE, -1));
        assertTrue(edge(res, npa("isMaintainedBy"), R1), "resource→ref edge present before invalidation");
        assertTrue(edge(res, npa("isMaintainedBy"), S), "IRI-valued maintained edge present before invalidation");
        assertTrue(edge(R1, npa("hasMaintainedResource"), res), "inverse edge present before invalidation");

        invalidate(np, ex("inv_mr"), PKH_A);
        run(AuthorityResolver.maintainedResourceConvenienceEdgeCleanup(STATE, -1));

        assertFalse(edge(res, npa("isMaintainedBy"), R1), "resource→ref edge swept");
        assertFalse(edge(res, npa("isMaintainedBy"), S), "IRI-valued maintained edge swept");
        assertFalse(edge(R1, npa("hasMaintainedResource"), res), "inverse edge swept");
        assertFalse(ask("?l a npa:MaintainedResourceLink ."), "maintained provenance link removed");
    }

    // ---------------- sub-space ----------------

    @Test
    void subSpaceEdgeRemovedWhenDeclarationInvalidated() {
        IRI sc = ex("child"), sp = ex("parent");
        IRI rc = ex("ref-child"), rp = ex("ref-parent");
        IRI np = ex("np_ss");
        refIri(rc, sc);
        refIri(rp, sp);
        admin("adm_ac", rc, sc, ALICE);
        admin("adm_ap", rp, sp, ALICE);
        extSubSpace("ss1", sc, sp, PKH_A, np);
        runToFixpoint(AuthorityResolver.subSpaceAdmitUpdate(STATE, -1));
        assertTrue(edge(rc, npa("isSubSpaceOf"), rp), "ref-to-ref edge present before invalidation");
        assertTrue(edge(sc, npa("isSubSpaceOf"), sp), "IRI-valued edge present before invalidation");
        assertTrue(edge(rp, npa("hasSubSpace"), rc), "inverse ref edge present before invalidation");

        invalidate(np, ex("inv_ss"), PKH_A);
        run(AuthorityResolver.subSpaceConvenienceEdgeCleanup(STATE, -1));

        assertFalse(edge(rc, npa("isSubSpaceOf"), rp), "ref-to-ref edge swept");
        assertFalse(edge(sc, npa("isSubSpaceOf"), sp), "IRI-valued edge swept");
        assertFalse(edge(rp, npa("hasSubSpace"), rc), "inverse ref edge swept");
        assertFalse(ask("?l a npa:SubSpaceLink ."), "sub-space provenance link removed");
    }

    @Test
    void prefixDerivedSubSpaceEdgeNotSweptByCleanup() {
        // A URL-prefix-derived sub-space edge (no source nanopub) must survive a cleanup
        // pass, because its DerivedSubSpaceLink — not a declaration — backs it.
        IRI parent = ex("p");
        IRI child  = ex("p/sub");
        IRI rc = ex("ref-pchild"), rp = ex("ref-pparent");
        c.add(rc, SpacesVocab.SPACE_IRI, child, SPACES);
        c.add(rc, npa("hasIdPrefix"), parent, SPACES);
        c.add(rp, SpacesVocab.SPACE_IRI, parent, SPACES);
        runToFixpoint(AuthorityResolver.subSpacePrefixFallbackUpdate(STATE));
        assertTrue(edge(rc, npa("isSubSpaceOf"), rp), "derived ref edge present");

        // A cleanup with nothing invalidated must not touch derived edges.
        run(AuthorityResolver.subSpaceConvenienceEdgeCleanup(STATE, -1));

        assertTrue(edge(rc, npa("isSubSpaceOf"), rp), "derived ref edge survives cleanup");
        assertTrue(edge(child, npa("isSubSpaceOf"), parent), "derived IRI-valued edge survives cleanup");
    }

    // ---------------- seeding helpers ----------------

    private void refIri(IRI ref, IRI iri) { c.add(ref, SpacesVocab.SPACE_IRI, iri, SPACES); }

    private void account(String name, IRI agent, Literal pkh) {
        IRI a = ex(name);
        c.add(a, RDF.TYPE, npa("AccountState"), STATE);
        c.add(a, npa("agent"), agent, STATE);
        c.add(a, npa("pubkey"), pkh, STATE);
    }

    private void admin(String name, IRI ref, IRI iri, IRI agent) {
        IRI ri = ex(name);
        c.add(ri, RDF.TYPE, GEN.ROLE_INSTANTIATION, STATE);
        c.add(ri, SpacesVocab.FOR_SPACE_REF, ref, STATE);
        c.add(ri, SpacesVocab.FOR_SPACE, iri, STATE);
        c.add(ri, SpacesVocab.INVERSE_PROPERTY, GEN.HAS_ADMIN, STATE);
        c.add(ri, SpacesVocab.FOR_AGENT, agent, STATE);
    }

    private void loadNum(IRI np) { c.add(np, npa("hasLoadNumber"), vf.createLiteral(0L), NPA.GRAPH); }

    /**
     * Seeds an invalidation in npa:graph: {@code inv npx:invalidates target}, a load number,
     * and the same-publisher signature bridge (both nanopubs signed by {@code pkh}).
     */
    private void invalidate(IRI target, IRI inv, Literal pkh) {
        c.add(inv, INVALIDATES, target, NPA.GRAPH);
        c.add(inv, npa("hasLoadNumber"), vf.createLiteral(0L), NPA.GRAPH);
        c.add(inv, NPA.HAS_VALID_SIGNATURE_FOR_PUBLIC_KEY_HASH, pkh, NPA.GRAPH);
        c.add(target, NPA.HAS_VALID_SIGNATURE_FOR_PUBLIC_KEY_HASH, pkh, NPA.GRAPH);
    }

    private void extAlias(String name, IRI canonical, IRI alias, Literal pkh, IRI np) {
        IRI d = ex(name);
        c.add(d, RDF.TYPE, SpacesVocab.SPACE_ALIAS_DECLARATION, SPACES);
        c.add(d, SpacesVocab.CANONICAL_SPACE, canonical, SPACES);
        c.add(d, SpacesVocab.ALIAS_SPACE, alias, SPACES);
        c.add(d, SpacesVocab.PUBKEY_HASH, pkh, SPACES);
        c.add(d, SpacesVocab.VIA_NANOPUB, np, SPACES);
        loadNum(np);
    }

    private void extSubSpace(String name, IRI child, IRI parent, Literal pkh, IRI np) {
        IRI d = ex(name);
        c.add(d, RDF.TYPE, SpacesVocab.SUB_SPACE_DECLARATION, SPACES);
        c.add(d, SpacesVocab.CHILD_SPACE, child, SPACES);
        c.add(d, SpacesVocab.PARENT_SPACE, parent, SPACES);
        c.add(d, SpacesVocab.PUBKEY_HASH, pkh, SPACES);
        c.add(d, SpacesVocab.VIA_NANOPUB, np, SPACES);
        loadNum(np);
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

    private boolean edge(IRI s, IRI p, IRI o) {
        return ask("<" + s + "> <" + p + "> <" + o + "> .");
    }

    private boolean ask(String pattern) {
        return c.prepareBooleanQuery(QueryLanguage.SPARQL, """
                PREFIX npa: <%1$s>
                PREFIX gen: <%2$s>
                ASK { GRAPH <%3$s> { %4$s } }
                """.formatted(NPA.NAMESPACE, GEN.NAMESPACE, STATE, pattern)).evaluate();
    }
}
