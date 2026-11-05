package com.knowledgepixels.query;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ApiQueryGuardTest {

    private static final String Q = "RAhmf1qdhKBoZD8WkzWSyXre5v6qqPHsdkYYlwbmLDdSc/get-recently-active-spaces?";

    // At most 2 identical requests at once; a failure after 5 s blocks until start + 60 s.
    private final ApiQueryGuard guard = new ApiQueryGuard(2, 5_000, 60_000, 120_000);

    private ApiQueryGuard.Admitted admit(String key, long now) {
        ApiQueryGuard.Admission a = guard.admit(key, now);
        return assertInstanceOf(ApiQueryGuard.Admitted.class, a);
    }

    private ApiQueryGuard.Refused refuse(String key, long now) {
        ApiQueryGuard.Admission a = guard.admit(key, now);
        return assertInstanceOf(ApiQueryGuard.Refused.class, a);
    }

    @Test
    void capsIdenticalRequestsInFlight() {
        admit(Q, 0);
        admit(Q, 100);
        ApiQueryGuard.Refused r = refuse(Q, 200);
        assertTrue(r.reason().contains("2 identical requests"));
        assertEquals(5, r.retryAfterSeconds());
        // Other parameters, or another query, are separate.
        admit(Q + "space=x", 200);
        admit("RAother/q?", 200);
    }

    @Test
    void completedRequestFreesItsSlot() {
        ApiQueryGuard.Admitted a = admit(Q, 0);
        admit(Q, 0);
        refuse(Q, 1_000);
        guard.release(a, true, 1_000);
        admit(Q, 1_000);
    }

    @Test
    void slowFailureBlocksUntilTheEvaluationMustHaveStopped() {
        ApiQueryGuard.Admitted a = admit(Q, 0);
        // The edge gave up after 10 s; RDF4J may run on until 60 s.
        guard.release(a, false, 10_000);
        ApiQueryGuard.Refused r = refuse(Q, 10_000);
        assertEquals(50, r.retryAfterSeconds());
        assertEquals(1, refuse(Q, 59_001).retryAfterSeconds());
        admit(Q, 60_000);
        // Unrelated requests are unaffected meanwhile.
        admit(Q + "space=x", 20_000);
    }

    @Test
    void quickFailureDoesNotBlock() {
        // E.g. a client cancelling the slower instances once one has answered.
        ApiQueryGuard.Admitted a = admit(Q, 0);
        guard.release(a, false, 1_200);
        admit(Q, 1_300);
    }

    @Test
    void slowSuccessDoesNotBlock() {
        ApiQueryGuard.Admitted a = admit(Q, 0);
        guard.release(a, true, 30_000);
        admit(Q, 30_000);
    }

    @Test
    void blockFromAnEarlierStartIsNotShortenedByALaterOne() {
        ApiQueryGuard.Admitted first = admit(Q, 0);
        ApiQueryGuard.Admitted second = admit(Q, 3_000);
        guard.release(second, false, 9_000);   // blocks until 63 s
        guard.release(first, false, 10_000);   // would block until 60 s only
        assertEquals(53, refuse(Q, 10_000).retryAfterSeconds());
    }

    @Test
    void unreleasedRequestsStopCountingWhenStale() {
        admit(Q, 0);
        admit(Q, 0);
        refuse(Q, 119_999);
        admit(Q, 120_000);
    }

    @Test
    void secondReleaseIsIgnoredAndPurgeDropsIdleKeys() {
        ApiQueryGuard.Admitted a = admit(Q, 0);
        guard.release(a, true, 100);
        guard.release(a, false, 10_000);
        admit(Q, 10_000);
        assertEquals(1, guard.trackedKeys());

        ApiQueryGuard.Admitted b = admit(Q + "space=x", 0);
        guard.release(b, false, 10_000);
        guard.purge(30_000);
        assertEquals(2, guard.trackedKeys());   // one in flight, one blocked
        guard.purge(240_000);                   // in-flight request stale, block over
        assertEquals(0, guard.trackedKeys());
    }

    @Test
    void zeroDisablesEachRule() {
        ApiQueryGuard open = new ApiQueryGuard(0, 0, 60_000, 120_000);
        ApiQueryGuard.Admitted a = null;
        for (int i = 0; i < 20; i++) {
            a = assertInstanceOf(ApiQueryGuard.Admitted.class, open.admit(Q, 0));
        }
        open.release(a, false, 30_000);
        assertInstanceOf(ApiQueryGuard.Admitted.class, open.admit(Q, 30_000));
    }

    @Test
    void fromEnvironmentUsesTheRdf4jLimit() {
        ApiQueryGuard g = ApiQueryGuard.fromEnvironment(60);
        ApiQueryGuard.Admitted a = assertInstanceOf(ApiQueryGuard.Admitted.class, g.admit(Q, 0));
        g.release(a, false, 10_000);
        ApiQueryGuard.Refused r = assertInstanceOf(ApiQueryGuard.Refused.class, g.admit(Q, 10_000));
        assertEquals(50, r.retryAfterSeconds());
        // Without an RDF4J limit, 60 s is assumed.
        ApiQueryGuard g0 = ApiQueryGuard.fromEnvironment(0);
        a = assertInstanceOf(ApiQueryGuard.Admitted.class, g0.admit(Q, 0));
        g0.release(a, false, 10_000);
        assertEquals(50, assertInstanceOf(ApiQueryGuard.Refused.class, g0.admit(Q, 10_000)).retryAfterSeconds());
    }

}
