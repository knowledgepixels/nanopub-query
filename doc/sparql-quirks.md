# SPARQL quirks to watch for when querying Nanopub Query

Collected gotchas for anyone writing SPARQL (in nanopub-query itself or in
consumers like nanodash) against the RDF4J-backed endpoints.

## `GRAPH ?x { OPTIONAL { ... } }` drops the row when the inner pattern is unmatched

The SPARQL 1.1 spec is ambiguous about the semantics of an `OPTIONAL` as the
sole pattern inside a `GRAPH` block. RDF4J's implementation evaluates the
inner pattern in the named graph and, if it produces no solutions, returns
no solutions for the GRAPH block itself — even though the inner pattern is
optional. Effect: the entire surrounding row is filtered out whenever the
optional triple isn't present.

**Symptom**: rows that "should" come back with `?namespace` (or whatever
the optional binds) left unbound are missing entirely from the result.

**Fix**: pull the OPTIONAL outside the GRAPH wrapper.

```sparql
# Hazardous — drops the row when ?resource has no gen:hasNamespace in ?a
GRAPH ?a { OPTIONAL { ?resource gen:hasNamespace ?namespace } }

# Safe — row is returned with ?namespace unbound
OPTIONAL { GRAPH ?a { ?resource gen:hasNamespace ?namespace } }
```

If multiple optional triples in the same graph are needed, give each its own
`OPTIONAL { GRAPH ?a { … } }` block rather than nesting them under a single
`GRAPH ?a { OPTIONAL … OPTIONAL … }`.

This bit a nanodash spaces-repo consumer twice (see knowledgepixels/nanodash#468)
— once on the maintained-resource namespace lookup, once on the role-load
label/title/name/template lookups.

## Resolving the `npa:hasCurrentSpaceState` pointer inline can blow up the planner

The recommended consumer pattern in `design-space-repositories.md` is to
resolve the current space-state graph IRI in the same SPARQL request:

```sparql
GRAPH npa:graph {
  npa:thisRepo npa:hasCurrentSpaceState ?g .
}
GRAPH ?g { … }
```

That's atomic across trust-state flips and correct. But for **complex**
joins under `GRAPH ?g` — multiple cross-graph triple patterns plus a UNION,
the kind of query the materialiser's per-tier admit pattern looks like —
RDF4J's planner can pick a plan that times out (504 against the nginx-fronted
endpoint after 60s) where the same query with a **hardcoded** state-graph
IRI returns in well under a second. The planner appears to lose its
cardinality footing when `?g` is bound only by a single triple lookup at
the top.

Workarounds, in increasing complexity:

1. **Keep it simple**: most consumer queries are single-anchor joins where
   the pointer pattern works fine. Only worry about this for queries with
   multiple cross-graph joins and a UNION inside `GRAPH ?g`.
2. **Hardcode the IRI for one request**: resolve the pointer in a separate
   `SELECT ?g WHERE { GRAPH npa:graph { npa:thisRepo npa:hasCurrentSpaceState ?g } }`
   request, then substitute the IRI into the heavy query as a constant.
   You give up atomicity across a trust-state flip during that window;
   consumers that re-query frequently can accept that.
3. **Restructure the heavy query**: pull the variable parts into separate
   sub-queries (`SELECT … WHERE { SELECT … } { … }`) to force the planner
   to materialise intermediates with better cardinality estimates.

Materialiser-internal code in `AuthorityResolver` doesn't hit this because
the per-tier UPDATEs format the state-graph IRI in as a Java string, not
a SPARQL variable. The trap is specific to consumers that follow the
documented pointer-resolution pattern verbatim.
