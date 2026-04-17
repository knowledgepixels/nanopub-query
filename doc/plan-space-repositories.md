# Plan: Space Repositories in Nanopub Query

## Context

Today Nanodash computes Space membership client-side via a chain of API queries (`GET_ADMINS` → `GET_SPACE_MEMBER_ROLES` → `GET_SPACE_MEMBERS` → `GET_VIEW_DISPLAYS`) against `full`/`meta`. It's slow and puts the membership logic in the wrong place.

This plan moves that calculation server-side: each space gets its own dynamic repo `space_<spaceRef>` (sibling to the existing `type_<HASH>` / `pubkey_<HASH>` repos) holding all space-management nanopubs. Validated authority is pre-computed and materialized into the repo's admin graph so consumers can answer "who's an admin of S? what views apply to S?" with one SPARQL query.

## Space Identity

Prefixes used here: `gen:` = `<https://w3id.org/kpxl/gen/terms/>`, `npa:` = `<http://purl.org/nanopub/admin/>`.

A space is identified by a **space ref** of the form `<NPID>_<SPACEIRIHASH>`:
- **NPID** — the trusty-URI artifact code of the **root nanopub**.
- **SPACEIRIHASH** — `Utils.createHash(<Space IRI>)`, same hashing pattern as `type_<HASH>`.

The space ref is the repo-name suffix directly: `space_<NPID>_<SPACEIRIHASH>`. Globally unique by construction (artifact codes are content-addressed). Human-decodable by splitting on `_`. A single root nanopub can declare multiple Space IRIs.

### Root nanopub resolution

Every space-defining nanopub (original or update) declares its root via `gen:hasRootDefinition` in the assertion:

```turtle
<spaceIRI> gen:hasRootDefinition <rootNanopubURI> .
```

For the root itself this is self-referential (resolved at trusty-URI minting); for any update it points back at the original root. **A `gen:Space`-typed nanopub without this triple is ignored** — no transition fallback. This makes every space-defining nanopub self-describing: no chain walking, no ordering dependency.

### Role types

Four predefined `gen:SpaceMemberRole` subclasses form the authority hierarchy:

| Type | Predicate / how it's granted |
|------|------------------------------|
| `gen:AdminRole`      | Hardcoded to `gen:hasAdmin`. No user-defined admin roles in the MVP. |
| `gen:MaintainerRole` | Built-in `gen:hasMaintainer`; user-defined predicates can also declare this type. |
| `gen:MemberRole`     | User-defined predicates declared `rdf:type gen:MemberRole`. |
| `gen:ObserverRole`   | User-defined predicates declared `rdf:type gen:ObserverRole`. **Default** when a role definition doesn't declare a type. |

Per-tier privilege enforcement (`core`/`structure`/`content`/`comment`) is Nanodash's concern, out of scope here.

## What Goes Into a Space Repo

Loaded permissively — anything in the assertion that references a known space ref:

- The space-defining nanopub itself (type `gen:Space`)
- `gen:hasAdmin`, `gen:hasMaintainer` declarations
- Role-definition nanopubs (declare a user-defined role predicate for the space)
- Role-assignment nanopubs using a learned role property
- `gen:isDisplayFor` view-display nanopubs
- A catch-all for anything else mentioning the space ref (handles ordering/learning gaps)

**Not included** (deprecated): `hasPinnedTemplate`, `hasPinnedQuery`. These will not be supported after the Nanodash migration.

Why not "all nanopubs from all members"? Circular dependency (membership before populating), retroactive backfill on each join, data duplication, complex unloading.

## Authority: evidence + policy

Membership is not a flat triple match — it's a closure over delegation chains, validated against the trust-state mirror in the `trust` repo. To keep consumer queries simple, the validated state is **materialized** into the space repo's `npa:graph`. Materialization writes *evidence* (with kind), not a policy verdict; the per-tier policy is a query-time filter that can change without re-loading.

### Closures (admin, then maintainer)

The **admin** closure starts from the root nanopub's `gen:hasAdmin` triples (trusted by construction — the root NPID is part of the space ref) and grows by accepting any further `gen:hasAdmin` declaration whose publisher pubkey resolves (via trust state) to an existing admin. Iterate to fixed point. The **maintainer** closure runs the same way over `gen:hasMaintainer`, seeded from grants by closed admins, accepting further grants from admins or existing maintainers.

The closures are computed in Java (SPARQL 1.1 has no fixed-point recursion).

### Two evidence kinds

Every nanopub matching a registered role property is classified into one or more:

- **`npa:authorityEvidence`** — publisher resolves to an agent in the appropriate authority closure for that role tier.
- **`npa:selfEvidence`** — publisher resolves to the assigned-member agent itself.

Both can apply to the same nanopub. Nanopubs that match neither are not materialized as evidence (they remain in the repo as raw data and can be reclassified if policy ever changes).

### Materialized shape

In `npa:graph` of each space repo: one `npa:RoleAssignment` per `(agent, role, space)` tuple, linking to one or more evidence objects:

```turtle
GRAPH npa:graph {
  _:r1 a npa:RoleAssignment ;
       npa:forSpace    <spaceRef> ;
       npa:role        <roleIri> ;       # gen:AdminRole, gen:MaintainerRole, or a user-defined role IRI
       npa:agent       <agent> ;
       npa:hasEvidence _:e1, _:e2 .
  _:e1 npa:evidenceKind npa:authorityEvidence ;
       npa:viaNanopub   <granting_np> .
  _:e2 npa:evidenceKind npa:selfEvidence ;
       npa:viaNanopub   <self_confirm_np> .
}
```

Predicate names are working titles — pin them at implementation time.

### Policy table

Applied at query time:

| Role tier | Required evidence (Phase 1) | Future tightening (example) |
|-----------|-----------------------------|------------------------------|
| `gen:AdminRole`      | authority   | authority + self |
| `gen:MaintainerRole` | authority   | authority + self |
| `gen:MemberRole`     | authority   | authority + self |
| `gen:ObserverRole`   | self        | (unchanged) |

Phase 1 is single-evidence per tier. Switching a tier to dual confirmation later is a SPARQL change in consumer queries — no re-materialization, since both kinds are already collected.

### Re-materialization

Recompute the closures, reclassify evidence, and rewrite `npa:graph`'s authority triples (one transaction per space repo) when:

- A new authority/role-related nanopub lands in the space repo (admin/maintainer grant, role definition, role assignment).
- The trust-state pointer flips (publisher → agent mapping may have changed → recompute every space).
- Supersession or invalidation removes a nanopub from the inputs.

Atomic per space repo. Eventual-consistency semantics — queries between trigger and recompute see the previous state.

### Decisions worth flagging

- **Closure-based, not time-ordered.** A grant counts whenever the granter is *ever* established as admin, not only at publication time. Simpler; revisit if attacks appear.
- **Sticky, non-cascading revocation.** Revoking A doesn't auto-revoke B (whom A admin'd). Cascading is a recurring source of surprise.

## Implementation Phases

### Phase 1 — Detection & Loading

Done in `NanopubLoader` + a new `SpaceRegistry` (in-memory, persisted to the admin repo).

- `SpaceRegistry`: tracks known space refs and the role properties learned per space; reverse index Space IRI → space refs. Loaded from admin repo on startup, updated as new nanopubs arrive.
- Detection in the loader (constructor / type-extraction site): match the categories listed in [What Goes Into a Space Repo](#what-goes-into-a-space-repo). For `gen:Space`-typed nanopubs, validate `gen:hasRootDefinition` and derive the space ref before registering.
- Loading in `executeLoading`: same shape as the existing `type_` loop, except the space ref goes directly into the repo name (no `Utils.createHash` indirection).
- `TripleStore.initNewRepo`: add a `space_` branch that records `npa:hasCoverageItem` (the space ref as a literal) and `npa:hasCoverageFilter`. No coverage-hash entry — the ref isn't hashed. Consider lighter indexes (3 instead of 6, like meta repos) — space repos are small.

### Phase 2 — Authority Materialization

A new `AuthorityResolver` owns closure computation, evidence classification, and the materialization write to `npa:graph`. Pure writer; consumers read with plain SPARQL.

Triggered from:
- The loader, after writing an authority/role-related nanopub into a space repo (covers most cases).
- The trust-state subsystem, after a successful pointer swap (recompute every known space).
- The invalidation flow (Phase 3) — a removed nanopub may shrink the materialized set.

Implementation freedom: how the resolver schedules and batches recomputes (per-space queue, debouncing, etc.) is to be decided in code; the contract above is what matters.

### Phase 3 — Routes, Metrics, Invalidation, Unloading

Wiring around the new repo type, all in existing files (`MainVerticle`, `MetricsCollector`, `NanopubLoader`):

- `/spaces` listing endpoint (mirrors `/types`); `for-space` redirect parameter; include `space_` repos in the dynamic-repo filter on the main listing.
- A space-repo gauge in `MetricsCollector`.
- Invalidation propagation: when a nanopub is invalidated, mirror the invalidation into any space repos it had been loaded into. Triggers a re-materialization for those spaces.
- Unloading:
  - **Member removal** — handled by standard invalidation; no extra logic.
  - **Root supersession** — the new root carries `gen:hasRootDefinition` pointing back at the original, so it resolves to the same space ref and lands in the same repo.
  - **Space dissolution** — when a root is retracted with no superseder, mark the space dissolved in `SpaceRegistry`; a periodic job clears dissolved space repos (pattern similar to `last30d` cleanup).

### Phase 4 — Nanodash Migration

Two contracts to honor on Nanodash's side, then a query rewrite:

- **Publishing:** space-defining nanopub templates must include `<spaceIRI> gen:hasRootDefinition <rootNanopubURI>` (self-referential for new spaces, original-root for updates). Pre-existing space nanopubs without this triple need to be republished to be picked up.
- **Querying:** replace `Space.triggerSpaceDataUpdate()`'s 4-query chain with SPARQL against the `space_<spaceRef>` repo. Replace `AbstractResourceWithProfile.GET_VIEW_DISPLAYS` admin-pubkey gate with a join against the materialized `npa:RoleAssignment` triples + a SERVICE join to the trust repo to map publisher pubkeys to agents. The view-display query shape (sketch — refine at implementation):

  ```sparql
  SELECT ?display WHERE {
    GRAPH ?np { ?display gen:isDisplayFor <SPACE_REF> . }
    GRAPH ?npPubinfo { ?np nptemp:hasPubkey ?pubkey . }
    GRAPH npa:graph {
      ?ra a npa:RoleAssignment ;
          npa:forSpace <SPACE_REF> ;
          npa:role     ?role ;
          npa:agent    ?publisherAgent ;
          npa:hasEvidence [ npa:evidenceKind npa:authorityEvidence ] .
      FILTER(?role IN (gen:AdminRole, gen:MaintainerRole))
    }
    SERVICE <…/repo/trust> {
      GRAPH npa:graph { npa:thisRepo npa:hasCurrentTrustState ?ts }
      GRAPH ?ts {
        ?_ npa:agent ?publisherAgent ; npa:pubkey ?pubkey ;
           npa:trustStatus ?status .
        FILTER(?status IN (npa:loaded, npa:toLoad))
      }
    }
  }
  ```

  Until maintainer detection is fully wired, the maintainer set is empty and the filter degenerates to admins-only. Switching any tier to dual confirmation later just adds a second `npa:hasEvidence` triple.
- Drop the pinned-templates / pinned-queries calls; those features are deprecated.

## Bootstrap (existing deployments)

On first deployment with space repos enabled, scan `meta` for `gen:Space`-typed nanopubs to discover roots, then scan `full` for nanopubs referencing each discovered space ref, and load into the corresponding `space_` repo. Re-run authority materialization for each. Restartable; tracked in the admin repo.

The same catch-up pattern handles the in-stream ordering case where a referencing nanopub arrives before the space-defining one: re-scan after each new space is discovered.

## Key Files

| File | Role |
|------|------|
| `NanopubLoader.java`     | Detection + space-repo loading + invalidation propagation |
| `TripleStore.java`       | `space_` branch in `initNewRepo` |
| `MainVerticle.java`      | `/spaces` route, `for-space` redirect, dynamic-repo filter |
| `MetricsCollector.java`  | Space-repo gauge |
| **New:** `SpaceRegistry.java`     | Known spaces + learned role properties; admin-repo persistence |
| **New:** `AuthorityResolver.java` | Closure computation, evidence classification, materialization write |

## Open / deferred

- **Time-ordered authority enforcement** (vs. closure-based today).
- **Cascading revocation** (vs. sticky).
- **Cryptographic linking** of admin agents to intro nanopubs / pubkeys (currently agent IRIs are taken at face value; the trust-state mirror only validates the *publisher* of authority nanopubs).
- **Dual-confirmation policies** per tier (the materialization already supports this; just flip the policy table and update consumer queries).
- **Multi-registry support** — single registry source assumed.

## Verification

- Unit tests for `SpaceRegistry` and `AuthorityResolver` (closures, evidence classification, idempotence of rematerialize).
- Integration tests with fixture nanopubs covering: space definition with hasRootDefinition, admin chain depth ≥ 2, role definition + matching role assignment, ViewDisplay published by an admin vs. a non-admin, supersession of an admin grant, trust-state pointer flip, root retraction + space dissolution.
- End-to-end against a local `nanopub-registry` instance: confirm the `space_<ref>` repo is populated and `npa:graph` carries the expected `RoleAssignment` triples.
- Nanodash side: a Space page renders correctly using only the new space-repo queries.
