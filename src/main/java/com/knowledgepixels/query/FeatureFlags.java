package com.knowledgepixels.query;

/**
 * Operator-controlled feature flags, read from the environment on each call. Kept
 * as a central table so operator-controlled features have consistent naming and a
 * single place to audit. Both flags default to {@code true}, i.e. the feature is
 * enabled unless explicitly disabled.
 *
 * <p>Disabling a flag makes the corresponding feature's entry points no-op:
 * polling, materialisation, and auxiliary repo creation are all skipped. Callers
 * don't need individual guards — the gated methods handle the check internally.
 *
 * <p>{@link MainVerticle#start(io.vertx.core.Promise)} logs a WARN at startup
 * whenever either flag is {@code false}, so an accidentally-flagged production
 * image is never silent.
 *
 * <p>Flags are re-read on each call rather than cached in {@code static final}
 * fields — the per-call overhead is a single map lookup in
 * {@link Utils#getEnvString(String, String)}, and call-time evaluation avoids
 * awkward interactions with {@link org.mockito.Mockito#mockStatic} in tests.
 */
public final class FeatureFlags {

    /**
     * When {@code false}, the trust-state mirror is disabled:
     * {@link TrustStateLoader#bootstrap()} and
     * {@link TrustStateLoader#maybeUpdate(String)} become no-ops. The {@code trust}
     * repo is never auto-created and no trust-state snapshot is ever fetched.
     *
     * <p>Controlled by the {@code NANOPUB_QUERY_ENABLE_TRUST_STATE} environment
     * variable. Default: {@code true}.
     *
     * @return {@code true} if the trust-state mirror is enabled
     */
    public static boolean trustStateEnabled() {
        return "true".equalsIgnoreCase(
                Utils.getEnvString("NANOPUB_QUERY_ENABLE_TRUST_STATE", "true"));
    }

    /**
     * When {@code false}, all spaces-related work is disabled:
     * {@link NanopubLoader#detectAndRegisterSpaces},
     * {@link SpacesAdminStore#bootstrap},
     * {@link SpacesAdminStore#scanExistingSpaces}, and
     * {@link SpacesAdminStore#persistSpace} become no-ops. The {@code spaces} repo
     * is never auto-created and no space state is ever registered.
     *
     * <p>Controlled by the {@code NANOPUB_QUERY_ENABLE_SPACES} environment
     * variable. Default: {@code true}.
     *
     * @return {@code true} if spaces processing is enabled
     */
    public static boolean spacesEnabled() {
        return "true".equalsIgnoreCase(
                Utils.getEnvString("NANOPUB_QUERY_ENABLE_SPACES", "true"));
    }

    /**
     * When {@code false}, per-nanopub writes to the {@code full} repo are skipped
     * in {@link NanopubLoader#executeLoading}. The {@code full} repo is the
     * catch-all endpoint for generic SPARQL queries that aren't scoped by pubkey
     * or type; disabling it breaks those queries but removes one of the heavier
     * per-nanopub write targets. {@code pubkey_*} and {@code type_*} still get
     * the same {@code allStatements}, so per-pubkey / per-type queries still work.
     *
     * <p>Intended both as a throughput-measurement lever on a test instance and
     * as a deliberate per-instance production option for deployments that don't
     * need generic SPARQL.
     *
     * <p>Controlled by the {@code NANOPUB_QUERY_ENABLE_FULL_REPO} environment
     * variable. Default: {@code true}.
     *
     * @return {@code true} if writes to the {@code full} repo are enabled
     */
    public static boolean fullRepoEnabled() {
        return "true".equalsIgnoreCase(
                Utils.getEnvString("NANOPUB_QUERY_ENABLE_FULL_REPO", "true"));
    }

    /**
     * When {@code false}, per-nanopub writes to the {@code text} repo are skipped.
     * The {@code text} repo is Lucene-backed and supports full-text search via
     * {@code npa:hasFilterLiteral}; disabling it removes Lucene from the write
     * path entirely (the single largest per-nanopub cost in the repo fan-out),
     * at the price of breaking full-text search.
     *
     * <p>Controlled by the {@code NANOPUB_QUERY_ENABLE_TEXT_REPO} environment
     * variable. Default: {@code true}.
     *
     * @return {@code true} if writes to the {@code text} repo are enabled
     */
    public static boolean textRepoEnabled() {
        return "true".equalsIgnoreCase(
                Utils.getEnvString("NANOPUB_QUERY_ENABLE_TEXT_REPO", "true"));
    }

    /**
     * When {@code false}, per-nanopub writes to the {@code last30d} repo are
     * skipped, along with its hourly cleanup SPARQL. The repo serves recent-
     * nanopub queries; when disabled, the same queries can be expressed against
     * the {@code full} repo with a date filter on {@code dcterms:created}.
     *
     * <p>Controlled by the {@code NANOPUB_QUERY_ENABLE_LAST30D_REPO} environment
     * variable. Default: {@code true}.
     *
     * @return {@code true} if writes to the {@code last30d} repo are enabled
     */
    public static boolean last30dRepoEnabled() {
        return "true".equalsIgnoreCase(
                Utils.getEnvString("NANOPUB_QUERY_ENABLE_LAST30D_REPO", "true"));
    }

    private FeatureFlags() {
    }

}
