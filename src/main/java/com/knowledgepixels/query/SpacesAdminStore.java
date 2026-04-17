package com.knowledgepixels.query;

import org.eclipse.rdf4j.common.transaction.IsolationLevels;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.query.QueryLanguage;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.nanopub.vocabulary.NPA;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.knowledgepixels.query.vocabulary.NPAS;

/**
 * Admin-repo persistence for {@link SpaceRegistry}'s known {@code (spaceRef, spaceIri)}
 * pairs. Mirrors the pattern used by {@link TrustStateLoader} for trust-state pointer
 * persistence: pure I/O wrapper around {@link TripleStore}, with a {@link #bootstrap}
 * call run once at startup and a {@link #persistSpace} call invoked whenever
 * {@link NanopubLoader#detectAndRegisterSpaces} adds a new space.
 *
 * <p>Persisted shape (in the admin repo's {@link NPA#GRAPH}):
 * <pre>{@code
 *   <npas:spaceRef> npa:hasSpaceIri <spaceIRI> .
 * }</pre>
 *
 * <p>Role properties and the source-nanopub reverse index are <em>not</em> persisted
 * — they are re-derived as nanopubs flow through the loader. See
 * {@code doc/plan-space-repositories.md}.
 */
public class SpacesAdminStore {

    private static final Logger log = LoggerFactory.getLogger(SpacesAdminStore.class);

    private static final ValueFactory vf = SimpleValueFactory.getInstance();

    /**
     * Predicate linking a space ref (subject in the {@code npas:} namespace) to its
     * Space IRI. Defined locally rather than in a vocab class because it's strictly
     * internal to space-registry persistence.
     */
    static final IRI NPA_HAS_SPACE_IRI = vf.createIRI(NPA.NAMESPACE, "hasSpaceIri");

    private SpacesAdminStore() {
    }

    /**
     * Loads any persisted {@code (spaceRef, spaceIri)} pairs from the admin repo and
     * registers them in the given registry. Intended to run once at startup.
     *
     * <p>Safe to call on a fresh deployment (admin repo may be empty — registers
     * nothing). Any failure is logged at INFO; the registry is left empty and the
     * loader will rebuild it as nanopubs flow through.
     *
     * @param registry the registry to seed
     */
    public static void bootstrap(SpaceRegistry registry) {
        try (RepositoryConnection conn = TripleStore.get().getRepoConnection(TripleStore.ADMIN_REPO)) {
            String query = String.format("""
                    SELECT ?ref ?iri WHERE {
                      GRAPH <%s> {
                        ?ref <%s> ?iri .
                      }
                    }
                    """,
                    NPA.GRAPH, NPA_HAS_SPACE_IRI);
            int loaded = 0;
            try (TupleQueryResult result =
                         conn.prepareTupleQuery(QueryLanguage.SPARQL, query).evaluate()) {
                while (result.hasNext()) {
                    var binding = result.next();
                    IRI refIri = (IRI) binding.getValue("ref");
                    IRI spaceIri = (IRI) binding.getValue("iri");
                    String iriStr = refIri.stringValue();
                    if (!iriStr.startsWith(NPAS.NAMESPACE)) {
                        log.warn("Skipping persisted space ref with unexpected IRI: {}", iriStr);
                        continue;
                    }
                    String spaceRef = iriStr.substring(NPAS.NAMESPACE.length());
                    String rootNanopubId = registry.getRootNanopubId(spaceRef);
                    registry.registerSpace(rootNanopubId, spaceIri);
                    loaded++;
                }
            }
            if (loaded > 0) {
                log.info("Spaces bootstrap: seeded {} space(s) from admin repo", loaded);
            }
        } catch (Exception ex) {
            log.info("Spaces bootstrap: failed to read persisted spaces: {}", ex.toString());
        }
    }

    /**
     * Persists a newly-registered {@code (spaceRef, spaceIri)} pair into the admin
     * repo. Idempotent: re-persisting the same pair is harmless (the SPARQL INSERT
     * just re-asserts an existing triple).
     *
     * @param rootNanopubId artifact code of the root nanopub
     * @param spaceIri      the Space IRI
     */
    public static void persistSpace(String rootNanopubId, IRI spaceIri) {
        String spaceRef = rootNanopubId + "_" + Utils.createHash(spaceIri);
        IRI refIri = NPAS.forSpaceRef(spaceRef);
        try (RepositoryConnection conn = TripleStore.get().getRepoConnection(TripleStore.ADMIN_REPO)) {
            conn.begin(IsolationLevels.SERIALIZABLE);
            conn.add(refIri, NPA_HAS_SPACE_IRI, spaceIri, NPA.GRAPH);
            conn.commit();
        } catch (Exception ex) {
            log.info("Failed to persist space {}: {}", spaceRef, ex.toString());
        }
    }

}
