package com.knowledgepixels.query;

import io.vertx.core.VertxOptions;
import io.vertx.launcher.application.HookContext;
import io.vertx.micrometer.Label;
import io.vertx.micrometer.MicrometerMetricsOptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApplicationLauncherTest {

    @Test
    void beforeStartingVertxEnablesMetrics() {
        VertxOptions options = new VertxOptions();
        HookContext context = mock(HookContext.class);
        when(context.vertxOptions()).thenReturn(options);

        new ApplicationLauncher(new String[0]).beforeStartingVertx(context);

        MicrometerMetricsOptions metricsOptions = (MicrometerMetricsOptions) options.getMetricsOptions();
        assertTrue(metricsOptions.isEnabled());
        assertTrue(metricsOptions.isJvmMetricsEnabled());
        assertTrue(metricsOptions.getPrometheusOptions().isEnabled());
        assertTrue(metricsOptions.getLabels().contains(Label.HTTP_ROUTE));
    }

    @Test
    void beforeStartingVertxWithNullContext() {
        assertThrows(NullPointerException.class, () -> new ApplicationLauncher(new String[0]).beforeStartingVertx(null));
    }

    @Test
    void launchWithHelpExitsWithoutStartingVertx() {
        assertEquals(0, new ApplicationLauncher(new String[]{"--help"}).launch());
    }

}
