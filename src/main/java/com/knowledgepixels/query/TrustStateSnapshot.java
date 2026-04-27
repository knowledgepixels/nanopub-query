package com.knowledgepixels.query;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Parsed envelope of a {@code /trust-state/<hash>.json} response from the
 * registry. Immutable; produced by {@link #parse(String)}.
 *
 * <p>The {@code trustStateCounter} field arrives as MongoDB extended JSON
 * (e.g. {@code {"$numberLong": "1"}}) when the registry's serializer chooses
 * to wrap a long. The parser handles either wrapped or plain numeric form.
 *
 * <p>The {@code createdAt} field uses Java's {@code ZonedDateTime.toString()}
 * shape — ISO-8601 plus an optional {@code [Etc/UTC]} zone bracket — which
 * {@link ZonedDateTime#parse(CharSequence)} reads natively.
 *
 * @param trustStateHash the hash advertised by the registry for this snapshot
 * @param trustStateCounter monotonic counter; matches the registry's value
 * @param createdAt when the registry committed this snapshot
 * @param accounts one entry per non-{@code "$"} account in the trust graph
 */
public record TrustStateSnapshot(
        String trustStateHash,
        long trustStateCounter,
        Instant createdAt,
        List<AccountEntry> accounts
) {

    /**
     * One account in a trust state snapshot. Mirrors the fields the registry
     * exposes; consumers (e.g. authority queries) decide which {@code status}
     * values count as "approved".
     *
     * <p>{@code pathCount}, {@code ratio}, and {@code quota} can be {@code null}
     * for accounts with {@code status == "skipped"} (trust calculation rejected
     * them, so those stats aren't meaningful). {@code name} and {@code nameCreatedAt}
     * are {@code null} when the declaring intro nanopub asserted no
     * {@code foaf:name} on the agent (or when running against a registry that
     * predates the field — additive non-breaking schema). Boxed types preserve
     * the absence.
     *
     * @param pubkey hex-encoded public key
     * @param agent agent IRI (typically an ORCID, but any IRI is allowed)
     * @param status one of the registry's {@code EntryStatus} values
     *               (e.g. {@code "loaded"}, {@code "toLoad"}, {@code "skipped"})
     * @param depth steps from the trust seed
     * @param pathCount number of independent trust paths, or {@code null} for skipped accounts
     * @param ratio aggregated trust score, or {@code null} for skipped accounts
     * @param quota allocated upload quota, or {@code null} for skipped accounts
     * @param name {@code foaf:name} from the declaring intro nanopub, or {@code null} if absent
     * @param nameCreatedAt {@code dct:created} of the declaring intro, or {@code null} if absent.
     *                      Used to break ties when multiple intros declare the same {@code (agent, pubkey)};
     *                      consumers picking one canonical name per agent across keys typically use
     *                      {@code MAX(ratio)} with this as a secondary tiebreaker.
     */
    public record AccountEntry(
            String pubkey,
            String agent,
            String status,
            Integer depth,
            Integer pathCount,
            Double ratio,
            Long quota,
            String name,
            Instant nameCreatedAt
    ) {}

    /**
     * Parses a {@code /trust-state/<hash>.json} envelope from its JSON
     * serialization. Throws {@link IllegalArgumentException} if the JSON is
     * malformed or missing required fields.
     *
     * @param json the response body as a string
     * @return the parsed snapshot
     * @throws IllegalArgumentException if parsing fails
     */
    public static TrustStateSnapshot parse(String json) {
        JsonObject obj;
        try {
            obj = new JsonObject(json);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Trust state envelope is not valid JSON", ex);
        }

        String hash = requireString(obj, "trustStateHash");
        long counter = unwrapLong(obj, "trustStateCounter");

        String rawCreatedAt = requireString(obj, "createdAt");
        Instant createdAt;
        try {
            createdAt = ZonedDateTime.parse(rawCreatedAt).toInstant();
        } catch (Exception ex) {
            throw new IllegalArgumentException(
                    "Cannot parse createdAt as ZonedDateTime: " + rawCreatedAt, ex);
        }

        JsonArray accountsArray = obj.getJsonArray("accounts");
        if (accountsArray == null) {
            throw new IllegalArgumentException("Trust state envelope is missing 'accounts' array");
        }
        List<AccountEntry> accounts = new ArrayList<>(accountsArray.size());
        for (int i = 0; i < accountsArray.size(); i++) {
            JsonObject a;
            try {
                a = accountsArray.getJsonObject(i);
            } catch (ClassCastException ex) {
                throw new IllegalArgumentException(
                        "Trust state account entry " + i + " is not a JSON object", ex);
            }
            accounts.add(new AccountEntry(
                    requireString(a, "pubkey"),
                    requireString(a, "agent"),
                    requireString(a, "status"),
                    a.getInteger("depth"),
                    a.getInteger("pathCount"),
                    a.getDouble("ratio"),
                    unwrapLongNullable(a, "quota"),
                    a.getString("name"),
                    unwrapDateNullable(a, "nameCreatedAt")
            ));
        }

        return new TrustStateSnapshot(hash, counter, createdAt,
                Collections.unmodifiableList(accounts));
    }

    private static String requireString(JsonObject obj, String key) {
        String s = obj.getString(key);
        if (s == null) {
            throw new IllegalArgumentException("Trust state envelope is missing required field: " + key);
        }
        return s;
    }

    /**
     * Reads a required long-typed field, transparently handling MongoDB
     * extended JSON ({@code {"$numberLong": "..."}}) as well as plain numeric
     * or string forms. Throws if the field is missing or null.
     */
    private static long unwrapLong(JsonObject obj, String key) {
        Long v = unwrapLongNullable(obj, key);
        if (v == null) {
            throw new IllegalArgumentException("Trust state envelope is missing required field: " + key);
        }
        return v;
    }

    /**
     * Reads an optional date-typed field. Handles MongoDB extended JSON
     * ({@code {"$date": "..."}} or {@code {"$date": {"$numberLong": "..."}}}),
     * plain ISO-8601 strings, and JSON {@code null}/missing. Returns {@code null}
     * for any of those last cases so the field is fully optional — consumers
     * working with snapshots from a registry that predates the {@code nameCreatedAt}
     * field still parse cleanly.
     */
    private static Instant unwrapDateNullable(JsonObject obj, String key) {
        Object v = obj.getValue(key);
        if (v == null) return null;
        if (v instanceof JsonObject j) {
            Object dateVal = j.getValue("$date");
            if (dateVal instanceof String s) {
                try {
                    return Instant.parse(s);
                } catch (Exception ex) {
                    throw new IllegalArgumentException("Cannot parse " + key + " as ISO-8601 instant: " + s, ex);
                }
            }
            if (dateVal instanceof JsonObject inner) {
                String numberLong = inner.getString("$numberLong");
                if (numberLong != null) return Instant.ofEpochMilli(Long.parseLong(numberLong));
            }
            if (dateVal instanceof Number n) return Instant.ofEpochMilli(n.longValue());
            throw new IllegalArgumentException("Cannot unwrap " + key + " from extended JSON $date: " + j);
        }
        if (v instanceof String s) {
            try {
                return Instant.parse(s);
            } catch (Exception ex) {
                throw new IllegalArgumentException("Cannot parse " + key + " as ISO-8601 instant: " + s, ex);
            }
        }
        if (v instanceof Number n) return Instant.ofEpochMilli(n.longValue());
        throw new IllegalArgumentException("Cannot unwrap " + key + " as date: " + v);
    }

    /**
     * Reads an optional long-typed field, returning {@code null} if the field
     * is absent or its value is JSON {@code null}. Same extended-JSON handling
     * as {@link #unwrapLong(JsonObject, String)}.
     */
    private static Long unwrapLongNullable(JsonObject obj, String key) {
        Object v = obj.getValue(key);
        if (v == null) return null;
        if (v instanceof Number n) return n.longValue();
        if (v instanceof JsonObject j) {
            String s = j.getString("$numberLong");
            if (s != null) return Long.parseLong(s);
        }
        if (v instanceof String s) return Long.parseLong(s);
        throw new IllegalArgumentException("Cannot unwrap " + key + " as long: " + v);
    }

}
