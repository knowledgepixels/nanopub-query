package com.knowledgepixels.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.LinkedHashSet;
import java.util.Set;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.util.Values;
import org.eclipse.rdf4j.model.vocabulary.DCTERMS;
import org.eclipse.rdf4j.model.vocabulary.OWL;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.nanopub.Nanopub;
import org.nanopub.NanopubUtils;

import com.knowledgepixels.query.vocabulary.GEN;
import com.knowledgepixels.query.vocabulary.NPAS;
import com.knowledgepixels.query.vocabulary.NPAX;
import com.knowledgepixels.query.vocabulary.SpaceAuthority;
import com.knowledgepixels.query.vocabulary.SpaceExtract;

class SpacesExtractorTest {

    private static final String ROOT_NP_AC = "RA1234567890123456789012345678901234567890123";
    private static final String OTHER_NP_AC = "RA9999999999999999999999999999999999999999999";
    private static final IRI ROOT_NP_URI = Values.iri("https://w3id.org/np/" + ROOT_NP_AC);
    private static final IRI OTHER_NP_URI = Values.iri("https://w3id.org/np/" + OTHER_NP_AC);
    private static final IRI SPACE_A = Values.iri("https://example.org/spaceA");
    private static final IRI SPACE_B = Values.iri("https://example.org/spaceB");
    private static final IRI ALICE = Values.iri("https://orcid.org/0000-0000-0000-0001");
    private static final IRI BOB = Values.iri("https://orcid.org/0000-0000-0000-0002");

    private MockedStatic<Utils> mockedUtils;
    private MockedStatic<NanopubUtils> mockedNanopubUtils;

    @BeforeEach
    void setUp() throws NoSuchFieldException, IllegalAccessException {
        Field instance = SpaceRegistry.class.getDeclaredField("instance");
        instance.setAccessible(true);
        instance.set(null, null);

        mockedUtils = Mockito.mockStatic(Utils.class);
        mockedUtils.when(() -> Utils.createHash(any()))
                .thenAnswer(inv -> "H(" + inv.getArgument(0) + ")");

        mockedNanopubUtils = Mockito.mockStatic(NanopubUtils.class);
    }

    @AfterEach
    void tearDown() {
        mockedUtils.close();
        mockedNanopubUtils.close();
    }

    private Nanopub mockNanopub(IRI npUri, Set<IRI> types, Set<Statement> assertion) {
        Nanopub np = mock(Nanopub.class);
        when(np.getUri()).thenReturn(npUri);
        when(np.getAssertion()).thenReturn(assertion);
        mockedNanopubUtils.when(() -> NanopubUtils.getTypes(np)).thenReturn(types);
        return np;
    }

    private Statement triple(IRI s, IRI p, Value o) {
        return Values.getValueFactory().createStatement(s, p, o);
    }

    private String spaceRef(String npAc, IRI spaceIri) {
        return npAc + "_H(" + spaceIri + ")";
    }

    private static Statement findOne(Set<Statement> stmts, IRI subj, IRI pred) {
        Statement found = null;
        for (Statement s : stmts) {
            if (s.getSubject().equals(subj) && s.getPredicate().equals(pred)) {
                if (found != null) {
                    throw new AssertionError("Multiple matches for " + subj + " " + pred);
                }
                found = s;
            }
        }
        return found;
    }

    // -------- admin-grant extraction --------

    @Test
    void unknownSpace_producesNoExtracts() {
        // SpaceRegistry is empty — no Space IRIs are registered.
        Set<Statement> assertion = new LinkedHashSet<>();
        assertion.add(triple(SPACE_A, GEN.HAS_ADMIN, ALICE));
        Nanopub np = mockNanopub(OTHER_NP_URI, Set.of(), assertion);

        SpacesExtractor.ExtractionResult result = SpacesExtractor.extract(np, SpaceRegistry.get());

        assertTrue(result.statements().isEmpty());
        assertTrue(result.spaceRefs().isEmpty());
    }

    @Test
    void adminGrantInSpaceDefiningNanopub_producesAdminExtract() {
        // Root nanopub defines space A and grants admin to Alice.
        Set<Statement> assertion = new LinkedHashSet<>();
        assertion.add(triple(SPACE_A, RDF.TYPE, GEN.SPACE));
        assertion.add(triple(SPACE_A, GEN.HAS_ROOT_DEFINITION, ROOT_NP_URI));
        assertion.add(triple(SPACE_A, GEN.HAS_ADMIN, ALICE));
        Nanopub np = mockNanopub(ROOT_NP_URI, Set.of(GEN.SPACE), assertion);

        // Pre-register space A so the extractor knows about it.
        NanopubLoader.detectAndRegisterSpaces(np);

        SpacesExtractor.ExtractionResult result = SpacesExtractor.extract(np, SpaceRegistry.get());

        String ref = spaceRef(ROOT_NP_AC, SPACE_A);
        assertEquals(Set.of(ref), result.spaceRefs());

        // Find the admin-grant extract object.
        Set<Statement> stmts = new LinkedHashSet<>(result.statements());
        IRI graph = NPAS.forSpaceRef(ref);
        // Locate the extract IRI by the kind triple.
        IRI extractIri = null;
        for (Statement s : stmts) {
            if (SpaceExtract.EXTRACT_KIND.equals(s.getPredicate())
                    && SpaceExtract.ADMIN_GRANT.equals(s.getObject())) {
                extractIri = (IRI) s.getSubject();
                break;
            }
        }
        assertEquals(NPAX.NAMESPACE,
                extractIri.stringValue().substring(0, NPAX.NAMESPACE.length()),
                "extract IRI must use the npax: namespace");

        // Expect the four shape triples, all in the per-space graph context.
        assertEquals(SpaceExtract.EXTRACT, findOne(stmts, extractIri, RDF.TYPE).getObject());
        assertEquals(SpaceExtract.ADMIN_GRANT, findOne(stmts, extractIri, SpaceExtract.EXTRACT_KIND).getObject());
        assertEquals(ALICE, findOne(stmts, extractIri, SpaceAuthority.AGENT).getObject());
        assertEquals(ROOT_NP_URI, findOne(stmts, extractIri, SpaceAuthority.VIA_NANOPUB).getObject());
        for (Statement s : stmts) {
            if (s.getSubject().equals(extractIri)) {
                assertEquals(graph, s.getContext(), "extract triples must be in the per-space named graph");
            }
        }
    }

    @Test
    void adminGrantFromOtherNanopub_extractsToKnownSpace() {
        // Pre-register space A via its root nanopub.
        Set<Statement> rootAssertion = new LinkedHashSet<>();
        rootAssertion.add(triple(SPACE_A, RDF.TYPE, GEN.SPACE));
        rootAssertion.add(triple(SPACE_A, GEN.HAS_ROOT_DEFINITION, ROOT_NP_URI));
        rootAssertion.add(triple(SPACE_A, GEN.HAS_ADMIN, ALICE));
        NanopubLoader.detectAndRegisterSpaces(
                mockNanopub(ROOT_NP_URI, Set.of(GEN.SPACE), rootAssertion));

        // Now an unrelated nanopub asserts gen:hasAdmin for the known space.
        Set<Statement> otherAssertion = new LinkedHashSet<>();
        otherAssertion.add(triple(SPACE_A, GEN.HAS_ADMIN, BOB));
        Nanopub other = mockNanopub(OTHER_NP_URI, Set.of(), otherAssertion);

        SpacesExtractor.ExtractionResult result = SpacesExtractor.extract(other, SpaceRegistry.get());

        String ref = spaceRef(ROOT_NP_AC, SPACE_A);
        assertEquals(Set.of(ref), result.spaceRefs());
        // Exactly one admin-grant extract for Bob.
        long bobExtracts = result.statements().stream()
                .filter(s -> SpaceAuthority.AGENT.equals(s.getPredicate()) && BOB.equals(s.getObject()))
                .count();
        assertEquals(1, bobExtracts);
    }

    @Test
    void adminGrantHashes_areDeterministicAcrossExtractions() {
        Set<Statement> assertion = new LinkedHashSet<>();
        assertion.add(triple(SPACE_A, RDF.TYPE, GEN.SPACE));
        assertion.add(triple(SPACE_A, GEN.HAS_ROOT_DEFINITION, ROOT_NP_URI));
        assertion.add(triple(SPACE_A, GEN.HAS_ADMIN, ALICE));
        Nanopub np1 = mockNanopub(ROOT_NP_URI, Set.of(GEN.SPACE), assertion);
        NanopubLoader.detectAndRegisterSpaces(np1);
        SpacesExtractor.ExtractionResult r1 = SpacesExtractor.extract(np1, SpaceRegistry.get());

        // Build a second nanopub mock with the exact same content.
        Nanopub np2 = mockNanopub(ROOT_NP_URI, Set.of(GEN.SPACE), assertion);
        SpacesExtractor.ExtractionResult r2 = SpacesExtractor.extract(np2, SpaceRegistry.get());

        // Same inputs → same extract IRIs (so RDF4J set semantics dedupes on re-loading).
        Set<IRI> subjects1 = r1.statements().stream().map(s -> (IRI) s.getSubject())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Set<IRI> subjects2 = r2.statements().stream().map(s -> (IRI) s.getSubject())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        assertEquals(subjects1, subjects2);
    }

    @Test
    void adminGrantHashes_differAcrossSpacesAndAgents() {
        // Two spaces, two agents — every (space, agent) pair gets its own extract IRI.
        String h1 = SpacesExtractor.extractHash(spaceRef(ROOT_NP_AC, SPACE_A), ROOT_NP_URI,
                SpaceExtract.ADMIN_GRANT, ALICE.stringValue());
        String h2 = SpacesExtractor.extractHash(spaceRef(ROOT_NP_AC, SPACE_A), ROOT_NP_URI,
                SpaceExtract.ADMIN_GRANT, BOB.stringValue());
        String h3 = SpacesExtractor.extractHash(spaceRef(ROOT_NP_AC, SPACE_B), ROOT_NP_URI,
                SpaceExtract.ADMIN_GRANT, ALICE.stringValue());
        assertNotEquals(h1, h2);
        assertNotEquals(h1, h3);
        assertNotEquals(h2, h3);
    }

    // -------- profile-field extraction --------

    @Test
    void profileFields_areExtractedFromSpaceDefiningNanopub() {
        Set<Statement> assertion = new LinkedHashSet<>();
        assertion.add(triple(SPACE_A, RDF.TYPE, GEN.SPACE));
        assertion.add(triple(SPACE_A, GEN.HAS_ROOT_DEFINITION, ROOT_NP_URI));
        assertion.add(triple(SPACE_A, DCTERMS.DESCRIPTION, Values.literal("Space A")));
        assertion.add(triple(SPACE_A, OWL.SAMEAS, Values.iri("https://other.example/spaceA-alt")));
        Nanopub np = mockNanopub(ROOT_NP_URI, Set.of(GEN.SPACE), assertion);

        NanopubLoader.detectAndRegisterSpaces(np);
        SpacesExtractor.ExtractionResult result = SpacesExtractor.extract(np, SpaceRegistry.get());

        // We expect: 0 admin-grant extracts (none in this assertion),
        // 2 profile-field extracts (description, sameAs).
        long profileExtracts = result.statements().stream()
                .filter(s -> SpaceExtract.PROFILE_FIELD.equals(s.getObject())
                        && SpaceExtract.EXTRACT_KIND.equals(s.getPredicate()))
                .count();
        assertEquals(2, profileExtracts);

        // Each profile extract carries fieldKey and fieldValue.
        long fieldKeyTriples = result.statements().stream()
                .filter(s -> SpaceExtract.FIELD_KEY.equals(s.getPredicate()))
                .count();
        assertEquals(2, fieldKeyTriples);
    }

    @Test
    void profileFields_skipStructuralAndAuthorityTriples() {
        Set<Statement> assertion = new LinkedHashSet<>();
        assertion.add(triple(SPACE_A, RDF.TYPE, GEN.SPACE));
        assertion.add(triple(SPACE_A, GEN.HAS_ROOT_DEFINITION, ROOT_NP_URI));
        assertion.add(triple(SPACE_A, GEN.HAS_ADMIN, ALICE));
        Nanopub np = mockNanopub(ROOT_NP_URI, Set.of(GEN.SPACE), assertion);

        NanopubLoader.detectAndRegisterSpaces(np);
        SpacesExtractor.ExtractionResult result = SpacesExtractor.extract(np, SpaceRegistry.get());

        // The only profile field would be rdf:type gen:Space — but neither
        // gen:hasRootDefinition nor gen:hasAdmin should appear as profile fields.
        // (rdf:type is currently treated as a profile field since it's not in the skip list.)
        long roots = result.statements().stream()
                .filter(s -> SpaceExtract.FIELD_KEY.equals(s.getPredicate())
                        && GEN.HAS_ROOT_DEFINITION.equals(s.getObject()))
                .count();
        long admins = result.statements().stream()
                .filter(s -> SpaceExtract.FIELD_KEY.equals(s.getPredicate())
                        && GEN.HAS_ADMIN.equals(s.getObject()))
                .count();
        assertEquals(0, roots);
        assertEquals(0, admins);
    }

    @Test
    void profileFields_areNotExtractedFromNonSpaceDefiningNanopub() {
        // Pre-register space A.
        Set<Statement> rootAssertion = new LinkedHashSet<>();
        rootAssertion.add(triple(SPACE_A, RDF.TYPE, GEN.SPACE));
        rootAssertion.add(triple(SPACE_A, GEN.HAS_ROOT_DEFINITION, ROOT_NP_URI));
        NanopubLoader.detectAndRegisterSpaces(
                mockNanopub(ROOT_NP_URI, Set.of(GEN.SPACE), rootAssertion));

        // A *non*-space-defining nanopub mentions the Space IRI (e.g. someone
        // tries to set its description from outside). We do NOT extract this
        // as a profile field — only space-defining nanopubs contribute profile.
        Set<Statement> otherAssertion = new LinkedHashSet<>();
        otherAssertion.add(triple(SPACE_A, DCTERMS.DESCRIPTION, Values.literal("Hijacked!")));
        Nanopub other = mockNanopub(OTHER_NP_URI, Set.of(), otherAssertion);

        SpacesExtractor.ExtractionResult result = SpacesExtractor.extract(other, SpaceRegistry.get());

        long profileExtracts = result.statements().stream()
                .filter(s -> SpaceExtract.PROFILE_FIELD.equals(s.getObject())
                        && SpaceExtract.EXTRACT_KIND.equals(s.getPredicate()))
                .count();
        assertEquals(0, profileExtracts);
        assertTrue(result.spaceRefs().isEmpty());
    }

}
