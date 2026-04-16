package com.knowledgepixels.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;

import java.lang.reflect.Field;
import java.util.Set;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.util.Values;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

class SpaceRegistryTest {

    private MockedStatic<Utils> mockedUtils;

    @BeforeEach
    void setUp() throws NoSuchFieldException, IllegalAccessException {
        // Reset the singleton so each test starts fresh.
        Field instance = SpaceRegistry.class.getDeclaredField("instance");
        instance.setAccessible(true);
        instance.set(null, null);

        // Stub Utils.createHash so we don't touch the admin repo (its real side effect).
        // Return a deterministic, inspectable value keyed off the input.
        mockedUtils = Mockito.mockStatic(Utils.class);
        mockedUtils.when(() -> Utils.createHash(any()))
                .thenAnswer(inv -> "H(" + inv.getArgument(0) + ")");
    }

    @AfterEach
    void tearDown() {
        mockedUtils.close();
    }

    @Test
    void registerSpace_returnsExpectedSpaceRef() {
        IRI spaceA = Values.iri("https://example.org/spaceA");
        String ref = SpaceRegistry.get().registerSpace("RA1", spaceA);
        assertEquals("RA1_H(https://example.org/spaceA)", ref);
    }

    @Test
    void registerSpace_isIdempotent() {
        IRI spaceA = Values.iri("https://example.org/spaceA");
        String first = SpaceRegistry.get().registerSpace("RA1", spaceA);
        String second = SpaceRegistry.get().registerSpace("RA1", spaceA);
        assertEquals(first, second);
        assertEquals(1, SpaceRegistry.get().getKnownSpaceRefs().size());
        assertEquals(Set.of(first), SpaceRegistry.get().findSpaceRefsBySpaceIri(spaceA));
    }

    @Test
    void twoRootsWithSameIri_produceTwoSpaceRefs() {
        IRI spaceA = Values.iri("https://example.org/spaceA");
        String ref1 = SpaceRegistry.get().registerSpace("RA1", spaceA);
        String ref2 = SpaceRegistry.get().registerSpace("RA2", spaceA);
        assertNotEquals(ref1, ref2);

        Set<String> found = SpaceRegistry.get().findSpaceRefsBySpaceIri(spaceA);
        assertEquals(Set.of(ref1, ref2), found);
    }

    @Test
    void oneRootWithTwoIris_produceTwoSpaceRefs() {
        IRI spaceA = Values.iri("https://example.org/spaceA");
        IRI spaceB = Values.iri("https://example.org/spaceB");
        String refA = SpaceRegistry.get().registerSpace("RA1", spaceA);
        String refB = SpaceRegistry.get().registerSpace("RA1", spaceB);
        assertNotEquals(refA, refB);

        assertEquals(Set.of(refA), SpaceRegistry.get().findSpaceRefsBySpaceIri(spaceA));
        assertEquals(Set.of(refB), SpaceRegistry.get().findSpaceRefsBySpaceIri(spaceB));
    }

    @Test
    void isKnownSpace_reflectsRegistration() {
        IRI spaceA = Values.iri("https://example.org/spaceA");
        String expectedRef = "RA1_H(https://example.org/spaceA)";

        assertFalse(SpaceRegistry.get().isKnownSpace(expectedRef));
        String ref = SpaceRegistry.get().registerSpace("RA1", spaceA);
        assertEquals(expectedRef, ref);
        assertTrue(SpaceRegistry.get().isKnownSpace(expectedRef));
    }

    @Test
    void getRootNanopubId_recoversNpidFromRef() {
        assertEquals("RA1", SpaceRegistry.get().getRootNanopubId("RA1_somehash"));
        assertEquals("RAabc123", SpaceRegistry.get().getRootNanopubId("RAabc123_deadbeef"));
    }

    @Test
    void getRootNanopubId_throwsOnInvalidRef() {
        assertThrows(IllegalArgumentException.class,
                () -> SpaceRegistry.get().getRootNanopubId("no-underscore-here"));
    }

    @Test
    void findSpaceRefsBySpaceIri_returnsEmptyIfNoneKnown() {
        IRI unknown = Values.iri("https://example.org/unknown");
        assertTrue(SpaceRegistry.get().findSpaceRefsBySpaceIri(unknown).isEmpty());
    }

    @Test
    void getKnownSpaceRefs_returnsUnmodifiableSet() {
        IRI spaceA = Values.iri("https://example.org/spaceA");
        SpaceRegistry.get().registerSpace("RA1", spaceA);
        Set<String> refs = SpaceRegistry.get().getKnownSpaceRefs();
        assertThrows(UnsupportedOperationException.class, () -> refs.add("foo"));
    }

    @Test
    void findSpaceRefsBySpaceIri_returnsUnmodifiableSet() {
        IRI spaceA = Values.iri("https://example.org/spaceA");
        SpaceRegistry.get().registerSpace("RA1", spaceA);
        Set<String> refs = SpaceRegistry.get().findSpaceRefsBySpaceIri(spaceA);
        assertThrows(UnsupportedOperationException.class, () -> refs.add("foo"));
    }

}
