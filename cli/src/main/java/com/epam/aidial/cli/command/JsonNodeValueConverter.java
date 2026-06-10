package com.epam.aidial.cli.command;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import picocli.CommandLine;

class JsonNodeValueConverter implements CommandLine.ITypeConverter<JsonNode> {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Override
    public JsonNode convert(String raw) {
        try {
            return JSON.readTree(raw);
        } catch (JsonProcessingException e) {
            return TextNode.valueOf(raw);
        }
    }
}
