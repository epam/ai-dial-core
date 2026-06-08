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
        ObjectNode schema = loadSchema(schemaName);
        schemas.put(schemaName, schema);
        registerDependencies(schema);
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