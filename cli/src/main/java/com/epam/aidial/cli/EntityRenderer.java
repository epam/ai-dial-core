package com.epam.aidial.cli;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;

interface EntityRenderer {

    String renderSingle(JsonNode node, String type) throws JsonProcessingException;

    /** Renders items from {@code GET /v1/metadata/{type}/{bucket}/} (ResourceItemMetadata shape — use {@code url} field). */
    String renderMetadataList(JsonNode items, String type) throws JsonProcessingException;

    /** Renders items from {@code GET /v1/admin/config/file/{type}} (simple {@code {"name":"..."}} shape). */
    String renderFileList(JsonNode items, String type) throws JsonProcessingException;

    static EntityRenderer of(OutputFormat fmt) {
        return switch (fmt) {
            case YAML  -> new YamlEntityRenderer();
            case TABLE -> new TableEntityRenderer();
            case JSON  -> new JsonEntityRenderer();
        };
    }
}
