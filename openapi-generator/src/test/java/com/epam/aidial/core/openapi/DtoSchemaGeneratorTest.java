package com.epam.aidial.core.openapi;

import com.epam.aidial.core.config.LocalizedValue;
import com.epam.aidial.core.openapi.annotations.ApiSchema;
import com.epam.aidial.core.openapi.annotations.ApiSchemaType;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DtoSchemaGeneratorTest {

    @Test
    void sanitizeSchemaNameStripsParenthesesAndCommas() {
        String sanitized = DtoSchemaGenerator.sanitizeSchemaName("Map(String,Deployment)");
        assertFalse(sanitized.contains("("), "Should not contain '('");
        assertFalse(sanitized.contains(")"), "Should not contain ')'");
        assertFalse(sanitized.contains(","), "Should not contain ','");
        assertTrue(sanitized.matches("[a-zA-Z0-9]+"), "Should only contain alphanumeric chars");
    }

    @Test
    void mapSchemaHasAdditionalProperties() {
        DtoSchemaGenerator generator = new DtoSchemaGenerator();
        generator.processType(EndpointMetadata.paramType(Map.class, String.class, String.class));

        Map<String, ObjectNode> schemas = generator.getSchemas();
        assertFalse(schemas.isEmpty(), "Schemas should not be empty");

        // Find the Map schema
        boolean foundMapWithAdditionalProps = false;
        for (Map.Entry<String, ObjectNode> entry : schemas.entrySet()) {
            ObjectNode schema = entry.getValue();
            if (schema.has("additionalProperties")) {
                foundMapWithAdditionalProps = true;
                break;
            }
        }
        assertTrue(foundMapWithAdditionalProps,
                "Map schema should have additionalProperties");
    }

    @Test
    void schemaNamesSanitized() {
        DtoSchemaGenerator generator = new DtoSchemaGenerator();
        generator.processType(EndpointMetadata.paramType(Map.class, String.class, Integer.class));

        Map<String, ObjectNode> schemas = generator.getSchemas();
        for (String name : schemas.keySet()) {
            assertFalse(name.contains("("), "Schema name should not contain '(': " + name);
            assertFalse(name.contains(")"), "Schema name should not contain ')': " + name);
            assertFalse(name.contains(","), "Schema name should not contain ',': " + name);
        }
    }

    @Test
    void resolveTypeNameProducesCleanName() {
        DtoSchemaGenerator generator = new DtoSchemaGenerator();
        String name = generator.resolveTypeName(
                EndpointMetadata.paramType(Map.class, String.class, Integer.class));
        assertNotNull(name);
        assertFalse(name.contains("("));
        assertFalse(name.contains(")"));
    }

    @Test
    void localizedValueSchemaIsOneOfStringAndMap() {
        DtoSchemaGenerator generator = new DtoSchemaGenerator();
        generator.processType(LocalizedValue.class);

        Map<String, ObjectNode> schemas = generator.getSchemas();
        ObjectNode schema = schemas.get("LocalizedValue");
        assertNotNull(schema, "LocalizedValue schema should be generated");
        assertTrue(schema.has("oneOf"), "Should have oneOf");
        assertFalse(schema.has("properties"), "Should not expose reflected fields");

        ArrayNode oneOf = (ArrayNode) schema.get("oneOf");
        assertEquals(2, oneOf.size());
        assertEquals("string", oneOf.get(0).get("type").asText());

        // Map<String, String> is deduplicated into the shared MapStringString component,
        // like every other Map<String, String> field in the generated spec.
        assertTrue(oneOf.get(1).has("$ref"));
        String mapSchemaName = oneOf.get(1).get("$ref").asText().substring("#/components/schemas/".length());
        ObjectNode mapSchema = schemas.get(mapSchemaName);
        assertNotNull(mapSchema, "Referenced map schema should be generated");
        assertEquals("object", mapSchema.get("type").asText());
        assertEquals("string", mapSchema.get("additionalProperties").get("type").asText());

        for (ObjectNode node : schemas.values()) {
            assertFalse(node.has("plainValue"), "plainValue must not be exposed as a property");
            assertFalse(node.has("localeMap"), "localeMap must not be exposed as a property");
        }
    }

    @ApiSchema(
            oneOf = {String.class},
            oneOfTypes = {@ApiSchemaType(implementation = Map.class, typeArguments = {String.class, Integer.class})}
    )
    private static final class SampleUnionType {
    }

    @Test
    void classLevelApiSchemaAnnotationDrivesGenericOneOf() {
        DtoSchemaGenerator generator = new DtoSchemaGenerator();
        generator.processType(SampleUnionType.class);

        Map<String, ObjectNode> schemas = generator.getSchemas();
        ObjectNode schema = schemas.get(generator.resolveTypeName(SampleUnionType.class));
        assertNotNull(schema);
        ArrayNode oneOf = (ArrayNode) schema.get("oneOf");
        assertEquals(2, oneOf.size());
        assertEquals("string", oneOf.get(0).get("type").asText());

        assertTrue(oneOf.get(1).has("$ref"));
        String mapSchemaName = oneOf.get(1).get("$ref").asText().substring("#/components/schemas/".length());
        ObjectNode mapSchema = schemas.get(mapSchemaName);
        assertNotNull(mapSchema, "Referenced map schema should be generated");
        assertEquals("object", mapSchema.get("type").asText());
        assertEquals("integer", mapSchema.get("additionalProperties").get("type").asText());
    }

    @Test
    void refPathsPointToComponentsSchemas() {
        DtoSchemaGenerator generator = new DtoSchemaGenerator();
        generator.processType(EndpointMetadata.paramType(
                java.util.List.class,
                com.epam.aidial.core.server.data.ResourceLink.class));

        Map<String, ObjectNode> schemas = generator.getSchemas();
        for (ObjectNode schema : schemas.values()) {
            checkRefsRecursive(schema);
        }
    }

    private void checkRefsRecursive(com.fasterxml.jackson.databind.JsonNode node) {
        if (node.isObject()) {
            if (node.has("$ref")) {
                String ref = node.get("$ref").asText();
                assertTrue(ref.startsWith("#/components/schemas/"),
                        "$ref should start with #/components/schemas/ but was: " + ref);
                String schemaName = ref.substring("#/components/schemas/".length());
                assertTrue(schemaName.matches("[a-zA-Z0-9]+"),
                        "Schema name in $ref should be alphanumeric: " + schemaName);
            }
            node.fields().forEachRemaining(entry -> checkRefsRecursive(entry.getValue()));
        } else if (node.isArray()) {
            node.forEach(this::checkRefsRecursive);
        }
    }

}