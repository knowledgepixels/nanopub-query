# Plan: Trust State Repositories in Nanopub Query

## Context

**Problem:** Nanopub Query needs to know the agent → approved-pubkey mapping to validate authority claims (e.g. "is the pubkey that signed this admin declaration actually approved for the claimed agent?"). That mapping is computed by the registry's trust calculation. Currently nanopub-query has no copy of it, so authority claims can't be validated.

**Goal:** Mirror the registry's trust state into a single `trust` RDF4J repository in nanopub-query. Each mirrored state lives in its own named graph keyed by the registry's `trustStateHash`. A current-state pointer (in the `npa:graph` admin graph of the `trust` repo) identifies which graph represents the live trust state, so query code can always resolve "is pubkey P approved for agent A right now?" via SPARQL. Old states are retained for a configurable window before being pruned.

This is a prerequisite for [#62](https://github.com/knowledgepixels/nanopub-query/issues/62) Phase 2B (admin authority checks) but is independently useful for any authority validation across nanopub-query.

## Upstream Contract

Provided by the registry as of [nanopub-registry#107 / PR #108](https://github.com/knowledgepixels/nanopub-registry/pull/108):

**Detection:** every registry response carries `Nanopub-Registry-Trust-State-Hash: <hash>`. A `HEAD /` is enough to learn the current hash without downloading the body.

**Fetch:** `GET /trust-state/<hash>.json` returns:

```json
{
  "trustStateHash": "<hash>",
  "trustStateCounter": {"$numberLong": "1"},
  "createdAt": "2026-04-15T14:16:16.112094241Z[Etc/UTC]",
  "accounts": [
    {
      "pubkey": "<hex>",
      "agent": "<iri>",
      "status": "toLoad",
      "depth": 1,
      "pathCount": 1,
      "ratio": 0.008,
      "quota": 100000
    }
  ]
}
```

Headers: `Cache-Control: public, immutable, max-age=31536000`. `404` if the hash isn't retained (registry keeps the last 100 snapshots, FIFO by counter).

Notes:
- `trustStateCounter` arrives as BSON extended-JSON (`{"$numberLong": "..."}`); the parser needs to unwrap it.
- `status` ranges over the registry's `EntryStatus` enum. For the account-level data we mirror, observed values include `loaded`, `toLoad`, `seen`, `visited`, `expanded`, `skipped`, `processed`, `aggregated`, `approved`, `contested`, `capped`. The loader converts each value into an IRI in the `npa:` namespace (`npa:loaded`, `npa:toLoad`, `npa:skipped`, …) and stores it as an IRI on the account state, not as a literal. No enumeration is hardcoded — if the registry adds a new status, the loader mints the corresponding IRI without code changes. **Important for queries:** these statuses are not all equivalent. `loaded` and `toLoad` mean "trust-approved" (the latter just hasn't been downloaded yet); `skipped` means "explicitly rejected by trust calculation"; the rest are intermediate or transient. Authority-validation queries should match a specific approved set, not "anything that's not loaded".
- The synthetic `$` pubkey ("all types" sentinel) is already excluded server-side.
- `createdAt` uses Java `ZonedDateTime.toString()` format with the `[Etc/UTC]` zone bracket — needs careful conversion to `xsd:dateTime`.

## Identity Model

A single `trust` repo holds every mirrored trust state. Each state occupies its own named graph, keyed by the registry's `trustStateHash`:

- **Trust state IRI:** `http://purl.org/nanopub/admin/truststate/<trustStateHash>` — also used as the named-graph URI for that state's account-state triples.
- **Account state IRI:** `http://purl.org/nanopub/admin/accountstate/<accountStateHash>` where `accountStateHash = SHA-256(trustStateHash + "|" + pubkey + "|" + agent)`. Stable within a snapshot; different across snapshots (since the trust hash is part of the input). Same `(pubkey, agent)` pair in two consecutive snapshots produces two different account-state IRIs — which is the right semantics: each snapshot is a self-contained immutable record.
- **Cross-state metadata** (one triple per trust state, plus the current pointer) lives in the existing `npa:graph` admin graph alongside other admin triples.

## Triple Schema

### Per-state account triples (in the trust-state's named graph)

```turtle
GRAPH <http://purl.org/nanopub/admin/truststate/abc...> {
    <http://purl.org/nanopub/admin/accountstate/XYZ...>
        a npa:AccountState ;
        npa:agent <agentIRI> ;
        npa:pubkey "<pubkeyHex>" ;
        npa:trustStatus npa:toLoad ;
        npa:depth 1 ;
        npa:pathCount 1 ;
        npa:ratio "0.008181818181818182"^^xsd:double ;
        npa:quota 100000 .
    …
}
```

### Cross-state metadata (in `npa:graph`)

```turtle
GRAPH npa:graph {
    <http://purl.org/nanopub/admin/truststate/abc...>
        a npa:TrustState ;
        npa:hasTrustStateHash "abc..." ;
        npa:hasTrustStateCounter "18"^^xsd:long ;
        npa:hasCreatedAt "<iso8601>"^^xsd:dateTime .

    <http://purl.org/nanopub/admin/truststate/def...>
        a npa:TrustState ;
        npa:hasTrustStateHash "def..." ;
        npa:hasTrustStateCounter "17"^^xsd:long ;
        npa:hasCreatedAt "<iso8601>"^^xsd:dateTime .

    npa:thisRepo npa:hasCurrentTrustState
        <http://purl.org/nanopub/admin/truststate/abc...> .
}
```

Predicate names (`npa:AccountState`, `npa:TrustState`, `npa:agent`, `npa:pubkey`, `npa:trustStatus`, `npa:depth`, `npa:pathCount`, `npa:ratio`, `npa:quota`, `npa:hasTrustStateHash`, `npa:hasTrustStateCounter`, `npa:hasCreatedAt`, `npa:hasCurrentTrustState`) and status IRI values (`npa:loaded`, `npa:toLoad`, plus any new ones the registry introduces) are sketches — happy to revise before implementation.

## Querying

### Canonical pattern: current state via pointer

The current state is resolved via a single SPARQL join — pointer in `npa:graph`, data in the pointed-to graph:

```sparql
PREFIX npa: <http://purl.org/nanopub/admin/>

# "Is pubkey P approved for agent A in the current trust state?"
ASK {
  GRAPH npa:graph {
    npa:thisRepo npa:hasCurrentTrustState ?g .
  }
  GRAPH ?g {
    ?s npa:agent <https://orcid.org/...> ;
       npa:pubkey "abcd..." ;
       npa:trustStatus npa:loaded .
  }
}
```

The two `GRAPH` clauses are joined by `?g` in a single query — no sequential round-trips. The IRI of the active state is auditable in query results when needed (`SELECT ?g …`).

### Historical state by hash

Skip the pointer; address the graph directly:

```sparql
GRAPH <http://purl.org/nanopub/admin/truststate/abc...> {
  ?s npa:agent <agent> ; npa:pubkey "pk" ; npa:trustStatus ?status .
}
```

### State evolution for an agent/pubkey

Join through the metadata in `npa:graph` to get an ordered timeline:

```sparql
SELECT ?g ?counter ?status WHERE {
  GRAPH npa:graph {
    ?g a npa:TrustState ;
       npa:hasTrustStateCounter ?counter .
  }
  GRAPH ?g {
    ?s npa:agent <agent> ; npa:pubkey "pk" ; npa:trustStatus ?status .
  }
} ORDER BY DESC(?counter)
```

### Java helper

Authority-checking call sites in #62 (Phase 2B and onward) shouldn't write the two-step SPARQL by hand. A small helper on `TrustStateRegistry` (or a separate `TrustQueries` class) wraps the canonical lookup:

```java
// Returns true iff the given pubkey is approved for the given agent in the current trust state.
public boolean isApproved(IRI agent, String pubkey, String requiredStatus);
```

Internally, executes the canonical query against the `trust` repo. Call sites stay clean and queries stay consistent.

## Implementation Plan

### Phase 1: TrustStateRegistry skeleton + unit tests

**New file:** `src/main/java/com/knowledgepixels/query/TrustStateRegistry.java`

In-memory singleton tracking the currently-known trust state hash:

```java
public class TrustStateRegistry {
    public static TrustStateRegistry get();
    public Optional<String> getCurrentHash();
    public void setCurrentHash(String hash);  // called after successful repo materialization
}
```

Pure data class. No HTTP, no RDF. Unit-testable in isolation.

### Phase 2: Hash-change detection via existing update poll

No new scheduler is needed. `JellyNanopubLoader.loadUpdates` already runs every 2 seconds (`MainVerticle.java:491-496`) and does a `HEAD /` on the registry to read metadata headers (`fetchRegistryMetadataInner` at `JellyNanopubLoader.java:341`). We just extend that flow to also read and react to `Nanopub-Registry-Trust-State-Hash`.

Changes:

1. Add `String trustStateHash` to the `RegistryMetadata` record (`JellyNanopubLoader.java:41-42`)
2. In `fetchRegistryMetadataInner`, read the header alongside the others (it's optional — older registries won't have it, handle `null` gracefully)
3. In `loadUpdates`, after metadata is fetched, compare `metadata.trustStateHash()` with `TrustStateRegistry.get().getCurrentHash()`:
   - Equal (or both null) → no-op
   - Different → call a new `TrustStateLoader.maybeUpdate(newHash)` that handles fetch + materialize (Phase 3+) on a worker thread so we don't block the 2-second poll loop
4. Same detection runs inside `loadInitial` so the first-launch case doesn't wait for an `UPDATE_POLL_INTERVAL` to discover trust state

Rationale for reusing the existing cycle:
- 2 s cadence is more than frequent enough — the registry only emits new trust states hourly or less
- 99% of polls are no-ops (O(1) hash equality check); no extra network traffic
- No new env vars to maintain
- Same failure handling as the rest of `loadUpdates` (registry unreachable → retry next cycle)

**New file:** `src/main/java/com/knowledgepixels/query/TrustStateLoader.java`

Handles the fetch + materialize side (no scheduling of its own):

```java
public class TrustStateLoader {
    // Called from JellyNanopubLoader.loadUpdates when hash changes.
    // Safe to call concurrently — internal guard skips if already materializing the same hash.
    public static void maybeUpdate(String newTrustStateHash);
}
```

### Phase 3: Snapshot fetch + envelope parsing

In `TrustStateLoader`:

1. `GET /trust-state/<hash>.json`
2. Parse the envelope (Jackson or RDF4J's JSON utilities; pick whichever is already a dependency)
3. Verify `body.trustStateHash == URL hash` (integrity check)
4. Unwrap `{"$numberLong": "..."}` for `trustStateCounter`
5. Convert `createdAt` to `xsd:dateTime` form (strip `[Etc/UTC]` bracket; parse remaining as `Instant`)
6. Yield a `TrustStateSnapshot` POJO containing hash, counter, timestamp, and a `List<AccountEntry>`

`404` from the registry → log + abandon this attempt; the registry has pruned the hash and we'll catch a newer one on the next poll. (We don't try to "back-fill" missed states.)

### Phase 4: `trust` repo init + triple builder + atomic swap

**File:** `src/main/java/com/knowledgepixels/query/TripleStore.java`

Auto-init of the `trust` repo on first access works through the existing `initNewRepo` path; no new prefix branch is required (the repo name is a fixed string, not prefix-derived). If we want coverage metadata, we can add it later — trust isn't a "coverage item" in the same sense that `pubkey_`/`type_` are.

**In `TrustStateLoader`**, after parsing a snapshot, do the full materialization + swap in a single serializable transaction on the `trust` repo:

```java
IRI trustStateIri = vf.createIRI("http://purl.org/nanopub/admin/truststate/" + hash);

try (RepositoryConnection conn = TripleStore.get().getRepoConnection("trust")) {
    conn.begin(IsolationLevels.SERIALIZABLE);

    // 1. Populate the state's named graph with account-state triples
    for (AccountEntry a : snapshot.accounts()) {
        IRI accountStateIri = vf.createIRI(
            "http://purl.org/nanopub/admin/accountstate/" + accountStateHash(hash, a));
        conn.add(accountStateIri, RDF.TYPE, NPA.ACCOUNT_STATE, trustStateIri);
        conn.add(accountStateIri, NPA.AGENT, vf.createIRI(a.agent()), trustStateIri);
        conn.add(accountStateIri, NPA.PUBKEY, vf.createLiteral(a.pubkey()), trustStateIri);
        conn.add(accountStateIri, NPA.TRUST_STATUS,
                 vf.createIRI(NPA.NAMESPACE + a.status()), trustStateIri);  // e.g. npa:loaded
        // ... depth, pathCount, ratio, quota
    }

    // 2. Cross-state metadata into npa:graph
    conn.add(trustStateIri, RDF.TYPE, NPA.TRUST_STATE, NPA.GRAPH);
    conn.add(trustStateIri, NPA.HAS_TRUST_STATE_HASH, vf.createLiteral(hash), NPA.GRAPH);
    conn.add(trustStateIri, NPA.HAS_TRUST_STATE_COUNTER,
             vf.createLiteral(snapshot.counter()), NPA.GRAPH);
    conn.add(trustStateIri, NPA.HAS_CREATED_AT,
             vf.createLiteral(snapshot.createdAt()), NPA.GRAPH);

    // 3. Swap the current pointer (in npa:graph)
    conn.remove(NPA.THIS_REPO, NPA.HAS_CURRENT_TRUST_STATE, null, NPA.GRAPH);
    conn.add(NPA.THIS_REPO, NPA.HAS_CURRENT_TRUST_STATE, trustStateIri, NPA.GRAPH);

    // 4. (Phase 5) Prune graphs beyond retention — see below

    conn.commit();
}
TrustStateRegistry.get().setCurrentHash(hash);
```

The whole transaction is atomic: readers see either the old pointer (still pointing at a fully-intact old graph) or the new pointer (pointing at the fully-loaded new graph). Never in between.

Trust data doesn't need XOR-checksum/count tracking — no individual nanopub loading happens in this repo, so the `NPA.HAS_NANOPUB_COUNT` / `NPA.HAS_NANOPUB_CHECKSUM` triples added by `initNewRepo` stay at their initial values (same as `last30d`).

### Phase 5: Retention

Inside the same materialization transaction, prune old trust-state graphs beyond the retention window:

```java
// Find states by counter, drop all but the N most recent.
// "Drop" = clear the state's graph + remove its metadata triples from npa:graph.
```

Retention is configurable via env var `TRUST_STATE_LOCAL_RETENTION` (default e.g. 5). Small enough that disk cost is negligible; large enough to allow brief audit/debugging and to cushion any in-flight queries against the previous state.

Retention = 1 is valid (keep only current). Retention = 0 would mean "no history" — reject as a config error.

### Phase 6: Bootstrap on startup

In `MainVerticle` startup, after the existing nanopub-loader init:

1. Read current pointer from the `trust` repo: `SELECT ?s { GRAPH npa:graph { npa:thisRepo npa:hasCurrentTrustState ?s } }`
2. If a pointer exists → derive the hash from the IRI (strip the `http://purl.org/nanopub/admin/truststate/` prefix), seed `TrustStateRegistry` with that hash
3. The existing `loadUpdates` poll will discover any newer registry hash on its next cycle and fire a materialization

Initial deployment (no pointer): `loadUpdates` discovers the registry's hash, fetches, materializes, and creates the first pointer.

## Risks & Mitigations

1. **Partial materialization on crash:** The whole materialization + pointer swap runs in one serializable transaction, so a crash mid-transaction rolls back cleanly. No orphans possible.
2. **Registry unreachable at startup:** Don't block startup. Authority-checking code that runs without trust state should degrade visibly (log warning, treat all admin claims as unverifiable until state arrives). Better than refusing to serve any queries.
3. **Hash returns 404 (snapshot pruned):** Skip; next poll will see a newer hash. Acceptable behavior — we lose visibility into one window of state changes but recover on the next valid hash.
4. **Pointer-update race:** `SERIALIZABLE` isolation in the `trust` repo, plus the in-memory registry only updating after commit, prevents inconsistent views across concurrent reads.
5. **`createdAt` zone-bracket parsing:** Java's `ZonedDateTime.parse` accepts the bracket form natively, but `xsd:dateTime` doesn't. Strip-then-format. Test explicitly.
6. **`status` value drift:** Don't filter at load time; surface all values as `npa:<status>` IRIs. If the registry adds new statuses later, queries can adapt without a loader change (the loader just mints whatever IRI the string maps to — no enumeration is hardcoded).
7. **Status-semantics confusion:** The registry's status set is wider than just `loaded`/`toLoad`. `skipped` in particular means "explicitly rejected by trust calculation" — a query that treats it as approved silently lets rejected accounts through. Authority queries should always match a positive list (e.g. `?status IN (npa:loaded, npa:toLoad)`), never "not skipped".

## Verification

1. Unit tests for `TrustStateRegistry` (Phase 1)
2. Unit tests for envelope parsing (Phase 3): real fixture from `localhost:9292`, verify URL-hash vs body-hash equality check, BSON-extended-JSON counter unwrap, `[Etc/UTC]`-bracket timestamp conversion
3. Local end-to-end (after Phase 4): point at `http://localhost:9292/`, watch logs for "Materialized trust state <hash>" within ~2 s of startup, inspect RDF4J workbench `trust` repo — named graph `<http://purl.org/nanopub/admin/truststate/<hash>>` should be populated, and `npa:graph` should contain the current-pointer triple
4. Trigger a registry-side rollover (or restart with new data), verify nanopub-query picks up the new hash on the next 2 s poll, adds the new graph, swaps the pointer, prunes any state beyond retention
5. Kill the registry mid-operation: `loadUpdates` already handles this (exceptions in metadata fetch are logged and retried next cycle) — trust state loading inherits the same resilience

## Out of Scope

- **Trust state persistence beyond the snapshot.** We just mirror what the registry says. No local computation, no cross-validation, no merging multiple registries.
- **Authority validation logic itself.** That lives in #62 Phase 2B (admin/maintainer detection) and queries the trust state via SPARQL. Out of this issue.
- **Multi-registry support.** Single registry source for now. If the deployment ever points at multiple registries, that's a follow-up.

## Implementation Order (Steps)

1. **Step 1:** `TrustStateRegistry` skeleton + unit tests (Phase 1)
2. **Step 2:** Extend `RegistryMetadata` with `trustStateHash`, wire hash-change detection into `loadUpdates` + `loadInitial`, call a stub `TrustStateLoader.maybeUpdate` that just logs (Phase 2; no fetch/materialize yet)
3. **Step 3:** Snapshot fetch + envelope parsing into POJO (Phase 3; unit tests against a captured fixture from `localhost:9292`)
4. **Step 4:** Triple materialization into the `trust` repo using the named-graph schema (Phase 4) — single transaction including the pointer swap
5. **Step 5:** Retention pruning inside the materialization transaction (Phase 5)
6. **Step 6:** Bootstrap from existing pointer on startup (Phase 6)

Each step ships independently and is verifiable end-to-end against the live registry.
