# Canary checklist — space-ref 1.15.x redeploy (post-Phase 1.5)

**Purpose:** the 1.15.0 revert happened because the canary verified *state shape* but never
exercised the **published query read path** (`/api`). nanopub-java's `QueryCall` races the
whole fleet and accepts the first 2xx, so a single instance that returns valid-looking empty
(or doubled) rows for a published query poisons consumers fleet-wide. This checklist makes
the read-path canary concrete: run each published query that touches the `npa:` state
structural edges via `/api` on the canary, and diff against a 1.14.4 reference instance.

See `doc/report-2026-06-12-mixed-fleet-spaceref-breakage.md` and the Phase 1.5 section of
`doc/design-spaceref-isolation.md`.

## Scope: published queries that read `npa:` state structural edges

Enumerated from `query.knowledgepixels.com/repo/full` (grlc query nanopubs whose SPARQL
references `isMaintainedBy` / `hasMaintainedResource` / `isSubSpaceOf` / `hasSubSpace` /
`sameAsSpace`), deduped to the **latest version** per family, then filtered to the ones that
actually read the `npa:`-namespaced edges in the **state graph** (not `gen:`-namespaced edges
in assertion graphs — those are unaffected by this change).

### A. Broken on ref-only 1.15.0 → RESTORED by Phase 1.5 (must return rows again)

These gate on the bare Space IRI via an admin `npa:forSpace` join or an IRI-subject edge
bind. On ref-only 1.15.0 they returned empty; Phase 1.5 must make them match 1.14.4.

| Query family | Latest np | Param | Edge / gate |
|---|---|---|---|
| Get view displays | `RAy49uUd2fPLHJAZ_7QKDtIDVgqaQ589OgQhMwNamKy-4` | `resource` | `isMaintainedBy` + `forSpace` admin gate |
| List view displays | `RAe63temfrauWIRVIpGa5booRJgioepZSg8t54lgMxmNo` | `resource` | `isMaintainedBy` + `forSpace` admin gate |
| Get preset views | `RApvCh39fmAuEO4S8XbzwY4_jKkmB44lIRdp_QTINF5UU` | `resource` | `isMaintainedBy` + `forSpace` admin gate |
| List preset assignments | `RAZYHKDeAsHTPN5IsXNKa0rZwmhiWklXcnxOU7IBS4vtk` | `resource` | `isMaintainedBy` + `forSpace` admin gate |
| View/preset test | `RAj2bKNyyK2WMh5GM43OHx3zU6CDoOPQkDmXSRt0sL8Ow` | `resource` | `isMaintainedBy` + `forSpace` admin gate |
| List sub-spaces of a space | `RAeATx6AKEtCllXCfsBlXlA1nov1SZXuurnKOIW8qe7o8` | `space` | `?spaceIRI npa:hasSubSpace ?sub` (IRI-subject) |
| List maintained resources of a space | `RAKTTYw5VcuK0aIRlTvSEVYFPA7mH3Yq_nKnC1qcpzHiI` | `space` | `?spaceIRI npa:hasMaintainedResource ?r` (IRI-subject) |

**Pass criterion:** for representative params, canary row count == 1.14.4 reference row count
(and non-empty where the reference is non-empty).

### B. DOUBLING introduced by Phase 1.5 — RESOLVED (harmless; no action needed)

| Query family | Latest np | Param | Behavior |
|---|---|---|---|
| Get sub-space links | `RAWgoQbP9_B9h3Bnwd1FGYX1gLYPyZFOxaeqIeA3TTPSU` | _(none)_ | `select ?child ?parent { ?child npa:isSubSpaceOf ?parent }` — **unconstrained** enumeration. Dual-emit returns **both** the ref-to-ref and IRI-to-IRI edge. Measured on the localhost canary: **304 rows vs 59 on 1.14.4 (5.15×)**, the extra 245 being ref-shaped (`…/admin/space/…`). |

**Resolution (option 2 — consumer tolerance, confirmed 2026-06-13):** the only live consumer
is `SpaceRepository.populateSubspaceRelations` in Nanodash (branch `feat/magic-query-params`,
the co-release candidate; released 4.28.0 doesn't read this repo at all). It resolves both
endpoints through `spacesById` (keyed by space IRI via `byId.put(space.getId(), space)`) and
`continue`s when either is `null`:

```java
Space child  = spacesById.get(r.get("child"));
Space parent = spacesById.get(r.get("parent"));
if (child == null || parent == null) continue;   // ref-shaped IRIs aren't keys → dropped
subspaceMap.computeIfAbsent(parent, k -> new HashSet<>()).add(child);
```

The ref namespace (`http://purl.org/nanopub/admin/space/…`) is disjoint from space IRIs, so
the 245 ref-shaped rows are silently dropped; the `HashSet` dedups regardless. The doubling
is **cosmetic** (extra wire payload only, 304 vs 59 rows) — no correctness impact, **no
bridge-supersede required** for rollout. A supersede remains a nice-to-have if the wire cost
ever matters, and is the natural Phase 2 form anyway.

### C. False positives (no action — read `gen:` edges in assertion graphs, not `npa:` state)

`Get maintained resources`, `Get maintained resources from spaces repo`, `Get sub-resources
of Space or Maintained Resource`, `Get collections for resource` — these match on
`gen:isMaintainedBy` inside the nanopub assertion graph (`graph ?a`), which Phase 1/1.5 does
not touch. Unaffected; listed here so a future audit doesn't re-flag them.

## Procedure

1. Deploy 1.15.x (this PR) to **one** canary instance with `FORCE_RESYNC`; wait for READY +
   stable load counter.
2. Capture state-shape baseline as before (`spaceref-baseline-capture.py <canary>/repo/spaces`)
   and diff vs `spaceref-baseline-nanodash-postresync.json` — admin/role parity, ref-keying,
   per-ref fan-out. (Necessary but **not sufficient** — that's the lesson.)
3. **Read-path diff (the new, mandatory step):** for each query in §A, hit
   `https://<canary>/api/<np>/<slug>?<param>=<value>` and the same on a 1.14.4 reference
   (`query.knowledgepixels.com` / `query.petapico.org`), compare row counts. Representative
   params: `resource=https://w3id.org/spaces/knowledgepixels/nanodash/r/home` (a maintained
   resource), `space=https://w3id.org/spaces/knowledgepixels/nanodash`. Note `/api` results
   can be cached — re-run after the resync settles.
4. Resolve §B before opening the canary to the public rotation (or accept option 2/3 with a
   logged rationale).
5. Only then roll 1.15.x to the remaining instances.

## One-shot diff helper

```python
import json, urllib.request
CANARY="https://<canary-host>"; REF="https://query.knowledgepixels.com"
SLUG={"RAy49uUd2fPLHJAZ_7QKDtIDVgqaQ589OgQhMwNamKy-4":("get-view-displays","resource",
      "https://w3id.org/spaces/knowledgepixels/nanodash/r/home")}  # extend with §A rows
def rows(host, np, slug, p, v):
    u=f"{host}/api/{np}/{slug}?{p}={urllib.parse.quote(v,safe='')}"
    req=urllib.request.Request(u, headers={"Accept":"text/csv"})
    with urllib.request.urlopen(req, timeout=60) as r:
        return max(0, r.read().decode().count("\n")-1)
for np,(slug,p,v) in SLUG.items():
    print(slug, "canary", rows(CANARY,np,slug,p,v), "ref", rows(REF,np,slug,p,v))
```
