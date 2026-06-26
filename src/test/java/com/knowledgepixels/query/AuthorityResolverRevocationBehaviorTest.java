package com.knowledgepixels.query;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Date;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.DCTERMS;
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
 * End-to-end execution tests for role revocation / detachment (issue #129), run against an
 * in-memory store the same way {@link AuthorityResolverTierIsolationTest} exercises the
 * downstream tiers. Validates the runtime SPARQL behaviour the string-contract tests in
 * {@link AuthorityResolverRevocationTest} can't: the authorization-scoped latest-wins, the
 * per-tier authority matrix, the root-admin exemption, and the displacement deletes.
 */
class AuthorityResolverRevocationBehaviorTest {

    private static final ValueFactory vf = SimpleValueFactory.getInstance();
    private static final IRI STATE = SpacesVocab.forSpaceState(
            "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789", 1L);
    private static final IRI SPACES = SpacesVocab.SPACES_GRAPH;
    private static final IRI ADMINGRAPH = NPA.GRAPH;

    private static IRI npa(String ln) { return vf.createIRI(NPA.NAMESPACE, ln); }
    private static IRI ex(String ln)  { return vf.createIRI("http://example.org/", ln); }

    private static final IRI S      = ex("space-S");
    private static final IRI R1     = ex("ref-R1");
    private static final IRI ALICE  = ex("alice");     // admin of R1
    private static final IRI DAVE   = ex("dave");      // root admin of R1
    private static final Literal PKH_A = vf.createLiteral("pkhA");   // Alice
    private static final Literal PKH_D = vf.createLiteral("pkhD");   // Dave (root)

    private static final IRI ROLE_X = ex("roleX");
    private static final IRI PRED   = ex("hasMember");
    private static final Date OLD = new Date(1_000_000L);
    private static final Date NEW = new Date(2_000_000L);

    private Repository repo;
    private RepositoryConnection c;

    @BeforeEach
    void setUp() {
        repo = new SailRepository(new MemoryStore());
        repo.init();
        c = repo.getConnection();
        refIri(R1, S);
        account("acctA", ALICE, PKH_A);
        account("acctD", DAVE, PKH_D);
        admin("adm_a", R1, S, ALICE);
        // Dave is a root admin (constitutional) AND materialized as an admin RI.
        admin("adm_d", R1, S, DAVE);
        c.add(ex("def_R1"), RDF.TYPE, SpacesVocab.SPACE_DEFINITION, SPACES);
        c.add(ex("def_R1"), SpacesVocab.FOR_SPACE_REF, R1, SPACES);
        c.add(ex("def_R1"), SpacesVocab.HAS_ROOT_ADMIN, DAVE, SPACES);
    }

    @AfterEach
    void tearDown() {
        if (c != null) c.close();
        if (repo != null) repo.shutDown();
    }

    // ---------------- non-admin instantiation revocation: inline suppression ----------------

    @Test
    void memberRevocation_newerThanGrant_isSuppressed() {
        seedMemberGrant(ex("mallory"), OLD);
        revocation("rev1", ex("mallory"), ROLE_X, PKH_A, NEW);   // admin-authored, newer
        runMemberTier();
        assertFalse(memberInRef(ex("mallory")),
                "a newer admin-authored revocation suppresses the member grant");
    }

    @Test
    void memberReassignment_newerThanRevocation_wins() {
        seedMemberGrant(ex("mallory"), NEW);                     // grant is the newer assertion
        revocation("rev1", ex("mallory"), ROLE_X, PKH_A, OLD);   // older revocation loses
        runMemberTier();
        assertTrue(memberInRef(ex("mallory")),
                "a re-assignment newer than the revocation re-grants the role");
    }

    @Test
    void memberRevocation_byUnauthorizedKey_isIgnored() {
        seedMemberGrant(ex("mallory"), OLD);
        account("acctNobody", ex("nobody"), vf.createLiteral("pkhN"));   // holds no role
        revocation("rev1", ex("mallory"), ROLE_X, vf.createLiteral("pkhN"), NEW);
        runMemberTier();
        assertTrue(memberInRef(ex("mallory")),
                "a revocation from a key with no qualifying tier does not suppress");
    }

    @Test
    void memberRevocation_byMaintainer_isAuthorized() {
        seedMemberGrant(ex("mallory"), OLD);
        // A maintainer of R1 (not admin) authors the revocation — allowed for the member tier.
        account("acctMaint", ex("mona"), vf.createLiteral("pkhM"));
        tierRI("mri", R1, ex("mona"), GEN.MAINTAINER_ROLE);
        revocation("rev1", ex("mallory"), ROLE_X, vf.createLiteral("pkhM"), NEW);
        runMemberTier();
        assertFalse(memberInRef(ex("mallory")),
                "a maintainer may revoke a member (matrix), even via the admin-pub arm");
    }

    @Test
    void observerSelfLeave_isAuthorized() {
        // Observer grant for mallory, then mallory revokes their own role (self-leave).
        seedObserverGrant(ex("mallory"), OLD);
        account("acctMallory", ex("mallory"), vf.createLiteral("pkhMal"));
        revocation("rev1", ex("mallory"), ROLE_X, vf.createLiteral("pkhMal"), NEW);
        runToFixpoint(AuthorityResolver.nonAdminTierUpdate(
                STATE, -1, GEN.OBSERVER_ROLE, AuthorityResolver.PUBLISHER_IS_ADMIN));
        assertFalse(memberInRef(ex("mallory")), "an agent may always leave its own role");
    }

    // ---------------- non-admin instantiation revocation: displacement delete ----------------

    @Test
    void memberRevocation_displacesAlreadyMaterializedRow() {
        seedMemberGrant(ex("mallory"), OLD);
        runMemberTier();
        assertTrue(memberInRef(ex("mallory")), "precondition: member materialized");
        // Revocation arrives in a later cycle; the displacement DELETE removes the row.
        revocation("rev1", ex("mallory"), ROLE_X, PKH_A, NEW);
        executeUpdate(AuthorityResolver.roleRevocationDelete(STATE, -1, GEN.MEMBER_ROLE));
        assertFalse(memberInRef(ex("mallory")),
                "displacement delete removes an already-materialized revoked member");
    }

    // ---------------- detachment ----------------

    @Test
    void roleDetachment_displacesAttachment() {
        extAttachment("att1", S, ROLE_X, PKH_A, ex("np_att"), OLD);
        runToFixpoint(AuthorityResolver.attachmentValidationUpdate(STATE, -1));
        assertTrue(hasAssignment(R1, ROLE_X), "precondition: attachment materialized");
        detachment("det1", ROLE_X, PKH_A, NEW);                  // admin-authored, newer
        executeUpdate(AuthorityResolver.roleDetachmentDelete(STATE, -1));
        assertFalse(hasAssignment(R1, ROLE_X),
                "a newer admin-authored detachment removes the attachment");
    }

    // ---------------- admin revocation + root-admin exemption ----------------

    @Test
    void adminRevocation_removesPlainAdmin_butExemptsRootAdmin() {
        // Carol (a plain admin) revokes both Alice (plain admin) and Dave (root admin). The
        // seeded admin RIs are given a viaNanopub + extraction-side dct:created so the delete's
        // candidate-timestamp join resolves. Alice's row is removed; Dave's is exempt (root).
        IRI carol = ex("carol");
        Literal pkhC = vf.createLiteral("pkhC");
        account("acctC", carol, pkhC);
        admin("adm_c", R1, S, carol);
        attachAdminSource(ex("adm_a"), "anp_a", OLD);   // Alice's seeded admin RI
        attachAdminSource(ex("adm_d"), "anp_d", OLD);   // Dave's seeded admin RI
        adminRevocation("revA", ALICE, pkhC, NEW);      // Carol revokes Alice
        adminRevocation("revD", DAVE, pkhC, NEW);       // Carol revokes Dave (root)
        executeUpdate(AuthorityResolver.adminRevocationDelete(STATE, -1));

        assertFalse(adminInRef(ALICE), "a plain admin is removed by a peer admin's revocation");
        assertTrue(adminInRef(DAVE), "a root admin is constitutional — revocation is inert");
    }

    // ---------------- read-path canary (published consumer queries) ----------------

    @Test
    void canary_getSpaceMembers_validatedFlagFlipsFalseOnRevocation() {
        // The published get-space-members query (knowledgepixels/nanodash, GET_SPACE_MEMBERS)
        // computes ?validated via EXISTS over the CURRENT space-state graph:
        //   graph ?g { ?vri a gen:RoleInstantiation ; npa:forSpaceRef ?ref ;
        //                    npa:forAgent ?member ; npa:viaNanopub ?np }
        // A revoked member must flip ?validated true -> false (the member still lists from the
        // raw spacesGraph, but is flagged unvalidated). No query change is needed.
        seedMemberGrant(ex("mallory"), OLD);
        runMemberTier();
        assertTrue(memberValidated(ex("mallory"), ex("np_m")),
                "precondition: member is validated in the state graph");
        revocation("rev1", ex("mallory"), ROLE_X, PKH_A, NEW);
        executeUpdate(AuthorityResolver.roleRevocationDelete(STATE, -1, GEN.MEMBER_ROLE));
        assertFalse(memberValidated(ex("mallory"), ex("np_m")),
                "get-space-members ?validated flips to false for a revoked member");
    }

    @Test
    void canary_getSpaceRoles_roleDisappearsOnDetachment() {
        // The published get-space-roles query reads gen:RoleAssignment rows from the current
        // space-state graph (npa:forSpaceRef ?ref ; gen:hasRole ?role). A detached role must
        // disappear from that listing. No query change is needed.
        extAttachment("att1", S, ROLE_X, PKH_A, ex("np_att"), OLD);
        runToFixpoint(AuthorityResolver.attachmentValidationUpdate(STATE, -1));
        assertTrue(hasAssignment(R1, ROLE_X), "precondition: role listed for the ref");
        detachment("det1", ROLE_X, PKH_A, NEW);
        executeUpdate(AuthorityResolver.roleDetachmentDelete(STATE, -1));
        assertFalse(hasAssignment(R1, ROLE_X),
                "get-space-roles no longer lists a detached role");
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

    /** A materialized non-admin RI carrying npa:hasRoleType (used to qualify a revoker). */
    private void tierRI(String name, IRI ref, IRI agent, IRI tier) {
        IRI ri = ex(name);
        c.add(ri, RDF.TYPE, GEN.ROLE_INSTANTIATION, STATE);
        c.add(ri, SpacesVocab.FOR_SPACE_REF, ref, STATE);
        c.add(ri, SpacesVocab.FOR_AGENT, agent, STATE);
        c.add(ri, SpacesVocab.HAS_ROLE_TYPE, tier, STATE);
    }

    private void loadNum(IRI np) { c.add(np, npa("hasLoadNumber"), vf.createLiteral(0L), ADMINGRAPH); }

    private void extAttachment(String name, IRI iri, IRI role, Literal pkh, IRI np, Date created) {
        IRI ra = ex(name);
        c.add(ra, RDF.TYPE, GEN.ROLE_ASSIGNMENT, SPACES);
        c.add(ra, SpacesVocab.FOR_SPACE, iri, SPACES);
        c.add(ra, GEN.HAS_ROLE, role, SPACES);
        c.add(ra, SpacesVocab.PUBKEY_HASH, pkh, SPACES);
        c.add(ra, SpacesVocab.VIA_NANOPUB, np, SPACES);
        c.add(ra, DCTERMS.CREATED, vf.createLiteral(created), SPACES);
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

    private void extInstantiation(String name, IRI iri, IRI agent, IRI pred, Literal pkh, IRI np, Date created) {
        IRI ri = ex(name);
        c.add(ri, RDF.TYPE, GEN.ROLE_INSTANTIATION, SPACES);
        c.add(ri, SpacesVocab.FOR_SPACE, iri, SPACES);
        c.add(ri, SpacesVocab.FOR_AGENT, agent, SPACES);
        c.add(ri, SpacesVocab.INVERSE_PROPERTY, pred, SPACES);
        c.add(ri, SpacesVocab.PUBKEY_HASH, pkh, SPACES);
        c.add(ri, SpacesVocab.VIA_NANOPUB, np, SPACES);
        c.add(ri, DCTERMS.CREATED, vf.createLiteral(created), SPACES);
        loadNum(np);
    }

    /** Seeds a complete admin-authored member grant for {@code agent} (created at {@code d}). */
    private void seedMemberGrant(IRI agent, Date d) {
        extAttachment("att1", S, ROLE_X, PKH_A, ex("np_att"), OLD);
        runToFixpoint(AuthorityResolver.attachmentValidationUpdate(STATE, -1));
        roleDecl("rd1", ROLE_X, GEN.MEMBER_ROLE, GEN.HAS_INVERSE_PROPERTY, PRED, ex("np_rd"));
        extInstantiation("ri_m", S, agent, PRED, PKH_A, ex("np_m"), d);
    }

    private void seedObserverGrant(IRI agent, Date d) {
        extAttachment("att1", S, ROLE_X, PKH_A, ex("np_att"), OLD);
        runToFixpoint(AuthorityResolver.attachmentValidationUpdate(STATE, -1));
        roleDecl("rd1", ROLE_X, GEN.OBSERVER_ROLE, GEN.HAS_INVERSE_PROPERTY, PRED, ex("np_rd"));
        extInstantiation("ri_o", S, agent, PRED, PKH_A, ex("np_o"), d);
    }

    private void runMemberTier() {
        runToFixpoint(AuthorityResolver.nonAdminTierUpdate(
                STATE, -1, GEN.MEMBER_ROLE, AuthorityResolver.PUBLISHER_IS_ADMIN));
    }

    private void revocation(String name, IRI agent, IRI role, Literal pkh, Date created) {
        IRI r = ex(name);
        IRI np = ex(name + "_np");
        c.add(r, RDF.TYPE, SpacesVocab.ROLE_REVOCATION, SPACES);
        c.add(r, SpacesVocab.FOR_SPACE, S, SPACES);
        c.add(r, SpacesVocab.FOR_AGENT, agent, SPACES);
        c.add(r, SpacesVocab.REVOKED_ROLE, role, SPACES);
        c.add(r, SpacesVocab.PUBKEY_HASH, pkh, SPACES);
        c.add(r, SpacesVocab.VIA_NANOPUB, np, SPACES);
        c.add(r, DCTERMS.CREATED, vf.createLiteral(created), SPACES);
        loadNum(np);
    }

    private void detachment(String name, IRI role, Literal pkh, Date created) {
        IRI d = ex(name);
        IRI np = ex(name + "_np");
        c.add(d, RDF.TYPE, SpacesVocab.ROLE_DETACHMENT, SPACES);
        c.add(d, SpacesVocab.FOR_SPACE, S, SPACES);
        c.add(d, SpacesVocab.REVOKED_ROLE, role, SPACES);
        c.add(d, SpacesVocab.PUBKEY_HASH, pkh, SPACES);
        c.add(d, SpacesVocab.VIA_NANOPUB, np, SPACES);
        c.add(d, DCTERMS.CREATED, vf.createLiteral(created), SPACES);
        loadNum(np);
    }

    /** Gives an already-seeded admin RI a viaNanopub + an extraction-side dct:created source. */
    private void attachAdminSource(IRI adminRi, String npName, Date created) {
        IRI np = ex(npName);
        c.add(adminRi, SpacesVocab.VIA_NANOPUB, np, STATE);
        IRI src = ex(npName + "_src");
        c.add(src, SpacesVocab.VIA_NANOPUB, np, SPACES);
        c.add(src, DCTERMS.CREATED, vf.createLiteral(created), SPACES);
        loadNum(np);
    }

    private void adminRevocation(String name, IRI agent, Literal pkh, Date created) {
        revocation(name, agent, GEN.ADMIN_ROLE, pkh, created);
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

    private boolean memberInRef(IRI agent) {
        return ask("?ri a gen:RoleInstantiation ; npa:forSpaceRef <" + R1 + "> ; npa:forAgent <" + agent + "> ."
                + " FILTER NOT EXISTS { ?ri npa:inverseProperty gen:hasAdmin }");
    }

    /** The published get-space-members ?validated EXISTS check, against the state graph. */
    private boolean memberValidated(IRI agent, IRI viaNp) {
        return ask("?vri a gen:RoleInstantiation ; npa:forSpaceRef <" + R1 + "> ;"
                + " npa:forAgent <" + agent + "> ; npa:viaNanopub <" + viaNp + "> ."
                + " FILTER NOT EXISTS { ?vri npa:inverseProperty gen:hasAdmin }");
    }

    private boolean adminInRef(IRI agent) {
        return ask("?ri a gen:RoleInstantiation ; npa:forSpaceRef <" + R1 + "> ; npa:forAgent <" + agent + "> ;"
                + " npa:inverseProperty gen:hasAdmin .");
    }

    private boolean ask(String pattern) {
        return c.prepareBooleanQuery(QueryLanguage.SPARQL, """
                PREFIX npa: <%1$s>
                PREFIX gen: <%2$s>
                ASK { GRAPH <%3$s> { %4$s } }
                """.formatted(NPA.NAMESPACE, GEN.NAMESPACE, STATE, pattern)).evaluate();
    }
}
