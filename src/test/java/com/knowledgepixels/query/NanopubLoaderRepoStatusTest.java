package com.knowledgepixels.query;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.junit.jupiter.api.Test;
import org.nanopub.NanopubUtils;
import org.nanopub.vocabulary.NPA;

import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code fetchRepoStatus} must never mistake an empty read for a fresh repo.
 *
 * <p>Every repo {@code loadNanopubToRepo} writes is seeded with {@code count=0} +
 * {@code INIT_CHECKSUM} in {@code initNewRepo}'s creation transaction, so a result
 * with no count/checksum bindings can only be a degraded read (a misbehaving store
 * returning no rows instead of failing, issue #142) or a store that lost its admin
 * triples. The old behavior — returning {@code count=0, INIT_CHECKSUM} — made the
 * caller commit {@code count=1} over the existing chain: observed on the kpxl
 * {@code full} repo on 2026-08-12, where the chain reset from 86860 to ~0 while all
 * data was still present.
 */
class NanopubLoaderRepoStatusTest {

    private static final ValueFactory VF = SimpleValueFactory.getInstance();

    private static final IRI NP_ID = VF.createIRI("http://purl.org/np/RA" + "A".repeat(43));

    private static void withConnection(Consumer<RepositoryConnection> body) {
        SailRepository repo = new SailRepository(new MemoryStore());
        try {
            try (RepositoryConnection conn = repo.getConnection()) {
                body.accept(conn);
            }
        } finally {
            repo.shutDown();
        }
    }

    @Test
    void emptyRepoIsRefusedNotTreatedAsFresh() {
        withConnection(conn -> {
            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> NanopubLoader.fetchRepoStatus(conn, NP_ID, "full"));
            assertTrue(ex.getMessage().contains("full"));
        });
    }

    @Test
    void seededRepoReportsItsChain() {
        withConnection(conn -> {
            conn.add(NPA.THIS_REPO, NPA.HAS_NANOPUB_COUNT, VF.createLiteral(42L), NPA.GRAPH);
            conn.add(NPA.THIS_REPO, NPA.HAS_NANOPUB_CHECKSUM,
                    VF.createLiteral(NanopubUtils.INIT_CHECKSUM), NPA.GRAPH);

            NanopubLoader.RepoStatus status = NanopubLoader.fetchRepoStatus(conn, NP_ID, "full");
            assertFalse(status.isLoaded());
            assertEquals(42L, status.count());
            assertEquals(NanopubUtils.INIT_CHECKSUM, status.checksum());
        });
    }

    @Test
    void loadedNanopubIsReportedAsLoaded() {
        withConnection(conn -> {
            conn.add(NPA.THIS_REPO, NPA.HAS_NANOPUB_COUNT, VF.createLiteral(42L), NPA.GRAPH);
            conn.add(NPA.THIS_REPO, NPA.HAS_NANOPUB_CHECKSUM,
                    VF.createLiteral(NanopubUtils.INIT_CHECKSUM), NPA.GRAPH);
            conn.add(NP_ID, NPA.HAS_LOAD_NUMBER, VF.createLiteral(41L), NPA.GRAPH);

            assertTrue(NanopubLoader.fetchRepoStatus(conn, NP_ID, "full").isLoaded());
        });
    }
}
