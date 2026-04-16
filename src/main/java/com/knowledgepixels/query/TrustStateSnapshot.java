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
     * @param pubkey hex-encoded public key
     * @param agent agent IRI (typically an ORCID, but any IRI is allowed)
     * @param status one of the registry's {@code EntryStatus} values
     *               (e.g. {@code "loaded"}, {@code "toLoad"}, {@code "skipped"})
     * @param depth steps from the trust seed
     * @param pathCount number of independent trust paths
     * @param ratio aggregated trust score
     * @param quota allocated upload quota
     */
    public record AccountEntry(
            String pubkey,
            String agent,
            String status,
            int depth,
            int pathCount,
            double ratio,
            long quota
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
                    unwrapLong(a, "quota")
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
     * Reads a long-typed field, transparently handling MongoDB extended JSON
     * ({@code {"$numberLong": "..."}}) as well as plain numeric / string forms.
     */
    private static long unwrapLong(JsonObject obj, String key) {
        Object v = obj.getValue(key);
        if (v == null) {
            throw new IllegalArgumentException("Trust state envelope is missing required field: " + key);
        }
        if (v instanceof Number n) return n.longValue();
        if (v instanceof JsonObject j) {
            String s = j.getString("$numberLong");
            if (s != null) return Long.parseLong(s);
        }
        if (v instanceof String s) return Long.parseLong(s);
        throw new IllegalArgumentException("Cannot unwrap " + key + " as long: " + v);
    }

}
