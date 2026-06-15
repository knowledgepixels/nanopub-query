# Design: materialize preset-bundled roles as validated role attachments

Status: **implemented** (presets issue #302 "point 1"; handed off from the Nanodash side).
Scope: this document covers the **nanopub-query** work. The Nanodash side needs no role-loading change (see §6).

> **Implementation notes — two deviations from this design, verified against Nanodash source:**
> 1. **Activation is active-by-default, not require-explicit.** §4.2 said "log+skip if neither
>    `gen:ActivatedPresetAssignment` nor `gen:DeactivatedPresetAssignment`." Nanodash's
>    `PresetAssignment.isActive()` is `!types.contains(DeactivatedPresetAssignment)` — an assignment
>    typed only `gen:PresetAssignment` is **active**. Skipping it would silently drop valid
>    assignments, so extraction emits `npa:isActivated = false` iff explicitly deactivated, else `true`.
> 2. **The join keys on the canonical preset *kind*, with latest-declaration-per-kind resolution
>    — consistent with how Nanodash views work.** Nanodash keys views on their `dct:isVersionOf`
>    kind (`ViewDisplay.getViewKindIri()`, node-IRI fallback) and resolves each to its latest current
>    head server-side in the `get-view-displays` query; the per-view-kind latest-wins lives in
>    `AbstractResourceWithProfile.getViewDisplays()`. The preset-role materialization mirrors this:
>    - `extractPreset` emits `npa:presetKind` = the `dct:isVersionOf` kind (or the `gen:Preset` node IRI
>      as fallback), plus `npa:ofPreset` for **both** the node IRI and the kind as lookup keys (the wire
>      IRI in `gen:isAssignmentOfPreset` may be either).
>    - `presetAttachmentValidationUpdate` maps the assignment's referenced IRI → canonical kind (via any
>      declaration's `npa:ofPreset`/`npa:presetKind`), then draws roles only from the **latest live**
>      declaration of that kind (`MAX(dct:created)` among non-invalidated declarations, subject-IRI
>      tiebreak). A superseded preset version's roles never leak — closing what was previously flagged as
>      a version-supersession limitation. Equivalent to the view "most-recent current head" rule in the
>      common case; invalidation filtering drops superseded versions, and the timestamp tiebreak handles
>      transient multi-head.

## 1. Context & goal

A Nanodash **preset** (`gen:Preset`) is a reusable bundle of default views and **roles**, assigned to a
resource through a `gen:PresetAssignment`. Preset *views* already work (expanded read-time in Nanodash's
`get-view-displays` query). Preset *roles* (`gen:hasRole`) do not work at all.

Why a read-time/display-only approach is insufficient (verified): the per-tier grant validator
`AuthorityResolver.nonAdminTierUpdate` **anchors on a validated `gen:RoleAssignment` attachment** in the
space-state graph before it will validate any role *grant* (instantiation):

```sparql
# 1. Anchor: validated attachments in this space-state graph (ref-keyed).
GRAPH <state> { ?ra a gen:RoleAssignment ; gen:hasRole ?role ;
                    npa:forSpaceRef ?spaceRef ; npa:forSpace ?space . }
# 2. tier-pinned RoleDeclaration … 4. targeted instantiation lookup …
```

So unless the role is a *real, validated attachment*, a grant of it never materializes and the member
never appears in `get-space-members`. nanopub-query is currently **entirely unaware** of presets.

**Goal:** expand active, admin-authored preset assignments into validated `gen:RoleAssignment` attachments,
exactly as if `<space> gen:hasRole <role>` had been published by the assignment's publisher. After that,
preset-bundled roles are first-class: listed by `GET_SPACE_ROLES`, grantable, and grants validate — through
the **existing** read path, with no Nanodash change.

## 2. Scope

- **In:** preset assignments whose `gen:isAssignmentFor` resource is a **Space**; expansion of `gen:hasRole`.
- **Out (future):** maintained-resource / individual-agent targets (roles live on the parent space; there's
  no attachment target for them today). Extract their `gen:appliesToInstancesOf` but don't act on it.
- Views are **not** materialized server-side — they stay read-time in Nanodash. Only roles need the trust model.

## 3. The core semantic difference: latest-wins, NOT `npx:invalidates`

This is the headline risk. Preset activation state is an **identity on the `(preset, resource)` pair**: the
latest assignment nanopub by `dct:created` wins, and a later `gen:DeactivatedPresetAssignment` for the same
pair suppresses it. There is **no `npx:invalidates`** link between assignment versions.

Consequence: the validation gate, the dedup, the deactivation check, and the deactivation delete must all use
a **`MAX(dct:created)`-per-pair** computation (with a `STR(?subject)` tiebreak for equal timestamps) — they
must **not** be copy-pasted from the `npx:invalidates`-driven `attachmentValidationUpdate` /
`roleAssignmentInvalidationDelete`. (A hard retraction of the assignment nanopub itself via `npx:invalidates`
is orthogonal and may additionally be honored as a defensive filter.)

**Authorization-scoped latest-wins (anti-hijack — do NOT skip).** Nanodash resolves effective state by
considering **only assignments from agents authorized over the target, *then* letting the most recent win**
(`docs/presets.md`: "considering only assignments from agents who are authorized over the target … and
letting the most recent one win"). The authorization filter comes **first**; latest-wins runs over the
already-filtered set. Replicating this is mandatory, not optional: the candidate set for the `MAX(dct:created)`
computation — in **both** the activation gate (§4.3 step 2) and the deactivation delete (§4.4) — must be
restricted to assignments whose publisher is a **validated admin of the target ref**, using the same admin
probe as the winning-row check. A naive "latest-wins over all publishers, then admin-check the winner" is a
hijack/DoS vector:

> Admin A publishes `ActivatedPresetAssignment` at t1. Any unauthorized key publishes a
> `DeactivatedPresetAssignment` for the same `(preset, resource)` pair at t2 > t1. With unscoped latest-wins,
> A's row is shadowed by the newer row (fails the `FILTER NOT EXISTS`) and the newer row isn't `isActivated`,
> so **nothing materializes** — and §4.4 would actively *delete* the already-materialized RA. An arbitrary key
> can thus suppress any preset-derived role.

This is the same anti-hijack class as the spaceref alias work (`admins(alias) ⊆ admins(canonical)`, issue
#113): trust must gate the shadowing rows, not only the surviving one. Cost: the latest-wins subquery gains a
per-candidate admin probe (publisher resolves through AccountState `pkh→agent` and an admin `RoleInstantiation`
`inverseProperty gen:hasAdmin` for the candidate's target ref) — more complex SPARQL than `attachmentValidationUpdate`'s,
called out here so it isn't discovered at INSERT time.

## 4. Design

### 4.1 Vocabulary
- `vocabulary/GEN.java`: add `PRESET`, `PRESET_ASSIGNMENT`, `ACTIVATED_PRESET_ASSIGNMENT`,
  `DEACTIVATED_PRESET_ASSIGNMENT`, `IS_ASSIGNMENT_OF_PRESET`, `IS_ASSIGNMENT_FOR`,
  `APPLIES_TO_INSTANCES_OF` (`HAS_ROLE` already exists). Standard `VocabUtils.createIRI(NAMESPACE, …)`.
- `vocabulary/SpacesVocab.java`: add extraction types/predicates `npa:PresetAssignment`,
  `npa:PresetDeclaration`, `npa:ofPreset`, `npa:forResource`, `npa:isActivated` (xsd:boolean),
  `npa:presetRole`, `npa:appliesToInstancesOf`, and **`npa:derivedFromPreset`** — the marker placed on every
  materialized RA that came from a preset (scopes the deactivation delete in §4.4 and the read-side marking in
  §6). Add minting helpers `forPresetAssignment(artifactCode)` / `forPresetDeclaration(artifactCode)`.

### 4.2 Extraction — `SpacesExtractor.java`
- Add `GEN.PRESET` and `GEN.PRESET_ASSIGNMENT` to `TRIGGER_TYPES`. This is the single "space-relevant" set the
  loader consults to decide what to write into the spaces repo — **verify `NanopubLoader` has no second
  hardcoded copy** of the trigger set.
- Dispatch in `extract(...)` (~line 135) to two new methods, mirroring `extractHasRole` (lines 397-420):
  - `extractPresetAssignment` → one `npa:PresetAssignment` row: `npa:ofPreset` (from `gen:isAssignmentOfPreset`),
    `npa:forResource` (from `gen:isAssignmentFor`), `npa:isActivated "true"/"false"^^xsd:boolean`
    (true if the assertion types it `gen:ActivatedPresetAssignment`, false if `gen:DeactivatedPresetAssignment`;
    log+skip if neither/both), `npa:viaNanopub`, plus `addProvenance(...)` which already emits
    `npa:pubkeyHash` + **`dct:created`** (the latest-wins key — essential).
  - `extractPreset` → one `npa:PresetDeclaration` row: `npa:ofPreset <preset>` (join key), `npa:presetRole <roleN>`
    for each `gen:hasRole`, `npa:appliesToInstancesOf <gen:Space|…>`, provenance.
- **Why extract preset roles into a summary row** rather than join the raw assertion graph at validation time:
  every existing tier joins only against the `npa:spacesGraph` summary; introducing a raw
  `GRAPH ?g { ?preset gen:hasRole ?role }` scan keyed via `np:hasAssertion` would be the only raw-assertion join
  in the resolver and a documented RDF4J-evaluator hazard. Keep it add-only and summary-based; the validation
  SPARQL joins assignment → declaration by `npa:ofPreset`.

### 4.3 Validation tier — `AuthorityResolver.java`
New `presetAttachmentValidationUpdate(graph, lastProcessed)`, modeled on `attachmentValidationUpdate`
(lines 875-949). It INSERTs a `gen:RoleAssignment` per `(ref, role)` into the state graph. WHERE clause:

1. **Anchor** on `npa:PresetAssignment` rows in `npa:spacesGraph`.
2. **Latest-wins (authorization-scoped — see §3):** `FILTER NOT EXISTS` a newer same-`(preset,resource)` row
   by `dct:created` (tiebreak `STR(?pa2) > STR(?pa)`); require `npa:isActivated = true`. **The `NOT EXISTS`
   candidate must itself be from a validated admin of the target ref** — i.e. the shadowing-row subgraph repeats
   the publisher-admin probe (step 6) for the candidate, so an unauthorized key's newer assignment cannot shadow
   an admin's activation. Without this, the gate is a hijack/DoS vector (§3).
3. **Roles:** join `npa:PresetDeclaration` by `npa:ofPreset` for each `npa:presetRole ?role`, and require
   `npa:appliesToInstancesOf gen:Space`.
4. **Load-number filter** on the assignment nanopub (delta window).
5. **Resource → ref:** `?targetRef npa:spaceIri ?resource` (no Space ref ⇒ nothing inserted ⇒ correct no-op for
   non-space resources; fan-out to N refs if the publisher admins several refs of the same IRI — intended,
   per design-spaceref-isolation.md).
6. **Publisher = validated admin of `?targetRef`** (AccountState `pkh→agent` + admin `RoleInstantiation`
   `inverseProperty gen:hasAdmin`), same probe as `attachmentValidationUpdate`.

INSERT shape (note the dual-emit + the marker):
```sparql
INSERT { GRAPH <STATE> {
  ?ra2 a gen:RoleAssignment ;
       npa:forSpaceRef ?targetRef ;
       npa:forSpace    ?space ;            # TRANSITIONAL-DUAL-EMIT (Phase 4: remove)
       gen:hasRole     ?role ;
       npa:viaNanopub  ?assignNp ;
       npa:derivedFromPreset ?assignNp .   # scoping marker (deletes §4.4, read-side §6)
} }
```
- **Subject minting must include `?role`** (one assignment → N roles, unlike `attachmentValidationUpdate` where
  one nanopub = one role): `BIND(IRI(CONCAT(STR(?pa),"__",ENCODE_FOR_URI(STR(?targetRef)),"__",ENCODE_FOR_URI(STR(?role)))) AS ?ra2)`.
  Dedup `FILTER NOT EXISTS` an existing RA for `(?targetRef, ?role)`.
- **Wiring:** add the call in `runAllTierLoops` **immediately before** the existing `attachment` tier
  (~line 534) so the downstream `nonAdminTierUpdate` maintainer/member/observer loops (lines 536-559) pick up
  the new attachments in the same pass. Also add to `runDownstreamWithoutLoadFilter` (~line 426, late-arrival
  sweep). Add a `presetAttachment` counter to `TierInsertedTriples` + the structural-adds/log wiring.
- **Evaluator quirks** (documented in AuthorityResolver / sparql-quirks.md): keep heavy `FILTER NOT EXISTS`
  outside wrapping `GRAPH` blocks (defer until `?created`/`?targetRef` bound); the new tier runs through
  `runTierLoop`, which already uses the bare `prepareUpdate().execute()` cross-graph path.

### 4.4 Incremental maintenance (deactivation / latest-wins removal)
A newer `DeactivatedPresetAssignment` (or any newer same-pair assignment) must remove previously-materialized
preset-derived RAs. This is **not** `npx:invalidates`, so `roleAssignmentInvalidationDelete` does not cover it.

- `newPresetAssignmentsArrived(lastProcessed)` — cheap ASK (mirror `newRoleDeclarationsArrived`, 454-468): true
  if any `npa:PresetAssignment`/`npa:PresetDeclaration` `viaNanopub` has load number `> lastProcessed`. OR it
  into `structuralAdds` (~299-303) so the tier re-runs when an assignment/preset lands.
- `presetDeactivationCheckWhere` + `presetDeactivationDelete` (mirror `roleAssignmentInvalidationCheckWhere`/
  `Delete`, 1533-1561) but **`dct:created`-driven and scoped by `npa:derivedFromPreset`**: delete a
  materialized preset-derived RA when a newer **admin-authored** same-pair `npa:PresetAssignment` exists (load
  number `> lastProcessed`). **The "newer same-pair assignment" must be from a validated admin of the target
  ref** (same publisher-admin probe as §4.3 step 6) — otherwise an unauthorized key's newer deactivation would
  delete an admin-materialized RA (the §3 hijack vector, in delete form). Add to `applyInvalidations` (~363).
  The re-INSERT in §4.3 then re-materializes only the currently-active pairs in the same cycle — net effect:
  deactivation removes, re-activation re-adds.
- Treat such a delete as **structural** → `setNeedsFullRebuild()` (like RA invalidation, 359-363), so
  downstream non-admin RIs derived through a removed attachment are bounded by the periodic full rebuild rather
  than a surgical cascade. The marker keeps deletes from ever touching directly-published `gen:hasRole`
  attachments.

## 5. Migration & deploy

- **Full re-ingest is the deploy path** (confirmed acceptable). Presets/assignments already on the network were
  never "space-relevant", so they are **not** in the spaces repo; only a full re-ingest re-evaluates
  space-relevance with the new `TRIGGER_TYPES` and extracts them. A state-only `FORCE_RESYNC` would **not**
  surface them — do a full re-ingest, not a resync.
- **Version bump:** `1.15.2-SNAPSHOT` → `1.16.0` on release (additive to the spaces pipeline).
- **Mixed-fleet:** the feature only **adds** RA rows in the already-dual-emitted attachment shape, so there is
  no new IRI-vs-ref wire hazard of the kind in `report-2026-06-12-mixed-fleet-spaceref-breakage.md`. Keep the
  `npa:forSpace` dual-emit and its `TRANSITIONAL-DUAL-EMIT (Phase 4: remove)` marker.

## 6. Read side (consumers)

- **Nanodash needs no role-loading change.** Preset-derived RAs are ordinary validated `gen:RoleAssignment`
  rows, so `GET_SPACE_ROLES` (the app-internal query Nanodash's `Space.loadRoles()` consumes) returns them and
  the role's tier/properties resolve through its existing `RoleDeclaration` join. The role then shows in
  "🎭 Assigned roles", is grantable, and grants validate.
- **Mark preset-sourced roles in the display listing** (requested): the published `list-space-roles` query
  (backing the "🎭 Assigned roles" view) should add a `?preset` / `?preset_label` column populated when the
  attachment carries `npa:derivedFromPreset` (and left empty for directly-published attachments) — mirroring
  how `list-view-displays` marks preset-supplied view rows. This is a **published-query nanopub change**
  (Nanodash/queries side), not nanopub-query Java; it depends only on the new `npa:derivedFromPreset` triple
  being present in the state graph. The query can surface the preset IRI from
  `?ra npa:derivedFromPreset ?assignNp` → join `npa:PresetAssignment npa:viaNanopub ?assignNp ; npa:ofPreset ?preset`.

## 7. Tests

- `SpacesExtractorTest`: `extract_presetAssignment_active/deactivated…`, `extract_preset_emitsPresetDeclaration…`,
  and a `TRIGGER_TYPES`-membership test — mirror `extract_hasRoleAttachment_emitsRoleAssignment` (line 222) and
  the trigger-type test (line 751). Inline `NanopubCreator` fixtures.
- `AuthorityResolverTest`: SPARQL-**string-structure** assertions (the in-memory UPDATE path is unusable per the
  class javadoc) — mirror `attachmentValidationUpdate_requiresAdminPublisher` (line 122): assert the new
  template contains `gen:RoleAssignment`, `npa:derivedFromPreset`, `npa:presetRole`,
  `appliesToInstancesOf gen:Space`, `inverseProperty gen:hasAdmin`, the load filter, and the `dct:created`
  latest-wins subquery; assert it does **not** gate activation on `npx:invalidates`. Add
  `presetDeactivationDelete_scopedToDerivedFromPreset`. Add
  `presetAttachmentValidationUpdate_latestWinsCandidateIsAdminScoped` — assert the `FILTER NOT EXISTS`
  shadowing-row subgraph repeats the publisher-admin probe (`inverseProperty gen:hasAdmin` against the
  candidate's publisher), so the latest-wins comparison ranges over admin-authored rows only (anti-hijack, §3).

## 8. Risk register

1. **Latest-wins ≠ invalidates** — all of {validation gate, dedup, deactivation ASK, deactivation DELETE} use
   `MAX(dct:created)`-per-pair, never the invalidation filter. Easiest mistake.
1b. **Unscoped latest-wins = hijack/DoS** (§3) — the `MAX(dct:created)` candidate set, in both the activation
   gate and the deactivation delete, must be restricted to **admin-authored** assignments. "Latest-wins over
   all publishers, then admin-check the winner" lets any key suppress/delete a preset-derived role with a newer
   `DeactivatedPresetAssignment`. Authorization filter first, then latest-wins — as Nanodash does. Mirrors the
   #113 `admins(alias) ⊆ admins(canonical)` anti-hijack rule.
2. **Subject minting must include `?role`** — omitting it collides all roles of one assignment.
3. **`dct:created` ties** — the `STR()` tiebreak makes latest-wins deterministic.
4. **RDF4J quirks** — `FILTER NOT EXISTS` outside `GRAPH` blocks; latest-wins subquery anchored on bound
   `?preset`/`?resource`.
5. **needsFullRebuild cascade** — removing a preset RA bounds staleness of downstream non-admin RIs via the
   structural-rebuild flag rather than a surgical cascade.
6. **Loader gate single-source** — confirm `SpacesExtractor.TRIGGER_TYPES` is the only "space-relevant" set.

## 9. Critical files

- `src/main/java/com/knowledgepixels/query/AuthorityResolver.java` — new `presetAttachmentValidationUpdate`,
  `presetDeactivationCheckWhere`/`Delete`, `newPresetAssignmentsArrived`; wiring in `runAllTierLoops` (~534),
  `runDownstreamWithoutLoadFilter` (~426), `applyInvalidations` (~363), `TierInsertedTriples`/structuralAdds.
- `src/main/java/com/knowledgepixels/query/SpacesExtractor.java` — `TRIGGER_TYPES`, `extractPreset`,
  `extractPresetAssignment`, dispatch (~135).
- `src/main/java/com/knowledgepixels/query/vocabulary/GEN.java`, `vocabulary/SpacesVocab.java`.
- `src/test/java/com/knowledgepixels/query/{SpacesExtractorTest,AuthorityResolverTest}.java`.

## 10. End-to-end verification (after a full re-ingest on a test instance)

1. As a space admin, publish a preset bundling a Member/Observer role + an active `PresetAssignment` for a test
   space (Nanodash templates `RAjdBPJa…` / `RA5shNOP…`).
2. After a load cycle: in the `spaces` state, the role appears as a validated `gen:RoleAssignment`
   (`npa:derivedFromPreset` set) for the space ref; `GET_SPACE_ROLES` returns it; the "🎭 Assigned roles" list
   shows it (marked from-preset once §6 query lands).
3. Grant that role to an agent → the grant **validates** (member appears in `get-space-members`). This is the
   whole point.
4. Publish a `DeactivatedPresetAssignment` for the same pair → after a cycle, the RA (and grants derived
   through it) are gone; re-activating re-adds them.
5. Negative: a non-admin publishing the same assignment yields **no** attachment.
6. Negative (anti-hijack, §3): with an admin's active assignment materialized, a **non-admin** publishes a
   *newer* `DeactivatedPresetAssignment` for the same `(preset, resource)` pair → after a cycle the admin's RA
   is **still present** (the unauthorized newer row neither shadows the activation gate nor triggers the
   deactivation delete). Conversely, an **admin's** newer deactivation does remove it (step 4).

## 11. Decisions already made

- Deploy via **full re-ingest** (not FORCE_RESYNC). (§5)
- **Also** mark preset-sourced roles in the `list-space-roles` display query. (§6)
- Server-side materialization chosen over read-time/display-only because grants require a validated attachment
  (§1).
- **Latest-wins is authorization-scoped**: the `MAX(dct:created)` candidate set (activation gate and
  deactivation delete) is restricted to admin-authored assignments, matching Nanodash's "authorized agents
  only, then latest-wins" and the #113 anti-hijack rule. (§3)
