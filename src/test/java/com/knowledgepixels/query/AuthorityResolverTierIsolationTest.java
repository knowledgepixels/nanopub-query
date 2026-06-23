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
 * End-to-end execution tests for the ref-keyed downstream tiers (Phase 1): attachment,
 * member (with role-property enrichment), alias, sub-space, maintained-resource, and
 * invalidation. Each runs the real tier SPARQL against an in-memory store seeded with two
 * refs (R1, R2) that share one Space IRI but have disjoint admins, and asserts the
 * relationship attaches only to the ref(s) the acting admin governs — never the other.
 *
 * <p>Admin rows are seeded directly into the state graph (the admin closure itself is
 * covered by {@link AuthorityResolverSpaceRefIsolationTest}) so each downstream tier is
 * exercised in isolation.
 */
class AuthorityResolverTierIsolationTest {

    private static final ValueFactory vf = SimpleValueFactory.getInstance();
    private static final IRI STATE = SpacesVocab.forSpaceState(
            "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789", 1L);
    private static final IRI SPACES = SpacesVocab.SPACES_GRAPH;
    private static final IRI ADMINGRAPH = NPA.GRAPH;
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
    void attachmentValidatesOnlyInTheGrantersRef() {
        IRI roleX = ex("roleX");
        extAttachment("att1", S, roleX, PKH_A, ex("np_att"));   // Alice attaches roleX to S
        runToFixpoint(AuthorityResolver.attachmentValidationUpdate(STATE, -1));

        assertTrue(hasAssignment(R1, roleX), "attachment validated in Alice's ref R1");
        assertFalse(hasAssignment(R2, roleX), "attachment must NOT leak into Bob's ref R2");
    }

    @Test
    void memberValidatesPerRefAndCarriesRoleProperty() {
        IRI roleX = ex("roleX");
        IRI pred  = ex("hasMember");
        extAttachment("att1", S, roleX, PKH_A, ex("np_att"));
        runToFixpoint(AuthorityResolver.attachmentValidationUpdate(STATE, -1));
        // Member role declaration (inverse-property direction) + a grant by Alice.
        roleDecl("rd1", roleX, GEN.MEMBER_ROLE, GEN.HAS_INVERSE_PROPERTY, pred, ex("np_rd"));
        extInstantiation("ri_m", S, ex("mallory"), SpacesVocab.INVERSE_PROPERTY, pred, PKH_A, ex("np_m"));
        runToFixpoint(AuthorityResolver.nonAdminTierUpdate(
                STATE, -1, GEN.MEMBER_ROLE, AuthorityResolver.PUBLISHER_IS_ADMIN));

        assertTrue(memberInRef(R1, ex("mallory")), "member validated in R1");
        assertFalse(memberInRef(R2, ex("mallory")), "member must NOT appear in R2");
        assertTrue(memberCarriesProperty(R1, ex("mallory"), pred),
                "materialized member row carries its role property (enrichment)");
    }

    @Test
    void customPredicateResolvesDirectionAndTierFromRoleDeclaration() {
        // A maintainer assignment using a custom predicate (gen:hasMaintainer) the
        // extractor can't classify, so it emitted a NEUTRAL instantiation
        // (npa:rolePredicate + raw bindingSubject/bindingObject). The materializer must
        // resolve its direction (INVERSE here, from the role declaration's
        // gen:hasInverseProperty) and tier (MaintainerRole), then materialize it exactly
        // like a pre-classified grant — carrying npa:inverseProperty for the read side.
        IRI roleX = ex("roleMaint");
        IRI pred  = ex("hasMaintainer");
        extAttachment("att1", S, roleX, PKH_A, ex("np_att"));   // Alice attaches the role to S
        runToFixpoint(AuthorityResolver.attachmentValidationUpdate(STATE, -1));
        roleDecl("rd1", roleX, GEN.MAINTAINER_ROLE, GEN.HAS_INVERSE_PROPERTY, pred, ex("np_rd"));
        // INVERSE source shape <space> pred <agent>: bindingSubject=space, bindingObject=agent.
        extNeutralInstantiation("ri_cm", S, ex("morgan"), pred, PKH_A, ex("np_cm"));
        runToFixpoint(AuthorityResolver.nonAdminTierUpdate(
                STATE, -1, GEN.MAINTAINER_ROLE, AuthorityResolver.PUBLISHER_IS_ADMIN));

        assertTrue(memberInRef(R1, ex("morgan")),
                "custom-predicate maintainer resolved and materialized in R1");
        assertTrue(memberCarriesProperty(R1, ex("morgan"), pred),
                "materialized row carries the resolved npa:inverseProperty (read by get-space-members)");
        assertFalse(memberInRef(R2, ex("morgan")),
                "must NOT leak into Bob's ref R2");
    }

    @Test
    void customPredicateRegularDirectionSwapsSpaceAndAgent() {
        // Same path, REGULAR direction: role declaration says the predicate is the
        // gen:hasRegularProperty, so the source shape is <agent> pred <space> —
        // bindingSubject is the agent, bindingObject is the space.
        IRI roleX = ex("roleMaintReg");
        IRI pred  = ex("maintains");
        extAttachment("att1", S, roleX, PKH_A, ex("np_att"));
        runToFixpoint(AuthorityResolver.attachmentValidationUpdate(STATE, -1));
        roleDecl("rd1", roleX, GEN.MAINTAINER_ROLE, GEN.HAS_REGULAR_PROPERTY, pred, ex("np_rd"));
        // REGULAR source shape <agent> pred <space>: bindingSubject=agent, bindingObject=space.
        extNeutralInstantiation("ri_cr", ex("morgan"), S, pred, PKH_A, ex("np_cr"));
        runToFixpoint(AuthorityResolver.nonAdminTierUpdate(
                STATE, -1, GEN.MAINTAINER_ROLE, AuthorityResolver.PUBLISHER_IS_ADMIN));

        assertTrue(memberInRef(R1, ex("morgan")),
                "regular-direction custom-predicate maintainer resolved in R1");
        assertTrue(maintainerCarriesRegularProperty(R1, ex("morgan"), pred),
                "materialized row carries the resolved npa:regularProperty");
    }

    @Test
    void aliasEmitsRefValuedEdgeOnlyForTheGovernedCanonicalRef() {
        IRI aold = ex("space-S-old");
        extAlias("al1", S, aold, PKH_A, ex("np_al"));          // Alice: <S> owl:sameAs <aold>
        runToFixpoint(AuthorityResolver.aliasAdmitUpdate(STATE, -1));

        assertTrue(sameAsEdge(aold, R1), "alias maps to R1 (Alice governs it)");
        assertFalse(sameAsEdge(aold, R2), "alias must NOT map to R2 (Alice is not its admin)");
    }

    @Test
    void aliasAntiHijackBlocksWhenAliasHasAForeignAdmin() {
        IRI aold = ex("space-active");
        IRI aref = ex("ref-active");
        IRI eve  = ex("eve");
        // The alias is an independently-governed live space (admin Eve, not an R1 admin).
        refIri(aref, aold);
        admin("adm_e", aref, aold, eve);
        extAlias("al1", S, aold, PKH_A, ex("np_al"));
        runToFixpoint(AuthorityResolver.aliasAdmitUpdate(STATE, -1));

        assertFalse(sameAsEdge(aold, R1),
                "anti-hijack: alias with a foreign admin (Eve ∉ admins(R1)) is rejected");
    }

    @Test
    void subSpaceEmitsRefToRefEdge() {
        IRI sc = ex("child"), sp = ex("parent");
        IRI rc = ex("ref-child"), rp = ex("ref-parent");
        refIri(rc, sc);
        refIri(rp, sp);
        admin("adm_ac", rc, sc, ALICE);   // Alice admins both child and parent refs
        admin("adm_ap", rp, sp, ALICE);
        extSubSpace("ss1", sc, sp, PKH_A, ex("np_ss"));
        runToFixpoint(AuthorityResolver.subSpaceAdmitUpdate(STATE, -1));

        assertTrue(edge(rc, npa("isSubSpaceOf"), rp), "ref-to-ref sub-space edge emitted");
        assertTrue(edge(rp, npa("hasSubSpace"), rc), "inverse ref-to-ref edge emitted");
    }

    @Test
    void maintainedResourceEmitsResourceToRefEdge() {
        IRI res = ex("resource-1");
        extMaintained("mr1", res, S, PKH_A, ex("np_mr"));     // Alice: <res> isMaintainedBy <S>
        runToFixpoint(AuthorityResolver.maintainedResourceAdmitUpdate(STATE, -1));

        assertTrue(edge(res, npa("isMaintainedBy"), R1), "resource → R1 edge (Alice governs R1)");
        assertFalse(edge(res, npa("isMaintainedBy"), R2), "resource must NOT map to R2");
    }

    @Test
    void invalidationRemovesRefScopedRows() {
        IRI roleX = ex("roleX");
        IRI attNp = ex("np_att");
        extAttachment("att1", S, roleX, PKH_A, attNp);
        sig(attNp, PKH_A);                       // attachment signed by Alice (pkhA)
        runToFixpoint(AuthorityResolver.attachmentValidationUpdate(STATE, -1));
        assertTrue(hasAssignment(R1, roleX), "precondition: assignment materialized");

        // A later nanopub from the SAME publisher (pkhA) invalidates the attachment.
        IRI invNp = ex("np_inv");
        c.add(invNp, INVALIDATES, attNp, ADMINGRAPH);
        c.add(invNp, npa("hasLoadNumber"), vf.createLiteral(1L), ADMINGRAPH);
        sig(invNp, PKH_A);
        executeUpdate(AuthorityResolver.roleAssignmentInvalidationDelete(STATE, -1));

        assertFalse(hasAssignment(R1, roleX),
                "self-retraction (same pubkey) removes the ref-scoped RoleAssignment");
    }

    @Test
    void foreignInvalidationIsIgnored_issue112() {
        // A validly-signed nanopub from a DIFFERENT key must not invalidate another
        // publisher's materialized state — the griefing/DoS gate of issue #112.
        IRI roleX = ex("roleX");
        IRI attNp = ex("np_att");
        extAttachment("att1", S, roleX, PKH_A, attNp);
        sig(attNp, PKH_A);                       // attachment signed by Alice (pkhA)
        runToFixpoint(AuthorityResolver.attachmentValidationUpdate(STATE, -1));
        assertTrue(hasAssignment(R1, roleX), "precondition: assignment materialized");

        // Mallory (pkhB) tries to retract Alice's (pkhA) attachment.
        IRI invNp = ex("np_inv");
        c.add(invNp, INVALIDATES, attNp, ADMINGRAPH);
        c.add(invNp, npa("hasLoadNumber"), vf.createLiteral(1L), ADMINGRAPH);
        sig(invNp, PKH_B);
        executeUpdate(AuthorityResolver.roleAssignmentInvalidationDelete(STATE, -1));

        assertTrue(hasAssignment(R1, roleX),
                "foreign-key invalidation must NOT remove the RoleAssignment");
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

    private void loadNum(IRI np) { c.add(np, npa("hasLoadNumber"), vf.createLiteral(0L), ADMINGRAPH); }

    /** Records the signing pubkey-hash of a nanopub (issue #112 self-retraction gate). */
    private void sig(IRI np, Literal pkh) {
        c.add(np, NPA.HAS_VALID_SIGNATURE_FOR_PUBLIC_KEY_HASH, pkh, ADMINGRAPH);
    }

    private void extAttachment(String name, IRI iri, IRI role, Literal pkh, IRI np) {
        IRI ra = ex(name);
        c.add(ra, RDF.TYPE, vf.createIRI(GEN.NAMESPACE, "RoleAssignment"), SPACES);
        c.add(ra, SpacesVocab.FOR_SPACE, iri, SPACES);
        c.add(ra, GEN.HAS_ROLE, role, SPACES);
        c.add(ra, SpacesVocab.PUBKEY_HASH, pkh, SPACES);
        c.add(ra, SpacesVocab.VIA_NANOPUB, np, SPACES);
        loadNum(np);
    }

    private void roleDecl(String name, IRI role, IRI tier, IRI dirDeclProp, IRI pred, IRI np) {
        IRI rd = ex(name);
        c.add(rd, RDF.TYPE, SpacesVocab.ROLE_DECLARATION, SPACES);
        c.add(rd, SpacesVocab.HAS_ROLE_TYPE, tier, SPACES);
        c.add(rd, SpacesVocab.ROLE, role, SPACES);
        c.add(rd, dirDeclProp, pred, SPACES);
        c.add(rd, SpacesVocab.VIA_NANOPUB, np, SPACES);
        loadNum(np);
    }

    private void extInstantiation(String name, IRI iri, IRI agent, IRI dirProp, IRI pred, Literal pkh, IRI np) {
        IRI ri = ex(name);
        c.add(ri, RDF.TYPE, GEN.ROLE_INSTANTIATION, SPACES);
        c.add(ri, SpacesVocab.FOR_SPACE, iri, SPACES);
        c.add(ri, SpacesVocab.FOR_AGENT, agent, SPACES);
        c.add(ri, dirProp, pred, SPACES);
        c.add(ri, SpacesVocab.PUBKEY_HASH, pkh, SPACES);
        c.add(ri, SpacesVocab.VIA_NANOPUB, np, SPACES);
        loadNum(np);
    }

    /**
     * A neutral (unresolved) instantiation as the extractor emits it for a custom role
     * predicate: raw bindingSubject/bindingObject + npa:rolePredicate, no direction or
     * forSpace/forAgent. The materializer resolves those from the role declaration.
     */
    private void extNeutralInstantiation(String name, IRI bindingSubject, IRI bindingObject,
                                         IRI pred, Literal pkh, IRI np) {
        IRI ri = ex(name);
        c.add(ri, RDF.TYPE, GEN.ROLE_INSTANTIATION, SPACES);
        c.add(ri, SpacesVocab.ROLE_PREDICATE, pred, SPACES);
        c.add(ri, SpacesVocab.BINDING_SUBJECT, bindingSubject, SPACES);
        c.add(ri, SpacesVocab.BINDING_OBJECT, bindingObject, SPACES);
        c.add(ri, SpacesVocab.PUBKEY_HASH, pkh, SPACES);
        c.add(ri, SpacesVocab.VIA_NANOPUB, np, SPACES);
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

    private void executeUpdate(String update) {
        c.prepareUpdate(QueryLanguage.SPARQL, update).execute();
    }

    private boolean hasAssignment(IRI ref, IRI role) {
        return ask("?ra a gen:RoleAssignment ; npa:forSpaceRef <" + ref + "> ; gen:hasRole <" + role + "> .");
    }

    private boolean memberInRef(IRI ref, IRI agent) {
        return ask("?ri a gen:RoleInstantiation ; npa:forSpaceRef <" + ref + "> ; npa:forAgent <" + agent + "> ."
                + " FILTER NOT EXISTS { ?ri npa:inverseProperty gen:hasAdmin }");
    }

    private boolean memberCarriesProperty(IRI ref, IRI agent, IRI pred) {
        return ask("?ri a gen:RoleInstantiation ; npa:forSpaceRef <" + ref + "> ; npa:forAgent <" + agent + "> ;"
                + " npa:inverseProperty <" + pred + "> .");
    }

    private boolean maintainerCarriesRegularProperty(IRI ref, IRI agent, IRI pred) {
        return ask("?ri a gen:RoleInstantiation ; npa:forSpaceRef <" + ref + "> ; npa:forAgent <" + agent + "> ;"
                + " npa:regularProperty <" + pred + "> .");
    }

    private boolean sameAsEdge(IRI alias, IRI canonRef) {
        return ask("<" + alias + "> npa:sameAsSpace <" + canonRef + "> .");
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
