# Plan: Space Repositories in Nanopub Query (v2)

Redesign of [plan-space-repositories.md](plan-space-repositories.md). Context, Space Identity, and Role Types are unchanged — see v1.

## What's In the `spaces` Repo

Two things in one repo:

1. **Raw nanopubs** of a small set of predefined types — `gen:Space`, role-definition nanopubs, role-assignment nanopubs, `gen:ViewDisplay`, `gen:ResourceView` — loaded whole (all four graphs) and kept indefinitely. Exact type IRIs for role definitions / assignments: tbd.
2. **One validated-links graph** (fixed IRI, tbd) holding the authority closures, validated role assignments, and validated view displays that hold under the current trust state. Each entry carries `viaNanopub` and the resolved publisher agent, so consumers never `SERVICE`-join to `trust`.

Profile fields stay in the raw assertions; the validated graph holds pointers + validated links only.

## Update flow

One path. Rebuild the validated-links graph atomically on any trigger:
- Raw nanopub of a predefined type loaded or invalidated.
- Trust-state pointer flips.

Triggers coalesce into a pending-rebuild flag; a worker drains it. Each rebuild: `CLEAR GRAPH` + bulk insert in one serializable transaction. Readers see either the old full graph or the new one.

No fast/slow split. No per-space graphs. No historical materializations.

## Authority

Same closure + evidence model as v1: admin closure seeded by the root's `gen:hasAdmin`; maintainer closure over registered `gen:MaintainerRole`-typed predicates, seeded by admin grants. Each matched nanopub classified as `authorityEvidence` and/or `selfEvidence`. Policy table decides per tier which kinds suffice; applied at materialization time.

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
