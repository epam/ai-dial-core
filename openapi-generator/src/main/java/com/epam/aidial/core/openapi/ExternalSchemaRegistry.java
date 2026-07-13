package com.epam.aidial.core.openapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import java.util.Map;

public class ExternalSchemaRegistry {

    private static final String COMPONENTS_PREFIX = "#/components/schemas/";
    private final Map<String, ObjectNode> schemas;
    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    public ExternalSchemaRegistry(Map<String, ObjectNode> schemas) {
        this.schemas = schemas;
    }

    public void register(String schemaName) {
        if (schemas.containsKey(schemaName)) {
            return;
        }
        // Skip registration for truly external file paths - they'll be resolved by the OpenAPI spec consumer
        // Project schemas should use schema names without path separators
        if (isTrulyExternalRef(schemaName)) {
            return;
        }
        ObjectNode schema = loadSchema(schemaName);
        schemas.put(schemaName, schema);
        registerDependencies(schema);
    }

    /**
     * Check if schemaRef is a truly external file path (outside the project resources).
     * Project-local schema files in resources should use schema names instead.
     *
     * @param schemaRef the schema reference string
     * @return true if this is a truly external reference that should be preserved as-is
     */
    private static boolean isTrulyExternalRef(String schemaRef) {
        // Paths starting with these patterns are truly external (outside project)
        // Examples: "../external/Schema.yaml", "/absolute/path/Schema.yaml", "http://..."
        return schemaRef.startsWith("../")
            || schemaRef.startsWith("/")
            || schemaRef.startsWith("http://")
            || schemaRef.startsWith("https://");
    }

    private ObjectNode loadSchema(String schemaName) {
        String resource = "schemas/" + schemaName + ".yaml";
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resource)) {
            if (is == null) {
                throw new IllegalArgumentException("Schema not found: " + schemaName);
            }
            return (ObjectNode) yamlMapper.readTree(is);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load schema: " + schemaName, e);
        }
    }

    private void registerDependencies(JsonNode node) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            ObjectNode objectNode = (ObjectNode) node;
            registerReferencedSchema(objectNode);
            Iterator<JsonNode> children = objectNode.elements();
            while (children.hasNext()) {
                registerDependencies(children.next());
            }
            return;
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                registerDependencies(child);
            }
        }
    }

    private void registerReferencedSchema(ObjectNode node) {
        JsonNode refNode = node.get("$ref");
        if (refNode == null) {
            return;
        }
        String ref = refNode.asText();
        if (!ref.startsWith(COMPONENTS_PREFIX)) {
            return;
        }
        String referencedSchema = ref.substring(COMPONENTS_PREFIX.length());
        register(referencedSchema);
    }
}