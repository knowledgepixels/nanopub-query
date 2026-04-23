# Plan: Space Repositories in Nanopub Query

## Context

Today Nanodash computes Space membership client-side via a chain of API queries (`GET_ADMINS` → `GET_SPACE_MEMBER_ROLES` → `GET_SPACE_MEMBERS` → `GET_VIEW_DISPLAYS`) against `full`/`meta`. It's slow and puts the membership logic in the wrong place. This plan moves that calculation server-side, into a new `spaces` repo.

## Space Identity

Prefixes: `gen:` = `<https://w3id.org/kpxl/gen/terms/>`, `npa:` = `<http://purl.org/nanopub/admin/>`.

A space is identified by a **space ref** of the form `<NPID>_<SPACEIRIHASH>`:
- **NPID** — trusty-URI artifact code of the **root nanopub**.
- **SPACEIRIHASH** — `Utils.createHash(<Space IRI>)`, same pattern as `type_<HASH>`.

Every space-defining nanopub declares its root via `gen:hasRootDefinition` (self-referential for the root itself; pointing at the original root for any update). A `gen:Space`-typed nanopub without this triple is ignored.

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
   - `gen:RoleAssignment` (new)
   - `gen:ViewDisplay`
   - `gen:ResourceView`

   For backwards compatibility, nanopubs using alternative role-assignment predicates — `gen:hasTeamMember` (`<https://w3id.org/kpxl/gen/terms/hasTeamMember>`), Wikidata P1344 (`<http://www.wikidata.org/entity/P1344>`), and more to be added — are treated as `gen:RoleAssignment` nanopubs. Temporary.

2. **One validated-links graph** (fixed IRI, tbd) holding the authority closures, validated role assignments, and validated view displays that hold under the current trust state. Each entry carries `viaNanopub` and the resolved publisher agent, so consumers never `SERVICE`-join to `trust`.

Profile fields stay in the raw assertions; the validated graph holds pointers + validated links only.

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
