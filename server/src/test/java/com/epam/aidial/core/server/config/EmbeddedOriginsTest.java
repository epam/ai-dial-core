package com.epam.aidial.core.server.config;

import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.Plugin;
import com.epam.aidial.core.plugin.OriginPlugin;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmbeddedOriginsTest {

    private static final PluginRegistry REGISTRY = PluginRegistry.load(Path.of("no-plugins-dir"));

    private final EmbeddedOrigins origins = new EmbeddedOrigins(REGISTRY);

    @Test
    void testSamePluginForTheSameAndForAnEqualDeclaration() {
        Config config = configWith(new Plugin(TestOriginPluginProvider.TYPE, greeting("hello")));
        OriginPlugin first = origins.resolve(config, "p1");

        assertSame(first, origins.resolve(config, "p1"));
        // a rebuild makes new objects for the same declaration
        assertSame(first, origins.resolve(configWith(new Plugin(TestOriginPluginProvider.TYPE, greeting("hello"))), "p1"));
    }

    @Test
    void testNewPluginAfterSettingsChange() {
        OriginPlugin hello = origins.resolve(configWith(new Plugin(TestOriginPluginProvider.TYPE, greeting("hello"))), "p1");

        assertNotSame(hello, origins.resolve(configWith(new Plugin(TestOriginPluginProvider.TYPE, greeting("hi"))), "p1"));
    }

    @Test
    void testMissingEntry() {
        assertFailure(new Config(), "No plugins entry is named 'p1'");
    }

    @Test
    void testProviderNotLoaded() {
        assertFailure(configWith(new Plugin("missing", null)), "No plugin provider of type 'missing' is loaded");
    }

    @Test
    void testProviderThatServesNoRequests() {
        assertFailure(configWith(new Plugin(TestEchoPluginProvider.TYPE, greeting("hi"))), "serves no requests");
    }

    @Test
    void testPluginThatCannotBeCreated() {
        ObjectNode settings = JsonNodeFactory.instance.objectNode().put("failCreate", true);

        assertFailure(configWith(new Plugin(TestOriginPluginProvider.TYPE, settings)), "Plugin 'p1' cannot be created");
    }

    private void assertFailure(Config config, String message) {
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> origins.resolve(config, "p1"));
        assertTrue(error.getMessage().contains(message), error.getMessage());
    }

    private static Config configWith(Plugin plugin) {
        Config config = new Config();
        config.setPlugins(Map.of("p1", plugin));
        return config;
    }

    private static ObjectNode greeting(String text) {
        return JsonNodeFactory.instance.objectNode().put("greeting", text);
    }
}
