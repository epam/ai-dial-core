package com.epam.aidial.core.server.data;

import com.epam.aidial.core.config.DeploymentInterface;
import com.epam.aidial.core.config.Features;
import com.epam.aidial.core.config.InterfaceType;
import com.epam.aidial.core.config.Model;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeaturesDataTest {

    @Test
    void interfaceFeaturesAreMergedBeforeDefaultsWithoutChangingListingFeatures() {
        Model model = new Model();
        Features features = new Features();
        features.setTemperatureSupported(false);
        features.setReasoningEfforts(List.of("low", "high"));
        model.setFeatures(features);
        Features overrides = new Features();
        overrides.setReasoningEfforts(List.of());
        DeploymentInterface declared = new DeploymentInterface("http://adapter");
        declared.setFeatures(overrides);
        model.setInterfaces(Map.of(InterfaceType.OPENAI_RESPONSES.getValue(), declared));

        FeaturesData effective = FeaturesData.createDeploymentFeatures(model, InterfaceType.OPENAI_RESPONSES);

        assertFalse(effective.isTemperature());
        assertTrue(effective.isSystemPrompt());
        assertFalse(effective.isTools());
        assertTrue(effective.isResponsesApi());
        assertFalse(effective.isChatCompletion());
        assertEquals(List.of(), effective.getReasoningEfforts());
        assertEquals(List.of("low", "high"), FeaturesData.createDeploymentFeatures(model).getReasoningEfforts());
    }

    @Test
    void createFeatures_defaultReasoningEffortsIsEmpty() {
        FeaturesData data = FeaturesData.createFeatures(null);

        assertTrue(data.getReasoningEfforts().isEmpty());
    }

    @Test
    void createFeatures_mapsReasoningEfforts() {
        Features features = new Features();
        features.setReasoningEfforts(List.of("low", "medium", "high"));

        FeaturesData data = FeaturesData.createFeatures(features);

        assertEquals(List.of("low", "medium", "high"), data.getReasoningEfforts());
    }
}
