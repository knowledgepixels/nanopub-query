package com.knowledgepixels.query;

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
 * <p>This file currently only contains the {@link #maybeUpdate(String)} entry
 * point as a stub that logs the detected hash. Fetch and materialization land
 * in subsequent steps. See {@code doc/plan-trust-state-repos.md}.
 */
public class TrustStateLoader {

    private static final Logger log = LoggerFactory.getLogger(TrustStateLoader.class);

    private TrustStateLoader() {
    }  // no instances

    /**
     * Called when registry-poll metadata is fetched. Compares the hash to the
     * locally-tracked one and, if different, will (eventually) materialize the
     * new snapshot. Currently logs the change and returns.
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
        // TODO step 3+: fetch /trust-state/<hash>.json, parse envelope, materialize, swap pointer, prune.
        log.info("Trust state hash change detected: {} -> {} (materialization not yet implemented)",
                current == null ? "(none)" : current, newTrustStateHash);
    }

}
