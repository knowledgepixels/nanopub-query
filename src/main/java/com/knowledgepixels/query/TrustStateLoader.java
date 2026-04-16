package com.knowledgepixels.query;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.util.EntityUtils;
import org.eclipse.rdf4j.common.transaction.IsolationLevels;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.model.vocabulary.XSD;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.nanopub.vocabulary.NPA;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.hash.Hashing;
import com.knowledgepixels.query.vocabulary.NPAA;
import com.knowledgepixels.query.vocabulary.NPAT;

/**
 * Materializes a registry trust state into the local {@code trust} repository
 * when a hash change is detected.
 *
 * <p>Detection happens in {@link JellyNanopubLoader} (which polls the registry
 * every ~2 s anyway and reads {@code Nanopub-Registry-Trust-State-Hash}). This
 * class does the rest: fetch {@code /trust-state/<hash>.json}, parse the
 * envelope, materialize the snapshot into a named graph, and swap the current
 * pointer — all in one serializable transaction.
 *
 * <p>See {@code doc/plan-trust-state-repos.md} for the full design.
 */
public class TrustStateLoader {

    private static final Logger log = LoggerFactory.getLogger(TrustStateLoader.class);

    /** Local name of the repository that holds all mirrored trust states. */
    static final String TRUST_REPO = "trust";

    private static final ValueFactory vf = SimpleValueFactory.getInstance();

    // Local extensions to the upstream NPA vocabulary (terms used only on the
    // consumer side). Defined here rather than in a vocab class because they're
    // strictly internal to the trust-state mirroring code.
    private static final IRI NPA_TRUST_STATE = vf.createIRI(NPA.NAMESPACE, "TrustState");
    private static final IRI NPA_ACCOUNT_STATE = vf.createIRI(NPA.NAMESPACE, "AccountState");
    private static final IRI NPA_HAS_TRUST_STATE_HASH = vf.createIRI(NPA.NAMESPACE, "hasTrustStateHash");
    private static final IRI NPA_HAS_TRUST_STATE_COUNTER = vf.createIRI(NPA.NAMESPACE, "hasTrustStateCounter");
    private static final IRI NPA_HAS_CREATED_AT = vf.createIRI(NPA.NAMESPACE, "hasCreatedAt");
    private static final IRI NPA_HAS_CURRENT_TRUST_STATE = vf.createIRI(NPA.NAMESPACE, "hasCurrentTrustState");
    private static final IRI NPA_AGENT = vf.createIRI(NPA.NAMESPACE, "agent");
    private static final IRI NPA_PUBKEY = vf.createIRI(NPA.NAMESPACE, "pubkey");
    private static final IRI NPA_TRUST_STATUS = vf.createIRI(NPA.NAMESPACE, "trustStatus");
    private static final IRI NPA_DEPTH = vf.createIRI(NPA.NAMESPACE, "depth");
    private static final IRI NPA_PATH_COUNT = vf.createIRI(NPA.NAMESPACE, "pathCount");
    private static final IRI NPA_RATIO = vf.createIRI(NPA.NAMESPACE, "ratio");
    private static final IRI NPA_QUOTA = vf.createIRI(NPA.NAMESPACE, "quota");

    private static final CloseableHttpClient httpClient =
            HttpClientBuilder.create().setDefaultRequestConfig(Utils.getHttpRequestConfig()).build();

    private TrustStateLoader() {
    }  // no instances

    /**
     * Called when registry-poll metadata is fetched. Compares the hash to the
     * locally-tracked one and, if different, fetches the snapshot and
     * materializes it into the {@code trust} repo.
     *
     * <p>Safe to call with a null/empty hash (older registries don't expose
     * trust state) — silently no-op in that case.
     *
     * @param newTrustStateHash the {@code trustStateHash} reported by the
     *                          registry, or null if the registry doesn't
     *                          expose one
     */
    public static void maybeUpdate(String newTrustStateHash) {
        if (newTrustStateHash == null || newTrustStateHash.isEmpty()) return;
        String current = TrustStateRegistry.get().getCurrentHash().orElse(null);
        if (newTrustStateHash.equals(current)) return;

        log.info("Trust state hash change detected: {} -> {}",
                current == null ? "(none)" : current, newTrustStateHash);

        Optional<TrustStateSnapshot> snapshotOpt = fetchSnapshot(newTrustStateHash);
        if (snapshotOpt.isEmpty()) return;
        TrustStateSnapshot snapshot = snapshotOpt.get();

        // Integrity check: the URL hash must match what's in the body.
        if (!newTrustStateHash.equals(snapshot.trustStateHash())) {
            log.warn("Trust state envelope hash mismatch: URL was {}, body says {}",
                    newTrustStateHash, snapshot.trustStateHash());
            return;
        }

        try {
            materialize(snapshot);
            TrustStateRegistry.get().setCurrentHash(snapshot.trustStateHash());
            log.info("Materialized trust state {} (counter={}, accounts={})",
                    snapshot.trustStateHash(), snapshot.trustStateCounter(),
                    snapshot.accounts().size());
        } catch (Exception ex) {
            log.warn("Failed to materialize trust state {}: {}",
                    snapshot.trustStateHash(), ex.toString(), ex);
        }
    }

    /**
     * Fetches and parses the snapshot for the given trust state hash from the
     * registry. Returns {@link Optional#empty()} on 404 (the registry has
     * pruned this hash) or on any I/O / parse error (logged at INFO).
     *
     * @param trustStateHash the hash to fetch
     * @return the parsed snapshot, or empty if unavailable
     */
    static Optional<TrustStateSnapshot> fetchSnapshot(String trustStateHash) {
        String url = JellyNanopubLoader.registryUrl
                + "trust-state/" + URLEncoder.encode(trustStateHash, StandardCharsets.UTF_8) + ".json";
        try (var response = httpClient.execute(new HttpGet(url))) {
            int status = response.getStatusLine().getStatusCode();
            if (status == 404) {
                log.info("Trust state snapshot {} returned 404 (pruned by registry); skipping",
                        trustStateHash);
                EntityUtils.consumeQuietly(response.getEntity());
                return Optional.empty();
            }
            if (status < 200 || status >= 300) {
                log.info("Trust state snapshot {} returned HTTP {} ({}); skipping",
                        trustStateHash, status, response.getStatusLine().getReasonPhrase());
                EntityUtils.consumeQuietly(response.getEntity());
                return Optional.empty();
            }
            String body = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
            return Optional.of(TrustStateSnapshot.parse(body));
        } catch (IOException ex) {
            log.info("Failed to fetch trust state snapshot {}: {}", trustStateHash, ex.toString());
            return Optional.empty();
        } catch (IllegalArgumentException ex) {
            log.info("Failed to parse trust state snapshot {}: {}", trustStateHash, ex.toString());
            return Optional.empty();
        }
    }

    /**
     * Writes the snapshot's account-state triples into the trust state's named
     * graph, writes cross-state metadata into {@code npa:graph}, and swaps the
     * current-state pointer — all in one serializable transaction. Idempotent
     * on the same hash (re-running just rewrites the same triples).
     *
     * @param snapshot the snapshot to materialize
     */
    static void materialize(TrustStateSnapshot snapshot) {
        IRI trustStateIri = NPAT.forHash(snapshot.trustStateHash());

        try (RepositoryConnection conn =
                     TripleStore.get().getRepoConnection(TRUST_REPO)) {
            conn.begin(IsolationLevels.SERIALIZABLE);

            // 1. Account-state triples in the trust state's named graph
            for (TrustStateSnapshot.AccountEntry a : snapshot.accounts()) {
                IRI accountStateIri =
                        NPAA.forHash(accountStateHash(snapshot.trustStateHash(), a));
                conn.add(accountStateIri, RDF.TYPE, NPA_ACCOUNT_STATE, trustStateIri);
                conn.add(accountStateIri, NPA_AGENT,
                        vf.createIRI(a.agent()), trustStateIri);
                conn.add(accountStateIri, NPA_PUBKEY,
                        vf.createLiteral(a.pubkey()), trustStateIri);
                conn.add(accountStateIri, NPA_TRUST_STATUS,
                        vf.createIRI(NPA.NAMESPACE, a.status()), trustStateIri);
                conn.add(accountStateIri, NPA_DEPTH,
                        vf.createLiteral(a.depth()), trustStateIri);
                conn.add(accountStateIri, NPA_PATH_COUNT,
                        vf.createLiteral(a.pathCount()), trustStateIri);
                conn.add(accountStateIri, NPA_RATIO,
                        vf.createLiteral(a.ratio()), trustStateIri);
                conn.add(accountStateIri, NPA_QUOTA,
                        vf.createLiteral(a.quota()), trustStateIri);
            }

            // 2. Cross-state metadata in npa:graph
            conn.add(trustStateIri, RDF.TYPE, NPA_TRUST_STATE, NPA.GRAPH);
            conn.add(trustStateIri, NPA_HAS_TRUST_STATE_HASH,
                    vf.createLiteral(snapshot.trustStateHash()), NPA.GRAPH);
            conn.add(trustStateIri, NPA_HAS_TRUST_STATE_COUNTER,
                    vf.createLiteral(snapshot.trustStateCounter()), NPA.GRAPH);
            conn.add(trustStateIri, NPA_HAS_CREATED_AT,
                    vf.createLiteral(snapshot.createdAt().toString(), XSD.DATETIME),
                    NPA.GRAPH);

            // 3. Atomic pointer swap
            conn.remove(NPA.THIS_REPO, NPA_HAS_CURRENT_TRUST_STATE, null, NPA.GRAPH);
            conn.add(NPA.THIS_REPO, NPA_HAS_CURRENT_TRUST_STATE, trustStateIri, NPA.GRAPH);

            // 4. (Step 5) Retention pruning will go here.

            conn.commit();
        }
    }

    /**
     * Computes the account-state hash for a single entry within a snapshot.
     * SHA-256 over {@code trustStateHash + "|" + pubkey + "|" + agent}; the
     * trustStateHash is part of the input so the same {@code (pubkey, agent)}
     * pair in two snapshots produces two different account-state IRIs.
     */
    static String accountStateHash(String trustStateHash, TrustStateSnapshot.AccountEntry a) {
        String composite = trustStateHash + "|" + a.pubkey() + "|" + a.agent();
        return Hashing.sha256().hashString(composite, StandardCharsets.UTF_8).toString();
    }

}
