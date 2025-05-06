package com.epam.aidial.core.server.service.schemarichapps;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.experimental.UtilityClass;

import java.util.Map;

@UtilityClass
public class JsonUtils {

    public static void replaceTextNodes(JsonNode node, Map<String, String> replacementMap, JsonNode parent, String fieldName) {
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> replaceTextNodes(entry.getValue(), replacementMap, node, entry.getKey()));
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                JsonNode childNode = node.get(i);
                if (childNode.isTextual()) {
                    String replacement = replacementMap.get(childNode.textValue());
                    if (replacement != null) {
                        ((ArrayNode) node).set(i, replacement);
                    }
                } else {
                    replaceTextNodes(childNode, replacementMap, node, String.valueOf(i));
                }
            }
        } else if (node.isTextual()) {
            String replacement = replacementMap.get(node.textValue());
            if (replacement != null && parent.isObject()) {
                ((ObjectNode) parent).put(fieldName, replacement);
            }
        }
    }

}
