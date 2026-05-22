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
