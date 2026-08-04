package com.knowledgepixels.query;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class TrustStateSnapshotTest {

    /**
     * Mirrors the live registry response shape: trustStateCounter is BSON-wrapped, plain
     * ZonedDateTime.toString() format for createdAt (with [Etc/UTC] zone bracket),
     * and a couple of representative account entries.
     */
    private static final String FIXTURE = """
            {
              "trustStateHash": "abc123",
              "trustStateCounter": {"$numberLong": "18"},
              "createdAt": "2026-04-15T14:16:16.112094241Z[Etc/UTC]",
              "accounts": [
                {
                  "pubkey": "edf7482308e4e59fc3f658fbd1fe2a2a9a538de3adce2ec7ad6c5f804461d310",
                  "agent": "https://orcid.org/0000-0001-5118-256X",
                  "status": "toLoad",
                  "depth": 1,
                  "pathCount": 1,
                  "ratio": 0.008181818181818182,
                  "quota": 100000,
                  "introNanopub": "http://purl.org/np/RAn2hFURf8krekyne_hB3hdvF-PB-r4Qvy3uLFXAp1CQ0"
                },
                {
                  "pubkey": "1162349fdeaf431e71ab55898cb2a425b971d466150c2aa5b3c1beb498045a37",
                  "agent": "https://orcid.org/0000-0002-1267-0234",
                  "status": "loaded",
                  "depth": 2,
                  "pathCount": 3,
                  "ratio": 0.0024,
                  "quota": 24000
                }
              ]
            }
            """;

    @Test
    void parse_extractsEnvelopeFields() {
        TrustStateSnapshot s = TrustStateSnapshot.parse(FIXTURE);
        assertEquals("abc123", s.trustStateHash());
        assertEquals(18L, s.trustStateCounter());
        // Reproduce the exact instant: 2026-04-15T14:16:16.112094241Z
        assertEquals(Instant.parse("2026-04-15T14:16:16.112094241Z"), s.createdAt());
        assertEquals(2, s.accounts().size());
    }

    @Test
    void parse_unwrapsBsonNumberLongCounter() {
        // The {"$numberLong": "18"} wrap is what MongoDB extended JSON looks like.
        TrustStateSnapshot s = TrustStateSnapshot.parse(FIXTURE);
        assertEquals(18L, s.trustStateCounter());
    }

    @Test
    void parse_acceptsPlainNumericCounter() {
        // If MongoDB serializes the long as plain JSON (smaller values, future change), still works.
        String json = FIXTURE.replace("{\"$numberLong\": \"18\"}", "18");
        TrustStateSnapshot s = TrustStateSnapshot.parse(json);
        assertEquals(18L, s.trustStateCounter());
    }

    @Test
    void parse_extractsAccountEntryFields() {
        TrustStateSnapshot s = TrustStateSnapshot.parse(FIXTURE);
        TrustStateSnapshot.AccountEntry first = s.accounts().getFirst();
        assertEquals("edf7482308e4e59fc3f658fbd1fe2a2a9a538de3adce2ec7ad6c5f804461d310", first.pubkey());
        assertEquals("https://orcid.org/0000-0001-5118-256X", first.agent());
        assertEquals("toLoad", first.status());
        assertEquals(1, first.depth());
        assertEquals(1, first.pathCount());
        assertEquals(0.008181818181818182, first.ratio(), 1e-15);
        assertEquals(100000L, first.quota());

        TrustStateSnapshot.AccountEntry second = s.accounts().get(1);
        assertEquals("loaded", second.status());
        assertEquals(2, second.depth());
        assertEquals(3, second.pathCount());
        assertEquals(24000L, second.quota());
    }

    @Test
    void parse_extractsIntroNanopubWhenPresentAndNullWhenAbsent() {
        // nanopub-registry#117/#118: the authorizing intro is stamped per account. It is
        // additive, so a snapshot from a registry that predates it has no field → null.
        TrustStateSnapshot s = TrustStateSnapshot.parse(FIXTURE);
        assertEquals("http://purl.org/np/RAn2hFURf8krekyne_hB3hdvF-PB-r4Qvy3uLFXAp1CQ0",
                s.accounts().getFirst().introNanopub(),
                "introNanopub extracted when present");
        assertNull(s.accounts().get(1).introNanopub(),
                "introNanopub is null when the account row omits it (pre-#118 registry)");
    }

    @Test
    void parse_returnsUnmodifiableAccountList() {
        TrustStateSnapshot s = TrustStateSnapshot.parse(FIXTURE);
        assertThrows(UnsupportedOperationException.class,
                () -> s.accounts().add(new TrustStateSnapshot.AccountEntry(
                        "x", "y", "z", 0, 0, 0.0, 0L, null, null)));
    }

    @Test
    void parse_emptyAccountsArrayIsValid() {
        String json = """
                {
                  "trustStateHash": "abc",
                  "trustStateCounter": {"$numberLong": "1"},
                  "createdAt": "2026-04-15T14:16:16Z[Etc/UTC]",
                  "accounts": []
                }""";
        TrustStateSnapshot s = TrustStateSnapshot.parse(json);
        assertTrue(s.accounts().isEmpty());
    }

    @Test
    void parse_throwsOnMalformedJson() {
        assertThrows(IllegalArgumentException.class,
                () -> TrustStateSnapshot.parse("{not-valid"));
    }

    @Test
    void parse_throwsOnMissingTrustStateHash() {
        String json = FIXTURE.replace("\"trustStateHash\": \"abc123\",", "");
        assertThrows(IllegalArgumentException.class, () -> TrustStateSnapshot.parse(json));
    }

    @Test
    void parse_throwsOnMissingAccountsArray() {
        String json = """
                {
                  "trustStateHash": "abc",
                  "trustStateCounter": {"$numberLong": "1"},
                  "createdAt": "2026-04-15T14:16:16Z[Etc/UTC]"
                }""";
        assertThrows(IllegalArgumentException.class, () -> TrustStateSnapshot.parse(json));
    }

    @Test
    void parse_throwsOnUnparseableTimestamp() {
        String json = FIXTURE.replace(
                "\"createdAt\": \"2026-04-15T14:16:16.112094241Z[Etc/UTC]\"",
                "\"createdAt\": \"not-a-date\"");
        assertThrows(IllegalArgumentException.class, () -> TrustStateSnapshot.parse(json));
    }

    @Test
    void parse_throwsOnMissingAccountField() {
        String json = FIXTURE.replace(
                "\"pubkey\": \"edf7482308e4e59fc3f658fbd1fe2a2a9a538de3adce2ec7ad6c5f804461d310\",",
                "");
        assertThrows(IllegalArgumentException.class, () -> TrustStateSnapshot.parse(json));
    }

    @Test
    void parse_extractsNameAndNameCreatedAtWhenPresent() {
        // Registry-side change (#62): accounts may carry foaf:name + dct:created
        // of the declaring intro. nameCreatedAt arrives as MongoDB extended JSON
        // ({"$date": "..."}) when the registry serializes a Date; the parser
        // must accept that shape as well as plain ISO-8601 strings.
        String json = """
                {
                  "trustStateHash": "abc",
                  "trustStateCounter": {"$numberLong": "1"},
                  "createdAt": "2026-04-15T14:16:16Z[Etc/UTC]",
                  "accounts": [
                    {
                      "pubkey": "abcdef",
                      "agent": "https://orcid.org/0000-0002-1267-0234",
                      "status": "loaded",
                      "depth": 1,
                      "pathCount": 1,
                      "ratio": 0.5,
                      "quota": 100,
                      "name": "Tobias Kuhn",
                      "nameCreatedAt": {"$date": "2025-11-12T10:30:00Z"}
                    }
                  ]
                }
                """;
        TrustStateSnapshot s = TrustStateSnapshot.parse(json);
        TrustStateSnapshot.AccountEntry a = s.accounts().getFirst();
        assertEquals("Tobias Kuhn", a.name());
        assertEquals(Instant.parse("2025-11-12T10:30:00Z"), a.nameCreatedAt());
    }

    @Test
    void parse_acceptsAccountWithoutNameFields() {
        // Registry that predates the name field: no "name" / "nameCreatedAt"
        // keys at all. Parser must treat them as null, not throw — the schema
        // is additive and consumers must work against either registry version.
        TrustStateSnapshot s = TrustStateSnapshot.parse(FIXTURE);
        TrustStateSnapshot.AccountEntry a = s.accounts().getFirst();
        assertNull(a.name());
        assertNull(a.nameCreatedAt());
    }

    @Test
    void parse_acceptsPlainStringNameCreatedAt() {
        // If the registry ever serializes nameCreatedAt as a plain ISO-8601
        // string (no $date wrap), the parser must still accept it.
        String json = """
                {
                  "trustStateHash": "abc",
                  "trustStateCounter": {"$numberLong": "1"},
                  "createdAt": "2026-04-15T14:16:16Z[Etc/UTC]",
                  "accounts": [
                    {
                      "pubkey": "x",
                      "agent": "https://example.org/agent",
                      "status": "loaded",
                      "depth": 1,
                      "pathCount": 1,
                      "ratio": 0.5,
                      "quota": 100,
                      "name": "Alice",
                      "nameCreatedAt": "2025-06-15T09:00:00Z"
                    }
                  ]
                }
                """;
        TrustStateSnapshot s = TrustStateSnapshot.parse(json);
        assertEquals(Instant.parse("2025-06-15T09:00:00Z"), s.accounts().getFirst().nameCreatedAt());
    }

    @Test
    void parse_acceptsSkippedAccountWithNullStats() {
        // Accounts rejected by trust calculation (status=skipped) have null
        // pathCount / ratio / quota. The parser must accept this and pass
        // the nulls through so materialization can choose to skip those
        // triples rather than inventing zero values.
        String json = """
                {
                  "trustStateHash": "abc",
                  "trustStateCounter": {"$numberLong": "1"},
                  "createdAt": "2026-04-15T14:16:16Z[Etc/UTC]",
                  "accounts": [
                    {
                      "pubkey": "a5c5aa...",
                      "agent": "https://orcid.org/0000-0001-8327-0142",
                      "status": "skipped",
                      "depth": 2,
                      "pathCount": null,
                      "ratio": null,
                      "quota": null
                    }
                  ]
                }
                """;
        TrustStateSnapshot s = TrustStateSnapshot.parse(json);
        TrustStateSnapshot.AccountEntry a = s.accounts().getFirst();
        assertEquals("skipped", a.status());
        assertEquals(2, a.depth());
        assertNull(a.pathCount());
        assertNull(a.ratio());
        assertNull(a.quota());
    }

    @Test
    void parse_acceptsCreatedAtWithoutZoneBracket() {
        // Plain ISO-8601 with offset is also accepted by ZonedDateTime.parse.
        String json = FIXTURE.replace(
                "\"createdAt\": \"2026-04-15T14:16:16.112094241Z[Etc/UTC]\"",
                "\"createdAt\": \"2026-04-15T14:16:16.112094241Z\"");
        TrustStateSnapshot s = TrustStateSnapshot.parse(json);
        assertEquals(Instant.parse("2026-04-15T14:16:16.112094241Z"), s.createdAt());
    }

    // ---------------- extended-JSON unwrapping ----------------
    //
    // Which of these shapes the registry emits depends on its BSON serializer
    // configuration, so the parser accepts all of them. These cases lock in that
    // tolerance (and the failure mode for shapes it cannot make sense of).

    /** Builds a one-account envelope with the given raw JSON for a single field. */
    private static String withAccountField(String field, String rawJsonValue) {
        return """
                {
                  "trustStateHash": "abc",
                  "trustStateCounter": {"$numberLong": "1"},
                  "createdAt": "2026-04-15T14:16:16Z[Etc/UTC]",
                  "accounts": [
                    {
                      "pubkey": "x",
                      "agent": "https://example.org/agent",
                      "status": "loaded",
                      "%s": %s
                    }
                  ]
                }
                """.formatted(field, rawJsonValue);
    }

    @Test
    void parse_unwrapsNameCreatedAtFromNestedNumberLongDate() {
        // {"$date": {"$numberLong": "..."}} — the shape Mongo uses for dates
        // outside the signed-32-bit-seconds range in canonical extended JSON.
        TrustStateSnapshot s = TrustStateSnapshot.parse(
                withAccountField("nameCreatedAt", "{\"$date\": {\"$numberLong\": \"1750000000000\"}}"));
        assertEquals(Instant.ofEpochMilli(1750000000000L),
                s.accounts().getFirst().nameCreatedAt());
    }

    @Test
    void parse_unwrapsNameCreatedAtFromNumericDate() {
        // {"$date": 1750000000000} — relaxed extended JSON.
        TrustStateSnapshot s = TrustStateSnapshot.parse(
                withAccountField("nameCreatedAt", "{\"$date\": 1750000000000}"));
        assertEquals(Instant.ofEpochMilli(1750000000000L),
                s.accounts().getFirst().nameCreatedAt());
    }

    @Test
    void parse_unwrapsNameCreatedAtFromWrappedIsoString() {
        // {"$date": "2025-06-15T09:00:00Z"} — the common wrapped-string form.
        TrustStateSnapshot s = TrustStateSnapshot.parse(
                withAccountField("nameCreatedAt", "{\"$date\": \"2025-06-15T09:00:00Z\"}"));
        assertEquals(Instant.parse("2025-06-15T09:00:00Z"),
                s.accounts().getFirst().nameCreatedAt());
    }

    @Test
    void parse_acceptsBareEpochMillisForNameCreatedAt() {
        TrustStateSnapshot s = TrustStateSnapshot.parse(
                withAccountField("nameCreatedAt", "1750000000000"));
        assertEquals(Instant.ofEpochMilli(1750000000000L),
                s.accounts().getFirst().nameCreatedAt());
    }

    @Test
    void parse_treatsExplicitNullNameCreatedAtAsAbsent() {
        TrustStateSnapshot s = TrustStateSnapshot.parse(
                withAccountField("nameCreatedAt", "null"));
        assertNull(s.accounts().getFirst().nameCreatedAt());
    }

    @Test
    void parse_throwsOnUnparseableNameCreatedAtString() {
        // A malformed timestamp is a real data problem — better to fail the whole
        // snapshot loudly than to silently drop the field and mis-resolve names.
        assertThrows(IllegalArgumentException.class, () -> TrustStateSnapshot.parse(
                withAccountField("nameCreatedAt", "\"15 June 2025\"")));
        assertThrows(IllegalArgumentException.class, () -> TrustStateSnapshot.parse(
                withAccountField("nameCreatedAt", "{\"$date\": \"15 June 2025\"}")));
    }

    @Test
    void parse_throwsOnUnrecognisedNameCreatedAtShape() {
        // An object that isn't a $date wrapper at all, and a JSON type that can
        // never be a date.
        assertThrows(IllegalArgumentException.class, () -> TrustStateSnapshot.parse(
                withAccountField("nameCreatedAt", "{\"unexpected\": 1}")));
        assertThrows(IllegalArgumentException.class, () -> TrustStateSnapshot.parse(
                withAccountField("nameCreatedAt", "{\"$date\": true}")));
        assertThrows(IllegalArgumentException.class, () -> TrustStateSnapshot.parse(
                withAccountField("nameCreatedAt", "true")));
    }

    @Test
    void parse_acceptsQuotaAsAStringAndAsExtendedJson() {
        assertEquals(4200L, TrustStateSnapshot.parse(withAccountField("quota", "\"4200\""))
                .accounts().getFirst().quota());
        assertEquals(4200L, TrustStateSnapshot.parse(withAccountField("quota", "{\"$numberLong\": \"4200\"}"))
                .accounts().getFirst().quota());
        assertEquals(4200L, TrustStateSnapshot.parse(withAccountField("quota", "4200"))
                .accounts().getFirst().quota());
        assertNull(TrustStateSnapshot.parse(withAccountField("quota", "null"))
                .accounts().getFirst().quota());
    }

    @Test
    void parse_throwsOnUnrecognisedQuotaShape() {
        // A JSON object that carries no $numberLong falls through to the error —
        // it must not be silently read as null, which would erase a real quota.
        assertThrows(IllegalArgumentException.class, () -> TrustStateSnapshot.parse(
                withAccountField("quota", "{\"unexpected\": 1}")));
        assertThrows(IllegalArgumentException.class, () -> TrustStateSnapshot.parse(
                withAccountField("quota", "true")));
    }

    @Test
    void parse_throwsOnMissingTrustStateCounter() {
        String json = """
                {
                  "trustStateHash": "abc",
                  "createdAt": "2026-04-15T14:16:16Z[Etc/UTC]",
                  "accounts": []
                }
                """;
        assertThrows(IllegalArgumentException.class, () -> TrustStateSnapshot.parse(json));
    }

    @Test
    void parse_acceptsTrustStateCounterAsAString() {
        String json = """
                {
                  "trustStateHash": "abc",
                  "trustStateCounter": "77",
                  "createdAt": "2026-04-15T14:16:16Z[Etc/UTC]",
                  "accounts": []
                }
                """;
        assertEquals(77L, TrustStateSnapshot.parse(json).trustStateCounter());
    }

    @Test
    void parse_throwsWhenAnAccountEntryIsNotAnObject() {
        String json = """
                {
                  "trustStateHash": "abc",
                  "trustStateCounter": 1,
                  "createdAt": "2026-04-15T14:16:16Z[Etc/UTC]",
                  "accounts": ["not-an-object"]
                }
                """;
        assertThrows(IllegalArgumentException.class, () -> TrustStateSnapshot.parse(json));
    }

}
