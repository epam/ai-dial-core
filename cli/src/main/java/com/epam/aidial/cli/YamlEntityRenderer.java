package com.epam.aidial.cli;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;

final class YamlEntityRenderer implements EntityRenderer {

    private static final YAMLMapper YAML = new YAMLMapper();

    @Override
    public String renderSingle(JsonNode node, String type) throws JsonProcessingException {
        return YAML.writeValueAsString(node).stripTrailing();
    }

    @Override
    public String renderMetadataList(JsonNode items, String type) throws JsonProcessingException {
        return YAML.writeValueAsString(items).stripTrailing();
    }

    @Override
    public String renderFileList(JsonNode items, String type) throws JsonProcessingException {
        return YAML.writeValueAsString(items).stripTrailing();
    }
}
