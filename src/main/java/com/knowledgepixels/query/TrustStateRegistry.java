package com.knowledgepixels.query;

import java.util.Optional;

/**
 * In-memory tracker for the currently-known trust state hash.
 *
 * <p>This is the small bit of mutable state shared between the polling code
 * (which detects when the registry advertises a new {@code trustStateHash})
 * and the materialization code (which writes the corresponding snapshot into
 * the {@code trust} repository's named graph).
 *
 * <p>Authoritative state lives in the {@code trust} RDF4J repository
 * ({@code npa:thisRepo npa:hasCurrentTrustState …}); this class is a
 * convenience cache so the polling code can do an O(1) equality check on
 * each tick without hitting RDF4J. The cache is updated only after a
 * successful materialization commits.
 *
 * <p>See {@code doc/design-trust-state-repos.md} for the full design.
 */
public class TrustStateRegistry {

    private static TrustStateRegistry instance;

    /**
     * Returns the singleton instance.
     *
     * @return the singleton instance
     */
    public static TrustStateRegistry get() {
        if (instance == null) {
            instance = new TrustStateRegistry();
        }
        return instance;
    }

    private String currentHash;

    private TrustStateRegistry() {
    }

    /**
     * Returns the trust state hash that nanopub-query currently considers
     * "loaded", or empty if no trust state has been materialized yet.
     *
     * @return the current trust state hash, or {@link Optional#empty()} if none
     */
    public synchronized Optional<String> getCurrentHash() {
        return Optional.ofNullable(currentHash);
    }

    /**
     * Records that the given trust state hash is now the current one. Should
     * only be called after the corresponding snapshot has been successfully
     * materialized and the pointer triple in the {@code trust} repo has been
     * committed.
     *
     * @param hash the trust state hash that is now current; must be non-null and non-empty
     * @throws IllegalArgumentException if the hash is null or empty
     */
    public synchronized void setCurrentHash(String hash) {
        if (hash == null || hash.isEmpty()) {
            throw new IllegalArgumentException("Trust state hash must be non-null and non-empty");
        }
        this.currentHash = hash;
    }

}
