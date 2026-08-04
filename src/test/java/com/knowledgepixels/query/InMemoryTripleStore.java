package com.knowledgepixels.query;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.Map;

import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.query.QueryLanguage;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.mockito.MockedStatic;

/**
 * Test harness that backs {@link TripleStore#getRepoConnection(String)} with real
 * in-process {@link MemoryStore} repositories, one per repo name, created lazily.
 *
 * <p>This is what lets the materializers be tested for real rather than by
 * asserting on generated SPARQL strings. {@link AuthorityResolver} and
 * {@link TrustStateLoader} reach the store exclusively through
 * {@code TripleStore.get().getRepoConnection(name)}, so stubbing that one seam is
 * enough to run {@code materialize} / {@code mirrorTrustState} / the pointer
 * helpers end-to-end against a real SPARQL engine. Each call hands out a
 * <em>fresh</em> connection to the same underlying repository, matching production
 * (callers close connections in try-with-resources) while keeping the data.
 *
 * <p>Viability note: {@link AuthorityResolverTest}'s javadoc records that a
 * sail-base/sail-memory version skew used to break SPARQL UPDATE and pattern-delete
 * in-process. The rdf4j-bom pin in {@code pom.xml} fixed that; {@link Rdf4jUpdateProbeTest}
 * guards the property this harness depends on.
 *
 * <p>Usage — always in try-with-resources, since it holds an open
 * {@link MockedStatic} registration and native store handles:
 * <pre>{@code
 * try (InMemoryTripleStore store = new InMemoryTripleStore()) {
 *     store.update("spaces", "INSERT DATA { ... }");
 *     AuthorityResolver.get().runFullBuild("hash");
 *     assertTrue(store.ask("spaces", "ASK { ... }"));
 * }
 * }</pre>
 */
final class InMemoryTripleStore implements AutoCloseable {

    private final Map<String, Repository> repos = new LinkedHashMap<>();
    private final MockedStatic<TripleStore> staticMock;

    InMemoryTripleStore() {
        TripleStore store = mock(TripleStore.class);
        // A fresh connection per call: production code closes every connection it
        // takes, so handing out one shared instance would leave later calls working
        // against a closed connection.
        when(store.getRepoConnection(anyString()))
                .thenAnswer(inv -> repo(inv.getArgument(0)).getConnection());
        staticMock = mockStatic(TripleStore.class);
        staticMock.when(TripleStore::get).thenReturn(store);
    }

    /** The (lazily created) repository behind a repo name. */
    Repository repo(String name) {
        return repos.computeIfAbsent(name, n -> {
            Repository r = new SailRepository(new MemoryStore());
            r.init();
            return r;
        });
    }

    /** Opens a connection the caller is responsible for closing. */
    RepositoryConnection connection(String name) {
        return repo(name).getConnection();
    }

    /** Executes a SPARQL UPDATE against the named repo. */
    void update(String name, String sparql) {
        try (RepositoryConnection conn = connection(name)) {
            conn.prepareUpdate(QueryLanguage.SPARQL, sparql).execute();
        }
    }

    /** Evaluates a SPARQL ASK against the named repo. */
    boolean ask(String name, String sparql) {
        try (RepositoryConnection conn = connection(name)) {
            return conn.prepareBooleanQuery(QueryLanguage.SPARQL, sparql).evaluate();
        }
    }

    /**
     * Evaluates a single-variable count query and returns the numeric result. The
     * query must project exactly one binding holding a numeric literal.
     */
    long count(String name, String sparql) {
        try (RepositoryConnection conn = connection(name);
             TupleQueryResult r = conn.prepareTupleQuery(QueryLanguage.SPARQL, sparql).evaluate()) {
            if (!r.hasNext()) return 0L;
            var bs = r.next();
            return ((Literal) bs.getValue(bs.getBindingNames().iterator().next())).longValue();
        }
    }

    /** Number of statements in the given named graph of the given repo. */
    long graphSize(String name, org.eclipse.rdf4j.model.IRI graph) {
        try (RepositoryConnection conn = connection(name)) {
            return conn.size(graph);
        }
    }

    @Override
    public void close() {
        staticMock.close();
        for (Repository r : repos.values()) {
            r.shutDown();
        }
        repos.clear();
    }
}
