package com.epam.aidial.core.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static com.epam.aidial.core.config.InterfaceType.ANTHROPIC_MESSAGES;
import static com.epam.aidial.core.config.InterfaceType.OPENAI_CHAT_COMPLETIONS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeploymentFeaturesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @ParameterizedTest
    @EnumSource(InterfaceType.class)
    void resolvesOnlyTheRequestedInterfacesOverrides(InterfaceType type) throws Exception {
        Model model = MAPPER.readValue("""
                {
                  "features": {"temperature_supported": true, "reasoning_efforts": ["low", "medium", "high"]},
                  "interfaces": {
                    "openaiChatCompletions": {"mode": "passthrough"},
                    "openaiResponses": {"mode": "passthrough"},
                    "openaiEmbeddings": {"mode": "passthrough"},
                    "anthropicMessages": {
                      "mode": "passthrough",
                      "features": {"reasoning_efforts": ["low", "medium", "high", "xhigh", "max"]}
                    }
                  }
                }
                """, Model.class);
        JsonNode before = MAPPER.valueToTree(model);

        Features effective = model.resolveFeatures(type);

        assertTrue(effective.getTemperatureSupported());
        assertEquals(type == ANTHROPIC_MESSAGES ? List.of("low", "medium", "high", "xhigh", "max")
                : List.of("low", "medium", "high"), effective.getReasoningEfforts());
        assertNull(effective.getToolsSupported()); // Core defaults are applied after merging.
        assertEquals(before, MAPPER.valueToTree(model));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"interfaces\":null}", "{\"interfaces\":{\"anthropicMessages\":null}}",
            "{\"interfaces\":{\"anthropicMessages\":{}}}", "{\"interfaces\":{\"anthropicMessages\":{\"features\":null}}}",
            "{\"interfaces\":{\"anthropicMessages\":{\"features\":{}}}}"})
    void missingOrEmptyInterfaceFeaturesInheritDeploymentFeatures(String json) throws Exception {
        Model model = MAPPER.readValue(json, Model.class);
        Features features = new Features();
        features.setToolsSupported(true);
        features.setReasoningEfforts(List.of("high"));
        model.setFeatures(features);

        assertEquals(features, model.resolveFeatures(ANTHROPIC_MESSAGES));
    }

    @Test
    void roundTripPreservesFalseEmptyAndNullOverridesAndAliases() throws Exception {
        Model model = MAPPER.readValue("""
                {
                  "features": {"toolsSupported": true, "temperatureSupported": true, "reasoningEfforts": ["high"]},
                  "interfaces": {
                    "anthropicMessages": {
                      "features": {"tools_supported": false, "temperature_supported": null, "reasoningEfforts": []}
                    }
                  }
                }
                """, Model.class);
        Model restored = MAPPER.readValue(MAPPER.writeValueAsString(model), Model.class);

        Features effective = restored.resolveFeatures(ANTHROPIC_MESSAGES);
        assertFalse(effective.getToolsSupported());
        assertTrue(effective.getTemperatureSupported());
        assertEquals(List.of(), effective.getReasoningEfforts());
        assertTrue(restored.resolveFeatures(OPENAI_CHAT_COMPLETIONS).getToolsSupported());
        assertEquals(List.of("high"), restored.resolveFeatures(OPENAI_CHAT_COMPLETIONS).getReasoningEfforts());
    }

    @Test
    void interfaceFeaturesWorkWithoutDeploymentFeaturesAndSurviveApplicationCopy() {
        Features overrides = new Features();
        overrides.setTemperatureSupported(false);
        DeploymentInterface declared = new DeploymentInterface("http://adapter");
        declared.setFeatures(overrides);
        Application application = new Application();
        application.setInterfaces(Map.of(OPENAI_CHAT_COMPLETIONS.getValue(), declared));

        Application copy = new Application(application);

        assertFalse(copy.resolveFeatures(OPENAI_CHAT_COMPLETIONS).getTemperatureSupported());
        assertNull(copy.resolveFeatures(ANTHROPIC_MESSAGES));
    }
}
