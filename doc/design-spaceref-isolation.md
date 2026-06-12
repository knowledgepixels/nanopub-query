# Design: Per-Space-Ref Authority Isolation

## Context

A **space ref** is the identity of a space: `<NPID>_<SPACEIRIHASH>` where `NPID` is the
root nanopub's artifact code and `SPACEIRIHASH = Utils.createHash(<Space IRI>)` (see
`design-space-repositories.md`). Distinct root definitions of the *same* Space IRI
therefore produce distinct refs — by design, so that a forged or rival root for an
existing IRI is a separate identity rather than an extension of the real one.

The authority materializer (`AuthorityResolver`) does not honor that boundary. Every
tier — admin closure, attachment validation, the member/maintainer/observer tiers,
and the alias / sub-space / maintained-resource admit passes — joins on the **bare
Space IRI** (`npa:forSpace <IRI>`), not on the ref. The ref is collapsed to its IRI at
the top of `adminTierUpdate` via `?spaceRef npa:spaceIri ?space`, and from there the
whole closure operates on `?space`.

### The bug

Two refs that share a Space IRI but have different roots get their authority **merged**.
If `S` has refs `R1` (root admin Alice) and `R2` (root admin Bob), the admin seed binds
both `(R1→Alice→S)` and `(R2→Bob→S)` with `?space = S`, the closures union, and Bob's
grants are honored in the same authority domain as Alice's. This is not only a forked-space
edge case: the rootless back-compat path (`SpacesExtractor` rootless branch) makes every
`gen:Space` nanopub without `gen:hasRootDefinition` its *own* root, so any rootless nanopub
naming `S` with itself as admin seeds itself into the merged authority for `S`.

## Decision

**Full isolation.** The space ref is the unit of authority. Two refs that share a Space
IRI are two independent authority domains; nothing crosses between them.

This was chosen over "converge by IRI" / "pick a canonical ref per IRI" because:
- There is no canonical ref derivable from the data (the Space IRI does not embed its
  root), so any canonical rule is a policy with a hijack-vulnerable default — the current
  implicit one (`get-spaces` `ORDER BY DESC(?date)`, latest definition wins) lets a *later*
  rival root become the displayed space.
- The only released Nanodash (4.28.0, 2026-05-13) does **not** read this repo at all (it
  computes trust client-side over generic grlc queries). The spaces-repo integration is
  entirely post-4.28.0 and unreleased, so there is no wire-compat obligation — we re-key
  freely and co-release the matching Nanodash queries.

### The one rule

Authority is per-ref. A **bare Space IRI used in an authority context** is satisfied,
per-ref, for exactly the refs of that IRI where the acting agent is a validated admin,
and the structural effect attaches to those refs.

This single rule governs every tier — admin grants, role attachments, aliases, sub-spaces,
maintained-resources. In SPARQL it is the natural fan-out of the join: replace each
`?x npa:forSpace ?spaceIRI` admin probe with

```sparql
?ref npa:spaceIri ?spaceIRI .
?x   npa:forSpaceRef ?ref ; npa:forAgent ?publisher .
```

so the query binds every ref of the IRI where the publisher is admin and materializes one
per-ref result for each. No canonical pick, no tiebreak, no trust oracle.

### Properties preserved

- **Isolation.** Eve (admin of rival ref `R2` of `S`) publishing `<S> owl:sameAs <Victim>`
  applies only to `{R2}`; Alice's real ref `R1` is untouched.
- **Anti-hijack (#113).** The `admins(alias) ⊆ admins(canonical)` gate now evaluates against
  the specific ref's admin set, not a merged blob — strictly tighter.
- **No escalation.** The only admin source is the per-ref root seed (`npa:hasRootAdmin`),
  which is immutable per ref. A bare-IRI reference can only act within refs you already
  govern; it can never bootstrap admin.

### Accepted consequences

1. **Rootless-only legacy spaces fragment.** A pre-migration space that exists only as
   scattered rootless nanopubs becomes multiple distinct spaces (one per declarer's ref),
   not one merged space. They do not re-converge. Acceptable: no released consumer depends
   on the merge, and the new Nanodash displays per ref.
2. One declaration can fan out to **N edges** when the acting agent is admin of N refs of
   the referenced IRI. Intended ("count for all refs that have the given admin").

## Data-model changes (`spaces` repo)

New / changed predicates on materialized state-graph rows:

- **`npa:forSpaceRef`** — added to every materialized authority row (admin RIs, RoleAssignments,
  non-admin RIs). This becomes the join key for the entire closure. `npa:forSpace <IRI>` is
  **dropped** from the state graph as a join key (it survives only as the convenience the read
  queries resolve through `?ref npa:spaceIri ?iri`). Extraction-graph rows are unchanged
  (still IRI-keyed — they are pre-validation and carry no ref).
- **`npa:sameAsSpace`** becomes **ref-valued on the canonical side**: `<aliasIRI> npa:sameAsSpace <canonicalRef>`.
  The alias side stays a bare IRI (the common case is a deprecated IRI with no ref of its own);
  the canonical side is a ref. Alias-aware admin lookups resolve `aliasIRI → canonicalRef`.
- Sub-space and maintained-resource convenience edges become **ref-to-ref** (sub-space) and
  **resource→ref** (maintained-resource) in the state graph.

### Per-ref subject minting and dedup

Because a single nanopub now produces rows for multiple refs, every minted subject and every
`FILTER NOT EXISTS` dedup must include a ref discriminator, or rows collide. Concretely:

- `SpacesVocab.forRoleInstantiation(artifactCode)` /
  `forRoleInstantiation(artifactCode, discriminatorHash)` gain a **ref-hash** component
  (`Utils.createHash(<spaceRef>)`), so the same instantiation nanopub yields distinct subjects
  per ref it validates into. Same for RoleAssignment / sub-space / maintained-resource / alias
  declaration subjects where fan-out applies.
- Every dedup `FILTER NOT EXISTS { ... ?existing npa:forSpace ?space ; npa:forAgent ?agent ... }`
  re-keys to `npa:forSpaceRef ?spaceRef`.

## Materialization changes (`AuthorityResolver`)

Each tier query keeps its current structure (anchor order, load-number filter, invalidation
filter, late-arrival behavior) and changes only the join key from `?space` to `?spaceRef`,
plus the `?ref npa:spaceIri ?space` indirection where the input is a bare IRI.

### `adminTierUpdate`

- **Seed branch** — already ref-aware (`?def npa:forSpaceRef ?spaceRef ; npa:hasRootAdmin ?publisher`).
  Keep `?spaceRef npa:spaceIri ?space` to probe the IRI-keyed extraction graph, but carry
  `?spaceRef` into the INSERT. Insert `?ri ... npa:forSpace ?space ; npa:forSpaceRef ?spaceRef`.
- **Closed-over branch** — recursion key flips from `?space` to `?spaceRef`
  (`?prev npa:forSpaceRef ?spaceRef ; ... npa:forAgent ?publisher`), then resolve
  `?spaceRef npa:spaceIri ?space` for the extraction-graph probe.
- **Instantiation lookup** — unchanged (IRI-keyed `npa:forSpace ?space ; npa:pubkeyHash ?pkh`
  against the extraction graph). The fan-out is automatic: an instantiation naming `S` joins to
  the admin rows of *each* ref of `S` whose admin set contains the publisher.
- **Dedup** — key on `(?spaceRef, ?agent)`.
- `spaceRefAliveFilter` is already anchored on `?spaceRef`; no change.

### `attachmentValidationUpdate` / `PUBLISHER_IS_ADMIN`

- Admin probe `?adminRI npa:forSpace ?space` → `?adminRI npa:forSpaceRef ?spaceRef`,
  with `?spaceRef npa:spaceIri ?space`. The attachment (IRI-keyed in extraction) fans out:
  for each ref of `S` whose admin set contains the publisher, insert a RoleAssignment tagged
  `npa:forSpaceRef ?spaceRef`.
- The alias `UNION` arm resolves the canonical side as a **ref**: `?aliasIRI npa:sameAsSpace ?canonRef`,
  admin probe on `?canonRef`.

### `nonAdminTierUpdate` (maintainer / member / observer) — **plus the members fix**

- Anchor RoleAssignment now carries `?spaceRef`; the inserted RI is tagged with it; the
  publisher constraint resolves admin-ness via `?spaceRef`; dedup keys on `(?spaceRef, ?agent)`.
- **Carry the role property onto the inserted row.** The current INSERT writes only
  `?ri a gen:RoleInstantiation ; npa:forSpace ?space ; npa:forAgent ?agent ; npa:viaNanopub ?np`
  — no `regularProperty`/`inverseProperty`. Add the matched `?pred` (as `npa:regularProperty` /
  `npa:inverseProperty`) so the validated member row is self-describing. This is what lets
  `get-space-members` read validated state instead of raw extraction (see Read contract).

### `aliasAdmitUpdate`

- Authority gate `?adminRI npa:forSpace ?canonical` → resolve `?canonRef npa:spaceIri ?canonical`,
  probe `?adminRI npa:forSpaceRef ?canonRef`. Anti-hijack `admins(alias) ⊆ admins(canonical)`
  evaluated per ref of each side.
- Emit `<aliasIRI> npa:sameAsSpace <canonRef>` (ref-valued canonical) for each canonical ref the
  publisher governs. Subject minted with a ref discriminator.

### `subSpaceAdmitUpdate`

- Mode A: publisher admin of both child and parent → fan out over the cross-product of
  (child refs where admin) × (parent refs where admin); emit ref-to-ref
  `<childRef> npa:isSubSpaceOf <parentRef>` (+ inverse).
- Mode B: co-declaration coverage, same per-ref resolution on each side.

### `maintainedResourceAdmitUpdate`

- Authority gate `?riA npa:forSpace ?s` → `?sRef npa:spaceIri ?s`, probe `npa:forSpaceRef ?sRef`.
  Emit `<r> npa:isMaintainedBy <sRef>` for each maintaining ref the publisher governs (the
  resource `r` is not a space, so no fan-out on `r`).

### URL-prefix sub-space fallback

- The prefix-match pass keys on `SpaceRef` aggregates already (`?childRef npa:spaceIri ?child`),
  so it is the least affected; its emitted edges become ref-to-ref for consistency.

No change to: invalidation DELETEs' structure (re-key their WHERE on `?spaceRef`), the
late-arrival sweep, the pointer-flip / full-build / incremental driver.

## Read contract (ref-explicit, co-released Nanodash)

The read path becomes ref-explicit end to end — Nanodash carries the ref it selected from
`get-spaces` into every follow-up query, so no canonical-ref policy is needed on the hot path.

| Query | Change |
|---|---|
| `get-spaces` | Already enumerates `npa:SpaceRef`/`npa:forSpaceRef`. **Project the ref** (`?spaceRef`) alongside `space_iri`, so the consumer can carry it. **Gate non-root definitions on the ref's validated admin set** (see [Definition selection within a ref](#definition-selection-within-a-ref)). |
| `get-space-admins` | Accept a **ref** (or `(spaceIri, rootNp)`); match `?ri npa:forSpaceRef ?ref ; npa:inverseProperty gen:hasAdmin`. |
| `get-space-admin-pubkey-hashes` | Same: admin RI on `npa:forSpaceRef ?ref` + AccountState. |
| `get-space-roles` | RoleAssignment on `npa:forSpaceRef ?ref`. |
| `get-space-members` | **Repoint to the current-state graph** (was raw `npa:spacesGraph`): match the validated non-admin `gen:RoleInstantiation` rows on `npa:forSpaceRef ?ref`, reading `regProp`/`invProp` from the row (now carried — see members fix). This makes membership authority-validated *and* ref-scoped. Note: strictly fewer "members" than today, since unvalidated instantiations no longer appear. |
| `get-sub-space-links` | Return ref-keyed `?childRef npa:isSubSpaceOf ?parentRef`; Nanodash resolves by ref. |
| `get-maintained-resources` | Return `?resource npa:isMaintainedBy ?sRef`; project the maintaining ref. |

The grlc queries are published nanopubs (`RA…` IDs); new revisions ship with the Nanodash
change. See the post-4.28.0 Nanodash `QueryApiAccess` "Spaces-repo queries" block for the
current IDs.

### Definition selection within a ref

Ref isolation alone does not gate what happens *inside* a ref: any nanopub that re-roots
to an existing root via `gen:hasRootDefinition` joins that ref as a further
`npa:SpaceDefinition`, and nothing in the extraction path checks who signed it. A
latest-definition-wins read (the current `get-spaces` `ORDER BY DESC(?date)`) would
therefore let a non-admin re-label or re-describe a space without creating a rival ref.

Rule, enforced in the read path (`get-spaces`), not in materialization:

- The **root definition** (the one whose `npa:viaNanopub` equals the ref's
  `npa:rootNanopub`) is trusted by construction — its NPID *is* the ref identity. Note
  that its *signer* need not be in its own `npa:hasRootAdmin` set (spaces are sometimes
  set up on behalf of their admins), so no signer check applies to it.
- A **non-root definition** supplies display data (label, type, description, dates,
  alt-IDs, default provenance) only if its `npa:pubkeyHash` resolves to a validated
  admin of the ref — i.e. it matches the same per-ref admin set that
  `get-space-admins` / `get-space-admin-pubkey-hashes` read (`npa:forSpaceRef ?ref` +
  AccountState).
- Selection: the latest non-invalidated definition passing this gate; the root
  definition is the floor when no gated update exists.
- Edge case — the root definition is itself invalidated and no gated update exists,
  yet the ref is still alive via some ungated (non-admin) definition. The floor is then
  gone and nothing passes the gate, so the ref renders with no label/type/description.
  This is the intended outcome (an admin-superseded space with only unauthorized updates
  left has no authoritative display data), not an accident — `get-spaces` simply returns
  the ref with empty display fields, and Nanodash shows the bare IRI.

Definitions failing the gate stay in the extraction graph untouched (add-only as
always); they are simply never selected for display.

### Nanodash-side

- `SpaceRepository`: keep one `Space` per ref (stop the `DESC(?date)` dedup-by-IRI; the ref is
  the identity). `Space` already carries `rootNanopubId`; thread the ref into `spaceParams`.
- `Space.spaceParams` / `allSpaceIris`: bind the ref (or `spaceIri + rootNp`) instead of bare IRI.
  `owl:sameAs` alt-IDs are now resolved server-side via `npa:sameAsSpace` rather than the
  client-side root-nanopub read — decide whether to retire the client-side alias expansion.
- `MaintainedResourceRepository`: resolve resource→space by ref.

## Backwards compatibility

- **Nanodash 4.28.0 (released):** does not read the spaces repo. **Zero impact.**
- **Post-4.28.0 Nanodash (unreleased):** co-released with the query revisions above.
- No deprecation window, no dual-keying for legacy reads.

## Migration

The state graph is rebuilt from scratch on every full build, so there is **no data migration**.
The extraction graph (`npa:spacesGraph` raw rows) is unchanged, so loaded nanopubs need no
re-extraction. Ship the change, then force a resync (`FORCE_RESYNC`) so the resolver rebuilds the
state graph under the new keying.

### Stray-ref cleanup (data task, before or with the co-release)

Per-ref display fragments legacy multi-ref spaces into duplicate entries (accepted
consequence 1). As measured on the live spaces repo on 2026-06-12, this affects 6 of 114
spaces (14 stray refs), all same-owner rootless-transition duplicates with identical
root-admin sets across refs (in three cases under *different* signers):

| Space IRI (under `https://w3id.org/spaces/`) | Live refs |
|---|---|
| `plantmetwiki` | 4 |
| `PSE8/Nanopublications-Hackathon` | 3 |
| `knowledgepixels/incubator/project2` | 3 |
| `ReproNanopub` | 2 |
| `nanopub/nanosessions/session31` | 2 |
| `sciencelive/dggs4eo` | 2 |

Cleanup: republish each space once with an explicit `gen:hasRootDefinition` pointing at
the ref to keep, and invalidate the stray definitions so their refs go dead
(`spaceRefAliveFilter` then drops their seeds). Pure publishing activity — no code in
either repo — but it should land before users see per-ref listings, so duplicates never
render.

## Testing

- Two refs sharing a Space IRI with disjoint root admins → admin/role/member sets stay disjoint;
  neither leaks into the other.
- Hijack: rival root + alias/sub-space/maintained-resource declaration by the rival admin →
  effects confined to the rival ref; the legitimate ref unchanged.
- Single-ref space → byte-identical authority sets to pre-change (modulo the new `forSpaceRef`
  triple and the now-validated member set).
- Fan-out: agent admin of N refs of an IRI issuing one declaration → N per-ref edges, distinct
  subjects, no dedup collision.
- `get-space-members` returns only validated members and respects ref isolation; carries the
  role property so client-side role mapping still works.
- Within-ref definition gating: a later definition re-rooted to the ref by a non-admin does
  not change the selected label/type; the same definition signed by a validated admin of the
  ref does. A ref with no gated update falls back to its root definition.
