package com.knowledgepixels.query;

import com.knowledgepixels.query.vocabulary.GEN;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.DCTERMS;
import org.eclipse.rdf4j.model.vocabulary.OWL;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.nanopub.Nanopub;
import org.nanopub.NanopubCreator;
import org.nanopub.vocabulary.NPA;
import org.nanopub.vocabulary.NPX;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.knowledgepixels.query.vocabulary.SpacesVocab.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link SpacesExtractor}. Each test builds a fixture nanopub
 * with {@link NanopubCreator} (non-trusty, fixed URI) and asserts the expected
 * statements appear in the extractor's output.
 */
class SpacesExtractorTest {

    private static final ValueFactory vf = SimpleValueFactory.getInstance();

    /**
     * Stand-in nanopub base URI for fixtures (non-trusty; fixed artifact-code-like suffix).
     */
    private static final String NP_BASE = "https://w3id.org/np/RA-testAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    /**
     * Artifact code derived from {@link #NP_BASE} — the 43 chars after "RA".
     */
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
        if (mockedUtils != null) {
            mockedUtils.close();
        }
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

        // Admin RoleInstantiation (space-scoped, both admins, inverse direction —
        // <space> hasAdmin <agent> is space-centric, INVERSE under the publisher convention)
        assertContains(out, riIri, RDF.TYPE, GEN.ROLE_INSTANTIATION);
        assertContains(out, riIri, FOR_SPACE, SPACE_IRI_1);
        assertContains(out, riIri, INVERSE_PROPERTY, GEN.HAS_ADMIN);
        assertContains(out, riIri, FOR_AGENT, ADMIN_AGENT_1);
        assertContains(out, riIri, FOR_AGENT, ADMIN_AGENT_2);
        assertContains(out, riIri, VIA_NANOPUB, NP_URI);
    }

    @Test
    void extract_spaceWithInlineRoleTriples_emitsAdditionalRoleInstantiations() throws Exception {
        // A gen:Space nanopub may also declare other role-predicate assignments
        // inline alongside hasAdmin (event facilitators, participants, etc.).
        // Each distinct predicate should mint its own RoleInstantiation entry
        // — gen:hasAdmin keeps the unhashed subject npari:<artifactCode> (existing
        // behaviour); others use npari:<artifactCode>_<predicateHash> to avoid
        // collision.
        IRI facilitatorPred = vf.createIRI("https://w3id.org/fair/3pff/has-event-facilitator");
        IRI participantPred = vf.createIRI("https://w3id.org/fair/3pff/participatedAsParticipantIn");
        IRI participantAgent = vf.createIRI("https://orcid.org/0000-0000-0000-0004");

        Nanopub np = creator()
                .type(GEN.SPACE)
                .assertion(SPACE_IRI_1, RDF.TYPE, GEN.SPACE)
                .assertion(SPACE_IRI_1, GEN.HAS_ROOT_DEFINITION, NP_URI)
                .assertion(SPACE_IRI_1, GEN.HAS_ADMIN, ADMIN_AGENT_1)
                .assertion(SPACE_IRI_1, facilitatorPred, ADMIN_AGENT_1)
                .assertion(SPACE_IRI_1, facilitatorPred, ADMIN_AGENT_2)
                .assertion(participantAgent, participantPred, SPACE_IRI_1)
                .finalizeNanopub();

        List<Statement> out = SpacesExtractor.extract(np, defaultContext());
        IRI adminRi = forRoleInstantiation(ARTIFACT_CODE);
        IRI facilitatorRi = forRoleInstantiation(ARTIFACT_CODE, Utils.createHash(facilitatorPred.stringValue()));
        IRI participantRi = forRoleInstantiation(ARTIFACT_CODE, Utils.createHash(participantPred.stringValue()));

        // Admin RI unchanged (same subject as before).
        assertContains(out, adminRi, RDF.TYPE, GEN.ROLE_INSTANTIATION);
        assertContains(out, adminRi, INVERSE_PROPERTY, GEN.HAS_ADMIN);
        assertContains(out, adminRi, FOR_AGENT, ADMIN_AGENT_1);

        // Facilitator RI: INVERSE (space-centric), multi-valued forAgent.
        assertContains(out, facilitatorRi, RDF.TYPE, GEN.ROLE_INSTANTIATION);
        assertContains(out, facilitatorRi, FOR_SPACE, SPACE_IRI_1);
        assertContains(out, facilitatorRi, INVERSE_PROPERTY, facilitatorPred);
        assertContains(out, facilitatorRi, FOR_AGENT, ADMIN_AGENT_1);
        assertContains(out, facilitatorRi, FOR_AGENT, ADMIN_AGENT_2);
        assertContains(out, facilitatorRi, VIA_NANOPUB, NP_URI);

        // Participant RI: REGULAR (agent-centric).
        assertContains(out, participantRi, RDF.TYPE, GEN.ROLE_INSTANTIATION);
        assertContains(out, participantRi, FOR_SPACE, SPACE_IRI_1);
        assertContains(out, participantRi, REGULAR_PROPERTY, participantPred);
        assertContains(out, participantRi, FOR_AGENT, participantAgent);
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
    void extract_backcompatInverseDirection_emitsRoleInstantiation() throws Exception {
        // Space-centric source: <space> <predicate> <agent>. INVERSE under the publisher
        // convention. hasTeamMember is classified INVERSE in BackcompatRolePredicates.
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
        assertContains(out, subject, INVERSE_PROPERTY, hasTeamMember);
        assertContains(out, subject, FOR_AGENT, MEMBER_AGENT);       // object of the assertion triple
        assertDoesNotContain(out, subject, REGULAR_PROPERTY, hasTeamMember);
    }

    @Test
    void extract_backcompatHasGuestAndHost_emitsRoleInstantiation_issue114() throws Exception {
        // gen:hasGuest / gen:hasHost are space-centric (INVERSE), like hasObserver/hasAdmin.
        // They were missing from BackcompatRolePredicates, so the extractor dropped
        // guest/host member RIs entirely (issue #114).
        for (String pred : new String[]{
                "https://w3id.org/kpxl/gen/terms/hasGuest",
                "https://w3id.org/kpxl/gen/terms/hasHost"}) {
            IRI predicate = vf.createIRI(pred);
            // Single-triple assertion → auto-typed by the predicate via NanopubUtils.getTypes.
            Nanopub np = creator()
                    .assertion(SPACE_IRI_1, predicate, MEMBER_AGENT)
                    .finalizeNanopub();

            List<Statement> out = SpacesExtractor.extract(np, defaultContext());
            IRI subject = forRoleInstantiation(ARTIFACT_CODE);

            assertContains(out, subject, RDF.TYPE, GEN.ROLE_INSTANTIATION);
            assertContains(out, subject, FOR_SPACE, SPACE_IRI_1);
            assertContains(out, subject, INVERSE_PROPERTY, predicate);
            assertContains(out, subject, FOR_AGENT, MEMBER_AGENT);
        }
    }

    @Test
    void extract_backcompatRegularDirection_swapsSpaceAndAgent() throws Exception {
        // Agent-centric source: <agent> <predicate> <space>. REGULAR under the publisher
        // convention. plansToAttend is classified REGULAR in BackcompatRolePredicates.
        IRI plansToAttend = vf.createIRI("https://w3id.org/kpxl/gen/terms/plansToAttend");
        Nanopub np = creator()
                .assertion(MEMBER_AGENT, plansToAttend, SPACE_IRI_1)
                .finalizeNanopub();

        List<Statement> out = SpacesExtractor.extract(np, defaultContext());
        IRI subject = forRoleInstantiation(ARTIFACT_CODE);

        assertContains(out, subject, RDF.TYPE, GEN.ROLE_INSTANTIATION);
        assertContains(out, subject, FOR_SPACE, SPACE_IRI_1);        // object side
        assertContains(out, subject, REGULAR_PROPERTY, plansToAttend);
        assertContains(out, subject, FOR_AGENT, MEMBER_AGENT);       // subject side
        assertDoesNotContain(out, subject, INVERSE_PROPERTY, plansToAttend);
    }

    // ---------------- gen:RoleInstantiation with a custom (unclassified) predicate ----------------

    @Test
    void extract_customPredicateExplicitlyTyped_emitsNeutralBinding() throws Exception {
        // gen:hasMaintainer is a user-defined role predicate (declared via a
        // gen:SpaceMemberRole nanopub's gen:hasInverseProperty); it is neither
        // gen:hasAdmin nor in BackcompatRolePredicates, so the extractor can't classify
        // its direction. For a nanopub explicitly typed gen:RoleInstantiation we emit a
        // neutral binding carrying the raw (subject, predicate, object); the materializer
        // resolves direction + tier from the role declaration.
        IRI hasMaintainer = vf.createIRI("https://w3id.org/kpxl/gen/terms/hasMaintainer");
        Nanopub np = creator()
                .type(GEN.ROLE_INSTANTIATION)
                .assertion(SPACE_IRI_1, hasMaintainer, MEMBER_AGENT)
                .finalizeNanopub();

        List<Statement> out = SpacesExtractor.extract(np, defaultContext());
        IRI subject = forRoleInstantiation(ARTIFACT_CODE, Utils.createHash(hasMaintainer.stringValue()));

        assertContains(out, subject, RDF.TYPE, GEN.ROLE_INSTANTIATION);
        assertContains(out, subject, ROLE_PREDICATE, hasMaintainer);
        assertContains(out, subject, BINDING_SUBJECT, SPACE_IRI_1);
        assertContains(out, subject, BINDING_OBJECT, MEMBER_AGENT);
        assertContains(out, subject, VIA_NANOPUB, NP_URI);
        // No direction was committed: neither the classified split nor regular/inverse.
        assertDoesNotContain(out, subject, FOR_SPACE, SPACE_IRI_1);
        assertDoesNotContain(out, subject, FOR_AGENT, MEMBER_AGENT);
        assertDoesNotContain(out, subject, INVERSE_PROPERTY, hasMaintainer);
        assertDoesNotContain(out, subject, REGULAR_PROPERTY, hasMaintainer);
    }

    @Test
    void extract_customPredicateNotExplicitlyTyped_emitsNothing() throws Exception {
        // Without an explicit gen:RoleInstantiation type, a single-triple
        // <space> hasMaintainer <agent> only auto-types as hasMaintainer, which is not a
        // trigger type — so the nanopub isn't space-relevant and nothing is emitted. (The
        // neutral path is gated on the explicit type to avoid minting inert entries for
        // incidental IRI-valued triples in nanopubs matched only via a backcompat predicate.)
        IRI hasMaintainer = vf.createIRI("https://w3id.org/kpxl/gen/terms/hasMaintainer");
        Nanopub np = creator()
                .assertion(SPACE_IRI_1, hasMaintainer, MEMBER_AGENT)
                .finalizeNanopub();

        List<Statement> out = SpacesExtractor.extract(np, defaultContext());

        assertTrue(out.isEmpty(), "Untyped custom-predicate binding must not be space-relevant");
    }

    // ---------------- gen:isSubSpaceOf ----------------

    @Test
    void extract_subSpaceStandalone_singlePair_emitsSubSpaceDeclaration() throws Exception {
        IRI parentSpace = vf.createIRI("https://example.org/spaces/parent");
        // Single-triple assertion: NanopubUtils.getTypes promotes the predicate to a type.
        Nanopub np = creator()
                .assertion(SPACE_IRI_1, GEN.IS_SUB_SPACE_OF, parentSpace)
                .finalizeNanopub();

        List<Statement> out = SpacesExtractor.extract(np, defaultContext());
        IRI subject = forSubSpaceDeclaration(ARTIFACT_CODE, Utils.createHash(parentSpace));

        assertAllInSpacesGraph(out);
        assertContains(out, subject, RDF.TYPE, SUB_SPACE_DECLARATION);
        assertContains(out, subject, CHILD_SPACE, SPACE_IRI_1);
        assertContains(out, subject, PARENT_SPACE, parentSpace);
        assertContains(out, subject, VIA_NANOPUB, NP_URI);
        assertContains(out, subject, NPX.SIGNED_BY, SIGNER_AGENT);
    }

    @Test
    void extract_subSpaceStandalone_multiPair_emitsOnePerParent() throws Exception {
        // Multi-triple assertion: predicate dispatch may not fire on multi-triple
        // assertions, so add the type explicitly via npx:hasNanopubType.
        IRI parent1 = vf.createIRI("https://example.org/spaces/parent1");
        IRI parent2 = vf.createIRI("https://example.org/spaces/parent2");
        Nanopub np = creator()
                .type(GEN.IS_SUB_SPACE_OF)
                .assertion(SPACE_IRI_1, GEN.IS_SUB_SPACE_OF, parent1)
                .assertion(SPACE_IRI_1, GEN.IS_SUB_SPACE_OF, parent2)
                .finalizeNanopub();

        List<Statement> out = SpacesExtractor.extract(np, defaultContext());
        IRI subj1 = forSubSpaceDeclaration(ARTIFACT_CODE, Utils.createHash(parent1));
        IRI subj2 = forSubSpaceDeclaration(ARTIFACT_CODE, Utils.createHash(parent2));

        assertContains(out, subj1, RDF.TYPE, SUB_SPACE_DECLARATION);
        assertContains(out, subj1, PARENT_SPACE, parent1);
        assertContains(out, subj2, RDF.TYPE, SUB_SPACE_DECLARATION);
        assertContains(out, subj2, PARENT_SPACE, parent2);
        // Distinct subjects — different parent hashes.
        assertNotEquals(subj1, subj2, "Per-parent subjects must differ");
    }

    @Test
    void extract_subSpaceStandalone_selfLoop_isRejected() throws Exception {
        Nanopub np = creator()
                .assertion(SPACE_IRI_1, GEN.IS_SUB_SPACE_OF, SPACE_IRI_1)
                .finalizeNanopub();

        List<Statement> out = SpacesExtractor.extract(np, defaultContext());
        assertTrue(out.isEmpty(),
                "Self-loop sub-space declaration should produce no extraction; got: " + out);
    }

    @Test
    void extract_subSpaceEmbeddedInGenSpace_emitsSubSpaceDeclaration() throws Exception {
        IRI parentSpace = vf.createIRI("https://example.org/spaces/parent");
        Nanopub np = creator()
                .type(GEN.SPACE)
                .assertion(SPACE_IRI_1, RDF.TYPE, GEN.SPACE)
                .assertion(SPACE_IRI_1, GEN.HAS_ROOT_DEFINITION, NP_URI)
                .assertion(SPACE_IRI_1, GEN.HAS_ADMIN, ADMIN_AGENT_1)
                .assertion(SPACE_IRI_1, GEN.IS_SUB_SPACE_OF, parentSpace)
                .finalizeNanopub();

        List<Statement> out = SpacesExtractor.extract(np, defaultContext());
        IRI subject = forSubSpaceDeclaration(ARTIFACT_CODE, Utils.createHash(parentSpace));

        // Sub-space declaration emitted alongside the SpaceRef / SpaceDefinition.
        assertContains(out, subject, RDF.TYPE, SUB_SPACE_DECLARATION);
        assertContains(out, subject, CHILD_SPACE, SPACE_IRI_1);
        assertContains(out, subject, PARENT_SPACE, parentSpace);
        assertContains(out, subject, VIA_NANOPUB, NP_URI);

        // SpaceRef + SpaceDefinition still present (regression guard).
        String spaceRef = ARTIFACT_CODE + "_" + Utils.createHash(SPACE_IRI_1);
        assertContains(out, forSpaceRef(spaceRef), RDF.TYPE, SPACE_REF);
        assertContains(out, forSpaceDefinition(ARTIFACT_CODE), RDF.TYPE, SPACE_DEFINITION);
    }

    @Test
    void extract_subSpaceEmbeddedInGenSpace_subjectMustBeDeclaredSpaceIri() throws Exception {
        // A gen:isSubSpaceOf triple whose subject isn't one of the declared Space IRIs
        // is ignored on the embedded path. Authors who want to declare for an unrelated
        // IRI should publish a separate gen:isSubSpaceOf nanopub.
        IRI unrelated = vf.createIRI("https://example.org/somewhere/else");
        IRI parentSpace = vf.createIRI("https://example.org/spaces/parent");
        Nanopub np = creator()
                .type(GEN.SPACE)
                .assertion(SPACE_IRI_1, RDF.TYPE, GEN.SPACE)
                .assertion(SPACE_IRI_1, GEN.HAS_ROOT_DEFINITION, NP_URI)
                .assertion(SPACE_IRI_1, GEN.HAS_ADMIN, ADMIN_AGENT_1)
                .assertion(unrelated, GEN.IS_SUB_SPACE_OF, parentSpace)
                .finalizeNanopub();

        List<Statement> out = SpacesExtractor.extract(np, defaultContext());
        IRI rejectedSubject = forSubSpaceDeclaration(ARTIFACT_CODE, Utils.createHash(parentSpace));
        assertDoesNotContain(out, rejectedSubject, RDF.TYPE, SUB_SPACE_DECLARATION);
    }

    // ---------------- owl:sameAs (space aliases, issue #113) ----------------

    @Test
    void extract_sameAsEmbeddedInGenSpace_emitsSpaceAliasDeclaration() throws Exception {
        IRI alias = vf.createIRI("https://example.org/spaces/old-alpha");
        Nanopub np = creator()
                .type(GEN.SPACE)
                .assertion(SPACE_IRI_1, RDF.TYPE, GEN.SPACE)
                .assertion(SPACE_IRI_1, GEN.HAS_ROOT_DEFINITION, NP_URI)
                .assertion(SPACE_IRI_1, GEN.HAS_ADMIN, ADMIN_AGENT_1)
                .assertion(SPACE_IRI_1, OWL.SAMEAS, alias)
                .finalizeNanopub();

        List<Statement> out = SpacesExtractor.extract(np, defaultContext());
        IRI subject = forSpaceAliasDeclaration(ARTIFACT_CODE, Utils.createHash(alias));

        // Alias declaration emitted alongside the SpaceRef / SpaceDefinition, with the
        // declared Space IRI as the canonical side and the owl:sameAs object as alias.
        assertContains(out, subject, RDF.TYPE, SPACE_ALIAS_DECLARATION);
        assertContains(out, subject, CANONICAL_SPACE, SPACE_IRI_1);
        assertContains(out, subject, ALIAS_SPACE, alias);
        assertContains(out, subject, VIA_NANOPUB, NP_URI);
        // Provenance carried so the materializer can gate on the publisher.
        assertContains(out, subject, NPX.SIGNED_BY, SIGNER_AGENT);

        // SpaceRef + SpaceDefinition still present (regression guard).
        String spaceRef = ARTIFACT_CODE + "_" + Utils.createHash(SPACE_IRI_1);
        assertContains(out, forSpaceRef(spaceRef), RDF.TYPE, SPACE_REF);
        assertContains(out, forSpaceDefinition(ARTIFACT_CODE), RDF.TYPE, SPACE_DEFINITION);
    }

    @Test
    void extract_sameAsEmbeddedInGenSpace_subjectMustBeDeclaredSpaceIri() throws Exception {
        // An owl:sameAs triple whose subject isn't the declared Space IRI is ignored:
        // the alias must be declared by the space claiming it.
        IRI unrelated = vf.createIRI("https://example.org/somewhere/else");
        IRI alias = vf.createIRI("https://example.org/spaces/old-alpha");
        Nanopub np = creator()
                .type(GEN.SPACE)
                .assertion(SPACE_IRI_1, RDF.TYPE, GEN.SPACE)
                .assertion(SPACE_IRI_1, GEN.HAS_ROOT_DEFINITION, NP_URI)
                .assertion(SPACE_IRI_1, GEN.HAS_ADMIN, ADMIN_AGENT_1)
                .assertion(unrelated, OWL.SAMEAS, alias)
                .finalizeNanopub();

        List<Statement> out = SpacesExtractor.extract(np, defaultContext());
        IRI rejectedSubject = forSpaceAliasDeclaration(ARTIFACT_CODE, Utils.createHash(alias));
        assertDoesNotContain(out, rejectedSubject, RDF.TYPE, SPACE_ALIAS_DECLARATION);
    }

    @Test
    void extract_sameAsSelfAlias_isRejected() throws Exception {
        Nanopub np = creator()
                .type(GEN.SPACE)
                .assertion(SPACE_IRI_1, RDF.TYPE, GEN.SPACE)
                .assertion(SPACE_IRI_1, GEN.HAS_ROOT_DEFINITION, NP_URI)
                .assertion(SPACE_IRI_1, GEN.HAS_ADMIN, ADMIN_AGENT_1)
                .assertion(SPACE_IRI_1, OWL.SAMEAS, SPACE_IRI_1)
                .finalizeNanopub();

        List<Statement> out = SpacesExtractor.extract(np, defaultContext());
        IRI rejectedSubject = forSpaceAliasDeclaration(ARTIFACT_CODE, Utils.createHash(SPACE_IRI_1));
        assertDoesNotContain(out, rejectedSubject, RDF.TYPE, SPACE_ALIAS_DECLARATION);
    }

    // ---------------- gen:isMaintainedBy ----------------

    @Test
    void extract_maintainedResourceStandalone_singlePair_emitsDeclaration() throws Exception {
        IRI resource = vf.createIRI("https://example.org/ontologies/foo");
        // Single-triple assertion: NanopubUtils.getTypes promotes the predicate to a type.
        Nanopub np = creator()
                .assertion(resource, GEN.IS_MAINTAINED_BY, SPACE_IRI_1)
                .finalizeNanopub();

        List<Statement> out = SpacesExtractor.extract(np, defaultContext());
        IRI subject = forMaintainedResourceDeclaration(ARTIFACT_CODE, Utils.createHash(resource));

        assertAllInSpacesGraph(out);
        assertContains(out, subject, RDF.TYPE, MAINTAINED_RESOURCE_DECLARATION);
        assertContains(out, subject, RESOURCE_IRI, resource);
        assertContains(out, subject, MAINTAINER_SPACE, SPACE_IRI_1);
        assertContains(out, subject, VIA_NANOPUB, NP_URI);
        assertContains(out, subject, NPX.SIGNED_BY, SIGNER_AGENT);
    }

    @Test
    void extract_maintainedResourceStandalone_multiPair_emitsOnePerResource() throws Exception {
        // Multi-triple assertion: predicate dispatch may not fire on multi-triple
        // assertions, so add the type explicitly via npx:hasNanopubType.
        IRI resource1 = vf.createIRI("https://example.org/ontologies/foo");
        IRI resource2 = vf.createIRI("https://example.org/ontologies/bar");
        Nanopub np = creator()
                .type(GEN.IS_MAINTAINED_BY)
                .assertion(resource1, GEN.IS_MAINTAINED_BY, SPACE_IRI_1)
                .assertion(resource2, GEN.IS_MAINTAINED_BY, SPACE_IRI_1)
                .finalizeNanopub();

        List<Statement> out = SpacesExtractor.extract(np, defaultContext());
        IRI subj1 = forMaintainedResourceDeclaration(ARTIFACT_CODE, Utils.createHash(resource1));
        IRI subj2 = forMaintainedResourceDeclaration(ARTIFACT_CODE, Utils.createHash(resource2));

        assertContains(out, subj1, RDF.TYPE, MAINTAINED_RESOURCE_DECLARATION);
        assertContains(out, subj1, RESOURCE_IRI, resource1);
        assertContains(out, subj2, RDF.TYPE, MAINTAINED_RESOURCE_DECLARATION);
        assertContains(out, subj2, RESOURCE_IRI, resource2);
        // Distinct subjects — different resource hashes.
        assertNotEquals(subj1, subj2, "Per-resource subjects must differ");
    }

    @Test
    void extract_maintainedResourceStandalone_resourceClassTypeMarker_emitsDeclaration() throws Exception {
        // Publisher convention used by Nanodash: type the nanopub at the head/pubinfo
        // level as gen:MaintainedResource (the resource class) rather than
        // gen:isMaintainedBy (the predicate). The extractor accepts either marker as
        // the type gate; both shapes carry the <r> gen:isMaintainedBy <s> link.
        IRI resource = vf.createIRI("https://example.org/ontologies/foo");
        Nanopub np = creator()
                .type(GEN.MAINTAINED_RESOURCE)
                .assertion(resource, RDF.TYPE, GEN.MAINTAINED_RESOURCE)
                .assertion(resource, GEN.IS_MAINTAINED_BY, SPACE_IRI_1)
                .finalizeNanopub();

        List<Statement> out = SpacesExtractor.extract(np, defaultContext());
        IRI subject = forMaintainedResourceDeclaration(ARTIFACT_CODE, Utils.createHash(resource));
        assertContains(out, subject, RDF.TYPE, MAINTAINED_RESOURCE_DECLARATION);
        assertContains(out, subject, RESOURCE_IRI, resource);
        assertContains(out, subject, MAINTAINER_SPACE, SPACE_IRI_1);
    }

    @Test
    void extract_maintainedResourceStandalone_typeTripleIsInformativeOnly() throws Exception {
        // The publisher contract for a maintained-resource-defining nanopub includes
        // <r> rdf:type gen:MaintainedResource in the assertion, but the extractor does
        // not gate on it — the gen:isMaintainedBy triple alone produces the declaration.
        IRI resource = vf.createIRI("https://example.org/ontologies/foo");
        Nanopub np = creator()
                .type(GEN.IS_MAINTAINED_BY)
                .assertion(resource, RDF.TYPE, GEN.MAINTAINED_RESOURCE)
                .assertion(resource, GEN.IS_MAINTAINED_BY, SPACE_IRI_1)
                .finalizeNanopub();

        List<Statement> out = SpacesExtractor.extract(np, defaultContext());
        IRI subject = forMaintainedResourceDeclaration(ARTIFACT_CODE, Utils.createHash(resource));
        assertContains(out, subject, RDF.TYPE, MAINTAINED_RESOURCE_DECLARATION);
        assertContains(out, subject, RESOURCE_IRI, resource);
        assertContains(out, subject, MAINTAINER_SPACE, SPACE_IRI_1);
    }

    @Test
    void extract_maintainedResourceStandalone_selfLoop_isRejected() throws Exception {
        Nanopub np = creator()
                .assertion(SPACE_IRI_1, GEN.IS_MAINTAINED_BY, SPACE_IRI_1)
                .finalizeNanopub();

        List<Statement> out = SpacesExtractor.extract(np, defaultContext());
        assertTrue(out.isEmpty(),
                "Self-loop maintained-resource declaration should produce no extraction; got: " + out);
    }

    @Test
    void extract_maintainedResourceEmbeddedInGenSpace_emitsDeclaration() throws Exception {
        IRI resource = vf.createIRI("https://example.org/ontologies/foo");
        Nanopub np = creator()
                .type(GEN.SPACE)
                .assertion(SPACE_IRI_1, RDF.TYPE, GEN.SPACE)
                .assertion(SPACE_IRI_1, GEN.HAS_ROOT_DEFINITION, NP_URI)
                .assertion(SPACE_IRI_1, GEN.HAS_ADMIN, ADMIN_AGENT_1)
                .assertion(resource, GEN.IS_MAINTAINED_BY, SPACE_IRI_1)
                .finalizeNanopub();

        List<Statement> out = SpacesExtractor.extract(np, defaultContext());
        IRI subject = forMaintainedResourceDeclaration(ARTIFACT_CODE, Utils.createHash(resource));

        // Maintained-resource declaration emitted alongside the SpaceRef / SpaceDefinition.
        assertContains(out, subject, RDF.TYPE, MAINTAINED_RESOURCE_DECLARATION);
        assertContains(out, subject, RESOURCE_IRI, resource);
        assertContains(out, subject, MAINTAINER_SPACE, SPACE_IRI_1);
        assertContains(out, subject, VIA_NANOPUB, NP_URI);

        // SpaceRef + SpaceDefinition still present (regression guard).
        String spaceRef = ARTIFACT_CODE + "_" + Utils.createHash(SPACE_IRI_1);
        assertContains(out, forSpaceRef(spaceRef), RDF.TYPE, SPACE_REF);
        assertContains(out, forSpaceDefinition(ARTIFACT_CODE), RDF.TYPE, SPACE_DEFINITION);
    }

    @Test
    void extract_maintainedResourceEmbeddedInGenSpace_objectMustBeDeclaredSpaceIri() throws Exception {
        // A gen:isMaintainedBy triple whose object isn't one of the declared Space IRIs
        // is ignored on the embedded path. Authors who want to declare for an unrelated
        // space should publish a separate gen:isMaintainedBy nanopub.
        IRI resource = vf.createIRI("https://example.org/ontologies/foo");
        IRI otherSpace = vf.createIRI("https://example.org/spaces/other");
        Nanopub np = creator()
                .type(GEN.SPACE)
                .assertion(SPACE_IRI_1, RDF.TYPE, GEN.SPACE)
                .assertion(SPACE_IRI_1, GEN.HAS_ROOT_DEFINITION, NP_URI)
                .assertion(SPACE_IRI_1, GEN.HAS_ADMIN, ADMIN_AGENT_1)
                .assertion(resource, GEN.IS_MAINTAINED_BY, otherSpace)
                .finalizeNanopub();

        List<Statement> out = SpacesExtractor.extract(np, defaultContext());
        IRI rejectedSubject = forMaintainedResourceDeclaration(ARTIFACT_CODE, Utils.createHash(resource));
        assertDoesNotContain(out, rejectedSubject, RDF.TYPE, MAINTAINED_RESOURCE_DECLARATION);
    }

    // ---------------- gen:Preset / gen:PresetAssignment (issue #302) ----------------

    @Test
    void extract_preset_emitsPresetDeclaration() throws Exception {
        // Embedded preset node (starts with the nanopub URI), version-of a stable kind.
        IRI presetIri = vf.createIRI(NP_BASE + "/preset");
        IRI presetKind = vf.createIRI("https://example.org/presets/nano-session");
        IRI role = vf.createIRI("https://w3id.org/np/RA-defAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA/maintainer");

        Nanopub np = creator()
                .type(GEN.PRESET)
                .assertion(presetIri, RDF.TYPE, GEN.PRESET)
                .assertion(presetIri, DCTERMS.IS_VERSION_OF, presetKind)
                .assertion(presetIri, GEN.APPLIES_TO_INSTANCES_OF, GEN.SPACE)
                .assertion(presetIri, GEN.HAS_ROLE, role)
                .finalizeNanopub();

        List<Statement> out = SpacesExtractor.extract(np, defaultContext());
        IRI subject = forPresetDeclaration(ARTIFACT_CODE);

        assertAllInSpacesGraph(out);
        assertContains(out, subject, RDF.TYPE, PRESET_DECLARATION);
        // Canonical kind = the dct:isVersionOf target.
        assertContains(out, subject, PRESET_KIND, presetKind);
        // Lookup key emitted for BOTH the node IRI and the version-independent kind, so an
        // assignment naming either maps to the canonical kind.
        assertContains(out, subject, OF_PRESET, presetIri);
        assertContains(out, subject, OF_PRESET, presetKind);
        assertContains(out, subject, PRESET_ROLE, role);
        assertContains(out, subject, APPLIES_TO_INSTANCES_OF, GEN.SPACE);
        assertContains(out, subject, VIA_NANOPUB, NP_URI);
        assertContains(out, subject, NPX.SIGNED_BY, SIGNER_AGENT);
    }

    @Test
    void extract_preset_noVersionOf_kindFallsBackToNodeIri() throws Exception {
        // No dct:isVersionOf: the canonical kind falls back to the preset node IRI,
        // matching Nanodash ViewDisplay.getViewKindIri()'s fallback.
        IRI presetIri = vf.createIRI(NP_BASE + "/preset");
        IRI role = vf.createIRI("https://w3id.org/np/RA-defAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA/maintainer");

        Nanopub np = creator()
                .type(GEN.PRESET)
                .assertion(presetIri, RDF.TYPE, GEN.PRESET)
                .assertion(presetIri, GEN.APPLIES_TO_INSTANCES_OF, GEN.SPACE)
                .assertion(presetIri, GEN.HAS_ROLE, role)
                .finalizeNanopub();

        List<Statement> out = SpacesExtractor.extract(np, defaultContext());
        IRI subject = forPresetDeclaration(ARTIFACT_CODE);
        assertContains(out, subject, PRESET_KIND, presetIri);
        assertContains(out, subject, OF_PRESET, presetIri);
    }

    @Test
    void extract_preset_nonEmbeddedPreset_emitsNothing() throws Exception {
        // Preset IRI outside the nanopub's namespace — ignored (mirrors role-declaration).
        IRI externalPreset = vf.createIRI("https://some.other.site/preset");
        Nanopub np = creator()
                .type(GEN.PRESET)
                .assertion(externalPreset, RDF.TYPE, GEN.PRESET)
                .assertion(externalPreset, GEN.HAS_ROLE,
                        vf.createIRI("https://some.other.site/role"))
                .finalizeNanopub();

        List<Statement> out = SpacesExtractor.extract(np, defaultContext());
        assertTrue(out.isEmpty(),
                "Extractor should ignore preset IRIs outside the nanopub's namespace, got: " + out);
    }

    @Test
    void extract_presetAssignment_active_emitsActivatedRow() throws Exception {
        IRI assignment = vf.createIRI(NP_BASE + "/assignment");
        IRI preset = vf.createIRI("https://example.org/presets/nano-session");

        Nanopub np = creator()
                .type(GEN.PRESET_ASSIGNMENT)
                .assertion(assignment, RDF.TYPE, GEN.PRESET_ASSIGNMENT)
                .assertion(assignment, RDF.TYPE, GEN.ACTIVATED_PRESET_ASSIGNMENT)
                .assertion(assignment, GEN.IS_ASSIGNMENT_OF_PRESET, preset)
                .assertion(assignment, GEN.IS_ASSIGNMENT_FOR, SPACE_IRI_1)
                .finalizeNanopub();

        List<Statement> out = SpacesExtractor.extract(np, defaultContext());
        IRI subject = forPresetAssignment(ARTIFACT_CODE);

        assertAllInSpacesGraph(out);
        assertContains(out, subject, RDF.TYPE, PRESET_ASSIGNMENT);
        assertContains(out, subject, OF_PRESET, preset);
        assertContains(out, subject, FOR_RESOURCE, SPACE_IRI_1);
        assertContains(out, subject, IS_ACTIVATED, vf.createLiteral(true));
        assertContains(out, subject, VIA_NANOPUB, NP_URI);
        // dct:created is the latest-wins key — must be present.
        assertContains(out, subject, DCTERMS.CREATED, vf.createLiteral(new Date(1_700_000_000_000L)));
    }

    @Test
    void extract_presetAssignment_deactivated_isActivatedFalse() throws Exception {
        IRI assignment = vf.createIRI(NP_BASE + "/assignment");
        IRI preset = vf.createIRI("https://example.org/presets/nano-session");

        Nanopub np = creator()
                .type(GEN.PRESET_ASSIGNMENT)
                .assertion(assignment, RDF.TYPE, GEN.PRESET_ASSIGNMENT)
                .assertion(assignment, RDF.TYPE, GEN.DEACTIVATED_PRESET_ASSIGNMENT)
                .assertion(assignment, GEN.IS_ASSIGNMENT_OF_PRESET, preset)
                .assertion(assignment, GEN.IS_ASSIGNMENT_FOR, SPACE_IRI_1)
                .finalizeNanopub();

        List<Statement> out = SpacesExtractor.extract(np, defaultContext());
        IRI subject = forPresetAssignment(ARTIFACT_CODE);
        assertContains(out, subject, IS_ACTIVATED, vf.createLiteral(false));
    }

    @Test
    void extract_presetAssignment_noActivationType_defaultsActive() throws Exception {
        // Active-by-default: an assignment typed only gen:PresetAssignment (no explicit
        // Activated/Deactivated) is active, matching Nanodash's PresetAssignment.isActive().
        IRI assignment = vf.createIRI(NP_BASE + "/assignment");
        IRI preset = vf.createIRI("https://example.org/presets/nano-session");

        Nanopub np = creator()
                .type(GEN.PRESET_ASSIGNMENT)
                .assertion(assignment, RDF.TYPE, GEN.PRESET_ASSIGNMENT)
                .assertion(assignment, GEN.IS_ASSIGNMENT_OF_PRESET, preset)
                .assertion(assignment, GEN.IS_ASSIGNMENT_FOR, SPACE_IRI_1)
                .finalizeNanopub();

        List<Statement> out = SpacesExtractor.extract(np, defaultContext());
        IRI subject = forPresetAssignment(ARTIFACT_CODE);
        assertContains(out, subject, IS_ACTIVATED, vf.createLiteral(true));
    }

    @Test
    void extract_presetAssignment_missingResource_emitsNothing() throws Exception {
        IRI assignment = vf.createIRI(NP_BASE + "/assignment");
        IRI preset = vf.createIRI("https://example.org/presets/nano-session");

        Nanopub np = creator()
                .type(GEN.PRESET_ASSIGNMENT)
                .assertion(assignment, RDF.TYPE, GEN.PRESET_ASSIGNMENT)
                .assertion(assignment, GEN.IS_ASSIGNMENT_OF_PRESET, preset)
                .finalizeNanopub();

        List<Statement> out = SpacesExtractor.extract(np, defaultContext());
        assertDoesNotContain(out, forPresetAssignment(ARTIFACT_CODE), RDF.TYPE, PRESET_ASSIGNMENT);
    }

    // ---------------- HAS_ID_PREFIX (regression coverage) ----------------

    @Test
    void extract_genSpace_emitsHasIdPrefixOnSpaceRef() throws Exception {
        // Regression / new-behaviour check: every gen:Space extraction now decorates
        // the SpaceRef aggregate with the path-prefix enumeration.
        Nanopub np = creator()
                .type(GEN.SPACE)
                .assertion(SPACE_IRI_1, RDF.TYPE, GEN.SPACE)
                .assertion(SPACE_IRI_1, GEN.HAS_ROOT_DEFINITION, NP_URI)
                .assertion(SPACE_IRI_1, GEN.HAS_ADMIN, ADMIN_AGENT_1)
                .finalizeNanopub();

        List<Statement> out = SpacesExtractor.extract(np, defaultContext());
        String spaceRef = ARTIFACT_CODE + "_" + Utils.createHash(SPACE_IRI_1);
        IRI refIri = forSpaceRef(spaceRef);
        // SPACE_IRI_1 = https://example.org/spaces/alpha → direct parent only.
        assertContains(out, refIri, HAS_ID_PREFIX, vf.createIRI("https://example.org/spaces"));
        // The deeper ancestor (host) should NOT be emitted: direct-parent-only
        // semantics means consumers walk transitively via npa:hasSubSpace+ at
        // query time, not via per-extraction multi-prefix triples.
        assertDoesNotContain(out, refIri, HAS_ID_PREFIX, vf.createIRI("https://example.org"));
    }

    // ---------------- ID-prefix enumeration ----------------

    @Test
    void enumerateIdPrefixes_typicalPath_returnsImmediateParent() {
        // Direct-parent-only: drops exactly one path segment, regardless of depth.
        // Multi-level descendants are reachable via SPARQL property paths
        // (npa:hasSubSpace+) at consumer query time.
        List<IRI> out = SpacesExtractor.enumerateIdPrefixes(
                vf.createIRI("https://example.org/a/b/c/space"));
        assertEquals(List.of(vf.createIRI("https://example.org/a/b/c")), out);
    }

    @Test
    void enumerateIdPrefixes_singleSegment_returnsHostOnly() {
        List<IRI> out = SpacesExtractor.enumerateIdPrefixes(
                vf.createIRI("https://example.org/space"));
        assertEquals(List.of(vf.createIRI("https://example.org")), out);
    }

    @Test
    void enumerateIdPrefixes_trailingSlash_isStripped() {
        List<IRI> out = SpacesExtractor.enumerateIdPrefixes(
                vf.createIRI("https://example.org/a/b/"));
        assertEquals(List.of(vf.createIRI("https://example.org/a")), out);
    }

    @Test
    void enumerateIdPrefixes_queryAndFragment_areStripped() {
        List<IRI> out = SpacesExtractor.enumerateIdPrefixes(
                vf.createIRI("https://example.org/a/space?x=1#frag"));
        assertEquals(List.of(vf.createIRI("https://example.org/a")), out);
    }

    @Test
    void enumerateIdPrefixes_hostOnlyIri_returnsEmpty() {
        assertTrue(SpacesExtractor.enumerateIdPrefixes(
                vf.createIRI("https://example.org")).isEmpty());
        assertTrue(SpacesExtractor.enumerateIdPrefixes(
                vf.createIRI("https://example.org/")).isEmpty());
    }

    @Test
    void enumerateIdPrefixes_nonHttpScheme_returnsEmptyForUrn() {
        // URNs have no path-style structure; the enumerator returns empty rather than
        // try to chop colon-separated NIDs.
        assertTrue(SpacesExtractor.enumerateIdPrefixes(
                vf.createIRI("urn:nbn:de:0287-2003-12345")).isEmpty());
    }

    // ---------------- isSpaceRelevant / TRIGGER_TYPES ----------------

    @Test
    void triggerTypes_containsAllDispatchTypes() {
        // The constant is the public source of truth NanopubLoader checks against
        // when deciding whether a retractor's invalidation target is space-relevant.
        // Locking in the membership here prevents drift from the dispatch in extract().
        assertTrue(SpacesExtractor.TRIGGER_TYPES.contains(GEN.SPACE));
        assertTrue(SpacesExtractor.TRIGGER_TYPES.contains(GEN.HAS_ROLE));
        assertTrue(SpacesExtractor.TRIGGER_TYPES.contains(GEN.SPACE_MEMBER_ROLE));
        assertTrue(SpacesExtractor.TRIGGER_TYPES.contains(GEN.ROLE_INSTANTIATION));
        assertTrue(SpacesExtractor.TRIGGER_TYPES.contains(GEN.IS_SUB_SPACE_OF));
        assertTrue(SpacesExtractor.TRIGGER_TYPES.contains(GEN.MAINTAINED_RESOURCE));
        assertTrue(SpacesExtractor.TRIGGER_TYPES.contains(GEN.IS_MAINTAINED_BY));
        assertTrue(SpacesExtractor.TRIGGER_TYPES.contains(GEN.PRESET));
        assertTrue(SpacesExtractor.TRIGGER_TYPES.contains(GEN.PRESET_ASSIGNMENT));
        for (IRI p : com.knowledgepixels.query.vocabulary.BackcompatRolePredicates.ALL) {
            assertTrue(SpacesExtractor.TRIGGER_TYPES.contains(p),
                    "backcompat role predicate missing from TRIGGER_TYPES: " + p);
        }
    }

    @Test
    void isSpaceRelevant_truePositiveAndTrueNegative() {
        assertTrue(SpacesExtractor.isSpaceRelevant(Set.of(GEN.SPACE)));
        assertTrue(SpacesExtractor.isSpaceRelevant(Set.of(GEN.IS_MAINTAINED_BY)));
        assertTrue(SpacesExtractor.isSpaceRelevant(
                Set.of(vf.createIRI("https://example.org/Foo"), GEN.HAS_ROLE)));
        // Non-space types — including a template-typed nanopub, which is the
        // collateral case the spaces-load narrowing exists to exclude.
        assertFalse(SpacesExtractor.isSpaceRelevant(Set.of()));
        assertFalse(SpacesExtractor.isSpaceRelevant(
                Set.of(vf.createIRI("https://w3id.org/np/o/ntemplate/AssertionTemplate"))));
        assertFalse(SpacesExtractor.isSpaceRelevant(
                Set.of(vf.createIRI("http://purl.org/nanopub/x/ExampleNanopub"))));
    }

    // ---------------- Load-counter stamping ----------------

    @Test
    void loadCounterStatements_stampsOnNanopubAndAdmin() {
        List<Statement> out = SpacesExtractor.loadCounterStatements(NP_URI, 42L);
        assertEquals(2, out.size(), "Expect exactly the load-number stamp + counter bump");
        assertContainsInContext(out, NP_URI, NPA.HAS_LOAD_NUMBER, vf.createLiteral(42L), NPA.GRAPH);
        assertContainsInContext(out, NPA.THIS_REPO, CURRENT_LOAD_COUNTER, vf.createLiteral(42L), NPA.GRAPH);
    }

    // ---------------- gen:RevokedRoleInstantiation / gen:detachedRole (issue #129) ----------------

    @Test
    void extract_revokedRoleInstantiation_nonAdmin_emitsRoleRevocationRow() throws Exception {
        IRI role = vf.createIRI("https://example.org/roles/reviewer");
        IRI revNode = vf.createIRI(NP_BASE + "#rev");
        Nanopub np = creator()
                .type(GEN.REVOKED_ROLE_INSTANTIATION)
                .assertion(revNode, RDF.TYPE, GEN.REVOKED_ROLE_INSTANTIATION)
                .assertion(revNode, GEN.FOR_SPACE, SPACE_IRI_1)
                .assertion(revNode, GEN.FOR_AGENT, MEMBER_AGENT)
                .assertion(revNode, GEN.HAS_ROLE, role)
                .finalizeNanopub();

        List<Statement> out = SpacesExtractor.extract(np, defaultContext());
        IRI subject = forRoleRevocation(ARTIFACT_CODE,
                Utils.createHash(MEMBER_AGENT.stringValue() + "\n" + role.stringValue()));
        assertContains(out, subject, RDF.TYPE, ROLE_REVOCATION);
        assertContains(out, subject, FOR_SPACE, SPACE_IRI_1);
        assertContains(out, subject, FOR_AGENT, MEMBER_AGENT);
        assertContains(out, subject, REVOKED_ROLE, role);
        assertContains(out, subject, VIA_NANOPUB, NP_URI);
        // dct:created (the latest-wins key) rides along via addProvenance.
        assertContains(out, subject, DCTERMS.CREATED, vf.createLiteral(new Date(1_700_000_000_000L)));
    }

    @Test
    void extract_revokedRoleInstantiation_admin_keysOnAdminRole() throws Exception {
        // Admin revocation carries gen:hasRole gen:AdminRole (decision #1), so the extractor
        // keys the negative on gen:AdminRole — matching the npa:hasRoleType gen:AdminRole the
        // admin tier stamps. One vocab term, one extraction path.
        IRI revNode = vf.createIRI(NP_BASE + "#rev");
        Nanopub np = creator()
                .type(GEN.REVOKED_ROLE_INSTANTIATION)
                .assertion(revNode, RDF.TYPE, GEN.REVOKED_ROLE_INSTANTIATION)
                .assertion(revNode, GEN.FOR_SPACE, SPACE_IRI_1)
                .assertion(revNode, GEN.FOR_AGENT, ADMIN_AGENT_1)
                .assertion(revNode, GEN.HAS_ROLE, GEN.ADMIN_ROLE)
                .finalizeNanopub();

        List<Statement> out = SpacesExtractor.extract(np, defaultContext());
        IRI subject = forRoleRevocation(ARTIFACT_CODE,
                Utils.createHash(ADMIN_AGENT_1.stringValue() + "\n" + GEN.ADMIN_ROLE.stringValue()));
        assertContains(out, subject, RDF.TYPE, ROLE_REVOCATION);
        assertContains(out, subject, FOR_AGENT, ADMIN_AGENT_1);
        assertContains(out, subject, REVOKED_ROLE, GEN.ADMIN_ROLE);
    }

    @Test
    void extract_revokedRoleInstantiation_missingForAgent_emitsNothing() throws Exception {
        IRI role = vf.createIRI("https://example.org/roles/reviewer");
        IRI revNode = vf.createIRI(NP_BASE + "#rev");
        Nanopub np = creator()
                .type(GEN.REVOKED_ROLE_INSTANTIATION)
                .assertion(revNode, RDF.TYPE, GEN.REVOKED_ROLE_INSTANTIATION)
                .assertion(revNode, GEN.FOR_SPACE, SPACE_IRI_1)
                .assertion(revNode, GEN.HAS_ROLE, role)
                .finalizeNanopub();

        List<Statement> out = SpacesExtractor.extract(np, defaultContext());
        assertTrue(out.stream().noneMatch(st -> ROLE_REVOCATION.equals(st.getObject())),
                "an incomplete revocation node emits no RoleRevocation row");
    }

    @Test
    void extract_detachedRole_emitsRoleDetachmentRow() throws Exception {
        IRI role = vf.createIRI("https://example.org/roles/reviewer");
        Nanopub np = creator()
                .type(GEN.DETACHED_ROLE)
                .assertion(SPACE_IRI_1, GEN.DETACHED_ROLE, role)
                .finalizeNanopub();

        List<Statement> out = SpacesExtractor.extract(np, defaultContext());
        IRI subject = forRoleDetachment(ARTIFACT_CODE, Utils.createHash(role.stringValue()));
        assertContains(out, subject, RDF.TYPE, ROLE_DETACHMENT);
        assertContains(out, subject, FOR_SPACE, SPACE_IRI_1);
        assertContains(out, subject, REVOKED_ROLE, role);
        assertContains(out, subject, VIA_NANOPUB, NP_URI);
        assertContains(out, subject, DCTERMS.CREATED, vf.createLiteral(new Date(1_700_000_000_000L)));
    }

    @Test
    void triggerTypes_includeRevocationVocab() {
        assertTrue(SpacesExtractor.TRIGGER_TYPES.contains(GEN.REVOKED_ROLE_INSTANTIATION),
                "RevokedRoleInstantiation is space-relevant");
        assertTrue(SpacesExtractor.TRIGGER_TYPES.contains(GEN.DETACHED_ROLE),
                "detachedRole is space-relevant");
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
    private static final IRI[] UNUSED_REFS = {HAS_DEFINITION, PUBKEY_HASH};

}
