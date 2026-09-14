package com.epam.aidial.core.server.data;

import com.epam.aidial.core.config.DeploymentInterface;
import com.epam.aidial.core.config.Features;
import com.epam.aidial.core.config.InterfaceType;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.ModelType;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InterfaceConfigDataTest {

    @Test
    void interfaceFlagsNameTheApiTheInterfaceItselfIs() {
        Model model = new Model();
        model.setInterfaces(declaredInterfaces(
                InterfaceType.OPENAI_CHAT_COMPLETIONS, InterfaceType.OPENAI_RESPONSES, InterfaceType.ANTHROPIC_MESSAGES));

        Map<String, InterfaceConfigData> configsByInterface = InterfaceConfigData.createInterfaceConfigs(model);

        assertEquals(Set.of("openaiChatCompletions", "openaiResponses", "anthropicMessages"), configsByInterface.keySet());
        assertTrue(configsByInterface.get("openaiChatCompletions").getFeatures().isChatCompletion());
        assertFalse(configsByInterface.get("openaiChatCompletions").getFeatures().isResponsesApi());
        assertTrue(configsByInterface.get("openaiResponses").getFeatures().isResponsesApi());
        assertFalse(configsByInterface.get("openaiResponses").getFeatures().isChatCompletion());
        assertFalse(configsByInterface.get("anthropicMessages").getFeatures().isChatCompletion());
        assertFalse(configsByInterface.get("anthropicMessages").getFeatures().isResponsesApi());
    }

    @Test
    void featuresAreTheOnesInForceForThatInterface() {
        Model model = new Model();
        Features features = new Features();
        features.setToolsSupported(true);
        features.setReasoningEfforts(List.of("low", "medium"));
        model.setFeatures(features);
        Features overrides = new Features();
        overrides.setReasoningEfforts(List.of("low", "medium", "high", "xhigh", "max"));
        DeploymentInterface declared = new DeploymentInterface("http://localhost:7001");
        declared.setFeatures(overrides);
        model.setInterfaces(Map.of(InterfaceType.ANTHROPIC_MESSAGES.getValue(), declared));

        Map<String, InterfaceConfigData> configsByInterface = InterfaceConfigData.createInterfaceConfigs(model);

        FeaturesData anthropicFeatures = configsByInterface.get("anthropicMessages").getFeatures();
        assertTrue(anthropicFeatures.isTools());
        assertEquals(List.of("low", "medium", "high", "xhigh", "max"), anthropicFeatures.getReasoningEfforts());
    }

    @Test
    void defaultsAreTheOnesInForceForThatInterface() {
        Model model = new Model();
        model.setDefaults(Map.of("temperature", 1));
        model.setResponsesDefaults(Map.of("temperature", 0.9));
        Map<String, DeploymentInterface> interfacesByType = declaredInterfaces(
                InterfaceType.OPENAI_CHAT_COMPLETIONS, InterfaceType.OPENAI_RESPONSES, InterfaceType.ANTHROPIC_MESSAGES);
        interfacesByType.get(InterfaceType.ANTHROPIC_MESSAGES.getValue()).setDefaults(Map.of("temperature", 0.5));
        model.setInterfaces(interfacesByType);

        Map<String, InterfaceConfigData> configsByInterface = InterfaceConfigData.createInterfaceConfigs(model);

        assertEquals(Map.of("temperature", 1), configsByInterface.get("openaiChatCompletions").getDefaults());
        assertEquals(Map.of("temperature", 0.9), configsByInterface.get("openaiResponses").getDefaults());
        assertEquals(Map.of("temperature", 0.5), configsByInterface.get("anthropicMessages").getDefaults());
    }

    @Test
    void defaultHeadersAreTheOnesInForceForThatInterface() {
        Model model = new Model();
        model.setDefaultHeaders(Map.of(
                "x-dial-cache-policy", "cache-priority",
                "x-dial-custom-header", "foo-bar"));
        Map<String, DeploymentInterface> interfacesByType = declaredInterfaces(
                InterfaceType.OPENAI_CHAT_COMPLETIONS, InterfaceType.ANTHROPIC_MESSAGES);
        interfacesByType.get(InterfaceType.ANTHROPIC_MESSAGES.getValue()).setDefaultHeaders(Map.of(
                "x-dial-custom-header", "foo-bar-2",
                "x-dial-custom-header-2", "some-value"));
        model.setInterfaces(interfacesByType);

        Map<String, InterfaceConfigData> configsByInterface = InterfaceConfigData.createInterfaceConfigs(model);

        // an interface declaring no headers of its own serves the deployment-level set
        assertEquals(Map.of("x-dial-cache-policy", "cache-priority", "x-dial-custom-header", "foo-bar"),
                configsByInterface.get("openaiChatCompletions").getDefaultHeaders());
        // one that does overrides by name and adds new ones, inheriting the rest
        assertEquals(Map.of(
                "x-dial-cache-policy", "cache-priority",
                "x-dial-custom-header", "foo-bar-2",
                "x-dial-custom-header-2", "some-value"),
                configsByInterface.get("anthropicMessages").getDefaultHeaders());
    }

    @Test
    void legacyFieldsAreAdvertisedAsTheInterfaceMatchingTheModelType() {
        Model chat = new Model();
        chat.setType(ModelType.CHAT);
        chat.setEndpoint("http://localhost:7001/chat/completions");
        assertEquals(Set.of("openaiChatCompletions"), InterfaceConfigData.createInterfaceConfigs(chat).keySet());

        Model embedding = new Model();
        embedding.setType(ModelType.EMBEDDING);
        embedding.setEndpoint("http://localhost:7001/embeddings");
        assertEquals(Set.of("openaiEmbeddings"), InterfaceConfigData.createInterfaceConfigs(embedding).keySet());

        Model responses = new Model();
        responses.setType(ModelType.CHAT);
        responses.setResponsesEndpoint("http://localhost:7001/responses");
        assertEquals(Set.of("openaiResponses"), InterfaceConfigData.createInterfaceConfigs(responses).keySet());
    }

    @Test
    void deploymentServingNoInterfaceHasNoConfigs() {
        Model model = new Model();
        model.setBaseUrl("http://localhost:7001");

        assertEquals(Map.of(), InterfaceConfigData.createInterfaceConfigs(model));
    }

    private static Map<String, DeploymentInterface> declaredInterfaces(InterfaceType... types) {
        Map<String, DeploymentInterface> interfacesByType = new HashMap<>();
        for (InterfaceType type : types) {
            interfacesByType.put(type.getValue(), new DeploymentInterface("http://localhost:7001"));
        }
        return interfacesByType;
    }
}
