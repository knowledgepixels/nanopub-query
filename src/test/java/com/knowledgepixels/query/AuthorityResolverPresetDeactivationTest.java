package com.knowledgepixels.query;

import com.knowledgepixels.query.vocabulary.GEN;
import org.nanopub.vocabulary.NPA;
import com.knowledgepixels.query.vocabulary.SpacesVocab;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Value;
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

import javax.xml.datatype.DatatypeFactory;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs the preset-deactivation check and DELETE (issue #302) against a small store: which
 * rows they remove, and that the selective part comes first. On 2026-11-04 the DELETE went
 * over RDF4J's 60 s limit on two instances, so the role-validation cycle failed on every
 * retry and no further role was validated there.
 */
class AuthorityResolverPresetDeactivationTest {

    private static final ValueFactory vf = SimpleValueFactory.getInstance();
    private static final IRI G = SpacesVocab.forSpaceState(
            "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789", 42L);
    private static final String EX = "https://example.org/";

    private static final IRI REF = vf.createIRI(EX, "ref/space1");
    private static final IRI RESOURCE = vf.createIRI(EX, "space1");
    private static final IRI PRESET = vf.createIRI(EX, "preset1");
    private static final IRI OTHER_PRESET = vf.createIRI(EX, "preset2");
    private static final IRI ADMIN = vf.createIRI(EX, "admin");
    private static final IRI STRANGER = vf.createIRI(EX, "stranger");
    private static final Literal ADMIN_KEY = vf.createLiteral("pkh-admin");
    private static final Literal STRANGER_KEY = vf.createLiteral("pkh-stranger");

    private static final IRI NP_OLD = vf.createIRI(EX, "np/old");
    private static final IRI NP_NEW = vf.createIRI(EX, "np/new");
    private static final IRI NP_OTHER = vf.createIRI(EX, "np/other");

    /** Role assignment derived from the older assignment of PRESET: the one to remove. */
    private static final IRI RA_SUPERSEDED = vf.createIRI(EX, "ra/superseded");
    /** Directly published role assignment: never touched. */
    private static final IRI RA_DIRECT = vf.createIRI(EX, "ra/direct");
    /** Role assignment derived from OTHER_PRESET, which nothing supersedes. */
    private static final IRI RA_OTHER_PRESET = vf.createIRI(EX, "ra/other-preset");

    private Repository repo;

    @BeforeEach
    void setUp() {
        repo = new SailRepository(new MemoryStore());
        repo.init();
    }

    @AfterEach
    void tearDown() {
        repo.shutDown();
    }

    private static IRI npa(String local) {
        return vf.createIRI(NPA.NAMESPACE, local);
    }

    private static Literal date(String iso) {
        try {
            return vf.createLiteral(DatatypeFactory.newInstance().newXMLGregorianCalendar(iso));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private void add(Resource s, IRI p, Value o, IRI graph) {
        try (RepositoryConnection conn = repo.getConnection()) {
            conn.add(s, p, o, graph);
        }
    }

    /** Space state with an admin, three role assignments, and three preset assignments. */
    private void loadFixture(Literal newerAssignmentKey) {
        IRI acct = vf.createIRI(EX, "acct/admin");
        add(acct, RDF.TYPE, npa("AccountState"), G);
        add(acct, npa("agent"), ADMIN, G);
        add(acct, npa("pubkey"), ADMIN_KEY, G);
        IRI acct2 = vf.createIRI(EX, "acct/stranger");
        add(acct2, RDF.TYPE, npa("AccountState"), G);
        add(acct2, npa("agent"), STRANGER, G);
        add(acct2, npa("pubkey"), STRANGER_KEY, G);
        IRI adminRI = vf.createIRI(EX, "ri/admin");
        add(adminRI, RDF.TYPE, GEN.ROLE_INSTANTIATION, G);
        add(adminRI, npa("forSpaceRef"), REF, G);
        add(adminRI, npa("inverseProperty"), vf.createIRI(GEN.NAMESPACE, "hasAdmin"), G);
        add(adminRI, npa("forAgent"), ADMIN, G);

        for (IRI ra : new IRI[] { RA_SUPERSEDED, RA_DIRECT, RA_OTHER_PRESET }) {
            add(ra, RDF.TYPE, vf.createIRI(GEN.NAMESPACE, "RoleAssignment"), G);
            add(ra, npa("forSpaceRef"), REF, G);
            add(ra, vf.createIRI(GEN.NAMESPACE, "hasRole"), vf.createIRI(EX, "role/" + ra.getLocalName()), G);
        }
        add(RA_SUPERSEDED, npa("derivedFromPreset"), NP_OLD, G);
        add(RA_OTHER_PRESET, npa("derivedFromPreset"), NP_OTHER, G);

        addAssignment("pa/old", NP_OLD, PRESET, "2026-01-01T00:00:00Z", ADMIN_KEY, 3);
        addAssignment("pa/new", NP_NEW, PRESET, "2026-02-01T00:00:00Z", newerAssignmentKey, 10);
        addAssignment("pa/other", NP_OTHER, OTHER_PRESET, "2026-01-01T00:00:00Z", ADMIN_KEY, 4);
    }

    private void addAssignment(String id, IRI np, IRI preset, String created, Literal key, long loadNumber) {
        IRI pa = vf.createIRI(EX, id);
        add(pa, RDF.TYPE, npa("PresetAssignment"), SpacesVocab.SPACES_GRAPH);
        add(pa, npa("viaNanopub"), np, SpacesVocab.SPACES_GRAPH);
        add(pa, npa("ofPreset"), preset, SpacesVocab.SPACES_GRAPH);
        add(pa, npa("forResource"), RESOURCE, SpacesVocab.SPACES_GRAPH);
        add(pa, npa("pubkeyHash"), key, SpacesVocab.SPACES_GRAPH);
        add(pa, DCTERMS.CREATED, date(created), SpacesVocab.SPACES_GRAPH);
        add(np, npa("hasLoadNumber"), vf.createLiteral(loadNumber), NPA.GRAPH);
    }

    /** The ASK that {@code wouldInvalidate} runs before the DELETE. */
    private boolean check(long lastProcessed) {
        String ask = "PREFIX npa: <" + NPA.NAMESPACE + ">\nPREFIX gen: <" + GEN.NAMESPACE + ">\nASK { "
                + AuthorityResolver.presetDeactivationCheckWhere(G, lastProcessed) + " }";
        try (RepositoryConnection conn = repo.getConnection()) {
            return conn.prepareBooleanQuery(QueryLanguage.SPARQL, ask).evaluate();
        }
    }

    private void delete(long lastProcessed) {
        try (RepositoryConnection conn = repo.getConnection()) {
            conn.prepareUpdate(QueryLanguage.SPARQL,
                    AuthorityResolver.presetDeactivationDelete(G, lastProcessed)).execute();
        }
    }

    private long triplesOf(IRI subject) {
        try (RepositoryConnection conn = repo.getConnection()) {
            return conn.getStatements(subject, null, null, G).stream().count();
        }
    }

    private long size() {
        try (RepositoryConnection conn = repo.getConnection()) {
            return conn.size();
        }
    }

    @Test
    void removesOnlyTheRowOfTheSupersededAssignment() {
        loadFixture(ADMIN_KEY);
        long before = size();
        assertEquals(4, triplesOf(RA_SUPERSEDED));

        assertTrue(check(5), "a newer admin assignment of the same preset was loaded in this cycle");
        delete(5);

        assertEquals(0, triplesOf(RA_SUPERSEDED), "the whole superseded row is removed");
        assertEquals(3, triplesOf(RA_DIRECT), "directly published assignments are never touched");
        assertEquals(4, triplesOf(RA_OTHER_PRESET), "rows of other presets are kept");
        assertEquals(before - 4, size(), "nothing else is removed");
    }

    @Test
    void doesNothingWhenTheNewerAssignmentWasProcessedBefore() {
        loadFixture(ADMIN_KEY);
        long before = size();

        assertFalse(check(10), "the newer assignment is not new in this cycle");
        delete(10);

        assertEquals(before, size());
    }

    @Test
    void doesNothingWhenTheNewerAssignmentIsNotFromAnAdmin() {
        loadFixture(STRANGER_KEY);
        long before = size();

        assertFalse(check(5), "a non-admin's newer assignment cannot deactivate an admin's");
        delete(5);

        assertEquals(before, size());
    }

    @Test
    void startsFromTheNewerAssignmentsOfThisCycle() {
        String where = AuthorityResolver.presetDeactivationCheckWhere(G, 5).strip();
        assertTrue(where.startsWith("{"), "the check opens with its sub-select");
        int subSelect = where.indexOf("SELECT");
        int loadFilter = where.indexOf("?lnNewer > 5");
        int roleRows = where.indexOf("gen:RoleAssignment");
        assertTrue(subSelect >= 0 && subSelect < loadFilter && loadFilter < roleRows,
                "the newer assignments loaded in this cycle are selected before the role rows");

        String delete = AuthorityResolver.presetDeactivationDelete(G, 5);
        assertTrue(delete.indexOf("SELECT DISTINCT ?ra") < delete.lastIndexOf("?ra ?p ?o"),
                "the rows to remove are found before their triples are read");
    }

}
