package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.ModelType;
import com.epam.aidial.core.server.data.ModelData;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ModelControllerTest {

    @Test
    void createModel_mapsEmbeddingDimensions() {
        Model model = new Model();
        model.setName("embedding-ada");
        model.setType(ModelType.EMBEDDING);
        model.setEmbeddingDimensions(1536);

        ModelData data = ModelController.createModel(model);

        assertEquals(1536, data.getEmbeddingDimensions());
    }

    @Test
    void createModel_omitsEmbeddingDimensionsWhenUnset() {
        Model model = new Model();
        model.setName("chat-gpt-35-turbo");
        model.setType(ModelType.CHAT);

        ModelData data = ModelController.createModel(model);

        assertNull(data.getEmbeddingDimensions());
    }
}
