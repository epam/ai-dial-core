package com.epam.aidial.core.server.util;

import com.epam.aidial.core.storage.util.UrlUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.experimental.UtilityClass;

import java.util.Map;

/**
 * Rewrites file/resource URLs embedded anywhere in a deployment's custom property map (e.g.
 * {@code application_properties} or {@code catalog_properties}) after those files have been
 * copied to a new location on publish/share. Deployment-type-agnostic: the tree-walk logic has no
 * knowledge of what the properties mean, only that some leaf string values are DIAL resource urls.
 */
@UtilityClass
public class CatalogPropertiesLinkRewriter {

    public static Map<String, Object> rewrite(Map<String, Object> properties, Map<String, String> replacementLinks) {
        if (properties == null || properties.isEmpty() || replacementLinks.isEmpty()) {
            return properties;
        }
        JsonNode node = ProxyUtil.MAPPER.convertValue(properties, JsonNode.class);
        replaceTextNodes(node, replacementLinks, null, null);
        return ProxyUtil.MAPPER.convertValue(node, new TypeReference<>() {
        });
    }

    private static void replaceTextNodes(JsonNode node, Map<String, String> replacementMap, JsonNode parent, String fieldName) {
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> replaceTextNodes(entry.getValue(), replacementMap, node, entry.getKey()));
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                JsonNode childNode = node.get(i);
                if (childNode.isTextual()) {
                    String decodedUrl = UrlUtil.tryDecodePath(childNode.textValue());
                    String replacement = replacementMap.get(decodedUrl);
                    if (replacement != null) {
                        ((ArrayNode) node).set(i, replacement);
                    }
                } else {
                    replaceTextNodes(childNode, replacementMap, node, String.valueOf(i));
                }
            }
        } else if (node.isTextual()) {
            String decodedUrl = UrlUtil.tryDecodePath(node.textValue());
            String replacement = replacementMap.get(decodedUrl);
            if (replacement != null && parent.isObject()) {
                ((ObjectNode) parent).put(fieldName, replacement);
            }
        }
    }
}
