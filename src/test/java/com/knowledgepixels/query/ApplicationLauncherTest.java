package com.knowledgepixels.query;

import io.vertx.core.VertxOptions;
import io.vertx.micrometer.MicrometerMetricsOptions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApplicationLauncherTest {

    @Test
    void beforeStartingVertxEnablesMetrics() {
        VertxOptions options = new VertxOptions();
        new ApplicationLauncher().beforeStartingVertx(options);

        assertTrue(options.getMetricsOptions().isEnabled());
        assertTrue(((MicrometerMetricsOptions) options.getMetricsOptions()).isJvmMetricsEnabled());
        assertTrue(((MicrometerMetricsOptions) options.getMetricsOptions()).getPrometheusOptions().isEnabled());
    }

    @Test
    void beforeStartingVertxWithNullOptions() {
        assertThrows(NullPointerException.class, () -> new ApplicationLauncher().beforeStartingVertx(null));
    }

    @Test
    void mainMethod() {
        PrintStream originalOut = System.out;
        ByteArrayOutputStream outContent = new ByteArrayOutputStream();
        System.setOut(new PrintStream(outContent));
        try {
            ApplicationLauncher.main(new String[]{"list"});
            String output = outContent.toString();
            assertTrue(output.contains("Listing vert.x applications..."));
        } finally {
            System.setOut(originalOut);
        }
    }

}