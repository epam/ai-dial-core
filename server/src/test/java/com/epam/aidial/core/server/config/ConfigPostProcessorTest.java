package com.epam.aidial.core.server.config;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.DeploymentInterface;
import com.epam.aidial.core.config.Interceptor;
import com.epam.aidial.core.config.InterfaceMode;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.Pricing;
import com.epam.aidial.core.config.PricingRate;
import com.epam.aidial.core.config.Role;
import com.epam.aidial.core.config.Route;
import com.epam.aidial.core.config.ToolSet;
import com.epam.aidial.core.config.Translator;
import com.epam.aidial.core.config.TranslatorRef;
import com.epam.aidial.core.config.Upstream;
import com.epam.aidial.core.config.UpstreamInterface;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

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

        ConfigPostProcessor.process(config, null);

        assertEquals(3, config.getModels().get("model").getInterfaces().size());
    }

    @Test
    void testSemanticThrowsOnRegistryTranslatorWithoutIn() {
        // a registry entry is declared under no interface, so in is the only thing tying it to one
        Config config = newMutableConfig();
        config.setTranslators(Map.of("anthropicMessagesToOpenaiChatCompletions",
                new Translator(null, "openaiChatCompletions", "http://translator/to-chat-completions")));

        assertThrows(IllegalStateException.class,
                () -> ConfigPostProcessor.processSemantic(config, null, Map.of(), Map.of(), null));
    }

    @Test
    void testSemanticLinksNamedTranslatorToItsRegistryEntry() {
        Config config = newMutableConfig();
        config.setTranslators(Map.of("anthropicMessagesToOpenaiChatCompletions",
                new Translator("anthropicMessages", "openaiChatCompletions", "http://translator/to-chat-completions")));
        Model model = new Model();
        model.setEndpoint("http://legacy/chat/completions");
        model.setInterfaces(Map.of("anthropicMessages",
                translated(TranslatorRef.named("anthropicMessagesToOpenaiChatCompletions"))));
        config.getModels().put("model", model);

        ConfigPostProcessor.processSemantic(config, null, Map.of(), Map.of(), null);

        TranslatorRef translator = config.getModels().get("model").getInterfaces().get("anthropicMessages").getTranslator();
        assertEquals("http://translator/to-chat-completions", translator.getDefinition().getBaseUrl());
        // the name is kept alongside what it resolved to, so the reference survives a config round-trip
        assertEquals("anthropicMessagesToOpenaiChatCompletions", translator.getName());
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
        assertNull(model.getInterfaces().get("anthropicMessages").getTranslator().getDefinition());
    }

    @Test
    void testSemanticAbortThrowsOnBothTranslatorAndBaseUrl() {
        // an interface is served by one or the other, so a config declaring both is rejected, not resolved
        Config config = newMutableConfig();
        Model model = new Model();
        model.setEndpoint("http://legacy/chat/completions");
        DeploymentInterface anthropic = translated(TranslatorRef.inline(
                new Translator(null, "openaiChatCompletions", "http://translator")));
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
        anthropic.setTranslator(TranslatorRef.inline(new Translator(null, "openaiChatCompletions", "http://translator")));
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
                new Translator("anthropicMessages", "openaiResponses", "http://translator/to-responses")))));
        config.getModels().put("model", model);

        assertThrows(InvalidEntityException.class,
                () -> ConfigPostProcessor.processSemantic(config, null, Map.of(), Map.of(), null));
    }

    @Test
    void testSemanticAbortThrowsOnTranslatorConvertingAnInterfaceToItself() {
        // the translator's own output arrives back on the interface it came from, and Core hands it
        // straight back to the translator: a loop, not a 503
        Config config = newMutableConfig();
        Model model = new Model();
        model.setEndpoint("http://legacy/chat/completions");
        model.setInterfaces(Map.of("anthropicMessages", translated(TranslatorRef.inline(
                new Translator("anthropicMessages", "anthropicMessages", "http://translator")))));
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
                new Translator(null, "anthropicMessages", "http://translator")))));
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

    private static DeploymentInterface translated(TranslatorRef translator) {
        DeploymentInterface declared = new DeploymentInterface();
        declared.setMode(InterfaceMode.TRANSLATOR);
        declared.setTranslator(translator);
        return declared;
    }

    private static Config newMutableConfig() {
        Config config = new Config();
        config.setModels(new HashMap<>());
        config.setApplications(new HashMap<>());
        config.setInterceptors(new HashMap<>());
        config.setToolsets(new LinkedHashMap<>());
        return config;
    }

    private static PricingRate flatRate(String rate) {
        PricingRate pricingRate = new PricingRate();
        pricingRate.setRate(rate);
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

        ConfigPostProcessor.process(config, null);

        assertEquals(List.of("good"), List.copyOf(config.getModels().keySet()));
    }
}
