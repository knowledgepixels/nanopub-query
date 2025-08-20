package com.knowledgepixels.query;

import io.vertx.core.Launcher;
import io.vertx.core.VertxOptions;
import io.vertx.micrometer.MicrometerMetricsOptions;
import io.vertx.micrometer.VertxPrometheusOptions;

/**
 * This is the launcher class referenced in the pom.xml as a starting point.
 */
public class ApplicationLauncher extends Launcher {

    /**
     * Initializes the application launcher.
     *
     * @param args Initialization parameters
     */
    public static void main(String[] args) {
        new ApplicationLauncher().dispatch(args);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void beforeStartingVertx(VertxOptions options) {
        options.setMetricsOptions(
                new MicrometerMetricsOptions()
                        .setPrometheusOptions(new VertxPrometheusOptions().setEnabled(true))
                        .setJvmMetricsEnabled(true)
                        .setEnabled(true)
        );
    }
}
