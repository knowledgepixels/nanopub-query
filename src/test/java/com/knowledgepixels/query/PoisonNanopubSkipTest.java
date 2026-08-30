package com.knowledgepixels.query;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.nanopub.Nanopub;
import org.nanopub.jelly.NanopubStream;
import org.nanopub.vocabulary.NPA;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression test for issue #208: a "poison" registry stream entry — one whose
 * content does not hash to its trusty URI artifact code — must be skipped with a
 * note instead of wedging the loader.
 *
 * <p>The fixture {@code poison-stream-2026-08-30.jelly} is the REAL registry
 * stream segment captured on 2026-08-30 ({@code ?afterCounter=88950} from
 * registry.petapico.org): entry 88951 is the invalid {@code RAAAAA…} nanopub
 * that stalled ingestion fleet-wide on 2026-08-29 (its signature is valid, its
 * artifact code is not — knowledgepixels/nanopub-registry#164), and entry 88952
 * is a valid nanopub. Before the fix, 88951 sailed past all constructor checks
 * and reached the repo writers, where {@code NanopubUtils.updateXorChecksum}
 * threw {@code ArrayIndexOutOfBoundsException} — a deterministic error the
 * retry loops treated as transient, so the loader never advanced.
 *
 * <p>The whole abort path (constructor checks + note write) runs on the calling
 * thread, which is what makes it testable against {@link InMemoryTripleStore}
 * (whose static mock is thread-local); the non-aborted load path spawns pool
 * threads and is out of scope here.
 */
class PoisonNanopubSkipTest {

    private static Nanopub poison;   // counter 88951, invalid artifact code
    private static Nanopub valid;    // counter 88952, valid

    @BeforeAll
    static void decodeFixture() throws java.io.IOException {
        List<Nanopub> nps = new ArrayList<>();
        try (var is = PoisonNanopubSkipTest.class.getResourceAsStream("/poison-stream-2026-08-30.jelly");
             var st = NanopubStream.fromByteStream(is).getAsNanopubs()) {
            st.forEach(m -> {
                assertTrue(m.isSuccess(), "the Jelly layer decodes even the poison entry successfully");
                nps.add(m.getNanopub());
            });
        }
        assertEquals(2, nps.size());
        poison = nps.get(0);
        valid = nps.get(1);
        assertEquals("https://w3id.org/np/RAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
                poison.getUri().stringValue());
    }

    @Test
    void poisonEntryIsAbortedByTheConstructor() {
        try (InMemoryTripleStore store = new InMemoryTripleStore()) {
            NanopubLoader loader = new NanopubLoader(poison, 88951);
            assertTrue(loader.isAborted(), "content/artifact-code mismatch must abort the load");
            assertEquals(List.of("could not load nanopub as its content does not match its trusty URI artifact code"),
                    loader.getNotes());
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void loadReturnsNormallyAndRecordsANote() {
        try (InMemoryTripleStore store = new InMemoryTripleStore()) {
            // Must neither throw nor hang — before the fix this path failed
            // deterministically in the repo writers and was retried forever.
            NanopubLoader.load(poison, 88951);

            assertTrue(store.ask(TripleStore.ADMIN_REPO,
                            "ASK { graph <" + NPA.GRAPH + "> { <" + poison.getUri() + "> <" + NPA.NOTE + "> ?note } }"),
                    "the skipped entry must be recorded in the admin repo");
        }
    }

    @Test
    void validSiblingEntryIsNotAborted() {
        try (InMemoryTripleStore store = new InMemoryTripleStore()) {
            // The entry right after the poison in the same capture: the new check
            // must not reject legitimate nanopubs.
            NanopubLoader loader = new NanopubLoader(valid, 88952);
            assertFalse(loader.isAborted());
        }
    }

    @Test
    void fixtureStaysHonest() {
        // Guard against the fixture being regenerated with different content.
        assertEquals("https://w3id.org/np/RAyWAjMmas07fRfLfqf4bY6NhTo0z2204kyI6CKeiJ3fk",
                valid.getUri().stringValue());
    }
}
