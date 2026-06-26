# Design: revoke & re-assign space roles (authorization-scoped latest-wins)

Status: **implemented** (issue [#129]; branch `feat/issue-129-role-revocation`), server side only.
Scope: this document covers the **nanopub-query** work. The **Nanodash** side needs publishing templates for the
two new vocabulary terms (see §8) — tracked separately.

## 1. Context & goal

Before this change a granted space role could be revoked **only by the original granter** — the same signing key,
via `npx:invalidates` gated by `samePublisherClause` (issue #112, `AuthorityResolver.samePublisherClause`). So an
admin could not revoke a member another admin admitted, and a member could not leave on their own.

The goal is to support **revocation** and **re-assignment** of roles at the *key* level, reusing the
authorization-scoped latest-wins model already proven for preset assignments (issue #302, see
`design-preset-role-materialization.md`). `npx:invalidates` stays for *nanopub-level* undo ("this exact statement was
a mistake"); the new negatives are *key-level* ("the agent no longer holds this role, however it was granted").

**Model.** The state of an `(agent, role, spaceRef)` key (instantiation) or `(role, spaceRef)` key (attachment) is
the sign of the **latest authorized assertion** by `dct:created`, with a subject-IRI tiebreak, **active-by-default**.
This mirrors `gen:DeactivatedPresetAssignment` (`GEN.java`): a revocation is just a newer authorized row that happens
to be negative; re-assignment is a newer positive row; toggling falls out for free.

## 2. Vocabulary (two new, previously-unused terms)

### Instantiation revocation — `gen:RevokedRoleInstantiation`

A typed node, in its own nanopub, keyed on `(space, agent, role)`:

```turtle
:r a gen:RevokedRoleInstantiation ;
   gen:forSpace <space> ;
   gen:forAgent <agent> ;     # whose role is revoked
   gen:hasRole  <roleIRI> .   # the role revoked (version-pinned, matches the materialized key)
# pubinfo carries dct:created (the latest-wins key) + the signature (= the revoker)
```

One form covers every tier. A predicate / back-compat role only materializes via a declared `gen:hasRole`
attachment, so every non-admin role has a role IRI to key on. **Admin is keyed on `gen:hasRole gen:AdminRole`**
(decision D1, §6) — the admin tier carries no per-role IRI, so the admin role *type* serves as the role key,
matching the `npa:hasRoleType gen:AdminRole` the admin tier stamps.

(`GEN.java` also gains the input predicates `gen:forSpace` / `gen:forAgent` used by this node shape.)

### Attachment revocation — `gen:detachedRole`

A single predicate triple, keyed on `(space, role)` — the stative antonym of `gen:hasRole`:

```turtle
<space> gen:detachedRole <roleIRI> .   # role no longer available in the space; admin-authored
```

Auto-typed like `gen:hasRole` (single-predicate-assertion). A winning detachment removes the role's availability, so
direct **and** preset-derived attachments — and the instantiations anchored on them — drop out (the cascade).

### State-side extraction terms (`SpacesVocab.java`)

`npa:RoleRevocation` / `npa:RoleDetachment` (RDF types on extraction rows), `npa:revokedRole` (the role IRI carried),
and minters `forRoleRevocation(artifactCode, agent⊕role hash)` / `forRoleDetachment(artifactCode, role hash)` in new
`nparev:` / `npadet:` namespaces (so negatives never collide with the `npari:` / `npara:` positives of the same
nanopub).

## 3. Authorization matrix — who may publish a *winning* assertion

| Target role | May set the latest (winning) assertion |
|---|---|
| **root admin** | **nobody at runtime** — constitutional (§5) |
| admin | admins |
| maintainer | admins |
| member | admins, maintainers |
| observer | admins, maintainers, members, **+ self** |

- **Self may always *revoke* its own role (leave) at any tier** (`signer == forAgent`), *except* root admins
  (decision D2, §6). Self may only *assign* at observer (self-attest) — unchanged.
- Equivalently for the non-admin tiers: **a revoker must hold a tier strictly higher than the target**
  (admin > maintainer > member > observer), or be the assignee. Admin is the exception (admins revoke admin peers).
- Forgeability is handled exactly as in #302: the candidate set is filtered to authorized publishers *before*
  `MAX(dct:created)`, so a postdated nanopub from an unauthorized key cannot win. Among co-equal admins it is
  "latest *claimed* time wins" — a shared-clock-trust assumption, fine within a mutually-trusting admin set.
- Authorization is evaluated against the **current** materialized tier sets each cycle (self-healing), matching the
  preset precedent — it is not frozen at authoring time (decision D-default).

## 4. Implementation (`AuthorityResolver.java`)

Negatives never materialize as final-state rows. Each is consumed where its key is bound, via a pair:
**(a)** an inline suppression `FILTER NOT EXISTS` in the relevant INSERT tier (prevents (re-)materialization), and
**(b)** a displacement `DELETE` run every cycle in `applyInvalidations` (removes already-materialized rows when the
negative arrives in a later cycle). Both are required because the cycle is incremental and the periodic full rebuild
only runs when `npa:needsFullRebuild` is set.

The latest-wins comparison is `COALESCE(?negCreated, epoch) > COALESCE(?candCreated, epoch)` with a
`STR()` subject tiebreak (decision D3, §6 — missing `dct:created` sorts as epoch).

| Tier / template | Inline filter | Displacement DELETE | Structural? |
|---|---|---|---|
| `nonAdminTierUpdate` (maintainer/member/observer) | `nonAdminRevocationSuppressionFilter(tierClass)` | `roleRevocationDelete` | **Yes** — a revoked maintainer/member is a sub-granting authority (§8) |
| `adminTierUpdate` | `adminRevocationSuppressionFilter` (+ root exemption) | `adminRevocationDelete` (+ root exemption) | **Yes** — admin RIs feed downstream |
| `attachmentValidationUpdate` (direct) | `roleDetachmentSuppressionFilter("attCreated","ra")` | `roleDetachmentDelete` | **Yes** — cascade |
| `presetAttachmentValidationUpdate` (preset-derived) | `roleDetachmentSuppressionFilter("created","pa")` | (same `roleDetachmentDelete`) | **Yes** — cascade |

**Authority arms.** The matrix is encoded **once** in `revocationAuthorityArmsGeneric()` — a UNION of admin / tiered /
self arms keyed on a bound `?tier` (the target role's tier), each higher-tier arm guarded by a `FILTER` on `?tier`.
Both paths consume it: the displacement DELETE binds `?tier` from the row's `npa:hasRoleType`; the inline suppression
filter binds `?tier` to its compile-time `tierClass` (`BIND(<tierClass> AS ?tier)`). One source of truth ⇒ suppression
and re-materialization can never authorize different revokers and flip-flop a row. **Authority derives from the target
tier, never from the positive arm's `publisherConstraint`** — otherwise the split `member(admin-pub)` /
`member(maint-pub)` INSERT arms would each test against only their own publisher class, leaving a hole (the
"multi-arm hazard").

**Aliases.** The negative's named space is matched against any IRI denoting the ref — its canonical
`npa:spaceIri` or a validated `npa:sameAsSpace` alias (issue #113) — so an alias-named revocation/detachment is not a
silent no-op. Revocation does not propagate to sub-spaces (per-ref keying isolates this).

**Detachment cascade.** A winning `gen:detachedRole` deletes the `(ref, role)` `gen:RoleAssignment` rows (direct +
preset-derived) and sets `needsFullRebuild`; the instantiations anchored on the removed attachment are dropped on the
next periodic rebuild, where the attachment-tier inline filters keep the role suppressed. Non-sticky (decision
D5): a newer attachment / preset assignment (newer `dct:created`) out-ranks the detachment and re-attaches.

## 5. Root admins are un-revokable (strict)

Root admins (`npa:hasRootAdmin` in the root `gen:Space` nanopub) are **constitutional**: sourced only from the
root-def seed (#110), never assignable or revocable at runtime. Implemented by **deriving on demand**, not a stamp —
both the admin inline filter and `adminRevocationCheckWhere` carry a nested guard:

```sparql
FILTER NOT EXISTS {
  GRAPH <spacesGraph> {
    ?def a npa:SpaceDefinition ; npa:forSpaceRef ?spaceRef ; npa:hasRootAdmin ?agent .
  }
}
```

so any revocation against a root admin is structurally inert. This **overrides self-leave** (decision D2): a root
admin who wants out must migrate the ref. No re-stamp, no re-ingest, zero consumer impact.

## 6. Decisions

| # | Decision | Resolution |
|---|---|---|
| D1 | Admin-revocation shape (admin tier has no `gen:hasRole`) | Carry **`gen:hasRole gen:AdminRole`**; one vocab term, one extraction path; matches the stamped `npa:hasRoleType gen:AdminRole`. Needs a Nanodash template convention (§9). |
| D2 | Can a root admin self-leave? | **No.** The `hasRootAdmin` exemption overrides self-leave; root admins are permanent for the ref's lifetime. |
| D3 | Missing `dct:created` (not currently mandatory; `addProvenance` emits it only when present) | **Treat missing as epoch** (sorts oldest). `COALESCE(?c, epoch)` on both sides; a positive without a timestamp always loses, a negative without one is inert. |
| D6 | Un-revoke via `npx:invalidates`? | **No.** The negative is *not* wrapped in `invalidationFilter`; the only un-revoke path is a newer authorized re-assignment. |
| D5 | `detachedRole` vs preset re-attach | **Non-sticky** plain latest-wins; a newer `PresetAssignment` re-attaches. Sticky opt-out is a v2 follow-up. |

## 7. Backwards compatibility

Fully back-compat, given the unused negative-assignment vocabulary:
- **No existing nanopub is reinterpreted** — nothing already published matches the new terms, so all current
  memberships stay active.
- **Active-by-default overlay, not a rewrite** — with zero negatives in existing data the latest assertion on every
  key is always positive → identical to today. `npx:invalidates` keeps working unchanged.
- **No state-graph shape change** — negatives never materialize as rows; root admins use derive-on-demand. Active
  rows look as today, no consumer repoint.
- **No re-ingest** — extractor + resolver changes take effect on the normal periodic rebuild from already-ingested
  data (a redeploy, not a re-sync).
- **Mixed-version fleet fails open** — un-upgraded servers ignore the unknown vocab and keep the member; full effect
  once the fleet is upgraded.

## 8. Known limitations (surfaced by code review — decide before public rollout)

1. **Fail-open resurrection on revoker departure (model-level; shared with #302).** Authorization is evaluated
   against the *current* materialized role set each cycle. For a *negative* assertion this fails **open**: if the
   revoker later loses the tier that authorized the revocation, the suppression lifts and the revoked grant
   re-materializes on the next rebuild. Concretely: admin B grants X, admin A revokes X, A then leaves → X's grant
   (still authorized by B) reappears, undoing A's revocation. The same property exists in #302 preset deactivation
   (a deactivating admin who leaves reactivates the preset). The threat model treats admin↔admin churn as low-risk
   (mutually-trusting admin set). **Accepted as intended behaviour** (issue author, 2026-06-26): such resurrections
   are fine — no change. Documented here so the property is explicit; revisiting would need sticky-once-applied
   revocations or authorization snapshotting (historical tier membership).
2. **Missing `dct:created` ⇒ a re-grant can be wrong-dropped (decision D3 consequence).** A positive lacking a
   timestamp sorts as epoch and loses to *any* timestamped negative — including an *older* revocation. A legit
   re-grant published without `dct:created` stays invisible until a newer timestamped grant arrives. Mitigation:
   **Nanodash must stamp `dct:created` on every role assign/revoke/attach/detach nanopub** (§9). This is the chosen
   D3 semantics, not a code defect, but it makes the Nanodash stamping requirement load-bearing.
3. **Stale-authorization window for cascades (consistent with existing structural deletes).** A detachment or admin
   revocation removes the directly-keyed row in-cycle but the downstream instantiations anchored on it are only
   dropped on the next periodic full rebuild (`needsFullRebuild`), not immediately. Between the two, those members
   remain queryable. Identical to the existing preset / alias / sub-space convenience-edge policy; bounded by the
   rebuild cadence.
4. **`gen:detachedRole` recognition depends on nanopub typing (same as `gen:hasRole`).** A detachment is dispatched
   via `NanopubUtils.getTypes` — single-predicate-assertion auto-typing or an explicit
   `npx:hasNanopubType gen:detachedRole`. A detachment nanopub with extra assertion triples and no explicit type
   marker would not be recognized. Nanodash templates must publish detachments as single-predicate assertions or with
   the explicit type marker (§9) — the same convention `gen:hasRole` attachments already rely on.

## 9. Nanodash follow-ups (tracked separately)

- Publishing templates for `gen:RevokedRoleInstantiation` (incl. the **`gen:hasRole gen:AdminRole`** admin
  convention) and `gen:detachedRole`. Both **must stamp `dct:created`** (the latest-wins key).
- Canary the role-reading published query nanopubs (`get-space-members` / role listings, the admins listing, the
  ref-scoped preset listing) before merge — a revoked member/observer/admin (non-root) disappears, a detached role's
  holders disappear, a root admin sticks. See `canary-checklist-spaceref-1.15.md` and
  `verify_published_query_consumers`.

## 10. Tests

- `SpacesExtractorTest` — extraction of both negatives (incl. admin `gen:AdminRole`, missing-field no-op,
  `TRIGGER_TYPES` membership).
- `AuthorityResolverRevocationTest` — SPARQL string-contract assertions (latest-wins on `dct:created`, epoch
  fallback, the per-tier authority matrix, the multi-arm hazard, root exemption, no `npx:invalidates`, detachment
  covering direct + preset-derived).
- `AuthorityResolverRevocationBehaviorTest` — in-memory runtime behaviour: inline suppression, re-assignment-wins,
  unauthorized-revoker ignored, maintainer-revokes-member, observer self-leave, displacement delete, detachment
  displacement, admin revocation with root-admin exemption.

## References

- Issue [#129]. Precedents: #302 / #121 / #122 (preset latest-wins), #112 (granter-only gate, superseded for the key
  level), #110 (root-admin seed), #113 (alias authority), #125 / #127 (role-type stamp).
- `design-preset-role-materialization.md`, `design-spaceref-isolation.md`, `design-space-repositories.md`,
  `canary-checklist-spaceref-1.15.md`, `sparql-quirks.md`.

[#129]: https://github.com/knowledgepixels/nanopub-query/issues/129
