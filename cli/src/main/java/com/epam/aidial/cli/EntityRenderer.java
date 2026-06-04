package com.epam.aidial.cli;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;

interface EntityRenderer {

    String renderSingle(JsonNode node, String type) throws JsonProcessingException;

    String renderList(JsonNode items, String type) throws JsonProcessingException;

    static EntityRenderer of(OutputFormat fmt) {
        return switch (fmt) {
            case YAML  -> new YamlEntityRenderer();
            case TABLE -> new TableEntityRenderer();
            case JSON  -> new JsonEntityRenderer();
        };
    }
}
