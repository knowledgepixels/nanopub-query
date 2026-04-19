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

import com.knowledgepixels.query.vocabulary.GEN;
import com.knowledgepixels.query.vocabulary.NPAS;

import net.trustyuri.TrustyUriUtils;

/**
 * Persistence for {@link SpaceRegistry}'s known {@code (spaceRef, spaceIri)} pairs,
 * stored in the {@code spaces} repo's {@link NPA#GRAPH} alongside the per-space
 * extract data. Mirrors the trust-state pattern, where the trust repo holds both
 * the snapshots and its own pointer metadata in {@code npa:graph}.
 *
 * <p>Persisted shape (in the {@code spaces} repo's {@link NPA#GRAPH}):
 * <pre>{@code
 *   <npas:spaceRef> npa:hasSpaceIri    <spaceIRI> ;
 *                   npa:hasRootNanopub <rootNanopubURI> .
 * }</pre>
 *
 * <p>The artifact code is encoded in the space ref itself, but the full root
 * nanopub URI isn't reconstructable in general (the same trusty artifact code
 * can be hosted under different namespace prefixes), so we store it explicitly
 * for query convenience.
 *
 * <p>Putting it in the spaces repo (rather than the global admin repo) makes the
 * spaces repo self-contained: a single SPARQL query against {@code spaces}
 * answers both "what spaces exist?" and "what extracts has each accumulated?".
 *
 * <p>Role properties and the source-nanopub reverse index are <em>not</em>
 * persisted — they are re-derived as nanopubs flow through the loader. See
 * {@code doc/plan-space-repositories.md}.
 */
public class SpacesStateStore {

    private static final Logger log = LoggerFactory.getLogger(SpacesStateStore.class);

    private static final ValueFactory vf = SimpleValueFactory.getInstance();

    /** Local name of the repository that holds per-space extract data and registry persistence. */
    static final String SPACES_REPO = "spaces";

    /**
     * Predicate linking a space ref (subject in the {@code npas:} namespace) to its
     * Space IRI. Defined locally rather than in a vocab class because it's strictly
     * internal to space-registry persistence.
     */
    static final IRI NPA_HAS_SPACE_IRI = vf.createIRI(NPA.NAMESPACE, "hasSpaceIri");

    /**
     * Predicate linking a space ref to the URI of its root nanopub. The artifact
     * code is encoded in the space ref, but the full URI isn't recoverable from
     * it alone — same trusty artifact code can be hosted under different namespace
     * prefixes. Stored explicitly for query convenience.
     */
    static final IRI NPA_HAS_ROOT_NANOPUB = vf.createIRI(NPA.NAMESPACE, "hasRootNanopub");

    private SpacesStateStore() {
    }

    /**
     * Loads any persisted {@code (spaceRef, spaceIri)} pairs from the spaces repo
     * and registers them in the given registry. Intended to run once at startup.
     *
     * <p>Safe to call on a fresh deployment (the spaces repo may not even exist —
     * auto-created, found empty, seeded nothing). Any failure is logged at INFO;
     * the registry is left empty and the loader will rebuild it as nanopubs flow.
     *
     * @param registry the registry to seed
     */
    public static void bootstrap(SpaceRegistry registry) {
        try (RepositoryConnection conn = TripleStore.get().getRepoConnection(SPACES_REPO)) {
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
                log.info("Spaces bootstrap: seeded {} space(s) from spaces repo", loaded);
            }
        } catch (Exception ex) {
            log.info("Spaces bootstrap: failed to read persisted spaces: {}", ex.toString());
        }
    }

    /**
     * Re-derives the known-spaces set by scanning the existing {@code type_<HASH>}
     * repo for {@link GEN#SPACE}. For each {@code <spaceIri> gen:hasRootDefinition
     * <rootUri>} triple found in any nanopub assertion there, registers the space
     * in {@link SpaceRegistry} and persists it via {@link #persistSpace} (so the
     * spaces-repo state catches up).
     *
     * <p>Intended to run once at startup, after {@link #bootstrap}. Idempotent:
     * spaces already known to the registry are detected via the {@code wasNew}
     * signal and skipped. Lets existing deployments pick up space-defining
     * nanopubs that were loaded before this code shipped, without a fresh DB.
     *
     * <p>If the type repo doesn't exist (no {@code gen:Space}-typed nanopub has
     * ever been loaded), this is a no-op. Failures are logged at INFO and don't
     * propagate; the loader will eventually re-derive state as new nanopubs flow.
     *
     * @param registry the registry to seed
     */
    public static void scanExistingSpaces(SpaceRegistry registry) {
        String typeRepo = "type_" + Utils.createHash(GEN.SPACE);
        if (!TripleStore.get().getRepositoryNames().contains(typeRepo)) {
            log.info("Spaces scan: no {} repo yet — skipping", typeRepo);
            return;
        }
        try (RepositoryConnection conn = TripleStore.get().getRepoConnection(typeRepo)) {
            // The type_<hash(gen:Space)> repo holds only gen:Space-typed nanopubs,
            // so any hasRootDefinition triple in any assertion graph here belongs
            // to a Space-defining nanopub. Walking np -> assertion graph keeps the
            // result tied to its source nanopub for logging and provenance.
            String query = """
                    PREFIX np:  <http://www.nanopub.org/nschema#>
                    PREFIX gen: <https://w3id.org/kpxl/gen/terms/>
                    SELECT ?np ?spaceIri ?rootUri WHERE {
                      GRAPH ?head {
                        ?np a np:Nanopublication ;
                            np:hasAssertion ?assertion .
                      }
                      GRAPH ?assertion {
                        ?spaceIri gen:hasRootDefinition ?rootUri .
                      }
                    }
                    """;
            int seen = 0;
            int registered = 0;
            try (TupleQueryResult result =
                         conn.prepareTupleQuery(QueryLanguage.SPARQL, query).evaluate()) {
                while (result.hasNext()) {
                    seen++;
                    var binding = result.next();
                    if (!(binding.getValue("spaceIri") instanceof IRI spaceIri)) continue;
                    if (!(binding.getValue("rootUri") instanceof IRI rootUri)) continue;
                    String rootNanopubId = TrustyUriUtils.getArtifactCode(rootUri.stringValue());
                    if (rootNanopubId == null || rootNanopubId.isEmpty()) {
                        log.warn("Spaces scan: ignoring {} — gen:hasRootDefinition target is not a trusty URI: {}",
                                spaceIri, rootUri);
                        continue;
                    }
                    SpaceRegistry.Registration reg = registry.registerSpace(rootNanopubId, spaceIri);
                    if (reg.wasNew()) {
                        persistSpace(rootNanopubId, spaceIri, rootUri);
                        registered++;
                    }
                }
            }
            log.info("Spaces scan: examined {} hasRootDefinition triple(s); newly registered {} space(s)",
                    seen, registered);
        } catch (Exception ex) {
            log.info("Spaces scan: failed to scan {}: {}", typeRepo, ex.toString());
        }
    }

    /**
     * Persists a newly-registered space into the spaces repo, recording both the
     * Space IRI and the full root-nanopub URI. Idempotent: re-persisting the same
     * triples is harmless.
     *
     * @param rootNanopubId  artifact code of the root nanopub
     * @param spaceIri       the Space IRI
     * @param rootNanopubUri the full URI of the root nanopub (as stated in the
     *                       defining {@code gen:hasRootDefinition} triple)
     */
    public static void persistSpace(String rootNanopubId, IRI spaceIri, IRI rootNanopubUri) {
        String spaceRef = rootNanopubId + "_" + Utils.createHash(spaceIri);
        IRI refIri = NPAS.forSpaceRef(spaceRef);
        try (RepositoryConnection conn = TripleStore.get().getRepoConnection(SPACES_REPO)) {
            conn.begin(IsolationLevels.SERIALIZABLE);
            conn.add(refIri, NPA_HAS_SPACE_IRI, spaceIri, NPA.GRAPH);
            conn.add(refIri, NPA_HAS_ROOT_NANOPUB, rootNanopubUri, NPA.GRAPH);
            conn.commit();
        } catch (Exception ex) {
            log.info("Failed to persist space {}: {}", spaceRef, ex.toString());
        }
    }

}
