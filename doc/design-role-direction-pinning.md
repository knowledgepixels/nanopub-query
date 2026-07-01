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

Two parts, deployable independently. The settled choices (see conversation, 2026-07-01):
keep the `BackcompatRolePredicates` name; fix **only** `gen:hasMaintainer` in Part A;
reserve `gen:hasHelper` as the Part B pilot; dedicated `gen:InverseRoleProperty` /
`gen:RegularRoleProperty` vocabulary; **strict drop** for an absent pin; inline
`gen:Space` assignments honored iff fixed-predicate **or** pinned.

### Part A — immediate fix: promote `gen:hasMaintainer` (partially closes #136)

Add **only `gen:hasMaintainer` (INVERSE)** to the known predicate→direction map,
`BackcompatRolePredicates`.

- **Keep the name and the "backcompat" framing.** The map is *not* reframed as permanent:
  the intent is still to retire it once pinning (Part B) is universal — at that point the
  few remaining old relations either break or get converted. Adding `hasMaintainer` is a
  pragmatic shortcut for a currently-common predicate, not a promise to keep the map
  forever. Do re-document lightly so it's clear the list is "resolve-without-a-pin, to be
  drained," not "legacy only."
- **Deliberately leave `gen:hasHelper` OUT.** It stays unresolved (dropped, per Part B's
  strict rule) so it can serve as the **test/pilot predicate** for the pubinfo pin. The
  few existing `hasHelper` instances (biochementity et al.) will be **manually migrated**
  — republished with the pin — after Part B ships.

Effect: `hasMaintainer` is resolved at extraction time into `spacesGraph` like every other
known predicate, on both the `gen:RoleInstantiation` path
(`SpacesExtractor.extractRoleInstantiation`) and the inline `gen:Space` path
(`emitInlineRoleInstantiations`). Existing `hasMaintainer` data is corrected by a full
re-materialization / re-ingest.

**Part A does not fully close #136.** The biochementity case in the issue is a `hasHelper`
membership, which is intentionally *not* fixed here; it is resolved by Part B + the manual
migration of those instances. The bot-as-admin workaround stays until then.

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

#### Triple structure → **classify the predicate** (decided)

```turtle
# in pubinfo
gen:hasHelper a gen:InverseRoleProperty .     # or gen:RegularRoleProperty
```

Reads naturally ("hasHelper is an inverse role property"), one triple per predicate used,
and is reusable across nanopubs. New vocabulary: two classes `gen:InverseRoleProperty` /
`gen:RegularRoleProperty` in the KPXL `gen` namespace (`https://w3id.org/kpxl/gen/terms/`).
Formal publication of the terms is a **follow-up, not a blocker** — Nanodash may emit them
before they are defined.

(The rejected alternative — `<this-np> gen:hasInverseProperty gen:hasHelper` — was more
consistent with role declarations but overloaded `gen:hasInverseProperty` with a nanopub
in the subject position where declarations put a role.)

#### Extractor resolution precedence

For each role triple in the assertion (and each inline role triple in a `gen:Space`
nanopub — see below), resolve direction by:

1. **`BackcompatRolePredicates`** — short-circuits known/legacy predicates (and all
   existing data).
2. **pubinfo pin** — the direction class above.
3. **Drop.** No known-map entry and no pin ⇒ the role triple is **rejected** (not emitted
   as a neutral binding). Strict by decision.

With (1) or (2) satisfied, the extractor emits the normalized shape directly into
`spacesGraph`, and `get-space-members` works with no query change. Because (3) never
produces a neutral row, the **neutral path becomes vestigial**: after a full re-ingest on
the new extractor, no neutral rows remain, and the extractor's neutral branch plus the
materializer's `nonAdminTierUpdate` UNION arms 3–4 (which only resolve neutral bindings)
can be **removed**. Sequence this removal *after* the re-ingest and *after* the existing
`hasHelper` instances are migrated to pins — otherwise those un-pinned instances are
dropped on re-ingest and their members vanish (no worse than today's #136 state, but the
migration is what restores them).

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
  (fixed space, one direction per predicate; only the agent set is collapsed). But today it
  handles **only** known-map predicates: an inline `<space> gen:hasHelper <bot>` in a
  `gen:Space` nanopub is **silently dropped**. **Decision (B.3):** this path must apply the
  same precedence as the `gen:RoleInstantiation` path — resolve inline assignments iff the
  predicate is in `BackcompatRolePredicates` **or** carries a pubinfo pin, dropping
  otherwise. So Part A makes inline `hasMaintainer` work; Part B makes inline pinned
  predicates (incl. migrated `hasHelper`) work. Factor the precedence into a shared
  `resolveDirection(predicate, pins)` helper used by both paths.

**Multi-space batching (decided).** A single role-assigning nanopub **may** bind one
predicate across multiple spaces, and each binding must become its own assignment. So the
minted RI subject is keyed on **`(predicate, space)`** (a hash of both), not
`hash(predicate)` alone. This also requires reworking the `gen:RoleInstantiation`
classified branch, which today emits one row and returns: it must iterate all role triples,
resolve each, group by `(space, predicate, direction)` with multi-valued `forAgent`, and
emit one row per group — the same grouping `emitInlineRoleInstantiations` already does, but
across multiple spaces. This belongs with Part B's `resolveDirection` refactor; until then
the classified path keeps its current one-assignment-per-nanopub limit (unchanged status
quo, not a regression, so Part A is unaffected).

## Settled decisions

- **Absent-pin behavior:** strict **drop/reject** (not neutral-fallback). Enables removing
  the neutral machinery after re-ingest + `hasHelper` migration (see precedence section).
  Accepts that convention-unaware third-party publishers of *unknown* predicates get
  dropped — an explicit strictness choice.
- **Vocabulary:** dedicated `gen:InverseRoleProperty` / `gen:RegularRoleProperty` classes.
- **Inline `gen:Space` path:** honored iff fixed-predicate or pinned (B.3 above).
- **Predicate scope in Part A:** `gen:hasMaintainer` only; `gen:hasHelper` reserved as the
  Part B pilot and migrated manually.
- **Multi-space batching:** supported — one nanopub may assign one predicate across several
  spaces, each a separate assignment; key the RI subject on `(predicate, space)` and group
  by `(space, predicate, direction)`.
- **Vocabulary publication:** Nanodash may emit the new `gen:` classes before they are
  formally defined; publishing them in the kpxl vocab is a follow-up.

## Open questions

1. **Timing of neutral-path removal.** Gated on (a) a full re-ingest on the new extractor
   and (b) migration of existing `hasHelper` instances to pins. Track as a follow-up, not
   part of the first Part B cut.

## Rollout

- **Part A** — add `gen:hasMaintainer` (INVERSE) to `BackcompatRolePredicates` (lightly
  re-document, no rename); unit test mirroring
  `AuthorityResolverTierIsolationTest.customPredicateResolvesDirectionAndTierFromRoleDeclaration`
  but asserting the **`spacesGraph`** normalized shape; full re-materialization / re-ingest
  to correct existing `hasMaintainer` data. **Partially** closes #136 (`hasHelper` deferred
  to Part B). Independent of Part B.
- **Part B** —
  1. Extractor: shared `resolveDirection(predicate, pins)` with precedence
     `BackcompatRolePredicates` → pubinfo pin → **drop**; wire into both
     `extractRoleInstantiation` and `emitInlineRoleInstantiations`, and rework the
     classified branch to group by `(space, predicate, direction)` with per-`(predicate,
     space)` subject keying (multi-space support).
  2. Nanodash emits the pin using `gen:InverseRoleProperty` / `gen:RegularRoleProperty`
     (may precede formal vocab definition — cross-repo dependency, not blocked on the vocab).
  3. Migrate existing `hasHelper` instances (republish with pins); then full re-ingest.
  4. Follow-up: remove the now-vestigial neutral branch + `nonAdminTierUpdate` arms 3–4;
     publish the `gen:` vocab term definitions.

## Related

- `design-space-repositories.md` — role declarations, `gen:SpaceMemberRole`,
  `gen:has(Regular|Inverse)Property`, the known-predicate list.
- `design-spaceref-isolation.md` — `spacesGraph` vs. per-ref state graph, `get-space-*`
  read model.
- `design-role-revocation.md` — per-`(space, agent, role)` suppression (why multi-valued
  `forAgent` on one extraction node is safe for revocation).
