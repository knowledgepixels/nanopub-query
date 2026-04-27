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

   For backwards compatibility, nanopubs typed with any of the following currently-used properties are also treated as `gen:RoleInstantiation` nanopubs (temporary; to be dropped at a later point). The registry indexes single-triple-assertion nanopubs with their predicate IRI as an additional `npx:hasNanopubType`, so no separate assertion-predicate scan is needed — the standard type-based ingestion path delivers them:

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

3. **One space-state graph**, `npass:<trustStateHash>_<loadCounterAtBuildStart>`, holding the validated closures under the current trust state (see [Space state graph](#space-state-graph)). Extended incrementally via SPARQL UPDATE driven by load-number deltas; fully rebuilt (into a new graph IRI) on trust-state flip or on the periodic-rebuild signal.

Every extraction uses a dedicated subject IRI per entry — derived from the originating nanopub's artifact code so subjects never collide with user nanopub IRIs, role IRIs, or anything else a nanopub might declare types on. Prefixes:

- `npari:` = `<http://purl.org/nanopub/admin/roleinst/>` — subject for `gen:RoleInstantiation` entries
- `npara:` = `<http://purl.org/nanopub/admin/roleassign/>` — subject for `gen:hasRole` (role-attachment) entries
- `npard:` = `<http://purl.org/nanopub/admin/roledecl/>` — subject for `npa:RoleDeclaration` entries (extracted from `gen:SpaceMemberRole` nanopubs)
- `npadef:` = `<http://purl.org/nanopub/admin/spacedef/>` — subject for `npa:SpaceDefinition` entries (per contributing `gen:Space` nanopub)
- `npainv:` = `<http://purl.org/nanopub/admin/invalidation/>` — subject for `npa:Invalidation` entries

Each entry carries `npa:viaNanopub <originatingNP>` to link back to the source; the stamped load number goes on that nanopub IRI so all of a nanopub's emitted entries share one stamp:

```turtle
  <thisNP> npa:hasLoadNumber <N> .
```

where `N` is the nanopub-query load counter at extraction time. The `spaces` repo's `npa:graph` also tracks `npa:thisRepo npa:currentLoadCounter <N>` so the materializer knows the current horizon.

In addition, every extraction entry carries a `dct:created` timestamp (prefix `dct:` = `<http://purl.org/dc/terms/>`) copied from the source nanopub's pubinfo `dct:created`, so consumers can sort entries chronologically without a JOIN to the raw nanopub. Matches the pattern already used in admin graphs of other repos.

### Triples added per `gen:Space` nanopub

Prefix: `npas:` = `<http://purl.org/nanopub/admin/space/>`. A space ref `<NPID>_<SPACEIRIHASH>` becomes the IRI `npas:<NPID>_<SPACEIRIHASH>`.

Two entries are emitted. An aggregate `npa:SpaceRef` (one per space ref, space-identity information only — no per-contributor fields, so no provenance-based cleanup needed) and a per-nanopub `npa:SpaceDefinition` that carries every triple specific to this particular contributing nanopub:

```turtle
GRAPH npa:spacesGraph {
  # Aggregate — space-identity; multiple contributing nanopubs reinforce the same triples
  npas:<spaceRef> a npa:SpaceRef ;
                  npa:spaceIri    <spaceIRI> ;
                  npa:rootNanopub <rootNP> .    # object of gen:hasRootDefinition; <thisNP> if the triple is absent

  # Per-contributor — one per loaded gen:Space nanopub for this space ref
  npadef:<artifactCode> a npa:SpaceDefinition ;
                        npa:forSpaceRef npas:<spaceRef> ;
                        npa:viaNanopub  <thisNP> ;
                        npx:signedBy    <publishingAgent> ;
                        npa:pubkeyHash  "<pubkeyHash>" ;
                        dct:created     "<timestamp>"^^xsd:dateTime .
}
```

For update nanopubs `<rootNP>` equals the original root (not `<thisNP>`), consistent with the root's own self-referential `gen:hasRootDefinition`. Every contributing nanopub reinforces the same `npa:spaceIri`/`npa:rootNanopub` triples on the aggregate (RDF set semantics).

Because each `npadef:` entry carries `npa:viaNanopub`, invalidating a `gen:Space` nanopub cleans it up via the standard DELETE-on-`npa:viaNanopub` path: signer/pubkey/created for that contributor go away, and the root's trust seed goes with it (see below). The aggregate `npas:<spaceRef>` remains — it's space-identity, not per-contributor.

Trust seeding is per space ref, so in the rootless transition case each declaration becomes its own root and creates its own space ref. When multiple space refs exist for the same Space IRI, Nanodash resolves to the one whose root definition has the earliest `dct:created` — first-root-wins, deterministic across time. Pattern: `ORDER BY ?created LIMIT 1` on `?spaceRef npa:rootNanopub ?root. ?def npa:viaNanopub ?root; dct:created ?created`.

For every `gen:Space` nanopub carrying one or more `gen:hasAdmin` triples in its assertion, additionally emit one `gen:RoleInstantiation` entry covering all asserted admins as multi-valued `npa:forAgent`:

```turtle
  npari:<artifactCode> a gen:RoleInstantiation ;
                       npa:forSpace        <spaceIRI> ;
                       npa:regularProperty gen:hasAdmin ;
                       npa:forAgent        <adminAgent1>, <adminAgent2> ;
                       npa:viaNanopub      <thisNP> ;
                       npx:signedBy        <publishingAgent> ;
                       npa:pubkeyHash      "<pubkeyHash>" ;
                       dct:created         "<timestamp>"^^xsd:dateTime .
```

where `<artifactCode>` is the trusty-URI artifact code of `<thisNP>`.

So admins asserted in any `gen:Space` nanopub (root or update) show up in the same SPARQL pattern consumers use for ordinary admin role instantiations.

If the loaded nanopub is additionally the space's root — detectable by `npa:rootNanopub <thisNP>` on its `npas:<spaceRef>` — also attach the trust-seed admin agents to the `npadef:` entry (not to the aggregate `npas:<spaceRef>`):

```turtle
  npadef:<artifactCode> npa:hasRootAdmin <adminAgent1>, <adminAgent2> .
```

These are the trust seed for the admin closure — trusted by construction because the root's NPID is part of the space ref, so no publisher-agent validation is needed. In the rootless transition case the nanopub is its own root, so the same rule applies and its admins seed the per-declaration space ref. Invalidating the root gen:Space nanopub DELETEs the `npadef:` entry (via `npa:viaNanopub`), taking the `npa:hasRootAdmin` seeds with it.

### Triples added per `gen:hasRole` nanopub

All attachments are emitted into `npa:spacesGraph`; validation (publisher must be in the admin closure of the target space) happens in the space-state-graph materialization step.

```turtle
GRAPH npa:spacesGraph {
  npara:<artifactCode> a gen:RoleAssignment ;
                       npa:forSpace    <spaceIRI> ;
                       gen:hasRole     <roleIri> ;
                       npa:viaNanopub  <thisNP> ;
                       npx:signedBy    <publishingAgent> ;
                       npa:pubkeyHash  "<pubkeyHash>" ;
                       dct:created     "<timestamp>"^^xsd:dateTime .
}
```

Prefix: `npx:` = `<http://purl.org/nanopub/x/>`. `npa:forSpace` points to the Space IRI (not the space-ref form), as used in the source nanopub's assertion. The attached `<roleIri>` is the IRI of a role instance defined in some `gen:SpaceMemberRole` nanopub; consumers JOIN against that def for the role's predicates and tier.

A single role (one `gen:SpaceMemberRole` nanopub) may be attached to multiple spaces via separate `gen:hasRole` nanopubs — each attachment is independent, gated by the respective space's admin closure, and produces its own `npa:RoleAssignment` entry.

### Triples added per `gen:SpaceMemberRole` nanopub

Role instances are *embedded* (not introduced) in their defining nanopub, so each one mints a new role IRI. The role-defining triples are summarized into `npa:spacesGraph` as an `npa:RoleDeclaration` entry:

```turtle
GRAPH npa:spacesGraph {
  npard:<artifactCode> a npa:RoleDeclaration ;
                       npa:role               <roleIri> ;
                       npa:hasRoleType        <gen:MaintainerRole | gen:MemberRole | gen:ObserverRole> ;
                       gen:hasRegularProperty <regularPropIRI> ;   # one per occurrence
                       gen:hasInverseProperty <inversePropIRI> ;   # optional, one per occurrence
                       npa:viaNanopub         <thisNP> ;
                       dct:created            "<timestamp>"^^xsd:dateTime .
}
```

Prefix: `gen:` = `<https://w3id.org/kpxl/gen/terms/>`. `npa:role` carries the actual role IRI for consumer JOINs; `npa:hasRoleType` carries the tier class.

The tier value for `npa:hasRoleType` comes from whatever subclass of `gen:SpaceMemberRole` (`gen:MaintainerRole`, `gen:MemberRole`, or `gen:ObserverRole`) the role is typed as in the source assertion. If the assertion declares none, default to `gen:ObserverRole`.

**No publisher-validation gate** — any nanopub with a valid embedded role IRI produces an `npa:RoleDeclaration`. A declaration is inert on its own and only becomes operative once attached to a space via a validated `gen:hasRole` nanopub.

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
                       npa:pubkeyHash      "<pubkeyHash>" ;
                       dct:created         "<timestamp>"^^xsd:dateTime .
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
                        npa:pubkeyHash   "<pubkeyHash>" ;
                        dct:created      "<timestamp>"^^xsd:dateTime .
}
```

Second-order invalidations are ignored — an invalidation whose target is itself already invalidated has no effect.

## Space state graph

A single space-state graph (prefix `npass:` = `<http://purl.org/nanopub/admin/spacestate/>`) holds the current validated state. Its IRI is:

```
npass:<trustStateHash>_<loadCounterAtBuildStart>
```

Underscore-joined, human-decodable by splitting on `_` (same shape as the `<NPID>_<SPACEIRIHASH>` space-ref form). `<loadCounterAtBuildStart>` is the value of `npa:thisRepo npa:currentLoadCounter` captured when this particular full build kicked off — so incremental cycles within the graph leave the name unchanged; only a full build (trust-state flip or periodic rebuild — see below) mints a new graph IRI.

Current-state pointer in `npa:graph`:

```turtle
GRAPH npa:graph {
  npa:thisRepo npa:hasCurrentSpaceState npass:<trustStateHash>_<loadCounterAtBuildStart> .
}
```

The graph contains two parts:

1. **Mirrored trust state** — copy of the trust-approved `(agent, pubkey)` rows from `npat:<trustStateHash>`, inline. "Trust-approved" means `npa:trustStatus` ∈ `{npa:loaded, npa:toLoad}` (the positive set per [design-trust-state-repos.md](design-trust-state-repos.md); `npa:contested`, `npa:skipped`, and the transient statuses are distinct values of the same `npa:trustStatus` predicate and are excluded automatically). Inline rather than federated so per-tier UPDATEs join purely locally.
2. **Validated closures** — `gen:RoleInstantiation` and `gen:RoleAssignment` entries copied over from `npa:spacesGraph` once they pass tier validation, one per validated nanopub. Each entry keeps its `npari:` / `npara:` subject IRI and carries `npa:viaNanopub <originatingNP>`, so invalidation cleanup matches entries by their originating nanopub.

Progress counter for incremental updates, stored inside the state graph itself so it drops atomically with `DROP GRAPH npass:<oldId>`:

```turtle
GRAPH npass:<trustStateHash>_<loadCounterAtBuildStart> {
  npass:<trustStateHash>_<loadCounterAtBuildStart> npa:processedUpTo <N> .
}
```

`<N>` starts at `<loadCounterAtBuildStart>` and is bumped by incremental cycles.

### Validation rule

All tier checks (authority evidence for admin/maintainer/member; self-evidence for observer) resolve the publisher via `(agent, pubkey)` pairs in the mirrored rows — i.e. the signing key on the extraction (`npa:pubkeyHash`) must match an `npa:AccountState` whose `npa:agent` is the agent being checked. `npx:signedBy` is informational only (self-declared from pubinfo) and never decides validity.

Since the registry's trust calculation flags any pubkey that claims multiple agents as `npa:contested` (and contested rows are not in the mirrored set), a trust-approved pubkey in `npass:<ts>` resolves to exactly one agent. No multi-agent ambiguity to handle at validation time.

**Invalidation filter (applies to every read from `npa:spacesGraph`).** Tier INSERTs, the admin-seed query, and `gen:hasRole` attachment validation all add:

```sparql
FILTER NOT EXISTS {
  ?inv a npa:Invalidation ; npa:invalidates ?np .
}
```

where `?np` is the source nanopub of the candidate being considered (`?entry npa:viaNanopub ?np`, or `?def npa:viaNanopub ?np` for the admin-seed query). This keeps `npa:spacesGraph` purely add-only — invalidations add an `npa:Invalidation` record but never delete prior extractions — while ensuring no query ever admits a candidate whose source has been retracted, regardless of arrival order. Covers the out-of-order case where an invalidation lands before its target.

### Mirror step

Executed in Java by `AuthorityResolver`, not via SPARQL SERVICE. Open a `RepositoryConnection` on each side; read trust-approved account-state rows from `npat:<trustStateHash>` in the `trust` repo; write the filtered rows into the new `npass:<trustStateHash>_<loadCounterAtBuildStart>` graph in the `spaces` repo, inside one spaces-side transaction:

```java
try (var trustConn = trustRepo.getConnection();
     var spacesConn = spacesRepo.getConnection()) {
    spacesConn.begin();
    try (var rows = trustConn.getStatements(null, NPA.TRUST_STATUS, null, trustStateIri)) {
        for (Statement s : rows) {
            if (!APPROVED_SET.contains(s.getObject())) continue;  // {npa:loaded, npa:toLoad}
            // copy (?accountState a npa:AccountState; npa:agent ...; npa:pubkey ...;
            //       npa:trustStatus ?status) into npass:<trustStateHash>_<loadCounterAtBuildStart>
        }
    }
    spacesConn.commit();
}
```

Keeps the spaces-repo transaction local, avoids SERVICE fragility, and matches the existing `TrustStateLoader` pattern (also Java-side cross-source copy).

## Update flow

Incremental and add-only at the raw layer; incremental at the space-state layer too, with full rebuild on trust-state flips *and* on periodic rebuild triggers (see [Periodic full rebuild](#periodic-full-rebuild)). All full rebuilds run in parallel with reads by writing to a brand-new graph and flipping the pointer atomically.

### Full build

Triggered by (a) a trust-state flip (the trust repo's `npa:hasCurrentTrustState` changes), or (b) the periodic-rebuild flag being set. Sequence:

1. Capture `M = currentLoadCounter` and `T = currentTrustStateHash`. The new graph's IRI is `npass:<T>_<M>`.
2. Mirror the trust-approved rows (see [Mirror step](#mirror-step)) from `npat:<T>` into the fresh `npass:<T>_<M>` graph.
3. Run the per-tier UPDATE loops from scratch on `npass:<T>_<M>`:
   - **Admin**: seed from `npadef:<…> npa:hasRootAdmin` (root-definition trust seeds); fixed-point INSERT every `gen:RoleInstantiation` with `npa:regularProperty gen:hasAdmin` whose `npa:pubkeyHash` resolves (via the mirrored rows) to an agent already in the admin set for that space.
   - **`gen:hasRole` validation**: INSERT every `gen:RoleAssignment` whose publisher is in the admin set of the target space. These validated attachments define which role predicates are active per space.
   - **Maintainer**: INSERT every `gen:RoleInstantiation` whose predicate is the `gen:hasRegularProperty` (or `gen:hasInverseProperty`) of an `npa:RoleDeclaration` with `npa:hasRoleType gen:MaintainerRole` attached to the target space, and whose publisher is in the admin set (or existing maintainer set). Fixed-point.
   - **Member / Observer**: same pattern, tiered publisher constraints. Observer additionally accepts **self-evidence**: the instantiation's `(npa:pubkeyHash, npa:forAgent)` pair matches an `npa:AccountState` in the mirrored rows — meaning the publisher truly is the assignee under the current trust state.
4. Set `processedUpTo` to `M` inside the new graph.
5. Flip `npa:thisRepo npa:hasCurrentSpaceState` to `npass:<T>_<M>`.
6. `DROP GRAPH npass:<previousId>` and clear `npa:needsFullRebuild` if set.

Concurrency: readers keep hitting the old graph (via the pointer) throughout steps 1–4; they only see the new graph after step 5. Incremental cycles can keep running on the old graph during the build — their work is discarded when the pointer flips, but the first incremental cycle on the new graph picks up `(M, currentLoadCounter]` and extends it.

### Incremental update (same trust state, new raw activity)

Triggered by a space-relevant nanopub load or invalidation (which bumps `currentLoadCounter`). Runs as a single cycle bounded by `(processedUpTo, currentLoadCounter]`. In the SPARQL sketches below, `<ts>` stands for the current space-state graph IRI (resolved via `npa:hasCurrentSpaceState` at cycle start).

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
2. Apply the tier INSERTs in the same order as the full build — admin → `gen:hasRole` validation → maintainer → member → observer. Each INSERT joins the candidate extraction entry to its originating nanopub's load number via `npa:viaNanopub` and filters by `FILTER(?ln > ?lastProcessed)`, plus a `FILTER NOT EXISTS` against the current space-state contents. Iterate to fixed point within the cycle. Sketch (admin tier):
   ```sparql
   INSERT { GRAPH npass:<ts> { ?ri a gen:RoleInstantiation ;
                                   npa:forSpace ?space ;
                                   npa:forAgent ?agent ;
                                   npa:viaNanopub ?np . } }
   WHERE {
     GRAPH npa:spacesGraph {
       ?ri a gen:RoleInstantiation ;
           npa:forSpace        ?space ;
           npa:regularProperty gen:hasAdmin ;
           npa:forAgent        ?agent ;
           npa:pubkeyHash      ?pkh ;
           npa:viaNanopub      ?np .
       ?np npa:hasLoadNumber ?ln .
       FILTER (?ln > ?lastProcessed)
       # Invalidation filter (per Validation rule)
       FILTER NOT EXISTS { ?inv a npa:Invalidation ; npa:invalidates ?np . }
     }
     # Authority evidence: publisher resolves to an existing admin of ?space
     GRAPH npass:<ts> {
       ?acct a npa:AccountState ;
             npa:agent  ?publisher ;
             npa:pubkey ?pkh .
       {
         # Seed: a root-definition entry's hasRootAdmin for this space
         GRAPH npa:spacesGraph {
           ?def a npa:SpaceDefinition ;
                npa:forSpaceRef [ npa:spaceIri ?space ] ;
                npa:hasRootAdmin ?publisher ;
                npa:viaNanopub   ?defNp .
           FILTER NOT EXISTS { ?inv a npa:Invalidation ; npa:invalidates ?defNp . }
         }
       }
       UNION
       { ?prev a gen:RoleInstantiation ;
               npa:forSpace ?space ;
               npa:regularProperty gen:hasAdmin ;
               npa:forAgent ?publisher . }                        # closed-over
     }
     FILTER NOT EXISTS { GRAPH npass:<ts> {
       ?ri a gen:RoleInstantiation ; npa:forSpace ?space ; npa:forAgent ?agent .
     } }
   }
   ```
   For the admin closure's seed triples (`npadef:<…> npa:hasRootAdmin <agent>`), the load-number filter is the usual `?def npa:viaNanopub ?np. ?np npa:hasLoadNumber ?ln. FILTER(?ln > ?lastProcessed)` — same shape as every other extraction entry. Maintainer / member / observer tiers follow the same shape: join `?entry npa:viaNanopub ?np. ?np npa:hasLoadNumber ?ln. FILTER(?ln > ?lastProcessed)`, swap the tier-specific publisher constraints.
3. Bump `processedUpTo` to the max load number consumed.

**Late-arrival handling.** A candidate whose enabling structural event (admin grant, `gen:hasRole` attachment, or `npa:RoleDeclaration`) hasn't happened yet at its own load-time gets filtered by `FILTER(?ln > ?lastProcessed)` and is never retried via delta-only scans. Rule: if a cycle's INSERTs add any new admin grant, validated attachment, or role declaration, immediately re-run the *downstream* tier INSERTs without the load-number filter on the candidate side (keeping only `FILTER NOT EXISTS` dedup). This catches candidates that landed before their enabler, at the cost of a full-scan pass on cycles with structural changes. Pure instantiation-add cycles stay on the fast delta path.

Loader and materializer decouple: the loader keeps appending raw triples with incrementing load numbers, the materializer processes deltas in small cycles. No global CLEAR.

Triggers coalesce into a pending-cycle flag (distinct from `npa:needsFullRebuild`); if a trigger arrives mid-cycle, the flag re-fires and another cycle runs after commit.

### Revocation semantics

Sticky and non-cascading: invalidating a grant removes only the derivation keyed on that nanopub. Other grants to the same agent persist. Second-order invalidations are ignored.

Invalidating a `gen:SpaceMemberRole` nanopub removes its `npa:RoleDeclaration` from `npass:<ts>` via the standard per-`npa:viaNanopub` DELETE. Previously-derived instantiations under that role stay (sticky); new instantiations can no longer be derived because either the JOIN against `npa:RoleDeclaration` fails (at full rebuild, when the declaration-reading query applies the invalidation filter) or the filter directly excludes instantiations whose source was retracted.

`npa:spacesGraph` stays purely add-only: invalidations add `npa:Invalidation` records but never delete prior extraction entries. Cleanup happens only in `npass:<ts>` (DELETE-on-viaNanopub) and via the invalidation filter on every extraction-graph read (per [Validation rule](#validation-rule)).

### Trust-state flip always means full rebuild

Load-number incremental only applies *within* a trust state. A flip changes which pubkeys map to which agents, invalidating all derivations. The full-build procedure above handles it: new `npass:<newT>_<M>` graph, pointer flip, drop old.

### Periodic full rebuild

Incremental updates cover pure addition cleanly, but invalidations of *structural* derivations (admin grants, validated `gen:hasRole` attachments, role declarations) leave sticky downstream entries in the current space-state graph that would only be re-evaluated on a from-scratch rebuild. To bound that staleness without cascading-DELETE complexity, a flag + periodic worker:

- Flag triple in `npa:graph`: `npa:thisRepo npa:needsFullRebuild true`.
- The incremental cycle sets the flag whenever it DELETEs a structural derivation:
  - any admin-tier `gen:RoleInstantiation` (publisher lost admin status),
  - any `gen:RoleAssignment` attachment (a role was detached from a space),
  - any `npa:RoleDeclaration` (a role definition was retracted).
  
  Invalidations that only remove leaf derivations (non-admin tier instantiations) don't set the flag.
- A periodic worker (configurable interval, default in the 5–15 min range, roughly aligned with trust-state-flip cadence) checks the flag. If set: run the full-build procedure above (same trust state, fresh `npass:<T>_<M>` with `M = currentLoadCounter`), flip the pointer, drop the old graph, clear the flag.

Readers are never blocked: during the worker's build the pointer still references the previous graph; the swap is atomic. Wasted incremental work during the rebuild is bounded by rebuild duration.

### Operational details

**Flag-setting for `npa:needsFullRebuild`.** Run the invalidation step as three separate DELETE sub-queries, one per structural kind (admin-tier `gen:RoleInstantiation`, `gen:RoleAssignment`, `npa:RoleDeclaration`). Precede each with an `ASK` using the same WHERE clause; if the ASK is true, set `npa:needsFullRebuild true` in `npa:graph` after the DELETE commits. Leaf-tier instantiation DELETEs do not set the flag.

**Rebuild serialization.** All full rebuilds (trust-state flip, periodic flag) run one at a time under a single materializer mutex. A trigger arriving during a build is coalesced: it either waits for the in-progress rebuild to commit or re-fires afterward.

**Crash recovery.** On startup, the materializer drops any `npass:*` graph that `npa:hasCurrentSpaceState` does not point at. Orphan graphs from interrupted builds are cleaned without manual intervention.

**Load-counter invariant.** `npa:thisRepo npa:currentLoadCounter` is expected to persist monotonically across process restarts (nanopub-query guarantees this today); `processedUpTo` values rely on it. If the invariant ever breaks, incremental cycles may skip deltas silently.

**Trust-state flip detection.** The materializer reads `npa:thisRepo npa:hasCurrentTrustState` in the `trust` repo at each rebuild-worker wakeup. A changed hash triggers the full-build path; no dedicated notification channel is needed.

## Implementation phases

1. **Raw loading** — `TripleStore` init, loader writes full nanopubs of predefined types into `spaces` and emits add-only extraction triples (including `npa:Invalidation` entries) into `npa:spacesGraph` with `npa:hasLoadNumber` stamps.
2. **Materialization** — new `AuthorityResolver` drives per-tier SPARQL UPDATE loops on load-number deltas for incremental updates; runs full rebuilds on trust-state flips and on the periodic `npa:needsFullRebuild` signal; manages the `npa:hasCurrentSpaceState` pointer and old-graph cleanup.
3. **Routes / metrics** — `/spaces` listing route (HTML + JSON), Prometheus gauges (rebuild duration, delta size, `processedUpTo` lag, distinct-subject totals).
4. **Nanodash migration** — publish with `gen:hasRootDefinition` and the predefined type IRIs; replace the 4-query chain with one query that resolves the current `npass:*` graph from the pointer (see [Querying the current space-state graph](#querying-the-current-space-state-graph)); drop `isAdminPubkey` gate and pinned templates/queries.

## Bootstrap

On first deployment, scan `meta` / `full` for the predefined types, load each matching nanopub whole into `spaces`, run one rebuild.

## Key files

| File | Role |
|------|------|
| `NanopubLoader.java` | Type-match + full-nanopub write + extraction into `npa:spacesGraph` + load-number stamping + trigger the materializer |
| `TripleStore.java` | `spaces` repo init |
| `MainVerticle.java` | `/spaces` listing route (HTML + JSON) |
| `MetricsCollector.java` | Rebuild duration, delta size, `processedUpTo` lag |
| **New:** `AuthorityResolver.java` | Mirror step, per-tier SPARQL UPDATE loops on load-number deltas, first-build on trust-state flips, pointer flip and old-graph drop |

## Querying the current space-state graph

Consumers (Nanodash, ad-hoc SPARQL users) resolve the live graph in-query by joining on the pointer in `npa:graph`:

```sparql
PREFIX npa: <http://purl.org/nanopub/admin/>
SELECT ... WHERE {
  GRAPH <http://purl.org/nanopub/admin/graph> {
    <http://purl.org/nanopub/admin/thisRepo> npa:hasCurrentSpaceState ?g .
  }
  GRAPH ?g {
    # actual space-state pattern here
  }
}
```

This is the *only* recommended pattern. Do **not** resolve the pointer in a separate request and then issue a second query against a frozen `npass:<hash>_<n>` IRI: a trust-state flip between the two requests will leave the second query reading an orphaned graph that is about to be dropped (or already gone). Resolving the pointer in the same SPARQL transaction as the actual read is atomic across flips.

Two consequences of using this pattern:

- A consumer never needs to know the trust-state hash or load counter — those are internal to the materializer.
- There is no `for-space` HTTP redirect: it would have to resolve the pointer at request time and return a frozen graph IRI, reintroducing the same race the in-query pattern is designed to avoid.

## Verification

- Unit: closures, incremental delta correctness (same result as a full rebuild), invalidation cleanup (sticky, non-cascading), rebuild idempotence.
- Integration: space definition, admin chain depth ≥ 2, role definition + attachment + instantiation, supersession, trust-state flip (full rebuild + pointer flip + old-graph drop), root retraction.
- End-to-end: Space page renders from `spaces` alone; steady-state reads do not `SERVICE`-join to `trust` (the mirror step runs at trust-state-flip time via Java-level cross-repo copy, not at read time).
