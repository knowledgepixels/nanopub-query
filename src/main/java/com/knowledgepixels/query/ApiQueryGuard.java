package com.knowledgepixels.query;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Stops repeated identical {@code /api} requests from piling up copies of one query in RDF4J.
 * <p>
 * The public edge gives up on a request after 10 s, but RDF4J keeps evaluating it until
 * {@code RDF4J_QUERY_TIMEOUT_SECONDS} (60 s by default; see doc/deployment-rationale.md). A
 * client that retries a query which never finishes in time therefore starts a new 60-second
 * evaluation on every attempt while the earlier ones are still running. On 2026-11-05 one such
 * view query, requested a few times a minute, kept 6-9 copies running on every host and used
 * all their cores; the readers it kept open also stopped RDF4J from writing the {@code last30d}
 * repo to disk.
 * <p>
 * Requests are identical when they name the same query with the same parameters. Two rules
 * apply to them:
 * <ul>
 *   <li>At most {@code maxIdenticalInFlight} run at the same time (0 means no limit).</li>
 *   <li>When one ends without a complete response (an error status or a closed connection)
 *       after running for at least {@code slowFailureMillis}, RDF4J may still be evaluating it,
 *       so identical requests are refused until that evaluation must have stopped: its start
 *       plus {@code evaluationLimitMillis}. Quick failures do not count, so a client that cancels
 *       the slower hosts once one has answered does not block anything.</li>
 * </ul>
 * Refused requests get a 503 at once, which costs RDF4J nothing; callers that race several
 * instances take another instance's answer. Requests that complete are never affected.
 * <p>
 * Thread-safe. Requests that are never released (which should not happen) stop counting after
 * {@code staleAfterMillis}, so a lost release cannot block a query for good.
 */
public class ApiQueryGuard {

    /**
     * Outcome of {@link #admit(String, long)}.
     */
    public sealed interface Admission permits Admitted, Refused {
    }

    /**
     * The request may go ahead; pass this to {@link #release(Admitted, boolean, long)} when it ends.
     *
     * @param key          the request's key (query and parameters)
     * @param startMillis  when the request was admitted
     */
    public record Admitted(String key, long startMillis) implements Admission {
    }

    /**
     * The request is refused.
     *
     * @param reason             why, for the response body and the log
     * @param retryAfterSeconds  when to try again, for the {@code Retry-After} header
     */
    public record Refused(String reason, long retryAfterSeconds) implements Admission {
    }

    private static final class Entry {
        final List<Admitted> inFlight = new ArrayList<>();
        long busyUntil;
    }

    private final int maxIdenticalInFlight;
    private final long slowFailureMillis;
    private final long evaluationLimitMillis;
    private final long staleAfterMillis;
    private final Map<String, Entry> entries = new HashMap<>();

    /**
     * Creates a guard.
     *
     * @param maxIdenticalInFlight   how many identical requests may run at once; 0 for no limit
     * @param slowFailureMillis      how long a request must have run before its failure blocks
     *                               identical requests; 0 to never block after failures
     * @param evaluationLimitMillis  how long RDF4J may evaluate a query after it started
     * @param staleAfterMillis       after how long an unreleased request stops counting
     */
    public ApiQueryGuard(int maxIdenticalInFlight, long slowFailureMillis, long evaluationLimitMillis, long staleAfterMillis) {
        this.maxIdenticalInFlight = maxIdenticalInFlight;
        this.slowFailureMillis = slowFailureMillis;
        this.evaluationLimitMillis = evaluationLimitMillis;
        this.staleAfterMillis = staleAfterMillis;
    }

    /**
     * Creates the guard configured by the environment: {@code NANOPUB_QUERY_MAX_IDENTICAL_IN_FLIGHT}
     * (default 3) and {@code NANOPUB_QUERY_SLOW_FAILURE_SECONDS} (default 5), with the given
     * RDF4J evaluation limit. A non-positive limit means RDF4J has none, in which case 60 s is
     * assumed.
     *
     * @param queryTimeoutSeconds the {@code RDF4J_QUERY_TIMEOUT_SECONDS} in force
     * @return the configured guard
     */
    public static ApiQueryGuard fromEnvironment(int queryTimeoutSeconds) {
        long limitMillis = 1000L * (queryTimeoutSeconds > 0 ? queryTimeoutSeconds : 60);
        return new ApiQueryGuard(
                Utils.getEnvInt("NANOPUB_QUERY_MAX_IDENTICAL_IN_FLIGHT", 3),
                1000L * Utils.getEnvInt("NANOPUB_QUERY_SLOW_FAILURE_SECONDS", 5),
                limitMillis,
                2 * limitMillis);
    }

    /**
     * Decides whether a request may go ahead.
     *
     * @param key  the request's key: query and parameters
     * @param now  the current time in milliseconds
     * @return {@link Admitted} or {@link Refused}
     */
    public synchronized Admission admit(String key, long now) {
        Entry e = entries.get(key);
        if (e != null) {
            e.inFlight.removeIf(a -> now - a.startMillis() >= staleAfterMillis);
            if (e.busyUntil > now) {
                return new Refused("an identical request ran for a long time without completing, and the database"
                        + " may still be evaluating it", seconds(e.busyUntil - now));
            }
            if (maxIdenticalInFlight > 0 && e.inFlight.size() >= maxIdenticalInFlight) {
                return new Refused(e.inFlight.size() + " identical requests are already running",
                        Math.max(1, seconds(slowFailureMillis)));
            }
        } else {
            e = new Entry();
            entries.put(key, e);
        }
        Admitted admitted = new Admitted(key, now);
        e.inFlight.add(admitted);
        return admitted;
    }

    /**
     * Records the end of an admitted request. Call it exactly once per {@link Admitted}; further
     * calls are ignored.
     *
     * @param admitted   what {@link #admit(String, long)} returned
     * @param completed  whether a complete response with a non-error status was sent
     * @param now        the current time in milliseconds
     */
    public synchronized void release(Admitted admitted, boolean completed, long now) {
        Entry e = entries.get(admitted.key());
        if (e == null || !e.inFlight.remove(admitted)) {
            return;
        }
        if (!completed && slowFailureMillis > 0 && now - admitted.startMillis() >= slowFailureMillis) {
            e.busyUntil = Math.max(e.busyUntil, admitted.startMillis() + evaluationLimitMillis);
        }
        if (e.inFlight.isEmpty() && e.busyUntil <= now) {
            entries.remove(admitted.key());
        }
    }

    /**
     * Drops entries with nothing running and no block in force, and requests that stopped counting.
     *
     * @param now the current time in milliseconds
     */
    public synchronized void purge(long now) {
        entries.values().removeIf(e -> {
            e.inFlight.removeIf(a -> now - a.startMillis() >= staleAfterMillis);
            return e.inFlight.isEmpty() && e.busyUntil <= now;
        });
    }

    /**
     * @return the number of keys currently tracked (for tests and metrics)
     */
    synchronized int trackedKeys() {
        return entries.size();
    }

    private static long seconds(long millis) {
        return (millis + 999) / 1000;
    }

}
