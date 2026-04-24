package com.knowledgepixels.query;

import static com.knowledgepixels.query.vocabulary.SpacesVocab.CURRENT_LOAD_COUNTER;
import static com.knowledgepixels.query.vocabulary.SpacesVocab.FOR_AGENT;
import static com.knowledgepixels.query.vocabulary.SpacesVocab.FOR_SPACE;
import static com.knowledgepixels.query.vocabulary.SpacesVocab.FOR_SPACE_REF;
import static com.knowledgepixels.query.vocabulary.SpacesVocab.HAS_DEFINITION;
import static com.knowledgepixels.query.vocabulary.SpacesVocab.HAS_ROLE_TYPE;
import static com.knowledgepixels.query.vocabulary.SpacesVocab.HAS_ROOT_ADMIN;
import static com.knowledgepixels.query.vocabulary.SpacesVocab.INVALIDATES;
import static com.knowledgepixels.query.vocabulary.SpacesVocab.INVALIDATION;
import static com.knowledgepixels.query.vocabulary.SpacesVocab.INVERSE_PROPERTY;
import static com.knowledgepixels.query.vocabulary.SpacesVocab.PUBKEY_HASH;
import static com.knowledgepixels.query.vocabulary.SpacesVocab.REGULAR_PROPERTY;
import static com.knowledgepixels.query.vocabulary.SpacesVocab.ROLE;
import static com.knowledgepixels.query.vocabulary.SpacesVocab.ROLE_DECLARATION;
import static com.knowledgepixels.query.vocabulary.SpacesVocab.ROOT_NANOPUB;
import static com.knowledgepixels.query.vocabulary.SpacesVocab.SPACES_GRAPH;
import static com.knowledgepixels.query.vocabulary.SpacesVocab.SPACE_DEFINITION;
import static com.knowledgepixels.query.vocabulary.SpacesVocab.SPACE_IRI;
import static com.knowledgepixels.query.vocabulary.SpacesVocab.SPACE_REF;
import static com.knowledgepixels.query.vocabulary.SpacesVocab.VIA_NANOPUB;
import static com.knowledgepixels.query.vocabulary.SpacesVocab.forInvalidation;
import static com.knowledgepixels.query.vocabulary.SpacesVocab.forRoleAssignment;
import static com.knowledgepixels.query.vocabulary.SpacesVocab.forRoleDeclaration;
import static com.knowledgepixels.query.vocabulary.SpacesVocab.forRoleInstantiation;
import static com.knowledgepixels.query.vocabulary.SpacesVocab.forSpaceDefinition;
import static com.knowledgepixels.query.vocabulary.SpacesVocab.forSpaceRef;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.nanopub.Nanopub;
import org.nanopub.NanopubCreator;
import org.nanopub.vocabulary.NPA;
import org.nanopub.vocabulary.NPX;

import com.knowledgepixels.query.vocabulary.GEN;

/**
 * Unit tests for {@link SpacesExtractor}. Each test builds a fixture nanopub
 * with {@link NanopubCreator} (non-trusty, fixed URI) and asserts the expected
 * statements appear in the extractor's output.
 */
class SpacesExtractorTest {

    private static final ValueFactory vf = SimpleValueFactory.getInstance();

    /** Stand-in nanopub base URI for fixtures (non-trusty; fixed artifact-code-like suffix). */
    private static final String NP_BASE = "https://w3id.org/np/RA-testAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    /** Artifact code derived from {@link #NP_BASE} — the 43 chars after "RA". */
    private static final String ARTIFACT_CODE = "RA-testAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

    private static final IRI NP_URI = vf.createIRI(NP_BASE);

    /**
     * Shared static mocks for {@link Utils}. The extractor's space-ref computation
     * calls {@link Utils#createHash(Object)}, which writes to the admin repo as a
     * side effect. We short-circuit that by stubbing the hash cache to always claim
     * the hash is already mapped.
     */
    private MockedStatic<Utils> mockedUtils;
    private static final IRI SPACE_IRI_1 = vf.createIRI("https://example.org/spaces/alpha");
    private static final IRI ADMIN_AGENT_1 = vf.createIRI("https://orcid.org/0000-0000-0000-0001");
    private static final IRI ADMIN_AGENT_2 = vf.createIRI("https://orcid.org/0000-0000-0000-0002");
    private static final IRI MEMBER_AGENT = vf.createIRI("https://orcid.org/0000-0000-0000-0003");
    private static final IRI SIGNER_AGENT = vf.createIRI("https://orcid.org/0000-0000-0000-0007");

    @BeforeEach
    void setUpMocks() {
        mockedUtils = mockStatic(Utils.class, CALLS_REAL_METHODS);
        @SuppressWarnings("unchecked")
        Map<String, Value> hashToObjectMap = mock(Map.class);
        mockedUtils.when(Utils::getHashToObjectMap).thenReturn(hashToObjectMap);
        when(hashToObjectMap.containsKey(anyString())).thenReturn(true);
    }

    @AfterEach
    void tearDownMocks() {
        if (mockedUtils != null) mockedUtils.close();
    }

    // ---------------- isSpaceRelevant ----------------

    @Test
    void isSpaceRelevant_recognizesPredefinedTypes() {
        assertTrue(SpacesExtractor.isSpaceRelevant(Set.of(GEN.SPACE)));
        assertTrue(SpacesExtractor.isSpaceRelevant(Set.of(GEN.HAS_ROLE)));
        assertTrue(SpacesExtractor.isSpaceRelevant(Set.of(GEN.SPACE_MEMBER_ROLE)));
        assertTrue(SpacesExtractor.isSpaceRelevant(Set.of(GEN.ROLE_INSTANTIATION)));
    }

    @Test
    void isSpaceRelevant_recognizesBackcompatPredicatesAsTypes() {
        IRI hasTeamMember = vf.createIRI("https://w3id.org/kpxl/gen/terms/hasTeamMember");
        IRI plansToAttend = vf.createIRI("https://w3id.org/kpxl/gen/terms/plansToAttend");
        assertTrue(SpacesExtractor.isSpaceRelevant(Set.of(hasTeamMember)));
        assertTrue(SpacesExtractor.isSpaceRelevant(Set.of(plansToAttend)));
    }

    @Test
    void isSpaceRelevant_rejectsUnrelatedTypes() {
        IRI unrelated = vf.createIRI("https://example.org/Foo");
        assertFalse(SpacesExtractor.isSpaceRelevant(Set.of(unrelated)));
        assertFalse(SpacesExtractor.isSpaceRelevant(Set.of()));
    }

    // ---------------- gen:Space ----------------

    @Test
    void extract_spaceSelfRooted_emitsSpaceRefSpaceDefinitionAndAdminRoleInstantiation() throws Exception {
        // Self-rooted: gen:hasRootDefinition points at the nanopub itself.
        Nanopub np = creator()
                .type(GEN.SPACE)
                .assertion(SPACE_IRI_1, RDF.TYPE, GEN.SPACE)
                .assertion(SPACE_IRI_1, GEN.HAS_ROOT_DEFINITION, NP_URI)
                .assertion(SPACE_IRI_1, GEN.HAS_ADMIN, ADMIN_AGENT_1)
                .assertion(SPACE_IRI_1, GEN.HAS_ADMIN, ADMIN_AGENT_2)
                .finalizeNanopub();

        List<Statement> out = SpacesExtractor.extract(np, defaultContext());
        String spaceRef = ARTIFACT_CODE + "_" + Utils.createHash(SPACE_IRI_1);
        IRI refIri = forSpaceRef(spaceRef);
        IRI defIri = forSpaceDefinition(ARTIFACT_CODE);
        IRI riIri = forRoleInstantiation(ARTIFACT_CODE);

        assertAllInSpacesGraph(out);

        // Aggregate SpaceRef
        assertContains(out, refIri, RDF.TYPE, SPACE_REF);
        assertContains(out, refIri, SPACE_IRI, SPACE_IRI_1);
        assertContains(out, refIri, ROOT_NANOPUB, NP_URI);

        // Per-contributor SpaceDefinition
        assertContains(out, defIri, RDF.TYPE, SPACE_DEFINITION);
        assertContains(out, defIri, FOR_SPACE_REF, refIri);
        assertContains(out, defIri, VIA_NANOPUB, NP_URI);
        assertContains(out, defIri, NPX.SIGNED_BY, SIGNER_AGENT);
        assertContains(out, defIri, HAS_ROOT_ADMIN, ADMIN_AGENT_1);
        assertContains(out, defIri, HAS_ROOT_ADMIN, ADMIN_AGENT_2);

        // Admin RoleInstantiation (space-scoped, both admins, regular direction)
        assertContains(out, riIri, RDF.TYPE, GEN.ROLE_INSTANTIATION);
        assertContains(out, riIri, FOR_SPACE, SPACE_IRI_1);
        assertContains(out, riIri, REGULAR_PROPERTY, GEN.HAS_ADMIN);
        assertContains(out, riIri, FOR_AGENT, ADMIN_AGENT_1);
        assertContains(out, riIri, FOR_AGENT, ADMIN_AGENT_2);
        assertContains(out, riIri, VIA_NANOPUB, NP_URI);
    }

    @Test
    void extract_spaceUpdate_emitsSpaceEntryButNoRootAdminSeed() throws Exception {
        // Update: gen:hasRootDefinition points at a different (original root) nanopub.
        IRI originalRoot = vf.createIRI("https://w3id.org/np/RA-origAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
        Nanopub np = creator()
                .type(GEN.SPACE)
                .assertion(SPACE_IRI_1, GEN.HAS_ROOT_DEFINITION, originalRoot)
                .assertion(SPACE_IRI_1, GEN.HAS_ADMIN, ADMIN_AGENT_1)
                .finalizeNanopub();

        List<Statement> out = SpacesExtractor.extract(np, defaultContext());
        String originalRootAc = "RA-origAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
        String spaceRef = originalRootAc + "_" + Utils.createHash(SPACE_IRI_1);
        IRI refIri = forSpaceRef(spaceRef);
        IRI defIri = forSpaceDefinition(ARTIFACT_CODE);

        assertContains(out, refIri, ROOT_NANOPUB, originalRoot);
        assertContains(out, defIri, FOR_SPACE_REF, refIri);
        assertContains(out, defIri, VIA_NANOPUB, NP_URI);

        // No hasRootAdmin seed — this is an update, not the root nanopub.
        assertDoesNotContain(out, defIri, HAS_ROOT_ADMIN, ADMIN_AGENT_1);

        // But the admin RoleInstantiation IS emitted (so updates can grant admin).
        IRI riIri = forRoleInstantiation(ARTIFACT_CODE);
        assertContains(out, riIri, RDF.TYPE, GEN.ROLE_INSTANTIATION);
        assertContains(out, riIri, FOR_AGENT, ADMIN_AGENT_1);
    }

    @Test
    void extract_rootlessTransitionCase_treatsSelfAsRoot() throws Exception {
        // No gen:hasRootDefinition: the transition rule treats the nanopub as its own
        // root. The Space IRI is detected via its rdf:type gen:Space and/or hasAdmin.
        Nanopub np = creator()
                .type(GEN.SPACE)
                .assertion(SPACE_IRI_1, RDF.TYPE, GEN.SPACE)
                .assertion(SPACE_IRI_1, GEN.HAS_ADMIN, ADMIN_AGENT_1)
                .finalizeNanopub();

        List<Statement> out = SpacesExtractor.extract(np, defaultContext());
        String spaceRef = ARTIFACT_CODE + "_" + Utils.createHash(SPACE_IRI_1);
        IRI refIri = forSpaceRef(spaceRef);
        IRI defIri = forSpaceDefinition(ARTIFACT_CODE);

        assertContains(out, refIri, RDF.TYPE, SPACE_REF);
        assertContains(out, refIri, ROOT_NANOPUB, NP_URI);   // self-rooted
        assertContains(out, defIri, HAS_ROOT_ADMIN, ADMIN_AGENT_1);
    }

    // ---------------- gen:hasRole ----------------

    @Test
    void extract_hasRoleAttachment_emitsRoleAssignment() throws Exception {
        IRI roleIri = vf.createIRI("https://w3id.org/np/RA-defAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA/maintainer");
        Nanopub np = creator()
                .type(GEN.HAS_ROLE)
                .assertion(SPACE_IRI_1, GEN.HAS_ROLE, roleIri)
                .finalizeNanopub();

        List<Statement> out = SpacesExtractor.extract(np, defaultContext());
        IRI subject = forRoleAssignment(ARTIFACT_CODE);

        assertAllInSpacesGraph(out);
        assertContains(out, subject, RDF.TYPE, GEN.ROLE_ASSIGNMENT);
        assertContains(out, subject, FOR_SPACE, SPACE_IRI_1);
        assertContains(out, subject, GEN.HAS_ROLE, roleIri);
        assertContains(out, subject, VIA_NANOPUB, NP_URI);
        assertContains(out, subject, NPX.SIGNED_BY, SIGNER_AGENT);
    }

    // ---------------- gen:SpaceMemberRole ----------------

    @Test
    void extract_spaceMemberRole_embeddedRole_emitsRoleDeclaration() throws Exception {
        // Role IRI minted inside this nanopub (starts with the nanopub URI).
        IRI roleIri = vf.createIRI(NP_BASE + "/maintainer");
        IRI regularPredicate = vf.createIRI("https://example.org/hasMaintainerOf");
        IRI inversePredicate = vf.createIRI("https://example.org/isMaintainerOf");

        Nanopub np = creator()
                .type(GEN.SPACE_MEMBER_ROLE)
                .assertion(roleIri, RDF.TYPE, GEN.SPACE_MEMBER_ROLE)
                .assertion(roleIri, RDF.TYPE, GEN.MAINTAINER_ROLE)
                .assertion(roleIri, GEN.HAS_REGULAR_PROPERTY, regularPredicate)
                .assertion(roleIri, GEN.HAS_INVERSE_PROPERTY, inversePredicate)
                .finalizeNanopub();

        List<Statement> out = SpacesExtractor.extract(np, defaultContext());
        IRI subject = forRoleDeclaration(ARTIFACT_CODE);

        assertContains(out, subject, RDF.TYPE, ROLE_DECLARATION);
        assertContains(out, subject, ROLE, roleIri);
        assertContains(out, subject, HAS_ROLE_TYPE, GEN.MAINTAINER_ROLE);
        assertContains(out, subject, GEN.HAS_REGULAR_PROPERTY, regularPredicate);
        assertContains(out, subject, GEN.HAS_INVERSE_PROPERTY, inversePredicate);
        assertContains(out, subject, VIA_NANOPUB, NP_URI);
    }

    @Test
    void extract_spaceMemberRole_missingTierDefaultsToObserver() throws Exception {
        IRI roleIri = vf.createIRI(NP_BASE + "/someRole");
        Nanopub np = creator()
                .type(GEN.SPACE_MEMBER_ROLE)
                .assertion(roleIri, RDF.TYPE, GEN.SPACE_MEMBER_ROLE)
                .finalizeNanopub();

        List<Statement> out = SpacesExtractor.extract(np, defaultContext());
        IRI subject = forRoleDeclaration(ARTIFACT_CODE);
        assertContains(out, subject, HAS_ROLE_TYPE, GEN.OBSERVER_ROLE);
    }

    @Test
    void extract_spaceMemberRole_nonEmbeddedRole_emitsNothing() throws Exception {
        // Role IRI NOT in this nanopub's namespace — should be ignored.
        IRI externalRole = vf.createIRI("https://some.other.site/role");
        Nanopub np = creator()
                .type(GEN.SPACE_MEMBER_ROLE)
                .assertion(externalRole, RDF.TYPE, GEN.SPACE_MEMBER_ROLE)
                .assertion(externalRole, RDF.TYPE, GEN.MAINTAINER_ROLE)
                .finalizeNanopub();

        List<Statement> out = SpacesExtractor.extract(np, defaultContext());
        assertTrue(out.isEmpty(),
                "Extractor should ignore role IRIs outside the nanopub's namespace, got: " + out);
    }

    // ---------------- gen:RoleInstantiation via backcompat ----------------

    @Test
    void extract_backcompatRegularDirection_emitsRoleInstantiation() throws Exception {
        IRI hasTeamMember = vf.createIRI("https://w3id.org/kpxl/gen/terms/hasTeamMember");
        Nanopub np = creator()
                // Single-triple assertion — this nanopub is detected as typed by the predicate
                // via NanopubUtils.getTypes's "only-predicate-in-assertion" rule.
                .assertion(SPACE_IRI_1, hasTeamMember, MEMBER_AGENT)
                .finalizeNanopub();

        List<Statement> out = SpacesExtractor.extract(np, defaultContext());
        IRI subject = forRoleInstantiation(ARTIFACT_CODE);

        assertContains(out, subject, RDF.TYPE, GEN.ROLE_INSTANTIATION);
        assertContains(out, subject, FOR_SPACE, SPACE_IRI_1);        // subject of the assertion triple
        assertContains(out, subject, REGULAR_PROPERTY, hasTeamMember);
        assertContains(out, subject, FOR_AGENT, MEMBER_AGENT);       // object of the assertion triple
        assertDoesNotContain(out, subject, INVERSE_PROPERTY, hasTeamMember);
    }

    @Test
    void extract_backcompatInverseDirection_swapsSpaceAndAgent() throws Exception {
        IRI plansToAttend = vf.createIRI("https://w3id.org/kpxl/gen/terms/plansToAttend");
        // For inverse direction the assertion is <agent> <predicate> <space>.
        Nanopub np = creator()
                .assertion(MEMBER_AGENT, plansToAttend, SPACE_IRI_1)
                .finalizeNanopub();

        List<Statement> out = SpacesExtractor.extract(np, defaultContext());
        IRI subject = forRoleInstantiation(ARTIFACT_CODE);

        assertContains(out, subject, RDF.TYPE, GEN.ROLE_INSTANTIATION);
        assertContains(out, subject, FOR_SPACE, SPACE_IRI_1);        // object side
        assertContains(out, subject, INVERSE_PROPERTY, plansToAttend);
        assertContains(out, subject, FOR_AGENT, MEMBER_AGENT);       // subject side
        assertDoesNotContain(out, subject, REGULAR_PROPERTY, plansToAttend);
    }

    // ---------------- Invalidation ----------------

    @Test
    void extractInvalidation_targetIsSpaceTyped_emitsInvalidationEntry() throws Exception {
        IRI invalidatedNp = vf.createIRI("https://w3id.org/np/RA-tgtAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
        Nanopub invalidator = creator()
                // Placeholder assertion (non-empty is required by NanopubImpl; the
                // extractor's invalidation path doesn't inspect the assertion).
                .assertion(NP_URI, NPX.INVALIDATES,
                        vf.createIRI("https://w3id.org/np/RA-tgtAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"))
                .finalizeNanopub();

        List<Statement> out = SpacesExtractor.extractInvalidation(
                invalidator, invalidatedNp, Set.of(GEN.SPACE), defaultContext());

        IRI subject = forInvalidation(ARTIFACT_CODE);
        assertContains(out, subject, RDF.TYPE, INVALIDATION);
        assertContains(out, subject, INVALIDATES, invalidatedNp);
        assertContains(out, subject, VIA_NANOPUB, NP_URI);
        assertContains(out, subject, NPX.SIGNED_BY, SIGNER_AGENT);
        assertAllInSpacesGraph(out);
    }

    @Test
    void extractInvalidation_targetNotSpaceRelevant_emitsNothing() throws Exception {
        IRI invalidatedNp = vf.createIRI("https://w3id.org/np/RA-tgtBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB");
        Nanopub invalidator = creator()
                // Placeholder assertion (non-empty is required by NanopubImpl; the
                // extractor's invalidation path doesn't inspect the assertion).
                .assertion(NP_URI, NPX.INVALIDATES,
                        vf.createIRI("https://w3id.org/np/RA-tgtAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"))
                .finalizeNanopub();
        IRI unrelated = vf.createIRI("https://example.org/SomeOtherType");

        List<Statement> out = SpacesExtractor.extractInvalidation(
                invalidator, invalidatedNp, Set.of(unrelated), defaultContext());

        assertTrue(out.isEmpty(), "No invalidation entry for non-space-relevant target");
    }

    // ---------------- Load-counter stamping ----------------

    @Test
    void loadCounterStatements_stampsOnNanopubAndAdmin() {
        List<Statement> out = SpacesExtractor.loadCounterStatements(NP_URI, 42L);
        assertEquals(2, out.size(), "Expect exactly the load-number stamp + counter bump");
        assertContainsInContext(out, NP_URI, NPA.HAS_LOAD_NUMBER, vf.createLiteral(42L), NPA.GRAPH);
        assertContainsInContext(out, NPA.THIS_REPO, CURRENT_LOAD_COUNTER, vf.createLiteral(42L), NPA.GRAPH);
    }

    // ---------------- helpers ----------------

    private SpacesExtractor.Context defaultContext() {
        return new SpacesExtractor.Context(ARTIFACT_CODE, SIGNER_AGENT, "fake-pubkey-hash", new Date(1_700_000_000_000L));
    }

    private NanopubBuilder creator() throws Exception {
        return new NanopubBuilder(NP_BASE);
    }

    /**
     * Small fluent wrapper around {@link NanopubCreator} for building test fixtures.
     * Uses a fixed nanopub URI (no trusty minting) to keep assertions deterministic.
     */
    private static final class NanopubBuilder {
        private final NanopubCreator nc;

        NanopubBuilder(String npUri) throws Exception {
            nc = new NanopubCreator(npUri);
            nc.setAssertionUri(npUri + "/assertion");
            nc.setProvenanceUri(npUri + "/provenance");
            nc.setPubinfoUri(npUri + "/pubinfo");
            // Nanopub requires at least one provenance statement.
            nc.addProvenanceStatement(vf.createIRI(npUri + "/assertion"),
                    vf.createIRI("http://www.w3.org/ns/prov#wasAttributedTo"),
                    SIGNER_AGENT);
            // Sign-by on pubinfo makes NanopubUtils.getTypes pick up types correctly and
            // matches the SpacesExtractor's signer lookup path.
            nc.addPubinfoStatement(NPX.SIGNED_BY, SIGNER_AGENT);
        }

        NanopubBuilder type(IRI typeIri) throws Exception {
            nc.addPubinfoStatement(NPX.HAS_NANOPUB_TYPE, typeIri);
            return this;
        }

        NanopubBuilder assertion(IRI s, IRI p, org.eclipse.rdf4j.model.Value o) throws Exception {
            nc.addAssertionStatement(s, p, o);
            return this;
        }

        Nanopub finalizeNanopub() throws Exception {
            return nc.finalizeNanopub();
        }
    }

    private static void assertContains(List<Statement> out, IRI subj, IRI pred,
                                       org.eclipse.rdf4j.model.Value obj) {
        for (Statement st : out) {
            if (st.getSubject().equals(subj)
                    && st.getPredicate().equals(pred)
                    && st.getObject().equals(obj)
                    && SPACES_GRAPH.equals(st.getContext())) {
                return;
            }
        }
        throw new AssertionError(
                "Expected statement not found in spaces graph: <" + subj + "> <" + pred + "> <"
                        + obj + "> .\nActual statements:\n" + formatStatements(out));
    }

    private static void assertContainsInContext(List<Statement> out, IRI subj, IRI pred,
                                                org.eclipse.rdf4j.model.Value obj, IRI context) {
        for (Statement st : out) {
            if (st.getSubject().equals(subj)
                    && st.getPredicate().equals(pred)
                    && st.getObject().equals(obj)
                    && context.equals(st.getContext())) {
                return;
            }
        }
        throw new AssertionError(
                "Expected statement not found in context <" + context + ">: <" + subj + "> <" + pred
                        + "> <" + obj + "> .\nActual statements:\n" + formatStatements(out));
    }

    private static void assertDoesNotContain(List<Statement> out, IRI subj, IRI pred,
                                             org.eclipse.rdf4j.model.Value obj) {
        for (Statement st : out) {
            if (st.getSubject().equals(subj)
                    && st.getPredicate().equals(pred)
                    && st.getObject().equals(obj)) {
                throw new AssertionError(
                        "Did not expect statement: <" + subj + "> <" + pred + "> <" + obj + "> .\n"
                                + "Actual statements:\n" + formatStatements(out));
            }
        }
    }

    private static void assertAllInSpacesGraph(List<Statement> out) {
        for (Statement st : out) {
            assertNotNull(st.getContext(), "Statement must have a context: " + st);
            assertEquals(SPACES_GRAPH, st.getContext(),
                    "Statement not in spaces graph: " + st);
        }
    }

    private static String formatStatements(List<Statement> out) {
        StringBuilder sb = new StringBuilder();
        for (Statement st : out) {
            sb.append("  ").append(st).append('\n');
        }
        return sb.toString();
    }

    // Silence unused-static-import warnings for imports we keep in case of future tests.
    @SuppressWarnings("unused")
    private static final IRI[] UNUSED_REFS = { HAS_DEFINITION, PUBKEY_HASH };

}
