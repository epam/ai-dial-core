package com.epam.aidial.core.server.config;

import com.epam.aidial.core.config.AliasPresentation;
import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.fasterxml.jackson.databind.json.JsonMapper;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Load-time checks of the {@code aliases} config section.
 */
class ConfigPostProcessorAliasTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static final String DEPLOYMENTS = """
            "models": {"gpt-4-internal": {"endpoint": "http://localhost/chat"}},
            "applications": {
              "plain-app": {"endpoint": "http://localhost/app"},
              "mcp-app": {"mcp": {"endpoint": "http://localhost/mcp"}}
            },
            "toolsets": {"search": {"endpoint": "http://localhost/search"}},
            """;

    @Test
    void aliasIsLoaded() {
        Config config = load(alias("assistant", "model", "models/gpt-4-internal"));
        ConfigPostProcessor.process(config, null, PluginRegistry.EMPTY);

        assertEquals("assistant", config.getAliases().get("assistant").getName());
        assertEquals(config.getAliases().get("assistant"), config.selectDeployment("assistant"));
    }

    @Test
    void everyPresentationFitsItsTargets() {
        Config config = load(alias("a1", "model", "applications/plain-app") + ","
                + alias("a2", "application", "models/gpt-4-internal") + ","
                + alias("a3", "toolset", "toolsets/search") + ","
                + alias("a4", "toolset", "applications/mcp-app"));
        ConfigPostProcessor.process(config, null, PluginRegistry.EMPTY);

        assertEquals(4, config.getAliases().size());
    }

    @Test
    void badTargetIsRefused() {
        assertRefused(alias("assistant", "model", "gpt-4-internal"), "target.path must be models/<name>");
        assertRefused(alias("assistant", "model", "prompts/bucket/prompt"), "target.path must be an application or a toolset");
        assertRefused("\"assistant\": {\"presentAs\": \"model\"}", "target.path is required");
        assertRefused(alias("assistant", "model", "x").replace("{\"path\": \"x\"}", "{}"), "target.path is required");
    }

    @Test
    void presentAsDefaultsToTheTargetType() {
        Config config = load("""
                "m": {"target": {"path": "models/gpt-4-internal"}},
                "a": {"target": {"path": "applications/plain-app"}},
                "t": {"target": {"path": "toolsets/search"}},
                "r": {"target": {"path": "applications/bucket/app"}}""");
        ConfigPostProcessor.process(config, null, PluginRegistry.EMPTY);

        assertEquals(AliasPresentation.MODEL, config.getAliases().get("m").getEffectivePresentation());
        assertEquals(AliasPresentation.APPLICATION, config.getAliases().get("a").getEffectivePresentation());
        assertEquals(AliasPresentation.TOOLSET, config.getAliases().get("t").getEffectivePresentation());
        assertEquals(AliasPresentation.APPLICATION, config.getAliases().get("r").getEffectivePresentation());
    }

    @Test
    void resourceTargetLoadsWithoutBeingRead() {
        Config config = load(alias("tools", "toolset", "toolsets/bucket/search"));
        ConfigPostProcessor.process(config, null, PluginRegistry.EMPTY);

        assertTrue(config.getAliases().containsKey("tools"));
    }

    @Test
    void nameClashFailsTheLoad() {
        Config config = load(alias("gpt-4-internal", "model", "applications/plain-app"));

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> ConfigPostProcessor.process(config, null, PluginRegistry.EMPTY));
        assertEquals("Deployment uniqueness is violated: duplicate is found gpt-4-internal", error.getMessage());
    }

    @Test
    void aliasToAnAliasIsRefused() {
        assertRefused(alias("assistant", "model", "aliases/other"), "An alias cannot point to another alias");
    }

    @Test
    void toolsetShownAsModelIsRefused() {
        assertRefused(alias("assistant", "model", "toolsets/search"), "An alias shown as model cannot point to toolsets/search");
        assertRefused(alias("tools", "toolset", "applications/plain-app"),
                "An alias shown as toolset cannot point to applications/plain-app");
    }

    @Test
    void missingTargetLoads() {
        Config config = load(alias("broken", "model", "models/missing"));
        ConfigPostProcessor.process(config, null, PluginRegistry.EMPTY);

        assertTrue(config.getAliases().containsKey("broken"));
    }

    @Test
    void invalidAliasIsSkippedInTheMergedRebuild() {
        Config config = load(alias("good", "model", "models/gpt-4-internal") + ","
                + alias("bad", "model", "aliases/good"));
        List<InvalidEntityException> skipped = new ArrayList<>();

        ConfigPostProcessor.processStructural(config);
        ConfigPostProcessor.processSemantic(config, null, config.getKeys(), java.util.Map.of(),
                (type, error) -> skipped.add(error));

        assertEquals(List.of("good"), List.copyOf(config.getAliases().keySet()));
        assertEquals(1, skipped.size());
        assertEquals(ResourceTypes.ALIAS, skipped.getFirst().getType());
        assertEquals("bad", skipped.getFirst().getMapKey());
    }

    private static void assertRefused(String aliases, String message) {
        Config config = load(aliases);
        InvalidEntityException error = assertThrows(InvalidEntityException.class, () -> ConfigPostProcessor.process(config, null, PluginRegistry.EMPTY));
        assertEquals(ResourceTypes.ALIAS, error.getType());
        assertTrue(error.getWarnings().stream().anyMatch(warning -> warning.getMessage().startsWith(message)),
                error.getWarnings().toString());
    }

    private static String alias(String name, String presentAs, String path) {
        return """
                "%s": {
                  "presentAs": "%s",
                  "target": {"path": "%s"}
                }""".formatted(name, presentAs, path);
    }

    @SneakyThrows
    private static Config load(String aliases) {
        return MAPPER.readValue("{" + DEPLOYMENTS + "\"aliases\": {" + aliases + "}}", Config.class);
    }
}
