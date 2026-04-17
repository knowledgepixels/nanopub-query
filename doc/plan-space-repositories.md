# Plan: Space Repositories in Nanopub Query

## Context

Today Nanodash computes Space membership client-side via a chain of API queries (`GET_ADMINS` → `GET_SPACE_MEMBER_ROLES` → `GET_SPACE_MEMBERS` → `GET_VIEW_DISPLAYS`) against `full`/`meta`. It's slow and puts the membership logic in the wrong place.

This plan moves that calculation server-side. A single new repo `spaces` holds, per-space, a named graph of *derived* authority data: who's admin/maintainer, the resolved role assignments with evidence, the validated view displays, and the space's profile metadata. Source nanopubs continue to live in `full`/`meta`; the `spaces` repo is purely the projection. Consumers answer "who's an admin of S? what views apply to S?" with one SPARQL query against `spaces`, no `SERVICE` join needed.

## Space Identity

Prefixes used here: `gen:` = `<https://w3id.org/kpxl/gen/terms/>`, `npa:` = `<http://purl.org/nanopub/admin/>`.

A space is identified by a **space ref** of the form `<NPID>_<SPACEIRIHASH>`:
- **NPID** — the trusty-URI artifact code of the **root nanopub**.
- **SPACEIRIHASH** — `Utils.createHash(<Space IRI>)`, same hashing pattern as `type_<HASH>`.

Globally unique by construction (artifact codes are content-addressed). Human-decodable by splitting on `_`. A single root nanopub can declare multiple Space IRIs.

Within the `spaces` repo, each space has its own named graph. Sketch IRI: `<http://purl.org/nanopub/admin/space/<spaceRef>>` — pin the exact namespace at implementation time.

### Root nanopub resolution

Every space-defining nanopub (original or update) declares its root via `gen:hasRootDefinition` in the assertion:

```turtle
<spaceIRI> gen:hasRootDefinition <rootNanopubURI> .
```

For the root itself this is self-referential (resolved at trusty-URI minting); for any update it points back at the original root. **A `gen:Space`-typed nanopub without this triple is ignored** — no transition fallback. Self-describing: no chain walking, no ordering dependency.

### Role types

Four predefined `gen:SpaceMemberRole` subclasses form the authority hierarchy:

| Type | Predicate / how it's granted |
|------|------------------------------|
| `gen:AdminRole`      | Hardcoded to `gen:hasAdmin`. No user-defined admin roles in the MVP. |
| `gen:MaintainerRole` | Built-in `gen:hasMaintainer`; user-defined predicates can also declare this type. |
| `gen:MemberRole`     | User-defined predicates declared `rdf:type gen:MemberRole`. |
| `gen:ObserverRole`   | User-defined predicates declared `rdf:type gen:ObserverRole`. **Default** when a role definition doesn't declare a type. |

Per-tier privilege enforcement (`core`/`structure`/`content`/`comment`) is Nanodash's concern, out of scope here.

## What's In the `spaces` Repo

Per space, in its named graph, the projection holds **only what's derived or extracted** — never raw nanopubs. Each entry carries `npa:viaNanopub` provenance back to the source in `full`.

- **Space profile** — root NPID, Space IRI, description, dates, alt IDs, declared subtypes.
- **Authority extracts** — the `gen:hasAdmin` / `gen:hasMaintainer` triples copied from source assertions, each tagged with the source nanopub and the publisher's resolved agent. These are the *inputs* the materializer iterates to compute closures.
- **Role definitions** — for each user-defined role predicate registered for the space: predicate IRI, declared role type (defaulting to `gen:ObserverRole`), label, template URI, regular/inverse property metadata.
- **`RoleAssignment` objects** — one per `(agent, role, space)` tuple, with one or more `npa:hasEvidence` links (see below).
- **Validated view displays** — for each `gen:isDisplayFor` source whose publisher passes the view-display policy: display IRI, target resource, applies-to/namespace/instances rules, template URI, source nanopub.

**Explicitly not in the `spaces` repo**:
- Source nanopubs themselves (in `full`).
- `hasPinnedTemplate` / `hasPinnedQuery` — deprecated, dropped after the Nanodash migration.
- A catch-all "any nanopub mentioning the space ref" — the extraction set above is the contract; new Nanodash needs are met by extending the materializer, not by hoarding raw data.

## Authority: evidence + policy

Membership is the fixed-point closure of a delegation chain, validated against the trust-state mirror in the `trust` repo. Validated state is materialized into the space's graph as evidence-bearing assignment objects so consumers query plain SPARQL — no `SERVICE` join needed at read time.

### Closures (admin, then maintainer)

The **admin** closure starts from the root nanopub's `gen:hasAdmin` triples (trusted by construction — the root NPID is part of the space ref) and grows by accepting any further `gen:hasAdmin` declaration whose publisher pubkey resolves (via trust state) to an existing admin. Iterate to fixed point. The **maintainer** closure runs the same way over `gen:hasMaintainer`, seeded from grants by closed admins, accepting further grants from admins or existing maintainers. Computed in Java (SPARQL 1.1 has no fixed-point recursion).

### Two evidence kinds

Every nanopub matching a registered role property is classified into one or more:
- **`npa:authorityEvidence`** — publisher resolves to an agent in the appropriate authority closure for that role tier.
- **`npa:selfEvidence`** — publisher resolves to the assigned-member agent itself.

Both can apply to the same nanopub. Nanopubs that match neither are *not* materialized.

### Materialized shape

In the space's graph: one `npa:RoleAssignment` per `(agent, role, space)` tuple, linking to one or more evidence objects. Each evidence object records both the source nanopub *and* the resolved publisher agent — so consumer queries don't need to touch the trust repo.

```turtle
GRAPH <npa:space/spaceRef> {
  _:r1 a npa:RoleAssignment ;
       npa:forSpace          <spaceRef> ;
       npa:role              <roleIri> ;       # gen:AdminRole, gen:MaintainerRole, or a user-defined role IRI
       npa:agent             <agent> ;
       npa:hasEvidence       _:e1, _:e2 .
  _:e1 npa:evidenceKind      npa:authorityEvidence ;
       npa:viaNanopub        <granting_np> ;
       npa:viaPublisherAgent <publisherAgent> .  # resolved at materialization time
  _:e2 npa:evidenceKind      npa:selfEvidence ;
       npa:viaNanopub        <self_confirm_np> ;
       npa:viaPublisherAgent <agent> .            # same as the assignee, by definition
}
```

Predicate names are working titles — pin at implementation time. `RoleAssignment` and evidence IRIs are deterministic (e.g. derived from `space_ref + role + agent` and from `assignment + source_nanopub`) so fast-path and recompute writes are idempotent.

### Policy table

Applied at query time:

| Role tier | Required evidence (Phase 1) | Future tightening (example) |
|-----------|-----------------------------|------------------------------|
| `gen:AdminRole`      | authority   | authority + self |
| `gen:MaintainerRole` | authority   | authority + self |
| `gen:MemberRole`     | authority   | authority + self |
| `gen:ObserverRole`   | self        | (unchanged) |

Switching a tier to dual confirmation later is a SPARQL change in consumer queries — both kinds are already collected.

### Decisions worth flagging

- **Closure-based, not time-ordered.** A grant counts whenever the granter is *ever* established as admin, not only at publication time. Simpler; revisit if attacks appear.
- **Sticky, non-cascading revocation.** Revoking A doesn't auto-revoke B (whom A admin'd). Cascading is a recurring source of surprise.
- **Trust repo stays separate.** Different lifecycle (registry-mirrored, snapshotted) and different update cadence; merging would muddy the model. The materializer is the only piece that joins trust + spaces, in Java where the closure logic lives.

## Implementation Phases

### Phase 1 — Detection & Extraction

Done in `NanopubLoader` + a new `SpaceRegistry` (in-memory, persisted to the admin repo).

- `SpaceRegistry`: tracks known space refs and the role properties learned per space; reverse index Space IRI → space refs. Loaded from admin repo on startup.
- Detection in the loader (constructor / type-extraction site): identify nanopubs that contribute to a known space — `gen:Space` (with required `gen:hasRootDefinition`), `gen:hasAdmin`/`gen:hasMaintainer`, role definitions for known spaces, role-assignments matching learned role properties, `gen:isDisplayFor` referencing a known space ref, root-nanopub profile data.
- For each detected nanopub, **extract** the relevant triples (not the whole nanopub) into the corresponding space graph in the `spaces` repo, tagged with `npa:viaNanopub` provenance. The extraction set is the schema in [What's In the `spaces` Repo](#whats-in-the-spaces-repo).
- `TripleStore`: one-time `spaces` repo init (not a per-prefix branch). Lighter indexes are fine — the repo is small.

### Phase 2 — Authority Materialization

A new `AuthorityResolver` owns closure computation, evidence classification (including resolving publisher pubkey → agent via the trust repo), view-display validation, and the materialization write to the space's graph in `spaces`. Pure writer; consumers read with plain SPARQL.

**Update flow.** Two paths cooperate:

- **Fast path (in the loader, same transaction as the extraction write).** When an authority-relevant extract lands, classify it against the *current* materialized state and trust state and emit any evidence triple (with resolved publisher agent) it directly contributes. Covers all purely additive cases — admin-grants-maintainer, observer-self-assigns, admin-assigns-member, any self-evidence — so the new membership is visible inside the same load cycle.
- **Slow path (worker thread, debounced).** The resolver maintains a `Set<spaceRef>` of dirty spaces. Loader writes (fast or otherwise) and invalidations call `markDirty(ref)`; trust-state pointer flips call `markAllDirty()`. A worker drains the set, running one full recompute per dirty space — clear all `RoleAssignment` + evidence + validated-view-display triples in the space graph, recompute closures, reclassify all evidence and re-validate displays, rewrite. One serializable transaction per recompute; idempotent; re-emits whatever the fast path wrote plus any transitive unlocks. If `markDirty` arrives mid-recompute, re-mark.

The slow path is the source of truth — the fast path is an optimization that's allowed to be incomplete (e.g. it can't catch transitive admin-chain expansion or invalidation-driven shrinkage). Both write the same triple shape.

### Phase 3 — Routes, Metrics, Invalidation, Unloading

- `/spaces` listing endpoint: enumerates known spaces from `SpaceRegistry`. `for-space?ref=<spaceRef>` redirects to a query against the `spaces` repo, scoped to that space's named graph.
- A `MetricsCollector` gauge for `#known spaces`.
- Invalidation propagation: when a source nanopub is invalidated, look up its affected spaces in `SpaceRegistry`'s reverse index, remove its extracted triples from those space graphs, and `markDirty(ref)` for each. The slow-path recompute does the rest.
- Unloading:
  - **Member removal** — handled by standard invalidation; no extra logic.
  - **Root supersession** — the new root carries `gen:hasRootDefinition` pointing back at the original, so it resolves to the same space ref. Same graph.
  - **Space dissolution** — when a root is retracted with no superseder, mark the space dissolved in `SpaceRegistry`; a periodic job drops the space's graph (pattern similar to `last30d` cleanup).

### Phase 4 — Nanodash Migration

Two contracts to honor on Nanodash's side, then a query rewrite:

- **Publishing:** space-defining nanopub templates must include `<spaceIRI> gen:hasRootDefinition <rootNanopubURI>` (self-referential for new spaces, original-root for updates). Pre-existing space nanopubs without this triple need to be republished to be picked up.
- **Querying:** replace `Space.triggerSpaceDataUpdate()`'s 4-query chain with a single SPARQL query against `spaces`, scoped to the space's named graph. Replace `AbstractResourceWithProfile.GET_VIEW_DISPLAYS` with a query against the materialized validated-display triples — no admin-pubkey gate, no `SERVICE` join. Sketch:

  ```sparql
  SELECT ?display ?template WHERE {
    GRAPH <npa:space/SPACE_REF> {
      ?vd a npa:ValidatedViewDisplay ;
          npa:displayIri ?display ;
          npa:templateUri ?template .
    }
  }
  ```

  Switching to dual confirmation later just adds a second `npa:hasEvidence` BGP triple in the relevant authority queries.
- Drop the pinned-templates / pinned-queries calls; deprecated.

## Bootstrap (existing deployments)

On first deployment with the `spaces` repo enabled, scan `meta` for `gen:Space`-typed nanopubs to discover roots, then scan `full` for nanopubs whose assertions contribute to each discovered space (the same detection logic as the loader uses). For each, extract into the corresponding space graph and run the materializer. Restartable; tracked in the admin repo.

The same catch-up pattern handles the in-stream ordering case where a referencing nanopub arrives before the space-defining one: re-scan after each new space is discovered.

## Key Files

| File | Role |
|------|------|
| `NanopubLoader.java`     | Detection + triple extraction into the `spaces` repo + invalidation propagation |
| `TripleStore.java`       | One-time `spaces` repo init |
| `MainVerticle.java`      | `/spaces` route, `for-space` redirect (graph-addressed) |
| `MetricsCollector.java`  | Known-spaces gauge |
| **New:** `SpaceRegistry.java`     | Known spaces + learned role properties + per-nanopub reverse index; admin-repo persistence |
| **New:** `AuthorityResolver.java` | Closure computation, evidence classification (incl. publisher-agent resolution), view-display validation, materialization write |

## Open / deferred

- **Time-ordered authority enforcement** (vs. closure-based today).
- **Cascading revocation** (vs. sticky).
- **Cryptographic linking** of admin agents to intro nanopubs / pubkeys (currently agent IRIs are taken at face value; the trust-state mirror only validates the *publisher* of authority nanopubs).
- **Dual-confirmation policies** per tier (the materialization already supports this; just flip the policy table and update consumer queries).
- **Multi-registry support** — single registry source assumed.
- **New consumer data needs** — extend the materializer's extraction set rather than reintroducing catch-all raw-nanopub loading. If the extraction set proves chronically incomplete, revisit the choice.

## Verification

- Unit tests for `SpaceRegistry` and `AuthorityResolver` (closures, evidence classification, view-display validation, idempotence of rematerialize, fast-path correctness under concurrent extracts).
- Integration tests with fixture nanopubs covering: space definition with `gen:hasRootDefinition`, admin chain depth ≥ 2, role definition + matching role assignment, ViewDisplay published by an admin vs. a non-admin (only the former materialized), supersession of an admin grant, trust-state pointer flip, root retraction + space dissolution.
- End-to-end against a local `nanopub-registry`: confirm the `spaces` repo's space graph holds the expected `RoleAssignment` and `ValidatedViewDisplay` triples, with `npa:viaNanopub` and `npa:viaPublisherAgent` populated.
- Nanodash side: a Space page renders correctly using only the new `spaces`-repo queries, with no `SERVICE` join to `trust`.
