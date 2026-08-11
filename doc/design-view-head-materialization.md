# Slimming `get-view-displays`: measured cost structure and head-version materialization

Status: investigation write-up, 2026-07-30. Measurements taken against
query.nanodash.net (fresh store, 86.5k nanopubs, 915 resource views in the
ResourceView type repo) using the published query
`RActfK6…/get-view-displays` ("ref-scoped") and standalone fragments of it.

## Where the time goes (measured)

| Part | Cost | Notes |
|---|---|---|
| Spaces authority gate (SERVICE `repo/spaces`) | ~0.1 s | fine |
| Branch (a) display patterns (endpoint repo) | small | fine |
| Branch (b) preset SERVICE (`repo/full`) | ~0.1 s | fine |
| Governed-pin sub-select (SERVICE ResourceView repo) | ~0.1 s | fine |
| **Version resolution** (SERVICE ResourceView repo, run-once sub-select) | **1.4–1.8 s nominal — up to 30 s+ under pressure** | dominant, fragile |
| Federation overhead (5 SERVICE hops via in-cluster loopback) | ~0.2 s total | fine |

The version-resolution hop resolves the supersedes-head for **every** view in
the repo on **every** call (~2 ms/view × 915 views), because the run-once
sub-select cannot receive outer bindings (SPARQL evaluates sub-selects
bottom-up). Its per-view work is a closure walk `(npx:supersedes|^npx:supersedes)*`
from a bound start plus a nested `filter not exists` that re-walks the closure
to reject non-newest fork heads — quadratic only within a version chain (chains
are short), so nominally fast.

Two structural problems remain regardless of nominal speed:

1. **It scales linearly with total view count.** 915 views → ~1.6 s today; at
   5–10k views the query alone crosses typical federation timeouts. Every call
   pays for the whole repo to resolve the ~dozen views it actually returns.
2. **Its runtime is state-dependent.** The same fragment measured 1.7 s three
   consecutive times and 30 s+ minutes later on the same store. Closure
   evaluation over the whole repo is one degraded-process regime away from any
   timeout (observed repeatedly on 2026-07-29; see
   knowledgepixels/nanopub-query#142 aftermath).

## Dead end, so nobody retries it: pure-SPARQL aggregation rewrite

Replacing the nested-FNE "newest head" anti-join with a single closure pass +
`GROUP BY ?vnp` / `MAX(concat(date, ">", head))` aggregation was measured at
**23–40 s with truncated results** (vs 1.7 s original). The sub-select cannot
be scoped to the outer bindings, so the closure runs **unbound repo-wide**:
every chain member × every component member. The existing query shape — bound
start points, closure per candidate — is already the right one for RDF4J's
evaluator. There is no significant slimming available inside SPARQL alone.
(These unbound-closure test queries were also heavy enough to destabilize the
shared production rdf4j — candidate-query benchmarking should use the test
server or a local instance, not a live one.)

## Second dead end (measured): kind-based resolution in pure SPARQL

Since the loader already materializes supersession as invalidation markers
(`v2 npx:supersedes v1` also writes `v2 npx:invalidates v1` into
`npa:graph`), "not validly invalidated" already means "neither retracted nor
superseded" — each version chain has exactly one alive member, its head. That
suggests replacing the closure entirely: resolve `?refView` via its
`dct:isVersionOf ?kind` to "the alive view of the same kind, newest-wins"
(the same pattern the governed-pin branch uses).

Semantically this is equivalent for kind-carrying views (fork races resolve
by newest-wins; a validly retracted head makes the whole chain unresolvable,
same as the closure; cross-pubkey supersession converges to the same winner;
kind-squatting exposure is comparable to supersedes-squatting). Coverage
measured on the ResourceView repo: **844 of 922 views carry a kind** — 78
legacy views would fall back to displaying their referenced version as-is.

Performance, however, is worse, not better: **9–25 s vs the closure's
1.7 s** (both FNE-inside-graph and FNE-outside-graph formulations). The
bottleneck was never the closure — bound-start chain walks are cheap — it is
the *unbound repo-wide sub-select* that any run-once rewrite requires, and
the kind-join + alive-check gives the planner worse join orders than the
closure shape at this scale. Together with the aggregation dead end above:
the current query is a local optimum for query-time resolution; every
rewrite attempt lost to it.

(Benchmarking note, learned the hard way twice: these experimental unbound
sub-select queries destabilized the shared production rdf4j both times they
were run against it — a 25 s query on a hot store can tip the process into
the degraded regime. Candidate-query benchmarking must run against a local
docker stack or the test server, never a live instance.)

## Recommended fix: materialize head-version pointers at load time

The kind-based insight above simplifies the materialization design: instead
of chain-walking `npa:hasHeadVersion` per member, the loader can maintain a
**per-kind current-version pointer**,

```
<kind>  npa:hasCurrentVersion  <head-view> .
```

recomputed whenever a kind-carrying view nanopub or an invalidation of one
is loaded (alive-set of one kind = a handful of nanopubs; newest-wins,
lexical tie-break). The query side becomes a two-hop point lookup:
`?refView dct:isVersionOf ?kind . ?kind npa:hasCurrentVersion ?latestView`.
Legacy kind-less views (78 today, shrinking) keep the closure as fallback or
resolve to themselves. The member-level `npa:hasHeadVersion` variant below
remains the alternative if kind-less coverage matters.

Precedent: `npa:hasGoverningSpaceRef` (#130) and the preset materialization
(#121) — both moved per-query derivation into load-time admin triples for the
same reason.

**New admin triple** in `npa:graph`, written wherever the nanopub's regular
admin triples go (at minimum the per-type repos and `full`):

```
<member-np>  npa:hasHeadVersion  <head-np> .
```

for every member of a supersedes version chain, where `<head-np>` is the
chain's current head under exactly the query's rules: not superseded, not
validly invalidated (invalidator signed with the head's own pubkey), newest
`dct:created` among fork heads (tie-break: lexically largest IRI, matching the
`max(concat(str(?date), ">", str(?np)))` idiom).

**Maintenance hooks** (all already exist as code paths in `NanopubLoader`):

- Loading a nanopub with `npx:supersedes` edges: walk its chain component
  (bound closure inside the loader — cheap, chains are short), compute the
  head, rewrite `npa:hasHeadVersion` for all members (delete + insert inside
  the same transaction style as count/checksum maintenance).
- Loading an invalidation of a chain member (forward and reverse invalidator
  propagation paths): recompute the component head the same way — this covers
  "head gets retracted → head reverts to predecessor", which any lazy scheme
  gets wrong.
- Reconciliation (`ShardReconciler`) repairs re-run the loader, so repaired
  shards heal their pointers for free.

**Backfill**: existing data needs a one-off pass (compute heads per component,
stamp members). Options: piggyback on a full-history reconciler sweep with a
version bump, or a standalone admin task. Fresh resyncs get it automatically
once the loader writes the triples.

**Query afterwards**: the whole version-resolution optional collapses to

```sparql
optional {
  ?vnp npx:embeds ?refView ; npa:hasHeadVersion ?latestNp .
  ?latestNp npx:embeds ?latestView ; np:hasAssertion ?va .
  graph ?va { ?latestView a gen:ResourceView .
              optional { ?latestView dct:isVersionOf ?viewKindOptional . } }
}
```

— O(referenced views) point lookups, no closure, no repo-wide scan, no
plan-fragility, constant cost as the repo grows. Expected total query time
well under 0.5 s, dominated by the (already cheap) other hops. The governed-pin
sub-select is unaffected and stays as is.

**Sequencing**: (1) implement + release materialization in nanopub-query,
(2) backfill / resync all instances, (3) author the new query version via
nanopub-skill (test unpublished against live instances with the
`_nanopub_trig` mechanism), (4) publish and switch Nanodash. The old query
version keeps working throughout (the closure path remains valid).

## Interim option (independent, Nanodash-side)

Split resolution out of the hot path: `get-view-displays` without the
resolution optional (fast, stable), plus per-view latest-version lookups
through Nanodash's `ApiCache` (resolutions change rarely; cache hits make the
N+1 pattern cheap). Worth doing only if materialization is far off — it adds
client complexity that the materialized triple makes unnecessary.
