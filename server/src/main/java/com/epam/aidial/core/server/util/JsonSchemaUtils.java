package com.epam.aidial.core.server.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.experimental.UtilityClass;

import java.util.Iterator;

@UtilityClass
public class JsonSchemaUtils {

    private static final String PROPERTIES = "properties";

    /**
     * Resolves all first-level $ref references in the "properties" section of a JSON schema.
     *
     * <p>
     * For each property with a $ref, this method:
     * <ul>
     *   <li>Finds the referenced node using the JSON Pointer in the $ref.</li>
     *   <li>Merges the referenced node into the property, preserving any additional fields on the property itself (except $ref).</li>
     *   <li>Replaces the referenced node in the schema with an empty object.</li>
     * </ul>
     * If no first-level property contains a $ref, the schema is returned unchanged.
     *
     * @param schema the JSON schema as a string
     * @return the rewritten schema as a string with first-level $ref in properties resolved
     * @throws JsonProcessingException if the schema cannot be parsed
     */
    public static String extractTopLevelRefs(String schema) throws JsonProcessingException {
        JsonNode schemaNode = ProxyUtil.MAPPER.readTree(schema);
        if (!hasTopLevelRefs(schemaNode)) {
            return schema;
        }
        ObjectNode propertiesNode = (ObjectNode) schemaNode.get(PROPERTIES);
        ObjectNode newPropertiesNode = propertiesNode.deepCopy();
        boolean modified = false;

        for (Iterator<String> it = propertiesNode.fieldNames(); it.hasNext(); ) {
            String fieldName = it.next();
            JsonNode propNode = propertiesNode.get(fieldName);
            if (propNode.has("$ref")) {
                String ref = propNode.get("$ref").asText();
                if (ref.startsWith("#/")) {
                    JsonNode targetNode = resolveRef(schemaNode, ref);
                    if (targetNode != null && targetNode.isObject()) {
                        ObjectNode merged = mergeNodes(targetNode, propNode);
                        newPropertiesNode.set(fieldName, merged);
                        clearReferencedNode(schemaNode, ref);
                        modified = true;
                    }
                }
            }
        }

        if (modified) {
            ((ObjectNode) schemaNode).set(PROPERTIES, newPropertiesNode);
            return ProxyUtil.MAPPER.writeValueAsString(schemaNode);
        }
        return schema;
    }

    private static boolean hasTopLevelRefs(JsonNode schemaNode) {
        if (!schemaNode.has(PROPERTIES) || !schemaNode.get(PROPERTIES).isObject()) {
            return false;
        }
        ObjectNode propertiesNode = (ObjectNode) schemaNode.get(PROPERTIES);
        for (Iterator<String> it = propertiesNode.fieldNames(); it.hasNext(); ) {
            String fieldName = it.next();
            JsonNode propNode = propertiesNode.get(fieldName);
            if (propNode.has("$ref")) {
                return true;
            }
        }
        return false;
    }

    private static JsonNode resolveRef(JsonNode schemaNode, String ref) {
        return schemaNode.at(ref.substring(1));
    }

    private static ObjectNode mergeNodes(JsonNode targetNode, JsonNode propNode) {
        ObjectNode merged = ProxyUtil.MAPPER.createObjectNode();
        targetNode.fields().forEachRemaining(e -> merged.set(e.getKey(), e.getValue()));
        propNode.fields().forEachRemaining(e -> {
            if (!"$ref".equals(e.getKey())) {
                merged.set(e.getKey(), e.getValue());
            }
        });
        return merged;
    }

    private static void clearReferencedNode(JsonNode schemaNode, String ref) {
        String[] pathParts = ref.substring(1).split("/");
        if (pathParts.length > 1) {
            JsonNode parent = schemaNode;
            for (int i = 1; i < pathParts.length - 1; i++) {
                parent = parent.path(pathParts[i]);
            }
            if (parent.isObject()) {
                ((ObjectNode) parent).set(pathParts[pathParts.length - 1], ProxyUtil.MAPPER.createObjectNode());
            }
        }
    }
}
