package com.epam.aidial.core.mcp.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaRegistryTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void modelsSchemaIsValidJsonWithProperties() throws Exception {
        SchemaRegistry registry = new SchemaRegistry();
        String schema = registry.getSchema("models");
        JsonNode parsed = MAPPER.readTree(schema);
        assertNotNull(parsed.get("properties"), "generated Model schema must expose properties");
    }

    @Test
    void schemasReturnsDialMetaSchema() throws Exception {
        SchemaRegistry registry = new SchemaRegistry();
        String schema = registry.getSchema("schemas");
        JsonNode parsed = MAPPER.readTree(schema);
        assertNotNull(parsed.get("$schema"), "meta-schema must carry $schema");
    }

    @Test
    void filesReturnsNotYetImplementedEnvelope() throws Exception {
        SchemaRegistry registry = new SchemaRegistry();
        String schema = registry.getSchema("files");
        JsonNode parsed = MAPPER.readTree(schema);
        assertEquals("files", parsed.get("type").asText());
        assertNotNull(parsed.get("error"));
        assertNotNull(parsed.get("hint"));
    }

    @Test
    void unknownTypeThrowsWithHint() {
        SchemaRegistry registry = new SchemaRegistry();
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> registry.getSchema("totally_unknown"));
        assertTrue(ex.getMessage().contains("dial_describe_schema"));
    }

    @Test
    void modelsSchemaIncludesEndpointFromBaseClass() throws Exception {
        SchemaRegistry registry = new SchemaRegistry();
        JsonNode parsed = MAPPER.readTree(registry.getSchema("models"));
        assertNotNull(parsed.get("properties").get("endpoint"),
                "Model.endpoint (inherited from Deployment) must surface in the schema");
    }
}
