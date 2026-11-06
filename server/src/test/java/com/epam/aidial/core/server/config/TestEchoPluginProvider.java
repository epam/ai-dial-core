package com.epam.aidial.core.server.config;

import com.epam.aidial.core.plugin.PluginProvider;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * Plugin provider of type {@code test-echo}, registered on the server test classpath, so every test Core has it:
 * settings are valid when they carry a string {@code greeting}.
 */
public class TestEchoPluginProvider implements PluginProvider {

    public static final String TYPE = "test-echo";

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public List<Problem> validate(ObjectNode settings) {
        return settings.path("greeting").isTextual()
                ? List.of()
                : List.of(new Problem("greeting", "greeting must be a string"));
    }
}
