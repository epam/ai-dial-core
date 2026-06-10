package com.epam.aidial.cli.command.output;

import com.epam.aidial.cli.service.OutputFormatDto;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;

public interface EntityRenderer {

    String renderSingle(JsonNode node, String type) throws JsonProcessingException;

    String renderList(JsonNode items, String type) throws JsonProcessingException;

    static EntityRenderer of(OutputFormatDto fmt) {
        return switch (fmt) {
            case YAML  -> new YamlEntityRenderer();
            case TABLE -> new TableEntityRenderer();
            case JSON  -> new JsonEntityRenderer();
        };
    }
}
