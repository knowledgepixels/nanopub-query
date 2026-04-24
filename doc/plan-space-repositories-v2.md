# Plan: Space Repositories in Nanopub Query

## Context

Today Nanodash computes Space membership client-side via a chain of API queries (`GET_ADMINS` → `GET_SPACE_MEMBER_ROLES` → `GET_SPACE_MEMBERS` → `GET_VIEW_DISPLAYS`) against `full`/`meta`. It's slow and puts the membership logic in the wrong place. This plan moves that calculation server-side, into a new `spaces` repo.

## Space Identity

Prefixes: `gen:` = `<https://w3id.org/kpxl/gen/terms/>`, `npa:` = `<http://purl.org/nanopub/admin/>`.

A space is identified by a **space ref** of the form `<NPID>_<SPACEIRIHASH>`:
- **NPID** — trusty-URI artifact code of the **root nanopub**.
- **SPACEIRIHASH** — `Utils.createHash(<Space IRI>)`, same pattern as `type_<HASH>`.

Every space-defining nanopub declares its root via `gen:hasRootDefinition` (self-referential for the root itself; pointing at the original root for any update).

For backwards compatibility during the transition phase, a `gen:Space`-typed nanopub *without* a `gen:hasRootDefinition` triple is still accepted: the declaration nanopub is treated as its own root. Each such declaration therefore produces its own space ref (per declaration, not shared across declarations for the same Space IRI). To be phased out once existing deployments have republished with explicit roots.

## Role types

Four `gen:SpaceMemberRole` subclasses:

| Type | Predicate / how it's granted |
|------|------------------------------|
| `gen:AdminRole`      | Single hardcoded instance `<https://w3id.org/np/RA_eEJjQbxzSqYSwPzfjzOZi5sMPpUmHskFNsgJYSws8I/adminRole>`, defining `gen:hasAdmin`. No other admin roles. |
| `gen:MaintainerRole` | User-defined predicates declared `rdf:type gen:MaintainerRole`. Nothing is hardcoded at this tier. |
| `gen:MemberRole`     | User-defined predicates declared `rdf:type gen:MemberRole`. |
| `gen:ObserverRole`   | User-defined predicates declared `rdf:type gen:ObserverRole`. **Default** when a role definition doesn't declare a type. |

Per-tier privilege enforcement is Nanodash's concern, out of scope here.

## What's In the `spaces` Repo

Two things in one repo:

1. **Raw nanopubs** of the following predefined types, loaded whole (all four graphs) and kept indefinitely:
   - `gen:Space`
   - `gen:hasRole`
   - `gen:SpaceMemberRole`
   - `gen:RoleInstantiation` (new)

   For backwards compatibility, nanopubs whose assertion uses any of the following currently-used properties are also treated as `gen:RoleInstantiation` nanopubs (temporary; to be dropped at a later point):

   - `<http://www.wikidata.org/entity/P1344>`
   - `<http://www.wikidata.org/entity/P463>`
   - `<http://www.wikidata.org/entity/P710>`
   - `<http://www.wikidata.org/entity/P823>`
   - `<https://w3id.org/fair/3pff/has-event-assistant>`
   - `<https://w3id.org/fair/3pff/has-event-facilitator>`
   - `<https://w3id.org/fair/3pff/participatedAsFacilitatorAssistantIn>`
   - `<https://w3id.org/fair/3pff/participatedAsImplementerAspirantIn>`
   - `<https://w3id.org/fair/3pff/participatedAsParticipantIn>`
   - `<https://w3id.org/kpxl/gen/terms/hasAdmin>`
   - `<https://w3id.org/kpxl/gen/terms/hasObserver>`
   - `<https://w3id.org/kpxl/gen/terms/hasProjectLead>`
   - `<https://w3id.org/kpxl/gen/terms/hasTeamMember>`
   - `<https://w3id.org/kpxl/gen/terms/plansToAttend>`

2. **One extraction graph**, `npa:spacesGraph`, holding add-only summary triples for each loaded space-relevant nanopub and each invalidation. No validation happens at this layer — everything that matches a predefined type is written here, load-number-stamped.

3. **One space-state graph**, `npass:<trustStateHash>`, holding the validated closures under the current trust state (see [Space state graph](#space-state-graph)). Rebuilt incrementally via SPARQL UPDATE driven by load-number deltas.

Profile fields stay in the raw nanopub assertions; consumers JOIN from extraction triples to raw assertion graphs via the nanopub IRI.

Every extraction uses a dedicated subject IRI per entry — derived from the originating nanopub's artifact code so subjects never collide with user nanopub IRIs, role IRIs, or anything else a nanopub might declare types on. Working prefixes:

- `npari:` = `<http://purl.org/nanopub/admin/roleinst/>` — subject for `gen:RoleInstantiation` entries
- `npara:` = `<http://purl.org/nanopub/admin/roleassign/>` — subject for `gen:hasRole` (role-attachment) entries
- `npard:` = `<http://purl.org/nanopub/admin/roledecl/>` — subject for `npa:RoleDeclaration` entries (extracted from `gen:SpaceMemberRole` nanopubs)
- `npainv:` = `<http://purl.org/nanopub/admin/invalidation/>` — subject for `npa:Invalidation` entries

Each entry carries `npa:viaNanopub <originatingNP>` to link back to the source; the stamped load number goes on that nanopub IRI so all of a nanopub's emitted entries share one stamp:

```turtle
  <thisNP> npa:hasLoadNumber <N> .
```

where `N` is the nanopub-query load counter at extraction time. The `spaces` repo's `npa:graph` also tracks `npa:thisRepo npa:currentLoadCounter <N>` so the materializer knows the current horizon.

### Triples added per `gen:Space` nanopub

Working prefix: `npas:` = `<http://purl.org/nanopub/admin/space/>`. A space ref `<NPID>_<SPACEIRIHASH>` becomes the IRI `npas:<NPID>_<SPACEIRIHASH>`.

```turtle
GRAPH npa:spacesGraph {
  npas:<spaceRef> a npa:SpaceRef ;
                  npa:spaceIri     <spaceIRI> ;
                  npa:rootNanopub  <rootNP> ;    # defaults to <thisNP> if the nanopub has no gen:hasRootDefinition
                  npa:hasDefinition <thisNP> ;
                  npx:signedBy     <publishingAgent> ;
                  npa:pubkeyHash   "<pubkeyHash>" .
}
```

Each loaded `gen:Space` nanopub contributes its own `npx:signedBy` and `npa:pubkeyHash` values, so multiple defining nanopubs (root + updates) accumulate multiple signer/pubkey pairs on the same `npas:<spaceRef>` — consumers can check validity of each declaration by matching signer/pubkey against the trust repo.

Trust seeding is per space ref, so in the rootless transition case each declaration becomes its own root and creates its own space ref. During the transition, Nanodash can default to surfacing the earliest- or latest-defined space ref per Space IRI.

For every `gen:Space` nanopub carrying one or more `gen:hasAdmin` triples in its assertion, additionally emit one `gen:RoleInstantiation` entry covering all asserted admins as multi-valued `npa:forAgent`:

```turtle
  npari:<artifactCode> a gen:RoleInstantiation ;
                       npa:forSpace        <spaceIRI> ;
                       npa:regularProperty gen:hasAdmin ;
                       npa:forAgent        <adminAgent1>, <adminAgent2> ;
                       npa:viaNanopub      <thisNP> ;
                       npx:signedBy        <publishingAgent> ;
                       npa:pubkeyHash      "<pubkeyHash>" .
```

where `<artifactCode>` is the trusty-URI artifact code of `<thisNP>`.

So admins asserted in any `gen:Space` nanopub (root or update) show up in the same SPARQL pattern consumers use for ordinary admin role instantiations.

If the loaded nanopub is additionally the space's root — detectable by `npa:rootNanopub` equalling `npa:hasDefinition` for the same space ref, i.e. `<thisNP> = <rootNP>` — also emit one triple per `gen:hasAdmin` target:

```turtle
  npas:<spaceRef> npa:hasRootAdmin <adminAgent> .
```

These are the trust seed for the admin closure — trusted by construction because the root's NPID is part of the space ref, so no publisher-agent validation is needed. In the rootless transition case the nanopub is its own root, so the same rule applies and its admins seed the per-declaration space ref.

Profile fields (description, dates, alt IDs, declared subtypes) stay in the raw nanopub's assertion graph — consumers JOIN via `npa:hasDefinition`. Names are working titles.

### Triples added per `gen:hasRole` nanopub

All attachments are emitted into `npa:spacesGraph`; validation (publisher must be in the admin closure of the target space) happens in the space-state-graph materialization step.

```turtle
GRAPH npa:spacesGraph {
  npara:<artifactCode> a gen:RoleAssignment ;
                       npa:forSpace    <spaceIRI> ;
                       gen:hasRole     <roleIri> ;
                       npa:viaNanopub  <thisNP> ;
                       npx:signedBy    <publishingAgent> ;
                       npa:pubkeyHash  "<pubkeyHash>" .
}
```

Prefix: `npx:` = `<http://purl.org/nanopub/x/>`. `npa:forSpace` points to the Space IRI (not the space-ref form), as used in the source nanopub's assertion. The attached `<roleIri>` is the IRI of a role instance defined in some `gen:SpaceMemberRole` nanopub; consumers JOIN against that def for the role's predicates and tier.

### Triples added per `gen:SpaceMemberRole` nanopub

Role instances are *embedded* (not introduced) in their defining nanopub, so each one mints a new role IRI. The role-defining triples are summarized into `npa:spacesGraph` as an `npa:RoleDeclaration` entry:

```turtle
GRAPH npa:spacesGraph {
  npard:<artifactCode> a npa:RoleDeclaration ;
                       npa:role               <roleIri> ;
                       npa:hasRoleType        <gen:MaintainerRole | gen:MemberRole | gen:ObserverRole> ;
                       gen:hasRegularProperty <regularPropIRI> ;   # one per occurrence
                       gen:hasInverseProperty <inversePropIRI> ;   # optional, one per occurrence
                       npa:viaNanopub         <thisNP> .
}
```

Prefix: `gen:` = `<https://w3id.org/kpxl/gen/terms/>`. `npa:role` carries the actual role IRI for consumer JOINs; `npa:hasRoleType` carries the tier class.

The tier value for `npa:hasRoleType` comes from whatever subclass of `gen:SpaceMemberRole` (`gen:MaintainerRole`, `gen:MemberRole`, or `gen:ObserverRole`) the role is typed as in the source assertion. If the assertion declares none, default to `gen:ObserverRole`.

**Embedding must be checked:** only emit these triples if `<roleIri>` starts with `<thisNP>`'s IRI (i.e. the role is genuinely embedded in this nanopub). Otherwise ignore — a role IRI outside the nanopub's namespace is not a valid embedded mint.

Label / name / title / assignment-template pointer stay in the raw assertion; consumers JOIN via `npa:viaNanopub` or by matching the `<roleIri>` directly against the raw assertion graph.

### Triples added per `gen:RoleInstantiation` nanopub

All instantiations are emitted into `npa:spacesGraph`; validation happens in the space-state-graph materialization step.

```turtle
GRAPH npa:spacesGraph {
  npari:<artifactCode> a gen:RoleInstantiation ;
                       npa:forSpace        <spaceIri> ;
                       npa:regularProperty <regularPropIRI> ;   # iff regular direction was used
                       # OR
                       npa:inverseProperty <inversePropIRI> ;   # iff inverse direction was used
                       npa:forAgent        <agent> ;
                       npa:viaNanopub      <thisNP> ;
                       npx:signedBy        <publishingAgent> ;
                       npa:pubkeyHash      "<pubkeyHash>" .
}
```

Exactly one of `npa:regularProperty` or `npa:inverseProperty` is emitted per instantiation, matching the predicate direction used in the source nanopub's assertion. Consumers JOIN through the corresponding `npa:RoleDeclaration` (via `gen:hasRegularProperty` / `gen:hasInverseProperty`) to resolve the role IRI and tier.

### Triples added per invalidation

No independent space-relevance check on invalidators. `NanopubLoader.java:578-586` already runs a per-invalidator loop that looks up the invalidated nanopub's types from the `meta` repo (`NPX.HAS_NANOPUB_TYPE`) and propagates into each type-specific repo. Hook into that loop: if any of the target's types is space-relevant, emit an `npa:Invalidation` entry. Detection of the invalidator itself is by the `npx:invalidates` / `npx:retracts` / `npx:supersedes` predicate in the assertion.

Each invalidation is loaded as an add-only event into `npa:spacesGraph`, stamped with the load number like any other extraction:

```turtle
GRAPH npa:spacesGraph {
  npainv:<artifactCode> a npa:Invalidation ;
                        npa:invalidates  <invalidatedNP> ;
                        npa:viaNanopub   <invalidatingNP> ;
                        npx:signedBy     <publishingAgent> ;
                        npa:pubkeyHash   "<pubkeyHash>" .
}
```

Second-order invalidations are ignored — an invalidation whose target is itself already invalidated has no effect.

## Space state graph

A single `npass:<trustStateHash>` graph (prefix `npass:` = `<http://purl.org/nanopub/admin/spacestate/>`) holds the current validated state. Current-state pointer in `npa:graph`:

```turtle
GRAPH npa:graph {
  npa:thisRepo npa:hasCurrentSpaceState npass:<trustStateHash> .
}
```

The graph contains two parts:

1. **Mirrored trust state** — copy of the approved + non-contested `(agent, pubkey)` rows from `npat:<trustStateHash>`, inline. Inline rather than federated so per-tier UPDATEs join purely locally.
2. **Validated closures** — `gen:RoleInstantiation` and `gen:RoleAssignment` entries copied over from `npa:spacesGraph` once they pass tier validation, one per validated nanopub. Each entry keeps its `npari:` / `npara:` subject IRI and carries `npa:viaNanopub <originatingNP>`, so invalidation cleanup matches entries by their originating nanopub.

Progress counter for incremental updates:

```turtle
npass:<trustStateHash> npa:processedUpTo <N> .
```

## Update flow

Incremental and add-only at the raw layer; incremental at the space-state layer too, with full rebuild only on trust-state flips.

### First build (new trust state)

Triggered by a trust-state flip (the trust repo's `npa:hasCurrentTrustState` pointer changes to a new hash). Sequence:

1. Mirror approved + non-contested rows from `npat:<newHash>` into a fresh `npass:<newHash>` graph.
2. Run the per-tier UPDATE loops from scratch (processedUpTo = 0):
   - **Admin**: seed from `npa:hasRootAdmin`; fixed-point INSERT every `gen:RoleInstantiation` with `npa:regularProperty gen:hasAdmin` whose `npa:pubkeyHash` resolves (via the mirrored rows) to an agent already in the admin set for that space.
   - **`gen:hasRole` validation**: INSERT every `gen:RoleAssignment` whose publisher is in the admin set of the target space. These validated attachments define which role predicates are active per space.
   - **Maintainer**: INSERT every `gen:RoleInstantiation` whose predicate is the `gen:hasRegularProperty` (or `gen:hasInverseProperty`) of an `npa:RoleDeclaration` with `npa:hasRoleType gen:MaintainerRole` attached to the target space, and whose publisher is in the admin set (or existing maintainer set). Fixed-point.
   - **Member / Observer**: same pattern, tiered publisher constraints. Observer also accepts self-evidence (publisher = assignee).
3. Set `processedUpTo` to the current `currentLoadCounter`.
4. Flip the current-space-state pointer to `npass:<newHash>`.
5. `DROP GRAPH npass:<oldHash>`.

### Incremental update (same trust state, new raw activity)

Triggered by a space-relevant nanopub load or invalidation (which bumps `currentLoadCounter`). Runs as a single cycle bounded by `(processedUpTo, currentLoadCounter]`:

1. Apply invalidation DELETEs — for each new `npa:Invalidation` entry, remove the space-state entry whose `npa:viaNanopub` points at the invalidated nanopub:
   ```sparql
   DELETE { GRAPH npass:<ts> { ?entry ?p ?o . } }
   WHERE {
     GRAPH npa:spacesGraph {
       ?inv a npa:Invalidation ;
            npa:invalidates ?invalidatedNP ;
            npa:viaNanopub ?invalidatingNP .
       ?invalidatingNP npa:hasLoadNumber ?ln .
       FILTER (?ln > ?lastProcessed)
     }
     GRAPH npass:<ts> {
       ?entry npa:viaNanopub ?invalidatedNP ;
              ?p ?o .
     }
   }
   ```
2. Apply the tier INSERTs, each scoped by `FILTER(?ln > ?lastProcessed)` on the raw triple's `npa:hasLoadNumber` and `FILTER NOT EXISTS` against the current space-state contents. Iterate to fixed point within the cycle.
3. Bump `processedUpTo` to the max load number consumed.

Loader and materializer decouple: the loader keeps appending raw triples with incrementing load numbers, the materializer processes deltas in small cycles. No global CLEAR.

Triggers coalesce into a pending-rebuild flag; if a trigger arrives mid-cycle, the flag re-fires and another cycle runs after commit.

### Revocation semantics

Sticky and non-cascading: invalidating a grant removes only the derivation keyed on that nanopub. Other grants to the same agent persist. Second-order invalidations are ignored.

### Trust-state flip always means full rebuild

Load-number incremental only applies *within* a trust state. A flip changes which pubkeys map to which agents, invalidating all derivations. `processedUpTo` restarts at 0 on the new graph.

## Implementation phases

1. **Raw loading** — `TripleStore` init, loader writes full nanopubs of predefined types into `spaces` and emits add-only extraction triples (including `npa:Invalidation` entries) into `npa:spacesGraph` with `npa:hasLoadNumber` stamps.
2. **Materialization** — new `AuthorityResolver` drives per-tier SPARQL UPDATE loops on load-number deltas for incremental updates; runs full first-build on trust-state flips, manages the `npass:<trustStateHash>` pointer and old-graph cleanup.
3. **Routes/metrics/invalidation** — `/spaces` listing, `for-space` redirect, gauges (rebuild duration, delta size, `processedUpTo` lag).
4. **Nanodash migration** — publish with `gen:hasRootDefinition` and the predefined type IRIs; replace the 4-query chain with one query against the current `npass:*` graph; drop `isAdminPubkey` gate and pinned templates/queries.

## Bootstrap

On first deployment, scan `meta` / `full` for the predefined types, load each matching nanopub whole into `spaces`, run one rebuild.

## Key files

| File | Role |
|------|------|
| `NanopubLoader.java` | Type-match + full-nanopub write + trigger rebuild |
| `TripleStore.java` | `spaces` repo init |
| `MainVerticle.java` | `/spaces` route, `for-space` redirect |
| `MetricsCollector.java` | Rebuild / link-count gauges |
| **New:** `AuthorityResolver.java` | Closures, evidence classification, publisher-agent resolution, atomic rebuild |

## Verification

- Unit: closures, evidence classification, rebuild idempotence.
- Integration: space definition, admin chain depth ≥ 2, role definition + assignment, supersession, trust-state flip, root retraction.
- End-to-end: Space page renders from `spaces` alone, no `SERVICE` to `trust`.
