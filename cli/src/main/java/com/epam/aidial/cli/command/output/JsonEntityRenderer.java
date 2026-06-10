package com.epam.aidial.cli.command.output;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

final class JsonEntityRenderer implements EntityRenderer {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Override
    public String renderSingle(JsonNode node, String type) throws JsonProcessingException {
        return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(node);
    }

    @Override
    public String renderList(JsonNode items, String type) throws JsonProcessingException {
        return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(items);
    }
}
