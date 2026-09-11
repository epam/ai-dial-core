package com.epam.aidial.core.openapi;

import com.epam.aidial.core.config.LocalizedValue;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.openapi.annotations.ApiSchema;
import com.epam.aidial.core.openapi.annotations.ApiSchemaType;
import com.epam.aidial.core.openapi.annotations.ApiSubType;
import com.epam.aidial.core.openapi.annotations.ApiSubTypes;
import com.fasterxml.jackson.databind.JsonNode;
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

    private record SampleManifest(
            String kind,
            @ApiSchema(oneOf = {com.epam.aidial.core.server.data.ResourceLink.class},
                    oneOfSchemaRefs = {"ProxyRequest"}) com.fasterxml.jackson.databind.JsonNode spec) {
    }

    private record SampleSchemaManifest(
            String kind,
            @ApiSchema(schemaRef = "ApplicationTypeSchema") com.fasterxml.jackson.databind.JsonNode spec) {
    }

    @Test
    void fieldLevelSchemaRefAnnotationDrivesPropertyRef() {
        DtoSchemaGenerator generator = new DtoSchemaGenerator();
        generator.processType(SampleSchemaManifest.class);

        Map<String, ObjectNode> schemas = generator.getSchemas();
        ObjectNode schema = schemas.get(generator.resolveTypeName(SampleSchemaManifest.class));
        assertNotNull(schema, "SampleSchemaManifest schema should be generated");

        ObjectNode spec = (ObjectNode) schema.get("properties").get("spec");
        assertNotNull(spec, "spec property should be generated");
        assertTrue(spec.has("$ref"), "spec property should be a $ref");
        assertEquals("#/components/schemas/ApplicationTypeSchema", spec.get("$ref").asText());

        ObjectNode applicationTypeSchema = schemas.get("ApplicationTypeSchema");
        assertNotNull(applicationTypeSchema, "ApplicationTypeSchema component should be registered");
        assertEquals("object", applicationTypeSchema.get("type").asText());
        assertTrue(applicationTypeSchema.get("additionalProperties").asBoolean());
        ArrayNode requiredIds = (ArrayNode) applicationTypeSchema.get("required");
        assertNotNull(requiredIds);
        assertEquals("$id", requiredIds.get(0).asText());
        ObjectNode idProperty = (ObjectNode) applicationTypeSchema.get("properties").get("$id");
        assertNotNull(idProperty);
        assertEquals("string", idProperty.get("type").asText());
        assertEquals("uri-reference", idProperty.get("format").asText());
    }

    @ApiSubTypes(
            discriminatorProperty = "kind",
            value = {
                    @ApiSubType(discriminatorValue = "Settings", type = SampleSettingsTypedManifest.class),
                    @ApiSubType(discriminatorValue = "Model", type = SampleModelTypedManifest.class),
                    @ApiSubType(discriminatorValue = "Schema", type = SampleSchemaTypedManifest.class)
            }
    )
    private record SampleAdminManifest(String kind, String name, JsonNode spec) {
    }

    private record SampleSettingsTypedManifest(String kind, String name, LocalizedValue spec) {
    }

    private record SampleModelTypedManifest(String kind, String name, Model spec) {
    }

    private record SampleSchemaTypedManifest(
            String kind,
            String name,
            @ApiSchema(schemaRef = "ApplicationTypeSchema") JsonNode spec) {
    }

    @Test
    void apiSubTypesAnnotationProducesKindDiscriminatedOneOf() {
        DtoSchemaGenerator generator = new DtoSchemaGenerator();
        generator.processType(SampleAdminManifest.class);

        Map<String, ObjectNode> schemas = generator.getSchemas();
        ObjectNode schema = schemas.get(generator.resolveTypeName(SampleAdminManifest.class));
        assertNotNull(schema, "SampleAdminManifest schema should be generated");

        ArrayNode oneOf = (ArrayNode) schema.get("oneOf");
        assertNotNull(oneOf, "SampleAdminManifest should be a oneOf");
        assertEquals(3, oneOf.size());
        String settingsRef = "#/components/schemas/" + generator.resolveTypeName(SampleSettingsTypedManifest.class);
        String modelRef = "#/components/schemas/" + generator.resolveTypeName(SampleModelTypedManifest.class);
        String schemaRef = "#/components/schemas/" + generator.resolveTypeName(SampleSchemaTypedManifest.class);
        assertEquals(settingsRef, oneOf.get(0).get("$ref").asText());
        assertEquals(modelRef, oneOf.get(1).get("$ref").asText());
        assertEquals(schemaRef, oneOf.get(2).get("$ref").asText());

        ObjectNode discriminator = (ObjectNode) schema.get("discriminator");
        assertNotNull(discriminator, "SampleAdminManifest should carry a discriminator");
        assertEquals("kind", discriminator.get("propertyName").asText());
        ObjectNode mapping = (ObjectNode) discriminator.get("mapping");
        assertEquals(modelRef, mapping.get("Model").asText());
        assertEquals(schemaRef, mapping.get("Schema").asText());

        ArrayNode required = (ArrayNode) schema.get("required");
        assertNotNull(required);
        assertEquals("kind", required.get(0).asText());

        ObjectNode modelManifest = schemas.get(generator.resolveTypeName(SampleModelTypedManifest.class));
        assertNotNull(modelManifest, "model manifest schema should be generated");
        assertEquals("#/components/schemas/Model",
                modelManifest.get("properties").get("spec").get("$ref").asText());
        assertEquals("string", modelManifest.get("properties").get("kind").get("type").asText());

        ObjectNode schemaManifest = schemas.get(generator.resolveTypeName(SampleSchemaTypedManifest.class));
        assertNotNull(schemaManifest, "schema manifest schema should be generated");
        assertEquals("#/components/schemas/ApplicationTypeSchema",
                schemaManifest.get("properties").get("spec").get("$ref").asText());
    }

    @Test
    void inheritanceBasedPolymorphicSchemaUsesComponentRefs() {
        DtoSchemaGenerator generator = new DtoSchemaGenerator();
        generator.processType(com.epam.aidial.core.storage.data.MetadataBase.class);

        Map<String, ObjectNode> schemas = generator.getSchemas();
        ObjectNode schema = schemas.get("MetadataBase");
        assertNotNull(schema, "MetadataBase schema should be generated");

        ArrayNode oneOf = (ArrayNode) schema.get("oneOf");
        assertEquals(2, oneOf.size());
        assertEquals("#/components/schemas/ResourceFolderMetadata", oneOf.get(0).get("$ref").asText());
        assertEquals("#/components/schemas/ResourceItemMetadata", oneOf.get(1).get("$ref").asText());

        ObjectNode folderMetadata = schemas.get("ResourceFolderMetadata");
        assertNotNull(folderMetadata, "ResourceFolderMetadata schema should be generated");
        ObjectNode items = (ObjectNode) folderMetadata.get("properties").get("items").get("items");
        assertEquals("#/components/schemas/MetadataBase", items.get("$ref").asText());
    }

    @Test
    void fieldLevelApiSchemaAnnotationDrivesPropertyOneOf() {
        DtoSchemaGenerator generator = new DtoSchemaGenerator();
        generator.processType(SampleManifest.class);

        Map<String, ObjectNode> schemas = generator.getSchemas();
        ObjectNode schema = schemas.get(generator.resolveTypeName(SampleManifest.class));
        assertNotNull(schema, "SampleManifest schema should be generated");

        ObjectNode spec = (ObjectNode) schema.get("properties").get("spec");
        assertNotNull(spec, "spec property should be generated");
        assertTrue(spec.has("oneOf"), "spec property should be a oneOf");
        ArrayNode oneOf = (ArrayNode) spec.get("oneOf");
        assertEquals(2, oneOf.size());
        assertEquals("#/components/schemas/ResourceLink", oneOf.get(0).get("$ref").asText());
        assertEquals("#/components/schemas/ProxyRequest", oneOf.get(1).get("$ref").asText());

        assertNotNull(schemas.get("ResourceLink"), "Referenced ResourceLink schema should be generated");
        assertNotNull(schemas.get("ProxyRequest"), "Referenced ProxyRequest schema should be registered");

        ObjectNode kind = (ObjectNode) schema.get("properties").get("kind");
        assertNotNull(kind);
        assertEquals("string", kind.get("type").asText());
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