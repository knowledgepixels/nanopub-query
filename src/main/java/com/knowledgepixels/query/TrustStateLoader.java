package com.knowledgepixels.query;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Materializes a registry trust state into the local {@code trust} repository
 * when a hash change is detected.
 *
 * <p>Detection happens in {@link JellyNanopubLoader} (which polls the registry
 * every ~2 s anyway and reads {@code Nanopub-Registry-Trust-State-Hash}). This
 * class does the rest: fetch {@code /trust-state/<hash>.json}, parse the
 * envelope, materialize the snapshot into a named graph, swap the current
 * pointer, and prune beyond retention — all in one serializable transaction.
 *
 * <p>Currently implements detection (via {@link #maybeUpdate(String)}) plus
 * snapshot fetch and envelope parsing. Materialization (Step 4+) is logged
 * but not yet performed. See {@code doc/plan-trust-state-repos.md}.
 */
public class TrustStateLoader {

    private static final Logger log = LoggerFactory.getLogger(TrustStateLoader.class);

    private static final CloseableHttpClient httpClient =
            HttpClientBuilder.create().setDefaultRequestConfig(Utils.getHttpRequestConfig()).build();

    private TrustStateLoader() {
    }  // no instances

    /**
     * Called when registry-poll metadata is fetched. Compares the hash to the
     * locally-tracked one and, if different, fetches and parses the snapshot.
     * (Materialization happens in subsequent steps.)
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

        // Integrity check: the registry's URL hash must match what's in the body.
        if (!newTrustStateHash.equals(snapshot.trustStateHash())) {
            log.warn("Trust state envelope hash mismatch: URL was {}, body says {}",
                    newTrustStateHash, snapshot.trustStateHash());
            return;
        }

        // TODO step 4+: materialize snapshot into the trust repo, swap pointer, prune.
        log.info("Fetched trust state snapshot {} (counter={}, createdAt={}, accounts={})",
                snapshot.trustStateHash(), snapshot.trustStateCounter(),
                snapshot.createdAt(), snapshot.accounts().size());
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

}
