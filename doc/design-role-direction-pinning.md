# Design: Pinning Role-Predicate Direction in Role-Assigning Nanopubs

## Context

A role assignment is published as a triple like `<space> gen:hasHelper <bot>`. To
materialize it, the extractor must know the triple's **direction** — which side is the
space and which is the agent:

- **INVERSE** (space-centric): `<space> <pred> <agent>` → `npa:inverseProperty`.
- **REGULAR** (agent-centric): `<agent> <pred> <space>` → `npa:regularProperty`.

Direction is a property of the *role*, declared once in the role definition
(`gen:SpaceMemberRole`) via `gen:hasInverseProperty` / `gen:hasRegularProperty` (see
`design-space-repositories.md`). The problem is that `SpacesExtractor` runs
**per nanopub** and cannot see the role declaration (a different nanopub) while
processing an instantiation. It therefore has only two ways to resolve direction today:

1. A hardcoded predicate→direction map, `BackcompatRolePredicates.DIRECTIONS`
   (`hasAdmin`, `hasObserver`, `hasGuest`, `hasHost`, `hasTeamMember`, `hasProjectLead`,
   `plansToAttend`, plus the Wikidata / 3pff sets). Predicates in this map are resolved
   **at extraction time** into the normalized shape (`npa:forSpace` / `npa:forAgent` /
   `npa:regular|inverseProperty`) written into `npa:spacesGraph`.

2. For a nanopub explicitly typed `gen:RoleInstantiation` using a predicate *not* in the
   map, the extractor emits a **neutral binding** — `npa:rolePredicate` +
   `npa:bindingSubject` + `npa:bindingObject` — deferring direction to the materializer
   (`AuthorityResolver.nonAdminTierUpdate`, UNION arms 3–4), which joins the neutral row
   against the role declaration and writes the normalized shape into the **per-ref state
   graph** only.

### The bug this addresses (issue #136)

`gen:hasHelper` and `gen:hasMaintainer` are correctly-modeled custom roles (they carry
`gen:RoleInstantiation` and a proper role declaration), so they are *not* in the
hardcoded map and go down path 2. The materializer resolves them correctly — but only in
the **state graph**. The deployed `get-space-members` query lists candidate members from
the **raw `npa:spacesGraph`** (by design, so it can show unvalidated members with a
`?validated` flag computed via `EXISTS` over the state graph). In `npa:spacesGraph` these
instantiations are still neutral (`npa:rolePredicate …`, no `npa:forSpace`/`npa:forAgent`),
so `get-space-members` — which keys on `npa:forSpace` — returns **0 rows**. Every other
role predicate is normalized in `spacesGraph` (e.g. `hasObserver` has dozens of normalized
rows) precisely because it is in the hardcoded map; `hasHelper`/`hasMaintainer` are the
only two that are not.

Confirmed live on `query.knowledgepixels.com/repo/spaces` for space
`https://w3id.org/spaces/biochementity` (root `…RAfldXyar…`): the state graph carries the
fully normalized `hasHelper` row, but `spacesGraph` carries only the neutral binding, and
the ref-scoped `get-space-members` returns empty.

### Why not just fix the read side

Two read-side-only options were considered and rejected as the *general* answer:

- **Source `get-space-members` from the state graph.** Drops unvalidated members, which
  contradicts its current design (unvalidated-with-flag is a feature).
- **Resolve the neutral form inside the `get-space-members` query.** Works, but pushes a
  role-declaration join into a published query that lives in the Nanodash repo, and every
  *other* consumer of these rows would need the same join.

The right long-term fix keeps `spacesGraph` self-sufficient: an instantiation should be
normalizable **from the nanopub alone**, without the extractor needing the separate role
declaration. That requires the direction to be **pinned in the assigning nanopub**.

## Decision

Two parts, deployable independently.

### Part A — immediate fix: promote the known predicates (closes #136)

Add `gen:hasHelper` and `gen:hasMaintainer` (both **INVERSE**) to the known
predicate→direction map, and **rename / re-document** that map. It is currently
`BackcompatRolePredicates`, documented as *temporary, to be dropped once deployments move
to `gen:RoleInstantiation`*. That framing is now wrong: the map is the permanent home for
**known-direction role predicates**, not just legacy ones. Suggested rename:
`KnownRolePredicateDirections` (keep the Wikidata/3pff/legacy entries; they are simply the
known set). Re-document so a future cleanup does not delete it and reintroduce this bug.

Effect: `hasHelper`/`hasMaintainer` are resolved at extraction time into `spacesGraph`
like every other known predicate, on both the `gen:RoleInstantiation` path
(`SpacesExtractor.extractRoleInstantiation`) and the inline `gen:Space` path
(`emitInlineRoleInstantiations`). Existing data is corrected by a full
re-materialization / re-ingest.

This is a pragmatic backstop for *known* predicates. It does **not** scale to genuinely
new custom predicates (each would need a code change + redeploy). Part B removes that
limitation.

### Part B — going forward: pin direction in the assigning nanopub

Let a role-assigning nanopub declare the direction of each role predicate it uses, so the
extractor resolves it per-nanopub with no hardcoding and no separate role declaration.

#### Graph → **pubinfo**

- The assertion stays the pure fact `<space> gen:hasHelper <bot>`, so it still reads
  correctly for consumers that already know the direction and is not polluted with
  processing hints.
- Direction is metadata about *how to read this nanopub's assertion* — exactly pubinfo's
  role (it already carries `npx:hasNanopubType`, `npx:introduces`, …). `SpacesExtractor`
  already reads pubinfo/signature to build its `Context`, so there is no new plumbing.
- pubinfo is inside the signature, so the pin is as tamper-evident as the assertion —
  no reason to prefer the assertion graph.

#### Granularity → **per predicate**

A predicate has exactly one direction, so a per-predicate pin is sufficient *and*
unambiguous even when one nanopub carries several role triples or several agents. A
per-nanopub flat flag breaks the moment a nanopub mixes predicates; per-triple
reification (RDF-star) is heavier than needed. Per-predicate is the sweet spot.

#### Triple structure → **classify the predicate** (recommended)

```turtle
# in pubinfo
gen:hasHelper a gen:InverseRoleProperty .     # or gen:RegularRoleProperty
```

Reads naturally ("hasHelper is an inverse role property"), one triple per predicate used,
and is reusable across nanopubs. New vocabulary: two classes `gen:InverseRoleProperty` /
`gen:RegularRoleProperty` in the KPXL `gen` namespace (`https://w3id.org/kpxl/gen/terms/`).

Close alternative — reuse the declaration vocabulary with the nanopub as subject:

```turtle
# in pubinfo
<this-np> gen:hasInverseProperty gen:hasHelper .
```

Maximally consistent with role declarations and scopes cleanly to "this nanopub uses it
inverse," but it reuses `gen:hasInverseProperty` with a *nanopub* in the subject position
where declarations put a *role* — a small domain stretch. We lean to the dedicated classes
to avoid overloading the declaration predicate.

#### Extractor resolution precedence

For each role triple in the assertion, resolve direction by:

1. **Known map** (`KnownRolePredicateDirections`) — short-circuits known/legacy predicates
   (and all existing data).
2. **pubinfo pin** — the direction class / triple above.
3. **Fallback** — neutral binding (today's path 2), or drop. See open question below.

With (1) or (2) satisfied, the extractor emits the normalized shape directly into
`spacesGraph`, and `get-space-members` works with no query change.

#### The role declaration stays the validation authority

The pin governs **read-side** extraction into `spacesGraph`. The state-graph materializer
must still cross-check the pinned direction against the role's `gen:hasInverseProperty` /
`gen:hasRegularProperty` (its arms 1–2 already do this). So a *wrong* pin can surface only
as an **unvalidated** `spacesGraph` row, never a validated one. Direction is thus
denormalized-but-contained: the single source of truth for **authority** remains the
declaration; the pin is only a locality hint for the per-nanopub extractor.

## Cardinality notes (current behavior, for reference)

The fix does not change these, but they bound what any redesign can rely on:

- **`gen:RoleInstantiation` path, classified branch:** emits **one** row from the *first*
  known-predicate triple and returns; the subject is minted from the artifact code alone,
  so it structurally holds one assignment per nanopub, and a trailing custom predicate in
  the same nanopub is never reached.
- **`gen:RoleInstantiation` path, neutral branch:** one row **per distinct predicate**,
  subject keyed by `hash(predicate)`. Multiple agents under the same predicate/space are
  fine (multi-valued `bindingObject`). Multiple *distinct spaces* under one predicate in
  one nanopub collapse onto a single subject with multi-valued `bindingSubject` **and**
  `bindingObject` → the space↔agent pairing becomes ambiguous and the materializer
  cross-products it. This is a latent conflation, not reachable by current single-space
  data.
- **Inline `gen:Space` path (`emitInlineRoleInstantiations`):** groups by
  `(space, predicate, direction)` with multi-valued `forAgent` — safe by construction
  (fixed space, one direction per predicate; only the agent set is collapsed). But it
  handles **only** known-map predicates: an inline `<space> gen:hasHelper <bot>` in a
  `gen:Space` nanopub is **silently dropped** (no neutral fallback on this path). Part A
  fixes this for these two predicates; Part B's pin would also need to be honored here if
  helper/maintainer members are declared inline in the Space nanopub.

**Orthogonal keying hazard.** Direction pinning fixes *which side is the space*; it does
nothing about the neutral path collapsing same-predicate-across-multiple-spaces. If
batched multi-space assignment nanopubs are ever allowed, key the minted RI subject on
`(predicate, space)` (or the full triple), independently of this design.

## Open questions

1. **Absent-pin behavior (Part B).** When a *new* custom predicate has no pubinfo pin and
   is not in the known map: fall back to neutral (keep today's path 2 + materializer
   resolution, so nothing regresses but the machinery stays), or **drop** (reject as
   malformed, which lets us eventually retire the whole neutral path — extractor neutral
   branch + `nonAdminTierUpdate` arms 3–4)? In an open publishing ecosystem, a hard drop
   breaks convention-unaware third-party publishers, so the realistic path is
   neutral-fallback now with a deprecation plan to require the pin later. Until then, Part
   B is a *fast path added on top of* the existing machinery, not a removal.
2. **Vocabulary choice.** Dedicated classes `gen:InverseRoleProperty` /
   `gen:RegularRoleProperty` (recommended) vs. reusing `gen:hasInverseProperty` /
   `gen:hasRegularProperty` with the nanopub as subject.
3. **Inline Space path (Part B).** Whether helper/maintainer (and future custom) members
   can be declared inline in a `gen:Space` nanopub, and if so whether the pin is honored
   there too (adding a neutral/pinned path to `emitInlineRoleInstantiations`, which today
   drops unknown predicates).

## Rollout

- **Part A** — code change to the known-predicate map + rename/re-document; unit test
  mirroring `AuthorityResolverTierIsolationTest.customPredicateResolvesDirectionAndTierFromRoleDeclaration`
  but asserting the **`spacesGraph`** normalized shape; full re-materialization / re-ingest
  to correct existing data. Closes #136. Independent of Part B.
- **Part B** — extractor change (precedence + read the pubinfo pin) + the
  `gen:InverseRoleProperty` / `gen:RegularRoleProperty` vocabulary; Nanodash emits the pin
  for new role-assigning nanopubs; decide the absent-pin behavior (open question 1) before
  any move to retire the neutral path.

## Related

- `design-space-repositories.md` — role declarations, `gen:SpaceMemberRole`,
  `gen:has(Regular|Inverse)Property`, the known-predicate list.
- `design-spaceref-isolation.md` — `spacesGraph` vs. per-ref state graph, `get-space-*`
  read model.
- `design-role-revocation.md` — per-`(space, agent, role)` suppression (why multi-valued
  `forAgent` on one extraction node is safe for revocation).
