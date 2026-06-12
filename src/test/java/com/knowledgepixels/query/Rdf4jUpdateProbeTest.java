package com.knowledgepixels.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.junit.jupiter.api.Test;

/**
 * Probe for the in-process RDF4J UPDATE path. {@link AuthorityResolverTest}'s javadoc
 * records that a prior sail-base/sail-memory version skew broke SPARQL UPDATE and
 * pattern-delete on the in-memory store, which is why the tier-query tests assert on
 * SPARQL strings only. After pinning all RDF4J artifacts to one version via the BOM,
 * this confirms INSERT/WHERE, pattern-delete, BIND-minted IRIs, and named-graph
 * isolation actually execute — the prerequisite for end-to-end materializer tests.
 */
class Rdf4jUpdateProbeTest {

    private static final String G = "http://example.org/g";

    @Test
    void insertWhere_patternDelete_bindMint_executeOnMemoryStore() {
        Repository repo = new SailRepository(new MemoryStore());
        repo.init();
        try (RepositoryConnection conn = repo.getConnection()) {
            // Seed a couple of source rows in the named graph.
            conn.prepareUpdate("""
                    INSERT DATA { GRAPH <%1$s> {
                      <http://example.org/s1> <http://example.org/p> <http://example.org/a> .
                      <http://example.org/s2> <http://example.org/p> <http://example.org/b> .
                    } }
                    """.formatted(G)).execute();

            // INSERT/WHERE that mints a fresh subject per source row via BIND(IRI(CONCAT(...))).
            // This is exactly the ref-scoped-minting shape Phase 1 needs.
            conn.prepareUpdate("""
                    INSERT { GRAPH <%1$s> {
                      ?minted <http://example.org/derived> ?o .
                    } }
                    WHERE {
                      GRAPH <%1$s> {
                        ?s <http://example.org/p> ?o .
                      }
                      BIND(IRI(CONCAT(STR(?s), "__ref")) AS ?minted)
                    }
                    """.formatted(G)).execute();

            long derived = count(conn, """
                    SELECT (COUNT(*) AS ?c) WHERE {
                      GRAPH <%1$s> { ?x <http://example.org/derived> ?o . }
                    }
                    """.formatted(G));
            assertEquals(2L, derived, "INSERT/WHERE with BIND-minted IRIs must materialize one row per source");

            assertTrue(conn.prepareBooleanQuery("""
                    ASK { GRAPH <%1$s> { <http://example.org/s1__ref> <http://example.org/derived> <http://example.org/a> . } }
                    """.formatted(G)).evaluate(), "minted IRI is the concatenation we asked for");

            // Pattern-delete (the path the javadoc said was broken).
            conn.prepareUpdate("""
                    DELETE { GRAPH <%1$s> { ?x <http://example.org/derived> ?o . } }
                    WHERE  { GRAPH <%1$s> { ?x <http://example.org/derived> ?o . } }
                    """.formatted(G)).execute();

            long afterDelete = count(conn, """
                    SELECT (COUNT(*) AS ?c) WHERE {
                      GRAPH <%1$s> { ?x <http://example.org/derived> ?o . }
                    }
                    """.formatted(G));
            assertEquals(0L, afterDelete, "pattern-delete must remove the derived rows");

            // Source rows untouched — confirms the delete was scoped, not a graph wipe.
            long source = count(conn, """
                    SELECT (COUNT(*) AS ?c) WHERE {
                      GRAPH <%1$s> { ?s <http://example.org/p> ?o . }
                    }
                    """.formatted(G));
            assertEquals(2L, source, "source rows survive a scoped pattern-delete");
        } finally {
            repo.shutDown();
        }
    }

    private static long count(RepositoryConnection conn, String sparql) {
        try (var r = conn.prepareTupleQuery(sparql).evaluate()) {
            return ((org.eclipse.rdf4j.model.Literal) r.next().getValue("c")).longValue();
        }
    }
}
