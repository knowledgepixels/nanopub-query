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

## Authority Chain Resolution

The admin set of a space is not a flat triple match — it's the fixed-point closure of a delegation chain rooted in the space's root nanopub. Same shape for the maintainer set, and the same "closure + validate" treatment applies to member assignments under the role-type rules. Authority-sensitive queries (view-display filtering in Phase 9, role-assignment validation in Phase 2D, and any future "is X allowed to do Y") must consult the *resolved* set, not raw `gen:hasAdmin` / `gen:hasMaintainer` / role-property triples.

The resolved sets are **materialized into the `npa:graph` admin graph of the space repo** so queries are plain SPARQL joins over a single, inspectable source of truth — no Java helper needed at query time.

### Algorithm (admins)

```
# Seed: the root nanopub's hasAdmin triples are trusted by construction —
# the root NPID is part of the space ref, so trusting the root is part of
# trusting the space identity itself. No publisher check needed for the seed.
admins := { agent : root_np asserts <spaceRef> gen:hasAdmin <agent> }

repeat:
  changed := false
  for each (np, agent, pubkey) where:
      - np in space repo asserts <spaceRef> gen:hasAdmin <agent>
      - np's publisher pubkey is `pubkey`
      - agent ∉ admins
    publisher_agent := trustState.agentFor(pubkey)   # null if pubkey not approved
    if publisher_agent ∈ admins:
      admins.add(agent); changed := true
until not changed

return admins
```

In practice the loop runs O(chain-depth) iterations — typically 1–3.

### Algorithm (maintainers)

Identical shape, with two differences:
- Seed = `<spaceRef> gen:hasMaintainer <agent>` triples in nanopubs whose publisher is in the closed *admin* set (no built-in seed; maintainers always require an admin grant somewhere upstream).
- Subsequent grants accepted if publisher is in (admins ∪ current maintainers).

The admin closure must be computed first; the maintainer closure consumes it as input.

### Algorithm (members, Phase 1 flat-role rules)

For every role-assignment nanopub in the space repo whose property is registered in `SpaceRegistry` as a role property of this space:
- If the role is the hardcoded `gen:AdminRole` → already covered by the admin closure above (don't double-count).
- Otherwise (Phase 1 treats all non-admin roles as Observer-equivalent): valid iff publisher pubkey resolves (via trust state) to the assigned-member agent itself (self-assignment).

When Phase 2C/D ships type-aware enforcement, this step splits per role type (Observer = self-assign; Member = maintainer+; Maintainer = admin+) — the materialization shape stays the same.

### Materialized output (in `npa:graph` of the space repo)

```turtle
GRAPH npa:graph {
  # ---- Resolved admin set (closed) ----
  _:a1 a npa:ResolvedRoleAssignment ;
       npa:forSpace        <spaceRef> ;
       npa:role            gen:AdminRole ;
       npa:agent           <agent1> ;
       npa:viaNanopub      <root_np> .              # for seed admins
  _:a2 a npa:ResolvedRoleAssignment ;
       npa:forSpace        <spaceRef> ;
       npa:role            gen:AdminRole ;
       npa:agent           <agent2> ;
       npa:viaNanopub      <delegation_np> .        # for delegated admins

  # ---- Resolved maintainer set (closed; empty in Phase 1) ----
  _:m1 a npa:ResolvedRoleAssignment ;
       npa:forSpace        <spaceRef> ;
       npa:role            gen:MaintainerRole ;
       npa:agent           <agent3> ;
       npa:viaNanopub      <maintainer_grant_np> .

  # ---- Resolved member assignments (any non-admin, non-maintainer role) ----
  _:r1 a npa:ResolvedRoleAssignment ;
       npa:forSpace        <spaceRef> ;
       npa:role            <user_defined_role_iri> ;
       npa:agent           <agentX> ;
       npa:viaNanopub      <assignment_np> .
}
```

One uniform shape across all three layers (admin / maintainer / other-member) keeps queries simple — Nanodash can list "all members of this space" with one BGP, or filter by role IRI for a specific layer. The `npa:viaNanopub` provenance pointer answers "where did this membership come from?" for audit and supersession tracking.

Convenience triples (e.g. `<spaceRef> npa:hasResolvedAdmin <agent>`) can be added later if query patterns make them worthwhile; for the MVP the single `ResolvedRoleAssignment` shape suffices.

### Re-materialization triggers

The resolved set must be recomputed when its inputs change. Triggers:

- **New space-repo nanopub** asserting `gen:hasAdmin`, `gen:hasMaintainer`, or any registered role property → admin / maintainer / member set may grow.
- **New role-definition nanopub** for the space → the set of "registered role properties" grows; previously-ignored assignments may now qualify.
- **Trust-state pointer flip** (already detected centrally by `TrustStateLoader`) → publisher → agent mapping may change globally; every space's resolved set may need recomputing.
- **Supersession or invalidation** of any of the above → may shrink the resolved set.

Recomputation happens inside a single transaction on the space repo: clear the `ResolvedRoleAssignment` triples in `npa:graph`, write the new closure. Atomic for queries; never half-updated.

### Semantics decisions baked in

1. **Closure-based, not time-ordered.** A `hasAdmin` nanopub published by A counts whenever A is *ever* established as admin, not only if A was already admin at publication time. The time-ordered version is more secure (resists post-hoc grants) but needs sortable timestamps and re-evaluation on every admin change. Closure-based is simpler and matches how most delegation models in the wild work. Revisit if a real attack scenario shows up.
2. **Sticky adminship.** Once a chain validates, the new admin stays admin until explicitly revoked via supersession/invalidation (Phase 6). Revocation does not cascade — if A is revoked, B (whom A admin'd) survives. Cascading revocation is a recurring source of surprise; sticky is the safer default.
3. **Materialization, not query-time computation.** SPARQL 1.1 has no fixed-point recursion and property paths can't consume the trust-state lookup inside the recursion, so any pure-SPARQL approach would punt to a Java helper at query time. Materializing the closure into `npa:graph` removes that wart: queries are plain joins, and the authority state is auditable from a single place.

### Implementation: `AuthorityResolver`

A new class (likely `src/main/java/com/knowledgepixels/query/AuthorityResolver.java`, or a method group on `SpaceRegistry`) owns the closure computation and the materialization write:

```java
// Recompute & materialize for one space. Idempotent. Atomic w.r.t. queries.
public void rematerialize(String spaceRef);

// Recompute & materialize for every known space — used after a trust-state flip.
public void rematerializeAll();
```

Hot reads (admin checks, member lookups) go through plain SPARQL against the space repo's `npa:graph` — no Java helper needed at query time. The resolver is purely a writer.

Triggered from:
- `NanopubLoader.executeLoading()` after loading a nanopub into a space repo, if the nanopub matches one of the trigger types above (covers cases 1 and 2).
- `TrustStateLoader` (or a coordinator above it) after a successful trust-state pointer swap (case 3) — calls `rematerializeAll()` over the spaces in `SpaceRegistry`.
- `loadInvalidateStatements` in the invalidation flow (case 4).

### Edge cases

- **Cycles** (A admins B, B admins A) — fixed-point converges regardless; both end up admin if either has a valid path back to the seed.
- **Self-grant by root admin** — the seed already includes them; the iteration would no-op.
- **Pubkey approved for multiple agents** — the trust state can map one pubkey to multiple agents; the resolver should treat publisher_agent ∈ admins as "*any* of the publisher's agents is in admins".
- **Trust state changes mid-flight** — recomputation reads the trust state via the current pointer in the trust repo, inside its own transaction; the materialization commits atomically. Subsequent flips just retrigger.
- **Materialization lag** — between a relevant new nanopub landing and the rematerialize call completing, queries see the previous resolved set. This is fine for an eventual-consistency model; the lag is bounded by the loader's cycle.

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

**B. Admin / maintainer declarations:** Assertion contains `gen:hasAdmin` or `gen:hasMaintainer` predicate where subject matches a known space ref in SpaceRegistry. For the MVP, `gen:hasAdmin` is the *only* admin mechanism (no user-defined admin roles). `gen:hasMaintainer` is a built-in shortcut for the maintainer role type. Authority validation goes through `AuthorityResolver` (see "Authority Chain Resolution" above): a `gen:hasAdmin` declaration counts iff its publisher is in the closed admin set; a `gen:hasMaintainer` declaration counts iff its publisher is in (closed admins ∪ closed maintainers). The detection step here just *loads* such nanopubs into the space repo; the closure decides whether they participate in the admin/maintainer set.

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
  - `gen:AdminRole` (hardcoded, identified by the well-known `ADMIN_ROLE_IRI`): publisher pubkey must resolve (via the trust state) to an agent that appears in the materialized `ResolvedRoleAssignment` set with `npa:role gen:AdminRole` — i.e. the *closed* admin set in `npa:graph`, not any direct `gen:hasAdmin` triple. The materialization (see "Authority Chain Resolution" above) prevents a malicious nanopub of the form `<spaceRef> gen:hasAdmin <evilAgent>` from creating a self-attesting admin.
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

Update `triggerDataUpdate()` (lines 134-143) to target the space repo for GET_VIEW_DISPLAYS when the resource is a Space, and replace the current admin-pubkey gate with a SPARQL join against the materialized `ResolvedRoleAssignment` triples in `npa:graph` (see "Authority Chain Resolution" above):

```sparql
PREFIX gen:    <https://w3id.org/kpxl/gen/terms/>
PREFIX npa:    <http://purl.org/nanopub/admin/>
PREFIX nptemp: <http://purl.org/nanopub/temp/>

SELECT ?display WHERE {
  GRAPH ?np {
    ?display gen:isDisplayFor <SPACE_REF_IRI> .
  }
  GRAPH ?npPubinfo {
    ?np nptemp:hasPubkey ?pubkey .
  }
  # Pubkey must resolve (via trust state) to an admin or maintainer of the space.
  GRAPH npa:graph {
    ?ra a npa:ResolvedRoleAssignment ;
        npa:forSpace <SPACE_REF_IRI> ;
        npa:role     ?role ;
        npa:agent    ?publisherAgent .
    FILTER(?role IN (gen:AdminRole, gen:MaintainerRole))
  }
  SERVICE <…/repo/trust> {
    GRAPH npa:graph { npa:thisRepo npa:hasCurrentTrustState ?ts }
    GRAPH ?ts {
      ?_ npa:agent ?publisherAgent ;
         npa:pubkey ?pubkey ;
         npa:trustStatus ?status .
      FILTER(?status IN (npa:loaded, npa:toLoad))
    }
  }
}
```

Until Phase 2C lands, no `gen:MaintainerRole` rows exist in the materialized set, so the filter degenerates to admins-only. Once maintainers are detected and materialized, the same query covers the union without changes. The SERVICE join to the trust repo is what maps the publisher's pubkey to an agent for comparison against the admin/maintainer set; it's small and indexed.

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
| **New:** `AuthorityResolver.java` | Computes the closed admin / maintainer / member sets per space (fixed-point closure over `gen:hasAdmin` / `gen:hasMaintainer` / role-property triples + trust state) and **materializes** them as `npa:ResolvedRoleAssignment` triples in the space repo's `npa:graph`. Triggered on space-repo authority changes and on trust-state pointer flips. Pure writer — no query-time helper needed. |

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
