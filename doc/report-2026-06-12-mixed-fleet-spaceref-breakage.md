# Report: 1.15.0 space-ref keying breaks IRI-keyed consumers on a mixed-version fleet

**Date:** 2026-06-12
**Status:** RESOLVED by Phase 1.5 (option 2, generalized) — see "Resolution" below
**Affects:** Nanodash against the public query fleet; any consumer of the `spaces` repo's validated state

## Symptom

On a fresh Nanodash start (local dev, current `feat/magic-query-params`), the home page
(`/`) intermittently renders "(no views have been added yet)" instead of the home
resource's views, and space data appears not to load. Reproduced in-process: five
identical `QueryAccess.get` calls for
`get-view-displays?resource=https://w3id.org/spaces/knowledgepixels/nanodash/r/home`
returned 0, 5, 0, 5, 5 rows.

## Diagnosis chain

1. The nanopub-java client (`QueryCall`, hardcoded list) races two of three instances —
   `query.knowledgepixels.com`, `query.petapico.org`, `query.nanodash.net` — and accepts
   the first HTTP-2xx response.
2. `query.nanodash.net` **deterministically returns 200 with zero rows** for the above
   query; the other two instances return the 5 home views. When nanodash.net wins the
   race, Nanodash caches a valid-looking empty result and marks the resource initialized
   with no views. Nothing fails loudly.
3. The fleet is version-skewed: knowledgepixels and petapico run **1.14.4**;
   nanodash.net runs **1.15.0** (all three report READY with the same 83,040 nanopubs).
4. On nanodash.net, the same SPARQL run ad-hoc against `/repo/...` works (its `service`
   IRIs resolve via w3id to the public endpoint); only the grlc `/api` route — which
   rewrites `service` calls to the instance's **own** in-cluster repos — returns empty.
   So the divergence is in that instance's own `spaces` state.
5. Bisection of the query via `_nanopub_trig` on nanodash.net isolated the failure to the
   admin/maintainer authority gate, specifically the maintained-resource hop:
   `?resource npa:isMaintainedBy? ?space . ?ri ... npa:forSpace ?space .`
6. Live state on nanodash.net (1.15.0):
   `<…/nanodash/r/home> npa:isMaintainedBy <http://purl.org/nanopub/admin/space/RAjQAT9…_7918…>`
   — the object is a **space ref**, not the space IRI. The IRI-keyed join finds nothing →
   gate yields no authorized pubkeys → zero rows.

## Root cause

PR #119 (per-space-ref authority isolation, Phases 0+1; released in **1.15.0**) re-keys
the validated `spaces` state to space refs, per `doc/design-spaceref-isolation.md`:

- `npa:isMaintainedBy` edges become resource→**ref**; sub-space edges ref-to-ref;
  `npa:sameAsSpace` ref-valued on the canonical side.
- Authority rows join on `npa:forSpaceRef`, with per-ref fan-out (state-row counts
  differ accordingly: e.g. 709 vs 613 RoleInstantiations on the two versions), plus the
  intended "members fix" (downward grants validate for the first time).

The change itself is correct and fixes a real privilege-merge bug. The breakage comes
from **rollout sequencing**: the design's "Read contract" requires co-released,
ref-explicit Nanodash queries, but all currently published spaces queries (the internal
`get-view-displays` plus the About-page listings: list-view-displays,
list-preset-assignments, list-space-roles, list-sub-spaces, list-maintained-resources,
members/observers, …) are still IRI-keyed. A 1.15.0 instance entered the public rotation
ahead of that co-release, and the client races mixed-version instances trusting any 2xx.

Spaces themselves still pass the gate on 1.15.0 (the `isMaintainedBy?` zero-hop binds the
IRI to itself), which made the breakage look flaky and resource-specific: queries about
`…/spaces/preset-test` worked on nanodash.net while `…/nanodash/r/home` (a maintained
resource) returned empty.

## Options

1. **Immediate mitigation:** take `query.nanodash.net` out of the public rotation (or
   roll it back to 1.14.4) until the read side is migrated. The mixed-version fleet is
   the active hazard — every IRI-keyed spaces query silently flips between full and
   empty results depending on which instance wins the race.
2. **Bridge queries (if the fleet must stay mixed):** make the gates dual-model, e.g.
   `?r npa:isMaintainedBy ?x . optional { ?x npa:spaceIri ?iri } bind(coalesce(?iri, ?x) as ?space)`
   — resolves both ref-valued (1.15.0) and IRI-valued (1.14.4) edges. Mechanical
   supersedes of the affected queries; reversible once the fleet converges.
3. **The planned path:** the read-contract co-release — ref-explicit queries, Nanodash
   carrying the selected ref into follow-up calls, plus the stray-ref cleanup already
   scoped in the design doc (6 spaces / 10 stray refs, all benign same-owner duplicates).
4. **Client robustness (nanopub-java):** `QueryCall` treats any 2xx from any instance as
   authoritative, giving zero protection against version-skewed or state-divergent
   instances. A consistency/health check, version pinning, or honoring a configured
   instance list would have surfaced this loudly instead of as a flaky empty home page.

## Resolution (Phase 1.5)

Chosen fix: option 2, generalized into the materializer instead of into the published
queries — **transitional IRI-valued dual-emit on the structural-edge tiers**. The root
cause was that the structural edges (`isMaintainedBy`/`hasMaintainedResource`,
`isSubSpaceOf`/`hasSubSpace`, `sameAsSpace`) were re-keyed to be ref-valued *only*, while
the row tiers (admin/attachment/member) had already kept a transitional `npa:forSpace`
dual-emit. Phase 1.5 closes that asymmetry: every structural admit pass now emits the
1.14.4 IRI-valued edge *alongside* the ref-valued one. Validation still joins on the ref
internally, so the privilege-isolation fix is untouched; the IRI edge is read-only sugar
for pre-ref consumers.

- `AuthorityResolver.subSpaceAdmitUpdate`, `maintainedResourceAdmitUpdate`,
  `aliasAdmitUpdate`, `subSpacePrefixFallbackUpdate` — each gains the IRI-valued edge in
  its INSERT block, tagged `TRANSITIONAL-DUAL-EMIT (Phase 1.5; remove in Phase 4)`.
- The three pre-existing row-tier `forSpace` dual-emits were retro-tagged with the same
  marker, so the eventual Phase 4 cleanup is one `grep TRANSITIONAL-DUAL-EMIT`.
- No invalidation change: structural convenience edges are already left sticky and reaped
  by the periodic full rebuild; the IRI-valued ones inherit that policy.
- Why generalize rather than supersede published queries (literal option 2): it fixes
  *every* IRI-keyed consumer, including ones not enumerated, and decouples redeploy from
  the Nanodash co-release. Option 2's `coalesce(?iri, ?x)` shape remains the right pattern
  for the Phase 2 ref-explicit rewrites.

Tests: `AuthorityResolverStructuralDualEmitTest` (6) asserts each pass emits both shapes,
that the exact legacy `get-view-displays` maintained gate binds the admin again, and that
the IRI-valued alias edge stays inert for the internal ref-keyed attachment tier. Full
suite 243 green. Safe to redeploy onto the mixed-version fleet; reverted in Phase 4 once
Phase 2/3 land. The class is expected to be deleted together with the dual-emit blocks.

## Evidence quick-reference

- `query.nanodash.net/api/RAy49uUd…/get-view-displays?resource=…/r/home` → 200, header
  only (deterministic, 4/4); same call on knowledgepixels/petapico → 5 rows.
- Same SPARQL ad-hoc via POST on `query.nanodash.net/repo/type/11daee46…` → 5 rows
  (services resolve via w3id to the public endpoint, masking the local divergence).
- Gate-less variant of the query via `_nanopub_trig` on nanodash.net → 5 rows;
  gate-only variant → 0 rows.
- `select ?o { … <r/home> npa:isMaintainedBy ?o }` on nanodash.net's current state →
  `http://purl.org/nanopub/admin/space/RAjQAT9PGfqv-YlzUMYAK79xx_LH48BZuOYLJYGdJlLbI_7918859685a77dffb48a5a44ed2e923de10ae412b326830de6701738ff8a2d5d`;
  on knowledgepixels → `https://w3id.org/spaces/knowledgepixels/nanodash`.
