package com.epam.aidial.core.server.config;

import com.epam.aidial.core.plugin.Plugin;
import com.epam.aidial.core.plugin.PluginProvider;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.Future;
import io.vertx.core.Vertx;

import java.util.List;

/**
 * A provider built against the API before {@code id()}: it overrides only the deprecated {@code type()}, so Core finds
 * it under that value through the default {@code id()}. Not registered on the test classpath; a test writes a jar that
 * registers it.
 */
public class LegacyTypePluginProvider implements PluginProvider {

    public static final String ID = "jar-legacy";

    @Override
    @SuppressWarnings("removal")
    public String type() {
        return ID;
    }

    @Override
    public List<Problem> validate(ObjectNode settings) {
        return List.of();
    }

    @Override
    public Plugin create(ObjectNode settings, Vertx vertx) {
        return (request, host) -> Future.failedFuture(new UnsupportedOperationException("jar-legacy serves no requests"));
    }
}
