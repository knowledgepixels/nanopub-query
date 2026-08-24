package com.knowledgepixels.query;

/**
 * Operator-controlled feature flags, read from the environment on each call. Kept
 * as a central table so operator-controlled features have consistent naming and a
 * single place to audit. The {@code NANOPUB_QUERY_ENABLE_*} flags default to
 * {@code true}, i.e. the feature is enabled unless explicitly disabled;
 * {@link #localInstance()} and {@link #allowTestRegistry()} default to
 * {@code false}.
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
     * When {@code false}, all spaces-related work is disabled: space-relevant
     * nanopubs are not extracted into {@code npa:spacesGraph}, the {@code spaces}
     * repo is never auto-created, and no space-state materialization runs.
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

    /**
     * When {@code false}, the periodic shard-consistency sweep
     * ({@link ShardReconciler#tick()}) is disabled: no reconciliation checkpoint
     * is established and missing shards are never detected or re-loaded.
     *
     * <p>Controlled by the {@code NANOPUB_QUERY_ENABLE_RECONCILIATION} environment
     * variable. Default: {@code true}.
     *
     * @return {@code true} if the shard-consistency sweep is enabled
     */
    public static boolean reconciliationEnabled() {
        return "true".equalsIgnoreCase(
                Utils.getEnvString("NANOPUB_QUERY_ENABLE_RECONCILIATION", "true"));
    }

    /**
     * When {@code true}, this instance is declared local/private: nanopubs typed
     * {@code npx:ProtectedNanopub} are loaded like any other. On the default
     * public setting they are rejected at load time
     * ({@link NanopubLoader}), as the 1st-generation nanopub-server did with its
     * {@code run.as.local.server} flag, so that content its publisher marked as
     * protected is never served from a public query endpoint.
     *
     * <p>Controlled by the {@code NANOPUB_QUERY_LOCAL_INSTANCE} environment
     * variable. Default: {@code false}. Never enable this on a public instance.
     *
     * @return {@code true} if this instance is configured as local/private
     */
    public static boolean localInstance() {
        return "true".equalsIgnoreCase(
                Utils.getEnvString("NANOPUB_QUERY_LOCAL_INSTANCE", "false"));
    }

    /**
     * When {@code true}, this instance keeps ingesting from its attached Nanopub
     * Registry even if that registry declares itself a test instance via the
     * {@code Nanopub-Registry-Test-Instance: true} response header. On the default
     * setting such a registry's content is ignored: {@link JellyNanopubLoader} loads
     * no nanopubs and fetches no trust state from it, mirroring what the registry
     * itself does with peers that report the same flag (it skips them outright).
     *
     * <p>A registry sets the flag at DB initialization from its own
     * {@code REGISTRY_TEST_INSTANCE} variable, so it marks content that must not
     * leak into production indexes. Enable this only for a Query instance that is
     * deliberately paired with a test registry, where ingesting test content is the
     * whole point (issue #25).
     *
     * <p>Content already loaded before the registry started reporting the flag is
     * left in place; the flag only stops further ingestion.
     *
     * <p>Controlled by the {@code NANOPUB_QUERY_ALLOW_TEST_REGISTRY} environment
     * variable. Default: {@code false}.
     *
     * @return {@code true} if content from a test-instance registry may be loaded
     */
    public static boolean allowTestRegistry() {
        return "true".equalsIgnoreCase(
                Utils.getEnvString("NANOPUB_QUERY_ALLOW_TEST_REGISTRY", "false"));
    }
    
  /**
     * When {@code false}, the pending-account mirror is disabled: introduced-but-
     * unapproved accounts are not written into the space-state graph as
     * {@code npa:PendingAccountState} rows, so self-signed observer roles from
     * not-yet-approved users stay invisible (the pre-issue-#195 behaviour).
     *
     * <p>Kill switch for an operator who does not want self-asserted introductions
     * to surface at all — e.g. a deployment that treats trust approval as the only
     * admission gate for visibility. Pending rows never confer authority either
     * way; they carry a distinct class precisely so that no authority join can
     * match them.
     *
     * <p>Controlled by the {@code NANOPUB_QUERY_ENABLE_PENDING_ACCOUNTS}
     * environment variable. Default: {@code true}.
     *
     * @return {@code true} if the pending-account mirror is enabled
     */
    public static boolean pendingAccountsEnabled() {
        return "true".equalsIgnoreCase(
                Utils.getEnvString("NANOPUB_QUERY_ENABLE_PENDING_ACCOUNTS", "true"));
    }

    private FeatureFlags() {
    }

}
