package com.epam.aidial.core.server;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.vertx.core.VertxOptions;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that the Micrometer registries which {@link AiDial} registers into the static global
 * registry during {@code setupMetrics} are removed again on {@code stop}, so repeated
 * start/stop cycles in the same JVM (as the integration-test suite does) do not leak registries.
 */
class AiDialMetricsLifecycleTest {

    private final Set<MeterRegistry> baseline = Set.copyOf(Metrics.globalRegistry.getRegistries());

    @AfterEach
    void cleanup() {
        // Defensive: remove anything a failing test may have left behind so other suites are unaffected.
        for (MeterRegistry registry : Metrics.globalRegistry.getRegistries()) {
            if (!baseline.contains(registry)) {
                Metrics.removeRegistry(registry);
            }
        }
    }

    @Test
    void stopRemovesGlobalRegistriesAddedByStart() throws Exception {
        VertxOptions options = new VertxOptions(metricsSettings());

        AiDial aiDial = new AiDial();

        Method setupMetrics = AiDial.class.getDeclaredMethod("setupMetrics", VertxOptions.class);
        setupMetrics.setAccessible(true);
        setupMetrics.invoke(aiDial, options);

        Set<MeterRegistry> afterSetup = Set.copyOf(Metrics.globalRegistry.getRegistries());
        Set<MeterRegistry> added = new HashSet<>(afterSetup);
        added.removeAll(baseline);
        assertFalse(added.isEmpty(), "setupMetrics should have added at least one registry to the global registry");

        aiDial.stop();

        Set<MeterRegistry> afterStop = Set.copyOf(Metrics.globalRegistry.getRegistries());
        for (MeterRegistry registry : added) {
            assertFalse(afterStop.contains(registry),
                    "stop should remove the registry added by setupMetrics from the global registry");
        }
        assertTrue(afterStop.containsAll(baseline), "stop must not remove pre-existing registries");
    }

    private JsonObject metricsSettings() {
        return new JsonObject()
                .put("metricsOptions", new JsonObject()
                        .put("enabled", true)
                        .put("prometheusOptions", new JsonObject().put("enabled", true)));
    }
}
