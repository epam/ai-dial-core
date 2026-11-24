package com.epam.aidial.core.server.config;

import com.epam.aidial.core.plugin.Plugin;
import com.epam.aidial.core.plugin.PluginProvider;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.Future;
import io.vertx.core.Vertx;

import java.io.UncheckedIOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Plugin provider of id {@code test-schema}, registered on the server test classpath. Its settings schema requires a
 * string {@code greeting} and allows no other property; its own check accepts everything and records the settings it
 * was called with.
 */
public class TestSchemaPluginProvider implements PluginProvider {

    public static final String ID = "test-schema";

    /**
     * The settings of each call of {@link #validate}, for the tests that check whether Core called it.
     */
    public static final List<ObjectNode> VALIDATED = new CopyOnWriteArrayList<>();

    private static final String SCHEMA = """
            {"type": "object", "required": ["greeting"], "properties": {"greeting": {"type": "string"}},
             "additionalProperties": false}
            """;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public ObjectNode settingsSchema() {
        try {
            return (ObjectNode) ProxyUtil.MAPPER.readTree(SCHEMA);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public List<Problem> validate(ObjectNode settings) {
        VALIDATED.add(settings);
        return List.of();
    }

    @Override
    public Plugin create(ObjectNode settings, Vertx vertx) {
        return (request, host) -> Future.failedFuture(new UnsupportedOperationException("test-schema serves no requests"));
    }
}
