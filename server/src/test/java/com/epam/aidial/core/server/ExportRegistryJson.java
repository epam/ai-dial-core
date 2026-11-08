package com.epam.aidial.core.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reads the parts of an export registry that the tests ask about. A reference between two resources is a dependency of the
 * resource that holds it, so the unresolved ones and the references to a resource are found by walking the resources.
 */
final class ExportRegistryJson {

    private ExportRegistryJson() {
    }

    /** The resources of the registry, in the order of their JSON object. */
    static List<JsonNode> resources(JsonNode registry) {
        List<JsonNode> resources = new ArrayList<>();
        registry.get("resources").forEach(resources::add);
        return resources;
    }

    /** The resource with a key, or null. */
    static JsonNode resource(JsonNode registry, String key) {
        return registry.get("resources").get(key);
    }

    /** The key of the resource at an index of the JSON object. */
    static String keyAt(JsonNode registry, int index) {
        List<String> keys = new ArrayList<>();
        registry.get("resources").fieldNames().forEachRemaining(keys::add);
        return keys.get(index);
    }

    /**
     * The dependencies that do not end in an included resource, each with the key of the resource that holds it in {@code source}.
     */
    static List<JsonNode> unresolved(JsonNode registry) {
        List<JsonNode> unresolved = new ArrayList<>();
        for (Map.Entry<String, JsonNode> resource : registry.get("resources").properties()) {
            for (JsonNode dependency : resource.getValue().get("dependencies")) {
                if (!"included".equals(dependency.get("status").asText())) {
                    ObjectNode copy = dependency.deepCopy();
                    copy.put("source", resource.getKey());
                    unresolved.add(copy);
                }
            }
        }
        return unresolved;
    }

    /** The included dependencies that point at a resource, each with the key of its source in {@code source}. */
    static List<JsonNode> referencesTo(JsonNode registry, String key) {
        List<JsonNode> references = new ArrayList<>();
        for (Map.Entry<String, JsonNode> resource : registry.get("resources").properties()) {
            for (JsonNode dependency : resource.getValue().get("dependencies")) {
                if (dependency.has("key") && key.equals(dependency.get("key").asText())) {
                    ObjectNode copy = dependency.deepCopy();
                    copy.put("source", resource.getKey());
                    references.add(copy);
                }
            }
        }
        return references;
    }
}
