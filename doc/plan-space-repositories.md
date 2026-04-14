# Plan: Space Repositories in Nanopub Query

## Context

**Problem:** Nanodash currently performs complex, multi-step Space/member calculation client-side. When a Space page loads, it executes a chain of API queries against nanopub-query (GET_ADMINS -> GET_SPACE_MEMBER_ROLES -> GET_SPACE_MEMBERS -> GET_VIEW_DISPLAYS), each depending on results from the previous. This is slow, fragile, and puts the membership logic in the wrong place.

**Goal:** Move Space/member calculation into Nanopub Query by giving each Space its own RDF4J repository (`space_<HASH>`), similar to the existing `type_<HASH>` and `pubkey_<HASH>` dynamic repos. The space repo would contain all nanopubs relevant to that space, enabling efficient direct SPARQL queries instead of the current multi-query chain. Like `last30d`, nanopubs would need to be un-loaded when memberships change or spaces dissolve.

## Space Identity Model

Every space is uniquely identified by a **space ref** of the form `NPID/SPACEIRIHASH`, where:

- **NPID** is the artifact code (e.g. `RA...`) of the **root nanopub** — the nanopub that founded the space. Since artifact codes are cryptographic hashes of content, they are globally unique and immutable.
- **SPACEIRIHASH** is `Utils.createHash(<Space IRI>)` — computed from the Space IRI declared in the root nanopub's assertions, following the same hashing pattern already used for `type_<HASH>` repos (`Utils.createHash(typeIri)`). A single root nanopub can define multiple spaces by declaring multiple Space IRIs.

This eliminates conflicts by construction: no two distinct root nanopubs share an artifact code, so no two distinct spaces share a space ref. The `space_<HASH>` repository name is derived from hashing the full `NPID/SPACEIRIHASH` string.

**Authority** traces back to the root nanopub: its assertions declare the initial admin set via `kpxl_terms:hasAdmin`. All subsequent admin delegations chain back to this immutable root. Currently admins are declared as agent IRIs; linking these to intro nanopubs/pubkeys for cryptographic verification is a straightforward future extension.

## Key Design Decision: What Goes Into a Space Repo?

**Space-referencing nanopubs** — nanopubs whose assertions reference a known space ref (`NPID/SPACEIRIHASH`) via relevant predicates. Specifically:

- `kpxl_terms:hasAdmin` — admin declarations (subject = space ref)
- **Dynamic role properties** — role assignment nanopubs using per-space role predicates (regularProperties: `<member> <role-prop> <space>`, inverseProperties: `<space> <role-prop> <member>`)
- `kpxl_terms:isDisplayFor` — ViewDisplay nanopubs that link views to the space
- Space-defining nanopubs themselves (root nanopubs with KPXL space types)
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
  SpacePage → SPARQL queries against space_<HASH> repo
  (all space-management nanopubs pre-indexed in one small repo)
```

## Implementation Plan

### Phase 1: SpaceRegistry (In-Memory + Admin Repo)

**New file:** `src/main/java/com/knowledgepixels/query/SpaceRegistry.java`

Singleton maintaining:
```java
Map<String, String> spaceRefToRepoHash           // NPID/SPACEIRIHASH → SHA256 hash (for repo name)
Map<String, String> spaceRefToRootNanopubId    // NPID/SPACEIRIHASH → root nanopub NPID (RA...)
Map<String, Set<IRI>> spaceRefToInitialAdmins    // NPID/SPACEIRIHASH → initial admin IRIs (from root nanopub)
Map<String, Set<IRI>> spaceRefToRoleProperties   // NPID/SPACEIRIHASH → role predicate IRIs (regular + inverse)
Set<String> knownSpaceRefs                       // all known space refs (for assertion scanning)
```

Persisted in admin repo using existing patterns:
- `npa:hash+<hash> npa:isHashOf "<NPID/SPACEIRIHASH>"` (existing hash pattern from Utils.createHash)
- `"<NPID/SPACEIRIHASH>" rdf:type npa:Space` (new triple to mark known spaces)
- `"<NPID/SPACEIRIHASH>" npa:rootNanopub "<NPID>"` (root nanopub reference)
- `"<NPID/SPACEIRIHASH>" npa:hasRoleProperty <rolePropertyIRI>` (new triple to track dynamic role properties per space)

On startup, loads known spaces and their role properties from admin repo. During loading, discovers new spaces from space-defining nanopubs and learns role properties from role-definition nanopubs.

### Phase 2: Space Detection in NanopubLoader

**File:** `src/main/java/com/knowledgepixels/query/NanopubLoader.java`

In the constructor (where types are extracted ~line 232), add space ref detection. A nanopub is loaded into a space repo if any of these match:

**A. Space-defining (root) nanopubs:** Nanopub type matches a KPXL space type (Alliance, Project, Consortium, Organization, Taskforce, Division, Taskunit, Group, Program, Initiative, Outlet, Campaign, Community, Event — all under `https://w3id.org/kpxl/gen/terms/`). The nanopub itself is the root nanopub. When detected:
  - Extract Space IRI from assertion (the subject with `rdf:type` matching the KPXL space type, or the subject of `kpxl_terms:hasAdmin` triples)
  - Compute SPACEIRIHASH = `Utils.createHash(spaceIri)` (same pattern as `type_<HASH>`)
  - Construct space ref = `<this-nanopub-NPID>/<SPACEIRIHASH>`
  - Extract initial admin set from `kpxl_terms:hasAdmin` assertions in this nanopub
  - Register in SpaceRegistry with root nanopub NPID and initial admin set
  - Load into `space_<HASH>` repo

**B. Admin declarations:** Assertion contains `kpxl_terms:hasAdmin` predicate where subject matches a known space ref in SpaceRegistry. Only trusted if publisher is in the admin set traceable from the root nanopub.

**C. Role-definition nanopubs:** Assertion defines roles for a known space (detected by referencing a known space ref). When detected:
  - Extract regularProperties and inverseProperties from the role definition
  - Register them in SpaceRegistry's `spaceRefToRoleProperties` map
  - Persist to admin repo
  - Load into `space_<HASH>` repo

**D. Role-assignment nanopubs (dynamic role properties):** Assertion uses a predicate that's registered in SpaceRegistry as a role property for a known space, AND the triple's subject or object matches the space ref. This requires SpaceRegistry to maintain the learned role properties.

**E. ViewDisplay nanopubs:** Assertion contains `kpxl_terms:isDisplayFor` predicate where object matches a known space ref.

**F. Catch-all:** Any nanopub whose assertion references a known space ref as subject or object (ensures we don't miss edge cases while the role property set is being learned).

### Phase 3: Loading into Space Repos

**File:** `NanopubLoader.java`, `executeLoading()` method (~line 334)

After the existing type_ loading loop, add:

```java
for (String spaceRef : detectedSpaceRefs) {  // spaceRef = "NPID/SPACEIRIHASH"
    String spaceHash = SpaceRegistry.get().getOrRegister(spaceRef);
    runTask.accept(() -> loadNanopubToRepo(np.getUri(), allStatements, "space_" + spaceHash));
}
```

This follows the exact same pattern as `type_` repo loading. `TripleStore.getRepository()` auto-creates the repo if it doesn't exist.

### Phase 4: TripleStore Changes

**File:** `src/main/java/com/knowledgepixels/query/TripleStore.java`

Add `space_` to the dynamic repo prefix handling in `initNewRepo()` (~line 361):

```java
if (repoName.startsWith("pubkey_") || repoName.startsWith("type_") || repoName.startsWith("space_")) {
    // existing coverage metadata logic applies unchanged
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

3. **Space root supersession:** When a space root nanopub is superseded (updated), the new version defines the current state. The invalidation of the old root propagates normally; the new root loads into the same space repo (same space ref = same hash).

4. **Role property changes:** When a role-definition nanopub is superseded, SpaceRegistry updates its role property set. New role properties are learned; old ones remain (additive, no removal needed since the catch-all in Phase 2F covers edge cases).

### Phase 8: Metrics

**File:** `src/main/java/com/knowledgepixels/query/MetricsCollector.java`

Add `spaceRepositoriesCounter` gauge following the `typeRepositoriesCounter` pattern.

### Phase 9: Nanodash Changes (Downstream)

**File:** `/home/tk/Code/nanodash/src/main/java/com/knowledgepixels/nanodash/domain/Space.java`

Simplify `triggerSpaceDataUpdate()` (lines 389-484) to query the `space_<HASH>` repo directly instead of executing 4+ chained API queries against the full/meta repos. The space repo contains all the management nanopubs, so queries for admins, roles, members, and view displays can all target the same small repo.

**File:** `/home/tk/Code/nanodash/src/main/java/com/knowledgepixels/nanodash/domain/AbstractResourceWithProfile.java`

Update `triggerDataUpdate()` (lines 134-143) to target the space repo for GET_VIEW_DISPLAYS when the resource is a Space.

**File:** `/home/tk/Code/nanodash/src/main/java/com/knowledgepixels/nanodash/QueryApiAccess.java`

Create new query nanopub IDs that target `space_<HASH>` repos. Existing queries can be adapted to work against the smaller space repo.

### Phase 10: Bootstrapping (Existing Deployments)

On first deployment with space repos enabled:

1. Scan `meta` repo for nanopubs with KPXL space type IRIs → discover all root nanopubs
2. For each root nanopub, derive space ref (`NPID/SPACEIRIHASH`) and register in SpaceRegistry with initial admin set
3. Scan `full` repo for role-definition nanopubs referencing discovered space refs → learn role properties
4. For each space, scan `full` repo for nanopubs referencing that space ref (via hasAdmin, role properties, isDisplayFor, etc.)
5. Load matching nanopubs into corresponding `space_<HASH>` repos
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
2. **Conflicting space declarations:** Not a risk — resolved by construction. Space Refs are `NPID/SPACEIRIHASH` where NPID is the root nanopub's content-addressed artifact code. Two distinct nanopubs cannot share an artifact code, so space refs are globally unique without coordination.
3. **Role property learning order:** Role-definition nanopubs must be processed before role-assignment nanopubs for precise matching. Mitigated by catch-all (Phase 2F) + post-load catch-up scan.
4. **Bootstrap time:** For existing deployments, scanning full repo for all space references could be slow. Make restartable and run as background task.
5. **Ordering during initial load:** Space-defining nanopubs may arrive after referencing nanopubs. Handle via catch-all matching on known space refs + post-initial-load catch-up scan.
6. **SpaceRegistry persistence:** If admin repo state is lost, SpaceRegistry must be reconstructable. Ensure bootstrap can re-derive all state from the full repo.

## Verification

1. Write unit tests for SpaceRegistry (space registration, role property learning, persistence)
2. Write integration tests with test nanopubs containing space types, role definitions, member assignments, and view displays
3. Deploy locally with docker-compose, load test nanopubs, verify space repos are created with correct content
4. Query space repos via SPARQL to confirm they contain: root nanopub, admin declarations, role definitions, member assignments, view displays
5. Test invalidation: retract a membership nanopub, verify invalidation propagates to space repo
6. Test space dissolution: retract a space root nanopub, verify cleanup runs
7. Test role property learning: load role-definition then role-assignment, verify both land in space repo
8. Update Nanodash to query space repos, verify Space pages load correctly with simplified query chain
