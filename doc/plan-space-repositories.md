# Plan: Space Repositories in Nanopub Query

## Context

**Problem:** Nanodash currently performs complex, multi-step Space/member calculation client-side. When a Space page loads, it executes a chain of API queries against nanopub-query (GET_ADMINS -> GET_SPACE_MEMBER_ROLES -> GET_SPACE_MEMBERS -> GET_VIEW_DISPLAYS), each depending on results from the previous. This is slow, fragile, and puts the membership logic in the wrong place.

**Goal:** Move Space/member calculation into Nanopub Query by giving each Space its own RDF4J repository (`space_<spaceRef>`), similar to the existing `type_<HASH>` and `pubkey_<HASH>` dynamic repos. The space repo would contain all nanopubs relevant to that space, enabling efficient direct SPARQL queries instead of the current multi-query chain. Like `last30d`, nanopubs would need to be un-loaded when memberships change or spaces dissolve.

## Space Identity Model

Prefix used in this document: `gen:` = `<https://w3id.org/kpxl/gen/terms/>`.

Every space is uniquely identified by a **space ref** of the form `NPID_SPACEIRIHASH`, where:

- **NPID** is the artifact code (e.g. `RA...`) of the **root nanopub** — the foundational nanopub for the space. Since artifact codes are cryptographic hashes of content, they are globally unique and immutable.
- **SPACEIRIHASH** is `Utils.createHash(<Space IRI>)` — computed from the Space IRI, following the same hashing pattern already used for `type_<HASH>` repos (`Utils.createHash(typeIri)`). A single root nanopub can define multiple spaces by declaring multiple Space IRIs.

Conflicts are eliminated by construction: no two distinct root nanopubs share an artifact code, so no two distinct spaces share a space ref. The space ref is used directly as the repo name suffix: `space_<spaceRef>` = `space_<NPID>_<SPACEIRIHASH>`. No additional hashing — the space ref is short enough (~108 chars) to serve as the repo name directly, and human-decodable (you can recover the NPID by splitting on `_` after the `space_` prefix).

### Root Nanopub Resolution

Every space-defining nanopub (original or update) declares its root via the predicate `gen:hasRootDefinition` in the assertion graph:

```turtle
<spaceIRI> gen:hasRootDefinition <rootNanopubURI> .
```

- For the root nanopub itself, this is self-referential: `<spaceIRI> gen:hasRootDefinition <thisNanopubURI>` (resolved at trusty-URI minting time).
- For any update (whether linked via `np:supersedes` or a "just overriding" republish), the same triple points back at the original root's URI.

This means **every space-defining nanopub self-describes its root** — no chain walking, no ordering dependency, no publisher-scoping needed. Updates arriving out of order still produce the correct space ref immediately.

**Required predicate:** a nanopub of type `gen:Space` is only recognized as a space-defining nanopub if it contains a `gen:hasRootDefinition` triple for its declared Space IRI. Nanopubs missing this triple are ignored by space detection. No transition fallback — the `gen:hasRootDefinition` convention is the only mechanism.

**Authority** traces back to the root nanopub: its assertions declare the initial admin set via `gen:hasAdmin`. All subsequent admin delegations chain back to this immutable root. Currently admins are declared as agent IRIs; linking these to intro nanopubs/pubkeys for cryptographic verification is a straightforward future extension.

### Role Types

Every space-member role is a subclass of `gen:SpaceMemberRole`. Four predefined role types form a hierarchy of assignment authority:

| Type | Assignment rule |
|------|-----------------|
| `gen:AdminRole` | Fixed to the `gen:hasAdmin` predicate. No user-defined admin roles in the MVP (can be added later). |
| `gen:MaintainerRole` | Assignable by existing admins or maintainers. The `gen:hasMaintainer` predicate is a built-in maintainer role; user-defined roles can also be of this type. |
| `gen:MemberRole` | Assignable by anyone at maintainer level or higher. |
| `gen:ObserverRole` | Self-assignable. Default for role definitions that don't declare a type. |

All four are declared as `rdfs:subClassOf gen:SpaceMemberRole` in the vocabulary.

User-defined role predicates declare their type via plain `rdf:type`:

```turtle
<speakerRolePredicate> rdf:type gen:MemberRole .
```

For this iteration, Nanopub Query's job is to *index* role declarations and assignments into the space repo so Nanodash can query them. The privilege-level enforcement (`core`/`structure`/`content`/`comment`) is Nanodash's concern and not part of this plan.

## Key Design Decision: What Goes Into a Space Repo?

**Space-referencing nanopubs** — nanopubs whose assertions reference a known space ref (`NPID_SPACEIRIHASH`) via relevant predicates. Specifically:

- `gen:hasAdmin` — admin declarations (subject = space ref)
- `gen:hasMaintainer` — built-in maintainer declarations (subject = space ref)
- **Dynamic role properties** — role assignment nanopubs using per-space role predicates (regularProperties: `<member> <role-prop> <space>`, inverseProperties: `<space> <role-prop> <member>`); each such predicate is declared `rdf:type` of one of `gen:MaintainerRole`/`gen:MemberRole`/`gen:ObserverRole` in its role-definition nanopub
- `gen:isDisplayFor` — ViewDisplay nanopubs that link views to the space (loaded permissively; query-time filter restricts to displays published by an admin or maintainer of the space — see Phase 2E)
- Space-defining nanopubs themselves (nanopubs of type `gen:Space`)
- Role-definition nanopubs (those that define roles for the space)

**Not included** (obsolete): ~~hasPinnedTemplate~~, ~~hasPinnedQuery~~

**Why not "all nanopubs from all members"?** That would require: (a) knowing membership before populating (circular dependency), (b) massive retroactive backfilling when members join, (c) enormous data duplication, (d) complex unloading when members leave.

## Architecture Overview

```
Current flow (Nanodash):
  SpacePage → GET_ADMINS(space) → GET_SPACE_MEMBER_ROLES(space, pubkeys)
    → GET_SPACE_MEMBERS(space, roles) → GET_VIEW_DISPLAYS(space) → ...
  (4+ sequential API calls, each over full/meta repo)

Proposed flow:
  SpacePage → SPARQL queries against space_<spaceRef> repo
  (all space-management nanopubs pre-indexed in one small repo)
```

## Implementation Plan

### Phase 1: SpaceRegistry (In-Memory + Admin Repo)

**New file:** `src/main/java/com/knowledgepixels/query/SpaceRegistry.java`

Singleton. Initial state — minimal, just what's needed to recognize space refs during detection:
```java
Map<IRI, Set<String>> spaceIriToSpaceRefs        // reverse index: Space IRI → space refs using that IRI
Set<String> knownSpaceRefs                       // all known space refs (for assertion scanning)
```

Added in later steps when they have a consumer:
```java
Map<String, Set<IRI>> spaceRefToRoleProperties   // added when Phase 2C/D is implemented (role learning)
// Initial admins are NOT cached in SpaceRegistry — they live in the space repo itself
// (in the root nanopub's gen:hasAdmin triples) and can be queried from there when
// authority checking (Phase 2B) is implemented.
```

The root nanopub NPID is derivable from the space ref (substring before the first `_`), so no dedicated map is needed.

Persisted in admin repo (details pinned down at the persistence step; sketch only here):
- A triple set recording each known space ref with its learned role properties
- No `Utils.createHash` entry for the space ref itself — the ref is not hashed

On startup, loads known spaces and their role properties from admin repo. During loading, discovers new spaces from space-defining nanopubs and learns role properties from role-definition nanopubs.

### Phase 2: Space Detection in NanopubLoader

**File:** `src/main/java/com/knowledgepixels/query/NanopubLoader.java`

In the constructor (where types are extracted ~line 232), add space ref detection. A nanopub is loaded into a space repo if any of these match:

**A. Space-defining nanopubs:** Nanopub is of type `gen:Space` (= `<https://w3id.org/kpxl/gen/terms/Space>`). Space-declaring nanopubs are required to carry this type directly; specific space subtypes like `gen:Alliance`, `gen:Project`, etc. may also be present but are not what detection checks for. When a `gen:Space`-typed nanopub is detected:
  - Extract Space IRI from assertion (the subject with `rdf:type gen:Space`, or the subject of `gen:hasAdmin` triples)
  - Resolve root nanopub URI: look for `<spaceIri> gen:hasRootDefinition ?root` in the assertion. If absent, **skip this nanopub** — it is not recognized as space-defining.
  - Derive root NPID from `?root`'s trusty URI (artifact code)
  - Compute SPACEIRIHASH = `Utils.createHash(spaceIri)` (same pattern as `type_<HASH>`)
  - Construct space ref = `<rootNanopubId> + "_" + <SPACEIRIHASH>`
  - Register the space ref in SpaceRegistry (adds to `knownSpaceRefs` and the `spaceIriToSpaceRefs` reverse index). Initial admins are not cached — they're in the root nanopub's `gen:hasAdmin` assertions inside the space repo itself, queryable when authority checking is implemented.
  - Load into `space_<spaceRef>` repo

**B. Admin / maintainer declarations:** Assertion contains `gen:hasAdmin` or `gen:hasMaintainer` predicate where subject matches a known space ref in SpaceRegistry. For the MVP, `gen:hasAdmin` is the *only* admin mechanism (no user-defined admin roles). `gen:hasMaintainer` is a built-in shortcut for the maintainer role type. Authority validation: trusted if publisher is in the admin chain traceable from the root nanopub (maintainer declarations can be published by admins or existing maintainers).

**C. Role-definition nanopubs:** Assertion defines a user-defined role for a known space. When detected:
  - Extract the role predicate IRI and its `rdf:type` — one of `gen:MaintainerRole` / `gen:MemberRole` / `gen:ObserverRole`. If no type declared, default to `gen:ObserverRole`. `gen:AdminRole` is not a valid type for user-defined roles in the MVP (skip with a warning if encountered).
  - Extract regularProperties and inverseProperties from the role definition
  - Register them in SpaceRegistry's `spaceRefToRoleProperties` map (keyed by space ref, with role type metadata attached)
  - Persist to admin repo
  - Load into `space_<spaceRef>` repo

**D. Role-assignment nanopubs (dynamic role properties):** Assertion uses a predicate that's registered in SpaceRegistry as a role property for a known space, AND the triple's subject or object matches the space ref. Validity depends on the role type:
  - `gen:ObserverRole` assignments: self-assignment allowed (publisher = the assigned subject)
  - `gen:MemberRole` assignments: trusted only if publisher is at maintainer level or higher
  - `gen:MaintainerRole` assignments: trusted only if publisher is at admin or maintainer level

  This requires SpaceRegistry to maintain the learned role properties along with their types.

**Phase 1 enforcement model (flat roles):** Initial implementation collapses (D) to two cases:
  - `gen:AdminRole` (hardcoded, identified by the well-known `ADMIN_ROLE_IRI`): publisher pubkey must be approved (per trust state) for an agent that holds `gen:hasAdmin` for the space.
  - **Every other role**, regardless of `rdf:type` declared on its role-definition nanopub: treated as `gen:ObserverRole` for enforcement — self-assignment only (publisher pubkey must resolve to the assigned-member agent).

  Role-type metadata (`rdf:type gen:MaintainerRole` etc.) is still stored in the space repo so the UI can render labels and so the future tightening becomes a query swap rather than a re-load. Phase 1 ignores the type for *enforcement*, not for *storage*.

  **Behavior change at switchover:** Nanodash today accepts any role-assignment nanopub matching a learned property (no publisher check). Self-assignment is stricter; a third-party-published assignment of someone else into a non-admin role will stop counting until reissued by the assigned member.

  **Future tightening (Phase 2B/C onward):** When MaintainerRole / MemberRole rules ship, previously-valid self-assignments of what's *now* recognized as a MaintainerRole or MemberRole will retroactively fail (since both require publisher authority above self). Tightening should land with an admin-side workflow for republishing such assignments through an authorized maintainer.

**E. ViewDisplay nanopubs:** Assertion contains `gen:isDisplayFor` predicate where object matches a known space ref. Loaded into the space repo regardless of publisher (catch-all behavior).

**Authority filter (query-time, not load-time):** A ViewDisplay only counts when its publisher pubkey resolves (via the trust-state mirror) to an agent that holds `gen:hasAdmin` or `gen:hasMaintainer` for the space. Until maintainer detection ships (Phase 2B/C), the union degenerates to admins only. Filtering at query time rather than load time means the rule auto-corrects when admin/maintainer membership changes — a previously-rejected display becomes valid the moment its publisher is granted authority, with no reload needed. The canonical SPARQL for this filter belongs in Phase 9 alongside the other Nanodash queries.

**F. Catch-all:** Any nanopub whose assertion references a known space ref as subject or object (ensures we don't miss edge cases while the role property set is being learned).

### Phase 3: Loading into Space Repos

**File:** `NanopubLoader.java`, `executeLoading()` method (~line 334)

After the existing type_ loading loop, add:

```java
for (String spaceRef : detectedSpaceRefs) {  // spaceRef = "NPID_SPACEIRIHASH"
    runTask.accept(() -> loadNanopubToRepo(np.getUri(), allStatements, "space_" + spaceRef));
}
```

Simpler than the `type_` pattern because the space ref is used directly as the repo name suffix — no `Utils.createHash` indirection. `TripleStore.getRepository()` auto-creates the repo if it doesn't exist.

### Phase 4: TripleStore Changes

**File:** `src/main/java/com/knowledgepixels/query/TripleStore.java`

Add a `space_` branch to the dynamic repo prefix handling in `initNewRepo()` (~line 361). The existing `pubkey_`/`type_` branch uses `Utils.getObjectForHash` to recover the covered object from a hash; for `space_` the covered item (the space ref) is already in the repo name, so no hash lookup is needed:

```java
if (repoName.startsWith("pubkey_") || repoName.startsWith("type_")) {
    // existing logic unchanged
    String h = repoName.replaceFirst("^[^_]+_", "");
    conn.add(NPA.THIS_REPO, NPA.HAS_COVERAGE_ITEM, Utils.getObjectForHash(h), NPA.GRAPH);
    conn.add(NPA.THIS_REPO, NPA.HAS_COVERAGE_HASH, vf.createLiteral(h), NPA.GRAPH);
    conn.add(NPA.THIS_REPO, NPA.HAS_COVERAGE_FILTER, vf.createLiteral("_" + repoName), NPA.GRAPH);
} else if (repoName.startsWith("space_")) {
    String spaceRef = repoName.substring("space_".length());
    conn.add(NPA.THIS_REPO, NPA.HAS_COVERAGE_ITEM, vf.createLiteral(spaceRef), NPA.GRAPH);
    conn.add(NPA.THIS_REPO, NPA.HAS_COVERAGE_FILTER, vf.createLiteral("_" + repoName), NPA.GRAPH);
    // no HAS_COVERAGE_HASH — space ref is the canonical identifier, not a hash
}
```

Consider using lighter indexes for space repos (3 instead of 6, like meta repos) since they're small management-focused repos.

### Phase 5: MainVerticle Routes

**File:** `src/main/java/com/knowledgepixels/query/MainVerticle.java`

1. Add `space_` to dynamic repo filter in main listing (~line 190)
2. Add `/spaces` endpoint listing all space repos with labels (following `/types` pattern at ~line 265)
3. Add `for-space` redirect parameter (following `for-type` at ~line 466)

### Phase 6: Invalidation Propagation

**File:** `NanopubLoader.java`, `loadInvalidateStatements()` (~line 530)

When a nanopub is invalidated, check if it was loaded into any space repos (by checking if the invalidated nanopub's assertions reference any known space refs in SpaceRegistry) and propagate the invalidation statement to those space repos.

### Phase 7: Unloading Mechanism

Similar to the `last30d` hourly cleanup pattern, but event-driven:

1. **Member removal:** When a membership/role-assignment nanopub is retracted/superseded, the invalidation propagates to the space repo (Phase 6). The retracted nanopub's invalidation is recorded in the space repo via standard mechanism.

2. **Space dissolution:** When a space's root nanopub is retracted and no superseding nanopub exists:
   - Mark the space as dissolved in SpaceRegistry
   - A periodic maintenance task (e.g., hourly) removes all data from dissolved space repos
   - Pattern: similar to `loadNanopubToLatest()` cleanup at lines 397-414

3. **Space root supersession:** When a root nanopub is superseded (updated), the new version declares `<spaceIRI> gen:hasRootDefinition <originalRoot>` in its assertion, so it resolves to the same space ref and loads into the same space repo. The invalidation of the old root propagates normally.

4. **Role property changes:** When a role-definition nanopub is superseded, SpaceRegistry updates its role property set. New role properties are learned; old ones remain (additive, no removal needed since the catch-all in Phase 2F covers edge cases).

### Phase 8: Metrics

**File:** `src/main/java/com/knowledgepixels/query/MetricsCollector.java`

Add `spaceRepositoriesCounter` gauge following the `typeRepositoriesCounter` pattern.

### Phase 9: Nanodash Changes (Downstream)

**Space-defining nanopub template:** Nanodash must include `<spaceIRI> gen:hasRootDefinition <rootNanopubURI>` in the assertion when publishing space-defining nanopubs:
- For a new space: the root URI is self-referential (resolved via trusty-URI placeholder minting)
- For an update: the root URI points back at the original root nanopub's URI

This predicate is required — a space nanopub without it will not be picked up by the space detection in nanopub-query. Any pre-existing space nanopubs that lack the predicate will need to be republished with the predicate included before they are recognized.

**File:** `/home/tk/Code/nanodash/src/main/java/com/knowledgepixels/nanodash/domain/Space.java`

Simplify `triggerSpaceDataUpdate()` (lines 389-484) to query the `space_<spaceRef>` repo directly instead of executing 4+ chained API queries against the full/meta repos. The space repo contains all the management nanopubs, so queries for admins, roles, members, and view displays can all target the same small repo.

**File:** `/home/tk/Code/nanodash/src/main/java/com/knowledgepixels/nanodash/domain/AbstractResourceWithProfile.java`

Update `triggerDataUpdate()` (lines 134-143) to target the space repo for GET_VIEW_DISPLAYS when the resource is a Space, and replace the current admin-pubkey gate with a SPARQL filter that joins on the space's admin/maintainer set and the trust state. Sketch:

```sparql
PREFIX gen:  <https://w3id.org/kpxl/gen/terms/>
PREFIX npa:  <http://purl.org/nanopub/admin/>
PREFIX nptemp: <http://purl.org/nanopub/temp/>

SELECT ?display WHERE {
  # ViewDisplay nanopubs in this space repo
  GRAPH ?np {
    ?display gen:isDisplayFor <SPACE_REF_IRI> .
    ?np nptemp:hasPubkey ?pubkey .
  }
  # Publisher must be admin or maintainer of the space (in same repo)
  GRAPH ?defNp {
    <SPACE_REF_IRI> ?roleProp ?agent .
    FILTER(?roleProp = gen:hasAdmin || ?roleProp = gen:hasMaintainer)
  }
  # Pubkey must be approved for the agent (trust-state mirror in the trust repo)
  SERVICE <…/repo/trust> {
    GRAPH npa:graph { npa:thisRepo npa:hasCurrentTrustState ?ts }
    GRAPH ?ts {
      ?_ npa:agent ?agent ; npa:pubkey ?pubkey ;
         npa:trustStatus ?status .
      FILTER(?status IN (npa:loaded, npa:toLoad))
    }
  }
}
```

Until Phase 2B/C lands, the `?roleProp = gen:hasMaintainer` branch yields no rows, so the filter degenerates to admins-only. Once maintainers are detected, the same query covers the union without changes.

**File:** `/home/tk/Code/nanodash/src/main/java/com/knowledgepixels/nanodash/QueryApiAccess.java`

Create new query nanopub IDs that target `space_<spaceRef>` repos. Existing queries can be adapted to work against the smaller space repo.

### Phase 10: Bootstrapping (Existing Deployments)

On first deployment with space repos enabled:

1. Scan `meta` repo for nanopubs of type `gen:Space` → discover all root nanopubs
2. For each root nanopub, resolve its `gen:hasRootDefinition` target, derive space ref (`NPID_SPACEIRIHASH`), and register in SpaceRegistry
3. Scan `full` repo for role-definition nanopubs referencing discovered space refs → learn role properties
4. For each space, scan `full` repo for nanopubs referencing that space ref (via hasAdmin, role properties, isDisplayFor, etc.)
5. Load matching nanopubs into corresponding `space_<spaceRef>` repos
6. Track bootstrap progress in admin repo (restartable)

Can be triggered via a management endpoint or run automatically on startup when SpaceRegistry is empty.

### Ordering During Initial Load

During the initial load from registry, nanopubs arrive in chronological order. This creates ordering challenges:

1. **Space-defining nanopub arrives** → space registered, repo created
2. **Role-definition nanopub arrives** → role properties learned, loaded into space repo
3. **Role-assignment nanopub arrives** → matched via learned role properties, loaded into space repo

But what if a role-assignment nanopub arrives *before* the role-definition? The catch-all (Phase 2F: any nanopub referencing a known space ref) handles this — if the space is already known, the nanopub gets loaded even if the specific role property isn't yet learned.

What if a nanopub references a space that isn't yet known? This can happen when management nanopubs (admin/role/viewdisplay) arrive before the space-defining root nanopub. Mitigation:
- After the initial load completes, run a **catch-up scan** of the `full` repo for any nanopubs referencing newly-discovered space refs that were missed during streaming
- Track which spaces were discovered during vs. after initial load to know which need catch-up

## Key Files to Modify

| File | Changes |
|------|---------|
| `NanopubLoader.java` | Space detection in constructor, space repo loading in executeLoading(), invalidation propagation |
| `TripleStore.java` | Add `space_` prefix to initNewRepo() |
| `MainVerticle.java` | `/spaces` route, `for-space` redirect, filter from main listing |
| `MetricsCollector.java` | Add space repo counter |
| `Utils.java` | Reference only — reuse existing `createHash()` |
| **New:** `SpaceRegistry.java` | Space IRI + role property tracking, admin repo persistence |

## Risks & Mitigations

1. **Repo proliferation:** Each space creates an LMDB repo. Use lighter indexes (3 instead of 6, like meta repos) since space repos are small.
2. **Conflicting space declarations:** Not a risk — resolved by construction. Space refs have the form `<NPID>_<SPACEIRIHASH>` where NPID is the root nanopub's content-addressed artifact code. Two distinct nanopubs cannot share an artifact code, so space refs are globally unique without coordination.
3. **Role property learning order:** Role-definition nanopubs must be processed before role-assignment nanopubs for precise matching. Mitigated by catch-all (Phase 2F) + post-load catch-up scan.
4. **Bootstrap time:** For existing deployments, scanning full repo for all space references could be slow. Make restartable and run as background task.
5. **Ordering during initial load:** Space-defining nanopubs may arrive after referencing nanopubs. Handle via catch-all matching on known space refs + post-initial-load catch-up scan.
6. **SpaceRegistry persistence:** If admin repo state is lost, SpaceRegistry must be reconstructable. Ensure bootstrap can re-derive all state from the full repo.

## Verification

1. Write unit tests for SpaceRegistry (space registration, role property learning, persistence)
2. Write integration tests with test nanopubs typed as `gen:Space`, plus role definitions, member assignments, and view displays
3. Deploy locally with docker-compose, load test nanopubs, verify space repos are created with correct content
4. Query space repos via SPARQL to confirm they contain: root nanopub, admin declarations, role definitions, member assignments, view displays
5. Test invalidation: retract a membership nanopub, verify invalidation propagates to space repo
6. Test space dissolution: retract a space root nanopub, verify cleanup runs
7. Test role property learning: load role-definition then role-assignment, verify both land in space repo
8. Update Nanodash to query space repos, verify Space pages load correctly with simplified query chain
