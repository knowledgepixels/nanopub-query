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
 * Phase 1.5 — transitional IRI-valued dual-emit on the structural-edge tiers.
 *
 * <p>Phase 1 ({@link AuthorityResolverTierIsolationTest}) re-keyed the structural edges
 * (sub-space, maintained-resource, alias) to be ref-valued only. That silently broke
 * pre-ref published query nanopubs on a mixed-version fleet — they gate on the bare Space
 * IRI ({@code ?r npa:isMaintainedBy ?space . ?ri npa:forSpace ?space}) and the ref-valued
 * object no longer binds (see {@code doc/report-2026-06-12-mixed-fleet-spaceref-breakage.md}).
 *
 * <p>Phase 1.5 restores the IRI-valued edges <em>alongside</em> the ref-valued ones
 * (validation still keys on the ref internally). These tests assert each structural admit
 * pass emits BOTH shapes, that the exact legacy gate that broke now binds again, and that
 * the IRI-valued alias edge stays inert for the internal (ref-keyed) attachment tier.
 *
 * <p>When Phase 4 drops the transitional emits, this whole class is expected to be deleted
 * together with the {@code TRANSITIONAL-DUAL-EMIT} blocks it guards.
 */
class AuthorityResolverStructuralDualEmitTest {

    private static final ValueFactory vf = SimpleValueFactory.getInstance();
    private static final IRI STATE = SpacesVocab.forSpaceState(
            "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789", 1L);
    private static final IRI SPACES = SpacesVocab.SPACES_GRAPH;

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
        // Two refs sharing IRI S; Alice admins R1, Bob admins R2. Admin rows dual-emit
        // forSpace (as the real admin tier does) so the IRI-keyed legacy gate can resolve.
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
    void subSpaceDualEmitsRefAndIriEdges() {
        IRI sc = ex("child"), sp = ex("parent");
        IRI rc = ex("ref-child"), rp = ex("ref-parent");
        refIri(rc, sc);
        refIri(rp, sp);
        admin("adm_ac", rc, sc, ALICE);
        admin("adm_ap", rp, sp, ALICE);
        extSubSpace("ss1", sc, sp, PKH_A, ex("np_ss"));
        runToFixpoint(AuthorityResolver.subSpaceAdmitUpdate(STATE, -1));

        // ref-to-ref (Phase 1, the internal/authoritative shape)
        assertTrue(edge(rc, npa("isSubSpaceOf"), rp), "ref-to-ref sub-space edge present");
        assertTrue(edge(rp, npa("hasSubSpace"), rc), "inverse ref-to-ref edge present");
        // IRI-to-IRI (Phase 1.5 transitional, for legacy consumers)
        assertTrue(edge(sc, npa("isSubSpaceOf"), sp), "IRI-valued sub-space edge dual-emitted");
        assertTrue(edge(sp, npa("hasSubSpace"), sc), "inverse IRI-valued edge dual-emitted");
    }

    @Test
    void maintainedResourceDualEmitsRefAndIriEdges() {
        IRI res = ex("resource-1");
        extMaintained("mr1", res, S, PKH_A, ex("np_mr"));
        runToFixpoint(AuthorityResolver.maintainedResourceAdmitUpdate(STATE, -1));

        // resource → ref (Phase 1)
        assertTrue(edge(res, npa("isMaintainedBy"), R1), "resource → ref edge present");
        assertTrue(edge(R1, npa("hasMaintainedResource"), res), "inverse ref edge present");
        // resource → IRI (Phase 1.5)
        assertTrue(edge(res, npa("isMaintainedBy"), S), "IRI-valued maintained edge dual-emitted");
        assertTrue(edge(S, npa("hasMaintainedResource"), res), "inverse IRI-valued edge dual-emitted");
        // isolation is preserved: still no edge to Bob's ref
        assertFalse(edge(res, npa("isMaintainedBy"), R2), "must NOT map to the un-governed ref R2");
    }

    @Test
    void aliasDualEmitsRefAndIriEdges() {
        IRI aold = ex("space-S-old");
        extAlias("al1", S, aold, PKH_A, ex("np_al"));   // canonical=S, alias=aold
        runToFixpoint(AuthorityResolver.aliasAdmitUpdate(STATE, -1));

        assertTrue(edge(aold, npa("sameAsSpace"), R1), "ref-valued alias edge present (canonical ref R1)");
        assertTrue(edge(aold, npa("sameAsSpace"), S), "IRI-valued alias edge dual-emitted (canonical IRI S)");
        assertFalse(edge(aold, npa("sameAsSpace"), R2), "must NOT map to the un-governed ref R2");
    }

    @Test
    void prefixFallbackDualEmitsRefAndIriEdges() {
        // Child SpaceRef whose id-prefix is the parent IRI; no explicit declaration, so the
        // URL-prefix fallback engages.
        IRI parent = ex("p");
        IRI child  = ex("p/sub");
        IRI rc = ex("ref-pchild"), rp = ex("ref-pparent");
        c.add(rc, SpacesVocab.SPACE_IRI, child, SPACES);
        c.add(rc, npa("hasIdPrefix"), parent, SPACES);
        c.add(rp, SpacesVocab.SPACE_IRI, parent, SPACES);
        runToFixpoint(AuthorityResolver.subSpacePrefixFallbackUpdate(STATE));

        assertTrue(edge(rc, npa("isSubSpaceOf"), rp), "derived ref-to-ref edge present");
        assertTrue(edge(child, npa("isSubSpaceOf"), parent), "derived IRI-valued edge dual-emitted");
        assertTrue(edge(parent, npa("hasSubSpace"), child), "derived inverse IRI-valued edge dual-emitted");
    }

    /**
     * The regression: the exact gate shape that get-view-displays embeds — bind the space
     * via the IRI-valued maintained edge, then require an admin RoleInstantiation
     * {@code npa:forSpace ?space}. Pre-1.5 this returned no admin (the maintained object was
     * a ref, forSpace was the IRI); Phase 1.5 makes it bind again.
     */
    @Test
    void legacyIriKeyedMaintainedGateBindsAdmin() {
        IRI res = ex("resource-1");
        extMaintained("mr1", res, S, PKH_A, ex("np_mr"));
        runToFixpoint(AuthorityResolver.maintainedResourceAdmitUpdate(STATE, -1));

        boolean adminVisible = c.prepareBooleanQuery(QueryLanguage.SPARQL, """
                PREFIX npa: <%1$s>
                PREFIX gen: <%2$s>
                ASK { GRAPH <%3$s> {
                  { <%4$s> npa:isMaintainedBy ?space . } UNION { BIND(<%4$s> AS ?space) }
                  ?ri a gen:RoleInstantiation ;
                      npa:inverseProperty gen:hasAdmin ;
                      npa:forSpace ?space ;
                      npa:forAgent ?adminAgent .
                  ?acct a npa:AccountState ;
                        npa:agent ?adminAgent ;
                        npa:pubkey ?pubkey .
                } }
                """.formatted(NPA.NAMESPACE, GEN.NAMESPACE, STATE, res)).evaluate();
        assertTrue(adminVisible,
                "legacy IRI-keyed maintained-resource gate resolves the maintaining space's admin");
    }

    /**
     * Safety: the IRI-valued alias edge must stay inert for the internal attachment tier,
     * which joins {@code ?space npa:sameAsSpace ?targetRef . ?adminRI npa:forSpaceRef ?targetRef}.
     * Because {@code forSpaceRef} is ref-valued, the IRI object can never bind ?targetRef —
     * so an attachment naming the alias still validates into exactly the canonical ref, never
     * the bare IRI.
     */
    @Test
    void iriValuedAliasEdgeIsInertForInternalAttachmentTier() {
        IRI aold = ex("space-S-old");
        IRI roleX = ex("roleX");
        extAlias("al1", S, aold, PKH_A, ex("np_al"));
        runToFixpoint(AuthorityResolver.aliasAdmitUpdate(STATE, -1));
        // Alice attaches roleX to the alias IRI; it must validate into canonical ref R1.
        extAttachment("att1", aold, roleX, PKH_A, ex("np_att"));
        runToFixpoint(AuthorityResolver.attachmentValidationUpdate(STATE, -1));

        assertTrue(hasAssignment(R1, roleX), "aliased attachment validated into canonical ref R1");
        assertFalse(hasAssignment(R2, roleX), "must NOT leak into the un-governed ref R2");
        // The IRI-valued alias object (S) must not have produced a bogus IRI-keyed assignment.
        assertFalse(hasAssignment(S, roleX), "IRI-valued alias edge produced no spurious IRI-keyed row");
        assertFalse(hasAssignment(aold, roleX), "no assignment keyed on the bare alias IRI");
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

    private void extAttachment(String name, IRI iri, IRI role, Literal pkh, IRI np) {
        IRI ra = ex(name);
        c.add(ra, RDF.TYPE, vf.createIRI(GEN.NAMESPACE, "RoleAssignment"), SPACES);
        c.add(ra, SpacesVocab.FOR_SPACE, iri, SPACES);
        c.add(ra, GEN.HAS_ROLE, role, SPACES);
        c.add(ra, SpacesVocab.PUBKEY_HASH, pkh, SPACES);
        c.add(ra, SpacesVocab.VIA_NANOPUB, np, SPACES);
        loadNum(np);
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

    private boolean hasAssignment(IRI ref, IRI role) {
        return ask("?ra a gen:RoleAssignment ; npa:forSpaceRef <" + ref + "> ; gen:hasRole <" + role + "> .");
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
