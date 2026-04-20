package com.knowledgepixels.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.LinkedHashSet;
import java.util.Set;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.util.Values;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.nanopub.Nanopub;
import org.nanopub.NanopubUtils;

import com.knowledgepixels.query.vocabulary.GEN;

class NanopubLoaderSpaceDetectionTest {

    private static final String ROOT_NP_AC = "RA1234567890123456789012345678901234567890123";
    private static final String OTHER_NP_AC = "RA9999999999999999999999999999999999999999999";
    private static final IRI ROOT_NP_URI = Values.iri("https://w3id.org/np/" + ROOT_NP_AC);
    private static final IRI OTHER_NP_URI = Values.iri("https://w3id.org/np/" + OTHER_NP_AC);
    private static final IRI SPACE_A = Values.iri("https://example.org/spaceA");
    private static final IRI SPACE_B = Values.iri("https://example.org/spaceB");
    private static final IRI NOT_A_SPACE_TYPE = Values.iri("https://example.org/SomeOtherType");
    private static final IRI RDF_TYPE = Values.iri("http://www.w3.org/1999/02/22-rdf-syntax-ns#type");

    private MockedStatic<Utils> mockedUtils;
    private MockedStatic<NanopubUtils> mockedNanopubUtils;

    @BeforeEach
    void setUp() throws NoSuchFieldException, IllegalAccessException {
        // Reset SpaceRegistry singleton for each test.
        Field instance = SpaceRegistry.class.getDeclaredField("instance");
        instance.setAccessible(true);
        instance.set(null, null);

        // Stub Utils.createHash so SpaceRegistry doesn't touch the admin repo.
        mockedUtils = Mockito.mockStatic(Utils.class);
        mockedUtils.when(() -> Utils.createHash(any()))
                .thenAnswer(inv -> "H(" + inv.getArgument(0) + ")");
        // FeatureFlags.spacesEnabled() reads Utils.getEnvString. Under mockStatic the
        // default answer is null, which would silently disable the feature — stub to
        // return the passed-in default so production-default semantics apply in tests.
        mockedUtils.when(() -> Utils.getEnvString(any(), any()))
                .thenAnswer(inv -> inv.getArgument(1));

        // Stub NanopubUtils.getTypes so we can control nanopub-level types directly,
        // without having to construct realistic pubinfo statements.
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

    private Statement triple(IRI s, IRI p, IRI o) {
        return Values.getValueFactory().createStatement(s, p, o);
    }

    @Test
    void selfReferentialRoot_registersWithOwnNpid() {
        Set<Statement> assertion = new LinkedHashSet<>();
        assertion.add(triple(SPACE_A, RDF_TYPE, GEN.SPACE));
        assertion.add(triple(SPACE_A, GEN.HAS_ROOT_DEFINITION, ROOT_NP_URI));
        Nanopub np = mockNanopub(ROOT_NP_URI, Set.of(GEN.SPACE), assertion);

        Set<String> returned = NanopubLoader.detectAndRegisterSpaces(np);

        Set<String> expected = Set.of(ROOT_NP_AC + "_H(" + SPACE_A + ")");
        assertEquals(expected, returned);
        assertEquals(expected, SpaceRegistry.get().getKnownSpaceRefs());
    }

    @Test
    void externalRoot_registersWithExternalNpid() {
        // Update nanopub: its own URI is OTHER_NP_URI but it points back at ROOT_NP_URI as the root.
        Set<Statement> assertion = new LinkedHashSet<>();
        assertion.add(triple(SPACE_A, RDF_TYPE, GEN.SPACE));
        assertion.add(triple(SPACE_A, GEN.HAS_ROOT_DEFINITION, ROOT_NP_URI));
        Nanopub np = mockNanopub(OTHER_NP_URI, Set.of(GEN.SPACE), assertion);

        Set<String> returned = NanopubLoader.detectAndRegisterSpaces(np);

        // Space ref must use the root's NPID, not the update's own NPID.
        Set<String> expected = Set.of(ROOT_NP_AC + "_H(" + SPACE_A + ")");
        assertEquals(expected, returned);
        assertEquals(expected, SpaceRegistry.get().getKnownSpaceRefs());
    }

    @Test
    void missingRootDefinition_isSkipped() {
        Set<Statement> assertion = new LinkedHashSet<>();
        assertion.add(triple(SPACE_A, RDF_TYPE, GEN.SPACE));
        // No gen:hasRootDefinition triple.
        Nanopub np = mockNanopub(ROOT_NP_URI, Set.of(GEN.SPACE), assertion);

        Set<String> returned = NanopubLoader.detectAndRegisterSpaces(np);

        assertTrue(returned.isEmpty());
        assertTrue(SpaceRegistry.get().getKnownSpaceRefs().isEmpty());
    }

    @Test
    void notSpaceTyped_isSkipped() {
        // gen:hasRootDefinition is present, but the nanopub isn't typed gen:Space.
        Set<Statement> assertion = new LinkedHashSet<>();
        assertion.add(triple(SPACE_A, GEN.HAS_ROOT_DEFINITION, ROOT_NP_URI));
        Nanopub np = mockNanopub(ROOT_NP_URI, Set.of(NOT_A_SPACE_TYPE), assertion);

        Set<String> returned = NanopubLoader.detectAndRegisterSpaces(np);

        assertTrue(returned.isEmpty());
        assertTrue(SpaceRegistry.get().getKnownSpaceRefs().isEmpty());
    }

    @Test
    void multipleSpacesInOneNanopub_areAllRegistered() {
        Set<Statement> assertion = new LinkedHashSet<>();
        assertion.add(triple(SPACE_A, RDF_TYPE, GEN.SPACE));
        assertion.add(triple(SPACE_A, GEN.HAS_ROOT_DEFINITION, ROOT_NP_URI));
        assertion.add(triple(SPACE_B, RDF_TYPE, GEN.SPACE));
        assertion.add(triple(SPACE_B, GEN.HAS_ROOT_DEFINITION, ROOT_NP_URI));
        Nanopub np = mockNanopub(ROOT_NP_URI, Set.of(GEN.SPACE), assertion);

        Set<String> returned = NanopubLoader.detectAndRegisterSpaces(np);

        Set<String> expected = Set.of(
                ROOT_NP_AC + "_H(" + SPACE_A + ")",
                ROOT_NP_AC + "_H(" + SPACE_B + ")"
        );
        assertEquals(expected, returned);
        assertEquals(expected, SpaceRegistry.get().getKnownSpaceRefs());
    }

    @Test
    void rootDefinitionPointingAtNonTrustyUri_isSkipped() {
        IRI nonTrustyRoot = Values.iri("https://example.org/notATrustyUri");
        Set<Statement> assertion = new LinkedHashSet<>();
        assertion.add(triple(SPACE_A, RDF_TYPE, GEN.SPACE));
        assertion.add(triple(SPACE_A, GEN.HAS_ROOT_DEFINITION, nonTrustyRoot));
        Nanopub np = mockNanopub(ROOT_NP_URI, Set.of(GEN.SPACE), assertion);

        Set<String> returned = NanopubLoader.detectAndRegisterSpaces(np);

        assertTrue(returned.isEmpty());
        assertTrue(SpaceRegistry.get().getKnownSpaceRefs().isEmpty());
    }

    @Test
    void multipleTypesIncludingSpace_isDetected() {
        Set<Statement> assertion = new LinkedHashSet<>();
        assertion.add(triple(SPACE_A, RDF_TYPE, GEN.SPACE));
        assertion.add(triple(SPACE_A, GEN.HAS_ROOT_DEFINITION, ROOT_NP_URI));
        // Nanopub has gen:Space alongside another type — should still be detected.
        Nanopub np = mockNanopub(ROOT_NP_URI, Set.of(NOT_A_SPACE_TYPE, GEN.SPACE), assertion);

        Set<String> returned = NanopubLoader.detectAndRegisterSpaces(np);

        Set<String> expected = Set.of(ROOT_NP_AC + "_H(" + SPACE_A + ")");
        assertEquals(expected, returned);
        assertEquals(expected, SpaceRegistry.get().getKnownSpaceRefs());
    }

    @Test
    void noTypes_isSkipped() {
        Set<Statement> assertion = new LinkedHashSet<>();
        assertion.add(triple(SPACE_A, GEN.HAS_ROOT_DEFINITION, ROOT_NP_URI));
        Nanopub np = mockNanopub(ROOT_NP_URI, Set.of(), assertion);

        Set<String> returned = NanopubLoader.detectAndRegisterSpaces(np);

        assertTrue(returned.isEmpty());
        assertTrue(SpaceRegistry.get().getKnownSpaceRefs().isEmpty());
    }

}
