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
   - `gen:ViewDisplay`
   - `gen:ResourceView`

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

2. **One validated-links graph**, `npa:spacesGraph`, holding the authority closures, validated role assignments, and validated view displays that hold under the current trust state. Each entry carries `npa:viaNanopub` and the resolved publisher agent, so consumers never `SERVICE`-join to `trust`.

Profile fields stay in the raw assertions; `npa:spacesGraph` holds pointers + validated links only.

### Triples added per `gen:Space` nanopub

Working prefix: `npas:` = `<http://purl.org/nanopub/admin/space/>`. A space ref `<NPID>_<SPACEIRIHASH>` becomes the IRI `npas:<NPID>_<SPACEIRIHASH>`.

```turtle
graph npa:spacesGraph {
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
  <thisNP> a gen:RoleInstantiation ;
           npa:forSpace        <spaceIRI> ;
           npa:regularProperty gen:hasAdmin ;
           npa:forAgent        <adminAgent1>, <adminAgent2> ;
           npx:signedBy        <publishingAgent> ;
           npa:pubkeyHash      "<pubkeyHash>" .
```

So admins asserted in any `gen:Space` nanopub (root or update) show up in the same SPARQL pattern consumers use for ordinary admin role instantiations.

If the loaded nanopub is additionally the space's root — detectable by `npa:rootNanopub` equalling `npa:hasDefinition` for the same space ref, i.e. `<thisNP> = <rootNP>` — also emit one triple per `gen:hasAdmin` target:

```turtle
  npas:<spaceRef> npa:hasRootAdmin <adminAgent> .
```

These are the trust seed for the admin closure — trusted by construction because the root's NPID is part of the space ref, so no publisher-agent validation is needed. In the rootless transition case the nanopub is its own root, so the same rule applies and its admins seed the per-declaration space ref.

Profile fields (description, dates, alt IDs, declared subtypes) stay in the raw nanopub's assertion graph — consumers JOIN via `npa:hasDefinition`. Names are working titles.

### Triples added per `gen:hasRole` nanopub

Only validated attachments are emitted (publisher must resolve to an admin of the target space at materialization time); a nanopub that fails this check produces no triples in `npa:spacesGraph`.

```turtle
graph npa:spacesGraph {
  <thisNP> a gen:RoleAssignment ;
           npa:forSpace    <spaceIRI> ;
           gen:hasRole     <roleIri> ;
           npx:signedBy    <publishingAgent> ;
           npa:pubkeyHash  "<pubkeyHash>" .
}
```

Prefix: `npx:` = `<http://purl.org/nanopub/x/>`. `npa:forSpace` points to the Space IRI (not the space-ref form), as used in the source nanopub's assertion. The attached `<roleIri>` is the IRI of a role instance defined in some `gen:SpaceMemberRole` nanopub; consumers JOIN against that def for the role's predicates and tier.

### Triples added per `gen:SpaceMemberRole` nanopub

Role instances are *embedded* (not introduced) in their defining nanopub, so each one mints a new role IRI. The existing assertion triples are copied verbatim; a single `npa:embeddedIn` triple provides the provenance link.

```turtle
graph npa:spacesGraph {
  <roleIri> a gen:SpaceMemberRole, <gen:MaintainerRole | gen:MemberRole | gen:ObserverRole> ;
            gen:hasRegularProperty <regularPropIRI> ;   # one per occurrence
            gen:hasInverseProperty <inversePropIRI> ;   # optional, one per occurrence
            npa:embeddedIn         <thisNP> .
}
```

Prefix: `gen:` = `<https://w3id.org/kpxl/gen/terms/>`.

The tier `rdf:type` triple (`gen:MaintainerRole`, `gen:MemberRole`, or `gen:ObserverRole`) is copied from the assertion alongside the `gen:SpaceMemberRole` type. If no tier is declared, default to `gen:ObserverRole`.

**Embedding must be checked:** only emit these triples if `<roleIri>` starts with `<thisNP>`'s IRI (i.e. the role is genuinely embedded in this nanopub). Otherwise ignore — a role IRI outside the nanopub's namespace is not a valid embedded mint.

Label / name / title / assignment-template pointer stay in the raw assertion; consumers JOIN via `npa:embeddedIn` or by matching the `<roleIri>` directly against the raw assertion graph.

### Triples added per `gen:RoleInstantiation` nanopub

Only validated instantiations are emitted; a nanopub that fails policy produces no triples in `npa:spacesGraph` (the raw nanopub stays in the repo regardless).

```turtle
graph npa:spacesGraph {
  <thisNP> a gen:RoleInstantiation ;
           npa:forSpace        <spaceIri> ;
           npa:regularProperty <regularPropIRI> ;   # iff regular direction was used
           # OR
           npa:inverseProperty <inversePropIRI> ;   # iff inverse direction was used
           npa:forAgent        <agent> ;
           npx:signedBy        <publishingAgent> ;
           npa:pubkeyHash      "<pubkeyHash>" .
}
```

Exactly one of `npa:regularProperty` or `npa:inverseProperty` is emitted per instantiation, matching the predicate direction used in the source nanopub's assertion. Consumers JOIN through the corresponding `gen:SpaceMemberRole` def (via `gen:hasRegularProperty` / `gen:hasInverseProperty`) to resolve the role IRI and tier.

## Update flow

One path. Rebuild the validated-links graph atomically on any trigger:
- Raw nanopub of a predefined type loaded or invalidated.
- Trust-state pointer flips.

Triggers coalesce into a pending-rebuild flag; a worker drains it. Each rebuild: `CLEAR GRAPH` + bulk insert in one serializable transaction. Readers see either the old full graph or the new one.

No fast/slow split. No per-space graphs. No historical materializations.

## Authority

Admin closure seeded by the root's `gen:hasAdmin`; maintainer closure over registered `gen:MaintainerRole`-typed predicates, seeded by admin grants. Each matched nanopub classified as `authorityEvidence` and/or `selfEvidence`. Policy table decides per tier which kinds suffice; applied at materialization time.

## Implementation phases

1. **Raw loading** — `TripleStore` init, loader writes full nanopubs of predefined types into `spaces`.
2. **Materialization** — new `AuthorityResolver` owns closures, evidence classification, view-display validation, atomic rebuild.
3. **Routes/metrics/invalidation** — `/spaces` listing, `for-space` redirect, gauges, invalidation-triggered rebuild.
4. **Nanodash migration** — publish with `gen:hasRootDefinition` and the predefined type IRIs; replace the 4-query chain with one query against `spaces`; drop `isAdminPubkey` gate and pinned templates/queries.

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
- Integration: space definition, admin chain depth ≥ 2, role definition + assignment, ViewDisplay by admin vs. non-admin, supersession, trust-state flip, root retraction.
- End-to-end: Space page renders from `spaces` alone, no `SERVICE` to `trust`.
