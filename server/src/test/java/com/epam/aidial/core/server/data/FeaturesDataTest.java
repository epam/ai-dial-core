package com.epam.aidial.core.server.data;

import com.epam.aidial.core.config.Features;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeaturesDataTest {

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
