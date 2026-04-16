package com.knowledgepixels.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;

import org.junit.jupiter.api.Test;

class TrustStateSnapshotTest {

    /** Mirrors the live registry response shape: trustStateCounter is BSON-wrapped, plain
     *  ZonedDateTime.toString() format for createdAt (with [Etc/UTC] zone bracket),
     *  and a couple of representative account entries. */
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
                  "quota": 100000
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
        TrustStateSnapshot.AccountEntry first = s.accounts().get(0);
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
    void parse_returnsUnmodifiableAccountList() {
        TrustStateSnapshot s = TrustStateSnapshot.parse(FIXTURE);
        assertThrows(UnsupportedOperationException.class,
                () -> s.accounts().add(new TrustStateSnapshot.AccountEntry(
                        "x", "y", "z", 0, 0, 0.0, 0L)));
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
        TrustStateSnapshot.AccountEntry a = s.accounts().get(0);
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

}
