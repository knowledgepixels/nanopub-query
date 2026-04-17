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
        SpaceRegistry.Registration r = SpaceRegistry.get().registerSpace("RA1", spaceA);
        assertEquals("RA1_H(https://example.org/spaceA)", r.spaceRef());
        assertTrue(r.wasNew());
    }

    @Test
    void registerSpace_isIdempotent() {
        IRI spaceA = Values.iri("https://example.org/spaceA");
        SpaceRegistry.Registration first = SpaceRegistry.get().registerSpace("RA1", spaceA);
        SpaceRegistry.Registration second = SpaceRegistry.get().registerSpace("RA1", spaceA);
        assertEquals(first.spaceRef(), second.spaceRef());
        assertTrue(first.wasNew());
        assertFalse(second.wasNew());
        assertEquals(1, SpaceRegistry.get().getKnownSpaceRefs().size());
        assertEquals(Set.of(first.spaceRef()), SpaceRegistry.get().findSpaceRefsBySpaceIri(spaceA));
    }

    @Test
    void twoRootsWithSameIri_produceTwoSpaceRefs() {
        IRI spaceA = Values.iri("https://example.org/spaceA");
        String ref1 = SpaceRegistry.get().registerSpace("RA1", spaceA).spaceRef();
        String ref2 = SpaceRegistry.get().registerSpace("RA2", spaceA).spaceRef();
        assertNotEquals(ref1, ref2);

        Set<String> found = SpaceRegistry.get().findSpaceRefsBySpaceIri(spaceA);
        assertEquals(Set.of(ref1, ref2), found);
    }

    @Test
    void oneRootWithTwoIris_produceTwoSpaceRefs() {
        IRI spaceA = Values.iri("https://example.org/spaceA");
        IRI spaceB = Values.iri("https://example.org/spaceB");
        String refA = SpaceRegistry.get().registerSpace("RA1", spaceA).spaceRef();
        String refB = SpaceRegistry.get().registerSpace("RA1", spaceB).spaceRef();
        assertNotEquals(refA, refB);

        assertEquals(Set.of(refA), SpaceRegistry.get().findSpaceRefsBySpaceIri(spaceA));
        assertEquals(Set.of(refB), SpaceRegistry.get().findSpaceRefsBySpaceIri(spaceB));
    }

    @Test
    void isKnownSpace_reflectsRegistration() {
        IRI spaceA = Values.iri("https://example.org/spaceA");
        String expectedRef = "RA1_H(https://example.org/spaceA)";

        assertFalse(SpaceRegistry.get().isKnownSpace(expectedRef));
        SpaceRegistry.Registration r = SpaceRegistry.get().registerSpace("RA1", spaceA);
        assertEquals(expectedRef, r.spaceRef());
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

    @Test
    void registerRoleProperty_storesAndReturnsTrueOnFirstAdd() {
        IRI spaceIri = Values.iri("https://example.org/spaceA");
        String ref = SpaceRegistry.get().registerSpace("RA1", spaceIri).spaceRef();
        IRI predicate = Values.iri("https://example.org/hasMember");
        IRI roleType = Values.iri("https://w3id.org/kpxl/gen/terms/MemberRole");
        RoleProperty rp = new RoleProperty(predicate, roleType, RoleProperty.Direction.REGULAR);

        assertTrue(SpaceRegistry.get().registerRoleProperty(ref, rp));
        assertFalse(SpaceRegistry.get().registerRoleProperty(ref, rp));
        assertEquals(Set.of(rp), SpaceRegistry.get().getRoleProperties(ref));
    }

    @Test
    void registerRoleProperty_throwsForUnknownSpaceRef() {
        IRI predicate = Values.iri("https://example.org/hasMember");
        IRI roleType = Values.iri("https://w3id.org/kpxl/gen/terms/MemberRole");
        RoleProperty rp = new RoleProperty(predicate, roleType, RoleProperty.Direction.REGULAR);
        assertThrows(IllegalArgumentException.class,
                () -> SpaceRegistry.get().registerRoleProperty("RA1_unknown", rp));
    }

    @Test
    void getRoleProperties_emptyForKnownSpaceWithoutProperties() {
        IRI spaceIri = Values.iri("https://example.org/spaceA");
        String ref = SpaceRegistry.get().registerSpace("RA1", spaceIri).spaceRef();
        assertTrue(SpaceRegistry.get().getRoleProperties(ref).isEmpty());
    }

    @Test
    void getRoleProperties_returnsUnmodifiableSet() {
        IRI spaceIri = Values.iri("https://example.org/spaceA");
        String ref = SpaceRegistry.get().registerSpace("RA1", spaceIri).spaceRef();
        IRI predicate = Values.iri("https://example.org/hasMember");
        IRI roleType = Values.iri("https://w3id.org/kpxl/gen/terms/MemberRole");
        SpaceRegistry.get().registerRoleProperty(ref,
                new RoleProperty(predicate, roleType, RoleProperty.Direction.REGULAR));
        Set<RoleProperty> props = SpaceRegistry.get().getRoleProperties(ref);
        assertThrows(UnsupportedOperationException.class,
                () -> props.add(new RoleProperty(predicate, roleType, RoleProperty.Direction.INVERSE)));
    }

    @Test
    void recordSourceNanopub_buildsReverseIndex() {
        IRI source = Values.iri("https://example.org/np/abc");
        SpaceRegistry.get().recordSourceNanopub(source, "RA1_x");
        SpaceRegistry.get().recordSourceNanopub(source, "RA2_y");
        SpaceRegistry.get().recordSourceNanopub(source, "RA1_x"); // duplicate is no-op
        assertEquals(Set.of("RA1_x", "RA2_y"), SpaceRegistry.get().getSpaceRefsForSource(source));
    }

    @Test
    void getSpaceRefsForSource_emptyForUnknownSource() {
        IRI source = Values.iri("https://example.org/np/never-seen");
        assertTrue(SpaceRegistry.get().getSpaceRefsForSource(source).isEmpty());
    }

    @Test
    void removeSourceNanopub_returnsAffectedSpacesAndClearsEntry() {
        IRI source = Values.iri("https://example.org/np/abc");
        SpaceRegistry.get().recordSourceNanopub(source, "RA1_x");
        SpaceRegistry.get().recordSourceNanopub(source, "RA2_y");

        Set<String> removed = SpaceRegistry.get().removeSourceNanopub(source);
        assertEquals(Set.of("RA1_x", "RA2_y"), removed);
        assertTrue(SpaceRegistry.get().getSpaceRefsForSource(source).isEmpty());
    }

    @Test
    void removeSourceNanopub_returnsEmptyIfNotTracked() {
        IRI source = Values.iri("https://example.org/np/never-tracked");
        assertTrue(SpaceRegistry.get().removeSourceNanopub(source).isEmpty());
    }

}
