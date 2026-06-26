package com.knowledgepixels.query;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.regex.Pattern;

import org.eclipse.rdf4j.model.IRI;
import org.junit.jupiter.api.Test;

import com.knowledgepixels.query.vocabulary.GEN;
import com.knowledgepixels.query.vocabulary.SpacesVocab;

/**
 * Pure-logic (SPARQL string-contract) tests for role revocation / detachment (issue #129).
 * Like {@link AuthorityResolverTest}, these assert the structure of the generated SPARQL
 * templates — the full-cycle behaviour runs against a live deployment (see the test-class
 * javadoc on {@link AuthorityResolverTest} for why the in-memory store can't exercise
 * pattern-delete / cross-graph UPDATE here).
 */
class AuthorityResolverRevocationTest {

    private static final IRI G = SpacesVocab.forSpaceState(
            "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789", 42L);

    private static final String MAINT = Pattern.quote(GEN.MAINTAINER_ROLE.stringValue());
    private static final String MEMB = Pattern.quote(GEN.MEMBER_ROLE.stringValue());

    /** Revoker-tier arm: {@code ?revRI ... npa:forAgent ?revAgent ; npa:hasRoleType <tier>}. */
    private static Pattern revokerTierArm(String tierIriQuoted) {
        return Pattern.compile("npa:forAgent\\s+\\?revAgent\\s*;\\s*npa:hasRoleType\\s+<" + tierIriQuoted + ">");
    }

    private static final Pattern ADMIN_REVOKER_ARM = Pattern.compile(
            "npa:inverseProperty\\s+gen:hasAdmin\\s*;\\s*npa:forAgent\\s+\\?revAgent");
    private static final Pattern SELF_REVOKER_ARM = Pattern.compile(
            "npa:pubkey\\s+\\?revPkh\\s*;\\s*npa:agent\\s+\\?agent");

    // ---------------- non-admin instantiation revocation ----------------

    @Test
    void nonAdminTierUpdate_memberContainsRevocationSuppression() {
        String sparql = AuthorityResolver.nonAdminTierUpdate(
                G, 0, GEN.MEMBER_ROLE, AuthorityResolver.PUBLISHER_IS_ADMIN);
        assertTrue(sparql.contains("npa:RoleRevocation"), "suppresses on a RoleRevocation negative");
        assertTrue(sparql.contains("npa:revokedRole ?role"), "keyed on the revoked role");
        assertTrue(sparql.contains("?revCreated > ?candCreated"),
                "latest-wins on dct:created (revocation vs grant)");
        assertTrue(sparql.contains("COALESCE(?revCreatedRaw")
                && sparql.contains("COALESCE(?candCreatedRaw"),
                "missing dct:created treated as epoch on both sides (decision #3)");
        assertTrue(sparql.contains("1970-01-01T00:00:00.000Z"), "epoch fallback literal present");
        assertTrue(ADMIN_REVOKER_ARM.matcher(sparql).find(), "admin may revoke a member");
        assertTrue(SELF_REVOKER_ARM.matcher(sparql).find(), "self-leave arm present");
    }

    @Test
    void nonAdminTierUpdate_revocationIsNotInvalidatable() {
        // Decision #6: a revocation is NOT wrapped in invalidationFilter — the only un-revoke
        // path is a newer positive re-assignment. invalidationFilter would mint ?_inv_rev.
        String sparql = AuthorityResolver.nonAdminTierUpdate(
                G, 0, GEN.OBSERVER_ROLE, AuthorityResolver.PUBLISHER_IS_ADMIN);
        assertFalse(sparql.contains("?_inv_rev"),
                "the revocation negative is not itself invalidatable");
    }

    @Test
    void nonAdminTierUpdate_authorityArmsFollowTierMatrix() {
        // Matrix (issue #129): a revoker must hold a tier strictly higher than the target. The
        // arms are selected at COMPILE TIME per tier (revocationAuthorityArmsForTier) — no
        // runtime ?tier FILTER, which would be invisible inside a UNION branch.
        String maintainer = AuthorityResolver.nonAdminTierUpdate(
                G, 0, GEN.MAINTAINER_ROLE, AuthorityResolver.PUBLISHER_IS_ADMIN);
        String member = AuthorityResolver.nonAdminTierUpdate(
                G, 0, GEN.MEMBER_ROLE, AuthorityResolver.PUBLISHER_IS_ADMIN);
        String observer = AuthorityResolver.nonAdminTierUpdate(
                G, 0, GEN.OBSERVER_ROLE, AuthorityResolver.PUBLISHER_IS_ADMIN);

        // maintainer revoked by admins only (+self): no maintainer/member revoker arm.
        assertFalse(revokerTierArm(MAINT).matcher(maintainer).find(),
                "maintainer is not revocable by a maintainer");
        assertFalse(revokerTierArm(MEMB).matcher(maintainer).find(),
                "maintainer is not revocable by a member");
        // member revoked by admins + maintainers (+self): maintainer arm, no member arm.
        assertTrue(revokerTierArm(MAINT).matcher(member).find(),
                "member is revocable by a maintainer");
        assertFalse(revokerTierArm(MEMB).matcher(member).find(),
                "member is not revocable by a peer member");
        // observer revoked by admins + maintainers + members (+self).
        assertTrue(revokerTierArm(MAINT).matcher(observer).find(),
                "observer is revocable by a maintainer");
        assertTrue(revokerTierArm(MEMB).matcher(observer).find(),
                "observer is revocable by a member");
        // admin + self arms present in every non-admin tier.
        for (String s : new String[]{maintainer, member, observer}) {
            assertTrue(ADMIN_REVOKER_ARM.matcher(s).find(), "admin-revoker arm present");
            assertTrue(SELF_REVOKER_ARM.matcher(s).find(), "self-revoke arm present");
        }
    }

    @Test
    void nonAdminTierUpdate_suppressionAuthorityIndependentOfPublisherConstraint() {
        // Multi-arm hazard: the suppression authority is derived from tierClass, NOT from the
        // positive arm's publisherConstraint — otherwise the member(admin-pub) arm would never
        // test against a maintainer-authored revocation. So the maintainer-revoker arm must be
        // present in the member tier regardless of which publisherConstraint is passed.
        String adminPub = AuthorityResolver.nonAdminTierUpdate(
                G, 0, GEN.MEMBER_ROLE, AuthorityResolver.PUBLISHER_IS_ADMIN);
        String selfPub = AuthorityResolver.nonAdminTierUpdate(
                G, 0, GEN.MEMBER_ROLE, AuthorityResolver.PUBLISHER_IS_SELF);
        assertTrue(revokerTierArm(MAINT).matcher(adminPub).find()
                && revokerTierArm(MAINT).matcher(selfPub).find(),
                "the maintainer-revoker arm appears regardless of publisherConstraint");
    }

    @Test
    void roleRevocationDelete_structureLatestWinsAndPerTierAuthority() {
        // Member tier: scoped to MemberRole rows; authority = admin + maintainer + self.
        String sparql = AuthorityResolver.roleRevocationDelete(G, 7, GEN.MEMBER_ROLE);
        assertTrue(sparql.contains("DELETE"), "DELETE clause");
        assertTrue(sparql.contains("npa:RoleRevocation"), "matches a RoleRevocation negative");
        assertTrue(sparql.contains("npa:hasRoleType <" + GEN.MEMBER_ROLE + ">"),
                "scoped to rows of the target tier (excludes admin rows, which carry AdminRole)");
        assertTrue(sparql.contains("?lnRev > 7"), "delta filter on the revocation's load number");
        assertTrue(sparql.contains("?revCreated > ?candCreated"), "latest-wins on dct:created");
        assertFalse(sparql.contains("?_inv_rev"), "not an npx:invalidates check");
        assertTrue(revokerTierArm(MAINT).matcher(sparql).find(),
                "member rows are revocable by a maintainer");
        assertFalse(revokerTierArm(MEMB).matcher(sparql).find(),
                "member rows are not revocable by a peer member");
        // Observer tier: also revocable by a member.
        String obs = AuthorityResolver.roleRevocationDelete(G, 7, GEN.OBSERVER_ROLE);
        assertTrue(revokerTierArm(MEMB).matcher(obs).find(),
                "observer rows are revocable by a member");
    }

    // ---------------- admin instantiation revocation ----------------

    @Test
    void adminTierUpdate_containsAdminRevocationWithRootExemption() {
        String sparql = AuthorityResolver.adminTierUpdate(G, 0);
        assertTrue(sparql.contains("npa:RoleRevocation"), "admin tier suppresses on a revocation");
        assertTrue(Pattern.compile("npa:revokedRole\\s+gen:AdminRole").matcher(sparql).find(),
                "admin revocation keys on gen:AdminRole (decision #1)");
        assertTrue(Pattern.compile("FILTER NOT EXISTS \\{[\\s\\S]*?npa:hasRootAdmin\\s+\\?agent")
                        .matcher(sparql).find(),
                "root admins are exempt — a revocation against a hasRootAdmin agent is inert");
        assertTrue(ADMIN_REVOKER_ARM.matcher(sparql).find(), "admins revoke admins");
        assertTrue(SELF_REVOKER_ARM.matcher(sparql).find(), "non-root admins may self-leave");
        // Admin revocation does NOT enable a maintainer/member to revoke an admin.
        assertFalse(revokerTierArm(MAINT).matcher(sparql).find(),
                "an admin is not revocable by a maintainer");
    }

    @Test
    void adminRevocationDelete_rootExemptStructuralAndDeltaScoped() {
        String sparql = AuthorityResolver.adminRevocationDelete(G, 9);
        assertTrue(sparql.contains("DELETE"), "DELETE clause");
        assertTrue(Pattern.compile("npa:revokedRole\\s+gen:AdminRole").matcher(sparql).find(),
                "keyed on gen:AdminRole");
        assertTrue(sparql.contains("npa:hasRootAdmin ?agent"), "root admins exempt in the delete too");
        assertTrue(sparql.contains("?lnRev > 9"), "delta filter on the revocation's load number");
        assertTrue(sparql.contains("npa:inverseProperty gen:hasAdmin"),
                "matches admin rows (and the admin-authority arm)");
    }

    // ---------------- detachment ----------------

    @Test
    void attachmentValidationUpdate_containsDetachmentSuppression() {
        String sparql = AuthorityResolver.attachmentValidationUpdate(G, 0);
        assertTrue(sparql.contains("npa:RoleDetachment"), "direct attachments honor a detachment");
        assertTrue(sparql.contains("?detCreated > ?attCreated"),
                "non-sticky latest-wins: a newer attachment re-attaches");
        // Detachment is admin-authored (matching who may attach).
        assertTrue(Pattern.compile("\\?detAdminRI[\\s\\S]*?npa:inverseProperty\\s+gen:hasAdmin")
                        .matcher(sparql).find(),
                "detachment must be authored by an admin of the ref");
    }

    @Test
    void presetAttachmentValidationUpdate_containsDetachmentSuppression() {
        String sparql = AuthorityResolver.presetAttachmentValidationUpdate(G, 0);
        assertTrue(sparql.contains("npa:RoleDetachment"),
                "preset-derived attachments honor a detachment too (per-role opt-out)");
        assertTrue(sparql.contains("?detCreated > ?created"),
                "compared against the preset assignment's dct:created (non-sticky)");
    }

    @Test
    void roleDetachmentDelete_coversDirectAndPresetDerivedAndIsDeltaScoped() {
        String sparql = AuthorityResolver.roleDetachmentDelete(G, 3);
        assertTrue(sparql.contains("DELETE"), "DELETE clause");
        assertTrue(sparql.contains("npa:RoleDetachment"), "matches a detachment negative");
        // Not scoped to derivedFromPreset — removes BOTH direct and preset-derived attachments.
        assertFalse(sparql.contains("npa:derivedFromPreset"),
                "detachment removes direct and preset-derived attachments alike");
        assertTrue(sparql.contains("?lnDet > 3"), "delta filter on the detachment's load number");
        assertTrue(sparql.contains("?detCreated > ?attCreated"), "latest-wins on dct:created");
    }
}
