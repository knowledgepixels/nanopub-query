package com.knowledgepixels.query;

import io.vertx.core.Promise;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The start promise must complete once the servers listen: Vert.x 5's launcher stops the
 * application when the deployment does not complete in time (1.28.3 restarted every two minutes).
 */
class MainVerticleStartTest {

    @Test
    void startPromiseCompletesOnceAllServersListen() {
        Promise<Void> start = Promise.promise();
        Promise<String> metrics = Promise.promise();
        Promise<String> proxy = Promise.promise();

        MainVerticle.completeWhenListening(start, metrics.future(), proxy.future());
        assertFalse(start.future().isComplete());

        metrics.complete("metrics");
        assertFalse(start.future().isComplete());

        proxy.complete("proxy");
        assertTrue(start.future().succeeded());
    }

    @Test
    void startPromiseFailsWhenAServerCannotListen() {
        Promise<Void> start = Promise.promise();
        Promise<String> metrics = Promise.promise();
        Promise<String> proxy = Promise.promise();

        MainVerticle.completeWhenListening(start, metrics.future(), proxy.future());
        metrics.complete("metrics");
        proxy.fail(new IllegalStateException("port 9393 in use"));

        assertTrue(start.future().failed());
        assertEquals("port 9393 in use", start.future().cause().getMessage());
    }

}
