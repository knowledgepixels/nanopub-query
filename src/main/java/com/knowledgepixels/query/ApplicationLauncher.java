package com.knowledgepixels.query;

import io.vertx.core.VertxOptions;
import io.vertx.launcher.application.HookContext;
import io.vertx.launcher.application.VertxApplication;
import io.vertx.launcher.application.VertxApplicationHooks;
import io.vertx.micrometer.Label;
import io.vertx.micrometer.MicrometerMetricsOptions;
import io.vertx.micrometer.VertxPrometheusOptions;

/**
 * This is the launcher class referenced in the pom.xml as a starting point.
 */
public class ApplicationLauncher extends VertxApplication implements VertxApplicationHooks {

    /**
     * Creates the launcher for the given command-line arguments.
     *
     * @param args Initialization parameters
     */
    public ApplicationLauncher(String[] args) {
        super(args);
    }

    /**
     * Initializes the application launcher.
     *
     * @param args Initialization parameters
     */
    public static void main(String[] args) {
        new ApplicationLauncher(args).launch();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void beforeStartingVertx(HookContext context) {
        VertxOptions options = context.vertxOptions();
        options.setMetricsOptions(
                new MicrometerMetricsOptions()
                        .setPrometheusOptions(new VertxPrometheusOptions().setEnabled(true))
                        .setJvmMetricsEnabled(true)
                        .addLabels(Label.HTTP_ROUTE)
                        .setEnabled(true)
        );
    }
}
