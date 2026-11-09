package com.epam.aidial.core.server.config;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Condition;
import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.DeploymentInterface;
import com.epam.aidial.core.config.Interceptor;
import com.epam.aidial.core.config.InterfaceMode;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.Operator;
import com.epam.aidial.core.config.OrgUnitPermission;
import com.epam.aidial.core.config.OrgUnitRole;
import com.epam.aidial.core.config.Plugin;
import com.epam.aidial.core.config.Pricing;
import com.epam.aidial.core.config.PricingRate;
import com.epam.aidial.core.config.Role;
import com.epam.aidial.core.config.Route;
import com.epam.aidial.core.config.Router;
import com.epam.aidial.core.config.ToolSet;
import com.epam.aidial.core.config.Translator;
import com.epam.aidial.core.config.TranslatorRef;
import com.epam.aidial.core.config.Upstream;
import com.epam.aidial.core.config.UpstreamAuthConfig;
import com.epam.aidial.core.config.UpstreamAuthType;
import com.epam.aidial.core.config.UpstreamInterface;
import com.epam.aidial.core.plugin.PluginProvider;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static com.epam.aidial.core.config.InterfaceType.ANTHROPIC_MESSAGES;
import static com.epam.aidial.core.config.InterfaceType.OPENAI_CHAT_COMPLETIONS;
import static com.epam.aidial.core.config.InterfaceType.OPENAI_RESPONSES;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ConfigPostProcessor}'s slice 2S.9 split:
 * structural slash-keyed-name rejection and semantic skip|abort routing.
 */
public class ConfigPostProcessorTest {

    // test-echo, from the test classpath
    private static final PluginRegistry PLUGINS = PluginRegistry.load(Path.of("no-plugins-dir"));

    @Test
    void testOrgUnitRolesCoreCannotEvaluateFailTheLoad() {
        Config config = new Config();
        config.setOrgUnitRoles(Map.of("auditor", new OrgUnitRole("x", List.of(new OrgUnitPermission(List.of("read"), "rules/**")))));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> ConfigPostProcessor.process(config, null, PluginRegistry.EMPTY));
        assertTrue(error.getMessage().startsWith("orgUnitRoles.auditor: pathPattern"), error.getMessage());
    }

    @Test
    void testStructuralDropsSlashKeyedNamesAcrossAllTypes() {
        Config config = newMutableConfig();
        config.getModels().put("good-model", new Model());
        config.getModels().put("bad/model", new Model());
        config.getApplications().put("good-app", new Application());
        config.getApplications().put("bad/app", new Application());
        config.getInterceptors().put("good-interceptor", new Interceptor());
        config.getInterceptors().put("bad/interceptor", new Interceptor());
        config.getRoles().put("good-role", new Role());
        config.getRoles().put("bad/role", new Role());
        Route route = new Route();
        route.setOrder(1);
        config.getRoutes().put("good-route", route);
        Route badRoute = new Route();
        badRoute.setOrder(2);
        config.getRoutes().put("bad/route", badRoute);
        config.getToolsets().put("good-toolset", new ToolSet());
        config.getToolsets().put("bad/toolset", new ToolSet());

        ConfigPostProcessor.processStructural(config);

        assertEquals(1, config.getModels().size());
        assertNotNull(config.getModels().get("good-model"));
        assertEquals(1, config.getApplications().size());
        assertNotNull(config.getApplications().get("good-app"));
        assertEquals(1, config.getInterceptors().size());
        assertEquals(1, config.getRoles().size());
        assertEquals(1, config.getRoutes().size());
        assertEquals(1, config.getToolsets().size());
    }

    @Test
    void testSemanticAbortThrowsOnDuplicateDeploymentId() {
        Config config = newMutableConfig();
        config.getModels().put("shared", new Model());
        config.getApplications().put("shared", new Application());

        assertThrows(IllegalStateException.class,
                () -> ConfigPostProcessor.processSemantic(config, null, Map.of(), Map.of(), null));
    }

    @Test
    void testSemanticSkipRoutesDuplicateToCallback() {
        Config config = newMutableConfig();
        config.getModels().put("shared", new Model());
        config.getApplications().put("shared", new Application());

        AtomicReference<ResourceTypes> capturedType = new AtomicReference<>();
        AtomicReference<String> capturedKey = new AtomicReference<>();

        ConfigPostProcessor.processSemantic(config, null, Map.of(), Map.of(), (type, error) -> {
            capturedType.set(type);
            capturedKey.set(error.getMapKey());
        });

        // Models processed first; application 'shared' is the duplicate that gets skipped.
        assertEquals(ResourceTypes.APPLICATION, capturedType.get());
        assertEquals("shared", capturedKey.get());
        assertTrue(config.getModels().containsKey("shared"));
        assertFalse(config.getApplications().containsKey("shared"));
    }

    @Test
    void testSemanticDropsCanonicalIdKeyedToolSet() {
        // Toolsets in Config are now keyed by short name only; a canonical-id-shaped key
        // ("toolsets/platform/name") fails isValidToolSetKey and is dropped.
        Config config = newMutableConfig();
        config.getToolsets().put("toolsets/platform/my-toolset", new ToolSet());

        ConfigPostProcessor.processSemantic(config, null, Map.of(), Map.of(), null);

        assertTrue(config.getToolsets().isEmpty());
    }

    @Test
    void testSemanticDropsToolSetWithInvalidName() {
        Config config = newMutableConfig();
        config.getToolsets().put("bad name", new ToolSet());

        ConfigPostProcessor.processSemantic(config, null, Map.of(), Map.of(), null);

        assertTrue(config.getToolsets().isEmpty());
    }

    @Test
    void testSemanticAbortThrowsOnCacheRateWithoutTokenUnit() {
        Config config = newMutableConfig();
        Model model = new Model();
        Pricing pricing = new Pricing();
        pricing.setUnit("char_without_whitespace");
        pricing.setCacheRead(flatRate("0.01"));
        model.setPricing(pricing);
        config.getModels().put("model", model);

        assertThrows(InvalidEntityException.class,
                () -> ConfigPostProcessor.processSemantic(config, null, Map.of(), Map.of(), null));
    }

    @Test
    void testSemanticSkipDropsModelWithCacheRateWithoutTokenUnit() {
        Config config = newMutableConfig();
        Model model = new Model();
        Pricing pricing = new Pricing();
        pricing.setUnit("char_without_whitespace");
        pricing.setCacheWrite(flatRate("0.02"));
        model.setPricing(pricing);
        config.getModels().put("model", model);

        AtomicReference<ResourceTypes> capturedType = new AtomicReference<>();
        AtomicReference<String> capturedKey = new AtomicReference<>();

        ConfigPostProcessor.processSemantic(config, null, Map.of(), Map.of(), (type, error) -> {
            capturedType.set(type);
            capturedKey.set(error.getMapKey());
        });

        assertEquals(ResourceTypes.MODEL, capturedType.get());
        assertEquals("model", capturedKey.get());
        assertFalse(config.getModels().containsKey("model"));
    }

    @Test
    void testSemanticAllowsCacheRateWithTokenUnit() {
        Config config = newMutableConfig();
        Model model = new Model();
        Pricing pricing = new Pricing();
        pricing.setUnit("token");
        pricing.setCacheRead(flatRate("0.01"));
        pricing.setCacheWrite(flatRate("0.02"));
        model.setPricing(pricing);
        config.getModels().put("model", model);

        ConfigPostProcessor.processSemantic(config, null, Map.of(), Map.of(), null);

        assertTrue(config.getModels().containsKey("model"));
    }

    @Test
    void testSemanticAllowsFlatPromptCompletionRateWithoutTokenUnit() {
        Config config = newMutableConfig();
        Model model = new Model();
        Pricing pricing = new Pricing();
        pricing.setUnit("char_without_whitespace");
        pricing.setPrompt(flatRate("0.56"));
        pricing.setCompletion(flatRate("0.67"));
        model.setPricing(pricing);
        config.getModels().put("model", model);

        ConfigPostProcessor.processSemantic(config, null, Map.of(), Map.of(), null);

        assertTrue(config.getModels().containsKey("model"));
    }

    @Test
    void testSemanticAbortThrowsOnDecisionTreePromptWithoutTokenUnit() {
        Config config = newMutableConfig();
        Model model = new Model();
        Pricing pricing = new Pricing();
        pricing.setUnit("char_without_whitespace");
        pricing.setPrompt(decisionTree("promptTokens", 272000, flatRate("0.000004"), flatRate("0.000002")));
        model.setPricing(pricing);
        config.getModels().put("model", model);

        assertThrows(InvalidEntityException.class,
                () -> ConfigPostProcessor.processSemantic(config, null, Map.of(), Map.of(), null));
    }

    @Test
    void testSemanticAllowsDecisionTreePromptCompletionWithTokenUnit() {
        Config config = newMutableConfig();
        Model model = new Model();
        Pricing pricing = new Pricing();
        pricing.setUnit("token");
        pricing.setPrompt(decisionTree("promptTokens", 272000, flatRate("0.000004"), flatRate("0.000002")));
        pricing.setCompletion(decisionTree("promptTokens", 272000, flatRate("0.000018"), flatRate("0.000012")));
        pricing.setCacheRead(decisionTree("promptTokens", 272000, flatRate("0.0000004"), flatRate("0.0000002")));
        pricing.setCacheWrite(decisionTree("promptTokens", 272000, flatRate("0.000005"), flatRate("0.0000025")));
        model.setPricing(pricing);
        config.getModels().put("model", model);

        ConfigPostProcessor.processSemantic(config, null, Map.of(), Map.of(), null);

        assertTrue(config.getModels().containsKey("model"));
    }

    @Test
    void testSemanticAbortThrowsOnRouteBearerUpstreamWithoutKey() {
        Config config = newMutableConfig();
        Upstream upstream = new Upstream();
        upstream.setEndpoint("https://openrouter.ai/api/v1/chat/completions");
        upstream.setAuthType(UpstreamAuthConfig.fromString("BEARER"));
        Route route = new Route();
        route.setUpstreams(List.of(upstream));
        config.getRoutes().put("openrouter", route);

        assertThrows(InvalidEntityException.class,
                () -> ConfigPostProcessor.processSemantic(config, null, Map.of(), Map.of(), null));
    }

    @Test
    void testSemanticAllowsRouteBearerUpstreamWithKey() {
        Config config = newMutableConfig();
        Upstream upstream = new Upstream();
        upstream.setEndpoint("https://openrouter.ai/api/v1/chat/completions");
        upstream.setKey("openrouter-key");
        upstream.setAuthType(UpstreamAuthConfig.fromString("BEARER"));
        Route route = new Route();
        route.setUpstreams(List.of(upstream));
        config.getRoutes().put("openrouter", route);

        ConfigPostProcessor.processSemantic(config, null, Map.of(), Map.of(), null);

        assertTrue(config.getRoutes().containsKey("openrouter"));
    }

    @Test
    void testSemanticAbortThrowsOnRouteCustomHeaderUpstreamWithoutKey() {
        Config config = newMutableConfig();
        Upstream upstream = new Upstream();
        upstream.setEndpoint("https://example.com/api");
        upstream.setAuthType(new UpstreamAuthConfig(UpstreamAuthType.CUSTOM_HEADER, "X-Api-Token"));
        Route route = new Route();
        route.setUpstreams(List.of(upstream));
        config.getRoutes().put("custom", route);

        assertThrows(InvalidEntityException.class,
                () -> ConfigPostProcessor.processSemantic(config, null, Map.of(), Map.of(), null));
    }

    @Test
    void testSemanticAllowsRouteCustomHeaderUpstreamWithKey() {
        Config config = newMutableConfig();
        Upstream upstream = new Upstream();
        upstream.setEndpoint("https://example.com/api");
        upstream.setKey("secret");
        upstream.setAuthType(new UpstreamAuthConfig(UpstreamAuthType.CUSTOM_HEADER, "X-Api-Token"));
        Route route = new Route();
        route.setUpstreams(List.of(upstream));
        config.getRoutes().put("custom", route);

        ConfigPostProcessor.processSemantic(config, null, Map.of(), Map.of(), null);

        assertTrue(config.getRoutes().containsKey("custom"));
    }

    @Test
    void testSemanticAbortThrowsOnUpstreamInterfaceWithNothingToResolveTo() {
        Config config = newMutableConfig();
        Model model = new Model();
        Upstream upstream = new Upstream();
        upstream.setId("no-base-url");
        upstream.setInterfaces(Map.of("openaiChatCompletions", new UpstreamInterface()));
        model.setUpstreams(List.of(upstream));
        config.getModels().put("model", model);

        assertThrows(InvalidEntityException.class,
                () -> ConfigPostProcessor.processSemantic(config, null, Map.of(), Map.of(), null));
    }

    @Test
    void testSemanticAllowsUpstreamInterfaceCompletedByEndpointOrBaseUrl() {
        Config config = newMutableConfig();
        Model model = new Model();
        Upstream explicit = new Upstream();
        explicit.setId("explicit");
        explicit.setInterfaces(Map.of("anthropicMessages", new UpstreamInterface("https://provider/v1/messages")));
        Upstream derived = new Upstream();
        derived.setId("derived");
        derived.setBaseUrl("https://provider");
        derived.setInterfaces(Map.of("openaiResponses", new UpstreamInterface()));
        model.setUpstreams(List.of(explicit, derived));
        config.getModels().put("model", model);

        ConfigPostProcessor.processSemantic(config, null, Map.of(), Map.of(), null);

        assertTrue(config.getModels().containsKey("model"));
    }

    @Test
    void testSemanticAbortThrowsOnUpstreamInterfacesWithoutId() {
        // endpoint is what identifies an upstream when id is absent, and this shape has none
        Config config = newMutableConfig();
        Model model = new Model();
        Upstream upstream = new Upstream();
        upstream.setBaseUrl("https://provider");
        upstream.setInterfaces(Map.of("anthropicMessages", new UpstreamInterface()));
        model.setUpstreams(List.of(upstream));
        config.getModels().put("model", model);

        assertThrows(InvalidEntityException.class,
                () -> ConfigPostProcessor.processSemantic(config, null, Map.of(), Map.of(), null));
    }

    @Test
    void testSemanticAllowsLegacyUpstreamWithoutId() {
        // the id requirement is scoped to the interfaces shape; legacy upstreams are identified by endpoint
        Config config = newMutableConfig();
        Model model = new Model();
        Upstream upstream = new Upstream();
        upstream.setEndpoint("https://provider/v1/chat/completions");
        model.setUpstreams(List.of(upstream));
        config.getModels().put("model", model);

        ConfigPostProcessor.processSemantic(config, null, Map.of(), Map.of(), null);

        assertTrue(config.getModels().containsKey("model"));
    }

    @Test
    void testPreservesAllInterfacesIncludingUnknownKeys() {
        Config config = newMutableConfig();

        // Models, applications and interceptors all keep their declared interfaces verbatim — config
        // processing never strips keys (unknown/forward-compatible keys simply never resolve to a route).
        Model model = new Model();
        Map<String, DeploymentInterface> interfaces = new LinkedHashMap<>();
        interfaces.put("openaiChatCompletions", new DeploymentInterface("http://model"));
        interfaces.put("openaiResponses", new DeploymentInterface("http://model-responses"));
        interfaces.put("anthropicMessages", new DeploymentInterface("http://model-anthropic"));
        model.setInterfaces(interfaces);
        config.getModels().put("model", model);

        ConfigPostProcessor.process(config, null, PluginRegistry.EMPTY);

        assertEquals(3, config.getModels().get("model").getInterfaces().size());
    }

    @Test
    void testSemanticThrowsOnRegistryTranslatorWithoutIn() {
        // a registry entry is declared under no interface, so in is the only thing tying it to one
        Config config = newMutableConfig();
        config.setTranslators(Map.of("anthropicMessagesToOpenaiChatCompletions",
                new Translator(null, OPENAI_CHAT_COMPLETIONS, "http://translator/to-chat-completions")));

        assertThrows(InvalidEntityException.class,
                () -> ConfigPostProcessor.processSemantic(config, null, Map.of(), Map.of(), null));
    }

    @Test
    void testSemanticSkipDropsRegistryTranslatorWithoutIn() {
        Config config = newMutableConfig();
        config.setTranslators(new HashMap<>(Map.of("anthropicMessagesToOpenaiChatCompletions",
                new Translator(null, OPENAI_CHAT_COMPLETIONS, "http://translator/to-chat-completions"))));

        AtomicReference<ResourceTypes> capturedType = new AtomicReference<>();
        AtomicReference<String> capturedKey = new AtomicReference<>();

        ConfigPostProcessor.processSemantic(config, null, Map.of(), Map.of(), (type, error) -> {
            capturedType.set(type);
            capturedKey.set(error.getMapKey());
        });

        assertEquals(ResourceTypes.TRANSLATOR, capturedType.get());
        assertEquals("anthropicMessagesToOpenaiChatCompletions", capturedKey.get());
        assertTrue(config.getTranslators().isEmpty());
    }

    @Test
    void testSemanticKeepsNamedTranslatorAsReference() {
        Config config = newMutableConfig();
        config.setTranslators(Map.of("anthropicMessagesToOpenaiChatCompletions",
                new Translator(ANTHROPIC_MESSAGES, OPENAI_CHAT_COMPLETIONS, "http://translator/to-chat-completions")));
        Model model = new Model();
        model.setEndpoint("http://legacy/chat/completions");
        model.setInterfaces(Map.of("anthropicMessages",
                translated(TranslatorRef.named("anthropicMessagesToOpenaiChatCompletions"))));
        config.getModels().put("model", model);

        ConfigPostProcessor.processSemantic(config, null, Map.of(), Map.of(), null);

        // nothing is materialized onto the model: the name stays a name and resolves against the live
        // registry when asked, so an edit to the registry entry needs no walk over the models naming it
        TranslatorRef translator = config.getModels().get("model").getInterfaces().get("anthropicMessages").getTranslator();
        assertEquals("anthropicMessagesToOpenaiChatCompletions", translator.getName());
        assertNull(translator.getInline());
        assertEquals("http://translator/to-chat-completions", translator.resolve(config.getTranslators()).getBaseUrl());
    }

    @Test
    void testSemanticKeepsModelWithAnUnknownTranslator() {
        // an unresolved reference leaves that one interface unserved, the same as it does for an
        // application or interceptor; it does not make the model invalid (DeploymentEndpointUtilTest
        // covers the 503 side of it)
        Config config = newMutableConfig();
        Model model = new Model();
        model.setEndpoint("http://legacy/chat/completions");
        model.setInterfaces(Map.of("anthropicMessages", translated(TranslatorRef.named("missing"))));
        config.getModels().put("model", model);

        ConfigPostProcessor.processSemantic(config, null, Map.of(), Map.of(), null);

        assertTrue(config.getModels().containsKey("model"));
        assertNull(model.getInterfaces().get("anthropicMessages").getTranslator().resolve(config.getTranslators()));
    }

    @Test
    void testSemanticAbortThrowsOnBothTranslatorAndBaseUrl() {
        // an interface is served by one or the other, so a config declaring both is rejected, not resolved
        Config config = newMutableConfig();
        Model model = new Model();
        model.setEndpoint("http://legacy/chat/completions");
        DeploymentInterface anthropic = translated(TranslatorRef.inline(
                new Translator(null, OPENAI_CHAT_COMPLETIONS, "http://translator")));
        anthropic.setBaseUrl("http://anthropic");
        model.setInterfaces(Map.of("anthropicMessages", anthropic));
        config.getModels().put("model", model);

        assertThrows(InvalidEntityException.class,
                () -> ConfigPostProcessor.processSemantic(config, null, Map.of(), Map.of(), null));
    }

    @Test
    void testSemanticAbortThrowsOnTranslatorWithoutTranslatorMode() {
        Config config = newMutableConfig();
        Model model = new Model();
        model.setEndpoint("http://legacy/chat/completions");
        DeploymentInterface anthropic = new DeploymentInterface();
        anthropic.setTranslator(TranslatorRef.inline(new Translator(null, OPENAI_CHAT_COMPLETIONS, "http://translator")));
        model.setInterfaces(Map.of("anthropicMessages", anthropic));
        config.getModels().put("model", model);

        assertThrows(InvalidEntityException.class,
                () -> ConfigPostProcessor.processSemantic(config, null, Map.of(), Map.of(), null));
    }

    @Test
    void testSemanticAbortThrowsOnTranslatorModeWithoutTranslator() {
        Config config = newMutableConfig();
        Model model = new Model();
        model.setBaseUrl("http://model");
        model.setInterfaces(Map.of("anthropicMessages", translated(null)));
        config.getModels().put("model", model);

        assertThrows(InvalidEntityException.class,
                () -> ConfigPostProcessor.processSemantic(config, null, Map.of(), Map.of(), null));
    }

    @Test
    void testSemanticAbortThrowsWhenTheModelDoesNotServeTheTranslatorOutput() {
        // the translator calls Core back on openaiResponses, which this model serves nowhere
        Config config = newMutableConfig();
        Model model = new Model();
        model.setEndpoint("http://legacy/chat/completions");
        model.setInterfaces(Map.of("anthropicMessages", translated(TranslatorRef.inline(
                new Translator(ANTHROPIC_MESSAGES, OPENAI_RESPONSES, "http://translator/to-responses")))));
        config.getModels().put("model", model);

        assertThrows(InvalidEntityException.class,
                () -> ConfigPostProcessor.processSemantic(config, null, Map.of(), Map.of(), null));
    }

    @Test
    void testSemanticAbortThrowsWhenTheTranslatorOutputIsItselfTranslated() {
        // anthropicMessages converts to openaiResponses, which converts back to anthropicMessages: the
        // callback would arrive on a translated interface and be handed to a translator again
        Config config = newMutableConfig();
        Model model = new Model();
        model.setBaseUrl("http://model");
        model.setInterfaces(Map.of(
                "anthropicMessages", translated(TranslatorRef.inline(
                        new Translator(ANTHROPIC_MESSAGES, OPENAI_RESPONSES, "http://translator/to-responses"))),
                "openaiResponses", translated(TranslatorRef.inline(
                        new Translator(OPENAI_RESPONSES, ANTHROPIC_MESSAGES, "http://translator/to-messages")))));
        config.getModels().put("model", model);

        InvalidEntityException error = assertThrows(InvalidEntityException.class,
                () -> ConfigPostProcessor.processSemantic(config, null, Map.of(), Map.of(), null));

        // both ends of the cycle are reported, each as the translated interface its own output lands on
        assertEquals(2, error.getWarnings().size());
        assertTrue(error.getMessage().contains("handed to a translator again"), error.getMessage());
    }

    @Test
    void testSemanticAbortThrowsOnTranslatorConvertingAnInterfaceToItself() {
        // the translator's own output arrives back on the interface it came from, and Core hands it
        // straight back to the translator: a loop, not a 503
        Config config = newMutableConfig();
        Model model = new Model();
        model.setEndpoint("http://legacy/chat/completions");
        model.setInterfaces(Map.of("anthropicMessages", translated(TranslatorRef.inline(
                new Translator(ANTHROPIC_MESSAGES, ANTHROPIC_MESSAGES, "http://translator")))));
        config.getModels().put("model", model);

        InvalidEntityException error = assertThrows(InvalidEntityException.class,
                () -> ConfigPostProcessor.processSemantic(config, null, Map.of(), Map.of(), null));

        // and it is reported as what it is, not as an interface the model fails to serve
        assertEquals(1, error.getWarnings().size());
        assertTrue(error.getMessage().contains("cannot convert 'anthropicMessages' to itself"), error.getMessage());
    }

    @Test
    void testSemanticAbortThrowsOnInlineTranslatorConvertingToItsOwnInterface() {
        // an inline definition names no in, so the interface it sits under is what it converts from
        Config config = newMutableConfig();
        Model model = new Model();
        model.setEndpoint("http://legacy/chat/completions");
        model.setInterfaces(Map.of("anthropicMessages", translated(TranslatorRef.inline(
                new Translator(null, ANTHROPIC_MESSAGES, "http://translator")))));
        config.getModels().put("model", model);

        InvalidEntityException error = assertThrows(InvalidEntityException.class,
                () -> ConfigPostProcessor.processSemantic(config, null, Map.of(), Map.of(), null));

        assertTrue(error.getMessage().contains("cannot convert 'anthropicMessages' to itself"), error.getMessage());
    }

    @Test
    void testSemanticAbortThrowsOnInterfaceWithNoBaseUrlAnywhere() {
        Config config = newMutableConfig();
        Model model = new Model();
        model.setInterfaces(Map.of("openaiChatCompletions", new DeploymentInterface()));
        config.getModels().put("model", model);

        assertThrows(InvalidEntityException.class,
                () -> ConfigPostProcessor.processSemantic(config, null, Map.of(), Map.of(), null));
    }

    @Test
    void testSemanticAllowsInterfacesCompletedByTheModelBaseUrl() {
        Config config = newMutableConfig();
        Model model = new Model();
        model.setBaseUrl("http://model");
        model.setInterfaces(Map.of(
                "openaiChatCompletions", new DeploymentInterface(),
                "openaiResponses", new DeploymentInterface("http://model-responses")));
        config.getModels().put("model", model);

        ConfigPostProcessor.processSemantic(config, null, Map.of(), Map.of(), null);

        assertTrue(config.getModels().containsKey("model"));
    }

    private static Config configWithOverridePaths(String interfaceType, Map<String, String> overridePaths) {
        Config config = newMutableConfig();
        Model model = new Model();
        model.setBaseUrl("http://model");
        DeploymentInterface declared = new DeploymentInterface();
        declared.setOverridePaths(overridePaths);
        model.setInterfaces(Map.of(interfaceType, declared));
        config.getModels().put("model", model);
        return config;
    }

    @Test
    void testSemanticAbortThrowsOnInterfacePathMappingUnderTheWrongInterface() {
        Config config = configWithOverridePaths("openaiChatCompletions", Map.of("postAnthropicMessages", "/v1/messages"));

        assertThrows(InvalidEntityException.class,
                () -> ConfigPostProcessor.processSemantic(config, null, Map.of(), Map.of(), null));
    }

    @Test
    void testSemanticAllowsBracesThatAreNotTheSupportedTokens() {
        // only {id} and {overrideName} are substituted; any other brace is plain path text
        Config config = configWithOverridePaths("openaiChatCompletions",
                Map.of("postAzureOpenaiChatCompletions", "/v1/{Id}/{nope}/{id"));

        ConfigPostProcessor.processSemantic(config, null, Map.of(), Map.of(), null);

        assertTrue(config.getModels().containsKey("model"));
    }

    @Test
    void testSemanticAbortThrowsOnIdVariableWhereTheOperationHasNone() {
        Config config = configWithOverridePaths("anthropicMessages", Map.of("postAnthropicMessages", "/v1/{id}/messages"));

        assertThrows(InvalidEntityException.class,
                () -> ConfigPostProcessor.processSemantic(config, null, Map.of(), Map.of(), null));
    }

    @Test
    void testSemanticAbortThrowsOnOverridePathsForTranslatedInterfaces() {
        Config config = newMutableConfig();
        Model model = new Model();
        model.setBaseUrl("http://model");
        DeploymentInterface anthropic = translated(TranslatorRef.inline(
                new Translator(null, OPENAI_CHAT_COMPLETIONS, "http://translator")));
        anthropic.setOverridePaths(Map.of("postAnthropicMessages", "/v1/messages"));
        model.setInterfaces(Map.of(
                "openaiChatCompletions", new DeploymentInterface(),
                "anthropicMessages", anthropic));
        config.getModels().put("model", model);

        assertThrows(InvalidEntityException.class,
                () -> ConfigPostProcessor.processSemantic(config, null, Map.of(), Map.of(), null));
    }

    @Test
    void testSemanticAllowsValidOverridePathsAndToleratesUnknownKeys() {
        Config config = newMutableConfig();
        Model model = new Model();
        model.setBaseUrl("http://model");
        DeploymentInterface chat = new DeploymentInterface();
        chat.setOverridePaths(Map.of(
                "postAzureOpenaiChatCompletions", "/openai/deployments/{overrideName}/v1/{id}/re{ponses",
                "someFutureKey", "/whatever/{unvalidated}"));
        DeploymentInterface responses = new DeploymentInterface();
        responses.setOverridePaths(Map.of(
                "postOpenaiResponses", "/v1/responses",
                "getOpenaiResponsesById", "/v1/responses/{id}"));
        model.setInterfaces(Map.of(
                "openaiChatCompletions", chat,
                "openaiResponses", responses));
        config.getModels().put("model", model);

        ConfigPostProcessor.processSemantic(config, null, Map.of(), Map.of(), null);

        assertTrue(config.getModels().containsKey("model"));
    }

    private static Application applicationWithOverridePaths(String type, Map<String, String> overridePaths) {
        Application application = new Application();
        DeploymentInterface declared = new DeploymentInterface("http://app");
        declared.setOverridePaths(overridePaths);
        application.setInterfaces(Map.of(type, declared));
        return application;
    }

    private static Interceptor interceptorWithOverridePaths(String type, Map<String, String> overridePaths) {
        Interceptor interceptor = new Interceptor();
        DeploymentInterface declared = new DeploymentInterface("http://interceptor");
        declared.setOverridePaths(overridePaths);
        interceptor.setInterfaces(Map.of(type, declared));
        return interceptor;
    }

    @Test
    void testSemanticAbortThrowsOnApplicationOverridePathTemplate() {
        Config config = newMutableConfig();
        config.getApplications().put("app",
                applicationWithOverridePaths("openaiResponses", Map.of("postOpenaiResponses", "/v1/{id}/responses")));

        assertThrows(InvalidEntityException.class,
                () -> ConfigPostProcessor.processSemantic(config, null, Map.of(), Map.of(), null));
    }

    @Test
    void testSemanticSkipDropsApplicationWithBadOverridePathTemplate() {
        Config config = newMutableConfig();
        config.getApplications().put("app",
                applicationWithOverridePaths("openaiResponses", Map.of("postOpenaiResponses", "/v1/{id}/responses")));

        AtomicReference<ResourceTypes> capturedType = new AtomicReference<>();
        AtomicReference<String> capturedKey = new AtomicReference<>();

        ConfigPostProcessor.processSemantic(config, null, Map.of(), Map.of(), (type, error) -> {
            capturedType.set(type);
            capturedKey.set(error.getMapKey());
        });

        assertEquals(ResourceTypes.APPLICATION, capturedType.get());
        assertEquals("app", capturedKey.get());
        assertFalse(config.getApplications().containsKey("app"));
    }

    @Test
    void testSemanticAbortThrowsOnInterceptorOverridePathTemplate() {
        Config config = newMutableConfig();
        config.getInterceptors().put("interceptor",
                interceptorWithOverridePaths("openaiResponses", Map.of("postOpenaiResponses", "/v1/{id}/responses")));

        assertThrows(InvalidEntityException.class,
                () -> ConfigPostProcessor.processSemantic(config, null, Map.of(), Map.of(), null));
    }

    @Test
    void testSemanticAllowsValidApplicationAndInterceptorOverridePaths() {
        Config config = newMutableConfig();
        config.getApplications().put("app",
                applicationWithOverridePaths("openaiChatCompletions", Map.of("postAzureOpenaiChatCompletions", "/openai/deployments/{id}/v1/chat/completions")));
        config.getInterceptors().put("interceptor",
                interceptorWithOverridePaths("openaiChatCompletions", Map.of("postAzureOpenaiChatCompletions", "/v1/chat/completions")));

        ConfigPostProcessor.processSemantic(config, null, Map.of(), Map.of(), null);

        assertTrue(config.getApplications().containsKey("app"));
        assertTrue(config.getInterceptors().containsKey("interceptor"));
    }

    @Test
    void testValidateSingleApplicationSkipsBadOverridePaths() {
        Config config = newMutableConfig();
        config.getApplications().put("app",
                applicationWithOverridePaths("openaiResponses", Map.of("postOpenaiResponses", "/v1/{id}/responses")));

        AtomicReference<ResourceTypes> capturedType = new AtomicReference<>();
        ConfigPostProcessor.validateSingleApplication(config, "app", (type, error) -> capturedType.set(type));

        assertEquals(ResourceTypes.APPLICATION, capturedType.get());
        assertFalse(config.getApplications().containsKey("app"));
    }

    @Test
    void testValidateSingleInterceptorThrowsInAbortMode() {
        Config config = newMutableConfig();
        config.getInterceptors().put("interceptor",
                interceptorWithOverridePaths("openaiResponses", Map.of("postOpenaiResponses", "/v1/{id}/responses")));

        assertThrows(InvalidEntityException.class,
                () -> ConfigPostProcessor.validateSingleInterceptor(config, "interceptor", null));
    }

    @Test
    void testPluginOfAnUnknownTypeIsKeptInAbortMode() {
        Config config = configWithPlugin("p1", new Plugin("missing", null));

        ConfigPostProcessor.processPlugins(config, PLUGINS, null);

        assertTrue(config.getPlugins().containsKey("p1"));
    }

    @Test
    void testPluginOfAnUnknownTypeIsDroppedInSkipMode() {
        Config config = configWithPlugin("p1", new Plugin("missing", null));
        AtomicReference<InvalidEntityException> skipped = new AtomicReference<>();

        ConfigPostProcessor.processPlugins(config, PLUGINS, (type, error) -> skipped.set(error));

        assertFalse(config.getPlugins().containsKey("p1"));
        assertEquals("p1", skipped.get().getMapKey());
        assertEquals("type", skipped.get().getWarnings().get(0).getField());
    }

    @Test
    void testPluginWithRejectedSettingsFailsInAbortMode() {
        Config config = configWithPlugin("p1", new Plugin(TestEchoPluginProvider.TYPE, null));

        InvalidEntityException error = assertThrows(InvalidEntityException.class,
                () -> ConfigPostProcessor.processPlugins(config, PLUGINS, null));

        assertEquals("settings.greeting", error.getWarnings().get(0).getField());
    }

    @Test
    void testPluginWithRejectedSettingsIsDroppedInSkipMode() {
        Config config = configWithPlugin("p1", new Plugin(TestEchoPluginProvider.TYPE, null));
        AtomicReference<InvalidEntityException> skipped = new AtomicReference<>();

        ConfigPostProcessor.processPlugins(config, PLUGINS, (type, error) -> skipped.set(error));

        assertFalse(config.getPlugins().containsKey("p1"));
        assertEquals("settings.greeting", skipped.get().getWarnings().get(0).getField());
    }

    @Test
    void testValidPluginIsKeptInSkipMode() {
        ObjectNode settings = JsonNodeFactory.instance.objectNode().put("greeting", "hi");
        Config config = configWithPlugin("p1", new Plugin(TestEchoPluginProvider.TYPE, settings));

        ConfigPostProcessor.processPlugins(config, PLUGINS, (type, error) -> {
            throw new AssertionError("unexpected skip: " + error.getMessage());
        });

        assertTrue(config.getPlugins().containsKey("p1"));
    }

    @Test
    void testStructuralDropsSlashKeyedPlugin() {
        Config config = configWithPlugin("bad/plugin", new Plugin(TestEchoPluginProvider.TYPE, null));
        config.getPlugins().put("valid-plugin", new Plugin(TestEchoPluginProvider.TYPE, null));

        ConfigPostProcessor.processStructural(config);

        assertEquals(List.of("valid-plugin"), List.copyOf(config.getPlugins().keySet()));
    }

    @Test
    void testProviderFailureRejectsTheSettings() {
        PluginRegistry registry = registryOf(settings -> {
            throw new IllegalStateException("provider defect");
        });
        Config config = configWithPlugin("p1", new Plugin("custom", null));
        AtomicReference<InvalidEntityException> skipped = new AtomicReference<>();

        ConfigPostProcessor.processPlugins(config, registry, (type, error) -> skipped.set(error));

        assertFalse(config.getPlugins().containsKey("p1"));
        assertEquals("settings", skipped.get().getWarnings().get(0).getField());
    }

    @Test
    void testProblemWithoutPathIsReportedOnSettings() {
        PluginRegistry registry = registryOf(settings -> List.of(new PluginProvider.Problem("", "bad settings")));
        List<ValidationWarning> warnings = new ArrayList<>();

        ConfigPostProcessor.validatePlugin("p1", new Plugin("custom", null), registry, true, warnings);

        assertEquals("settings", warnings.get(0).getField());
    }

    private static PluginRegistry registryOf(Function<ObjectNode, List<PluginProvider.Problem>> validate) {
        return new PluginRegistry(Map.of("custom", new PluginProvider() {
            @Override
            public String type() {
                return "custom";
            }

            @Override
            public List<Problem> validate(ObjectNode settings) {
                return validate.apply(settings);
            }
        }));
    }

    private static Config configWithPlugin(String name, Plugin plugin) {
        Config config = newMutableConfig();
        config.setPlugins(new LinkedHashMap<>(Map.of(name, plugin)));
        return config;
    }

    @Test
    void testRouterWithInterceptorsRejected() {
        Config config = newMutableConfig();
        Router router = router("http://router", "openaiChatCompletions", new DeploymentInterface());
        router.setInterceptors(List.of("i1"));
        config.getRouters().put("r1", router);

        InvalidEntityException error = assertThrows(InvalidEntityException.class,
                () -> ConfigPostProcessor.processSemantic(config, null, Map.of(), Map.of(), null));
        assertTrue(error.getMessage().contains("A router declares no interceptors"), error.getMessage());
    }

    @Test
    void testRouterWithNullInterceptorsLoadsWithEmptyList() {
        Config config = newMutableConfig();
        Router router = router("http://router", "openaiChatCompletions", new DeploymentInterface());
        router.setInterceptors(null);
        config.getRouters().put("r1", router);

        ConfigPostProcessor.processSemantic(config, null, Map.of(), Map.of(), null);

        assertEquals(List.of(), config.getRouters().get("r1").getInterceptors());
    }

    @Test
    void testRouterWithLegacyEndpointRejected() {
        Config config = newMutableConfig();
        Router router = router(null, "openaiChatCompletions", new DeploymentInterface());
        router.setEndpoint("http://router/chat");
        config.getRouters().put("r1", router);

        InvalidEntityException error = assertThrows(InvalidEntityException.class,
                () -> ConfigPostProcessor.processSemantic(config, null, Map.of(), Map.of(), null));
        assertTrue(error.getMessage().contains("A router declares interfaces, not endpoint"), error.getMessage());
    }

    @Test
    void testRouterPassThroughEntryWithNoUrlAnywhereRejected() {
        Config config = newMutableConfig();
        config.getRouters().put("r1", router(null, "openaiChatCompletions", new DeploymentInterface()));

        InvalidEntityException error = assertThrows(InvalidEntityException.class,
                () -> ConfigPostProcessor.processSemantic(config, null, Map.of(), Map.of(), null));
        assertEquals("Router 'r1': Interface 'openaiChatCompletions' declares no base_url and the router declares no baseUrl", error.getMessage());
    }

    @Test
    void testRouterTranslatorWithoutModeRejected() {
        Config config = newMutableConfig();
        DeploymentInterface anthropic = new DeploymentInterface();
        anthropic.setTranslator(TranslatorRef.inline(new Translator(null, OPENAI_CHAT_COMPLETIONS, "http://t")));
        config.getRouters().put("r1", router("http://router", "anthropicMessages", anthropic));

        InvalidEntityException error = assertThrows(InvalidEntityException.class,
                () -> ConfigPostProcessor.processSemantic(config, null, Map.of(), Map.of(), null));
        assertTrue(error.getMessage().contains("Router 'r1': A translator requires mode 'translator'"), error.getMessage());
    }

    @Test
    void testRouterSkippedUnderSkipModeAndValidRouterNamed() {
        Config config = newMutableConfig();
        config.getRouters().put("bad", router(null, "openaiChatCompletions", new DeploymentInterface()));
        config.getRouters().put("good", router("http://router", "openaiChatCompletions", new DeploymentInterface()));
        AtomicReference<ResourceTypes> capturedType = new AtomicReference<>();

        ConfigPostProcessor.processSemantic(config, null, Map.of(), Map.of(), (type, error) -> capturedType.set(type));

        assertEquals(ResourceTypes.ROUTER, capturedType.get());
        assertFalse(config.getRouters().containsKey("bad"));
        assertEquals("good", config.getRouters().get("good").getName());
        assertTrue(ConfigPostProcessor.isDeploymentIdTakenByAnotherDeploymentType(config, ResourceTypes.MODEL, "good"));
    }

    @Test
    void testValidateSingleRouterDropsAnInvalidRouterUnderSkip() {
        Config config = newMutableConfig();
        config.getRouters().put("bad", router(null, "openaiChatCompletions", new DeploymentInterface()));
        AtomicReference<ResourceTypes> capturedType = new AtomicReference<>();

        ConfigPostProcessor.validateSingleRouter(config, "bad", (type, error) -> capturedType.set(type));

        assertEquals(ResourceTypes.ROUTER, capturedType.get());
        assertFalse(config.getRouters().containsKey("bad"));
    }

    @Test
    void testValidateSingleRouterThrowsInAbortMode() {
        Config config = newMutableConfig();
        Router router = router("http://router", "openaiChatCompletions", new DeploymentInterface());
        router.setInterceptors(List.of("i1"));
        config.getRouters().put("r1", router);

        InvalidEntityException error = assertThrows(InvalidEntityException.class,
                () -> ConfigPostProcessor.validateSingleRouter(config, "r1", null));
        assertTrue(error.getMessage().contains("Router 'r1': A router declares no interceptors"), error.getMessage());
    }

    @Test
    void testValidateRouterInvariantsNamesTheRouterAndEmptiesNullInterceptors() {
        Router router = router("http://router", "openaiChatCompletions", new DeploymentInterface());
        router.setInterceptors(null);
        List<ValidationWarning> warnings = new ArrayList<>();

        ConfigPostProcessor.validateRouterInvariants("r1", router, Map.of(), warnings);

        assertTrue(warnings.isEmpty(), warnings.toString());
        assertEquals("r1", router.getName());
        assertEquals(List.of(), router.getInterceptors());
    }

    @Test
    void testRouterPropertiesWithoutSchemaRejected() {
        Router router = router("http://router", "openaiChatCompletions", new DeploymentInterface());
        router.setApplicationProperties(Map.of("tier", "medium"));
        List<ValidationWarning> warnings = new ArrayList<>();

        ConfigPostProcessor.validateRouterInvariants("r1", router, Map.of(), warnings);

        assertEquals(1, warnings.size(), warnings.toString());
        assertEquals("applicationProperties", warnings.getFirst().getField());
        assertTrue(warnings.getFirst().getMessage().contains(
                "A router declares applicationTypeSchemaId and applicationProperties together"), warnings.toString());
    }

    @Test
    void testRouterSchemaWithoutPropertiesRejected() {
        Router router = router("http://router", "openaiChatCompletions", new DeploymentInterface());
        router.setApplicationTypeSchemaId(URI.create("https://dial.test/router-type"));
        List<ValidationWarning> warnings = new ArrayList<>();

        ConfigPostProcessor.validateRouterInvariants("r1", router, Map.of(), warnings);

        assertEquals(1, warnings.size(), warnings.toString());
        assertEquals("applicationTypeSchemaId", warnings.getFirst().getField());
    }

    @Test
    void testRouterWithSchemaAndEmptyPropertiesPasses() {
        // {} counts as set: the pairing rule is about presence, the schema decides what the properties must hold
        Router router = router("http://router", "openaiChatCompletions", new DeploymentInterface());
        router.setApplicationTypeSchemaId(URI.create("https://dial.test/router-type"));
        router.setApplicationProperties(Map.of());
        List<ValidationWarning> warnings = new ArrayList<>();

        ConfigPostProcessor.validateRouterInvariants("r1", router, Map.of(), warnings);

        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    @Test
    void testStructuralDropsSlashKeyedRouter() {
        Config config = newMutableConfig();
        config.getRouters().put("bad/router", new Router());

        ConfigPostProcessor.processStructural(config);

        assertTrue(config.getRouters().isEmpty());
    }

    @Test
    void testDecisionSettingsNeedDecisionMode() {
        Config config = newMutableConfig();
        Router router = router("http://router", "openaiChatCompletions", new DeploymentInterface());
        router.setDecision(decision(null, 2));
        config.getRouters().put("r1", router);

        InvalidEntityException error = assertThrows(InvalidEntityException.class,
                () -> ConfigPostProcessor.processSemantic(config, null, Map.of(), Map.of(), null));
        assertTrue(error.getMessage().contains("decision requires mode 'decision'"), error.getMessage());
    }

    @Test
    void testDecisionRouterNeedsUrlForEachInterface() {
        Config config = newMutableConfig();
        config.getRouters().put("r1", decisionRouter(null, null));

        InvalidEntityException error = assertThrows(InvalidEntityException.class,
                () -> ConfigPostProcessor.processSemantic(config, null, Map.of(), Map.of(), null));
        assertTrue(error.getMessage().contains("declares no base_url and the router declares no baseUrl"), error.getMessage());
    }

    @Test
    void testDecisionRouterWithTranslatorRejected() {
        Config config = newMutableConfig();
        Router router = decisionRouter("http://router", null);
        router.setInterfaces(Map.of("anthropicMessages", translated(TranslatorRef.inline(new Translator(null, OPENAI_CHAT_COMPLETIONS, "http://t")))));
        config.getRouters().put("r1", router);

        InvalidEntityException error = assertThrows(InvalidEntityException.class,
                () -> ConfigPostProcessor.processSemantic(config, null, Map.of(), Map.of(), null));
        assertTrue(error.getMessage().contains("A router in mode 'decision' serves no interface through a translator"), error.getMessage());
    }

    @Test
    void testDecisionRouterWithEmbeddedInterfaceRejected() {
        Config config = newMutableConfig();
        Router router = decisionRouter("http://router", null);
        router.setInterfaces(Map.of("anthropicMessages", embedded("p1")));
        config.getRouters().put("r1", router);

        InvalidEntityException error = assertThrows(InvalidEntityException.class,
                () -> ConfigPostProcessor.processSemantic(config, null, Map.of(), Map.of(), null));
        assertTrue(error.getMessage().contains("A router in mode 'decision' serves no interface through a plugin"), error.getMessage());
    }

    @Test
    void testMaxDecisionsOutOfRangeRejected() {
        for (int value : new int[] {0, 11}) {
            Config config = newMutableConfig();
            config.getRouters().put("r1", decisionRouter("http://router", decision(null, value)));

            InvalidEntityException error = assertThrows(InvalidEntityException.class,
                    () -> ConfigPostProcessor.processSemantic(config, null, Map.of(), Map.of(), null));
            assertTrue(error.getMessage().contains("decision.maxDecisions must be between 1 and 10"), error.getMessage());
        }
    }

    @Test
    void testMaxDecisionsAtTheCapAccepted() {
        Config config = newMutableConfig();
        config.getRouters().put("r1", decisionRouter("http://router", decision(null, 10)));

        ConfigPostProcessor.processSemantic(config, null, Map.of(), Map.of(), null);

        assertTrue(config.getRouters().containsKey("r1"));
    }

    @Test
    void testDecisionTimeoutMustBePositive() {
        Config config = newMutableConfig();
        config.getRouters().put("r1", decisionRouter("http://router", decision(0L, null)));

        InvalidEntityException error = assertThrows(InvalidEntityException.class,
                () -> ConfigPostProcessor.processSemantic(config, null, Map.of(), Map.of(), null));
        assertTrue(error.getMessage().contains("decision.timeoutMs must be positive"), error.getMessage());
    }

    @Test
    void testValidDecisionRouterAccepted() {
        Config config = newMutableConfig();
        Router router = decisionRouter(null, decision(200L, null));
        // an interface's base_url serves a router that declares no baseUrl
        router.setInterfaces(Map.of("openaiChatCompletions", new DeploymentInterface("http://router")));
        config.getRouters().put("r1", router);

        ConfigPostProcessor.processSemantic(config, null, Map.of(), Map.of(), null);

        assertTrue(config.getRouters().containsKey("r1"));
    }

    @Test
    void testModelInterfaceMessageUnchanged() {
        Config config = newMutableConfig();
        Model model = new Model();
        model.setInterfaces(Map.of("openaiChatCompletions", new DeploymentInterface()));
        config.getModels().put("m", model);

        InvalidEntityException error = assertThrows(InvalidEntityException.class,
                () -> ConfigPostProcessor.processSemantic(config, null, Map.of(), Map.of(), null));
        assertEquals("Model 'm': Interface 'openaiChatCompletions' declares no base_url and the model declares no baseUrl", error.getMessage());
    }

    private static DeploymentInterface translated(TranslatorRef translator) {
        DeploymentInterface declared = new DeploymentInterface();
        declared.setMode(InterfaceMode.TRANSLATOR);
        declared.setTranslator(translator);
        return declared;
    }

    private static Router decisionRouter(String baseUrl, Router.Decision decision) {
        Router router = router(baseUrl, "openaiChatCompletions", new DeploymentInterface());
        router.setMode(Router.Mode.DECISION);
        router.setDecision(decision);
        return router;
    }

    private static Router.Decision decision(Long timeoutMs, Integer maxDecisions) {
        Router.Decision decision = new Router.Decision();
        decision.setTimeoutMs(timeoutMs);
        decision.setMaxDecisions(maxDecisions);
        return decision;
    }

    private static Config newMutableConfig() {
        Config config = new Config();
        config.setModels(new HashMap<>());
        config.setApplications(new HashMap<>());
        config.setInterceptors(new HashMap<>());
        config.setToolsets(new LinkedHashMap<>());
        config.setRouters(new LinkedHashMap<>());
        return config;
    }

    private static Router router(String baseUrl, String interfaceType, DeploymentInterface declared) {
        Router router = new Router();
        router.setBaseUrl(baseUrl);
        router.setInterfaces(Map.of(interfaceType, declared));
        return router;
    }

    private static PricingRate flatRate(String rate) {
        PricingRate pricingRate = new PricingRate();
        pricingRate.setRate(rate);
        return pricingRate;
    }

    private static PricingRate decisionTree(String field, Object value, PricingRate ifTrue, PricingRate ifFalse) {
        Condition condition = new Condition();
        condition.setField(field);
        condition.setOperator(Operator.GT);
        condition.setValue(value);

        PricingRate pricingRate = new PricingRate();
        pricingRate.setTest(condition);
        pricingRate.setIfTrue(ifTrue);
        pricingRate.setIfFalse(ifFalse);
        return pricingRate;
    }

    @Test
    void testProcessOrchestratesStructuralThenSemanticInAbortMode() {
        Config config = new Config();
        // Use a LinkedHashMap to keep deterministic iteration: bad keys must be removed structurally
        // before the semantic pass complains about anything else.
        Map<String, Model> models = new LinkedHashMap<>();
        models.put("bad/key", new Model());
        models.put("good", new Model());
        config.setModels(models);

        ConfigPostProcessor.process(config, null, PluginRegistry.EMPTY);

        assertEquals(List.of("good"), List.copyOf(config.getModels().keySet()));
    }

    // --- validateModelInvariants: the shared set every pre-write surface must call ---------------
    // These guard the anti-drift contract. processModels/validateSingleModel and the three pre-write
    // sites all route through validateModelInvariants, so a write can never be admitted that the
    // next rebuild would reject.

    /**
     * The abort-mode rebuild and {@code validateModelInvariants} must report identical warnings,
     * so a validator added only to {@link ConfigPostProcessor#processModels} fails here — provided
     * {@code maximallyInvalidModel} trips it; extend that fixture when adding a rule.
     */
    @Test
    void testValidateModelInvariantsReportsExactlyWhatTheRebuildReports() {
        Config config = newMutableConfig();
        config.getModels().put("model", maximallyInvalidModel());

        InvalidEntityException rebuild = assertThrows(InvalidEntityException.class,
                () -> ConfigPostProcessor.processSemantic(config, null, Map.of(), Map.of(), null));

        Model standalone = maximallyInvalidModel();
        standalone.setName("model");
        List<ValidationWarning> preWrite = new ArrayList<>();
        ConfigPostProcessor.validateModelInvariants(standalone, config.getTranslators(), preWrite);

        assertEquals(rebuild.getWarnings(), preWrite,
                () -> "rebuild and pre-write validation diverged — a validator was added to "
                        + "processModels but not to validateModelInvariants (or vice versa).\n"
                        + "rebuild:  " + rebuild.getWarnings() + "\npre-write: " + preWrite);

        // Non-empty, so an accidentally-valid fixture can't make the comparison vacuous.
        List<String> fields = preWrite.stream().map(ValidationWarning::getField).toList();
        assertTrue(fields.contains("pricing"), () -> "expected a pricing warning: " + fields);
        assertTrue(fields.contains("upstreams[0].id"), () -> "expected an upstream id warning: " + fields);
        assertTrue(fields.contains("interfaces.openaiChatCompletions"),
                () -> "expected a deployment-interface warning: " + fields);
    }

    /**
     * A model violating every rule in the shared set at once. One rule per family, so the
     * differential comparison above has something to compare on each.
     */
    private static Model maximallyInvalidModel() {
        Model model = new Model();

        Pricing pricing = new Pricing();
        pricing.setUnit("char_without_whitespace");
        pricing.setCacheRead(flatRate("0.01"));
        model.setPricing(pricing);

        Upstream upstream = new Upstream();
        upstream.setInterfaces(Map.of("openaiChatCompletions", new UpstreamInterface()));
        model.setUpstreams(List.of(upstream));

        model.setInterfaces(Map.of("openaiChatCompletions", new DeploymentInterface()));
        return model;
    }

    @Test
    void testValidateModelInvariantsWithEmptyTranslatorMapToleratesUnresolvedName() {
        // A name with no entry leaves the interface unserved, not the model invalid — so a partial
        // translator map can only yield fewer warnings, never spurious ones. This is what makes it
        // safe to validate against the live snapshot / batch scratch rather than the merged config.
        Model model = new Model();
        model.setName("model");
        model.setInterfaces(Map.of("anthropicMessages", translated(TranslatorRef.named("not-registered-yet"))));

        List<ValidationWarning> warnings = new ArrayList<>();
        ConfigPostProcessor.validateModelInvariants(model, Map.of(), warnings);

        assertTrue(warnings.isEmpty(), () -> "unresolved translator name must not warn: " + warnings);
    }

    @Test
    void testEmbeddedInterfaceIsRead() throws Exception {
        Model model = ProxyUtil.MAPPER.readValue("""
                {"interfaces": {"openaiChatCompletions": {"mode": "embedded", "plugin": "p1"}}}
                """, Model.class);

        DeploymentInterface declared = model.getInterfaces().get("openaiChatCompletions");
        assertEquals(InterfaceMode.EMBEDDED, declared.getMode());
        assertEquals("p1", declared.getPlugin());
    }

    @Test
    void testEmbeddedModelNeedsNoBaseUrl() {
        assertEquals(List.of(), modelWarnings(embedded("p1")));
    }

    @Test
    void testEmbeddedModeRequiresPlugin() {
        assertWarning(modelWarnings(embedded(null)), "Mode 'embedded' requires a plugin");
        assertWarning(applicationWarnings(embedded(null)), "Mode 'embedded' requires a plugin");
    }

    @Test
    void testPluginAndBaseUrlRejected() {
        DeploymentInterface declared = embedded("p1");
        declared.setBaseUrl("http://app");

        assertWarning(modelWarnings(declared), "An interface is served either by a plugin or by a base_url, not by both");
        assertWarning(applicationWarnings(declared), "An interface is served either by a plugin or by a base_url, not by both");
    }

    @Test
    void testPluginRequiresEmbeddedMode() {
        DeploymentInterface declared = new DeploymentInterface("http://app");
        declared.setPlugin("p1");

        assertWarning(modelWarnings(declared), "A plugin requires mode 'embedded'");
        assertWarning(applicationWarnings(declared), "A plugin requires mode 'embedded'");
        Interceptor interceptor = new Interceptor();
        interceptor.setName("interceptor");
        interceptor.setInterfaces(Map.of("openaiChatCompletions", declared));
        List<ValidationWarning> warnings = new ArrayList<>();
        ConfigPostProcessor.validateInterfaces(interceptor, warnings);
        assertWarning(warnings, "A plugin requires mode 'embedded'");
    }

    @Test
    void testEmbeddedInterceptorRejected() {
        Interceptor interceptor = new Interceptor();
        interceptor.setName("interceptor");
        interceptor.setInterfaces(Map.of("openaiChatCompletions", embedded("p1")));

        List<ValidationWarning> warnings = new ArrayList<>();
        ConfigPostProcessor.validateInterfaces(interceptor, warnings);

        assertWarning(warnings, "Mode 'embedded' is not supported for an interceptor");
    }

    @Test
    void testOverridePathsUnderEmbeddedEntryRejected() {
        DeploymentInterface declared = embedded("p1");
        declared.setOverridePaths(Map.of("postAzureOpenaiChatCompletions", "/x"));

        assertWarning(applicationWarnings(declared), "An embedded interface is served by its plugin: overridePaths has no effect");
    }

    @Test
    void testUnresolvedPluginNameAccepted() {
        // no plugins map is consulted: a name with no entry leaves the interface unserved, not the deployment invalid
        assertEquals(List.of(), applicationWarnings(embedded("not-registered-yet")));
    }

    @Test
    void testFileConfigWithPluginAndBaseUrlRejected() {
        Config config = newMutableConfig();
        Application application = new Application();
        DeploymentInterface declared = embedded("p1");
        declared.setBaseUrl("http://app");
        application.setInterfaces(Map.of("openaiChatCompletions", declared));
        config.getApplications().put("app", application);

        InvalidEntityException error = assertThrows(InvalidEntityException.class,
                () -> ConfigPostProcessor.process(config, null, PluginRegistry.EMPTY));

        assertWarning(error.getWarnings(), "An interface is served either by a plugin or by a base_url, not by both");
    }

    private static List<ValidationWarning> modelWarnings(DeploymentInterface declared) {
        Model model = new Model();
        model.setName("model");
        model.setInterfaces(Map.of("openaiChatCompletions", declared));
        List<ValidationWarning> warnings = new ArrayList<>();
        ConfigPostProcessor.validateModelInvariants(model, Map.of(), warnings);
        return warnings;
    }

    private static List<ValidationWarning> applicationWarnings(DeploymentInterface declared) {
        Application application = new Application();
        application.setName("app");
        application.setInterfaces(Map.of("openaiChatCompletions", declared));
        List<ValidationWarning> warnings = new ArrayList<>();
        ConfigPostProcessor.validateInterfaces(application, warnings);
        return warnings;
    }

    private static void assertWarning(List<ValidationWarning> warnings, String message) {
        assertTrue(warnings.stream().anyMatch(warning -> warning.getMessage().contains(message)), () -> "no '" + message + "' in " + warnings);
    }

    private static DeploymentInterface embedded(String plugin) {
        DeploymentInterface declared = new DeploymentInterface();
        declared.setMode(InterfaceMode.EMBEDDED);
        declared.setPlugin(plugin);
        return declared;
    }

    @Test
    void testValidateModelInvariantsDoesNotDuplicateOverridePathWarnings() {
        // validateDeploymentInterfaces already runs the per-entry override-path check, so callers
        // must not also call validateInterfaces(Deployment, ...) — this pins that contract.
        Model model = new Model();
        model.setName("model");
        model.setBaseUrl("http://model");
        DeploymentInterface declared = new DeploymentInterface();
        declared.setOverridePaths(Map.of("postAnthropicMessages", "/v1/messages"));
        model.setInterfaces(Map.of("openaiChatCompletions", declared));

        List<ValidationWarning> warnings = new ArrayList<>();
        ConfigPostProcessor.validateModelInvariants(model, Map.of(), warnings);

        assertEquals(1, warnings.size(), () -> "override-path warning reported more than once: " + warnings);
    }

    @Test
    void testValidateCrossReferencesReportsOnlyItsOwnWarnings() {
        Config config = newMutableConfig();
        config.getInterceptors().put("known", new Interceptor());
        Model model = new Model();
        model.setName("model");
        model.setInterceptors(List.of("known"));

        List<ValidationWarning> warnings = new ArrayList<>();
        warnings.add(new ValidationWarning("pricing", "pre-existing hard failure"));

        assertTrue(ConfigPostProcessor.validateCrossReferences(model, config, warnings),
                () -> "resolved refs must report true regardless of earlier warnings: " + warnings);
        assertEquals(1, warnings.size());

        model.setInterceptors(List.of("known", "missing"));
        assertFalse(ConfigPostProcessor.validateCrossReferences(model, config, warnings));
        assertEquals(2, warnings.size());
        assertEquals("interceptors[1]", warnings.get(1).getField());
    }
}
